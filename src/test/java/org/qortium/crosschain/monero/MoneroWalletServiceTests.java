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
    @Test public void hungNativeReadPoisonsLaneAndLateResultDoesNotReviveIt() throws Exception {
        Fake backend = new Fake("old"); backend.readEntered = new CountDownLatch(1); backend.releaseRead = new CountDownLatch(1);
        try (var service = new MoneroWalletService((keys, height) -> backend, Duration.ofMillis(80), Duration.ofHours(1))) {
            var a = service.activate(seed(1), 0, null);
            assertTrue(backend.readEntered.await(3, TimeUnit.SECONDS));
            Thread.sleep(120);
            assertEquals("RESTART_REQUIRED", service.status(a.sessionId()).state());
            assertNull(service.status(a.sessionId()).wallet());
            backend.releaseRead.countDown();
            assertThrows(MoneroWalletService.Rejected.class, () -> service.activate(seed(2), 0, a.sessionId()));
            await(() -> backend.closed);
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
}
