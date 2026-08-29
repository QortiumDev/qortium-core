package org.qortium.controller;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import org.qortium.crosschain.ZcashFamilyNativeAdapter;
import org.qortium.crosschain.ZcashFamilyNativeCoordinator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Explicitly opt-in end-to-end proof of external-wallet recovery: a fresh wallet imports a
 * DIFFERENT account's spending key through the upstream verified request, drives the exact
 * dedicated rescan, and recovers that account's earlier synthetic note — plus the fail-closed
 * counterexamples and in-process reopen persistence. Unfunded, loopback-only, deterministic.
 */
public class PirateUnifiedRecoveryAcceptanceTests {

	private static final String RUN_PROPERTY = "qortium.runPirateUnifiedRecoveryAcceptanceTests";
	private static final String ARTIFACT_PATH_PROPERTY = "qortium.pirateUnifiedArtifactPath";
	private static final String BUNDLE_PATH_PROPERTY = "qortium.pirateUnifiedBundlePath";
	private static final String STORAGE_PATH_PROPERTY = "qortium.pirateUnifiedRecoveryStoragePath";
	private static final String DONOR_ADDRESS =
			"zs1ra3g8uphtg8ad7p8ye76pg06nr9rg5y8m5ycq40vpw4nvae6amehenaafv02g3dny9myxz7f60s";

