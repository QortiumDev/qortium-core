package org.qortium.crosschain;

import org.json.JSONObject;
import org.qortium.crypto.Crypto;
import org.qortium.utils.Base58;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;

/** Durable send reservations, deliberately outside recoverable native wallet namespaces. */
public final class PirateChainSendJournal implements AutoCloseable {
    public enum Phase { ACCEPTED, NATIVE_STARTED, BROADCAST, FAILED, UNRESOLVED }
    public record Operation(String operationId, String walletIdentityHash, String network,
            String idempotencyKey, String fingerprint, Phase phase, String txid, String reason,
            long createdAt, long updatedAt) {
        public boolean blocksSpending() { return phase == Phase.ACCEPTED || phase == Phase.NATIVE_STARTED || phase == Phase.UNRESOLVED; }
    }
    public static class StorageException extends IOException {
        public StorageException() { super("ARRR_SEND_STORAGE_UNAVAILABLE"); }
    }
    public static class ConflictException extends Exception {
        public ConflictException() { super("ARRR_SEND_REQUEST_CONFLICT"); }
    }
    private final Path root;
    private final String network;
    private final Map<String, Operation> operations = new HashMap<>();
    private final Set<String> corruptWallets = new HashSet<>();
    private boolean closed;
    private final Set<String> corruptOperationIds = new HashSet<>();
    public String network() { return network; }
    private final ExecutorService persistence;
    private final FileChannel lockChannel;
    private final FileLock lock;

    public PirateChainSendJournal(Path root, String network) throws IOException {
        this(root, network, Executors.newSingleThreadExecutor(task -> {
            Thread writer = new Thread(task, "ARRR send journal writer"); writer.setDaemon(true); return writer;
        }));
    }
    PirateChainSendJournal(Path root, String network, ExecutorService persistence) throws IOException {
        this.persistence = persistence;
        this.root = root.toAbsolutePath().normalize();
        this.network = network;
        secureDirectory(this.root);
        FileChannel channel = FileChannel.open(this.root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new StorageException();
            load();
        } catch (Exception failure) {
            if (acquired != null) acquired.release();
            channel.close();
            persistence.shutdown();
            throw new StorageException();
        }
        this.lockChannel = channel;
        this.lock = acquired;
    }

    public static String walletIdentity(String entropy58) {
        byte[] entropy = Base58.decode(entropy58);
        if (entropy == null || entropy.length != 32) throw new IllegalArgumentException("Invalid wallet identity");
        try { return Base58.encode(Crypto.digest(entropy)); }
        finally { Arrays.fill(entropy, (byte) 0); }
    }

