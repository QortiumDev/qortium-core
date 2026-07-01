package org.qortium.network.i2p;

import org.junit.After;
import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic, hermetic coverage of {@link SamSession}'s SAM v3 wire handling and key persistence
 * against an in-process fake SAM bridge — no live i2pd required, so these run in normal CI (unlike
 * {@code SamSessionIntegrationTests}). Complements the protocol-validated-against-live-i2pd note in
 * the class doc by guarding the parsing/framing/persistence logic against regressions.
 */
public class SamSessionFakeSamTests {

	// Valid I2P/standard base64 tokens (length multiple of 4 so they decode cleanly).
	private static final String FAKE_PUB = "AAAABBBBCCCCDDDDEEEEFFFF";
	private static final String FAKE_PRIV = "ZZZZYYYYXXXXWWWWVVVVUUUU";
	// Independently computed: base32(sha256(base64decode(FAKE_PUB))), lowercase, no padding.
	private static final String EXPECTED_B32 = "talqfkgavfsju3v6ssyr5yem46bi6vyc5kpjah6nijdcqjk3nb4q.b32.i2p";
	// Any well-formed remote destination for STREAM CONNECT.
	private static final String REMOTE_B32 = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";

	private FakeSamServer server;
	private SamSession session;
	private Path tempDir;

	@After
	public void after() throws Exception {
		if (session != null) session.close();
		if (server != null) server.close();
		if (tempDir != null) deleteRecursively(tempDir);
	}

	private SamSession newSession(Path keyFile) throws IOException {
		this.server = new FakeSamServer(FAKE_PUB, FAKE_PRIV, "OK", true);
		SamSession s = new SamSession("127.0.0.1", server.port(), "qortium-fakesam-test", keyFile);
		// The fake SAM server answers SESSION CREATE instantly; disable the zombie-build timing guard so
		// the hermetic test is not rejected as a no-tunnel-build destination.
		s.setMinRealSessionBuildMillisForTesting(0);
		return s;
	}

	private Path freshKeyDir() throws IOException {
		this.tempDir = Files.createTempDirectory("samsession-test");
		return tempDir;
	}

	// ---- start() / key handling -------------------------------------------------------------

	@Test
	public void testStartHandshakesDerivesB32AndPersistsKeys() throws Exception {
		Path keyFile = freshKeyDir().resolve("nested").resolve("chain.keys");
		session = newSession(keyFile);

		session.start();

		assertTrue(session.isSessionUp());
		assertEquals(EXPECTED_B32, session.getLocalB32());
		assertTrue("DEST GENERATE expected when no key file exists", server.destGenerateSeen());
		assertTrue("key file should be persisted", Files.exists(keyFile));
		String contents = Files.readString(keyFile);
		assertTrue(contents.contains("PUB=" + FAKE_PUB));
		assertTrue(contents.contains("PRIV=" + FAKE_PRIV));
	}

	@Test
	public void testStartReusesPersistedKeysWithoutRegenerating() throws Exception {
		Path keyFile = freshKeyDir().resolve("chain.keys");
		Files.write(keyFile, List.of("PUB=" + FAKE_PUB, "PRIV=" + FAKE_PRIV), StandardCharsets.US_ASCII);
		session = newSession(keyFile);

		session.start();

		assertTrue(session.isSessionUp());
		assertEquals(EXPECTED_B32, session.getLocalB32());
		assertFalse("must not DEST GENERATE when a valid key file exists", server.destGenerateSeen());
	}

	@Test
	public void testStartRegeneratesWhenKeyFileIsMalformed() throws Exception {
		Path keyFile = freshKeyDir().resolve("chain.keys");
		// PRIV carries an injected extra token (whitespace) — must be rejected, not fed to SAM.
		Files.write(keyFile, List.of("PUB=" + FAKE_PUB, "PRIV=" + FAKE_PRIV + " EXTRA=evil"),
				StandardCharsets.US_ASCII);
		session = newSession(keyFile);

		session.start();

		assertTrue(session.isSessionUp());
		assertTrue("malformed key file should force regeneration", server.destGenerateSeen());
	}

	// ---- connect() / STREAM CONNECT ---------------------------------------------------------

	@Test
	public void testConnectStreamsBytesEndToEnd() throws Exception {
		session = newSession(freshKeyDir().resolve("chain.keys"));
		session.start();

		SocketChannel ch = session.connect(REMOTE_B32);
		assertNotNull(ch);
		try {
			ch.socket().setSoTimeout(5_000);
			OutputStream out = ch.socket().getOutputStream();
			InputStream in = ch.socket().getInputStream();
			byte[] payload = "qortium-i2p".getBytes(StandardCharsets.US_ASCII);
			out.write(payload);
			out.flush();
			byte[] echoed = new byte[payload.length];
			readFully(in, echoed);
			assertEquals("qortium-i2p", new String(echoed, StandardCharsets.US_ASCII));
		} finally {
			ch.close();
		}
	}

