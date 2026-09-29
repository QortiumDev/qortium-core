package org.qortium.crosschain;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.qortium.utils.Base58;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.qortium.crosschain.PirateChainSendJournal.Phase.*;

public class PirateChainSendServiceTests {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private PirateChainSendService.Request request(String key) {
        String entropy = Base58.encode(new byte[32]);
        return new PirateChainSendService.Request(entropy, PirateChainSendJournal.walletIdentity(entropy), "MAIN", key, "zs-recipient", 100, null, 10000);
    }
    @Test public void concurrentIdenticalPostsInvokeNativeOnlyOnce() throws Exception {
        AtomicInteger sends = new AtomicInteger(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var service = new PirateChainSendService(new PirateChainSendJournal(temp.getRoot().toPath().resolve("journal"), "MAIN"), (request, lifecycle) -> {
            lifecycle.beforeNative(); sends.incrementAndGet(); entered.countDown(); release.await(); lifecycle.broadcast("ab".repeat(32));
        })) {
            var request = request(UUID.randomUUID().toString());
            var op = service.submit(request, () -> {}); assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                for (int i = 0; i < 10; i++) assertEquals(op.operationId(), service.submit(request, () -> { throw new AssertionError("duplicate must bypass readiness"); }).operationId());
                assertEquals(1, sends.get());
            } finally { release.countDown(); }
            awaitPhase(service, op.operationId(), BROADCAST);
            assertEquals(op.operationId(), service.submit(request, () -> { throw new AssertionError(); }).operationId());
            assertNull(service.blocking(request.walletIdentityHash()));
        }
    }
    @Test public void afterStartExceptionBlocksWalletAndRepeatDoesNotExecute() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        try (var service = new PirateChainSendService(new PirateChainSendJournal(temp.getRoot().toPath().resolve("journal"), "MAIN"), (request, lifecycle) -> {
            lifecycle.beforeNative(); sends.incrementAndGet(); throw new IllegalStateException("uncertain");
        })) {
            var request = request(UUID.randomUUID().toString()); var op = service.submit(request, () -> {});
            awaitPhase(service, op.operationId(), UNRESOLVED);
            assertEquals(op.operationId(), service.blocking(request.walletIdentityHash()));
            assertEquals(op.operationId(), service.submit(request, () -> {}).operationId()); assertEquals(1, sends.get());
            assertThrows(PirateChainSendJournal.ConflictException.class, () -> service.submit(request(UUID.randomUUID().toString()), () -> {}));
        }
    }
    @Test public void provenPreStartFailureDoesNotReserveFunds() throws Exception {
        try (var service = new PirateChainSendService(new PirateChainSendJournal(temp.getRoot().toPath().resolve("journal"), "MAIN"), (request, lifecycle) -> {
            throw new ForeignBlockchainException.InsufficientFundsException("private native detail");
        })) {
            var request = request(UUID.randomUUID().toString()); var op = service.submit(request, () -> {});
            awaitPhase(service, op.operationId(), FAILED);
            assertEquals("ARRR_INSUFFICIENT_VERIFIED_FUNDS", service.get(op.operationId()).reason());
            assertNull(service.blocking(request.walletIdentityHash()));
        }
    }
    @Test public void reservationExistsBeforeAdmissionAndProvenAdmissionFailureReleasesIt() throws Exception {
        try (var journal = new PirateChainSendJournal(temp.getRoot().toPath().resolve("journal"), "MAIN");
             var service = new PirateChainSendService(journal, (r, l) -> { throw new AssertionError("must not send"); })) {
            var r = request(UUID.randomUUID().toString());
            assertThrows(ForeignBlockchainException.WalletBusyException.class, () -> service.submit(r, () -> {
                try { assertNotNull(journal.blocking(r.walletIdentityHash())); }
                catch (java.io.IOException e) { throw new AssertionError(e); }
                throw new ForeignBlockchainException.WalletBusyException("trade already has lane");
            }));
            assertNull(journal.blocking(r.walletIdentityHash()));
            assertEquals(FAILED, journal.lookup(r.walletIdentityHash(), r.idempotencyKey()).phase());
        }
    }
    @Test public void lateWorkerResultResolvesTimedOutOperationWithoutReplay() throws Exception {
        var observer = new java.util.concurrent.atomic.AtomicReference<PirateChainSendService.Lifecycle>();
        try (var service = new PirateChainSendService(new PirateChainSendJournal(temp.getRoot().toPath().resolve("journal"), "MAIN"), (r, l) -> {
            l.beforeNative(); observer.set(l); throw new ForeignBlockchainException.SendOutcomeUnknownException("timeout");
        })) {
            var r = request(UUID.randomUUID().toString()); var op = service.submit(r, () -> {});
            awaitPhase(service, op.operationId(), UNRESOLVED);
            service.stopAdmissions();
            assertThrows(PirateChainSendJournal.StorageException.class, () -> service.submit(r, () -> {}));
            observer.get().broadcast("cd".repeat(32));
            assertEquals(BROADCAST, service.get(op.operationId()).phase());
        }
        assertThrows(PirateChainSendJournal.StorageException.class, () -> observer.get().broadcast("cd".repeat(32)));
    }
    @Test public void rejectsMismatchedAuthorityAndNetworkBeforeAdmission() throws Exception {
        try (var journal = new PirateChainSendJournal(temp.getRoot().toPath().resolve("journal"), "MAIN");
             var service = new PirateChainSendService(journal, (r, l) -> { throw new AssertionError(); })) {
            var r = request(UUID.randomUUID().toString());
            for (var bad : java.util.List.of(
                    new PirateChainSendService.Request(r.entropy58(), "wrong-wallet", "MAIN", r.idempotencyKey(), r.recipient(), 100, null, 10000),
                    new PirateChainSendService.Request(r.entropy58(), r.walletIdentityHash(), "TEST3", r.idempotencyKey(), r.recipient(), 100, null, 10000),
                    new PirateChainSendService.Request(r.entropy58(), r.walletIdentityHash(), "MAIN", r.idempotencyKey(), r.recipient(), 100, null, 10001)))
                assertThrows(IllegalArgumentException.class, () -> service.submit(bad, () -> { throw new AssertionError(); }));
            assertNull(journal.blocking(r.walletIdentityHash()));
        }
    }
    private void awaitPhase(PirateChainSendService service, String id, PirateChainSendJournal.Phase phase) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) { if (service.get(id).phase() == phase) return; Thread.sleep(5); }
        assertEquals(phase, service.get(id).phase());
    }
}
