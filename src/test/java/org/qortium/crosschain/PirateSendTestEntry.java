package org.qortium.crosschain;

/** Test-only adapter into the package-private native leg; production can enter only through the service. */
public final class PirateSendTestEntry {
    public static String sendWithLifecycle(String entropy, String recipient, long amount, String memo, PirateChainSendService.Lifecycle lifecycle) throws ForeignBlockchainException {
        return PirateChain.getInstance().sendCoins(entropy, recipient, amount, memo, lifecycle);
    }
    public static String send(String entropy, String recipient, long amount, String memo) throws ForeignBlockchainException {
        return PirateChain.getInstance().sendCoins(entropy, recipient, amount, memo, new PirateChainSendService.Lifecycle() {
            public void beforeNative() { }
            public void broadcast(String txid) { }
        });
    }
}
