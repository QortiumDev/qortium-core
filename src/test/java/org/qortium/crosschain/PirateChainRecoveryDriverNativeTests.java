package org.qortium.crosschain;

import org.json.JSONObject;
import org.junit.Test;
import org.qortium.api.model.crosschain.PirateChainBalance;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryRequest;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryResult;
import org.qortium.controller.PirateUnifiedLoopbackLightwalletd;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Explicitly opt-in proof that Core's R2 recovery driver works against the REAL native
 * library: a verified import through {@link PirateWallet#importVerifiedSpendingKey}
 * persists the durable record, and repeated {@link PirateWallet#progressRecovery} passes
 * issue the real native rescan, observe it, and clear the record only when the native
 * spendability authority is terminal-safe — recovering the foreign note. This closes the
 * controller-boundary gap that the scripted-fake driver tests cannot cover.
 */
public class PirateChainRecoveryDriverNativeTests {

	private static final String RUN_PROPERTY = "qortium.runPirateRecoveryDriverNativeTests";
	private static final String BUNDLE_PATH_PROPERTY = "qortium.pirateUnifiedBundlePath";
	private static final String STORAGE_PATH_PROPERTY = "qortium.pirateRecoveryDriverStoragePath";
	private static final int DEFAULT_BIRTHDAY = (int) PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT;

	@Test
	public void testDriverDrivesRealNativeRecoveryToCompletion() throws Exception {
		assumeTrue("Set -D" + RUN_PROPERTY + "=true to execute the native driver acceptance",
				Boolean.getBoolean(RUN_PROPERTY));
		String bundlePath = System.getProperty(BUNDLE_PATH_PROPERTY);
		assumeTrue("Set -D" + BUNDLE_PATH_PROPERTY + "=/absolute/path/to/the staged bundle",
				bundlePath != null && !bundlePath.isBlank());
		String storagePath = System.getProperty(STORAGE_PATH_PROPERTY);
		assumeTrue("Set -D" + STORAGE_PATH_PROPERTY + "=/absolute/new/path/to/temporary storage",
				storagePath != null && !storagePath.isBlank() && Path.of(storagePath).isAbsolute());

		Path bundle = Path.of(bundlePath).toAbsolutePath().normalize();
		Path storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
		assertFalse("Driver storage path already exists", Files.exists(storageRoot));
		String libraryFilename = org.qortium.controller.ZcashFamilyWalletController.resolveRustLibFilename();
		assumeTrue("The current host has no mapped Pirate Unified library", libraryFilename != null);
		Path library = bundle.resolve(libraryFilename);
		assumeTrue("Staged bundle lacks the host library (run the artifact acceptance first)",
				Files.isRegularFile(library));
		Files.createDirectories(storageRoot);

		ZcashFamilyWalletConfig config = new ZcashFamilyWalletConfig("Pirate Chain", "ARRR", "PirateChain",
				"legacy-signature", "ARRRWalletEncryption", "zs", () -> DEFAULT_BIRTHDAY, () -> null,
				() -> true, () -> "unified-signature", () -> false, () -> storageRoot.resolve("wallets"));
		PirateWallet wallet = new PirateWallet(config, entropy((byte) 11), false, false);
		Path walletStorage = wallet.getUnifiedStorage().getStorageDirectory();

		try (PirateUnifiedLoopbackLightwalletd lightwalletd = new PirateUnifiedLoopbackLightwalletd(true)) {
			ZcashFamilyNativeCoordinator.getInstance().execute("Pirate recovery driver native acceptance",
					ZcashFamilyNativeCoordinator.SYNC_TIMEOUT, adapter -> {
						adapter.loadLibrary(library);
						assertTrue("Pirate Unified JNI library did not load", adapter.isLoaded());
						adapter.initLogging();
						// TEMP-DIAGNOSTIC: capture the native decision log
						adapter.invokeJson(new JSONObject()
								.put("method", "set_debug_logging_enabled").put("enabled", true)
								.toString(), false);

						// Donor: export the foreign spending key and its address.
						initNativeWallet(adapter, storageRoot.resolve("donor"), (byte) 7,
								lightwalletd.endpoint());
						org.json.JSONArray donorExport = new org.json.JSONArray(adapter.execute("export", ""));
						String donorAddress = donorExport.getJSONObject(0).getString("address");
						String donorSpendingKey = donorExport.getJSONObject(0).getString("private_key");

						// Recipient: the PirateWallet-owned namespace, synced for birthday validation.
						String recipientWalletId = initNativeWallet(adapter, walletStorage, (byte) 11,
								lightwalletd.endpoint());
						assertNotEquals("Recipient derived the donor address", donorAddress,
								new org.json.JSONArray(adapter.execute("export", ""))
										.getJSONObject(0).getString("address"));
						// The import needs the persisted chain tip, written by the sync
						// engine's finalization shortly after sync progress completes; the
						// sync must then be cancelled CLEANLY or its un-graceful stop
						// zeroes the persisted tip again.
						syncToTip(adapter);
						awaitKnownChainTip(adapter, recipientWalletId);
						JSONObject preImportCancel = new JSONObject(adapter.invokeJson(new JSONObject()
								.put("method", "cancel_sync").put("wallet_id", recipientWalletId)
								.toString(), false));
						assertTrue("Pre-import sync cancellation failed", preImportCancel.optBoolean("ok"));
						awaitKnownChainTip(adapter, recipientWalletId);

						// The R1 wallet method: imports AND persists the durable driver record.
						PirateChainVerifiedRecoveryRequest request = new PirateChainVerifiedRecoveryRequest();
						request.pool = "sapling";
						request.spendingKey = donorSpendingKey;
						request.expectedAddress = donorAddress;
						request.addressIndex = 0;
						request.birthdayHeight = (int) PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT;
						PirateChainVerifiedRecoveryResult imported =
								wallet.importVerifiedSpendingKey(adapter, request);
						assertTrue("Import did not require a rescan", imported.rescanRequired);
						assertEquals("Import floor drifted",
								Long.valueOf(PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT),
								imported.requiredRescanFromHeight);
						assertTrue("Durable driver record was not persisted", wallet.hasPendingRecovery());
						assertEquals(ZcashFamilyWallet.RecoveryProgress.PENDING, wallet.peekRecoveryProgress());

						// The R2 driver against the real native library: issue, observe, clear.
						ZcashFamilyWallet.RecoveryProgress progress = null;
						long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
						while (System.nanoTime() < deadline) {
							progress = wallet.progressRecovery(adapter);
							if (progress == ZcashFamilyWallet.RecoveryProgress.RECOVERED)
								break;
							assertTrue("Driver reported an unexpected progress: " + progress,
									progress == ZcashFamilyWallet.RecoveryProgress.PENDING
											|| progress == ZcashFamilyWallet.RecoveryProgress.RECOVERING);
							Thread.sleep(100L);
						}
						assertEquals("Driver never reached RECOVERED",
								ZcashFamilyWallet.RecoveryProgress.RECOVERED, progress);
						assertFalse("Durable record survived completion", wallet.hasPendingRecovery());
						assertNull("Completed recovery left a floor in storage",
								wallet.getUnifiedStorage().read().getRecoveryRescanFromHeight());
						assertEquals(ZcashFamilyWallet.RecoveryProgress.RECOVERED,
								wallet.peekRecoveryProgress());

						// The foreign note is recovered in the driver-owned wallet, read through
						// the same typed path Core uses for Unified wallets.
						PirateChainBalance balance = wallet.getWalletBalances(adapter);
						assertEquals("Recovered note missing from total balance",
								PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_VALUE,
								balance.zbalance);
						assertEquals("Recovered note missing from verified balance",
								PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_VALUE,
								balance.verified_zbalance);

						JSONObject cancelled = new JSONObject(adapter.invokeJson(new JSONObject()
								.put("method", "cancel_sync").put("wallet_id", recipientWalletId)
								.toString(), false));
						assertTrue("Final sync cancellation failed", cancelled.optBoolean("ok"));
						return null;
					});

			assertEquals("Driver acceptance attempted a forbidden transaction RPC", 0,
					lightwalletd.forbiddenRpcCount());
			assertEquals("Driver acceptance attempted an RPC outside the deterministic fixture", 0,
					lightwalletd.unexpectedRpcCount());
		}
	}

	private static byte[] entropy(byte marker) {
		byte[] entropy = new byte[32];
		Arrays.fill(entropy, marker);
		return entropy;
	}

	private static String initNativeWallet(ZcashFamilyNativeAdapter adapter, Path storage, byte marker,
			String endpoint) throws Exception {
		JSONObject configured = new JSONObject(adapter.configureStorage(storage.toString(),
				"qortium-recovery-driver-" + marker));
		assertTrue("Storage was not initialized: " + storage, configured.optBoolean("initialized"));
		String seed = new JSONObject(adapter.getSeedPhraseFromEntropyB64(
				Base64.getEncoder().encodeToString(entropy(marker)))).getString("seedPhrase");
		JSONObject initialized = new JSONObject(adapter.initFromSeed(endpoint, "", seed,
				Long.toString(PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT), "", ""));
		String walletId = initialized.getString("wallet_id");
		assertFalse("Wallet initialization returned no id", walletId.isBlank());
		return walletId;
	}

	private static void awaitKnownChainTip(ZcashFamilyNativeAdapter adapter, String walletId)
			throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		do {
			JSONObject response = new JSONObject(adapter.invokeJson(new JSONObject()
					.put("method", "get_spendability_status").put("wallet_id", walletId)
					.toString(), false));
			JSONObject result = response.optBoolean("ok") ? response.optJSONObject("result") : null;
			if (result != null && result.optLong("target_height", 0L) > 0L)
				return;
			Thread.sleep(100L);
		} while (System.nanoTime() < deadline);
		throw new AssertionError("Persisted chain tip never became known");
	}

	private static void syncToTip(ZcashFamilyNativeAdapter adapter) throws Exception {
		JSONObject syncStarted = new JSONObject(adapter.execute("sync", ""));
		assertEquals("Sync command was not accepted", "success", syncStarted.getString("result"));
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
		do {
			JSONObject status = new JSONObject(adapter.execute("syncStatus", ""));
			long height = new JSONObject(adapter.execute("height", "")).getLong("height");
			boolean inProgress = status.optBoolean("in_progress", false)
					|| status.optBoolean("syncing", false);
			if (height >= PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT && !inProgress)
				return;
			Thread.sleep(100L);
		} while (System.nanoTime() < deadline);
		throw new AssertionError("Sync did not reach the deterministic tip");
	}
}
