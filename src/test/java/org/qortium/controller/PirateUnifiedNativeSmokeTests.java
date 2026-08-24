package org.qortium.controller;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
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
	private static final String LOOPBACK_SERVER = "https://127.0.0.1:1/";
	private static final String FIRST_ADDRESS =
			"zs1ra3g8uphtg8ad7p8ye76pg06nr9rg5y8m5ycq40vpw4nvae6amehenaafv02g3dny9myxz7f60s";

	@Test
	public void testHostLibrarySupportsOfflineWalletLifecycle() throws Exception {
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

		ZcashFamilyNativeCoordinator.getInstance().execute("Pirate Unified offline JNI acceptance", adapter -> {
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
					LOOPBACK_SERVER, "", firstSeed, "100000", "", ""), "first wallet initialization");
			assertTrue("First wallet initialization omitted its seed marker", firstInitialized.has("seed"));
			assertEquals("First wallet birthday changed", 100000L, firstInitialized.getLong("birthday"));
			String firstWalletId = firstInitialized.getString("wallet_id");
			assertFalse("First wallet identifier was empty", firstWalletId.isBlank());

			assertEquals("First wallet height did not retain its birthday", 100000L,
					object(adapter.execute("height", ""), "first wallet height").getLong("height"));
			String firstExport = adapter.execute("export", "");
			JSONArray firstExportEntries = array(firstExport, "first wallet export");
			assertFalse("First wallet export was empty", firstExportEntries.isEmpty());
			JSONObject firstExportEntry = firstExportEntries.getJSONObject(0);
			assertEquals("First wallet address vector changed", FIRST_ADDRESS,
					firstExportEntry.getString("address"));
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
			assertEquals("Idle sync height did not retain the birthday", 100000L,
					syncStatus.getLong("scanned_height"));
			assertEquals("Fresh wallet transaction list was not empty", 0,
					array(adapter.execute("list", ""), "fresh transaction list").length());

			JSONObject firstReopened = object(adapter.initFromSeed(
					LOOPBACK_SERVER, "", firstSeed, "100000", "", ""), "first wallet reopen");
			assertTrue("Existing wallet reopen omitted its seed marker", firstReopened.has("seed"));
			String wallets = adapter.invokeJson("{\"method\":\"list_wallets\"}", false);
			assertEquals("Seed restore created a duplicate native wallet", 1,
					occurrences(wallets, "\"name\":\"Qortal "));
			JSONObject migratedReopen = object(adapter.initFromB64(
					LOOPBACK_SERVER, "", "ignored-after-migration", "", ""), "migrated database reopen");
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
					LOOPBACK_SERVER, "", secondSeed, "110000", "", ""), "second wallet initialization");
			assertEquals("Second wallet birthday changed", 110000L, secondInitialized.getLong("birthday"));
			assertFalse("Wallet state leaked into the second namespace",
					adapter.execute("export", "").contains(FIRST_ADDRESS));

			JSONObject firstReconfigured = object(adapter.configureStorage(
					storageRoot.resolve("wallet-a").toString(), "qortium-offline-wallet-a"),
					"first storage reconfiguration");
			assertTrue("First native storage did not reopen", firstReconfigured.optBoolean("initialized"));
			JSONObject originalReopened = object(adapter.initFromSeed(
					LOOPBACK_SERVER, "", firstSeed, "100000", "", ""), "original wallet reopen");
			String reopenedWalletId = originalReopened.getString("wallet_id");
			assertTrue("Original namespace did not reopen after wallet switch",
					adapter.execute("export", "").contains(FIRST_ADDRESS));

			JSONObject buildInfo = object(adapter.invokeJson("{\"method\":\"get_build_info\"}", false),
					"build information");
			assertTrue("Typed native service invocation failed", buildInfo.optBoolean("ok"));
			JSONObject cancelRequest = new JSONObject().put("method", "cancel_sync")
					.put("wallet_id", reopenedWalletId.isBlank() ? firstWalletId : reopenedWalletId);
			try {
				JSONObject syncStarted = object(adapter.execute("sync", ""), "sync start");
				assertEquals("Offline sync command was not accepted", "success", syncStarted.getString("result"));
			} finally {
				JSONObject cancelResponse = object(adapter.invokeJson(cancelRequest.toString(), false),
						"sync cancellation request");
				assertTrue("Offline sync cancellation request failed", cancelResponse.optBoolean("ok"));
			}
			return null;
		});
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
