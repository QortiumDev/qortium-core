package org.qortium.crosschain.monero;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.qortium.crosschain.monero.MoneroSendContracts.*;

public class MoneroSendJournalTests {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    static final String WALLET = "a".repeat(64), OTHER = "b".repeat(64), ADDRESS = "4".repeat(95), HASH = "c".repeat(64);
    static final String SESSION = "00000000-0000-4000-8000-000000000001";
    static byte[] key() { byte[] key = new byte[32]; Arrays.fill(key, (byte) 7); return key; }
    static Request request() { return new Request(UUID.randomUUID().toString(), ADDRESS, "9007199254740993"); }
    static Candidate candidate() { return new Candidate(ADDRESS, "9007199254740993", "100", HASH, "deadbeef".repeat(40), "cafe".repeat(30), List.of("d".repeat(64))); }
    Path path() { return temp.getRoot().toPath().resolve("root"); }
    static class Fixture implements AutoCloseable {
        final MoneroSendJournal.Root root;
        MoneroSendJournal journal;
        MoneroSendMachine machine;
        final AtomicLong wall = new AtomicLong(1_000_000), nano = new AtomicLong(1_000_000);
        Fixture(Path path) { root = MoneroSendJournal.Root.open(path); reopen(); }
        void reopen() {
            if (journal != null) journal.close();
            journal = root.openWallet(WALLET, key()); machine = new MoneroSendMachine(journal, SESSION, wall::get, nano::get);
        }
        MoneroSendMachine.Admission prepare(Request request) { return machine.prepare(request, SESSION); }
        View prepared(Request request) { var admitted = prepare(request); return machine.finishPreparation(admitted.work(), candidate()); }
        MoneroSendMachine.Admission commit(Request request, View quote) { return machine.commit(request.operationId(), quote.quoteDigest(), SESSION); }
        void broadcast(Request request) { var quote = prepared(request); var relay = commit(request, quote); machine.takeRelay(relay.work()); machine.finishRelay(relay.work(), HASH); }
        void confirmed(Request request) { broadcast(request); observe(request, 10, true); }
        void observe(Request request, long depth, boolean unlocked) {
            machine.reconcile(machine.beginReconciliation(SESSION), Map.of(request.operationId(), new MoneroSendMachine.Observation(HASH, true, depth, unlocked, false)), SESSION);
        }
        public void close() { root.close(); }
    }
    @Test public void cancellationBeforeAdmissionSurvivesRestartAndNeverMintsWork() {
        try (var f = new Fixture(path())) {
            Request request = request();
            assertEquals(State.CANCELLED, f.machine.cancel(request.operationId(), SESSION).state());
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.prepare(request));
            f.reopen();
            assertEquals(State.CANCELLED, f.machine.status(request.operationId(), SESSION).state());
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.prepare(request));
            assertFalse(f.machine.walletHeld(SESSION));
            Request later = request(); f.broadcast(later); f.reopen();
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.machine.cancel(later.operationId(), SESSION));
            assertEquals(State.UNKNOWN, f.machine.status(later.operationId(), SESSION).state());
            assertTrue(f.machine.walletHeld(SESSION));
        }
    }
    @Test public void contractsRejectCoercionAndBounds() {
        for (String amount : List.of("0", "01", "-1", "1.0", "1e4", "18446744073709551616", " 1"))
            assertThrows(IllegalArgumentException.class, () -> new Request(UUID.randomUUID().toString(), ADDRESS, amount));
        assertEquals(MAX_ATOMIC, atomic("18446744073709551615", true));
        assertThrows(IllegalArgumentException.class, () -> new Request(UUID.randomUUID().toString(), "https://example.org", "1"));
        assertThrows(IllegalArgumentException.class, () -> new Candidate(ADDRESS, MAX_ATOMIC.toString(), "1", HASH, "aa", "bb", List.of(HASH)));
        assertThrows(IllegalArgumentException.class, () -> new Candidate(ADDRESS, "1", "1", HASH, "AA", "bb", List.of(HASH)));
        assertFalse(candidate().toString().contains(candidate().metadata()));
        try (var keys = new MoneroKeys(key())) {
            byte[] journalKey = keys.sendJournalKey();
            assertFalse(Arrays.equals(journalKey, HexFormat.of().parseHex(keys.password())));
            assertFalse(Arrays.equals(journalKey, HexFormat.of().parseHex(keys.spendHex())));
            byte[] again = keys.sendJournalKey(); journalKey[0] ^= 1;
            assertFalse(Arrays.equals(journalKey, again)); keys.close();
            assertThrows(IllegalStateException.class, keys::sendJournalKey);
        }
    }
    @Test public void encryptedReopenExpiresAndSanitizesPreparedArtifacts() throws Exception {
        try (var f = new Fixture(path())) {
            Request request = request(); f.prepared(request);
            byte[] first = Files.readAllBytes(path().resolve(WALLET).resolve("ledger.aesgcm"));
            assertFalse(new String(first, StandardCharsets.ISO_8859_1).contains(candidate().metadata()));
            assertFalse(new String(first, StandardCharsets.ISO_8859_1).contains(ADDRESS));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path().resolve(WALLET).resolve("ledger.aesgcm")));
            assertThrows(MoneroSendJournal.Failure.class, () -> new MoneroSendMachine(f.journal, SESSION, f.wall::get, f.nano::get));
            f.reopen();
            assertEquals(State.EXPIRED, f.machine.status(request.operationId(), SESSION).state());
            Entry tombstone = f.journal.read().entries().get(request.operationId());
            assertNull(tombstone.request()); assertNull(tombstone.candidate()); assertNotNull(tombstone.artifactDigest());
            assertNull(f.prepare(request).work()); assertFalse(f.machine.walletHeld(SESSION));
            byte[] second = Files.readAllBytes(path().resolve(WALLET).resolve("ledger.aesgcm"));
            assertFalse(Arrays.equals(Arrays.copyOfRange(first, 12, 24), Arrays.copyOfRange(second, 12, 24)));
        }
    }
    @Test public void idempotencyAndPerWalletGuard() {
        try (var f = new Fixture(path())) {
            Request request = request(); var first = f.prepare(request);
            assertNotNull(first.work()); assertNull(f.prepare(request).work());
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.prepare(request()));
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.prepare(new Request(request.operationId(), ADDRESS, "1")));
            try (var other = f.root.openWallet(OTHER, key())) {
                var machine = new MoneroSendMachine(other, SESSION, f.wall::get, f.nano::get);
                assertNotNull(machine.prepare(request(), SESSION).work());
            }
            f.machine.finishPreparation(first.work(), null);
            assertEquals(State.PREPARE_FAILED, f.prepare(request).view().state());
            assertNull(f.prepare(request).work()); assertNotNull(f.prepare(request()).work());
        }
    }
    @Test public void concurrentDifferentRequestsAdmitOneWorker() throws Exception {
        try (var f = new Fixture(path())) {
            var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            Callable<Boolean> attempt = () -> {
                ready.countDown(); start.await();
                try { return f.prepare(request()).work() != null; }
                catch (MoneroSendMachine.Rejected e) { return false; }
            };
            try {
                Future<Boolean> a = pool.submit(attempt), b = pool.submit(attempt);
                assertTrue(ready.await(3, TimeUnit.SECONDS)); start.countDown();
                assertNotEquals(a.get(3, TimeUnit.SECONDS), b.get(3, TimeUnit.SECONDS));
                assertEquals(1, f.journal.read().entries().size());
            } finally { start.countDown(); pool.shutdownNow(); }
        }
    }
    @Test public void restartNormalizesEveryRecoverableStateBeforeAdmission() {
        for (State state : List.of(State.PREPARING, State.PREPARED, State.RELAYING, State.BROADCAST, State.CONFIRMED_WAIT, State.CONFIRMED)) {
            try (var f = new Fixture(temp.getRoot().toPath().resolve(state.name()))) {
                Request request = request(); var job = f.prepare(request);
                if (state != State.PREPARING) {
                    View quote = f.machine.finishPreparation(job.work(), candidate());
                    if (state != State.PREPARED) {
                        var relay = f.commit(request, quote);
                        if (state != State.RELAYING) {
                            f.machine.takeRelay(relay.work()); f.machine.finishRelay(relay.work(), HASH);
                            if (state == State.CONFIRMED_WAIT) f.observe(request, 1, false);
                            if (state == State.CONFIRMED) f.observe(request, 10, true);
                        }
                    }
                }
                f.reopen();
                State expected = state == State.CONFIRMED ? state :
                        state == State.PREPARING || state == State.PREPARED ? State.EXPIRED : State.UNKNOWN;
                assertEquals(expected, f.machine.status(request.operationId(), SESSION).state());
                assertNull(f.prepare(request).work());
                if (expected != State.EXPIRED) assertThrows(MoneroSendMachine.Rejected.class, () -> f.prepare(request()));
                else assertNotNull(f.prepare(request()).work());
            }
        }
    }
    @Test public void cancellationCannotReleaseRunningPreparationOrReviveLateResult() throws Exception {
        try (var f = new Fixture(path())) {
            Request request = request(); var job = f.prepare(request).work();
            var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
            var pool = Executors.newSingleThreadExecutor();
            try {
                Future<View> result = pool.submit(() -> { entered.countDown(); finish.await(); return f.machine.finishPreparation(job, candidate()); });
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(State.PREPARING, f.machine.cancel(request.operationId(), SESSION).state());
                assertTrue(f.machine.walletHeld(SESSION));
                assertThrows(MoneroSendMachine.Rejected.class, () -> f.prepare(request()));
                finish.countDown(); assertEquals(State.CANCELLED, result.get(3, TimeUnit.SECONDS).state());
                assertFalse(f.machine.walletHeld(SESSION));
                assertThrows(MoneroSendMachine.Rejected.class, () -> f.machine.finishPreparation(job, candidate()));
                assertNull(f.journal.read().entries().get(request.operationId()).candidate());
            } finally { finish.countDown(); pool.shutdownNow(); }
        }
    }
    @Test public void sessionAndClockChangesFenceCandidates() {
        try (var f = new Fixture(path())) {
            Request request = request(); var job = f.prepare(request).work();
            String changed = UUID.randomUUID().toString(); f.machine.changeSession(changed);
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.machine.status(request.operationId(), SESSION));
            assertEquals(State.CANCELLED, f.machine.finishPreparation(job, candidate()).state());
        }
        for (int mode = 0; mode < 3; mode++) try (var f = new Fixture(temp.getRoot().toPath().resolve("clock" + mode))) {
            Request request = request(); View quote = f.prepared(request);
            if (mode == 0) f.wall.addAndGet(-1);
            else if (mode == 1) f.wall.addAndGet(MoneroSendMachine.QUOTE_MILLIS);
            else f.nano.addAndGet(MoneroSendMachine.QUOTE_MILLIS * 1_000_000L);
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.commit(request, quote));
            assertEquals(State.EXPIRED, f.machine.status(request.operationId(), SESSION).state());
            assertFalse(f.machine.walletHeld(SESSION));
        }
    }
    @Test public void expiryOfRunningPreparationRetainsReasonAndGuard() {
        try (var f = new Fixture(path())) {
            Request request = request(); var job = f.prepare(request).work();
            f.nano.addAndGet(MoneroSendMachine.QUOTE_MILLIS * 1_000_000L);
            f.machine.cancel(request.operationId(), SESSION); // cancel is first call after expiry
            assertTrue(f.machine.walletHeld(SESSION));
            assertEquals(State.EXPIRED, f.journal.read().entries().get(request.operationId()).cancellation());
            f.machine.cancel(request.operationId(), SESSION); // first cause wins
            assertEquals(State.EXPIRED, f.machine.finishPreparation(job, candidate()).state());
            assertFalse(f.machine.walletHeld(SESSION));
        }
    }
    @Test public void reconciliationReceiptsFenceReplaysAndConcurrentWork() {
        try (var f = new Fixture(path())) {
            Request old = request(); f.confirmed(old);
            var receipt = f.machine.beginReconciliation(SESSION);
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.prepare(request()));
            Map<String, MoneroSendMachine.Observation> observations = Map.of(old.operationId(), new MoneroSendMachine.Observation(HASH, true, 10, true, false));
            f.machine.reconcile(receipt, observations, SESSION);
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.machine.reconcile(receipt, observations, SESSION));
            Request next = request(); var job = f.prepare(next).work();
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.machine.beginReconciliation(SESSION));
            f.machine.finishPreparation(job, candidate());
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.machine.reconcile(receipt, observations, SESSION));
            var current = f.machine.beginReconciliation(SESSION);
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.machine.reconcile(receipt, observations, SESSION));
            f.machine.reconcile(current, Map.of(), SESSION);
            assertEquals(State.CANCELLED, f.machine.status(next.operationId(), SESSION).state());
            assertTrue(f.machine.walletHeld(SESSION));
            var abandoned = f.machine.beginReconciliation(SESSION);
            f.machine.abandonReconciliation(abandoned, SESSION);
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.machine.reconcile(abandoned, observations, SESSION));
            var priorSession = f.machine.beginReconciliation(SESSION);
            String changed = UUID.randomUUID().toString(); f.machine.changeSession(changed);
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.machine.reconcile(priorSession, observations, changed));
        }
    }
    @Test public void exactCandidateAndQuoteRequired() {
        try (var f = new Fixture(path())) {
            Request first = request(); var job = f.prepare(first).work();
            Candidate changed = new Candidate(ADDRESS, "1", "100", HASH, "aa", "bb", List.of(HASH));
            assertEquals(State.PREPARE_FAILED, f.machine.finishPreparation(job, changed).state());
            Request second = request(); View quote = f.prepared(second);
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.machine.commit(second.operationId(), "0".repeat(64), SESSION));
            assertEquals(State.PREPARED, f.machine.status(second.operationId(), SESSION).state());
        }
    }
    @Test public void oneDurableRelayAdmissionAndUnknownOnNativeError() {
        try (var f = new Fixture(path())) {
            Request request = request(); View quote = f.prepared(request); var relay = f.commit(request, quote);
            assertEquals(State.RELAYING, f.journal.read().entries().get(request.operationId()).state());
            assertNull(f.commit(request, quote).work());
            AtomicInteger nativeCalls = new AtomicInteger();
            assertEquals(candidate(), f.machine.takeRelay(relay.work())); nativeCalls.incrementAndGet();
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.machine.takeRelay(relay.work()));
            assertEquals(State.UNKNOWN, f.machine.finishRelay(relay.work(), null).state());
            assertEquals(1, nativeCalls.get()); assertNull(f.commit(request, quote).work());
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.machine.cancel(request.operationId(), SESSION));
            f.reopen(); assertEquals(State.UNKNOWN, f.machine.status(request.operationId(), SESSION).state());
        }
    }
    @Test public void mismatchedHashAndTimeoutDoNotReleaseOrAcceptLateResult() {
        try (var f = new Fixture(path())) {
            Request request = request(); var relay = f.commit(request, f.prepared(request)); f.machine.takeRelay(relay.work());
            assertEquals(State.UNKNOWN, f.machine.finishRelay(relay.work(), "e".repeat(64)).state());
            assertTrue(f.machine.walletHeld(SESSION));
        }
        try (var f = new Fixture(temp.getRoot().toPath().resolve("timeout"))) {
            Request request = request(); var relay = f.commit(request, f.prepared(request)); f.machine.takeRelay(relay.work());
            f.machine.uncertainRelay(relay.work());
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.machine.finishRelay(relay.work(), HASH));
            assertTrue(f.machine.walletHeld(SESSION));
        }
    }
    @Test public void everyWriteFailurePreventsRelayAndPoisonsUntilReopen() {
        for (var barrier : MoneroSendJournal.Barrier.values()) try (var f = new Fixture(temp.getRoot().toPath().resolve(barrier.name()))) {
            Request request = request(); View quote = f.prepared(request);
            f.journal.inject(b -> { if (b == barrier) throw new java.io.IOException("sensitive storage diagnostic"); });
            assertThrows(MoneroSendJournal.Failure.class, () -> f.commit(request, quote));
            assertThrows(MoneroSendJournal.Failure.class, () -> f.commit(request, quote));
            assertThrows(MoneroSendJournal.Failure.class, () -> f.machine.walletHeld(SESSION));
            f.reopen();
            State expected = barrier == MoneroSendJournal.Barrier.TEMP_WRITTEN || barrier == MoneroSendJournal.Barrier.TEMP_SYNCED ? State.EXPIRED : State.UNKNOWN;
            assertEquals(expected, f.machine.status(request.operationId(), SESSION).state());
        }
    }
    @Test public void successPersistenceFailureAndSanitizationFailureRemainHeld() {
        try (var f = new Fixture(path())) {
            Request request = request(); var relay = f.commit(request, f.prepared(request)); f.machine.takeRelay(relay.work());
            f.journal.inject(b -> { throw new java.io.IOException(); });
            assertThrows(MoneroSendJournal.Failure.class, () -> f.machine.finishRelay(relay.work(), HASH));
            f.reopen(); assertEquals(State.UNKNOWN, f.machine.status(request.operationId(), SESSION).state());
        }
        try (var f = new Fixture(temp.getRoot().toPath().resolve("cancel"))) {
            Request request = request(); f.prepared(request);
            f.journal.inject(b -> { throw new java.io.IOException(); });
            assertThrows(MoneroSendJournal.Failure.class, () -> f.machine.cancel(request.operationId(), SESSION));
            assertThrows(MoneroSendJournal.Failure.class, () -> f.prepare(request()));
        }
    }
    @Test public void confirmationRequiresExactHashDepthUnlockAndFreshReconciliation() {
        try (var f = new Fixture(path())) {
            Request request = request(); f.broadcast(request);
            f.machine.reconcile(f.machine.beginReconciliation(SESSION), Map.of(request.operationId(), new MoneroSendMachine.Observation("e".repeat(64), true, 100, true, false)), SESSION);
            assertEquals(State.UNKNOWN, f.machine.status(request.operationId(), SESSION).state());
            f.observe(request, 9, true); assertEquals(State.CONFIRMED_WAIT, f.machine.status(request.operationId(), SESSION).state());
            f.observe(request, 10, false); assertTrue(f.machine.walletHeld(SESSION));
            f.observe(request, 10, true); assertFalse(f.machine.walletHeld(SESSION));
            assertEquals(10, f.journal.read().entries().get(request.operationId()).confirmations());
            f.nano.addAndGet(3_000_000_000L);
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.prepare(request()));
            f.observe(request, 10, true);
            Request next = request(); View quote = f.prepared(next);
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.commit(next, quote));
            f.observe(request, 10, true); assertNotNull(f.commit(next, quote).work());
        }
    }
    @Test public void rollbackCancelsNewQuoteAndPreservesMultipleHistoricalHolds() {
        try (var f = new Fixture(path())) {
            Request old = request(); f.confirmed(old);
            Request newer = request(); View quote = f.prepared(newer);
            f.machine.reconcile(f.machine.beginReconciliation(SESSION), Map.of(), SESSION);
            assertEquals(State.CANCELLED, f.machine.status(newer.operationId(), SESSION).state());
            assertEquals(State.UNKNOWN, f.machine.status(old.operationId(), SESSION).state());
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.commit(newer, quote));
        }
        try (var f = new Fixture(temp.getRoot().toPath().resolve("aggregate"))) {
            Request old = request(); f.confirmed(old); Request newer = request(); View quote = f.prepared(newer);
            f.observe(old, 10, true); var relay = f.commit(newer, quote); f.machine.takeRelay(relay.work());
            f.machine.finishRelay(relay.work(), HASH); f.machine.reconcile(f.machine.beginReconciliation(SESSION), Map.of(), SESSION); f.reopen();
            assertEquals(2, f.journal.read().entries().values().stream().filter(e -> e.state() == State.UNKNOWN).count());
            assertThrows(MoneroSendMachine.Rejected.class, () -> f.prepare(request()));
        }
    }
    @Test public void exactPoolPresenceRetainsBroadcastButAbsenceNeverMeansFailure() {
        try (var f = new Fixture(path())) {
            Request request = request(); f.broadcast(request);
            f.machine.reconcile(f.machine.beginReconciliation(SESSION),
                    Map.of(request.operationId(), new MoneroSendMachine.Observation(HASH, false, 0, false, true)), SESSION);
            assertEquals(State.BROADCAST, f.machine.status(request.operationId(), SESSION).state());
            assertTrue(f.machine.walletHeld(SESSION));
            f.machine.reconcile(f.machine.beginReconciliation(SESSION), Map.of(), SESSION);
            assertEquals(State.UNKNOWN, f.machine.status(request.operationId(), SESSION).state());
            assertTrue(f.machine.walletHeld(SESSION));
        }
    }
    @Test public void pendingSnapshotOversizedFileAndWalletAliasHandling() throws Exception {
        try (var f = new Fixture(path())) {
            Request request = request(); f.prepared(request);
            Path directory = path().resolve(WALLET), file = directory.resolve("ledger.aesgcm");
            byte[] old = Files.readAllBytes(file);
            f.machine.cancel(request.operationId(), SESSION); f.journal.close();
            Files.write(directory.resolve("ledger.pending"), old);
            Files.setPosixFilePermissions(directory.resolve("ledger.pending"), PosixFilePermissions.fromString("rw-------"));
            f.reopen();
            assertEquals(State.CANCELLED, f.machine.status(request.operationId(), SESSION).state());
            assertFalse(Files.exists(directory.resolve("ledger.pending")));
            f.journal.close();
            Files.write(file, new byte[MoneroSendJournal.MAX_FILE + 1]);
            assertThrows(MoneroSendJournal.Failure.class, () -> f.root.openWallet(WALLET, key()));
            Path moved = temp.getRoot().toPath().resolve("moved");
            Files.move(directory, moved); Files.createSymbolicLink(directory, moved);
            assertThrows(MoneroSendJournal.Failure.class, () -> f.root.openWallet(WALLET, key()));
        }
    }
    @Test public void networkTransplantIsRejectedAndPairFlagSurvivesMutation() throws Exception {
        byte[] encrypted;
        try (var root = MoneroSendJournal.Root.open(path(), "regtest"); var journal = root.openWallet(WALLET, key())) {
            journal.markNativePaired(); journal.replace(Map.of());
            assertTrue(journal.read().nativePaired());
            encrypted = Files.readAllBytes(path().resolve(WALLET).resolve("ledger.aesgcm"));
        }
        Path main = temp.getRoot().toPath().resolve("main");
        try (var root = MoneroSendJournal.Root.open(main); var journal = root.openWallet(WALLET, key())) { }
        Files.write(main.resolve(WALLET).resolve("ledger.aesgcm"), encrypted);
        try (var root = MoneroSendJournal.Root.open(main)) {
            assertThrows(MoneroSendJournal.Failure.class, () -> root.openWallet(WALLET, key()));
        }
    }
    @Test public void wrongKeyWalletTamperTruncationAndMissingLedgerFailClosed() throws Exception {
        Path rootPath = path();
        try (var f = new Fixture(rootPath)) {
            f.prepared(request()); f.journal.close();
            byte[] wrong = key(); wrong[0]++;
            assertThrows(MoneroSendJournal.Failure.class, () -> f.root.openWallet(WALLET, wrong));
            try (var other = f.root.openWallet(OTHER, key())) { }
            Path file = rootPath.resolve(WALLET).resolve("ledger.aesgcm"); byte[] good = Files.readAllBytes(file);
            Files.write(rootPath.resolve(OTHER).resolve("ledger.aesgcm"), good);
            assertThrows(MoneroSendJournal.Failure.class, () -> f.root.openWallet(OTHER, key()));
            byte[] changed = good.clone(); changed[changed.length - 1] ^= 1; Files.write(file, changed);
            assertThrows(MoneroSendJournal.Failure.class, () -> f.root.openWallet(WALLET, key()));
            Files.write(file, Arrays.copyOf(good, 8));
            assertThrows(MoneroSendJournal.Failure.class, () -> f.root.openWallet(WALLET, key()));
            Files.delete(file);
            assertThrows(MoneroSendJournal.Failure.class, () -> f.root.openWallet(WALLET, key()));
        }
    }
    static byte[] envelope(String json, long sequence) throws Exception {
        byte[] nonce = new byte[12]; new java.security.SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("Qortium/XMR/mainnet/derivation-v1/send-journal/v1\n" + WALLET + "\n" + sequence).getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(24 + encrypted.length).putInt(0x584d5231).putLong(sequence).put(nonce).put(encrypted).array();
    }
    @Test public void authenticatedSchemaViolationsAndOverflowAreRejected() throws Exception {
        try (var f = new Fixture(path())) {
            f.journal.close(); Path file = path().resolve(WALLET).resolve("ledger.aesgcm");
            for (String bad : List.of("{\"version\":2,\"sequence\":0,\"nativePaired\":false,\"entries\":{}}", "{\"version\":1,\"sequence\":0,\"nativePaired\":false,\"entries\":{},\"extra\":1}",
                    "{\"version\":1,\"version\":1,\"sequence\":0,\"nativePaired\":false,\"entries\":{}}", "{\"version\":\"1\",\"sequence\":0,\"nativePaired\":false,\"entries\":{}}",
                    "{\"version\":1,\"sequence\":0.0,\"nativePaired\":false,\"entries\":{}}", "{\"version\":1,\"sequence\":0,\"nativePaired\":false,\"entries\":{}} {}")) {
                Files.write(file, envelope(bad, 0)); assertThrows(MoneroSendJournal.Failure.class, () -> f.root.openWallet(WALLET, key()));
            }
            Files.write(file, envelope("{\"version\":1,\"sequence\":9223372036854775807,\"nativePaired\":false,\"entries\":{}}", Long.MAX_VALUE));
            try (var journal = f.root.openWallet(WALLET, key())) { assertThrows(MoneroSendJournal.Failure.class, () -> journal.replace(Map.of())); }
        }
    }
    @Test public void filesystemAliasesPermissionsAndLockReplacementRejected() throws Exception {
        try (var f = new Fixture(path())) {
            assertThrows(MoneroSendJournal.Failure.class, () -> MoneroSendJournal.Root.open(path()));
            f.journal.close(); Path file = path().resolve(WALLET).resolve("ledger.aesgcm");
            Path link = temp.getRoot().toPath().resolve("hardlink"); Files.createLink(link, file);
            assertThrows(MoneroSendJournal.Failure.class, () -> f.root.openWallet(WALLET, key())); Files.delete(link);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
            assertThrows(MoneroSendJournal.Failure.class, () -> f.root.openWallet(WALLET, key()));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            Files.move(file, link); Files.createSymbolicLink(file, link);
            assertThrows(MoneroSendJournal.Failure.class, () -> f.root.openWallet(WALLET, key()));
            Files.delete(path().resolve(".send.lock"));
            Files.createFile(path().resolve(".send.lock"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            assertThrows(MoneroSendJournal.Failure.class, f.root::check);
        }
    }
    static Process child(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), MoneroSendJournalTests.class.getName()));
        command.addAll(List.of(args)); return new ProcessBuilder(command).redirectErrorStream(true).start();
    }
    static void awaitExit(Process process, int expected) throws Exception {
        try { assertTrue(process.waitFor(15, TimeUnit.SECONDS)); assertEquals(expected, process.exitValue()); }
        finally { if (process.isAlive()) process.destroyForcibly().waitFor(3, TimeUnit.SECONDS); }
    }
    @Test public void separateJvmCannotTakeRootLock() throws Exception {
        try (var f = new Fixture(path())) {
            Process child = child("lock", path().toString());
            awaitExit(child, 42);
        }
        Process child = child("lock", path().toString()); awaitExit(child, 0);
    }
    @Test public void jvmDeathAtEveryCommitWriteBoundaryNormalizesConservatively() throws Exception {
        for (var barrier : MoneroSendJournal.Barrier.values()) {
            Path rootPath = temp.getRoot().toPath().resolve("crash" + barrier);
            Process child = child("crash", rootPath.toString(), barrier.name());
            awaitExit(child, 23);
            try (var f = new Fixture(rootPath)) {
                Entry entry = f.journal.read().entries().values().iterator().next();
                State expected = barrier == MoneroSendJournal.Barrier.TEMP_WRITTEN || barrier == MoneroSendJournal.Barrier.TEMP_SYNCED ? State.EXPIRED : State.UNKNOWN;
                assertEquals(expected, entry.state());
                assertEquals(expected == State.UNKNOWN, f.machine.walletHeld(SESSION));
            }
        }
    }
    public static void main(String[] args) {
        if (args[0].equals("lock")) {
            try (var ignored = MoneroSendJournal.Root.open(Path.of(args[1]))) { System.exit(0); }
            catch (MoneroSendJournal.Failure e) { System.exit(42); }
        }
        try (var f = new Fixture(Path.of(args[1]))) {
            Request request = request(); View quote = f.prepared(request);
            f.journal.inject(b -> { if (b.name().equals(args[2])) Runtime.getRuntime().halt(23); });
            f.commit(request, quote); throw new AssertionError("crash did not trigger");
        }
    }
}
