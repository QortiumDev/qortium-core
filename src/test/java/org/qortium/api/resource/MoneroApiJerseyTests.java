package org.qortium.api.resource;

import com.fasterxml.jackson.databind.*;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.eclipse.jetty.ee8.servlet.*;
import org.eclipse.jetty.server.*;
import org.glassfish.jersey.servlet.ServletContainer;
import org.junit.*;
import org.qortium.api.ApiServiceTestConfig;
import org.qortium.crosschain.monero.*;
import org.qortium.settings.Settings;
import org.qortium.test.common.*;
import java.util.List;
import static org.junit.Assert.*;

/** Real Jersey provider stack: JSON records, exact atomic strings, auth before body parsing. */
public class MoneroApiJerseyTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Before public void setup() throws Exception { Common.useDefaultSettings(); ApiCommon.installTestApiKey(); }
    @After public void cleanup() throws Exception {
        var service = (MoneroWalletService) FieldUtils.readStaticField(MoneroWalletRuntime.class, "instance", true);
        if (service != null) service.close();
        FieldUtils.writeStaticField(MoneroWalletRuntime.class, "instance", null, true);
        FieldUtils.writeField(Settings.getInstance(), "moneroWalletEnabled", false, true);
        ApiCommon.clearTestApiKey();
    }
    @Test public void realApiKeepsContractShapeAndRejectsMalformedSeedBodies() throws Exception {
        var service = new MoneroWalletService((keys, height) -> new MoneroWalletBackend() {
            public Snapshot read() { return new Snapshot("synthetic-test-address", 100, 100, true,
                    "9007199254740993", "1", List.of()); }
            public org.qortium.crosschain.WalletServerPool.Status servers() {
                return new org.qortium.crosschain.WalletServerPool(java.util.List.of("https://configured.test")).status();
            }
            public void close() { }
        });
        FieldUtils.writeField(Settings.getInstance(), "moneroWalletEnabled", true, true);
        FieldUtils.writeStaticField(MoneroWalletRuntime.class, "instance", service, true);
        try (var server = new TestServer()) {
            String invalid = server.call("POST", "/activate", "{\"secret\":\"MUST_NOT_ECHO\"}", "", true);
            assertTrue(invalid, invalid.startsWith("HTTP/1.1 400"));
            assertFalse(invalid, invalid.contains("MUST_NOT_ECHO"));
            assertTrue(invalid, invalid.contains("XMR_INVALID_ACTIVATION"));
            String denied = server.call("POST", "/activate", "not json", "", false);
            assertTrue(denied, denied.startsWith("HTTP/1.1 403"));
            String body = "{\"coinSeed\":\"" + "01".repeat(32) + "\",\"restoreHeight\":0,\"derivationVersion\":1}";
            JsonNode activated = payload(server.call("POST", "/activate", body, "", true));
            String session = activated.get("sessionId").asText();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!"READY".equals(service.status(session).state())) {
                if (System.nanoTime() > deadline) fail("wallet did not become ready");
                Thread.sleep(5);
            }
            String response = server.call("GET", "/wallet", "", "X-XMR-SESSION: " + session + "\r\n", true);
            JsonNode wallet = payload(response);
            assertFalse(wallet.get("send").asBoolean());
            assertTrue(wallet.get("wallet").get("balanceAtomic").isTextual());
            assertEquals("9007199254740993", wallet.get("wallet").get("balanceAtomic").asText());
            assertTrue(wallet.get("wallet").get("transactions").isArray());
            assertTrue(response.toLowerCase().contains("cache-control: no-store"));
            String providerResponse = server.call("GET", "/servers", "", "X-XMR-SESSION: " + session + "\r\n", true);
            assertEquals("server-1", payload(providerResponse).get("selectedId").asText());
            assertTrue(providerResponse.toLowerCase().contains("cache-control: no-store"));
            String wrongOwner = server.call("GET", "/servers", "", "X-XMR-SESSION: stale\r\n", true);
            assertTrue(wrongOwner.startsWith("HTTP/1.1 409"));assertFalse(wrongOwner.contains("configured.test"));
            assertTrue(server.call("GET", "/servers", "", "", false).startsWith("HTTP/1.1 403"));
            String stale = server.call("GET", "/wallet", "", "X-XMR-SESSION: stale\r\n", true);
            assertTrue(stale, stale.startsWith("HTTP/1.1 409"));
            assertFalse(stale.contains("synthetic-test-address"));
        }
    }
    private static JsonNode payload(String response) throws Exception {
        assertTrue(response, response.startsWith("HTTP/1.1 200"));
        return JSON.readTree(response.substring(response.indexOf("\r\n\r\n") + 4));
    }
    public static class TestServer implements AutoCloseable {
        private final Server server = new Server();
        private final ServerConnector connector = new ServerConnector(server);
        public TestServer() throws Exception {
            var config = ApiServiceTestConfig.create();
            var context = new ServletContextHandler(); context.setContextPath("/");
            context.addServlet(new ServletHolder(new ServletContainer(config)), "/*");
            connector.setHost("127.0.0.1"); connector.setPort(0);
            server.addConnector(connector); server.setHandler(context); server.start();
        }
        public String call(String method, String suffix, String body, String extra, boolean auth) throws Exception {
            String request = method + " /crosschain/xmr" + suffix + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: application/json\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n"
                    + (auth ? "X-API-KEY: " + ApiCommon.TEST_API_KEY + "\r\n" : "") + extra + "\r\n" + body;
            try (var socket = new java.net.Socket("127.0.0.1", connector.getLocalPort())) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        public void close() throws Exception { server.stop(); }
    }
}
