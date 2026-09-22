package org.qortium.controller;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.qortium.crosschain.ForeignBlockchainException;

/** Process-wide ownership survives controller stop/start. No spending material is retained. */
public final class ArrrWalletOwnership {
    public record State(String identity, String revision, String address) { }
    private final java.util.Map<String, String> addresses = new java.util.LinkedHashMap<>();
    private final AtomicReference<State> state = new AtomicReference<>(new State(null, UUID.randomUUID().toString(), null));

    public State snapshot() { return state.get(); }

    public void requireOwner(String identity) throws ForeignBlockchainException.WalletBusyException {
        String owner = state.get().identity();
        if (owner == null || !owner.equals(identity))
            throw new ForeignBlockchainException.WalletBusyException("ARRR_WALLET_NOT_ACTIVE");
    }

    /** Called only on the native coordinator, after successful wallet selection. */
    public synchronized void selected(String identity, String address) {
        if (address != null) addresses.put(identity, address);
        while (addresses.size() > 64) addresses.remove(addresses.keySet().iterator().next());
        state.updateAndGet(previous -> new State(identity,
                identity.equals(previous.identity()) ? previous.revision() : UUID.randomUUID().toString(), address));
    }

    public synchronized String address(String identity) { return addresses.get(identity); }

    public void requireRevision(String revision) throws ForeignBlockchainException {
        if (revision == null || !revision.equals(state.get().revision()))
            throw new ForeignBlockchainException("ARRR_SESSION_CHANGED: refresh wallet status and confirm again");
    }

    public void lifecycleChanged() {
        state.updateAndGet(previous -> new State(previous.identity(), UUID.randomUUID().toString(), previous.address()));
    }
}
