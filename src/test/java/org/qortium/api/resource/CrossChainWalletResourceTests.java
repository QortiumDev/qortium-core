package org.qortium.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.*;
import org.qortium.api.ApiException;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;
import javax.ws.rs.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class CrossChainWalletResourceTests {
    @Before public void setup() throws Exception { Common.useDefaultSettings(); ApiCommon.installTestApiKey(); }
    @After public void cleanup() { ApiCommon.clearTestApiKey(); }
    private CrossChainWalletResource resource(String host, String key) {
        var resource = (CrossChainWalletResource) ApiCommon.buildResource(CrossChainWalletResource.class, ApiCommon.buildRequest(host, key));
        // ApiCommon's servlet mock normally returns no content type.
        contentType(resource, "application/json");
        return resource;
    }
    private void contentType(CrossChainWalletResource resource, String value) {
        var original = resource.request;
        resource.request = (javax.servlet.http.HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{javax.servlet.http.HttpServletRequest.class},
                (proxy, method, args) -> method.getName().equals("getContentType") ? value : method.invoke(original, args));
    }
    private InputStream body(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
    private InputStream noRead() { return new InputStream() { public int read() { throw new AssertionError("Body consumed before admission"); } }; }

    @Test public void everyCoinKeepsAuthenticationAndLoopbackBeforeBodyConsumption() {
        for (String coin : java.util.List.of("ARRR", "XMR", "UNKNOWN")) {
            assertThrows(ApiException.class, () -> resource("127.0.0.1", null).write(coin, "activate", null, null, noRead()));
            assertThrows(ApiException.class, () -> resource("192.0.2.1", ApiCommon.TEST_API_KEY).write(coin, "activate", ApiCommon.TEST_API_KEY, null, noRead()));
        }
    }
    @Test public void catalogOnlyAdvertisesRegisteredOperationsAndRejectsUnknownsBeforeReading() throws Exception {
        var resource = resource("127.0.0.1", ApiCommon.TEST_API_KEY);
        var json = new ObjectMapper();
        for (String coin : java.util.List.of("ARRR", "XMR")) {
            var response = resource.read(coin, "protocol", ApiCommon.TEST_API_KEY, null);
            var protocol = json.readTree((String) response.getEntity());
            assertEquals(CrossChainWalletResource.CONTRACT, protocol.get("contract").asText());
            assertEquals(coin, protocol.get("coin").asText());
            assertEquals("X-WALLET-SESSION", protocol.get("sessionHeader").asText());
            assertTrue(protocol.get("writes").toString().contains("stop"));
            assertEquals("no-store", response.getHeaderString("Cache-Control"));
            assertThrows(NotFoundException.class, () -> resource.write(coin, "walletseedphrase", ApiCommon.TEST_API_KEY, null, noRead()));
        }
        assertThrows(NotFoundException.class, () -> resource.write("unknown", "activate", ApiCommon.TEST_API_KEY, null, noRead()));
        assertThrows(NotFoundException.class, () -> resource.write("XMR", "start", ApiCommon.TEST_API_KEY, null, noRead()));
    }
    @Test public void disabledMoneroStillRejectsActivationAndSendBeforeBodyConsumption() throws Exception {
        var resource = resource("127.0.0.1", ApiCommon.TEST_API_KEY);
        assertEquals(503, resource.write("XMR", "activate", ApiCommon.TEST_API_KEY, null, noRead()).getStatus());
        for (String operation : java.util.List.of("send/prepare", "send/commit", "send/cancel", "send/reconcile"))
            assertEquals(503, assertThrows(ServiceUnavailableException.class,
                    () -> resource.write("XMR", operation, ApiCommon.TEST_API_KEY, null, noRead())).getResponse().getStatus());
    }
    @Test public void pirateSessionRejectsUnknownDuplicateCoercedAndTrailingFieldsWithoutEcho() {
        var resource = resource("127.0.0.1", ApiCommon.TEST_API_KEY);
        for (String value : java.util.List.of("{\"secret\":\"MUST_NOT_ECHO\"}",
                "{\"entropy58\":\"a\",\"entropy58\":\"MUST_NOT_ECHO\"}", "{\"operation\":[\"activate\"]}",
                "{\"operation\":\"activate\"} {}", " ".repeat(16385))) {
            var failure = assertThrows(BadRequestException.class, () -> resource.write("ARRR", "session", ApiCommon.TEST_API_KEY, null, body(value)));
            assertFalse(failure.getMessage().contains("MUST_NOT_ECHO"));
        }
        assertThrows(BadRequestException.class, () -> resource.write("ARRR", "activate", ApiCommon.TEST_API_KEY, null, body("{\"operation\":\"status\"}")));
    }
    @Test public void pirateSendKeepsTheExistingStrictMoneyReader() {
        var resource = resource("127.0.0.1", ApiCommon.TEST_API_KEY);
        for (String value : java.util.List.of("{\"arrrAmount\":[1,2]}", "{\"arrrAmount\":\"1\",\"arrrAmount\":\"2\"}", "{\"privateKey\":\"MUST_NOT_ECHO\"}")) {
            var failure = assertThrows(ApiException.class, () -> resource.write("ARRR", "send", ApiCommon.TEST_API_KEY, null, body(value)));
            assertFalse(failure.getMessage().contains("MUST_NOT_ECHO"));
        }
    }
    @Test public void contentTypeDoesNotSelectAnUnexpectedBodyParser() {
        var resource = resource("127.0.0.1", ApiCommon.TEST_API_KEY);
        contentType(resource, "text/plain");
        assertThrows(NotSupportedException.class, () -> resource.write("ARRR", "send", ApiCommon.TEST_API_KEY, null, noRead()));
        assertThrows(NotSupportedException.class, () -> resource.write("XMR", "activate", ApiCommon.TEST_API_KEY, null, noRead()));
    }
}
