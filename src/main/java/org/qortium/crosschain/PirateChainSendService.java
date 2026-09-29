package org.qortium.crosschain;

import java.io.IOException;
import java.util.concurrent.*;
import static org.qortium.crosschain.PirateChainSendJournal.Phase.*;

/** One admission and one native invocation per durable request. No retry or replay worker. */
public final class PirateChainSendService implements AutoCloseable {
    public record Request(String entropy58, String walletIdentityHash, String network,
            String idempotencyKey, String recipient, long amount, String memo, long fee) { }
    public interface Lifecycle {
        void beforeNative() throws IOException;
        void broadcast(String txid) throws IOException;
    }
    @FunctionalInterface public interface Sender {
        void send(Request request, Lifecycle lifecycle) throws Exception;
    }
    @FunctionalInterface public interface Admission {
        void check() throws ForeignBlockchainException;
    }
    private final PirateChainSendJournal journal;
    private final Sender sender;
    private final ThreadPoolExecutor executor;

    public PirateChainSendService(PirateChainSendJournal journal, Sender sender) {
        this.journal = journal;
        this.sender = sender;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), task -> {
                    Thread worker = new Thread(task, "ARRR durable send"); worker.setDaemon(true); return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized PirateChainSendJournal.Operation submit(Request request, Admission admission)
            throws IOException, PirateChainSendJournal.ConflictException, ForeignBlockchainException {
        if (!PirateChainSendJournal.walletIdentity(request.entropy58).equals(request.walletIdentityHash)
                || !journal.network().equals(request.network) || request.fee != PirateChain.getSendFeeAtomic())
            throw new IllegalArgumentException("ARRR_SEND_IDENTITY_MISMATCH");
        if (executor.isShutdown()) throw new PirateChainSendJournal.StorageException();
        String fingerprint = PirateChainSendJournal.fingerprint(request.network, request.recipient,
                request.amount, request.memo, request.fee);
        var existing = journal.existing(request.walletIdentityHash, request.idempotencyKey, fingerprint);
        if (existing != null) return existing;
        if (executor.getActiveCount() > 0 || !executor.getQueue().isEmpty())
            throw new ForeignBlockchainException.WalletBusyException("ARRR_SEND_BUSY");
        var operation = journal.admit(request.walletIdentityHash, request.idempotencyKey, fingerprint);
        // Reserve first: trade funding checks this reservation inside the native lane.
        // If trade already owns the lane, admission refuses this not-yet-started send.
        try { admission.check(); }
        catch (ForeignBlockchainException | RuntimeException failure) {
            journal.transition(operation.operationId(), FAILED, null, preSendReason(failure));
            throw failure;
        }
        try { executor.execute(() -> execute(operation.operationId(), request)); }
        catch (RejectedExecutionException stopped) {
            return journal.transition(operation.operationId(), FAILED, null, "INTERRUPTED_BEFORE_EXECUTION");
        }
        return operation;
    }

    private void execute(String id, Request request) {
        try {
            sender.send(request, new Lifecycle() {
                @Override public void beforeNative() throws IOException {
                    var operation = journal.transition(id, NATIVE_STARTED, null, null);
                    if (operation.phase() != NATIVE_STARTED) throw new PirateChainSendJournal.StorageException();
                }
                @Override public void broadcast(String txid) throws IOException {
                    journal.transition(id, BROADCAST, txid, null);
                }
            });
            // A sender must durably capture success itself, inside its native callback.
            finishUnanswered(id, "ARRR_SEND_NO_RESULT");
        } catch (Throwable failure) {
            // Deliberately never log exceptions or request material from a custody operation.
            try { finishUnanswered(id, preSendReason(failure)); }
            catch (IOException storageFailure) { /* journal quarantines this wallet; never retry */ }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private void finishUnanswered(String id, String reason) throws IOException {
        var current = journal.get(id);
        if (current.phase() == ACCEPTED) journal.transition(id, FAILED, null, reason);
        else if (current.phase() == NATIVE_STARTED)
            journal.transition(id, UNRESOLVED, null, "ARRR_SEND_OUTCOME_UNKNOWN");
    }
    private static String preSendReason(Throwable failure) {
        if (failure instanceof ForeignBlockchainException.InsufficientFundsException) return "ARRR_INSUFFICIENT_VERIFIED_FUNDS";
        if (failure instanceof ForeignBlockchainException.InvalidRecipientException) return "ARRR_RECIPIENT_INVALID";
        if (failure instanceof ForeignBlockchainException.WalletBusyException) return "ARRR_WALLET_BUSY";
        if (failure instanceof ForeignBlockchainException.WalletNotReadyException) return "ARRR_WALLET_NOT_READY";
        return "ARRR_SEND_FAILED_BEFORE_EXECUTION";
    }
    public PirateChainSendJournal.Operation get(String id) throws IOException { return journal.get(id); }
    public PirateChainSendJournal.Operation lookup(String wallet, String key) throws IOException { return journal.lookup(wallet, key); }
    public String blocking(String wallet) throws IOException { return journal.blocking(wallet); }
    /** Stop new admissions; retain the process lock while late native callbacks may exist. */
    public synchronized void stopAdmissions() { executor.shutdown(); }
    @Override public void close() throws IOException {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new IOException("ARRR send worker still active");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("ARRR send worker interrupted"); }
        journal.close();
    }
}
