package org.qortium.crosschain.monero;

import org.junit.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class MoneroWalletServiceTests {
    static byte[] seed(int n) { byte[] seed = new byte[32]; seed[0] = (byte)n; return seed; }
    static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) fail("condition timed out");
            Thread.sleep(5);
        }
    }
    static class Fake implements MoneroWalletBackend {
        final String address;
        final AtomicInteger reads = new AtomicInteger();
        volatile boolean closed;
        volatile boolean failClose;
        CountDownLatch readEntered, releaseRead;
        Fake(String address) { this.address = address; }
        public Snapshot read() throws Exception {
            reads.incrementAndGet();
            if (readEntered != null) { readEntered.countDown(); releaseRead.await(); }
            assertFalse(closed);
            return new Snapshot(address, 99, 99, true, "9007199254740993", "9007199254740000", List.of());
        }
        public void close() { if (failClose) throw new IllegalStateException("SENSITIVE native diagnostic"); closed = true; }
    }
    static MoneroWalletService service(MoneroWalletBackend.Factory factory) {
        return new MoneroWalletService(factory, Duration.ofSeconds(3), Duration.ofHours(1));
    }
    @Test public void lastDisplaySurvivesOverdueAndFailedReadWithoutGrantingLiveReadiness() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong(1);
        Fake backend = new Fake("display") {
            @Override public Snapshot read() throws Exception {
                if (reads.incrementAndGet() == 1) return new Snapshot("display", 20, 100, false, "0", "0", List.of());
                readEntered.countDown(); releaseRead.await(); throw new java.io.IOException();
            }
        };
        backend.readEntered = new CountDownLatch(1); backend.releaseRead = new CountDownLatch(1);
        try (var service = new MoneroWalletService((keys, height) -> backend, Duration.ofSeconds(90), Duration.ofMillis(10), clock::get)) {
            var owner = service.activate(seed(1), 0, null);
            assertTrue(backend.readEntered.await(3, TimeUnit.SECONDS));
            var observed = service.status(owner.sessionId()).display(); assertNotNull(observed);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(91));
            var overdue = service.status(owner.sessionId()); assertNull(overdue.wallet()); assertEquals("UNAVAILABLE", overdue.state());
            assertSame(observed, overdue.display()); assertEquals(20, overdue.progress().height());
            backend.releaseRead.countDown(); await(() -> service.status(owner.sessionId()).readRetryAt() != null);
            assertSame(observed, service.status(owner.sessionId()).display()); assertNull(service.status(owner.sessionId()).wallet());
            service.deactivate(owner.sessionId());
            try { service.status(owner.sessionId()); fail(); } catch (MoneroWalletService.Rejected expected) { assertEquals(409, expected.status); }
        } finally { backend.releaseRead.countDown(); }
    }

    @Test public void progressSurvivesStaleBalancesButNeverSwitchesOwnersOrRefreshesOnPoll() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var callbacks = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<MoneroWalletBackend.ScanProgress>>();
        var reads = new AtomicInteger(); var opens = new AtomicInteger();
        Fake old = new Fake("old") {
            @Override public Snapshot read(java.util.function.Consumer<ScanProgress> cb) throws Exception {
                callbacks.set(cb);
                cb.accept(new ScanProgress(40, 100));
                if (reads.incrementAndGet() > 1) { entered.countDown(); release.await(); }
                return new Snapshot("old", 40, 100, false, "0", "0", List.of());
            }
        };
        try (var service = new MoneroWalletService((keys, height) -> opens.incrementAndGet() == 1 ? old : new Fake("new"),
                Duration.ofSeconds(3), Duration.ofMillis(50))) {
            var a = service.activate(seed(1), 10, null);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var before = service.status(a.sessionId()).progress();
            assertNotNull(before); assertEquals(10, before.startHeight());
            assertNotEquals(a.sessionId(), before.scanId());
            // Age only the completed financial snapshot, while the native read remains in flight.
            var age = MoneroWalletService.class.getDeclaredField("updatedNanos"); age.setAccessible(true);
            synchronized (service) { age.setLong(service, System.nanoTime() - TimeUnit.SECONDS.toNanos(31)); }
            callbacks.get().accept(new MoneroWalletBackend.ScanProgress(50, 100));
            var stale = service.status(a.sessionId());
            assertEquals("STALE", stale.state()); assertNull(stale.wallet()); assertEquals(50, stale.progress().height());
            assertNotNull(stale.display()); assertEquals("old", stale.display().data().address());
            assertEquals(stale.updatedAt().longValue(), stale.display().updatedAt());
            Thread.sleep(10);
            callbacks.get().accept(new MoneroWalletBackend.ScanProgress(50, 100));
            assertEquals(stale.progress(), service.status(a.sessionId()).progress());
            var b = service.activate(seed(2), 0, a.sessionId());
            callbacks.get().accept(new MoneroWalletBackend.ScanProgress(90, 100));
            assertNull(service.status(b.sessionId()).progress());
            assertNull(service.status(b.sessionId()).display());
            release.countDown();
            await(() -> "READY".equals(service.status(b.sessionId()).state()));
            callbacks.get().accept(new MoneroWalletBackend.ScanProgress(95, 100));
            assertEquals(99, service.status(b.sessionId()).progress().height());
            assertNotEquals(before.scanId(), service.status(b.sessionId()).progress().scanId());
        } finally { release.countDown(); }
    }

    @Test public void callbackCannotExtendDeadlineOrPublishAfterReadFinished() throws Exception {
        var callback = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<MoneroWalletBackend.ScanProgress>>();
        Fake backend = new Fake("old") {
            @Override public Snapshot read(java.util.function.Consumer<ScanProgress> cb) throws Exception {
                callback.set(cb); return super.read();
            }
        };
        Fake completed = backend;
        try (var service = service((keys, height) -> completed)) {
            var a = service.activate(seed(1), 0, null);
            await(() -> "READY".equals(service.status(a.sessionId()).state()));
            callback.get().accept(new MoneroWalletBackend.ScanProgress(5, 100));
            assertEquals(99, service.status(a.sessionId()).progress().height());
        }
        backend = new Fake("hung") {
            @Override public Snapshot read(java.util.function.Consumer<ScanProgress> cb) throws Exception {
                callback.set(cb); cb.accept(new ScanProgress(1, 100)); return super.read();
            }
        };
        backend.readEntered = new CountDownLatch(1); backend.releaseRead = new CountDownLatch(1);
        Fake hung = backend;
        try (var service = new MoneroWalletService((keys, height) -> hung, Duration.ofMillis(80), Duration.ofHours(1))) {
            var a = service.activate(seed(1), 0, null);
            assertTrue(hung.readEntered.await(3, TimeUnit.SECONDS));
            Thread.sleep(120);
            callback.get().accept(new MoneroWalletBackend.ScanProgress(2, 100));
            assertEquals("UNAVAILABLE", service.status(a.sessionId()).state());
            assertEquals(1, service.status(a.sessionId()).progress().height());
            assertNull(service.failure());
            hung.releaseRead.countDown();
            await(() -> "READY".equals(service.status(a.sessionId()).state()));
        } finally { hung.releaseRead.countDown(); }
    }

    static class ScanFixture implements AutoCloseable {
        final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1);
        final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<MoneroWalletBackend.ScanProgress>> callback = new java.util.concurrent.atomic.AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile boolean readThrows, journalFails, nativeFails;
        volatile boolean blockClose;
        final CountDownLatch closeEntered = new CountDownLatch(1), releaseClose = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<MoneroWalletBackend.ReadPhase>> phases = new java.util.concurrent.atomic.AtomicReference<>();
        final Fake backend = new Fake("synthetic") {
            @Override public Snapshot read(java.util.function.Consumer<ScanProgress> cb,
                                           java.util.function.Consumer<ReadPhase> phase) throws Exception {
                phases.set(phase); return read(cb);
            }
            @Override public Snapshot read(java.util.function.Consumer<ScanProgress> cb) throws Exception {
                callback.set(cb); entered.countDown(); release.await();
                if (journalFails) throw new MoneroSendJournal.Failure();
                if (nativeFails) throw new UnsatisfiedLinkError("SENSITIVE");
                if (readThrows) throw new IllegalStateException("SENSITIVE");
                return super.read();
            }
            @Override public void close() {
                closeEntered.countDown();
                if (blockClose) {
                    try { releaseClose.await(); }
                    catch (InterruptedException e) { throw new IllegalStateException(e); }
                }
                super.close();
            }
        };
        final MoneroWalletService service = new MoneroWalletService((keys,height)->backend,
                Duration.ofSeconds(90), Duration.ofHours(1), clock::get);
        final String session;
        ScanFixture() throws Exception {
            session = service.activate(seed(1), 0, null).sessionId();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
        }
        void advance(long seconds, long height, long target) {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
            callback.get().accept(new MoneroWalletBackend.ScanProgress(height,target));
        }
        public void close() { release.countDown(); releaseClose.countDown(); service.close(); }
    }

    @Test public void forwardScanProgressOutlivesTotalDeadlineButStillExpiresWhenStalled() throws Exception {
        try (var f = new ScanFixture()) {
            f.advance(60,10,100); f.advance(60,20,100); f.advance(60,30,100);
            assertEquals("SCANNING",f.service.status(f.session).state());
            assertNull(f.service.failure());
            f.clock.addAndGet(TimeUnit.SECONDS.toNanos(91));
            assertEquals("UNAVAILABLE",f.service.status(f.session).state());
            var failure = f.service.overdueRead();
            assertEquals(MoneroWalletService.FailureReason.DEADLINE,failure.reason());
            assertEquals(MoneroWalletService.Phase.SCAN,failure.phase());
            assertEquals(271000,failure.elapsedMillis()); assertEquals(91000,failure.progressAgeMillis());
            assertEquals(3,failure.advances());
            f.advance(1,40,100);
            assertSame(failure,f.service.overdueRead()); assertNull(f.service.failure()); assertNotNull(f.service.status(f.session).progress());
        }
    }

    @Test public void duplicateBackwardInvalidAndTargetOnlyCallbacksCannotExtendInactivity() throws Exception {
        try (var f = new ScanFixture()) {
            f.advance(20,10,100);
            f.advance(20,10,100); // duplicate
            f.advance(20,9,100); // backward
            f.advance(20,10,110); // changed target and return to former high water
            f.advance(20,200,100); // invalid
            f.clock.addAndGet(TimeUnit.SECONDS.toNanos(11));
            assertEquals("UNAVAILABLE",f.service.status(f.session).state());
            assertEquals(1,f.service.overdueRead().advances());
        }
    }

    @Test public void queuedSwitchKeepsItsAbsoluteDeadlineDespiteOldReadProgress() throws Exception {
        try (var f = new ScanFixture()) {
            f.advance(60,10,100); f.advance(60,20,100);
            var next = f.service.activate(seed(2),0,f.session);
            f.advance(60,30,100);
            assertEquals("OPENING",f.service.status(next.sessionId()).state());
            f.advance(31,40,100);
            assertEquals("RESTART_REQUIRED",f.service.status(next.sessionId()).state());
            assertEquals(MoneroWalletService.Phase.LIFECYCLE,f.service.failure().phase());
            assertEquals(91000,f.service.failure().elapsedMillis());
            assertEquals(0,f.service.failure().advances());
        }
    }

    @Test public void phaseChangesNeverRenewReadDeadlineAndRecordStalledSubphase() throws Exception {
        try (var f = new ScanFixture()) {
            f.phases.get().accept(MoneroWalletBackend.ReadPhase.SYNC);
            assertEquals(org.qortium.crosschain.WalletReadStatus.State.IN_FLIGHT, f.service.status(f.session).read().state());
            assertEquals(org.qortium.crosschain.WalletReadStatus.Phase.SYNC, f.service.status(f.session).read().phase());
            assertNull(f.service.status(f.session).read().retryAt());
            f.clock.addAndGet(TimeUnit.SECONDS.toNanos(60));
            f.phases.get().accept(MoneroWalletBackend.ReadPhase.SAVE);
            f.clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
            assertEquals("UNAVAILABLE", f.service.status(f.session).state());
            assertEquals(org.qortium.crosschain.WalletReadStatus.State.OVERDUE, f.service.status(f.session).read().state());
            assertEquals(MoneroWalletBackend.ReadPhase.SAVE, f.service.overdueRead().readPhase());
            assertEquals(31000, f.service.overdueRead().readPhaseAgeMillis());
            var incident = f.service.overdueRead();
            f.phases.get().accept(MoneroWalletBackend.ReadPhase.BALANCE);
            assertEquals(org.qortium.crosschain.WalletReadStatus.Phase.BALANCE, f.service.status(f.session).read().phase());
            f.advance(1, 99, 99);
            assertSame(incident, f.service.overdueRead());
            assertNull(f.service.status(f.session).wallet()); assertNull(f.service.status(f.session).progress());
            f.release.countDown();
            await(() -> "READY".equals(f.service.status(f.session).state()));
            assertNotNull(f.service.status(f.session).wallet()); assertNull(f.service.failure());
            await(() -> f.service.status(f.session).read().state() == org.qortium.crosschain.WalletReadStatus.State.IDLE);
        }
    }

    @Test public void readDisplayCannotFollowAnOldOwnerIntoAQueuedSwitch() throws Exception {
        try (var f = new ScanFixture()) {
            f.phases.get().accept(MoneroWalletBackend.ReadPhase.SYNC);
            f.advance(91, 10, 100);
            var next = f.service.activate(seed(2), 0, f.session);
            f.phases.get().accept(MoneroWalletBackend.ReadPhase.SAVE);
            assertThrows(MoneroWalletService.Rejected.class, () -> f.service.status(f.session));
            var display = f.service.status(next.sessionId()).read();
            assertEquals(org.qortium.crosschain.WalletReadStatus.State.IDLE, display.state());
            assertNull(display.phase()); assertNull(display.retryAt());
        }
    }

    @Test public void lateReadExceptionNeverPublishesFinancialData() throws Exception {
        try (var f = new ScanFixture()) {
            f.readThrows = true; f.advance(91, 10, 100);
            assertEquals("UNAVAILABLE", f.service.status(f.session).state());
            f.release.countDown();
            await(() -> f.service.overdueRead() == null);
            assertEquals("UNAVAILABLE", f.service.status(f.session).state());
            assertNull(f.service.status(f.session).wallet()); assertNull(f.service.failure());
        }
    }

    @Test public void overdueReadCannotRecoverAfterQueuedLifecycleExpiresWithoutAPoll() throws Exception {
        try (var f = new ScanFixture()) {
            f.advance(91, 10, 100);
            var next = f.service.activate(seed(2), 0, f.session);
            f.clock.addAndGet(TimeUnit.SECONDS.toNanos(91));
            f.release.countDown();
            await(() -> f.backend.closed);
            assertEquals("RESTART_REQUIRED", f.service.session().state());
            assertEquals(MoneroWalletService.Phase.LIFECYCLE, f.service.failure().phase());
            assertNull(f.service.status(next.sessionId()).wallet());
        }
    }

    @Test public void overdueReadDeactivationWaitsForNativeReturnAndNeverRepublishes() throws Exception {
        try (var f = new ScanFixture()) {
            f.advance(91, 10, 100);
            f.service.deactivate(f.session);
            assertFalse(f.backend.closed);
            f.release.countDown();
            await(() -> "IDLE".equals(f.service.session().state()));
            assertTrue(f.backend.closed); assertNull(f.service.failure());
        }
    }

    @Test public void queuedScanStopWaitsBeyondLifecycleDeadlineWithoutReactivatingOrClosingAlongsideRead() throws Exception {
        try (var f = new ScanFixture()) {
            f.advance(91, 10, 100);
            var stopped = f.service.deactivate(f.session);
            f.advance(120, 20, 100);
            assertEquals("CLOSING", f.service.session().state());
            assertNull(f.service.session().walletId()); assertNull(f.service.failure());
            assertFalse(f.backend.closed);
            assertThrows(MoneroWalletService.Rejected.class, () -> f.service.activate(seed(2), 0, stopped.sessionId()));
            f.release.countDown();
            await(() -> "IDLE".equals(f.service.session().state()));
            assertTrue(f.backend.closed); assertNull(f.service.failure());
        }
    }

    @Test public void queuedScanStopStillTimesOutIfItsActualNativeCloseStalls() throws Exception {
        try (var f = new ScanFixture()) {
            f.blockClose = true;
            f.service.deactivate(f.session);
            f.clock.addAndGet(TimeUnit.SECONDS.toNanos(120));
            assertEquals("CLOSING", f.service.session().state());
            f.release.countDown();
            assertTrue(f.closeEntered.await(3, TimeUnit.SECONDS));
            f.clock.addAndGet(TimeUnit.SECONDS.toNanos(91));
            assertEquals("RESTART_REQUIRED", f.service.session().state());
            assertEquals(MoneroWalletService.Phase.LIFECYCLE, f.service.failure().phase());
            assertEquals(91000, f.service.failure().elapsedMillis());
        }
    }

    @Test public void overdueReadShutdownCannotReviveOrCloseAlongsideNativeWork() throws Exception {
        try (var f = new ScanFixture()) {
            f.advance(91, 10, 100);
            f.service.close();
            assertEquals("STOPPED", f.service.session().state()); assertFalse(f.backend.closed);
            f.advance(91, 20, 100);
            assertEquals("STOPPED", f.service.session().state());
            f.release.countDown(); await(() -> f.backend.closed);
            assertEquals("STOPPED", f.service.session().state());
            assertNull(f.service.status(f.session).wallet());
        }
    }

    @Test public void journalAndNativeFailuresAfterOverdueReadRemainFatal() throws Exception {
        for (boolean journal : new boolean[] {true, false}) {
            try (var f = new ScanFixture()) {
                f.advance(91, 10, 100);
                f.journalFails = journal; f.nativeFails = !journal;
                f.release.countDown(); await(() -> f.backend.closed);
                assertEquals("RESTART_REQUIRED", f.service.session().state());
                assertEquals(journal ? MoneroWalletService.FailureReason.JOURNAL_FAILURE
                        : MoneroWalletService.FailureReason.NATIVE_LINKAGE, f.service.failure().reason());
                assertNull(f.service.status(f.session).wallet());
            }
        }
    }

    @Test public void lifecycleFailureHasSafeDistinctDiagnostic() throws Exception {
        try (var service = service((keys,height)->{ throw new IllegalStateException("SENSITIVE native diagnostic"); })) {
            service.activate(seed(1),0,null);
            await(()->"RESTART_REQUIRED".equals(service.session().state()));
            assertEquals(MoneroWalletService.FailureReason.LIFECYCLE_FAILURE,service.failure().reason());
            assertFalse(service.failure().toString().contains("SENSITIVE"));
        }
    }

    @Test public void safePreAdmissionRejectionPermitsCorrectedExplicitActivation() throws Exception {
        try (var service = service((keys, height) -> {
            if (height > 100) throw new MoneroWalletBackend.AdmissionRejected("XMR_RESTORE_HEIGHT_ABOVE_TIP");
            return new Fake("accepted");
        })) {
            var first = service.activate(seed(1), 200, null);
            await(() -> "ACTIVATION_REJECTED".equals(service.session().state()));
            assertEquals("XMR_RESTORE_HEIGHT_ABOVE_TIP", service.session().errorCode());
            assertNull(service.session().walletId());
            var corrected = service.activate(seed(1), 0, first.sessionId());
            await(() -> "READY".equals(service.status(corrected.sessionId()).state()));
            assertNull(service.session().errorCode());
        }
    }
    @Test public void sameWalletReactivationDoesNotReopenAndStaleSessionCannotReadOrSwitch() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        try (var service = service((keys, height) -> { opens.incrementAndGet(); return new Fake(keys.walletId); })) {
            var a = service.activate(seed(1), 0, null);
            await(() -> "READY".equals(service.status(a.sessionId()).state()));
            assertEquals(a.sessionId(), service.activate(seed(1), 0, a.sessionId()).sessionId());
            assertEquals(1, opens.get());
            assertEquals("9007199254740993", service.status(a.sessionId()).wallet().balanceAtomic());
            assertFalse(service.status(a.sessionId()).send());
            assertThrows(MoneroWalletService.Rejected.class, () -> service.activate(seed(2), 0, null));
            var b = service.activate(seed(2), 0, a.sessionId());
            assertThrows(MoneroWalletService.Rejected.class, () -> service.status(a.sessionId()));
            await(() -> "READY".equals(service.status(b.sessionId()).state()));
            assertEquals(b.walletId(), service.status(b.sessionId()).wallet().address());
            assertThrows(MoneroWalletService.Rejected.class, () -> service.activate(seed(2), 1, b.sessionId()));
        }
    }
    @Test public void switchDuringReadClearsOldSnapshotAndLateReadCannotOverwriteNewOwner() throws Exception {
        Fake old = new Fake("old"); old.readEntered = new CountDownLatch(1); old.releaseRead = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();
        try (var service = service((keys, height) -> opens.incrementAndGet() == 1 ? old : new Fake("new"))) {
            var a = service.activate(seed(1), 0, null);
            assertTrue(old.readEntered.await(3, TimeUnit.SECONDS));
            var b = service.activate(seed(2), 0, a.sessionId());
            assertNull(service.status(b.sessionId()).wallet());
            assertEquals("OPENING", service.status(b.sessionId()).state());
            assertThrows(MoneroWalletService.Rejected.class, () -> service.activate(seed(3), 0, b.sessionId()));
            old.releaseRead.countDown();
            await(() -> "READY".equals(service.status(b.sessionId()).state()));
            assertTrue(old.closed);
            assertEquals("new", service.status(b.sessionId()).wallet().address());
        } finally { old.releaseRead.countDown(); }
    }
    @Test public void failedCloseNeverOpensReplacementOrLeaksDiagnostic() throws Exception {
        Fake old = new Fake("old"); old.failClose = true;
        AtomicInteger opens = new AtomicInteger();
        try (var service = service((keys, height) -> { opens.incrementAndGet(); return old; })) {
            var a = service.activate(seed(1), 0, null);
            await(() -> "READY".equals(service.status(a.sessionId()).state()));
            var b = service.activate(seed(2), 0, a.sessionId());
            await(() -> "RESTART_REQUIRED".equals(service.status(b.sessionId()).state()));
            assertNull(service.status(b.sessionId()).wallet());
            assertEquals(1, opens.get());
            assertEquals("XMR_RESTART_REQUIRED", assertThrows(MoneroWalletService.Rejected.class,
                    () -> service.activate(seed(3), 0, b.sessionId())).code);
        }
    }
    @Test public void hungReadStaysUnavailableWithoutClosingAndFullSuccessRecoversSameHandle() throws Exception {
        Fake backend = new Fake("old"); backend.readEntered = new CountDownLatch(1); backend.releaseRead = new CountDownLatch(1);
        try (var service = new MoneroWalletService((keys, height) -> backend, Duration.ofMillis(80), Duration.ofHours(1))) {
            var a = service.activate(seed(1), 0, null);
            assertTrue(backend.readEntered.await(3, TimeUnit.SECONDS));
            Thread.sleep(120);
            assertEquals("UNAVAILABLE", service.status(a.sessionId()).state());
            assertNull(service.status(a.sessionId()).wallet());
            assertFalse(backend.closed); assertNull(service.failure());
            backend.releaseRead.countDown();
            await(() -> "READY".equals(service.status(a.sessionId()).state()));
            assertFalse(backend.closed); assertNull(service.overdueRead());
            assertEquals("old", service.status(a.sessionId()).wallet().address());
        } finally { backend.releaseRead.countDown(); }
    }
    @Test public void openingThatFinishesAfterDeadlineClosesHandleAndCannotPublish() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Fake backend = new Fake("late");
        try (var service = new MoneroWalletService((keys, height) -> {
            entered.countDown(); release.await(); return backend;
        }, Duration.ofMillis(80), Duration.ofHours(1))) {
            var a = service.activate(seed(1), 0, null);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Thread.sleep(120);
            assertEquals("RESTART_REQUIRED", service.status(a.sessionId()).state());
            release.countDown();
            await(() -> backend.closed);
            assertEquals(0, backend.reads.get());
            assertNull(service.status(a.sessionId()).wallet());
        } finally { release.countDown(); }
    }
    @Test public void deactivateRevokesWalletReadsAndShutdownRejectsNewWork() throws Exception {
        Fake backend = new Fake("old");
        var service = service((keys, height) -> backend);
        try {
            var a = service.activate(seed(1), 0, null);
            await(() -> "READY".equals(service.status(a.sessionId()).state()));
            var empty = service.deactivate(a.sessionId());
            assertThrows(MoneroWalletService.Rejected.class, () -> service.status(a.sessionId()));
            assertThrows(MoneroWalletService.Rejected.class, () -> service.status(empty.sessionId()));
            await(() -> "IDLE".equals(service.session().state()));
            assertTrue(backend.closed);
        } finally { service.close(); }
        assertThrows(MoneroWalletService.Rejected.class, () -> service.activate(seed(2), 0, service.session().sessionId()));
    }
    @Test public void failedReadsBackOffPollingCannotRenewRetryAndFullSuccessRecovers() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong(1);
        var failRead = new java.util.concurrent.atomic.AtomicBoolean(true);
        Fake fake = new Fake("retry") {
            @Override public Snapshot read() throws Exception {
                if (failRead.get()) { reads.incrementAndGet(); throw new IllegalStateException("SENSITIVE native text"); }
                return super.read();
            }
        };
        try (var service = new MoneroWalletService((keys,height)->fake,Duration.ofSeconds(90),Duration.ofMillis(10),clock::get)) {
            var a = service.activate(seed(1),0,null);
            await(()->"UNAVAILABLE".equals(service.status(a.sessionId()).state()));
            var retry = service.status(a.sessionId()).readRetryAt(); assertNotNull(retry);
            await(() -> service.status(a.sessionId()).read().state() == org.qortium.crosschain.WalletReadStatus.State.RETRY_SCHEDULED);
            assertEquals(retry, service.status(a.sessionId()).read().retryAt());
            assertNull(service.status(a.sessionId()).read().phase());
            assertEquals("XMR_WALLET_READ_UNAVAILABLE",service.session().errorCode());
            for (int i=0;i<20;i++) {
                assertNull(service.status(a.sessionId()).wallet());
                assertEquals(retry,service.status(a.sessionId()).readRetryAt());
                service.activate(seed(1),0,a.sessionId());
            }
            Thread.sleep(50); assertEquals(1,fake.reads.get());
            failRead.set(false); clock.addAndGet(TimeUnit.SECONDS.toNanos(10));
            await(()->"READY".equals(service.status(a.sessionId()).state()));
            assertNull(service.status(a.sessionId()).readRetryAt());assertNull(service.session().errorCode());
            assertNotNull(service.status(a.sessionId()).wallet());
        }
    }
    @Test public void stopCancelsRetryAndNextOwnerHasNoInheritedReadCooldown() throws Exception {
        var opened = new AtomicInteger(); Fake failed = new Fake("failed") {
            @Override public Snapshot read() { reads.incrementAndGet(); throw new IllegalStateException("SENSITIVE"); }
        };
        try (var service = new MoneroWalletService((keys,height)->opened.incrementAndGet()==1?failed:new Fake("next"),
                Duration.ofSeconds(90),Duration.ofMillis(10))) {
            var a=service.activate(seed(1),0,null);
            await(()->"UNAVAILABLE".equals(service.status(a.sessionId()).state()));
            var stopped=service.deactivate(a.sessionId());await(()->"IDLE".equals(service.session().state()));
            Thread.sleep(50);assertEquals(1,failed.reads.get());assertTrue(failed.closed);
            var b=service.activate(seed(2),0,stopped.sessionId());
            await(()->"READY".equals(service.status(b.sessionId()).state()));
            assertNull(service.status(b.sessionId()).readRetryAt());
            assertThrows(MoneroWalletService.Rejected.class,()->service.status(a.sessionId()));
        }
    }
    @Test public void failedOldReadCannotAttachCooldownOrErrorToNewOwner() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var opened=new AtomicInteger();
        Fake old=new Fake("old") {
            @Override public Snapshot read() throws Exception { entered.countDown();release.await();throw new IllegalStateException("SENSITIVE"); }
        };
        try(var service=service((keys,height)->opened.incrementAndGet()==1?old:new Fake("new"))) {
            var a=service.activate(seed(1),0,null);assertTrue(entered.await(3,TimeUnit.SECONDS));
            var b=service.activate(seed(2),0,a.sessionId());release.countDown();
            await(()->"READY".equals(service.status(b.sessionId()).state()));
            assertNull(service.status(b.sessionId()).readRetryAt());assertNull(service.session().errorCode());
        } finally {release.countDown();}
    }

    @Test public void chainPreparationIsDisplayOnlyAndCannotExtendDeadline() throws Exception {
        var cb = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<MoneroWalletBackend.ScanProgress>>();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Fake backend = new Fake("preparing") {
            public Snapshot read(java.util.function.Consumer<ScanProgress> counts) throws Exception {
                cb.set(counts); counts.accept(new ScanProgress(10, 200)); entered.countDown(); release.await();
                return new Snapshot("preparing", 200, 200, true, "0", "0", List.of());
            }
        };
        try (var service = new MoneroWalletService((keys, height) -> backend, Duration.ofMillis(100), Duration.ofHours(1))) {
            var a = service.activate(seed(1), 100, null); assertTrue(entered.await(3, TimeUnit.SECONDS));
            var status = service.status(a.sessionId()); assertNull(status.progress()); assertEquals(10, status.preparation().height());
            Thread.sleep(130); cb.get().accept(new MoneroWalletBackend.ScanProgress(20, 200));
            assertEquals("UNAVAILABLE", service.status(a.sessionId()).state()); assertNull(service.status(a.sessionId()).wallet());
            release.countDown(); await(() -> "READY".equals(service.status(a.sessionId()).state()));
            assertNull(service.status(a.sessionId()).preparation());
            cb.get().accept(new MoneroWalletBackend.ScanProgress(50, 200)); assertNull(service.status(a.sessionId()).preparation());
        } finally { release.countDown(); }
    }

}
