package org.qortium.crosschain.monero;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.qortium.crosschain.monero.MoneroSendContracts.*;
import static org.qortium.crosschain.monero.MoneroWalletServiceTests.*;

public class MoneroSendServiceTests {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    static final String HASH = "c".repeat(64), ADDRESS = "4".repeat(95);
    static Request request() { return new Request(UUID.randomUUID().toString(), ADDRESS, "1000"); }
    static Candidate candidate() { return new Candidate(ADDRESS, "1000", "100", HASH, "aa", "bb", List.of("d".repeat(64))); }
    static class Native implements MoneroSendBackend {
        final MoneroSendJournal journal;
        java.util.function.LongSupplier quoteClock = System::nanoTime;
        @Override public MoneroSendMachine sendMachine(String session) {
            return new MoneroSendMachine(journal, session, System::currentTimeMillis, quoteClock);
        }
        final AtomicInteger preparations = new AtomicInteger(), relays = new AtomicInteger(), observes = new AtomicInteger();
        volatile CountDownLatch prepareEntered, prepareRelease, relayEntered, relayRelease, observeEntered, observeRelease;
        volatile CountDownLatch readEntered, readRelease;
        volatile java.util.function.Consumer<ScanProgress> readCallback;
        public Snapshot read(java.util.function.Consumer<ScanProgress> callback) throws Exception {
            readCallback = callback;
            if (readEntered != null) { readEntered.countDown(); readRelease.await(); }
            if (readThrows) throw new IllegalStateException("SENSITIVE native response");
            return read();
        }
        volatile boolean lostResponse, daemonDown, closed, readThrows;
        volatile Map<String, MoneroSendMachine.Observation> observations = Map.of();
        Native(MoneroSendJournal journal) { this.journal = journal; }
        public MoneroSendJournal journal() { return journal; }
        public Snapshot read() { assertFalse(closed); return new Snapshot(ADDRESS, 1, 1, true, "10000", "10000", List.of()); }
        public Candidate prepare(Request request) throws Exception {
            assertFalse(closed); preparations.incrementAndGet();
            if (prepareEntered != null) { prepareEntered.countDown(); prepareRelease.await(); }
            return candidate();
        }
        public void validateRelay(Candidate candidate) { assertFalse(closed); }
        public String relay(Candidate candidate) throws Exception {
            assertFalse(closed);
            assertEquals(State.RELAYING, journal.read().entries().values().stream().filter(e -> e.candidate() != null).findFirst().orElseThrow().state());
            assertEquals("aa", candidate.metadata()); relays.incrementAndGet();
            if (relayEntered != null) { relayEntered.countDown(); relayRelease.await(); }
            if (lostResponse) throw new IllegalStateException("SENSITIVE native response");
            return HASH;
        }
        public Map<String, MoneroSendMachine.Observation> observe(Map<String, String> hashes) throws Exception {
            observes.incrementAndGet();
            if (observeEntered != null) { observeEntered.countDown(); observeRelease.await(); }
            assertFalse(closed);
            if (daemonDown) throw new IllegalStateException("SENSITIVE daemon diagnostic");
            return observations;
        }
        public void close() { closed = true; journal.close(); }
    }
    class Fixture implements AutoCloseable {
        final Path path = temp.getRoot().toPath().resolve(UUID.randomUUID().toString());
        final MoneroSendJournal.Root root = MoneroSendJournal.Root.open(path);
        final AtomicInteger opened = new AtomicInteger(), factoryClosed = new AtomicInteger();
        volatile Native nativeWallet;
        final MoneroWalletService service;
        Fixture(long timeout) { this(timeout, Duration.ofHours(1), System::nanoTime); }
        Fixture(long timeout, Duration poll, java.util.function.LongSupplier clock) {
            service = new MoneroWalletService(new MoneroWalletBackend.Factory() {
                public MoneroWalletBackend open(MoneroKeys keys, long height) {
                    root.check(); opened.incrementAndGet();
                    byte[] key = keys.sendJournalKey();
                    try {
                        nativeWallet = new Native(root.openWallet(keys.walletId, key));
                        nativeWallet.quoteClock = clock;
                        return nativeWallet;
                    }
                    finally { Arrays.fill(key, (byte) 0); }
                }
                public void close() { root.close(); factoryClosed.incrementAndGet(); }
            }, Duration.ofMillis(timeout), poll, clock);
        }
        String activate(int n, String previous) throws Exception {
            String session = service.activate(seed(n), 0, previous).sessionId();
            await(() -> "READY".equals(service.status(session).state())); return session;
        }
        View prepare(Request r, String s) throws Exception { return service.prepareSend(r, s).get(3, TimeUnit.SECONDS); }
        View commit(Request r, View q, String s) throws Exception { return service.commitSend(r.operationId(), q.quoteDigest(), s).get(3, TimeUnit.SECONDS); }
        public void close() { service.close(); root.close(); }
    }
    @Test public void queuedCommitBehindAdvancingScanCannotRelayAfterOwnerSwitch() throws Exception {
        var clock = new AtomicLong(1);
        try (var f = new Fixture(90000, Duration.ofMillis(50), clock::get)) {
            String a = f.activate(1,null); Request r = request(); View q = f.prepare(r,a);
            Native old = f.nativeWallet;
            old.readRelease = new CountDownLatch(1); old.readEntered = new CountDownLatch(1);
            try {
                assertTrue(old.readEntered.await(3,TimeUnit.SECONDS));
                var commit = f.service.commitSend(r.operationId(),q.quoteDigest(),a);
                for (int height=2;height<=5;height++) {
                    clock.addAndGet(TimeUnit.SECONDS.toNanos(60));
                    old.readCallback.accept(new MoneroWalletBackend.ScanProgress(height,100));
                    assertNotEquals("RESTART_REQUIRED",f.service.status(a).state());
                    assertFalse(commit.isDone()); assertEquals(0,old.relays.get());
                }
                var b = f.service.activate(seed(2),0,a);
                old.readRelease.countDown();
                assertThrows(ExecutionException.class,()->commit.get(3,TimeUnit.SECONDS));
                await(()->"READY".equals(f.service.status(b.sessionId()).state()));
                assertEquals(0,old.relays.get());
            } finally { old.readRelease.countDown(); }
        }
    }

