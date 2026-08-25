package org.qortium.controller;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import org.qortium.crosschain.ZcashFamilyNativeAdapter;
import org.qortium.crosschain.ZcashFamilyNativeCoordinator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Explicitly opt-in host JNI smoke. This test is never part of normal CI. */
public class PirateUnifiedNativeSmokeTests {

	private static final String RUN_PROPERTY = "qortium.runPirateUnifiedNativeSmokeTests";
	private static final String ARTIFACT_PATH_PROPERTY = "qortium.pirateUnifiedArtifactPath";
	private static final String BUNDLE_PATH_PROPERTY = "qortium.pirateUnifiedBundlePath";
	private static final String STORAGE_PATH_PROPERTY = "qortium.pirateUnifiedNativeStoragePath";
	private static final String FIRST_ADDRESS =
			"zs1ra3g8uphtg8ad7p8ye76pg06nr9rg5y8m5ycq40vpw4nvae6amehenaafv02g3dny9myxz7f60s";

	@Test
	public void testHostLibrarySupportsWalletLifecycleAgainstLoopbackLightwalletd() throws Exception {
		assumeTrue("Set -D" + RUN_PROPERTY + "=true to execute the staged host native library",
				Boolean.getBoolean(RUN_PROPERTY));
		String artifactPath = System.getProperty(ARTIFACT_PATH_PROPERTY);
		assumeTrue("Set -D" + ARTIFACT_PATH_PROPERTY + "=/absolute/path/to/the/pinned/archive",
				artifactPath != null && !artifactPath.isBlank());
		String bundlePath = System.getProperty(BUNDLE_PATH_PROPERTY);
		assumeTrue("Set -D" + BUNDLE_PATH_PROPERTY + "=/absolute/path/to/staged/bundle",
				bundlePath != null && !bundlePath.isBlank());
		String storagePath = System.getProperty(STORAGE_PATH_PROPERTY);
		assumeTrue("Set -D" + STORAGE_PATH_PROPERTY + "=/absolute/new/path/to/temporary/storage",
				storagePath != null && !storagePath.isBlank() && Path.of(storagePath).isAbsolute());
		String libraryFilename = ZcashFamilyWalletController.resolveRustLibFilename();
		assumeTrue("The current host has no mapped Pirate Unified library", libraryFilename != null);

		Path artifact = Path.of(artifactPath).toAbsolutePath().normalize();
		Path bundle = Path.of(bundlePath).toAbsolutePath().normalize();
		Path storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
		assertFalse("Temporary native storage path already exists", Files.exists(storageRoot));
		Files.createDirectory(storageRoot);
		PirateUnifiedWalletBundle.FileRecord trustedRecord =
				PirateUnifiedWalletBundle.validateArtifact(artifact, bundle, libraryFilename);
		Path library = bundle.resolve(libraryFilename);

		try (PirateUnifiedLoopbackLightwalletd serverA = new PirateUnifiedLoopbackLightwalletd();
				PirateUnifiedLoopbackLightwalletd serverB = new PirateUnifiedLoopbackLightwalletd(0, "main", "main",
						PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT + 4L);
				PirateUnifiedLoopbackLightwalletd nativeBad =
						new PirateUnifiedLoopbackLightwalletd(0, "main", "test")) {
			ZcashFamilyNativeCoordinator.getInstance().execute("Pirate Unified loopback JNI acceptance", adapter -> {
				PirateUnifiedWalletBundle.validateSelectedLibrary(library, trustedRecord);
				adapter.loadLibrary(library);
				if (!adapter.isLoaded())
					throw new AssertionError("Pirate Unified JNI library did not load");
				adapter.initLogging();

				byte[] firstEntropy = new byte[32];
				Arrays.fill(firstEntropy, (byte) 7);
				String firstSeed = seedPhrase(adapter.getSeedPhraseFromEntropyB64(
						Base64.getEncoder().encodeToString(firstEntropy)));
				JSONObject firstConfigured = object(adapter.configureStorage(
						storageRoot.resolve("wallet-a").toString(), "qortium-offline-wallet-a"),
						"first storage configuration");
				assertTrue("First native storage was not initialized", firstConfigured.optBoolean("initialized"));

				JSONObject firstInitialized = object(adapter.initFromSeed(
						serverA.endpoint(), "", firstSeed,
						Long.toString(PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT), "", ""),
						"first wallet initialization");
				assertTrue("First wallet initialization omitted its seed marker", firstInitialized.has("seed"));
				assertEquals("First wallet birthday changed",
						PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT,
						firstInitialized.getLong("birthday"));
				String firstWalletId = firstInitialized.getString("wallet_id");
				assertFalse("First wallet identifier was empty", firstWalletId.isBlank());

				assertEquals("First wallet height did not retain its birthday",
						PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT,
						object(adapter.execute("height", ""), "first wallet height").getLong("height"));
				String firstExport = adapter.execute("export", "");
				JSONArray firstExportEntries = array(firstExport, "first wallet export");
				assertFalse("First wallet export was empty", firstExportEntries.isEmpty());
				JSONObject firstExportEntry = firstExportEntries.getJSONObject(0);
				assertTrue("First wallet address vector changed",
						FIRST_ADDRESS.equals(firstExportEntry.getString("address")));
				assertFalse("First wallet export omitted private-key material",
						firstExportEntry.getString("private_key").isBlank());
				JSONObject firstBalance = object(adapter.execute("balance", ""), "first wallet balance");
				assertEquals("Fresh wallet total balance was not zero", 0L, firstBalance.getLong("zbalance"));
				assertEquals("Fresh wallet verified balance was not zero", 0L,
						firstBalance.getLong("verified_zbalance"));
				JSONObject encryption = object(adapter.execute("encryptionstatus", ""), "encryption status");
				assertTrue("Native storage did not report encryption", encryption.optBoolean("encrypted"));
				assertFalse("Configured native storage remained locked", encryption.optBoolean("locked", true));
				JSONObject syncStatus = object(adapter.execute("syncStatus", ""), "idle sync status");
				assertFalse("Fresh wallet unexpectedly reported active sync", syncStatus.optBoolean("in_progress", true));
				assertEquals("Idle sync height did not retain the birthday",
						PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT,
						syncStatus.getLong("scanned_height"));
				assertEquals("Fresh wallet transaction list was not empty", 0,
						array(adapter.execute("list", ""), "fresh transaction list").length());

				JSONObject firstReopened = object(adapter.initFromSeed(
						serverA.endpoint(), "", firstSeed,
						Long.toString(PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT), "", ""),
						"first wallet reopen");
				assertTrue("Existing wallet reopen omitted its seed marker", firstReopened.has("seed"));
				String wallets = adapter.invokeJson("{\"method\":\"list_wallets\"}", false);
				assertEquals("Seed restore created a duplicate native wallet", 1,
						occurrences(wallets, "\"name\":\"Qortal "));
				JSONObject migratedReopen = object(adapter.initFromB64(
						serverA.endpoint(), "", "ignored-after-migration", "", ""), "migrated database reopen");
				assertTrue("Migrated database reopen did not initialize", migratedReopen.optBoolean("initalized"));
				assertEquals("Migrated database reopen returned an error", "none", migratedReopen.getString("error"));
				String saveMarker = new String(Base64.getDecoder().decode(adapter.save()), StandardCharsets.UTF_8);
				assertTrue("Native save marker did not identify Unified storage",
						saveMarker.contains("pirate-unified-wallet-sqlite"));

				byte[] secondEntropy = new byte[32];
				Arrays.fill(secondEntropy, (byte) 8);
				String secondSeed = seedPhrase(adapter.getSeedPhraseFromEntropyB64(
						Base64.getEncoder().encodeToString(secondEntropy)));
				JSONObject secondConfigured = object(adapter.configureStorage(
						storageRoot.resolve("wallet-b").toString(), "qortium-offline-wallet-b"),
						"second storage configuration");
				assertTrue("Second native storage was not initialized", secondConfigured.optBoolean("initialized"));
				JSONObject secondInitialized = object(adapter.initFromSeed(
						serverA.endpoint(), "", secondSeed,
						Long.toString(PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT), "", ""),
						"second wallet initialization");
				assertEquals("Second wallet birthday changed",
						PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT,
						secondInitialized.getLong("birthday"));
				String secondWalletId = secondInitialized.getString("wallet_id");
				assertFalse("Second wallet identifier was empty", secondWalletId.isBlank());
				assertFalse("Second wallet reused the first wallet identifier",
						firstWalletId.equals(secondWalletId));
				JSONArray secondExportEntries = array(adapter.execute("export", ""), "second wallet export");
				assertFalse("Second wallet export was empty", secondExportEntries.isEmpty());
				JSONObject secondExportEntry = secondExportEntries.getJSONObject(0);
				assertFalse("Second wallet export omitted its address",
						secondExportEntry.getString("address").isBlank());
				assertFalse("Second wallet export omitted private-key material",
						secondExportEntry.getString("private_key").isBlank());
				assertFalse("Wallet state leaked into the second namespace",
						FIRST_ADDRESS.equals(secondExportEntry.getString("address")));

				JSONObject firstReconfigured = object(adapter.configureStorage(
						storageRoot.resolve("wallet-a").toString(), "qortium-offline-wallet-a"),
						"first storage reconfiguration");
				assertTrue("First native storage did not reopen", firstReconfigured.optBoolean("initialized"));
				JSONObject originalReopened = object(adapter.initFromSeed(
						serverA.endpoint(), "", firstSeed,
						Long.toString(PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT), "", ""),
						"original wallet reopen");
				String reopenedWalletId = originalReopened.getString("wallet_id");
				assertTrue("Original namespace did not reopen after wallet switch",
						adapter.execute("export", "").contains(FIRST_ADDRESS));

					JSONObject buildInfo = object(adapter.invokeJson("{\"method\":\"get_build_info\"}", false),
							"build information");
					assertTrue("Typed native service invocation failed", buildInfo.optBoolean("ok"));
					JSONObject cancelRequest = new JSONObject().put("method", "cancel_sync")
							.put("wallet_id", reopenedWalletId.isBlank() ? firstWalletId : reopenedWalletId);
					int serverABarrier = -1;
					try {
						JSONObject syncStarted = object(adapter.execute("sync", ""), "sync start");
						assertEquals("Loopback sync command was not accepted", "success", syncStarted.getString("result"));
						awaitNativeSync(adapter);

						JSONObject rejectedNode = envelopeResult(adapter, new JSONObject().put("method", "test_node")
								.put("url", nativeBad.endpoint()).put("tls_pin", JSONObject.NULL),
								"native-bad node test");
						assertTrue("Native-bad fixture was not reachable", rejectedNode.optBoolean("success"));
						assertEquals("Native-bad fixture did not expose its wrong chain", "test",
								rejectedNode.optString("chain_name"));
						assertEquals("Native-bad fixture received synchronization traffic", 0,
								nativeBad.rpcCount(PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE, "GetBlock")
										+ nativeBad.rpcCount(PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE, "GetBlockRange"));

						JSONObject nodeTest = envelopeResult(adapter, new JSONObject().put("method", "test_node")
								.put("url", serverB.endpoint()).put("tls_pin", JSONObject.NULL), "server B node test");
						assertTrue("Server B native node test failed", nodeTest.optBoolean("success"));
						assertEquals("Server B native chain changed", "main", nodeTest.optString("chain_name"));
						assertEquals("Server B native height changed", serverB.tipHeight(),
								nodeTest.getLong("latest_block_height"));

						JSONObject cancelResponse = object(adapter.invokeJson(cancelRequest.toString(), false),
								"pre-cutover sync cancellation request");
						assertAcknowledged(cancelResponse, "Pre-cutover cancellation");
						serverABarrier = nativeRpcCount(serverA);

						JSONObject setRequest = new JSONObject().put("method", "set_lightd_endpoint")
								.put("wallet_id", reopenedWalletId).put("url", serverB.endpoint())
								.put("tls_pin_opt", JSONObject.NULL);
						assertAcknowledged(object(adapter.invokeJson(setRequest.toString(), false),
								"server B endpoint mutation"), "Server B endpoint mutation");
						JSONObject getRequest = new JSONObject().put("method", "get_lightd_endpoint")
								.put("wallet_id", reopenedWalletId);
						String selectedEndpoint = envelopeResultValue(adapter, getRequest, "server B endpoint readback");
						assertEquals("Server B endpoint readback changed", withoutTrailingSlash(serverB.endpoint()),
								withoutTrailingSlash(selectedEndpoint));
						JSONObject consensus = envelopeResult(adapter,
								new JSONObject().put("method", "validate_consensus_branch")
										.put("wallet_id", reopenedWalletId), "server B consensus validation");
						assertTrue("Server B consensus branch was rejected", consensus.optBoolean("is_valid"));

						int serverBTipRangesBeforeSync = serverB.pirateTipRangeCount();
						JSONObject bSyncStarted = object(adapter.execute("sync", ""), "server B sync start");
						assertEquals("Server B sync command was not accepted", "success",
								bSyncStarted.getString("result"));
						awaitNativeSync(adapter, serverB.tipHeight());
						assertTrue("Server B sync never requested a range ending at its newer tip",
								serverB.pirateTipRangeCount() > serverBTipRangesBeforeSync);
						assertTrue("Native client never reached server B", nativeRpcCount(serverB) > 0);

						JSONObject persistedReopen = object(adapter.configureStorage(
								storageRoot.resolve("wallet-a").toString(), "qortium-offline-wallet-a"),
								"cutover storage reopen");
						assertTrue("Cutover storage did not reopen", persistedReopen.optBoolean("initialized"));
						assertEquals("Persisted endpoint did not remain on server B",
								withoutTrailingSlash(serverB.endpoint()), withoutTrailingSlash(
										envelopeResultValue(adapter, getRequest, "persisted server B endpoint")));
						JSONObject cutoverReopened = object(adapter.initFromSeed(serverB.endpoint(), "", firstSeed,
								Long.toString(PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT), "", ""),
								"cutover wallet reopen");
						assertEquals("Cutover reopen changed wallet identity", reopenedWalletId,
								cutoverReopened.getString("wallet_id"));
						assertTrue("Cutover reopen changed wallet address",
								adapter.execute("export", "").contains(FIRST_ADDRESS));
					} finally {
						JSONObject cancelResponse = object(adapter.invokeJson(cancelRequest.toString(), false),
								"sync cancellation request");
						assertAcknowledged(cancelResponse, "Loopback sync cancellation");
					}
					assertTrue("Native cutover barrier was not established", serverABarrier >= 0);
					assertEquals("Native traffic returned to server A after endpoint commit", serverABarrier,
							nativeRpcCount(serverA));
					return null;
			});

			assertTrue("Native sync never requested the complete deterministic compact-block range; observed "
					+ serverA.observedRanges(),
					serverA.completeRangeCount(PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE) > 0);
			assertTrue("Native client never requested the Pirate lightwalletd tip",
					serverA.rpcCount(PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE,
							"GetLatestBlock") > 0);
			assertEquals("Native acceptance attempted a forbidden transaction RPC", 0,
					serverA.forbiddenRpcCount() + serverB.forbiddenRpcCount() + nativeBad.forbiddenRpcCount());
			assertTrue("Native acceptance omitted its optional subtree capability probe",
					serverA.subtreeProbeCount() + serverB.subtreeProbeCount() >= 1);
			assertEquals("Native acceptance attempted an RPC outside the deterministic fixture", 0,
					serverA.unexpectedRpcCount() + serverB.unexpectedRpcCount() + nativeBad.unexpectedRpcCount());
		}
	}

