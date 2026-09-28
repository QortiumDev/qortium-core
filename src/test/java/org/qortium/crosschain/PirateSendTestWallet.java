package org.qortium.crosschain;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Native-free Unified wallet double for the send flow tests. Unlike {@link PirateSessionTestWallet}
 * it keeps every native-facing step of a send real (balance, unlock, export, send all go through
 * the coordinator's adapter); only the light-client endpoint machinery, which needs a real
 * lightwalletd, is stubbed. The synchronized check still reads height/tip through the adapter.
 */
public class PirateSendTestWallet extends PirateWallet {

	public PirateSendTestWallet(byte[] entropy, boolean nullSeed, Path root) throws IOException {
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
	@Override public boolean prepareForSynchronization(ZcashFamilyNativeAdapter adapter) { return true; }

	@Override
	public <T> T withValidatedServerSelectionLease(ZcashFamilyNativeAdapter adapter,
			ZcashFamilyNativeCoordinator.NativeOperation<T> operation) throws Exception {
		return operation.execute(adapter);
	}

	@Override
	public boolean isSynchronized() {
		return ZcashFamilyNativeCoordinator.getInstance().execute("test wallet synchronization check",
				adapter -> ZcashFamilyWallet.isHeightSynchronized(getHeight(adapter), getChainTip(adapter), 0));
	}

	@Override
	protected Integer getChainTip(ZcashFamilyNativeAdapter adapter) {
		JSONObject json = new JSONObject(adapter.execute("info", ""));
		return json.has("latest_block_height") ? json.getInt("latest_block_height") : null;
	}
}