    @Test public void overdueReadRejectsNewSendsAndQueuedQuoteStillExpiresAfterRecovery() throws Exception {
        var clock = new AtomicLong(1);
        try (var f = new Fixture(90000, Duration.ofMillis(50), clock::get)) {
            String a = f.activate(1, null); Request r = request(); View q = f.prepare(r, a);
            Native old = f.nativeWallet;
            old.readRelease = new CountDownLatch(1); old.readEntered = new CountDownLatch(1);
            try {
                assertTrue(old.readEntered.await(3, TimeUnit.SECONDS));
                var pending = f.service.commitSend(r.operationId(), q.quoteDigest(), a);
                clock.addAndGet(TimeUnit.SECONDS.toNanos(121));
                assertEquals("UNAVAILABLE", f.service.status(a).state());
                assertNull(f.service.status(a).wallet()); assertFalse(old.closed);
                assertThrows(MoneroWalletService.Rejected.class, () -> f.service.prepareSend(request(), a));
                // Do not poll send status: queued execution itself must enforce quote expiry.
                old.readRelease.countDown();
                assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
                await(() -> "READY".equals(f.service.status(a).state()));
                assertEquals(State.EXPIRED, f.service.sendStatus(r.operationId(), a).state());
                assertEquals(0, old.relays.get()); assertEquals(1, f.opened.get()); assertFalse(old.closed);
            } finally { old.readRelease.countDown(); }
        }
    }

    @Test public void cancelledQueuedCommitCannotRelayAfterOverdueReadThrows() throws Exception {
        var clock = new AtomicLong(1);
        try (var f = new Fixture(90000, Duration.ofMillis(50), clock::get)) {
            String a = f.activate(1, null); Request r = request(); View q = f.prepare(r, a);
            Native old = f.nativeWallet;
            old.readRelease = new CountDownLatch(1); old.readEntered = new CountDownLatch(1);
            try {
                assertTrue(old.readEntered.await(3, TimeUnit.SECONDS));
                var pending = f.service.commitSend(r.operationId(), q.quoteDigest(), a);
                clock.addAndGet(TimeUnit.SECONDS.toNanos(91));
                assertEquals("UNAVAILABLE", f.service.status(a).state());
                assertEquals(State.CANCELLED, f.service.cancelSend(r.operationId(), a).state());
                old.readThrows = true; old.readRelease.countDown();
                assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
                assertEquals(0, old.relays.get()); assertNull(f.service.status(a).wallet());
                assertNull(f.service.failure()); assertFalse(old.closed);
            } finally { old.readRelease.countDown(); }
        }
    }

