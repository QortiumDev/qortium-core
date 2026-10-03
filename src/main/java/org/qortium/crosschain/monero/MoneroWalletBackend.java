package org.qortium.crosschain.monero;

import java.util.List;

/** Native boundary is replaceable in lifecycle tests. Send primitives remain on a package-private seam. */
public interface MoneroWalletBackend extends AutoCloseable {
    /** Only thrown after a new in-memory wallet has closed, before any durable marker/checkpoint. */
    final class AdmissionRejected extends Exception {
        public final String code;
        public AdmissionRejected(String code) { super(code); this.code = code; }
    }
    record Transaction(String txid, Long timestamp, Long height, boolean confirmed,
                       String incomingAtomic, String outgoingAtomic, String feeAtomic) { }
    record Snapshot(String address, long height, long targetHeight, boolean synced,
                    String balanceAtomic, String unlockedAtomic, List<Transaction> transactions) {
        public Snapshot { transactions = List.copyOf(transactions); }
    }
    interface Factory extends AutoCloseable {
        MoneroWalletBackend open(MoneroKeys keys, long restoreHeight) throws Exception;
        default void close() throws Exception { }
    }
    /** Callback contains counts only; implementations must not perform extra native reads here. */
    record ScanProgress(long height, long targetHeight) { }
    default Snapshot read(java.util.function.Consumer<ScanProgress> progress) throws Exception { return read(); }
    Snapshot read() throws Exception;
    void close() throws Exception;
}