	private static void awaitNativeSync(ZcashFamilyNativeAdapter adapter) throws Exception {
		awaitNativeSync(adapter, PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT);
	}

	private static void awaitNativeSync(ZcashFamilyNativeAdapter adapter, long expectedTip) throws Exception {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
		JSONObject status = null;
		long height = Long.MIN_VALUE;
		do {
			status = object(adapter.execute("syncStatus", ""), "active sync status");
			height = object(adapter.execute("height", ""), "active wallet height").getLong("height");
			if (height >= expectedTip && !isSyncInProgress(status))
				break;
			Thread.sleep(100L);
		} while (System.nanoTime() < deadline);

		assertTrue("Native sync did not reach the deterministic tip",
			height >= expectedTip);
		assertFalse("Native sync remained active after reaching the deterministic tip",
				status != null && isSyncInProgress(status));
	}

	private static boolean isSyncInProgress(JSONObject status) {
		if (status.has("in_progress"))
			return requiredBoolean(status, "in_progress");
		if (status.has("syncing"))
			return requiredBoolean(status, "syncing");
		throw new AssertionError("Native sync status omitted both recognized activity fields");
	}

	private static boolean requiredBoolean(JSONObject status, String key) {
		Object value = status.opt(key);
		if (value instanceof Boolean booleanValue)
			return booleanValue;
		if (value instanceof String stringValue
				&& ("true".equalsIgnoreCase(stringValue) || "false".equalsIgnoreCase(stringValue)))
			return Boolean.parseBoolean(stringValue);
		throw new AssertionError("Native sync status returned a non-boolean activity field");
	}