    @Test public void successfulLateReadCannotClearJournalFailureLatchedWhileBlocked() throws Exception {
        var clock = new AtomicLong(1);
        try (var f = new Fixture(90000, Duration.ofMillis(50), clock::get)) {
            String a = f.activate(1, null); Request r = request(); f.prepare(r, a);
            Native old = f.nativeWallet;
            old.readRelease = new CountDownLatch(1); old.readEntered = new CountDownLatch(1);
            try {
                assertTrue(old.readEntered.await(3, TimeUnit.SECONDS));
                clock.addAndGet(TimeUnit.SECONDS.toNanos(91));
                assertEquals("UNAVAILABLE", f.service.status(a).state());
                old.journal.close(); // synthetic journal failure while native read still owns the lane
                assertEquals("XMR_RESTART_REQUIRED", assertThrows(MoneroWalletService.Rejected.class,
                        () -> f.service.sendStatus(r.operationId(), a)).code);
                var failure = f.service.failure();
                assertEquals(MoneroWalletService.FailureReason.JOURNAL_FAILURE, failure.reason());
                assertFalse(old.closed);
                old.readRelease.countDown(); // read returns a normal Snapshot; fatal latch must win
                await(() -> old.closed);
                assertSame(failure, f.service.failure());
                assertEquals("RESTART_REQUIRED", f.service.session().state());
                assertNull(f.service.status(a).wallet()); assertEquals(1, f.opened.get());
                assertEquals(0, old.relays.get());
            } finally { old.readRelease.countDown(); }
        }
    }

