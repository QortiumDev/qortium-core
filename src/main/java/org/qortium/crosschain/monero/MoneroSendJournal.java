package org.qortium.crosschain.monero;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import static org.qortium.crosschain.monero.MoneroSendContracts.*;

/** Encrypted atomic snapshots. Not wired to the read runtime or any native send operation. */
final class MoneroSendJournal implements AutoCloseable {
    static final int MAX_FILE = 16 * 1024 * 1024;
    private static final int MAGIC = 0x584d5231;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    static {
        JSON.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxNestingDepth(16).maxStringLength(2 * 1024 * 1024).maxNumberLength(20).build());
    }
    enum Barrier { TEMP_WRITTEN, TEMP_SYNCED, RENAMED, DIRECTORY_SYNCED }
    interface Fault { void at(Barrier barrier) throws IOException; }
    static final Fault NO_FAULT = barrier -> { };
    static final class Failure extends RuntimeException {
        Failure() { super("XMR send storage requires recovery"); } // no paths, keys or parser/native diagnostics
    }
    /** A cooperating process must hold this before ANY wallet/cache/journal access in the future send runtime. */
    static final class Root implements AutoCloseable {
        final Path path;
        final Map<String, MoneroSendJournal> journals = new HashMap<>();
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed, failed;
        private final Object lockIdentity;
        private Root(Path path, FileChannel channel, FileLock lock) throws IOException {
            this.path = path; this.channel = channel; this.lock = lock;
            lockIdentity = Files.readAttributes(path.resolve(".send.lock"), java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            if (lockIdentity == null) throw new IOException();
        }
        static Root open(Path path) {
            FileChannel channel = null;
            try {
                path = path.toAbsolutePath().normalize();
                for (Path part = path; part != null; part = part.getParent()) if (Files.isSymbolicLink(part)) throw new IOException();
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                    syncDirectory(path.getParent());
                }
                privateDirectory(path);
                Path file = path.resolve(".send.lock");
                channel = FileChannel.open(file, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                privateFile(file);
                FileLock lock = channel.tryLock();
                if (lock == null) throw new IOException();
                syncDirectory(path);
                return new Root(path, channel, lock);
            } catch (Exception e) {
                if (channel != null) try { channel.close(); } catch (IOException ignored) { }
                throw new Failure();
            }
        }
        synchronized void check() {
            if (closed || failed || !lock.isValid()) throw new Failure();
            try {
                privateDirectory(path); privateFile(path.resolve(".send.lock"));
                Object identity = Files.readAttributes(path.resolve(".send.lock"), java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
                if (!lockIdentity.equals(identity)) throw new IOException();
            } catch (IOException e) { failed = true; throw new Failure(); }
        }
        synchronized MoneroSendJournal openWallet(String walletId, byte[] key) {
            check(); hex(walletId, 64); require(key != null && key.length == 32);
            if (journals.containsKey(walletId)) throw new Failure();
            MoneroSendJournal journal = new MoneroSendJournal(this, walletId, key);
            journals.put(walletId, journal); return journal;
        }
        synchronized void released(String id, MoneroSendJournal journal) { journals.remove(id, journal); }
        @Override public void close() {
            List<MoneroSendJournal> owned;
            synchronized (this) {
                if (closed) return;
                closed = true; owned = List.copyOf(journals.values());
            }
            // Integration must stop/join its native worker before releasing this process-lifetime lock.
            // Do not hold the root monitor while waiting on a journal monitor.
            for (MoneroSendJournal journal : owned) journal.close();
            try { try { lock.release(); } finally { channel.close(); } } catch (IOException e) { throw new Failure(); }
        }
    }
    private final Root root;
    private final String walletId;
    private final Path directory, file;
    private final byte[] key;
    private Ledger current;
    private boolean failed, closed, machineClaimed;
    private Fault fault = NO_FAULT;
    private MoneroSendJournal(Root root, String walletId, byte[] key) {
        this.root = root; this.walletId = walletId; this.key = key.clone();
        directory = root.path.resolve(walletId); file = directory.resolve("ledger.aesgcm");
        try {
            boolean existing = Files.exists(directory, LinkOption.NOFOLLOW_LINKS);
            if (!existing) {
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                syncDirectory(root.path);
                current = new Ledger(1, 0, Map.of());
                persist(current);
            } else {
                privateDirectory(directory); privateFile(file);
                try (var files = Files.list(directory)) {
                    for (Path child : files.toList()) {
                        if (!child.equals(file) && !child.equals(directory.resolve("ledger.pending"))) throw new IOException();
                        privateFile(child);
                    }
                }
                current = decrypt(readBounded(file));
                // A pre-rename temporary write cannot have authorized a native effect. The durable main file wins.
                if (Files.deleteIfExists(directory.resolve("ledger.pending"))) syncDirectory(directory);
            }
        } catch (Exception e) { Arrays.fill(this.key, (byte) 0); throw new Failure(); }
    }
    private static void privateDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))) throw new IOException();
    }
    private static void privateFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------"))) throw new IOException();
        // Refuse an unexpected hard link as well as symlinks. Initial native qualification is Linux only.
        if (((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) throw new IOException();
    }
    private static byte[] readBounded(Path path) throws IOException {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FILE + 1);
            if (bytes.length > MAX_FILE) throw new IOException(); return bytes;
        }
    }
    private static void syncDirectory(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
    }
    private byte[] aad(long sequence) { return ("Qortium/XMR/mainnet/derivation-v1/send-journal/v1\n" + walletId + "\n" + sequence).getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private byte[] encrypt(Ledger ledger) throws Exception {
        byte[] plain = JSON.writeValueAsBytes(ledger);
        try {
            if (plain.length > MAX_FILE - 64) throw new IOException();
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(ledger.sequence()));
            byte[] encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(24 + encrypted.length).putInt(MAGIC).putLong(ledger.sequence()).put(nonce).put(encrypted).array();
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    private Ledger decrypt(byte[] bytes) throws Exception {
        if (bytes.length < 40) throw new IOException();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC) throw new IOException();
        long sequence = buffer.getLong(); if (sequence < 0) throw new IOException();
        byte[] nonce = new byte[12]; buffer.get(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad(sequence));
        byte[] plain = cipher.doFinal(bytes, 24, bytes.length - 24);
        try {
            Ledger ledger = JSON.readValue(plain, Ledger.class);
            if (ledger.sequence() != sequence) throw new IOException(); return ledger;
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    private void persist(Ledger ledger) throws Exception {
        privateDirectory(root.path); privateDirectory(directory);
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) privateFile(file);
        Path temp = directory.resolve("ledger.pending");
        byte[] encrypted = encrypt(ledger);
        try (FileChannel channel = FileChannel.open(temp, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            ByteBuffer buffer = ByteBuffer.wrap(encrypted); while (buffer.hasRemaining()) channel.write(buffer);
            fault.at(Barrier.TEMP_WRITTEN); channel.force(true); fault.at(Barrier.TEMP_SYNCED);
        }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        fault.at(Barrier.RENAMED); syncDirectory(directory); fault.at(Barrier.DIRECTORY_SYNCED);
    }
    synchronized void check() { root.check(); if (closed || failed) throw new Failure(); }
    synchronized void claimMachine() { check(); if (machineClaimed) throw new Failure(); machineClaimed = true; }
    synchronized Ledger read() { check(); return current; }
    synchronized void replace(Map<String, Entry> entries) {
        check();
        try {
            Ledger next = new Ledger(1, Math.addExact(current.sequence(), 1), entries);
            persist(next); current = next;
        } catch (Exception e) { failed = true; throw new Failure(); }
    }
    // Failure injection never changes production ordering; package-private and no runtime caller.
    synchronized void inject(Fault fault) { this.fault = Objects.requireNonNull(fault); }
    @Override public synchronized void close() {
        closed = true; current = null; Arrays.fill(key, (byte) 0); root.released(walletId, this);
    }
}