	@Test
	public void testVerifiedImportRecoversForeignHistoryWithCounterexamplesAndReopen() throws Exception {
		assumeTrue("Set -D" + RUN_PROPERTY + "=true to execute recovery acceptance",
				Boolean.getBoolean(RUN_PROPERTY));
		String artifactPath = System.getProperty(ARTIFACT_PATH_PROPERTY);
		assumeTrue("Set -D" + ARTIFACT_PATH_PROPERTY + "=/absolute/path/to/the/pinned archive",
				artifactPath != null && !artifactPath.isBlank());
		String bundlePath = System.getProperty(BUNDLE_PATH_PROPERTY);
		assumeTrue("Set -D" + BUNDLE_PATH_PROPERTY + "=/absolute/path/to/the staged bundle",
				bundlePath != null && !bundlePath.isBlank());
		String storagePath = System.getProperty(STORAGE_PATH_PROPERTY);
		assumeTrue("Set -D" + STORAGE_PATH_PROPERTY + "=/absolute/new/path/to/temporary storage",
				storagePath != null && !storagePath.isBlank() && Path.of(storagePath).isAbsolute());

		Path artifact = Path.of(artifactPath).toAbsolutePath().normalize();
		Path bundle = Path.of(bundlePath).toAbsolutePath().normalize();
		Path storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
		assertFalse("Recovery storage path already exists", Files.exists(storageRoot));
		String libraryFilename = ZcashFamilyWalletController.resolveRustLibFilename();
		assumeTrue("The current host has no mapped Pirate Unified library", libraryFilename != null);
		PirateUnifiedWalletBundle.FileRecord trustedRecord =
				PirateUnifiedWalletBundle.validateArtifact(artifact, bundle, libraryFilename);
		Path library = bundle.resolve(libraryFilename);
		Files.createDirectories(storageRoot);

		try (PirateUnifiedLoopbackLightwalletd lightwalletd = new PirateUnifiedLoopbackLightwalletd(true)) {
			ZcashFamilyNativeCoordinator.getInstance().execute("Pirate Unified recovery acceptance",
					ZcashFamilyNativeCoordinator.SYNC_TIMEOUT, adapter -> {
						PirateUnifiedWalletBundle.validateSelectedLibrary(library, trustedRecord);
						adapter.loadLibrary(library);
						assertTrue("Pirate Unified JNI library did not load", adapter.isLoaded());
						adapter.initLogging();

						// Donor account: derive only; never synchronized, never funded here.
						String donorSeed = seedFromEntropy(adapter, (byte) 7);
						initWallet(adapter, storageRoot.resolve("donor"), "qortium-recovery-donor",
								donorSeed, lightwalletd.endpoint());
						JSONObject donorEntry = firstExportEntry(adapter);
						String donorAddress = donorEntry.getString("address");
						String donorSpendingKey = donorEntry.getString("private_key");
						assertEquals("Donor account address drifted from the deterministic vector",
								DONOR_ADDRESS, donorAddress);
						assertFalse("Donor spending key export was empty", donorSpendingKey.isBlank());

						// Recipient account: different entropy, fresh storage, no donor knowledge.
						String recipientSeed = seedFromEntropy(adapter, (byte) 9);
						JSONObject recipientInit = initWallet(adapter, storageRoot.resolve("recipient"),
								"qortium-recovery-recipient", recipientSeed, lightwalletd.endpoint());
						String recipientWalletId = recipientInit.getString("wallet_id");
						String recipientAddress = firstExportEntry(adapter).getString("address");
						assertNotEquals("Recipient derived the donor address", donorAddress, recipientAddress);

						// The import's birthday validation needs a persisted known chain tip.
						// That row is written by the sync engine's witness-check finalization
						// (shortly AFTER sync progress reports complete), and a sync task that
						// is stopped un-gracefully zeroes it again — so the operational
						// contract is: finish the sync, cancel it CLEANLY, then import.
						syncToTip(adapter);
						awaitKnownChainTip(adapter, recipientWalletId);
						cancelSync(adapter, recipientWalletId);
						awaitKnownChainTip(adapter, recipientWalletId);
						assertEquals("Recipient saw a balance before importing anything", 0L,
								typedTotalBalance(adapter, recipientWalletId));

						// Fail-closed counterexamples: each must reject, and each must leave the
						// wallet's key groups untouched. `export` only exposes the primary key
						// group, so the key-group set is compared either side of every rejection.
						assertRejectedWithoutMutation(adapter, recipientWalletId, donorSpendingKey,
								recipientAddress, 0, PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_HEIGHT,
								"Expected address is not controlled by the spending key");
					assertRejectedWithoutMutation(adapter, recipientWalletId, mixCase(donorSpendingKey),
								donorAddress, 0, PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_HEIGHT,
								"Invalid Sapling spending key");
						assertRejectedWithoutMutation(adapter, recipientWalletId, donorSpendingKey,
								donorAddress, 0, PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT + 10L,
								"Birthday height exceeds the wallet's known chain tip");

						// The real verified import.
						JSONObject imported = importResult(adapter, recipientWalletId, donorSpendingKey,
								donorAddress, 0, PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT);
						assertFalse("First verified import claimed already_imported",
								imported.getBoolean("already_imported"));
						assertTrue("Verified import did not require a rescan",
								imported.getBoolean("rescan_required"));
						assertEquals("Verified import floor drifted from the requested birthday",
								PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT,
								imported.getLong("required_rescan_from_height"));
						assertEquals("Verified import returned a non-canonical address",
								donorAddress, imported.getString("address"));
					long keyId = imported.getLong("key_id");

					// The 32-bit address index is legacy response/display metadata in v1.1.9,
					// not an ownership boundary or derivation cursor. Direct proof recovered
					// and persisted the full 88-bit cursor, so changing only this metadata is
					// still an idempotent import of the same key group.
					JSONObject alternateIndexRetry = importResult(adapter, recipientWalletId,
							donorSpendingKey, donorAddress, 1,
							PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT);
					assertTrue("Alternate legacy-index retry was not idempotent",
							alternateIndexRetry.getBoolean("already_imported"));
					assertEquals("Alternate legacy-index retry created a new key group",
							keyId, alternateIndexRetry.getLong("key_id"));
					assertEquals("Alternate legacy-index metadata was not echoed",
							1, alternateIndexRetry.getInt("address_index"));

					// Bech32 case idempotency: an all-uppercase retry is the same import.
						JSONObject uppercaseRetry = importResult(adapter, recipientWalletId,
								donorSpendingKey.toUpperCase(Locale.ROOT), donorAddress, 0,
								PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT);
						assertTrue("Uppercase exact retry was not idempotent",
								uppercaseRetry.getBoolean("already_imported"));
						assertEquals("Uppercase exact retry created a new key group",
								keyId, uppercaseRetry.getLong("key_id"));

						// Drive the dedicated rescan and wait for the spendability authority.
						JSONObject rescan = object(adapter.invokeJson(new JSONObject()
								.put("method", "rescan").put("wallet_id", recipientWalletId)
								.put("from_height", PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT)
								.toString(), false), "recovery rescan");
						assertTrue("Recovery rescan was not acknowledged", rescan.optBoolean("ok")
								&& rescan.getJSONObject("result").optBoolean("acknowledged"));
						awaitSpendabilityTerminal(adapter, recipientWalletId);
						// The completed rescan session lives on as a follow sync and suppresses
						// live reads until it is ended; cancel cleanly before reading state.
						cancelSync(adapter, recipientWalletId);

						// The imported key's earlier note is now recovered in the recipient wallet.
						assertRecoveredState(adapter, donorAddress, recipientWalletId);

						// An exact retry after completion is a pure no-op with a null floor.
						JSONObject completedRetry = importResult(adapter, recipientWalletId, donorSpendingKey,
								donorAddress, 0, PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT);
						assertTrue("Completed exact retry was not idempotent",
								completedRetry.getBoolean("already_imported"));
						assertFalse("Completed exact retry re-required a rescan",
								completedRetry.getBoolean("rescan_required"));
						assertTrue("Completed exact retry reported a pending floor",
								completedRetry.isNull("required_rescan_from_height"));

						// In-process reopen: the imported key and its recovered history persist.
						JSONObject reopened = initWallet(adapter, storageRoot.resolve("recipient"),
								"qortium-recovery-recipient", recipientSeed, lightwalletd.endpoint());
						assertEquals("Reopen changed the wallet identity", recipientWalletId,
								reopened.getString("wallet_id"));
						// `export` returns only the primary key group, so the imported key's
						// survival is proven through the key-group listing instead.
						assertTrue("Reopened wallet lost the imported key group",
								listsKeyId(adapter, recipientWalletId, keyId));
						syncToTip(adapter);
						assertRecoveredState(adapter, donorAddress, recipientWalletId);

						JSONObject cancelled = object(adapter.invokeJson(new JSONObject()
								.put("method", "cancel_sync").put("wallet_id", recipientWalletId)
								.toString(), false), "final sync cancellation");
						assertTrue("Final sync cancellation failed", cancelled.optBoolean("ok"));
						return null;
					});

			assertEquals("Recovery acceptance attempted a forbidden transaction RPC", 0,
					lightwalletd.forbiddenRpcCount());
			assertEquals("Recovery acceptance attempted an RPC outside the deterministic fixture", 0,
					lightwalletd.unexpectedRpcCount());
		}
	}