    @Test public void exactQuoteDuplicateCommitAndUnknownRecoveryAcrossWalletSwitch() throws Exception {
        try (var f = new Fixture(3000)) {
            String a = f.activate(1, null); Request r = request(); View quote = f.prepare(r, a);
            assertEquals(State.PREPARED, quote.state()); assertEquals(1, f.nativeWallet.preparations.get());
            assertEquals(State.PREPARED, f.prepare(r, a).state()); assertEquals(1, f.nativeWallet.preparations.get());
            Native old = f.nativeWallet; old.lostResponse = true;
            assertEquals(State.UNKNOWN, f.commit(r, quote, a).state());
            assertEquals(State.UNKNOWN, f.commit(r, quote, a).state()); assertEquals(1, old.relays.get());
            String b = f.activate(2, a); assertTrue(old.closed);
            assertEquals(State.PREPARED, f.prepare(request(), b).state());
            String again = f.activate(1, b);
            assertEquals(State.UNKNOWN, f.service.sendStatus(r.operationId(), again).state());
            assertThrows(ExecutionException.class, () -> f.prepare(request(), again));
            assertFalse(f.service.status(again).send());
        }
    }
    @Test public void switchDuringPreparationDiscardsLateCandidateBeforeClose() throws Exception {
        try (var f = new Fixture(3000)) {
            String a = f.activate(1, null); Native old = f.nativeWallet;
            old.prepareEntered = new CountDownLatch(1); old.prepareRelease = new CountDownLatch(1);
            Request r = request(); var pending = f.service.prepareSend(r, a);
            try {
                assertTrue(old.prepareEntered.await(3, TimeUnit.SECONDS));
                String b = f.service.activate(seed(2), 0, a).sessionId();
                assertFalse(old.closed); assertEquals(1, f.opened.get());
                assertThrows(MoneroWalletService.Rejected.class, () -> f.service.sendStatus(r.operationId(), b));
                assertThrows(MoneroWalletService.Rejected.class, () -> f.service.cancelSend(r.operationId(), b));
                assertEquals(State.PREPARING, old.journal.read().entries().get(r.operationId()).state());
                old.prepareRelease.countDown();
                assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
                await(() -> "READY".equals(f.service.status(b).state()));
                String back = f.activate(1, b);
                assertEquals(State.CANCELLED, f.service.sendStatus(r.operationId(), back).state());
            } finally { old.prepareRelease.countDown(); }
        }
    }
    @Test public void cancellationAndDaemonFailureNeverRelay() throws Exception {
        try (var f = new Fixture(3000)) {
            String a = f.activate(1, null); Request r = request();
            f.nativeWallet.daemonDown = true;
            ExecutionException failure = assertThrows(ExecutionException.class, () -> f.prepare(r, a));
            assertFalse(failure.toString().contains("SENSITIVE")); assertEquals(0, f.nativeWallet.preparations.get());
            f.nativeWallet.daemonDown = false;
            View quote = f.prepare(r, a);
            assertEquals(State.CANCELLED, f.service.cancelSend(r.operationId(), a).state());
            assertThrows(ExecutionException.class, () -> f.commit(r, quote, a));
            assertEquals(0, f.nativeWallet.relays.get());
        }
    }
    @Test public void relayTimeoutPersistsUnknownAndNeverReleasesLaneForReplacement() throws Exception {
        try (var f = new Fixture(150)) {
            String a = f.activate(1, null); Request r = request(); View q = f.prepare(r, a); Native old = f.nativeWallet;
            old.relayEntered = new CountDownLatch(1); old.relayRelease = new CountDownLatch(1);
            var pending = f.service.commitSend(r.operationId(), q.quoteDigest(), a);
            try {
                assertTrue(old.relayEntered.await(3, TimeUnit.SECONDS)); Thread.sleep(200);
                assertEquals("RESTART_REQUIRED", f.service.status(a).state());
                assertEquals(State.UNKNOWN, old.journal.read().entries().get(r.operationId()).state());
                assertThrows(MoneroWalletService.Rejected.class, () -> f.service.activate(seed(2), 0, a));
                assertThrows(MoneroSendJournal.Failure.class, () -> MoneroSendJournal.Root.open(f.path));
                assertFalse(old.closed);
                old.relayRelease.countDown();
                assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
                await(() -> old.closed); assertEquals(1, old.relays.get()); assertEquals(1, f.opened.get());
            } finally { old.relayRelease.countDown(); }
        }
    }
    @Test public void shutdownWhileRelayBlockedKeepsRootLockUntilWorkerActuallyReturns() throws Exception {
        var f = new Fixture(10000);
        String a = f.activate(1, null); Request r = request(); View q = f.prepare(r, a); Native old = f.nativeWallet;
        old.relayEntered = new CountDownLatch(1); old.relayRelease = new CountDownLatch(1);
        f.service.commitSend(r.operationId(), q.quoteDigest(), a);
        try {
            assertTrue(old.relayEntered.await(3, TimeUnit.SECONDS)); f.service.close();
            assertFalse(old.closed); assertEquals(0, f.factoryClosed.get());
            assertThrows(MoneroSendJournal.Failure.class, () -> MoneroSendJournal.Root.open(f.path));
            old.relayRelease.countDown(); await(() -> f.factoryClosed.get() == 1);
            try (var lock = MoneroSendJournal.Root.open(f.path)) { assertTrue(old.closed); }
        } finally { old.relayRelease.countDown(); f.close(); }
    }
    @Test public void switchAfterRelayAdmissionWaitsForExactOutcome() throws Exception {
        try (var f = new Fixture(3000)) {
            String a = f.activate(1, null); Request r = request(); View q = f.prepare(r, a); Native old = f.nativeWallet;
            old.relayEntered = new CountDownLatch(1); old.relayRelease = new CountDownLatch(1);
            var pending = f.service.commitSend(r.operationId(), q.quoteDigest(), a);
            try {
                assertTrue(old.relayEntered.await(3, TimeUnit.SECONDS));
                String b = f.service.activate(seed(2), 0, a).sessionId();
                assertEquals(1, f.opened.get()); assertFalse(old.closed);
                old.relayRelease.countDown(); assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
                await(() -> "READY".equals(f.service.status(b).state()));
                String back = f.activate(1, b);
                assertEquals(State.UNKNOWN, f.service.sendStatus(r.operationId(), back).state());
                assertEquals(1, old.relays.get());
            } finally { old.relayRelease.countDown(); }
        }
    }
    @Test public void corruptJournalPreventsAnyNativeWalletCreation() throws Exception {
        Path root = temp.getRoot().toPath().resolve("corrupt");
        Path namespace = root.resolve("xmr-mainnet-v1");
        Files.createDirectories(namespace);
        Files.setPosixFilePermissions(namespace, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        try (var keys = new MoneroKeys(seed(1))) {
            Path coordination = namespace.resolve(".coordination");
            try (var lock = MoneroSendJournal.Root.open(coordination)) {
                byte[] key = keys.sendJournalKey();
                try (var journal = lock.openWallet(keys.walletId, key)) { }
                finally { Arrays.fill(key, (byte) 0); }
            }
            Files.write(coordination.resolve(keys.walletId).resolve("ledger.aesgcm"), new byte[40]);
            try (var factory = MoneroJniWallet.factory(root, "https://unused.invalid")) {
                assertThrows(MoneroSendJournal.Failure.class, () -> factory.open(keys, 0));
                assertFalse(Files.exists(namespace.resolve(keys.walletId))); // fails before native/cache creation
            }
        }
    }
    @Test public void confirmationAndRollbackAreReadFromFreshBackendNotUiHistory() throws Exception {
        try (var f = new Fixture(3000)) {
            String a = f.activate(1, null); Request old = request(); View quote = f.prepare(old, a); f.commit(old, quote, a);
            f.nativeWallet.observations = Map.of(old.operationId(), new MoneroSendMachine.Observation(HASH, true, 10, true, false));
            f.service.reconcileSends(a).get(3, TimeUnit.SECONDS);
            assertEquals(State.CONFIRMED, f.service.sendStatus(old.operationId(), a).state());
            Request next = request(); View nextQuote = f.prepare(next, a);
            f.nativeWallet.observations = Map.of();
            assertThrows(ExecutionException.class, () -> f.commit(next, nextQuote, a));
            assertEquals(State.UNKNOWN, f.service.sendStatus(old.operationId(), a).state());
            assertEquals(State.CANCELLED, f.service.sendStatus(next.operationId(), a).state());
            assertEquals(1, f.nativeWallet.relays.get());
        }
    }
    @Test public void queuedPrepareCannotTouchNativeOrJournalAfterReadFailure() throws Exception {
        try (var f = new Fixture(90000,Duration.ofMillis(50),System::nanoTime)) {
            String session=f.activate(1,null);
            var nativeWallet=f.nativeWallet;
            nativeWallet.readEntered=new CountDownLatch(1);nativeWallet.readRelease=new CountDownLatch(1);nativeWallet.readThrows=true;
            try {
                assertTrue(nativeWallet.readEntered.await(3,TimeUnit.SECONDS));
                var queued=f.service.prepareSend(request(),session);
                nativeWallet.readRelease.countDown();
                var error=assertThrows(ExecutionException.class,()->queued.get(3,TimeUnit.SECONDS));
                assertEquals("XMR_WORK_IN_PROGRESS",((MoneroWalletService.Rejected)error.getCause()).code);
                assertEquals(0,nativeWallet.preparations.get());assertEquals(0,nativeWallet.relays.get());assertEquals(0,nativeWallet.observes.get());
                assertTrue(nativeWallet.journal.read().entries().isEmpty());
                assertNotNull(f.service.status(session).readRetryAt());
            } finally {nativeWallet.readRelease.countDown();}
        }
    }
    @Test public void queuedCommitLeavesPreparedQuoteUnclaimedAfterReadFailure() throws Exception {
        try (var f = new Fixture(90000,Duration.ofMillis(50),System::nanoTime)) {
            String session=f.activate(1,null);Request request=request();View quote=f.prepare(request,session);
            var nativeWallet=f.nativeWallet;int beforeObserves=nativeWallet.observes.get();
            nativeWallet.readEntered=new CountDownLatch(1);nativeWallet.readRelease=new CountDownLatch(1);nativeWallet.readThrows=true;
            try {
                assertTrue(nativeWallet.readEntered.await(3,TimeUnit.SECONDS));
                var queued=f.service.commitSend(request.operationId(),quote.quoteDigest(),session);
                nativeWallet.readRelease.countDown();assertThrows(ExecutionException.class,()->queued.get(3,TimeUnit.SECONDS));
                assertEquals(0,nativeWallet.relays.get());assertEquals(beforeObserves,nativeWallet.observes.get());
                assertEquals(State.PREPARED,f.service.sendStatus(request.operationId(),session).state());
            } finally {nativeWallet.readRelease.countDown();}
        }
    }

}
