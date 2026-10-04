package org.qortium.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.eclipse.jetty.ee8.servlet.*;
import org.eclipse.jetty.server.*;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.junit.*;
import org.qortium.api.ApiServiceTestConfig;
import org.qortium.crosschain.monero.*;
import org.qortium.settings.Settings;
import org.qortium.test.common.*;
import javax.ws.rs.Path;
import java.util.*;
import static org.junit.Assert.*;

/** HTTP routing/provider checks; native calls use offline synthetic backends only. */
public class CrossChainWalletApiJerseyTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Before public void setup() throws Exception { Common.useDefaultSettings(); ApiCommon.installTestApiKey(); }
    @After public void cleanup() throws Exception {
        var service = (MoneroWalletService) FieldUtils.readStaticField(MoneroWalletRuntime.class, "instance", true);
        if (service != null) service.close();
        FieldUtils.writeStaticField(MoneroWalletRuntime.class, "instance", null, true);
        FieldUtils.writeField(Settings.getInstance(), "moneroWalletEnabled", false, true);
        ApiCommon.clearTestApiKey();
    }
    @Test public void genericRoutingKeepsNativeMoneroOwnershipExactAmountsAndNoBodyStop() throws Exception {
        var service = new MoneroWalletService((keys, height) -> new MoneroWalletBackend() {
            public Snapshot read() { return new Snapshot("synthetic-address", 100, 100, true,
                    "9007199254740993", "1", List.of()); }
            public void close() { }
        });
        FieldUtils.writeField(Settings.getInstance(), "moneroWalletEnabled", true, true);
        FieldUtils.writeStaticField(MoneroWalletRuntime.class, "instance", service, true);
        try (var server = new TestServer(false)) {
            String denied = server.call("POST", "/XMR/activate", "not-json", "application/json", "", false);
            assertTrue(denied, denied.startsWith("HTTP/1.1 403"));
            String malformed = server.call("POST", "/XMR/activate", "{\"secret\":\"MUST_NOT_ECHO\"}", "application/json", "", true);
            assertTrue(malformed, malformed.startsWith("HTTP/1.1 400")); assertFalse(malformed.contains("MUST_NOT_ECHO"));
            String body = "{\"coinSeed\":\"" + "01".repeat(32) + "\",\"restoreHeight\":0,\"derivationVersion\":1}";
            var activated = payload(server.call("POST", "/XMR/activate", body, "application/json", "", true));
            String session = activated.get("sessionId").asText();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!"READY".equals(service.status(session).state())) {
                if (System.nanoTime() > deadline) fail("wallet did not become ready"); Thread.sleep(5);
            }
            String response = server.call("GET", "/XMR/status", "", null, "X-WALLET-SESSION: " + session + "\r\n", true);
            var wallet = payload(response);
            assertTrue(wallet.get("wallet").get("balanceAtomic").isTextual());
            assertEquals("9007199254740993", wallet.get("wallet").get("balanceAtomic").asText());
            assertTrue(response.toLowerCase().contains("cache-control: no-store"));
            String stale = server.call("GET", "/XMR/status", "", null, "X-WALLET-SESSION: stale\r\n", true);
            assertTrue(stale, stale.startsWith("HTTP/1.1 409")); assertFalse(stale.contains("synthetic-address"));
            String stopped = server.call("POST", "/XMR/stop", "", null, "X-WALLET-SESSION: " + session + "\r\n", true);
            assertTrue(stopped, stopped.startsWith("HTTP/1.1 200"));
            String unknown = server.call("POST", "/XMR/start", "", null, "", true);
            assertTrue(unknown, unknown.startsWith("HTTP/1.1 404"));
            var protocol = payload(server.call("GET", "/ARRR/protocol", "", null, "Accept: application/json\r\n", true));
            assertEquals(CrossChainWalletResource.CONTRACT, protocol.get("contract").asText());
            String badSend = server.call("POST", "/ARRR/send", "{\"arrrAmount\":[1,2]}", "application/json", "", true);
            assertTrue(badSend, badSend.startsWith("HTTP/1.1 400"));
        }
    }
    @Test public void textAcceptAndAtomicResponseRemainExactThroughInheritedGenericHttpRoutes() throws Exception {
        // Stub only the native ARRR adapter, never HTTP routing or response production.
        try (var server = new TestServer(true)) {
            for (String operation : List.of("address", "balance")) {
                String response = server.call("POST", "/ARRR/" + operation, "synthetic-entropy", "text/plain", "Accept: text/plain\r\n", true);
                assertTrue(response, response.startsWith("HTTP/1.1 200"));
                assertTrue(response, response.toLowerCase().contains("content-type: text/plain"));
                assertTrue(response.toLowerCase().contains("cache-control: no-store"));
                assertEquals(operation.equals("address") ? "zs1synthetic-address" : "9007199254740993", responseBody(response));
            }
        }
    }
    @Path("/test/crosschain/wallets/{coin}")
    public static class SyntheticPirateWalletResource extends CrossChainWalletResource {
        @Override Adapter pirate() {
            return new Adapter() {
                public Set<String> reads() { return Set.of(); }
                public Set<String> writes() { return Set.of("address", "balance"); }
                public Object read(String op, String key, String session, String id) { throw new AssertionError(); }
                public Object write(String op, String key, String session, String id, java.io.InputStream body) {
                    return op.equals("address") ? "zs1synthetic-address" : "9007199254740993";
                }
            };
        }
    }
    private static String responseBody(String value) { return value.substring(value.indexOf("\r\n\r\n") + 4); }
    private static com.fasterxml.jackson.databind.JsonNode payload(String response) throws Exception {
        assertTrue(response, response.startsWith("HTTP/1.1 200")); return JSON.readTree(responseBody(response));
    }
    private static class TestServer implements AutoCloseable {
        private final Server server = new Server();
        private final ServerConnector connector = new ServerConnector(server);
        private final String prefix;
        TestServer(boolean syntheticPirate) throws Exception {
            prefix = syntheticPirate ? "/test/crosschain/wallets" : "/crosschain/wallets";
            ResourceConfig config = ApiServiceTestConfig.create();
            if (syntheticPirate) {
                var classes = new HashSet<>(config.getClasses()); classes.remove(CrossChainWalletResource.class);
                config = new ResourceConfig(classes); config.register(SyntheticPirateWalletResource.class);
            }
            var context = new ServletContextHandler(); context.setContextPath("/");
            context.addServlet(new ServletHolder(new ServletContainer(config)), "/*");
            connector.setHost("127.0.0.1"); connector.setPort(0); server.addConnector(connector);
            server.setHandler(context); server.start();
        }
        String call(String method, String suffix, String body, String type, String extra, boolean auth) throws Exception {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String header = method + " " + prefix + suffix + " HTTP/1.1\r\nHost: localhost\r\n"
                    + (type == null ? "" : "Content-Type: " + type + "\r\n")
                    + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n"
                    + (auth ? "X-API-KEY: " + ApiCommon.TEST_API_KEY + "\r\n" : "") + extra + "\r\n";
            try (var socket = new java.net.Socket("127.0.0.1", connector.getLocalPort())) {
                socket.setSoTimeout(5000); socket.getOutputStream().write(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                socket.getOutputStream().write(bytes); return new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        public void close() throws Exception { server.stop(); }
    }
}