	private static JSONObject envelopeResult(ZcashFamilyNativeAdapter adapter, JSONObject request, String operation) {
		JSONObject envelope = object(adapter.invokeJson(request.toString(), false), operation);
		assertTrue(operation + " failed", envelope.optBoolean("ok"));
		JSONObject result = envelope.optJSONObject("result");
		assertTrue(operation + " returned no object result", result != null);
		return result;
	}

	private static String envelopeResultValue(ZcashFamilyNativeAdapter adapter, JSONObject request,
			String operation) {
		JSONObject envelope = object(adapter.invokeJson(request.toString(), false), operation);
		assertTrue(operation + " failed", envelope.optBoolean("ok"));
		String result = envelope.optString("result", null);
		assertTrue(operation + " returned no string result", result != null && !result.isBlank());
		return result;
	}

	private static void assertAcknowledged(JSONObject envelope, String operation) {
		assertTrue(operation + " failed", envelope.optBoolean("ok"));
		JSONObject result = envelope.optJSONObject("result");
		assertTrue(operation + " was not acknowledged",
				result != null && result.optBoolean("acknowledged", false));
	}

	private static String withoutTrailingSlash(String endpoint) {
		return endpoint != null && endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
	}

	private static int nativeRpcCount(PirateUnifiedLoopbackLightwalletd lightwalletd) {
		int count = 0;
		for (String method : new String[] {"GetLatestBlock", "GetLightdInfo", "GetBlock", "GetBlockRange",
				"GetTreeState", "GetSubtreeRoots"})
			count += lightwalletd.rpcCount(PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE, method);
		return count;
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

	private static String seedPhrase(String response) {
		String seedPhrase = object(response, "deterministic seed derivation").getString("seedPhrase");
		assertFalse("Deterministic seed derivation returned an empty phrase", seedPhrase.isBlank());
		return seedPhrase;
	}

	private static int occurrences(String value, String marker) {
		int count = 0;
		int offset = 0;
		while ((offset = value.indexOf(marker, offset)) >= 0) {
			count++;
			offset += marker.length();
		}
		return count;
	}
}