	private static String seedFromEntropy(ZcashFamilyNativeAdapter adapter, byte marker) {
		byte[] entropy = new byte[32];
		Arrays.fill(entropy, marker);
		String seed = object(adapter.getSeedPhraseFromEntropyB64(
				Base64.getEncoder().encodeToString(entropy)), "deterministic seed derivation")
				.getString("seedPhrase");
		assertFalse("Deterministic seed derivation returned an empty phrase", seed.isBlank());
		return seed;
	}

	private static JSONObject initWallet(ZcashFamilyNativeAdapter adapter, Path storage, String passphrase,
			String seed, String endpoint) {
		JSONObject configured = object(adapter.configureStorage(storage.toString(), passphrase),
				"storage configuration for " + storage.getFileName());
		assertTrue("Storage was not initialized: " + storage.getFileName(),
				configured.optBoolean("initialized"));
		JSONObject initialized = object(adapter.initFromSeed(endpoint, "", seed,
				Long.toString(PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT), "", ""),
				"wallet initialization for " + storage.getFileName());
		assertFalse("Wallet initialization returned no id",
				initialized.optString("wallet_id", "").isBlank());
		return initialized;
	}

	private static JSONObject firstExportEntry(ZcashFamilyNativeAdapter adapter) {
		JSONArray entries = array(adapter.execute("export", ""), "wallet key export");
		assertFalse("Wallet export contained no entries", entries.isEmpty());
		return entries.getJSONObject(0);
	}

	private static JSONObject buildImport(String walletId, String spendingKey, String expectedAddress,
			int addressIndex, long birthdayHeight) {
		return new JSONObject()
				.put("method", "import_spending_key_verified")
				.put("wallet_id", walletId)
				.put("pool", "sapling")
				.put("spending_key", spendingKey)
				.put("expected_address", expectedAddress)
				.put("address_index", addressIndex)
				.put("birthday_height", birthdayHeight);
	}

	private static JSONObject importResult(ZcashFamilyNativeAdapter adapter, String walletId,
			String spendingKey, String expectedAddress, int addressIndex, long birthdayHeight) {
		JSONObject response = object(adapter.invokeJson(buildImport(walletId, spendingKey,
				expectedAddress, addressIndex, birthdayHeight).toString(), false), "verified import");
		assertTrue("Verified import failed: " + response.optString("error", "(no error)"),
				response.optBoolean("ok"));
		return response.getJSONObject("result");
	}

