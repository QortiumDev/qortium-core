package org.qortium.crosschain.monero;

import java.time.Duration;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.concurrent.*;

/** One native wallet at a time, immutable owner-scoped snapshots, at most one lifecycle transition. */
public final class MoneroWalletService implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger(MoneroWalletService.class);
    enum Phase { IDLE, LIFECYCLE, SCAN, SEND }
    enum FailureReason { DEADLINE, LIFECYCLE_FAILURE, NATIVE_LINKAGE, JOURNAL_FAILURE }
    record Failure(FailureReason reason, Phase phase, long elapsedMillis, long progressAgeMillis, long advances) { }
    private Phase phase = Phase.IDLE;
    private Failure failure;
    private long lastAdvanceAt, readHighWater, advances;
    private final LongSupplier monotonic;
    synchronized Failure failure() { return failure; } // internal diagnostic, never wallet authority

    public static final class Rejected extends RuntimeException {
        public final int status;
        public final String code;
        Rejected(int status, String code) { super(code); this.status = status; this.code = code; }
    }
    public record Session(String sessionId, String walletId, String state, String errorCode) { }
    public record Status(String sessionId, String walletId, String state, boolean send,
                         Long updatedAt, Progress progress, MoneroWalletBackend.Snapshot wallet) { }

    /** Display identity is independent of, and never accepted as, session authority. */
    public record Progress(String scanId, long startHeight, long height, long targetHeight, long updatedAt) { }
    private String scanId;
    private Progress progress;
    private Object activeRead;

    private final MoneroWalletBackend.Factory factory;
    private final ScheduledThreadPoolExecutor worker;
    private final long deadlineNanos;
    private MoneroWalletBackend backend; // worker-owned
    private MoneroSendCoordinator sends; // published/invalidated under service monitor, native calls worker-only
    private boolean sendQueued, closeFailed;
    private MoneroSendMachine.Work sendWork;
    private MoneroSendCoordinator workContext;

    private String sessionId;
    private String walletId;
    private String state = "IDLE";
    private String errorCode;
    private long restoreHeight;
    private long startedAt;
    private long updatedAt;
    private long updatedNanos;
    private boolean transition;
    private boolean failed;
    private boolean closed;
    private MoneroWalletBackend.Snapshot snapshot;

    public MoneroWalletService(MoneroWalletBackend.Factory factory) {
        this(factory, Duration.ofSeconds(90), Duration.ofSeconds(2));
    }
    MoneroWalletService(MoneroWalletBackend.Factory factory, Duration deadline, Duration poll) {
        this(factory, deadline, poll, System::nanoTime);
    }
    MoneroWalletService(MoneroWalletBackend.Factory factory, Duration deadline, Duration poll, LongSupplier monotonic) {
        this.monotonic = monotonic;
        this.factory = factory;
        this.deadlineNanos = deadline.toNanos();
        worker = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "XMR-wallet"); thread.setDaemon(true); return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        worker.scheduleWithFixedDelay(this::refresh, poll.toMillis(), poll.toMillis(), TimeUnit.MILLISECONDS);
    }

    // Privileged coordination only. Never returns keys, balances or another wallet's snapshot.
    public synchronized Session session() { checkDeadline(); return new Session(sessionId, walletId, state, errorCode); }

    public synchronized Session activate(byte[] coinSeed, long height, String expectedSession) {
        checkAvailable();
        requireSession(expectedSession);
        if (height < 0 || height > 500_000_000L) throw new Rejected(400, "XMR_INVALID_RESTORE_HEIGHT");
        MoneroKeys keys = new MoneroKeys(coinSeed);
        if (keys.walletId.equals(walletId)) {
            keys.close();
            if (restoreHeight != height) throw new Rejected(409, "XMR_RESTORE_HEIGHT_MISMATCH");
            return session(); // tab returns/unlock do not restart an already active scan
        }
        if (transition) { keys.close(); throw new Rejected(409, "XMR_SWITCH_IN_PROGRESS"); }
        String nextSession = UUID.randomUUID().toString();
        try { if (sends != null) sends.machine.changeSession(nextSession); }
        catch (RuntimeException e) { keys.close(); fail(FailureReason.JOURNAL_FAILURE); throw new Rejected(503, "XMR_RESTART_REQUIRED"); }
        sessionId = nextSession;
        walletId = keys.walletId;
        restoreHeight = height;
        state = "OPENING"; errorCode = null; snapshot = null; updatedAt = 0;
        progress = null; activeRead = null; scanId = UUID.randomUUID().toString();
        transition = true;
        begin(Phase.LIFECYCLE);
        worker.execute(() -> switchWallet(keys, height));
        return session();
    }

    public synchronized Session deactivate(String expectedSession) {
        checkAvailable(); requireSession(expectedSession);
        if (transition) throw new Rejected(409, "XMR_SWITCH_IN_PROGRESS");
        String nextSession = UUID.randomUUID().toString();
        try { if (sends != null) sends.machine.changeSession(nextSession); }
        catch (RuntimeException e) { fail(FailureReason.JOURNAL_FAILURE); throw new Rejected(503, "XMR_RESTART_REQUIRED"); }
        sessionId = nextSession; walletId = null;
        state = "CLOSING"; errorCode = null; snapshot = null; updatedAt = 0;
        progress = null; activeRead = null; scanId = null;
        transition = true; begin(Phase.LIFECYCLE);
        worker.execute(() -> switchWallet(null, 0));
        return session();
    }

    public synchronized Status status(String expectedSession) {
        requireSession(expectedSession);
        if (sessionId == null || walletId == null) throw new Rejected(409, "XMR_NO_ACTIVE_WALLET");
        checkDeadline();
        boolean stale = updatedAt != 0 && monotonic.getAsLong() - updatedNanos > TimeUnit.SECONDS.toNanos(30);
        return new Status(sessionId, walletId, stale && !failed ? "STALE" : state, false,
                updatedAt == 0 ? null : updatedAt, progress, stale || failed ? null : snapshot);
    }

    private void switchWallet(MoneroKeys keys, long height) {
        String admissionError = null;
        try {
            closeBackend();
            synchronized (this) { if (closed || failed) return; }
            if (keys != null) backend = factory.open(keys, height);
            synchronized (this) {
                checkDeadline();
                if (closed || failed) return;
                sends = backend instanceof MoneroSendBackend sendBackend ? new MoneroSendCoordinator(sendBackend, sessionId) : null;
                state = keys == null ? "IDLE" : "SCANNING";
                end();
            }
        } catch (MoneroWalletBackend.AdmissionRejected e) {
            admissionError = e.code;
        } catch (Exception | LinkageError e) {
            // Native exception text can contain wallet data. Never publish or log it.
            fail(FailureReason.LIFECYCLE_FAILURE);
        } finally {
            if (keys != null) keys.close();
            synchronized (this) {
                checkDeadline();
                if (admissionError != null && !closed && !failed) {
                    walletId = null; state = "ACTIVATION_REJECTED"; errorCode = admissionError; end();
                }
                // Release exactly once, after cleanup: a second activation may now acquire this slot.
                transition = false;
            }
            cleanupIfStopped();
        }
        refresh();
    }

    private void refresh() {
        String owner;
        Object read = new Object();
        synchronized (this) {
            if (closed || failed || transition || backend == null) return;
            owner = sessionId; begin(Phase.SCAN); activeRead = read;
            lastAdvanceAt = startedAt; advances = 0;
            readHighWater = progress == null ? restoreHeight : Math.max(restoreHeight, progress.height());
        }
        try {
            MoneroWalletBackend.Snapshot next = backend.read(counts -> publishProgress(owner, read, counts));
            synchronized (this) {
                checkDeadline();
                if (closed || failed || !owner.equals(sessionId)) return;
                publishProgress(owner, read, new MoneroWalletBackend.ScanProgress(next.height(), next.targetHeight()));
                snapshot = next; updatedAt = System.currentTimeMillis(); updatedNanos = monotonic.getAsLong();
                state = next.synced() ? "READY" : "SCANNING";
            }
        } catch (Exception e) {
            synchronized (this) {
                checkDeadline();
                if (!closed && !failed && owner.equals(sessionId)) { snapshot = null; progress = null; state = "UNAVAILABLE"; }
            }
        } catch (LinkageError e) { fail(FailureReason.NATIVE_LINKAGE); }
        finally {
            synchronized (this) {
                // An activation queued during a read has its own deadline and neutral state.
                if (owner.equals(sessionId)) end();
                if (activeRead == read) activeRead = null;
            }
            cleanupIfStopped();
        }
    }

    private synchronized void publishProgress(String owner, Object read, MoneroWalletBackend.ScanProgress counts) {
        // A native callback may arrive after a switch, timeout or the read itself. It grants no authority.
        checkDeadline();
        if (closed || failed || transition || activeRead != read || !owner.equals(sessionId)) return;
        if (counts == null || counts.height() < restoreHeight || counts.targetHeight() <= 0
                || counts.height() > counts.targetHeight() || counts.targetHeight() > 500_000_000L) return;
        // Only forward movement in this read extends scan inactivity. Target-only changes,
        // duplicate/backward counts and callbacks from a previous owner/read grant no time.
        if (counts.height() > readHighWater) {
            readHighWater = counts.height(); lastAdvanceAt = monotonic.getAsLong(); advances++;
        }
        // Polls and duplicate callbacks must not manufacture forward progress or a fresh ETA.
        if (progress != null && progress.height() == counts.height() && progress.targetHeight() == counts.targetHeight()) return;
        progress = new Progress(scanId, restoreHeight, counts.height(), counts.targetHeight(), System.currentTimeMillis());
    }

    synchronized CompletableFuture<MoneroSendContracts.View> prepareSend(MoneroSendContracts.Request request, String expected) {
        return submitSend(request, null, null, expected);
    }
    synchronized CompletableFuture<MoneroSendContracts.View> commitSend(String id, String digest, String expected) {
        return submitSend(null, id, digest, expected);
    }
    synchronized MoneroSendContracts.View cancelSend(String id, String expected) {
        checkAvailable(); requireSession(expected);
        if (transition || sends == null) throw new Rejected(409, "XMR_NO_ACTIVE_WALLET");
        try { return sends.machine.cancel(id, expected); }
        catch (MoneroSendJournal.Failure e) { fail(FailureReason.JOURNAL_FAILURE); throw new Rejected(503, "XMR_RESTART_REQUIRED"); }
        catch (MoneroSendMachine.Rejected e) { throw new Rejected(409, "XMR_SEND_NOT_READY"); }
    }
    synchronized MoneroSendContracts.View sendStatus(String id, String expected) {
        checkAvailable(); requireSession(expected);
        if (transition || sends == null) throw new Rejected(409, "XMR_NO_ACTIVE_WALLET");
        try { return sends.machine.status(id, expected); }
        catch (MoneroSendJournal.Failure e) { fail(FailureReason.JOURNAL_FAILURE); throw new Rejected(503, "XMR_RESTART_REQUIRED"); }
        catch (MoneroSendMachine.Rejected e) { throw new Rejected(409, "XMR_SEND_NOT_READY"); }
    }
    synchronized CompletableFuture<Void> reconcileSends(String expected) {
        return submitSend(null, null, null, expected).thenApply(ignored -> null);
    }
    private CompletableFuture<MoneroSendContracts.View> submitSend(MoneroSendContracts.Request request, String id, String digest, String expected) {
        checkAvailable(); requireSession(expected);
        if (transition || sendQueued || sends == null) throw new Rejected(409, "XMR_WORK_IN_PROGRESS");
        MoneroSendCoordinator context = sends;
        CompletableFuture<MoneroSendContracts.View> result = new CompletableFuture<>();
        sendQueued = true;
        worker.execute(() -> {
            MoneroSendContracts.View value = null;
            Rejected failure = null;
            try {
                synchronized (this) { checkAvailable(); requireSession(expected); begin(Phase.SEND); }
                context.reconcile(expected);
                if (id != null) context.validateCommit(id, expected);
                MoneroSendMachine.Admission admission;
                synchronized (this) {
                    checkAvailable(); requireSession(expected);
                    admission = request != null ? context.machine.prepare(request, expected)
                            : id != null ? context.machine.commit(id, digest, expected) : null;
                    sendWork = admission == null ? null : admission.work(); workContext = context;
                }
                Runnable beforePublish = () -> { synchronized (this) { checkDeadline(); } };
                value = admission == null ? null
                        : request != null ? context.finishPreparation(admission, request, beforePublish) : context.finishRelay(admission, beforePublish);
            } catch (MoneroSendJournal.Failure | LinkageError e) {
                synchronized (this) { fail(e instanceof LinkageError ? FailureReason.NATIVE_LINKAGE : FailureReason.JOURNAL_FAILURE); }
                failure = new Rejected(503, "XMR_RESTART_REQUIRED");
            } catch (Exception e) {
                failure = new Rejected(409, "XMR_SEND_NOT_READY"); // never leak native/parser messages
            } finally {
                synchronized (this) {
                    if (java.util.Objects.equals(expected, sessionId)) end();
                    sendQueued = false; sendWork = null; workContext = null;
                }
                cleanupIfStopped();
            }
            synchronized (this) {
                try { checkAvailable(); requireSession(expected); }
                catch (Rejected e) { failure = e; }
                if (failure == null) result.complete(value); else result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private void closeBackend() throws Exception {
        MoneroWalletBackend old = backend;
        backend = null; // close failure must never make a handle eligible for reuse
        synchronized (this) { sends = null; }
        if (old != null) {
            try { old.close(); }
            catch (Exception | LinkageError e) { closeFailed = true; throw e; }
        }
    }
    private void cleanupIfStopped() {
        synchronized (this) { if (!closed && !failed) return; }
        try { closeBackend(); } catch (Exception | LinkageError ignored) { }
    }

    private void end() { startedAt = 0; phase = Phase.IDLE; }

    private void begin(Phase next) { phase = next; startedAt = monotonic.getAsLong(); }

    private synchronized void fail(FailureReason reason) {
        if (failed) return; // retain the first cause, even after native cleanup fails
        long now = monotonic.getAsLong();
        failure = new Failure(reason, phase, startedAt == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(now - startedAt),
                phase == Phase.SCAN ? TimeUnit.NANOSECONDS.toMillis(now - lastAdvanceAt) : 0,
                phase == Phase.SCAN ? advances : 0);
        // Fixed enums and durations/counts only: never log native exceptions, wallet/session identifiers or keys.
        LOGGER.warn("XMR worker failed: reason={} phase={} elapsedMs={} progressAgeMs={} advances={}",
                failure.reason(), failure.phase(), failure.elapsedMillis(), failure.progressAgeMillis(), failure.advances());
        progress = null; activeRead = null;
        failed = true; state = "RESTART_REQUIRED"; errorCode = "XMR_RESTART_REQUIRED"; snapshot = null;
        if (workContext != null && sendWork != null) {
            try { workContext.machine.interruptWork(sendWork); }
            catch (RuntimeException ignored) { /* failed journal already holds; startup normalization remains mandatory */ }
        }
    }
    private void checkDeadline() {
        long activity = phase == Phase.SCAN && activeRead != null && !transition ? lastAdvanceAt : startedAt;
        if (!failed && startedAt != 0 && monotonic.getAsLong() - activity > deadlineNanos)
            fail(FailureReason.DEADLINE);
    }
    private void checkAvailable() {
        checkDeadline();
        if (closed) throw new Rejected(503, "XMR_STOPPED");
        if (failed) throw new Rejected(503, "XMR_RESTART_REQUIRED");
    }
    private void requireSession(String expectedSession) {
        if (!java.util.Objects.equals(sessionId, expectedSession)) throw new Rejected(409, "XMR_SESSION_CHANGED");
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            if (sends != null) {
                try { sends.machine.changeSession(UUID.randomUUID().toString()); }
                catch (RuntimeException e) { fail(FailureReason.JOURNAL_FAILURE); }
            }
            closed = true; snapshot = null; progress = null; activeRead = null; state = "STOPPED";
        }
        // Never race native close against our reader. A stuck JNI call cannot be made safe by interrupting Java.
        worker.execute(() -> {
            try {
                closeBackend();
                if (!closeFailed) factory.close(); // queued behind every native call, even after caller's wait times out
            } catch (Exception | LinkageError ignored) { }
        });
        worker.shutdown();
        try { worker.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