	@Test
	public void testConnectReturnsNullOnStreamStatusError() throws Exception {
		this.server = new FakeSamServer(FAKE_PUB, FAKE_PRIV, "CANT_REACH_PEER", false);
		Path keyFile = freshKeyDir().resolve("chain.keys");
		session = new SamSession("127.0.0.1", server.port(), "qortium-fakesam-test", keyFile);
		session.setMinRealSessionBuildMillisForTesting(0);
		session.start();

		assertNull(session.connect(REMOTE_B32));
	}

	@Test
	public void testConnectRejectsInvalidDestinationWithoutTouchingSam() throws Exception {
		session = newSession(freshKeyDir().resolve("chain.keys"));
		session.start();

		assertNull(session.connect("not-a-valid-b32"));
	}

	// ---- startForward() / STREAM FORWARD ----------------------------------------------------

	@Test
	public void testStartForwardSucceeds() throws Exception {
		session = newSession(freshKeyDir().resolve("chain.keys"));
		session.start();

		// Should complete without throwing (fake replies STREAM STATUS RESULT=OK).
		session.startForward(34567);
	}

	@Test
	public void testControlConnectionLossMarksSessionDownAndFiresCallback() throws Exception {
		this.server = new FakeSamServer(FAKE_PUB, FAKE_PRIV, "OK", true);
		AtomicInteger downCallbacks = new AtomicInteger();
		session = new SamSession("127.0.0.1", server.port(), "qortium-fakesam-test",
				freshKeyDir().resolve("chain.keys"), null, downCallbacks::incrementAndGet);
		session.setMinRealSessionBuildMillisForTesting(0);
		session.start();

		assertTrue(session.isSessionUp());
		assertEquals(EXPECTED_B32, session.getLocalB32());

		server.closeConnections();

		waitUntil("session down callback", () -> downCallbacks.get() == 1 && !session.isSessionUp());
		assertFalse(session.isSessionUp());
		assertNull(session.getLocalB32());
		assertEquals(1, downCallbacks.get());
	}

	@Test
	public void testExplicitCloseDoesNotFireSessionDownCallback() throws Exception {
		this.server = new FakeSamServer(FAKE_PUB, FAKE_PRIV, "OK", true);
		AtomicInteger downCallbacks = new AtomicInteger();
		session = new SamSession("127.0.0.1", server.port(), "qortium-fakesam-test",
				freshKeyDir().resolve("chain.keys"), null, downCallbacks::incrementAndGet);
		session.setMinRealSessionBuildMillisForTesting(0);
		session.start();

		assertTrue(session.isSessionUp());

		session.close();

		waitUntil("explicit close", () -> !session.isSessionUp());
		assertEquals(0, downCallbacks.get());
	}

	// ---- readForwardedDestination() ---------------------------------------------------------

	@Test
	public void testReadForwardedDestinationDerivesB32FromLeadingLine() throws Exception {
		session = newSession(freshKeyDir().resolve("chain.keys"));

		try (ServerSocketChannel srv = ServerSocketChannel.open()) {
			srv.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			SocketChannel writer = SocketChannel.open(srv.getLocalAddress());
			SocketChannel inbound = srv.accept();
			try {
				// SILENT=false: i2pd writes the remote destination as the first line.
				writer.socket().getOutputStream().write((FAKE_PUB + "\n").getBytes(StandardCharsets.US_ASCII));
				writer.socket().getOutputStream().flush();

				assertEquals(EXPECTED_B32, session.readForwardedDestination(inbound));
			} finally {
				writer.close();
				inbound.close();
			}
		}
	}

	// ---- pure helpers -----------------------------------------------------------------------

	@Test
	public void testToB32KnownVector() throws Exception {
		assertEquals(EXPECTED_B32, SamSession.toB32(FAKE_PUB));
	}

	@Test
	public void testIsValidKeyTokenRejectsWhitespaceAndNull() {
		assertTrue(SamSession.isValidKeyToken("AbC123~-="));
		assertFalse(SamSession.isValidKeyToken("has space"));
		assertFalse(SamSession.isValidKeyToken("with\ttab"));
		assertFalse(SamSession.isValidKeyToken(""));
		assertFalse(SamSession.isValidKeyToken(null));
	}

	// ---- test plumbing ----------------------------------------------------------------------