	private static void assertImportError(ZcashFamilyNativeAdapter adapter, String walletId,
			String spendingKey, String expectedAddress, int addressIndex, long birthdayHeight,
			String expectedError) {
		JSONObject response = object(adapter.invokeJson(buildImport(walletId, spendingKey,
				expectedAddress, addressIndex, birthdayHeight).toString(), false),
				"counterexample import");
		assertFalse("Counterexample import unexpectedly succeeded (" + expectedError + ")",
				response.optBoolean("ok"));
		assertEquals("Counterexample import returned a different error",
				expectedError, response.optString("error", null));
	}

	private static String mixCase(String bech32) {
		// Uppercase the first lowercase letter only: valid Bech32 rejects mixed case.
		for (int i = 0; i < bech32.length(); i++) {
			if (Character.isLowerCase(bech32.charAt(i)))
				return bech32.substring(0, i) + Character.toUpperCase(bech32.charAt(i))
						+ bech32.substring(i + 1);
		}
		throw new AssertionError("Spending key contained no lowercase letter to flip");
	}

	private static void syncToTip(ZcashFamilyNativeAdapter adapter) throws Exception {
		JSONObject syncStarted = object(adapter.execute("sync", ""), "sync start");
		assertEquals("Sync command was not accepted", "success", syncStarted.getString("result"));
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
		long height = Long.MIN_VALUE;
		JSONObject status = null;
		do {
			status = object(adapter.execute("syncStatus", ""), "sync status");
			height = object(adapter.execute("height", ""), "wallet height").getLong("height");
			if (height >= PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT && !isSyncInProgress(status))
				return;
			Thread.sleep(100L);
		} while (System.nanoTime() < deadline);
		throw new AssertionError("Sync did not reach the deterministic tip; height " + height);
	}

	private static void cancelSync(ZcashFamilyNativeAdapter adapter, String walletId) {
		JSONObject cancelled = object(adapter.invokeJson(new JSONObject()
				.put("method", "cancel_sync").put("wallet_id", walletId)
				.toString(), false), "sync cancellation");
		assertTrue("Sync cancellation was not acknowledged", cancelled.optBoolean("ok")
				&& cancelled.getJSONObject("result").optBoolean("acknowledged"));
	}

	private static void awaitKnownChainTip(ZcashFamilyNativeAdapter adapter, String walletId)
			throws Exception {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
		JSONObject result = null;
		do {
			JSONObject response = object(adapter.invokeJson(new JSONObject()
					.put("method", "get_spendability_status").put("wallet_id", walletId)
					.toString(), false), "spendability tip probe");
			result = response.optBoolean("ok") ? response.optJSONObject("result") : null;
			if (result != null && result.optLong("target_height", 0L) > 0L)
				return;
			Thread.sleep(100L);
		} while (System.nanoTime() < deadline);
		throw new AssertionError("Persisted chain tip never became known: " + result);
	}

	private static void awaitSpendabilityTerminal(ZcashFamilyNativeAdapter adapter, String walletId)
			throws Exception {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
		JSONObject result = null;
		do {
			JSONObject response = object(adapter.invokeJson(new JSONObject()
					.put("method", "get_spendability_status").put("wallet_id", walletId)
					.toString(), false), "spendability status");
			result = response.optBoolean("ok") ? response.optJSONObject("result") : null;
			if (result != null && result.optBoolean("spendable")
					&& !result.optBoolean("rescan_required", true)
					&& !result.optBoolean("repair_queued", true))
				return;
			Thread.sleep(100L);
		} while (System.nanoTime() < deadline);
		throw new AssertionError("Spendability never reached its terminal-safe state: " + result);
	}

	/**
	 * Reads the total balance through the typed request Core uses for Unified wallets.
	 * The legacy `balance` command additionally builds a per-address breakdown whose
	 * address scan is pathological once a key import puts balance on an address the
	 * wallet's own key cannot derive.
	 */
	/** Asserts one counterexample rejects with its exact error and mutates no key group. */
	private static void assertRejectedWithoutMutation(ZcashFamilyNativeAdapter adapter, String walletId,
			String spendingKey, String expectedAddress, int addressIndex, long birthdayHeight,
			String expectedError) {
		java.util.List<Long> before = keyGroupIds(adapter, walletId);
		assertImportError(adapter, walletId, spendingKey, expectedAddress, addressIndex,
				birthdayHeight, expectedError);
		assertEquals("A rejected import changed the wallet's key groups (" + expectedError + ")",
				before, keyGroupIds(adapter, walletId));
	}

