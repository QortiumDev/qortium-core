package org.qortium.controller;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.qortium.crosschain.ZcashFamilyNativeAdapter;
import org.qortium.crosschain.ZcashFamilyNativeCoordinator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Three separate opt-in JVMs prove old-create, new-upgrade and new-reopen persistence. */
public class PirateUnifiedUpgradeAcceptanceTests {
    private static final String OLD_SHA256 = "871eafe7d4b18e383f3810a31a95f058e5d10b37cbb8ac101ad01206ac235bef";
    private static final String EXPECTED_ADDRESS = "zs1ra3g8uphtg8ad7p8ye76pg06nr9rg5y8m5ycq40vpw4nvae6amehenaafv02g3dny9myxz7f60s";

    @Test
    public void testPersistedWalletAcrossReleaseUpgrade() throws Exception {
        assumeTrue("Native upgrade acceptance is opt-in", Boolean.getBoolean("qortium.runPirateUnifiedUpgradeAcceptanceTests"));
        String phase = System.getProperty("qortium.pirateUpgradePhase", "");
        assertTrue("Unknown upgrade phase", Arrays.asList("create", "upgrade", "reopen").contains(phase));
        Path artifact = requiredPath("qortium.pirateUnifiedArtifactPath");
        Path bundle = requiredPath("qortium.pirateUnifiedBundlePath");
        Path root = requiredPath("qortium.pirateUpgradeStoragePath");
        Path evidence = root.resolve("identity.json");
        String filename = ZcashFamilyWalletController.resolveRustLibFilename();
        assertNotNull("Unsupported native host", filename);
        Path library = bundle.resolve(filename);
        if (phase.equals("create")) {
            assertFalse("Creation must use absent disposable storage", Files.exists(root));
            assertEquals("Old official artifact size changed", 363257276L, Files.size(artifact));
            assertEquals("Old official artifact hash changed", OLD_SHA256, digest(artifact));
            // Match the selected binary to the pinned old archive without changing production pins.
            try (ZipFile zip = new ZipFile(artifact.toFile())) {
                var entries = zip.stream().filter(e -> !e.isDirectory() &&
                        (e.getName().equals(filename) || e.getName().endsWith("/" + filename))).toList();
                assertEquals("Old archive must contain one host library", 1, entries.size());
                try (var input = zip.getInputStream(entries.get(0))) {
                    assertEquals("Old staged library differs from pinned archive", digest(library), digest(input));
                }
            }
            Files.createDirectory(root);
        } else {
            assertTrue("Prior phase evidence missing", Files.isRegularFile(evidence));
            var record = PirateUnifiedWalletBundle.validateArtifact(artifact, bundle, filename);
            PirateUnifiedWalletBundle.validateSelectedLibrary(library, record);
        }
        try (PirateUnifiedLoopbackLightwalletd server = new PirateUnifiedLoopbackLightwalletd(true)) {
            ZcashFamilyNativeCoordinator.getInstance().execute("Pirate release upgrade acceptance",
                    ZcashFamilyNativeCoordinator.SYNC_TIMEOUT, adapter -> {
                adapter.loadLibrary(library);
                assertTrue("Native library not loaded", adapter.isLoaded());
                adapter.initLogging();
                assertTrue("Storage failed to open", new JSONObject(adapter.configureStorage(
                        root.resolve("wallet").toString(), "qortium-disposable-upgrade-test")).optBoolean("initialized"));
                String walletId;
                if (phase.equals("create")) {
                    byte[] entropy = new byte[32];
                    Arrays.fill(entropy, (byte) 7);
                    String seed = new JSONObject(adapter.getSeedPhraseFromEntropyB64(
                            Base64.getEncoder().encodeToString(entropy))).getString("seedPhrase");
                    walletId = new JSONObject(adapter.initFromSeed(server.endpoint(), "", seed,
                            Long.toString(PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT), "", "")).getString("wallet_id");
                    assertEquals("Sync rejected", "success", new JSONObject(adapter.execute("sync", "")).getString("result"));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                    boolean completed = false;
                    do {
                        JSONObject state = new JSONObject(adapter.execute("syncStatus", ""));
                        long height = new JSONObject(adapter.execute("height", "")).getLong("height");
                        if (height >= PirateUnifiedLoopbackLightwalletd.TIP_HEIGHT &&
                                !state.optBoolean("in_progress", false) && !state.optBoolean("syncing", false)) {
                            completed = true;
                            break;
                        }
                        Thread.sleep(100L);
                    } while (System.nanoTime() < deadline);
                    assertTrue("Synthetic history sync did not finish", completed);
                    JSONObject cancelled = new JSONObject(adapter.invokeJson(new JSONObject().put("method", "cancel_sync")
                            .put("wallet_id", walletId).toString(), false));
                    assertTrue("Sync cancellation failed", cancelled.optBoolean("ok"));
                } else {
                    JSONObject opened = new JSONObject(adapter.initFromB64(server.endpoint(), "", "ignored-after-migration", "", ""));
                    assertTrue("Existing database failed to open", opened.optBoolean("initialized"));
                    walletId = new JSONObject(Files.readString(evidence)).getString("walletId");
                }
                JSONArray wallets = result(adapter, "list_wallets", null).getJSONArray("result");
                assertEquals("Wallet registry changed size", 1, wallets.length());
                assertTrue("Wallet identity changed", walletId.equals(wallets.getJSONObject(0).getString("id")));
                assertEquals("Birthday changed", PirateUnifiedLoopbackLightwalletd.SAPLING_ACTIVATION_HEIGHT,
                        wallets.getJSONObject(0).getLong("birthday_height"));
                JSONArray exported = new JSONArray(adapter.execute("export", ""));
                assertEquals("Primary key export changed size", 1, exported.length());
                assertTrue("Address changed", EXPECTED_ADDRESS.equals(exported.getJSONObject(0).getString("address")));
                String keyHash = digest(new java.io.ByteArrayInputStream(exported.getJSONObject(0).getString("private_key")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                JSONObject balance = result(adapter, "get_balance", walletId).getJSONObject("result");
                assertEquals("Persisted synthetic balance changed", PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_VALUE,
                        Long.parseLong(balance.get("total").toString()));
                JSONArray txs = new JSONArray(adapter.execute("list", ""));
                assertEquals("Persisted transaction missing or duplicated", 1, txs.length());
                assertEquals("Transaction height changed", PirateUnifiedLoopbackLightwalletd.HISTORICAL_NOTE_HEIGHT,
                        txs.getJSONObject(0).getLong("block_height"));
                JSONArray groups = result(adapter, "list_key_groups", walletId).getJSONArray("result");
                assertEquals("Key group count changed", 1, groups.length());
                JSONObject identity = new JSONObject().put("walletId", walletId).put("keyHash", keyHash)
                        .put("keyId", groups.getJSONObject(0).getLong("id"));
                if (phase.equals("create")) Files.writeString(evidence, identity.toString());
                else assertTrue("Encrypted key identity changed", identity.similar(new JSONObject(Files.readString(evidence))));
                assertTrue("Save marker missing", !adapter.save().isBlank());
                return null;
            });
            assertEquals("Forbidden transaction RPC", 0, server.forbiddenRpcCount());
            assertEquals("Unexpected fixture RPC", 0, server.unexpectedRpcCount());
            if (!phase.equals("create")) assertEquals("Reopen unexpectedly rescanned history", 0,
                    server.rpcCount(PirateUnifiedLoopbackLightwalletd.PIRATE_SERVICE, "GetBlockRange"));
        }
        System.out.println("PIRATE_UPGRADE_PHASE_PASS phase=" + phase);
    }

    private static JSONObject result(ZcashFamilyNativeAdapter adapter, String method, String walletId) {
        JSONObject request = new JSONObject().put("method", method);
        if (walletId != null) request.put("wallet_id", walletId);
        JSONObject response = new JSONObject(adapter.invokeJson(request.toString(), false));
        assertTrue("Native request failed: " + method, response.optBoolean("ok"));
        return response;
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property, "");
        assertTrue("Required absolute path: " + property, !value.isBlank() && Path.of(value).isAbsolute());
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static String digest(Path file) throws Exception {
        try (var input = Files.newInputStream(file)) { return digest(input); }
    }

    private static String digest(java.io.InputStream input) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        for (int n; (n = input.read(buffer)) != -1;) hash.update(buffer, 0, n);
        return HexFormat.of().formatHex(hash.digest());
    }
}