    /** Length prefixes prevent ambiguous field concatenation; no spend authority enters the digest. */
    public static String fingerprint(String network, String recipient, long amount, String memo, long fee) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(1);
                for (String field : new String[] { network, recipient, Long.toString(amount), memo, "FIXED", Long.toString(fee) }) {
                    if (field == null) out.writeInt(-1);
                    else { byte[] encoded = field.getBytes(StandardCharsets.UTF_8); out.writeInt(encoded.length); out.write(encoded); }
                }
            }
            return HexFormat.of().formatHex(Crypto.digest(bytes.toByteArray()));
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public synchronized Operation existing(String wallet, String key, String fingerprint) throws IOException, ConflictException {
        requireHealthy(wallet);
        for (Operation op : operations.values()) {
            if (op.walletIdentityHash.equals(wallet) && op.idempotencyKey.equals(key)) {
                if (!op.fingerprint.equals(fingerprint)) throw new ConflictException();
                return op;
            }
        }
        return null;
    }

    /** Journal-only recovery after the HTTP response was lost; never invokes native code. */
    public synchronized Operation lookup(String wallet, String key) throws IOException {
        requireHealthy(wallet);
        return operations.values().stream()
                .filter(op -> op.walletIdentityHash.equals(wallet) && op.idempotencyKey.equals(key))
                .findFirst().orElse(null);
    }

    public synchronized Operation admit(String wallet, String key, String fingerprint) throws IOException, ConflictException {
        Operation existing = existing(wallet, key, fingerprint);
        if (existing != null) return existing;
        if (blocking(wallet) != null) throw new ConflictException();
        long now = System.currentTimeMillis();
        Operation op = new Operation(UUID.randomUUID().toString(), wallet, network, key, fingerprint,
                Phase.ACCEPTED, null, null, now, now);
        persist(op);
        operations.put(op.operationId, op);
        return op;
    }

    public synchronized Operation get(String id) throws IOException {
        if (closed || corruptOperationIds.contains(id)) throw new StorageException();
        Operation op = operations.get(id);
        if (op == null && !corruptWallets.isEmpty()) throw new StorageException();
        if (op != null) requireHealthy(op.walletIdentityHash);
        return op;
    }

    public synchronized String blocking(String wallet) throws IOException {
        requireHealthy(wallet);
        return operations.values().stream().filter(op -> op.walletIdentityHash.equals(wallet) && op.blocksSpending())
                .map(Operation::operationId).findFirst().orElse(null);
    }

    public synchronized Operation transition(String id, Phase phase, String txid, String reason) throws IOException {
        Operation previous = get(id);
        if (previous == null) throw new StorageException();
        boolean legal = switch (previous.phase) {
            case ACCEPTED -> phase == Phase.NATIVE_STARTED || phase == Phase.FAILED;
            case NATIVE_STARTED -> phase == Phase.BROADCAST || phase == Phase.UNRESOLVED;
            // A late worker may still deliver a trustworthy txid after the caller timed out.
            case UNRESOLVED -> phase == Phase.BROADCAST;
            default -> false;
        };
        if (!legal) return previous;
        if (phase == Phase.BROADCAST && (txid == null || !txid.matches("[0-9a-f]{64}"))) throw new StorageException();
        if (phase != Phase.BROADCAST && txid != null) throw new StorageException();
        if (reason != null && !reason.matches("[A-Z0-9_]{1,80}")) throw new StorageException();
        Operation next = new Operation(id, previous.walletIdentityHash, network, previous.idempotencyKey,
                previous.fingerprint, phase, txid, reason, previous.createdAt, Math.max(previous.updatedAt, System.currentTimeMillis()));
        persist(next);
        operations.put(id, next);
        return next;
    }

    private void requireHealthy(String wallet) throws IOException {
        if (closed || wallet == null || !wallet.matches("[1-9A-HJ-NP-Za-km-z]{32,44}") || corruptWallets.contains(wallet))
            throw new StorageException();
    }

    private void load() throws IOException {
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.toList()) {
                String wallet = dir.getFileName().toString();
                if (wallet.equals(".lock")) continue;
                requireHealthy(wallet);
                try {
                    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new StorageException();
                    Set<String> keys = new HashSet<>();
                    try (var files = Files.list(dir)) {
                        for (Path file : files.toList()) {
                            if (file.getFileName().toString().startsWith(".pending-")) continue;
                            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 8192) throw new StorageException();
                            JSONObject envelope = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
                            String payload = envelope.getString("payload");
                            if (!HexFormat.of().formatHex(Crypto.digest(payload.getBytes(StandardCharsets.UTF_8)))
                                    .equals(envelope.getString("sha256"))) throw new StorageException();
                            JSONObject data = new JSONObject(payload);
                            if (data.getInt("version") != 1) throw new StorageException();
                            Operation op = new Operation(data.getString("operationId"), data.getString("walletIdentityHash"),
                                    data.getString("network"), data.getString("idempotencyKey"), data.getString("fingerprint"),
                                    Phase.valueOf(data.getString("phase")), nullable(data, "txid"), nullable(data, "reason"),
                                    data.getLong("createdAt"), data.getLong("updatedAt"));
                            if (!wallet.equals(op.walletIdentityHash) || !network.equals(op.network)
                                    || !uuid(op.operationId) || !uuid(op.idempotencyKey)
                                    || !file.getFileName().toString().equals(op.operationId + ".json")
                                    || !op.fingerprint.matches("[0-9a-f]{64}") || !keys.add(op.idempotencyKey)
                                    || operations.containsKey(op.operationId) || op.createdAt <= 0 || op.updatedAt < op.createdAt
                                    || (op.phase == Phase.BROADCAST ? op.txid == null || !op.txid.matches("[0-9a-f]{64}") : op.txid != null))
                                throw new StorageException();
                            operations.put(op.operationId, op);
                        }
                    }
                } catch (Exception invalid) {
                    corruptWallets.add(wallet);
                    try (var files = Files.list(dir)) {
                        for (Path file : files.toList()) {
                            String name = file.getFileName().toString();
                            if (name.endsWith(".json") && uuid(name.substring(0, name.length() - 5)))
                                corruptOperationIds.add(name.substring(0, name.length() - 5));
                        }
                    }
                }
            }
        }
        // No replay on restart, including an ACCEPTED record whose worker never began.
        for (Operation op : List.copyOf(operations.values())) {
            if (corruptWallets.contains(op.walletIdentityHash)) continue;
            if (op.phase == Phase.ACCEPTED) transition(op.operationId, Phase.FAILED, null, "INTERRUPTED_BEFORE_EXECUTION");
            if (op.phase == Phase.NATIVE_STARTED) transition(op.operationId, Phase.UNRESOLVED, null, "ARRR_SEND_OUTCOME_UNKNOWN");
        }
    }

    private static boolean uuid(String value) { return value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
    private static String nullable(JSONObject json, String key) { return json.isNull(key) ? null : json.getString(key); }

    /** Native cancellation must never interrupt the filesystem transaction that captures a txid. */
    private void persist(Operation op) throws IOException {
        final Future<?> write;
        try { write = persistence.submit(() -> { persistOnWriter(op); return null; }); }
        catch (RejectedExecutionException stopped) { throw new StorageException(); }
        boolean interrupted = false;
        try {
            for (;;) {
                try { write.get(); return; }
                catch (InterruptedException cancellation) { interrupted = true; }
                catch (ExecutionException failure) { throw new StorageException(); }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private void persistOnWriter(Operation op) throws IOException {
        Path dir = root.resolve(op.walletIdentityHash);
        Path temporary = null;
        try {
            requireHealthy(op.walletIdentityHash);
            if (!uuid(op.idempotencyKey) || !op.fingerprint.matches("[0-9a-f]{64}")) throw new StorageException();
            secureDirectory(dir);
            JSONObject data = new JSONObject().put("version", 1).put("operationId", op.operationId)
                    .put("walletIdentityHash", op.walletIdentityHash).put("network", op.network)
                    .put("idempotencyKey", op.idempotencyKey).put("fingerprint", op.fingerprint)
                    .put("phase", op.phase.name()).put("txid", op.txid == null ? JSONObject.NULL : op.txid)
                    .put("reason", op.reason == null ? JSONObject.NULL : op.reason)
                    .put("createdAt", op.createdAt).put("updatedAt", op.updatedAt);
            String payload = data.toString();
            byte[] content = new JSONObject().put("payload", payload).put("sha256",
                    HexFormat.of().formatHex(Crypto.digest(payload.getBytes(StandardCharsets.UTF_8))))
                    .toString().getBytes(StandardCharsets.UTF_8);
            temporary = Files.createTempFile(dir, ".pending-", ".json");
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temporary, dir.resolve(op.operationId + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(dir);
        } catch (Exception failure) {
            corruptWallets.add(op.walletIdentityHash);
            throw new StorageException();
        } finally { if (temporary != null) Files.deleteIfExists(temporary); }
    }

    private static void secureDirectory(Path dir) throws IOException {
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new StorageException();
            return;
        }
        secureDirectory(dir.getParent());
        try { Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
        catch (UnsupportedOperationException unsupported) { Files.createDirectory(dir); }
        forceDirectory(dir.getParent());
        forceDirectory(dir);
    }
    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
    }
    @Override public synchronized void close() throws IOException { if (closed) return; closed = true; persistence.shutdown(); try { lock.release(); } finally { lockChannel.close(); } }
}
