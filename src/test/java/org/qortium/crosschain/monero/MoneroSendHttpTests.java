package org.qortium.crosschain.monero;

import com.fasterxml.jackson.databind.*;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.*;
import org.qortium.api.resource.MoneroApiJerseyTests.TestServer;
import org.qortium.settings.Settings;
import org.qortium.test.common.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.qortium.crosschain.monero.MoneroWalletServiceTests.await;

/** Actual Jersey/auth/strict-reader/service/journal path with a fault-injectable native boundary. */
public class MoneroSendHttpTests {
    private final MoneroSendServiceTests owner = new MoneroSendServiceTests();
    private MoneroSendServiceTests.Fixture fixture;
    private TestServer server;
    private String session;
    private static final ObjectMapper JSON = new ObjectMapper();
    @Before public void setup() throws Exception {
        Common.useDefaultSettings(); ApiCommon.installTestApiKey(); owner.temp.create();
        fixture = owner.new Fixture(10000);
        FieldUtils.writeField(Settings.getInstance(), "moneroWalletEnabled", true, true);
        FieldUtils.writeField(Settings.getInstance(), "moneroWalletSendEnabled", true, true);
        FieldUtils.writeStaticField(MoneroWalletRuntime.class, "instance", fixture.service, true);
        session = fixture.activate(1, null); server = new TestServer();
    }
    @After public void cleanup() throws Exception {
        if (fixture != null && fixture.nativeWallet != null) {
            if (fixture.nativeWallet.observeRelease != null) fixture.nativeWallet.observeRelease.countDown();
            if (fixture.nativeWallet.prepareRelease != null) fixture.nativeWallet.prepareRelease.countDown();
            if (fixture.nativeWallet.relayRelease != null) fixture.nativeWallet.relayRelease.countDown();
        }
        if (server != null) server.close();
        if (fixture != null) fixture.close();
        FieldUtils.writeStaticField(MoneroWalletRuntime.class, "instance", null, true);
        ApiCommon.clearTestApiKey(); owner.temp.delete();
    }
    private String call(String route, String body) throws Exception {
        return server.call(route.startsWith("/send/status/") ? "GET" : "POST", route, body,
                "X-XMR-SESSION: " + session + "\r\n", true);
    }
    private JsonNode payload(String response, int status) throws Exception {
        assertTrue(response, response.startsWith("HTTP/1.1 " + status));
        assertTrue(response.toLowerCase().contains("cache-control: no-store"));
        return JSON.readTree(response.substring(response.indexOf("\r\n\r\n") + 4));
    }
    private String operation(String id) { return "{\"operationId\":\"" + id + "\"}"; }
    private String prepare(String id) {
        return operation(id).replace("}", ",\"address\":\"" + MoneroSendServiceTests.ADDRESS + "\",\"amountAtomic\":\"1000\"}");
    }
    @Test public void exactQuoteUnknownAndDuplicateCommitNeverRelayTwice() throws Exception {
        String id = UUID.randomUUID().toString();
        JsonNode quote = payload(call("/send/prepare", prepare(id)), 200);
        assertEquals("PREPARED", quote.get("state").asText());
        assertEquals("1000", quote.get("amountAtomic").textValue());
        assertEquals("100", quote.get("feeAtomic").textValue());
        assertTrue(quote.get("expiresAt").asLong() > System.currentTimeMillis());
        Set<String> names = new HashSet<>(); quote.fieldNames().forEachRemaining(names::add);
        assertEquals(Set.of("operationId", "state", "quoteDigest", "address", "amountAtomic", "feeAtomic", "txid", "walletHeld", "expiresAt", "confirmations", "unlocked"), names);
        payload(call("/send/prepare", prepare(id)), 200); assertEquals(1, fixture.nativeWallet.preparations.get());
        String commit = operation(id).replace("}", ",\"quoteDigest\":\"" + quote.get("quoteDigest").asText() + "\"}");
        fixture.nativeWallet.lostResponse = true;
        assertEquals("UNKNOWN", payload(call("/send/commit", commit), 200).get("state").asText());
        assertEquals("UNKNOWN", payload(call("/send/commit", commit), 200).get("state").asText());
        assertEquals(1, fixture.nativeWallet.relays.get());
        assertEquals("XMR_SEND_NOT_READY", payload(call("/send/cancel", operation(id)), 409).get("code").asText());
        fixture.nativeWallet.observations = Map.of(id, new MoneroSendMachine.Observation(MoneroSendServiceTests.HASH, true, 10, true, false));
        JsonNode confirmed = payload(call("/send/reconcile", operation(id)), 200);
        assertEquals("CONFIRMED", confirmed.get("state").asText()); assertEquals(10, confirmed.get("confirmations").asInt());
        assertFalse(confirmed.get("walletHeld").asBoolean());
        String oldSession = session; session = fixture.activate(2, session);
        payload(call("/send/status/" + id, ""), 409);
        String stale = server.call("GET", "/send/status/" + id, "", "X-XMR-SESSION: " + oldSession + "\r\n", true);
        assertEquals("XMR_SESSION_CHANGED", payload(stale, 409).get("code").asText());
        assertFalse(stale.contains(MoneroSendServiceTests.HASH));
    }
    @Test public void timedOutPreparationRemainsCancellableAndIsNotResubmitted() throws Exception {
        fixture.nativeWallet.prepareEntered = new CountDownLatch(1); fixture.nativeWallet.prepareRelease = new CountDownLatch(1);
        String id = UUID.randomUUID().toString();
        JsonNode pending = payload(call("/send/prepare", prepare(id)), 202);
        assertEquals("XMR_SEND_PENDING", pending.get("code").asText()); assertTrue(pending.get("statusRequired").asBoolean());
        assertEquals("PREPARING", payload(call("/send/cancel", operation(id)), 200).get("state").asText());
        fixture.nativeWallet.prepareRelease.countDown();
        await(() -> "CANCELLED".equals(fixture.service.sendStatus(id, session).state().name()));
        assertEquals("CANCELLED", payload(call("/send/status/" + id, ""), 200).get("state").asText());
        assertEquals(1, fixture.nativeWallet.preparations.get()); assertEquals(0, fixture.nativeWallet.relays.get());
    }
    @Test public void queuedPreparationDoesNotClaimDurableAcceptance() throws Exception {
        fixture.nativeWallet.observeEntered = new CountDownLatch(1); fixture.nativeWallet.observeRelease = new CountDownLatch(1);
        String id = UUID.randomUUID().toString();
        JsonNode pending = payload(call("/send/prepare", prepare(id)), 503);
        assertEquals("XMR_SEND_ADMISSION_PENDING", pending.get("code").asText());
        assertFalse(pending.get("durable").asBoolean()); assertTrue(pending.get("statusRequired").asBoolean());
        payload(call("/send/status/" + id, ""), 409);
        assertEquals("CANCELLED", payload(call("/send/cancel", operation(id)), 200).get("state").asText());
        assertEquals(0, fixture.nativeWallet.preparations.get());
        fixture.nativeWallet.observeRelease.countDown();
        await(() -> {
            try { return fixture.service.sendStatus(id, session).state() == MoneroSendContracts.State.CANCELLED; }
            catch (MoneroWalletService.Rejected e) { return false; }
        });
        assertEquals("CANCELLED", payload(call("/send/status/" + id, ""), 200).get("state").asText());
        assertEquals(0, fixture.nativeWallet.preparations.get());
    }
    @Test public void parserAndAuthFailuresAreRedactedAndHaveNoEffects() throws Exception {
        String secret = "DO_NOT_ECHO_NATIVE_METADATA";
        for (String route : List.of("/send/prepare", "/send/commit", "/send/cancel", "/send/reconcile")) {
            String denied = server.call("POST", route, secret, "", false);
            assertTrue(denied, denied.startsWith("HTTP/1.1 403")); assertFalse(denied.contains(secret));
            String invalid = call(route, "{\"metadata\":\"" + secret + "\"}");
            assertEquals("XMR_INVALID_SEND_REQUEST", payload(invalid, 400).get("code").asText()); assertFalse(invalid.contains(secret));
        }
        assertEquals(0, fixture.nativeWallet.preparations.get()); assertEquals(0, fixture.nativeWallet.relays.get());
        assertEquals("XMR_INVALID_SEND_REQUEST", payload(call("/send/status/not-a-uuid", ""), 400).get("code").asText());
    }
}