	/** Sorted key-group ids, used to prove a rejected import mutated nothing. */
	private static java.util.List<Long> keyGroupIds(ZcashFamilyNativeAdapter adapter, String walletId) {
		JSONObject response = object(adapter.invokeJson(new JSONObject()
				.put("method", "list_key_groups").put("wallet_id", walletId).toString(), false),
				"key group listing");
		assertTrue("Key group listing failed", response.optBoolean("ok"));
		JSONArray groups = response.getJSONArray("result");
		java.util.List<Long> ids = new java.util.ArrayList<>();
		for (int i = 0; i < groups.length(); i++)
			ids.add(groups.getJSONObject(i).optLong("id", -1L));
		java.util.Collections.sort(ids);
		return ids;
	}

	/** True when the wallet still lists the given key group id. */
	private static boolean listsKeyId(ZcashFamilyNativeAdapter adapter, String walletId, long keyId) {
		JSONObject response = object(adapter.invokeJson(new JSONObject()
				.put("method", "list_key_groups").put("wallet_id", walletId).toString(), false),
				"key group listing");
		assertTrue("Key group listing failed", response.optBoolean("ok"));
		JSONArray groups = response.getJSONArray("result");
		for (int i = 0; i < groups.length(); i++) {
			if (groups.getJSONObject(i).optLong("id", -1L) == keyId)
				return true;
		}
		return false;
	}

	private static long typedTotalBalance(ZcashFamilyNativeAdapter adapter, String walletId) {
		JSONObject response = object(adapter.invokeJson(new JSONObject()
				.put("method", "get_balance").put("wallet_id", walletId).toString(), false),
				"typed balance");
		assertTrue("Typed balance request failed", response.optBoolean("ok"));
		return Long.parseLong(response.getJSONObject("result").get("total").toString());
	}

	private static long typedSpendableBalance(ZcashFamilyNativeAdapter adapter, String walletId) {
		JSONObject response = object(adapter.invokeJson(new JSONObject()
				.put("method", "get_balance").put("wallet_id", walletId).toString(), false),
				"typed balance");
		assertTrue("Typed balance request failed", response.optBoolean("ok"));
		return Long.parseLong(response.getJSONObject("result").get("spendable").toString());
	}

	private static void assertRecoveredState(ZcashFamilyNativeAdapter adapter, String donorAddress,
			String walletId) {
		assertEquals("Recovered note missing from total balance",
				PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_VALUE,
				typedTotalBalance(adapter, walletId));
		assertEquals("Recovered note missing from verified balance",
				PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_VALUE,
				typedSpendableBalance(adapter, walletId));

		JSONArray transactions = array(adapter.execute("list", ""), "post-recovery transaction list");
		assertEquals("Recovered transaction was not present exactly once", 1, transactions.length());
		JSONObject transaction = transactions.getJSONObject(0);
		assertEquals("Recovered transaction height changed",
				PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_HEIGHT,
				transaction.getLong("block_height"));
		JSONArray incoming = transaction.getJSONArray("incoming_metadata");
		assertEquals("Recovered transaction lacked exactly one incoming note", 1, incoming.length());
		assertEquals("Recovered note did not belong to the imported address", donorAddress,
				incoming.getJSONObject(0).getString("address"));
	}

	private static boolean isSyncInProgress(JSONObject status) {
		if (status.has("in_progress"))
			return status.getBoolean("in_progress");
		if (status.has("syncing"))
			return status.getBoolean("syncing");
		throw new AssertionError("Sync status omitted its activity field");
	}

	private static JSONObject object(String response, String operation) {
		try {
			return new JSONObject(response);
		} catch (RuntimeException e) {
			throw new AssertionError(operation + " returned invalid JSON without exposing its response", e);
		}
	}

	private static JSONArray array(String response, String operation) {
		try {
			return new JSONArray(response);
		} catch (RuntimeException e) {
			throw new AssertionError(operation + " returned invalid JSON without exposing its response", e);
		}
	}
}
