package org.qortium.network.i2p;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin SAM v3 client that uses an external i2pd router as a socket-like I2P transport.
 *
 * <p>One instance == one I2P destination (one logical network). The control socket stays open for
 * the lifetime of the session; outbound {@code STREAM CONNECT} and the inbound {@code STREAM
 * FORWARD} each use their own socket per the SAM v3 spec. SAM streams are plain TCP to the bridge
 * that transparently carry data to/from the remote {@code .b32.i2p} peer, so the returned
 * {@link SocketChannel}s drop straight into Qortium's existing selector/peer machinery.
 *
 * <p>Protocol validated against i2pd SAM v3.1 on 2026-06-18. Note: {@code SESSION CREATE} and
 * {@code STREAM CONNECT} can take tens of seconds because i2pd builds/looks-up tunnels, hence the
 * generous read timeouts. See {@code docs/design/i2p-fallback-transport.md}.
 */
public class SamSession implements I2PStreamProvider {

    private static final Logger LOGGER = LogManager.getLogger(SamSession.class);

    private static final String B32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";
    private static final Pattern TOKEN = Pattern.compile("(\\S+?)=(\\S+)");
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9._~-]+");
    private static final Pattern B32_DESTINATION = Pattern.compile("[a-z2-7]{52}\\.b32\\.i2p", Pattern.CASE_INSENSITIVE);
    /** I2P base64 key/destination charset (standard base64 with -/~ for +//, optional = padding). */
    private static final Pattern I2P_KEY_TOKEN = Pattern.compile("[A-Za-z0-9~=-]+");

    /** TCP/SAM HELLO should be immediate on a healthy local router. */
    private static final int SAM_CONNECT_TIMEOUT_MS = 5_000;
    private static final int SAM_REPLY_TIMEOUT_MS = 10_000;
    /** Tunnel build / lease lookup can be slow; allow plenty of time for session & stream setup. */
    private static final int SETUP_TIMEOUT_MS = 120_000;
    /** SAM control/forward lines are short by spec; cap reads so a misbehaving peer can't exhaust memory. */
    private static final int MAX_LINE_LENGTH = 8192;

    private final String samHost;
    private final int samPort;
    private final String sessionId;   // SAM session nickname (unique per session on the router)
    private final Path keyFile;       // persists PUB/PRIV so our .b32.i2p is stable across restarts

    private final AtomicBoolean sessionUp = new AtomicBoolean(false);

    private SocketChannel controlChannel;     // owns the session; must stay open
    private SocketChannel forwardChannel;      // owns the STREAM FORWARD; must stay open
    private Thread controlReader;
    private volatile String localB32;

    public SamSession(String samHost, int samPort, String sessionId, Path keyFile) {
        if (samHost == null || samHost.isBlank())
            throw new IllegalArgumentException("SAM host cannot be blank");
        if (samPort <= 0 || samPort > 65535)
            throw new IllegalArgumentException("SAM port out of range: " + samPort);
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches())
            throw new IllegalArgumentException("Invalid SAM session ID");

        this.samHost = samHost;
        this.samPort = samPort;
        this.sessionId = sessionId;
        this.keyFile = Objects.requireNonNull(keyFile, "keyFile");
    }

    // ---- I2PStreamProvider ------------------------------------------------------------------

    @Override
    public synchronized void start() throws IOException {
        if (sessionUp.get())
            return;

        controlChannel = openSam();
        controlChannel.socket().setSoTimeout(SAM_REPLY_TIMEOUT_MS);
        InputStream in = controlChannel.socket().getInputStream();
        OutputStream out = controlChannel.socket().getOutputStream();

        hello(in, out);

        String[] keys = loadOrGenerateKeys(in, out); // [pub, priv]
        String pub = keys[0], priv = keys[1];
        this.localB32 = toB32(pub);

        controlChannel.socket().setSoTimeout(SETUP_TIMEOUT_MS);
        sendLine(out, "SESSION CREATE STYLE=STREAM ID=" + sessionId + " DESTINATION=" + priv);
        String reply = readLine(in);
        String result = token(reply, "RESULT");
        if (!"OK".equals(result))
            throw new IOException("SAM SESSION CREATE failed: " + reply);
        controlChannel.socket().setSoTimeout(0); // long-lived; reader blocks

        sessionUp.set(true);
        startControlReader(in);
        LOGGER.info("I2P session '{}' up, reachable at {}", sessionId, localB32);
    }

    @Override
    public String getLocalB32() {
        return sessionUp.get() ? localB32 : null;
    }

    @Override
    public boolean isSessionUp() {
        return sessionUp.get();
    }

    @Override
    public SocketChannel connect(String remoteB32) throws IOException {
        if (!isValidB32Destination(remoteB32)) {
            LOGGER.debug("Rejecting invalid I2P destination");
            return null;
        }

        if (!sessionUp.get())
            throw new IOException("I2P session not up");

        String normalizedRemoteB32 = remoteB32.toLowerCase(Locale.ROOT);
        LOGGER.info("I2P STREAM CONNECT '{}' -> {}", sessionId, normalizedRemoteB32);
        SocketChannel ch = openSam();
        try {
            ch.socket().setSoTimeout(SAM_REPLY_TIMEOUT_MS);
            InputStream in = ch.socket().getInputStream();
            OutputStream out = ch.socket().getOutputStream();
            hello(in, out);
            ch.socket().setSoTimeout(SETUP_TIMEOUT_MS);
            sendLine(out, "STREAM CONNECT ID=" + sessionId + " DESTINATION=" + normalizedRemoteB32 + " SILENT=false");
            String reply = readLine(in);
            if (!"OK".equals(token(reply, "RESULT"))) {
                ch.close();
                LOGGER.warn("I2P STREAM CONNECT '{}' -> {} failed: {}", sessionId, normalizedRemoteB32, reply);
                return null;
            }
            ch.socket().setSoTimeout(0);
            LOGGER.info("I2P STREAM CONNECT '{}' -> {} established", sessionId, normalizedRemoteB32);
            return ch; // now a transparent data stream; caller configures non-blocking + selector
        } catch (IOException e) {
            try { ch.close(); } catch (IOException ignored) { /* best effort */ }
            LOGGER.warn("I2P STREAM CONNECT '{}' -> {} failed: {}", sessionId, normalizedRemoteB32, e.getMessage());
            throw e;
        }
    }

    @Override
    public synchronized void startForward(int localPort) throws IOException {
        if (!sessionUp.get())
            throw new IOException("I2P session not up");
        if (localPort <= 0 || localPort > 65535)
            throw new IOException("Invalid I2P forward port: " + localPort);

        forwardChannel = openSam();
        InputStream in = forwardChannel.socket().getInputStream();
        OutputStream out = forwardChannel.socket().getOutputStream();
        forwardChannel.socket().setSoTimeout(SAM_REPLY_TIMEOUT_MS);
        hello(in, out);
        forwardChannel.socket().setSoTimeout(SETUP_TIMEOUT_MS);
        sendLine(out, "STREAM FORWARD ID=" + sessionId + " PORT=" + localPort + " SILENT=false");
        String reply = readLine(in);
        if (!"OK".equals(token(reply, "RESULT"))) {
            forwardChannel.close();
            throw new IOException("SAM STREAM FORWARD failed: " + reply);
        }
        forwardChannel.socket().setSoTimeout(0); // kept open for the session's lifetime
        LOGGER.info("I2P inbound forward for '{}' -> 127.0.0.1:{}", sessionId, localPort);
    }

    @Override
    public String readForwardedDestination(SocketChannel inbound) throws IOException {
        // SILENT=false: the first line on a forwarded connection is the remote peer's destination.
        String dest = readLine(inbound.socket().getInputStream());
        if (dest == null || dest.isBlank())
            throw new IOException("missing destination line on forwarded I2P connection");
        return toB32(dest.trim());
    }

    @Override
    public synchronized void close() {
        sessionUp.set(false);
        localB32 = null;
        if (controlReader != null)
            controlReader.interrupt();
        closeQuietly(forwardChannel);
        closeQuietly(controlChannel);
    }

    // ---- SAM plumbing -----------------------------------------------------------------------

    private SocketChannel openSam() throws IOException {
        SocketChannel channel = SocketChannel.open();
        channel.socket().connect(new InetSocketAddress(samHost, samPort), SAM_CONNECT_TIMEOUT_MS);
        return channel; // blocking
    }

    private void hello(InputStream in, OutputStream out) throws IOException {
        sendLine(out, "HELLO VERSION MIN=3.0 MAX=3.1");
        String reply = readLine(in);
        if (!"OK".equals(token(reply, "RESULT")))
            throw new IOException("SAM HELLO failed: " + reply);
    }

    /** Returns {pub, priv}, generating and persisting a fresh Ed25519 destination if none saved. */
    private String[] loadOrGenerateKeys(InputStream in, OutputStream out) throws IOException {
        if (Files.exists(keyFile)) {
            String pub = null, priv = null;
            for (String l : Files.readAllLines(keyFile)) {
                if (l.startsWith("PUB=")) pub = l.substring(4).trim();
                else if (l.startsWith("PRIV=")) priv = l.substring(5).trim();
            }
            // Validate before trusting: a malformed/tampered file must not feed stray tokens into the
            // SAM control line (SESSION CREATE ... DESTINATION=<priv>). Treat invalid as unreadable.
            if (isValidKeyToken(pub) && isValidKeyToken(priv))
                return new String[]{pub, priv};
            LOGGER.warn("I2P key file {} missing or malformed; regenerating", keyFile);
        }

        sendLine(out, "DEST GENERATE SIGNATURE_TYPE=7"); // 7 = EdDSA-SHA512-Ed25519
        String reply = readLine(in);
        String pub = token(reply, "PUB");
        String priv = token(reply, "PRIV");
        if (pub == null || priv == null)
            throw new IOException("SAM DEST GENERATE failed: " + reply);

        persistKeys(pub, priv);
        return new String[]{pub, priv};
    }

    /** @return true if {@code s} is a well-formed I2P base64 key token (no whitespace / control chars). */
    static boolean isValidKeyToken(String s) {
        return s != null && I2P_KEY_TOKEN.matcher(s).matches();
    }

    /**
     * Persist the destination keys with restrictive permissions and no broad-perms window: a temp file
     * is created and chmod'd to {@code rw-------} <em>before</em> the secret bytes are written, then
     * atomically moved into place. The containing directory is restricted too. Best effort on non-POSIX
     * filesystems (where POSIX perms are unsupported).
     */
    private void persistKeys(String pub, String priv) {
        Path tmp = null;
        try {
            Path parent = keyFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                trySetPosixPermissions(parent, "rwx------");
            }
            tmp = (parent != null)
                    ? Files.createTempFile(parent, "i2pkeys", ".tmp")
                    : Files.createTempFile("i2pkeys", ".tmp");
            trySetPosixPermissions(tmp, "rw-------"); // restrict BEFORE writing the secret
            Files.write(tmp, List.of("PUB=" + pub, "PRIV=" + priv), StandardCharsets.US_ASCII);
            try {
                Files.move(tmp, keyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, keyFile, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
            trySetPosixPermissions(keyFile, "rw-------");
        } catch (IOException e) {
            LOGGER.warn("Could not persist I2P keys to {}: {}", keyFile, e.getMessage());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort */ }
            }
        }
    }

    private static void trySetPosixPermissions(Path path, String perms) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem, or path no longer present; best effort
        }
    }

    private void startControlReader(InputStream in) {
        controlReader = new Thread(() -> {
            try {
                String line;
                while (sessionUp.get() && (line = readLine(in)) != null) {
                    if (line.startsWith("PING"))
                        // keep the session alive: reply PONG with the same payload
                        sendLine(controlChannel.socket().getOutputStream(), "PONG" + line.substring(4));
                }
            } catch (IOException e) {
                if (sessionUp.get())
                    LOGGER.warn("I2P session '{}' control connection lost: {}", sessionId, e.getMessage());
            } finally {
                sessionUp.set(false); // session is gone until restarted
            }
        }, "i2p-sam-" + sessionId);
        controlReader.setDaemon(true);
        controlReader.start();
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static void sendLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Reads one line (terminated by '\n'); byte-at-a-time so it never over-reads into stream data. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') bos.write(b);
            if (bos.size() > MAX_LINE_LENGTH)
                throw new IOException("SAM line exceeded " + MAX_LINE_LENGTH + " bytes");
        }
        if (b == -1 && bos.size() == 0)
            return null;
        return bos.toString(StandardCharsets.US_ASCII);
    }

    /** Extracts the value of a {@code KEY=VALUE} token from a SAM reply line, or null. */
    private static String token(String line, String key) {
        if (line == null)
            return null;
        Matcher m = TOKEN.matcher(line);
        while (m.find()) {
            if (m.group(1).equals(key))
                return m.group(2);
        }
        return null;
    }

    static boolean isValidB32Destination(String destination) {
        return destination != null && B32_DESTINATION.matcher(destination).matches();
    }

    /** Derives the {@code <base32>.b32.i2p} address from an I2P base64 destination. */
    static String toB32(String i2pBase64Dest) throws IOException {
        try {
            byte[] dest = i2pBase64Decode(i2pBase64Dest);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(dest);
            return base32(hash) + ".b32.i2p";
        } catch (Exception e) {
            throw new IOException("could not derive b32 from destination", e);
        }
    }

    private static byte[] i2pBase64Decode(String s) {
        // I2P base64 uses '-' and '~' instead of '+' and '/'.
        String std = s.replace('-', '+').replace('~', '/');
        int pad = (4 - std.length() % 4) % 4;
        std += "====".substring(0, pad);
        return Base64.getDecoder().decode(std);
    }

    /** RFC 4648 base32, lowercase, no padding — the I2P address form. */
    private static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(B32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0)
            sb.append(B32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        return sb.toString();
    }

    private static void closeQuietly(SocketChannel ch) {
        if (ch != null) {
            try { ch.close(); } catch (IOException ignored) { /* best effort */ }
        }
    }
}
