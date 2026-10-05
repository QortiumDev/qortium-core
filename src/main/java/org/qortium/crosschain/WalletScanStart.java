package org.qortium.crosschain;

/** Coin-neutral first-use intent. Resume never changes a saved wallet's discovery boundary. */
public record WalletScanStart(Mode mode, Long height) {
    public enum Mode { RESUME, RESTORE_FROM_HEIGHT, NEW_AT_CURRENT_TIP }
    public WalletScanStart {
        if (mode == null || (mode == Mode.RESTORE_FROM_HEIGHT) != (height != null)
                || (height != null && (height < 0 || height > 500_000_000L)))
            throw new IllegalArgumentException("Invalid wallet scan start");
    }
    public static WalletScanStart resume() { return new WalletScanStart(Mode.RESUME, null); }
    public static WalletScanStart restore(long height) { return new WalletScanStart(Mode.RESTORE_FROM_HEIGHT, height); }
    public static WalletScanStart newAtTip() { return new WalletScanStart(Mode.NEW_AT_CURRENT_TIP, null); }
}
