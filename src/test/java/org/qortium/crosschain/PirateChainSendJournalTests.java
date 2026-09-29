package org.qortium.crosschain;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.qortium.utils.Base58;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.qortium.crosschain.PirateChainSendJournal.Phase.*;

public class PirateChainSendJournalTests {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static String wallet(int n) { byte[] bytes = new byte[32]; bytes[0] = (byte)n; return PirateChainSendJournal.walletIdentity(Base58.encode(bytes)); }
    private static String fingerprint(String memo) { return PirateChainSendJournal.fingerprint("MAIN", "zs-recipient", 100, memo, 10000); }

    @Test public void retriesAndRestartNeverReadmitAPayment() throws Exception {
        Path root = temp.getRoot().toPath().resolve("journal"); String key = UUID.randomUUID().toString(); String id;
        try (var journal = new PirateChainSendJournal(root, "MAIN")) {
            var first = journal.admit(wallet(1), key, fingerprint(null)); id = first.operationId();
            assertEquals(first, journal.admit(wallet(1), key, fingerprint(null)));
            assertThrows(PirateChainSendJournal.ConflictException.class, () -> journal.admit(wallet(1), key, fingerprint("different")));
            journal.transition(id, NATIVE_STARTED, null, null);
        }
        try (var journal = new PirateChainSendJournal(root, "MAIN")) {
            assertEquals(UNRESOLVED, journal.get(id).phase());
            assertEquals(id, journal.blocking(wallet(1)));
            assertEquals(id, journal.admit(wallet(1), key, fingerprint(null)).operationId());
            assertThrows(PirateChainSendJournal.ConflictException.class, () -> journal.admit(wallet(1), UUID.randomUUID().toString(), fingerprint(null)));
            assertNotNull(journal.admit(wallet(2), key, fingerprint(null)));
        }
    }
    @Test public void acceptedRestartIsFailedButSameKeyIsStillDeduplicated() throws Exception {
        Path root = temp.getRoot().toPath().resolve("journal"); String key = UUID.randomUUID().toString(); String id;
        try (var journal = new PirateChainSendJournal(root, "MAIN")) { id = journal.admit(wallet(1), key, fingerprint("")).operationId(); }
        try (var journal = new PirateChainSendJournal(root, "MAIN")) {
            assertEquals(FAILED, journal.get(id).phase()); assertNull(journal.blocking(wallet(1)));
            assertEquals(id, journal.admit(wallet(1), key, fingerprint("")).operationId());
        }
    }
    @Test public void lateSuccessCanResolveUnknownButFailureCannotReleaseIt() throws Exception {
        try (var journal = new PirateChainSendJournal(temp.getRoot().toPath().resolve("journal"), "MAIN")) {
            var op = journal.admit(wallet(1), UUID.randomUUID().toString(), fingerprint(null));
            journal.transition(op.operationId(), NATIVE_STARTED, null, null);
            journal.transition(op.operationId(), UNRESOLVED, null, "ARRR_SEND_OUTCOME_UNKNOWN");
            assertEquals(UNRESOLVED, journal.transition(op.operationId(), FAILED, null, "FAILURE").phase());
            journal.transition(op.operationId(), BROADCAST, "ab".repeat(32), null);
            assertNull(journal.blocking(wallet(1)));
        }
    }
    @Test public void corruptWalletBlocksOnlyItAndNoRecordContainsAuthority() throws Exception {
        Path root = temp.getRoot().toPath().resolve("journal"); String id;
        try (var journal = new PirateChainSendJournal(root, "MAIN")) { id = journal.admit(wallet(1), UUID.randomUUID().toString(), fingerprint("private-memo")).operationId(); }
        Path file = root.resolve(wallet(1)).resolve(id + ".json");
        assertFalse(Files.readString(file).contains("private-memo"));
        Files.writeString(file, "broken");
        try (var journal = new PirateChainSendJournal(root, "MAIN")) {
            assertThrows(PirateChainSendJournal.StorageException.class, () -> journal.blocking(wallet(1)));
            assertThrows(PirateChainSendJournal.StorageException.class, () -> journal.get(id));
            assertThrows(PirateChainSendJournal.StorageException.class, () -> journal.get(UUID.randomUUID().toString()));
            assertNotNull(journal.admit(wallet(2), UUID.randomUUID().toString(), fingerprint(null)));
        }
    }
    @Test public void exclusiveProcessLockAndUnambiguousFingerprints() throws Exception {
        Path root = temp.getRoot().toPath().resolve("journal");
        try (var journal = new PirateChainSendJournal(root, "MAIN")) {
            assertThrows(PirateChainSendJournal.StorageException.class, () -> new PirateChainSendJournal(root, "MAIN"));
            assertNotEquals(fingerprint(null), fingerprint(""));
            assertNotEquals(PirateChainSendJournal.fingerprint("MAIN", "zs", 12, "3", 10000), PirateChainSendJournal.fingerprint("MAIN", "zs", 1, "23", 10000));
        }
    }
    @Test public void interruptingNativeCallerCannotInterruptDurableBroadcastWrite() throws Exception {
        var armed = new java.util.concurrent.atomic.AtomicBoolean(false);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var writer = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>()) {
            protected void beforeExecute(Thread thread, Runnable task) {
                if (armed.get()) { entered.countDown(); try { release.await(); } catch (InterruptedException impossible) { throw new AssertionError(impossible); } }
            }
        };
        try (var journal = new PirateChainSendJournal(temp.getRoot().toPath().resolve("journal"), "MAIN", writer)) {
            var op = journal.admit(wallet(1), UUID.randomUUID().toString(), fingerprint(null));
            journal.transition(op.operationId(), NATIVE_STARTED, null, null);
            armed.set(true);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread nativeWorker = new Thread(() -> {
                try { journal.transition(op.operationId(), BROADCAST, "ab".repeat(32), null); assertTrue(Thread.currentThread().isInterrupted()); }
                catch (Throwable e) { failure.set(e); }
            });
            nativeWorker.start();
            try { assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)); nativeWorker.interrupt(); }
            finally { release.countDown(); }
            nativeWorker.join(5000); assertFalse(nativeWorker.isAlive()); assertNull(failure.get());
            assertEquals(BROADCAST, journal.get(op.operationId()).phase());
        } finally { release.countDown(); writer.shutdown(); }
    }

}
