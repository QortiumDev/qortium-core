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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Explicitly opt-in proof that a fresh same-entropy wallet recovers pre-existing history. */
public class PirateUnifiedHistoricalRestoreTests {

	private static final String RUN_PROPERTY = "qortium.runPirateUnifiedHistoricalRestoreTests";
	private static final String ARTIFACT_PATH_PROPERTY = "qortium.pirateUnifiedArtifactPath";
	private static final String BUNDLE_PATH_PROPERTY = "qortium.pirateUnifiedBundlePath";
	private static final String STORAGE_PATH_PROPERTY = "qortium.pirateUnifiedHistoricalRestoreStoragePath";
	private static final String EXPECTED_ADDRESS =
			"zs1ra3g8uphtg8ad7p8ye76pg06nr9rg5y8m5ycq40vpw4nvae6amehenaafv02g3dny9myxz7f60s";

	@Test
	public void testFreshSameEntropyWalletRecoversHistoricalTransactionAndBalance() throws Exception {
		assumeTrue("Set -D" + RUN_PROPERTY + "=true to execute historical-restore acceptance",
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
		assertFalse("Historical-restore storage path already exists", Files.exists(storageRoot));
		String libraryFilename = ZcashFamilyWalletController.resolveRustLibFilename();
		assumeTrue("The current host has no mapped Pirate Unified library", libraryFilename != null);
		PirateUnifiedWalletBundle.FileRecord trustedRecord =
				PirateUnifiedWalletBundle.validateArtifact(artifact, bundle, libraryFilename);
		Path library = bundle.resolve(libraryFilename);

		try (PirateUnifiedLoopbackLightwalletd lightwalletd = new PirateUnifiedLoopbackLightwalletd(true)) {
			ZcashFamilyNativeCoordinator.getInstance().execute("Pirate Unified historical restore acceptance",
					adapter -> {
						PirateUnifiedWalletBundle.validateSelectedLibrary(library, trustedRecord);
						adapter.loadLibrary(library);
						assertTrue("Pirate Unified JNI library did not load", adapter.isLoaded());
						adapter.initLogging();

						byte[] entropy = new byte[32];
						Arrays.fill(entropy, (byte) 7);
						String seed = object(adapter.getSeedPhraseFromEntropyB64(
								Base64.getEncoder().encodeToString(entropy)), "deterministic seed derivation")
								.getString("seedPhrase");
						assertFalse("Deterministic seed derivation returned an empty phrase", seed.isBlank());

						JSONObject configured = object(adapter.configureStorage(
								storageRoot.toString(), "qortium-historical-restore-test"),
								"fresh storage configuration");
						assertTrue("Fresh restore storage was not initialized", configured.optBoolean("initialized"));
						JSONObject initialized = object(adapter.initFromSeed(
								lightwalletd.endpoint(), "", seed,
								Long.toString(PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT), "", ""),
								"fresh same-entropy wallet initialization");
						assertEquals("Fresh restore changed the conservative birthday",
								PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT,
								initialized.getLong("birthday"));
						assertTrue("Fresh restore did not derive the expected account address",
								adapter.execute("export", "").contains(EXPECTED_ADDRESS));
						assertEquals("Fresh restore contained balance before scanning history", 0L,
								object(adapter.execute("balance", ""), "pre-sync balance").getLong("zbalance"));
						assertEquals("Fresh restore contained transactions before scanning history", 0,
								array(adapter.execute("list", ""), "pre-sync transaction list").length());

						String walletId = initialized.getString("wallet_id");
						JSONObject cancelRequest = new JSONObject().put("method", "cancel_sync")
								.put("wallet_id", walletId);
						try {
							JSONObject syncStarted = object(adapter.execute("sync", ""), "historical sync start");
							assertEquals("Historical sync command was not accepted",
									"success", syncStarted.getString("result"));
							awaitNativeSync(adapter);
							assertHistoricalState(adapter);
						} finally {
							JSONObject cancelled = object(adapter.invokeJson(cancelRequest.toString(), false),
									"historical sync cancellation");
							assertTrue("Historical sync cancellation failed", cancelled.optBoolean("ok"));
						}
						return null;
					});

			assertTrue("Fresh restore did not request its complete birthday-to-tip range; observed "
					+ lightwalletd.observedRanges(),
					lightwalletd.completeRangeCount(PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE) > 0);
			assertEquals("Historical restore attempted a forbidden transaction RPC", 0,
					lightwalletd.forbiddenRpcCount());
			assertEquals("Historical restore changed its optional subtree capability probe count", 1,
					lightwalletd.subtreeProbeCount());
			assertEquals("Historical restore attempted an RPC outside the deterministic fixture", 0,
					lightwalletd.unexpectedRpcCount());
		}
	}

	private static void assertHistoricalState(ZcashFamilyNativeAdapter adapter) {
		JSONObject balance = object(adapter.execute("balance", ""), "post-sync historical balance");
		assertEquals("Historical note was not recovered into total balance",
				PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_VALUE, balance.getLong("zbalance"));
		assertEquals("Historical note was not recovered into verified balance",
				PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_VALUE,
				balance.getLong("verified_zbalance"));

		JSONArray transactions = array(adapter.execute("list", ""), "post-sync historical transaction list");
		assertEquals("Historical transaction was not recovered exactly once", 1, transactions.length());
		JSONObject transaction = transactions.getJSONObject(0);
		assertEquals("Historical transaction height changed",
				PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_HEIGHT,
				transaction.getLong("block_height"));
		assertEquals("Historical transaction amount changed",
				PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_VALUE,
				transaction.getLong("amount"));
		assertFalse("Historical transaction remained unconfirmed", transaction.optBoolean("unconfirmed"));
		JSONArray incoming = transaction.getJSONArray("incoming_metadata");
		assertEquals("Historical transaction did not contain exactly one incoming note", 1, incoming.length());
		assertEquals("Historical note address changed", EXPECTED_ADDRESS,
				incoming.getJSONObject(0).getString("address"));
		assertEquals("Historical note value changed", PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_VALUE,
				incoming.getJSONObject(0).getLong("value"));
	}

	private static void awaitNativeSync(ZcashFamilyNativeAdapter adapter) throws Exception {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
		JSONObject status = null;
		long height = Long.MIN_VALUE;
		do {
			status = object(adapter.execute("syncStatus", ""), "historical sync status");
			height = object(adapter.execute("height", ""), "historical wallet height").getLong("height");
			if (height >= PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT && !isSyncInProgress(status))
				break;
			Thread.sleep(100L);
		} while (System.nanoTime() < deadline);

		assertEquals("Historical sync did not finish at the exact deterministic tip",
				PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT, height);
		assertFalse("Historical sync remained active after reaching the deterministic tip",
				status != null && isSyncInProgress(status));
	}

	private static boolean isSyncInProgress(JSONObject status) {
		if (status.has("in_progress"))
			return status.getBoolean("in_progress");
		if (status.has("syncing"))
			return status.getBoolean("syncing");
		throw new AssertionError("Historical sync status omitted its activity field");
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
