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
    record Failure(FailureReason reason, Phase phase, long elapsedMillis, long progressAgeMillis, long advances,
                   MoneroWalletBackend.ReadPhase readPhase, long readPhaseAgeMillis) { }
    private Phase phase = Phase.IDLE;
    private Failure failure;
    private Failure overdueRead; // temporary unavailability, never a fatal-latch reset
    private MoneroWalletBackend.ReadPhase readPhase;
    private long readPhaseAt;
    synchronized Failure overdueRead() { return overdueRead; }
    private long lastAdvanceAt, readHighWater, advances;
    private final LongSupplier monotonic;
    synchronized Failure failure() { return failure; } // internal diagnostic, never wallet authority

    public static final class Rejected extends RuntimeException {
        public final int status;
        public final String code;
        Rejected(int status, String code) { super(code); this.status = status; this.code = code; }
    }
    public record Session(String sessionId, String walletId, String state, String errorCode, Long restoreHeight, String initializationMode) { }
    public record Status(String sessionId, String walletId, String state, boolean send,
                         Long updatedAt, Progress progress, MoneroWalletBackend.Snapshot wallet,
                         Long readRetryAt, org.qortium.crosschain.WalletServerPool.Status servers, Long restoreHeight, String initializationMode, Progress preparation,
                         org.qortium.crosschain.WalletReadStatus read) { }

    /** Display identity is independent of, and never accepted as, session authority. */
    public record Progress(String scanId, long startHeight, long height, long targetHeight, long updatedAt) { }
    private String scanId;
    private Progress progress;
    private Progress preparation;
    private String initializationMode;
    private org.qortium.crosschain.WalletScanStart requestedStart;
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
    private long readRetryNanos;
    private Long readRetryAt;
    private int readFailures;
    private org.qortium.crosschain.WalletServerPool.Status servers;
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
    public synchronized Session session() { checkDeadline(); return new Session(sessionId, walletId, state, errorCode, walletId == null || transition ? null : restoreHeight, initializationMode); }

    public synchronized Session activate(byte[] coinSeed, long height, String expectedSession) {
        if (height < 0 || height > 500_000_000L) throw new Rejected(400, "XMR_INVALID_RESTORE_HEIGHT");
        return activate(coinSeed, org.qortium.crosschain.WalletScanStart.restore(height), expectedSession);
    }
    public synchronized Session activate(byte[] coinSeed, org.qortium.crosschain.WalletScanStart start, String expectedSession) {
        long height = start.height() == null ? 0 : start.height();
        checkAvailable();
        requireSession(expectedSession);
        if (height < 0 || height > 500_000_000L) throw new Rejected(400, "XMR_INVALID_RESTORE_HEIGHT");
        MoneroKeys keys = new MoneroKeys(coinSeed);
        if (keys.walletId.equals(walletId)) {
            keys.close();
            if (transition) {
                if (!start.equals(requestedStart)) throw new Rejected(409, "XMR_SWITCH_IN_PROGRESS");
            } else if (start.mode() == org.qortium.crosschain.WalletScanStart.Mode.NEW_AT_CURRENT_TIP
                    && !"NEW_AT_CURRENT_TIP".equals(initializationMode)) throw new Rejected(409, "XMR_EXISTING_WALLET");
            else if (start.height() != null && restoreHeight != height) throw new Rejected(409, "XMR_RESTORE_HEIGHT_MISMATCH");
            return session(); // tab returns/unlock do not restart an already active scan
        }
        if (transition) { keys.close(); throw new Rejected(409, "XMR_SWITCH_IN_PROGRESS"); }
        String nextSession = UUID.randomUUID().toString();
        try { if (sends != null) sends.machine.changeSession(nextSession); }
        catch (RuntimeException e) { keys.close(); fail(FailureReason.JOURNAL_FAILURE); throw new Rejected(503, "XMR_RESTART_REQUIRED"); }
        sessionId = nextSession;
        walletId = keys.walletId;
        restoreHeight = height; requestedStart = start; initializationMode = null;
        state = "OPENING"; errorCode = null; snapshot = null; updatedAt = 0;
        progress = null; preparation = null; activeRead = null; scanId = UUID.randomUUID().toString();
        clearReadRetry(); servers = null;
        transition = true;
        begin(Phase.LIFECYCLE);
        worker.execute(() -> switchWallet(keys, start));
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
        progress = null; preparation = null; activeRead = null; scanId = null;
        clearReadRetry(); servers = null;
        transition = true; begin(Phase.LIFECYCLE);
        worker.execute(() -> switchWallet(null, org.qortium.crosschain.WalletScanStart.resume()));
        return session();
    }

    public synchronized Status status(String expectedSession) {
        requireSession(expectedSession);
        if (sessionId == null || walletId == null) throw new Rejected(409, "XMR_NO_ACTIVE_WALLET");
        checkDeadline();
        boolean stale = snapshot != null && updatedAt != 0 && monotonic.getAsLong() - updatedNanos > TimeUnit.SECONDS.toNanos(30);
        return new Status(sessionId, walletId, stale && !failed && overdueRead == null ? "STALE" : state, false,
                updatedAt == 0 ? null : updatedAt, progress, stale || failed ? null : snapshot,
                readRetryAt, servers, transition ? null : restoreHeight, initializationMode, preparation, readStatus());
    }

    private org.qortium.crosschain.WalletReadStatus readStatus() {
        var idle = org.qortium.crosschain.WalletReadStatus.State.IDLE;
        if (closed || failed || transition)
            return new org.qortium.crosschain.WalletReadStatus(idle, null, null);
        if (phase == Phase.SCAN && activeRead != null) {
            var current = overdueRead == null ? org.qortium.crosschain.WalletReadStatus.State.IN_FLIGHT
                    : org.qortium.crosschain.WalletReadStatus.State.OVERDUE;
            var step = readPhase == null ? null : org.qortium.crosschain.WalletReadStatus.Phase.valueOf(readPhase.name());
            return new org.qortium.crosschain.WalletReadStatus(current, step, null);
        }
        return readRetryAt == null ? new org.qortium.crosschain.WalletReadStatus(idle, null, null)
                : new org.qortium.crosschain.WalletReadStatus(org.qortium.crosschain.WalletReadStatus.State.RETRY_SCHEDULED, null, readRetryAt);
    }

    private void switchWallet(MoneroKeys keys, org.qortium.crosschain.WalletScanStart start) {
        String admissionError = null;
        try {
            closeBackend();
            synchronized (this) { if (closed || failed) return; }
            if (keys != null) backend = factory.open(keys, start);
            synchronized (this) {
                checkDeadline();
                if (closed || failed) return;
                if (backend != null) {
                    if (backend.restoreHeight() >= 0) restoreHeight = backend.restoreHeight();
                    initializationMode = backend.initializationMode();
                }
                sends = backend instanceof MoneroSendBackend sendBackend ? new MoneroSendCoordinator(sendBackend, sessionId) : null;
                servers = backend == null ? null : backend.servers();
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
            if (closed || failed || transition || backend == null
                    || (readRetryAt != null && monotonic.getAsLong() - readRetryNanos < 0)) return;
            owner = sessionId; begin(Phase.SCAN); activeRead = read;
            lastAdvanceAt = startedAt; advances = 0;
            readPhase = MoneroWalletBackend.ReadPhase.CHECK; readPhaseAt = startedAt;
            readHighWater = progress == null ? restoreHeight : Math.max(restoreHeight, progress.height());
        }
        try {
            MoneroWalletBackend.Snapshot next = backend.read(counts -> publishProgress(owner, read, counts), step -> publishReadPhase(owner, read, step));
            synchronized (this) {
                checkDeadline();
                if (closed || failed || transition || activeRead != read || !owner.equals(sessionId)) return;
                // Only the entire read returning successfully can restore financial availability.
                if (overdueRead != null) {
                    LOGGER.info("XMR overdue read completed: elapsedMs={}",
                            TimeUnit.NANOSECONDS.toMillis(monotonic.getAsLong() - startedAt));
                    overdueRead = null;
                }
                lastAdvanceAt = monotonic.getAsLong();
                publishProgress(owner, read, new MoneroWalletBackend.ScanProgress(next.height(), next.targetHeight()));
                snapshot = next; updatedAt = System.currentTimeMillis(); updatedNanos = monotonic.getAsLong();
                state = next.synced() ? "READY" : "SCANNING";
                errorCode = null; clearReadRetry();
            }
        } catch (MoneroSendJournal.Failure e) { fail(FailureReason.JOURNAL_FAILURE);
        } catch (Exception e) {
            synchronized (this) {
                checkDeadline();
                if (!closed && !failed && !transition && activeRead == read && owner.equals(sessionId)) {
                    snapshot = null; progress = null; preparation = null; state = "UNAVAILABLE";
                    errorCode = e instanceof MoneroDaemonPool.Unavailable ? "XMR_DAEMON_UNAVAILABLE" : "XMR_WALLET_READ_UNAVAILABLE";
                    readFailures = Math.min(readFailures + 1, 30);
                    long delayMillis = e instanceof MoneroDaemonPool.Unavailable unavailable ? Math.max(2000, unavailable.delayMillis)
                            : Math.min(300_000L, 10_000L << Math.min(readFailures - 1, 5));
                    readRetryNanos = monotonic.getAsLong() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
                    readRetryAt = System.currentTimeMillis() + delayMillis;
                }
            }
        } catch (LinkageError e) { fail(FailureReason.NATIVE_LINKAGE); }
        finally {
            synchronized (this) {
                checkDeadline();
                // Cached, key-free provider health: API polling never calls the native/backend handle.
                if (owner.equals(sessionId) && !transition) servers = backend == null ? null : backend.servers();
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
        if (closed || failed || transition || overdueRead != null || activeRead != read || !owner.equals(sessionId)) return;
        if (counts == null || counts.height() < 0 || counts.targetHeight() <= 0
                || counts.height() > counts.targetHeight() || counts.targetHeight() > 500_000_000L) return;
        if (counts.height() < restoreHeight) {
            // Hash preparation is display-only: it cannot extend any inactivity/send/lifecycle deadline.
            if (preparation == null || counts.height() > preparation.height())
                preparation = new Progress(scanId, preparation == null ? counts.height() : preparation.startHeight(),
                        counts.height(), restoreHeight, System.currentTimeMillis());
            return;
        }
        preparation = null;
        // Only forward movement in this read extends scan inactivity. Target-only changes,
        // duplicate/backward counts and callbacks from a previous owner/read grant no time.
        if (counts.height() > readHighWater) {
            readHighWater = counts.height(); lastAdvanceAt = monotonic.getAsLong(); advances++;
        }
        // Polls and duplicate callbacks must not manufacture forward progress or a fresh ETA.
        if (progress != null && progress.height() == counts.height() && progress.targetHeight() == counts.targetHeight()) return;
        progress = new Progress(scanId, restoreHeight, counts.height(), counts.targetHeight(), System.currentTimeMillis());
    }

    private synchronized void publishReadPhase(String owner, Object read, MoneroWalletBackend.ReadPhase step) {
        checkDeadline();
        if (closed || failed || transition || activeRead != read || !owner.equals(sessionId) || step == null) return;
        if (readPhase != step) { readPhase = step; readPhaseAt = monotonic.getAsLong(); }
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
        if (transition || overdueRead != null || readRetryAt != null || sendQueued || sends == null) throw new Rejected(409, "XMR_WORK_IN_PROGRESS");
        MoneroSendCoordinator context = sends;
        CompletableFuture<MoneroSendContracts.View> result = new CompletableFuture<>();
        sendQueued = true;
        worker.execute(() -> {
            MoneroSendContracts.View value = null;
            Rejected failure = null;
            try {
                synchronized (this) {
                    checkAvailable(); requireSession(expected);
                    // A queued send must recheck read availability after the preceding read completes.
                    if (readRetryAt != null || overdueRead != null) throw new Rejected(409, "XMR_WORK_IN_PROGRESS");
                    begin(Phase.SEND);
                }
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
            } catch (Rejected e) { failure = e;
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

    private void clearReadRetry() { readRetryAt = null; readRetryNanos = 0; readFailures = 0; }

    private void end() { startedAt = 0; phase = Phase.IDLE; overdueRead = null; readPhase = null; }

    private void begin(Phase next) { phase = next; startedAt = monotonic.getAsLong(); overdueRead = null; readPhase = null; }

    private synchronized void fail(FailureReason reason) {
        if (failed) return; // retain the first cause, even after native cleanup fails
        failure = diagnostic(reason);
        // Fixed enums and durations/counts only: never log native exceptions, wallet/session identifiers or keys.
        LOGGER.warn("XMR worker failed: reason={} phase={} elapsedMs={} progressAgeMs={} advances={} readPhase={} readPhaseAgeMs={}",
                failure.reason(), failure.phase(), failure.elapsedMillis(), failure.progressAgeMillis(), failure.advances(),
                failure.readPhase(), failure.readPhaseAgeMillis());
        progress = null; preparation = null; activeRead = null;
        clearReadRetry();
        failed = true; state = "RESTART_REQUIRED"; errorCode = "XMR_RESTART_REQUIRED"; snapshot = null;
        if (workContext != null && sendWork != null) {
            try { workContext.machine.interruptWork(sendWork); }
            catch (RuntimeException ignored) { /* failed journal already holds; startup normalization remains mandatory */ }
        }
    }
    private Failure diagnostic(FailureReason reason) {
        long now = monotonic.getAsLong();
        return new Failure(reason, phase, startedAt == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(now - startedAt),
                phase == Phase.SCAN ? TimeUnit.NANOSECONDS.toMillis(now - lastAdvanceAt) : 0,
                phase == Phase.SCAN ? advances : 0,
                phase == Phase.SCAN ? readPhase : null,
                phase == Phase.SCAN ? TimeUnit.NANOSECONDS.toMillis(now - readPhaseAt) : 0);
    }
    private void checkDeadline() {
        boolean scanning = phase == Phase.SCAN && activeRead != null && !transition;
        long activity = scanning ? lastAdvanceAt : startedAt;
        if (closed || failed || startedAt == 0 || monotonic.getAsLong() - activity <= deadlineNanos) return;
        if (!scanning) { fail(FailureReason.DEADLINE); return; }
        if (overdueRead != null) return;
        overdueRead = diagnostic(FailureReason.DEADLINE);
        // Keep the single worker/handle in place. Do not interrupt JNI or close alongside the read.
        snapshot = null; progress = null; preparation = null; state = "UNAVAILABLE"; errorCode = null;
        LOGGER.warn("XMR read overdue: elapsedMs={} progressAgeMs={} advances={} readPhase={} readPhaseAgeMs={}",
                overdueRead.elapsedMillis(), overdueRead.progressAgeMillis(), overdueRead.advances(),
                overdueRead.readPhase(), overdueRead.readPhaseAgeMillis());
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
            clearReadRetry();
            closed = true; snapshot = null; progress = null; preparation = null; activeRead = null; state = "STOPPED";
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
