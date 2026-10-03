package org.qortium.crosschain.monero;

import com.fasterxml.jackson.databind.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Opt-in, synthetic, loopback OFFLINE regtest only. Never uses configured Core wallets or daemons. */
public class MoneroJniAcceptanceTests {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private String daemon;
    private JsonNode rpc(String method, Map<String, Object> params) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(daemon + "/json_rpc")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("jsonrpc", "2.0", "id", "xmr-core-test", "method", method, "params", params)))).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        assertFalse(body.has("error")); return body.get("result");
    }
    @Before public void offlineOnly() throws Exception {
        daemon = System.getProperty("qortium.xmr.regtestDaemon");
        Assume.assumeNotNull(daemon);
        URI uri = URI.create(daemon);
        assertEquals("127.0.0.1", uri.getHost()); assertEquals("http", uri.getScheme());
        assertTrue(rpc("get_info", Map.of()).get("offline").asBoolean());
        // The orchestrator additionally pins the daemon executable and supplies --regtest --offline.
    }
    @Test public void nativeFixtureParityEncryptedReopenAndSeparateWalletBalances() throws Exception {
        Path root = temporary.getRoot().toPath();
        var fixtures = MoneroKeysTests.fixtures();
        try (var keys = new MoneroKeys(HexFormat.of().parseHex(fixtures.get(0).get("coinSeed").asText()))) {
            assertThrows(MoneroWalletBackend.AdmissionRejected.class, () -> MoneroJniWallet.open(root, daemon, keys, 500_000_000L, true));
            try (var files = Files.list(root.resolve("xmr-regtest-v1").resolve(keys.walletId))) {
                assertEquals(0, files.count());
            }
        }
        for (JsonNode fixture : fixtures) {
            try (var keys = new MoneroKeys(HexFormat.of().parseHex(fixture.get("coinSeed").asText()));
                 var wallet = MoneroJniWallet.open(root, daemon, keys, 0, true)) {
                assertEquals(fixture.get("address").asText(), wallet.read().address());
            }
        }
        JsonNode first = fixtures.get(3), second = fixtures.get(4);
        String address = first.get("address").asText();
        rpc("generateblocks", Map.of("wallet_address", address, "amount_of_blocks", 80));
        String balance; long height;
        try (var keys = new MoneroKeys(HexFormat.of().parseHex(first.get("coinSeed").asText()));
             var wallet = MoneroJniWallet.open(root, daemon, keys, 0, true)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            MoneroWalletBackend.Snapshot snapshot;
            do {
                snapshot = wallet.read();
                if (snapshot.synced() && new java.math.BigInteger(snapshot.balanceAtomic()).signum() > 0) break;
                if (System.nanoTime() > deadline) fail("Native scan did not reach fixture funds");
                Thread.sleep(100);
            } while (true);
            assertFalse(snapshot.transactions().isEmpty());
            assertTrue(snapshot.transactions().get(0).confirmed());
            balance = snapshot.balanceAtomic(); height = snapshot.height();
            Path dir = root.resolve("xmr-regtest-v1").resolve(keys.walletId);
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
            for (String name : List.of("wallet", "wallet.keys")) {
                byte[] data = Files.readAllBytes(dir.resolve(name));
                String printable = new String(data, java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(printable.contains(keys.spendHex())); assertFalse(printable.contains(keys.password()));
            }
        }
        try (var keys = new MoneroKeys(HexFormat.of().parseHex(first.get("coinSeed").asText()));
             var wallet = MoneroJniWallet.open(root, daemon, keys, 0, true)) {
            var reopened = wallet.read();
            assertEquals(balance, reopened.balanceAtomic()); assertTrue(reopened.height() >= height);
            assertEquals(address, reopened.address());
        }
        try (var keys = new MoneroKeys(HexFormat.of().parseHex(second.get("coinSeed").asText()));
             var wallet = MoneroJniWallet.open(root, daemon, keys, 0, true)) {
            assertEquals("0", wallet.read().balanceAtomic());
            assertNotEquals(address, wallet.read().address());
        }
        try (var keys = new MoneroKeys(HexFormat.of().parseHex(first.get("coinSeed").asText()))) {
            assertThrows(IllegalStateException.class, () -> MoneroJniWallet.open(root, daemon, keys, 1, true));
            Path dir = root.resolve("xmr-regtest-v1").resolve(keys.walletId);
            String identity = Files.readString(dir.resolve("identity"));
            Files.writeString(dir.resolve("identity"), identity.replace("derivation=1", "derivation=2"));
            assertThrows(IllegalStateException.class, () -> MoneroJniWallet.open(root, daemon, keys, 0, true));
            Files.writeString(dir.resolve("identity"), identity);
            Files.createSymbolicLink(dir.resolve("unexpected"), root);
            assertThrows(IllegalStateException.class, () -> MoneroJniWallet.open(root, daemon, keys, 0, true));
            Files.delete(dir.resolve("unexpected"));
            assertThrows(Exception.class, () -> monero.wallet.MoneroWalletFull.openWallet(dir.resolve("wallet").toString(),
                    "deliberately-wrong-public-test-password", monero.daemon.model.MoneroNetworkType.MAINNET,
                    (monero.common.MoneroRpcConnection)null, true));
            Files.delete(dir.resolve("wallet"));
            assertThrows(IllegalStateException.class, () -> MoneroJniWallet.open(root, daemon, keys, 0, true));
        }
    }
}