	private static void readFully(InputStream in, byte[] buf) throws IOException {
		int off = 0;
		while (off < buf.length) {
			int n = in.read(buf, off, buf.length - off);
			if (n < 0) throw new IOException("unexpected EOF");
			off += n;
		}
	}

	private static void waitUntil(String description, BooleanSupplier condition) throws Exception {
		long deadline = System.currentTimeMillis() + 5_000L;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean())
				return;
			Thread.sleep(25L);
		}
		throw new AssertionError("Timed out waiting for " + description);
	}

	private static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) return;
		try (var paths = Files.walk(dir)) {
			paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
					.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
		}
	}

	/**
	 * Minimal in-process SAM v3 bridge: per connection it answers HELLO, then dispatches DEST GENERATE
	 * / SESSION CREATE / STREAM CONNECT / STREAM FORWARD with canned replies. A STREAM CONNECT
	 * connection optionally becomes a byte echo pipe so connect() data flow can be asserted.
	 */
	private static final class FakeSamServer implements Closeable {
		private final ServerSocket serverSocket;
		private final String pub;
		private final String priv;
		private final String streamStatusResult;
		private final boolean echoData;
		private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
		private volatile boolean destGenerateSeen = false;

		FakeSamServer(String pub, String priv, String streamStatusResult, boolean echoData) throws IOException {
			this.pub = pub;
			this.priv = priv;
			this.streamStatusResult = streamStatusResult;
			this.echoData = echoData;
			this.serverSocket = new ServerSocket();
			this.serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			Thread acceptThread = new Thread(this::acceptLoop, "fake-sam-accept");
			acceptThread.setDaemon(true);
			acceptThread.start();
		}

		int port() {
			return serverSocket.getLocalPort();
		}

		boolean destGenerateSeen() {
			return destGenerateSeen;
		}

		void closeConnections() {
			synchronized (sockets) {
				for (Socket s : sockets) {
					try { s.close(); } catch (IOException ignored) { }
				}
			}
		}

		private void acceptLoop() {
			try {
				while (!serverSocket.isClosed()) {
					Socket s = serverSocket.accept();
					sockets.add(s);
					Thread t = new Thread(() -> handle(s), "fake-sam-conn");
					t.setDaemon(true);
					t.start();
				}
			} catch (IOException ignored) {
				// server closed
			}
		}

		private void handle(Socket s) {
			try {
				InputStream in = s.getInputStream();
				OutputStream out = s.getOutputStream();
				String line;
				while ((line = readLine(in)) != null) {
					if (line.startsWith("HELLO")) {
						writeLine(out, "HELLO REPLY RESULT=OK VERSION=3.1");
					} else if (line.startsWith("DEST GENERATE")) {
						destGenerateSeen = true;
						writeLine(out, "DEST REPLY PUB=" + pub + " PRIV=" + priv);
					} else if (line.startsWith("SESSION CREATE")) {
						writeLine(out, "SESSION STATUS RESULT=OK DESTINATION=" + priv);
						// control connection stays open for the session's lifetime
					} else if (line.startsWith("NAMING LOOKUP")) {
						// Post-session LeaseSet publication self-check (verifyLeaseSetPublished): the real
						// router resolves our own b32 once published. Report OK so the check passes promptly
						// instead of blocking on the SAM reply timeout.
						writeLine(out, "NAMING REPLY RESULT=OK NAME=ME VALUE=" + pub);
					} else if (line.startsWith("STREAM CONNECT")) {
						writeLine(out, "STREAM STATUS RESULT=" + streamStatusResult);
						if ("OK".equals(streamStatusResult) && echoData) {
							int b;
							while ((b = in.read()) != -1) {
								out.write(b);
								out.flush();
							}
						}
						return;
					} else if (line.startsWith("STREAM FORWARD")) {
						writeLine(out, "STREAM STATUS RESULT=" + streamStatusResult);
						return;
					}
				}
			} catch (IOException ignored) {
				// connection closed
			} finally {
				try { s.close(); } catch (IOException ignored) { }
			}
		}

		private static String readLine(InputStream in) throws IOException {
			StringBuilder sb = new StringBuilder();
			int b;
			while ((b = in.read()) != -1) {
				if (b == '\n') break;
				if (b != '\r') sb.append((char) b);
			}
			if (b == -1 && sb.length() == 0) return null;
			return sb.toString();
		}

		private static void writeLine(OutputStream out, String line) throws IOException {
			out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
			out.flush();
		}

		@Override
		public void close() throws IOException {
			serverSocket.close();
			closeConnections();
		}
	}
}
