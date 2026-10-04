package org.qortium.crosschain.monero;

import java.util.List;

/** Native boundary is replaceable in lifecycle tests. Send primitives remain on a package-private seam. */
public interface MoneroWalletBackend extends AutoCloseable {
    /** Policy/admission rejection with no live new native handle. Existing checkpoints are never rewritten. */
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
        default MoneroWalletBackend open(MoneroKeys keys, org.qortium.crosschain.WalletScanStart start) throws Exception {
            if (start.mode() == org.qortium.crosschain.WalletScanStart.Mode.NEW_AT_CURRENT_TIP)
                throw new AdmissionRejected("XMR_SCAN_START_UNSUPPORTED");
            return open(keys, start.height() == null ? 0 : start.height());
        }
        default void close() throws Exception { }
    }
    /** Callback contains counts only; implementations must not perform extra native reads here. */
    record ScanProgress(long height, long targetHeight) { }
    /** Fixed diagnostic labels only. Phase reports never extend the scan inactivity deadline. */
    enum ReadPhase { CHECK, SYNC, DAEMON, HISTORY, SAVE, BALANCE }
    default Snapshot read(java.util.function.Consumer<ScanProgress> progress,
                          java.util.function.Consumer<ReadPhase> phase) throws Exception { return read(progress); }
    default Snapshot read(java.util.function.Consumer<ScanProgress> progress) throws Exception { return read(); }
    Snapshot read() throws Exception;
    /** Cached initialization metadata, never a native getter. Negative means unavailable in a test backend. */
    default long restoreHeight() { return -1; }
    default String initializationMode() { return "RESTORE_FROM_HEIGHT"; }
    /** Key-free cached provider metadata only, safe to read without entering JNI. */
    default org.qortium.crosschain.WalletServerPool.Status servers() { return null; }
    void close() throws Exception;
}
