package org.qortium.crosschain;

import java.io.IOException;
import java.nio.file.Path;

/** Native-free wallet double, used by controller ownership integration tests. */
public class PirateSessionTestWallet extends PirateWallet {
    public PirateSessionTestWallet(byte[] entropy, boolean nullSeed, Path root) throws IOException {
        super(new ZcashFamilyWalletConfig("Pirate Chain", "ARRR", "PirateChain", "test",
                "test", "zs", () -> 1, () -> null, () -> true, () -> "test", () -> false, () -> root),
                entropy, nullSeed, false);
        setReady(true);
    }
    @Override public boolean isInitialized() { return true; }
    @Override public String getWalletAddress() { return "zs" + "a".repeat(40); }
    @Override public boolean prepareForSwitch(ZcashFamilyNativeAdapter adapter) { return true; }
    @Override public boolean save() { return true; }
    @Override public void cleanupAfterSwitch() { }
}
