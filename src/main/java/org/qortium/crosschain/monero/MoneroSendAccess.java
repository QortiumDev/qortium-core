package org.qortium.crosschain.monero;

import java.util.concurrent.*;

/** Explicit public projection: internal candidates, journal and native receipts never cross this boundary. */
public final class MoneroSendAccess {
    public record Operation(String operationId, String state, String quoteDigest, String address,
                            String amountAtomic, String feeAtomic, String txid, boolean walletHeld,
                            long expiresAt, long confirmations, boolean unlocked) {
        @Override public String toString() { return "XMR send operation [redacted]"; }
    }
    private final MoneroWalletService service;
    public MoneroSendAccess(MoneroWalletService service) { this.service = service; }

    public static void validateId(String id) { MoneroSendContracts.uuid(id); }
    public static void validateDigest(String digest) { MoneroSendContracts.hex(digest, 64); }
    public static void validateRequest(String id, String address, String amount) {
        new MoneroSendContracts.Request(id, address, amount);
    }
    public void owner(String session) {
        // A null token is never an owner, even when the runtime has no active session.
        if (session == null) throw new MoneroWalletService.Rejected(409, "XMR_SESSION_CHANGED");
        service.status(session);
    }
    public CompletableFuture<Void> prepare(String id, String address, String amount, String session) {
        owner(session);
        return service.prepareSend(new MoneroSendContracts.Request(id, address, amount), session).thenApply(ignored -> null);
    }
    public CompletableFuture<Void> commit(String id, String digest, String session) {
        validateId(id); validateDigest(digest); owner(session);
        return service.commitSend(id, digest, session).thenApply(ignored -> null);
    }
    public Operation cancel(String id, String session) {
        validateId(id); owner(session);
        synchronized (service) { return project(service.cancelSend(id, session)); }
    }
    public Operation status(String id, String session) {
        validateId(id); owner(session);
        synchronized (service) { return project(service.sendStatus(id, session)); }
    }
    public CompletableFuture<Void> reconcile(String id, String session) {
        // Check operation ownership/existence before admitting native work.
        status(id, session);
        return service.reconcileSends(session);
    }
    private static Operation project(MoneroSendContracts.View v) {
        return new Operation(v.operationId(), v.state().name(), v.quoteDigest(), v.address(),
                v.amountAtomic(), v.feeAtomic(), v.txid(), v.walletHeld(), v.expiresAt(),
                v.confirmations(), v.unlocked());
    }
}
