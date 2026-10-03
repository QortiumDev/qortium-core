package org.qortium.crosschain.monero;

import com.fasterxml.jackson.databind.*;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

public class MoneroKeysTests {
    static JsonNode fixtures() throws Exception {
        try (var input = MoneroKeysTests.class.getResourceAsStream("/monero/derivation-v1.json")) {
            return new ObjectMapper().readTree(input).get("fixtures");
        }
    }
    static byte[] coinSeed(JsonNode fixture, String ticker) throws Exception {
        byte[] seed = HexFormat.of().parseHex(fixture.get("masterSeed").asText());
        byte[] account;
        if (fixture.get("walletVersion").asInt() == 1) account = seed;
        else {
            byte[] nonce = ByteBuffer.allocate(4).putInt((int) fixture.get("nonce").asLong()).array();
            byte[] material = concat(nonce, seed, nonce);
            account = Arrays.copyOf(hash("SHA-512", concat(hash("SHA-512", material), material)), 32);
        }
        byte[] reversed = account.clone(); MoneroKeys.reverse(reversed);
        return Arrays.copyOf(hash("SHA-512", concat(reversed, hash("SHA-256", concat(reversed, ticker.getBytes(StandardCharsets.UTF_8))))), 32);
    }
    static byte[] hash(String algorithm, byte[] data) throws Exception { return MessageDigest.getInstance(algorithm).digest(data); }
    static byte[] concat(byte[]... arrays) {
        var result = new java.io.ByteArrayOutputStream();
        for (byte[] array : arrays) result.writeBytes(array);
        return result.toByteArray();
    }
    @Test public void canonicalHomeAccountSemanticsAndMoneroKeys() throws Exception {
        for (JsonNode fixture : fixtures()) {
            byte[] seed = coinSeed(fixture, "XMR");
            assertEquals(fixture.get("coinSeed").asText(), MoneroKeys.hex(seed));
            assertFalse(Arrays.equals(seed, coinSeed(fixture, "ARRR")));
            assertFalse(Arrays.equals(seed, coinSeed(fixture, "")));
            try (var keys = new MoneroKeys(seed)) {
                assertEquals(fixture.get("spend").asText(), keys.spendHex());
                assertEquals(fixture.get("view").asText(), keys.viewHex());
                assertNotEquals(keys.walletId, keys.password());
                assertNotEquals(keys.spendHex(), keys.password());
            }
        }
    }
    @Test public void standardOrderBoundaryAndZeroRejection() {
        byte[] order = HexFormat.of().parseHex("edd3f55c1a631258d69cf7a2def9de1400000000000000000000000000000010");
        assertArrayEquals(new byte[32], MoneroKeys.reduce(order));
        assertThrows(IllegalArgumentException.class, () -> new MoneroKeys(order));
        assertThrows(IllegalArgumentException.class, () -> new MoneroKeys(new byte[31]));
        order[0]++;
        byte[] one = new byte[32]; one[0] = 1;
        assertArrayEquals(one, MoneroKeys.reduce(order));
    }
    @Test public void daemonPolicyNeverAcceptsRemotePlaintextOrCredentials() {
        for (String uri : List.of("http://127.0.0.1:18081", "https://example.org:18081", "http://[::1]:18081"))
            MoneroJniWallet.validateDaemon(uri);
        for (String uri : List.of("http://example.org", "http://localhost", "https://user:secret@example.org", "file:///tmp/a", "https://example.org/path", "https://example.org?key=secret"))
            assertThrows(IllegalArgumentException.class, () -> MoneroJniWallet.validateDaemon(uri));
    }
}
