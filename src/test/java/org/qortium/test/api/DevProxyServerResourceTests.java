package org.qortium.test.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.proxy.resource.DevProxyServerResource;
import org.qortium.controller.DevProxyManager;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DevProxyServerResourceTests {

    private static final int PROXY_CONNECT_TIMEOUT_MS = 5000;
    private static final int PROXY_READ_TIMEOUT_MS = 30000;

    private HttpServer server;

    @Before
    public void beforeTest() throws DataException {
        Common.useDefaultSettings();
        DevProxyManager.getInstance().stop();
        DevProxyManager.getInstance().setSourceHostAndPort("127.0.0.1:5173");
    }

    @After
    public void afterTest() throws DataException {
        if (this.server != null) {
            this.server.stop(0);
            this.server = null;
        }

        DevProxyManager.getInstance().stop();
        DevProxyManager.getInstance().setSourceHostAndPort("127.0.0.1:5173");
    }

    @Test
    public void testProxyReturnsUpstreamErrorResponseBody() throws Exception {
        byte[] body = "not found from upstream".getBytes(StandardCharsets.UTF_8);

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/missing.txt", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND, body.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        this.server.start();

        DevProxyManager.getInstance().setSourceHostAndPort("127.0.0.1:" + this.server.getAddress().getPort());

        Exchange exchange = new Exchange();
        DevProxyServerResource resource = new DevProxyServerResource();
        setField(resource, "request", exchange.request);
        setField(resource, "response", exchange.response);

        HttpServletResponse result = resource.getProxyPath("missing.txt");

        assertSame(exchange.response, result);
        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, exchange.status);
        assertEquals("text/plain", exchange.contentType);
        assertEquals(body.length, exchange.contentLength);
        assertArrayEquals(body, exchange.outputStream.toByteArray());
        assertEquals("default-src 'self'", exchange.getResponseHeader("Content-Security-Policy"));
    }

    @Test
    public void testProxyForwardsSafeUpstreamResponseHeaders() throws Exception {
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/asset.txt", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.getResponseHeaders().add("X-Dev-Proxy-Test", "forwarded");
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, body.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        this.server.start();

        DevProxyManager.getInstance().setSourceHostAndPort("127.0.0.1:" + this.server.getAddress().getPort());

        Exchange exchange = new Exchange();
        DevProxyServerResource resource = new DevProxyServerResource();
        setField(resource, "request", exchange.request);
        setField(resource, "response", exchange.response);

        resource.getProxyPath("asset.txt");

        assertEquals(HttpURLConnection.HTTP_OK, exchange.status);
        assertEquals("forwarded", exchange.getResponseHeader("X-Dev-Proxy-Test"));
        assertEquals("default-src 'self'", exchange.getResponseHeader("Content-Security-Policy"));
        assertArrayEquals(body, exchange.outputStream.toByteArray());
    }

    @Test
    public void testProxyStreamsNonHtmlResponsesWithoutContentLength() throws Exception {
        byte[] body = "chunked asset body".getBytes(StandardCharsets.UTF_8);

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/chunked.bin", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        this.server.start();

        DevProxyManager.getInstance().setSourceHostAndPort("127.0.0.1:" + this.server.getAddress().getPort());

        Exchange exchange = new Exchange();
        DevProxyServerResource resource = new DevProxyServerResource();
        setField(resource, "request", exchange.request);
        setField(resource, "response", exchange.response);

        resource.getProxyPath("chunked.bin");

        assertEquals(HttpURLConnection.HTTP_OK, exchange.status);
        assertEquals("application/octet-stream", exchange.contentType);
        assertFalse(exchange.contentLengthSet);
        assertEquals("default-src 'self'", exchange.getResponseHeader("Content-Security-Policy"));
        assertArrayEquals(body, exchange.outputStream.toByteArray());
    }

    @Test
    public void testProxyRewritesRouteStyleHtmlResponses() throws Exception {
        byte[] body = "<html><head></head><body>route html</body></html>".getBytes(StandardCharsets.UTF_8);

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/dashboard", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, body.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        this.server.start();

        DevProxyManager.getInstance().setSourceHostAndPort("127.0.0.1:" + this.server.getAddress().getPort());

        Exchange exchange = new Exchange();
        DevProxyServerResource resource = new DevProxyServerResource();
        setField(resource, "request", exchange.request);
        setField(resource, "response", exchange.response);

        resource.getProxyPath("dashboard");

        String rewrittenBody = new String(exchange.outputStream.toByteArray(), StandardCharsets.UTF_8);
        assertEquals(HttpURLConnection.HTTP_OK, exchange.status);
        assertEquals("text/html; charset=UTF-8", exchange.contentType);
        assertEquals(exchange.outputStream.toByteArray().length, exchange.contentLength);
        assertEquals("default-src 'self' 'unsafe-inline' 'unsafe-eval'; media-src 'self' data: blob:; img-src 'self' data: blob:; connect-src 'self' ws:; font-src 'self' data:;", exchange.getResponseHeader("Content-Security-Policy"));
        assertTrue(rewrittenBody.contains("/apps/q-apps.js?time="));
        assertTrue(rewrittenBody.contains("route html"));
    }

    @Test
    public void testProxyDecodesAndRewritesCompressedHtmlResponses() throws Exception {
        byte[] body = "<html><head></head><body>compressed html</body></html>".getBytes(StandardCharsets.UTF_8);
        byte[] compressedBody = gzip(body);

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/compressed", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, compressedBody.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(compressedBody);
            }
        });
        this.server.start();

        DevProxyManager.getInstance().setSourceHostAndPort("127.0.0.1:" + this.server.getAddress().getPort());

        Exchange exchange = new Exchange();
        DevProxyServerResource resource = new DevProxyServerResource();
        setField(resource, "request", exchange.request);
        setField(resource, "response", exchange.response);

        resource.getProxyPath("compressed");

        String rewrittenBody = new String(exchange.outputStream.toByteArray(), StandardCharsets.UTF_8);
        assertEquals(HttpURLConnection.HTTP_OK, exchange.status);
        assertEquals("text/html", exchange.contentType);
        assertEquals(exchange.outputStream.toByteArray().length, exchange.contentLength);
        assertNull(exchange.getResponseHeader("Content-Encoding"));
        assertTrue(rewrittenBody.contains("/apps/q-apps.js?time="));
        assertTrue(rewrittenBody.contains("compressed html"));
    }

    @Test
    public void testProxyPreservesCompressedNonHtmlResponses() throws Exception {
        byte[] body = "compressed asset".getBytes(StandardCharsets.UTF_8);
        byte[] compressedBody = gzip(body);

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/compressed.bin", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, compressedBody.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(compressedBody);
            }
        });
        this.server.start();

        DevProxyManager.getInstance().setSourceHostAndPort("127.0.0.1:" + this.server.getAddress().getPort());

        Exchange exchange = new Exchange();
        DevProxyServerResource resource = new DevProxyServerResource();
        setField(resource, "request", exchange.request);
        setField(resource, "response", exchange.response);

        resource.getProxyPath("compressed.bin");

        assertEquals(HttpURLConnection.HTTP_OK, exchange.status);
        assertEquals("application/octet-stream", exchange.contentType);
        assertEquals("gzip", exchange.getResponseHeader("Content-Encoding"));
        assertEquals(compressedBody.length, exchange.contentLength);
        assertArrayEquals(compressedBody, exchange.outputStream.toByteArray());
    }

    @Test
    public void testProxyFiltersManagedRequestHeaders() throws Exception {
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        Map<String, String> upstreamHeaders = new LinkedHashMap<>();

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/request-headers.txt", exchange -> {
            upstreamHeaders.put("X-Dev-Proxy-Request", exchange.getRequestHeaders().getFirst("X-Dev-Proxy-Request"));
            upstreamHeaders.put("Host", exchange.getRequestHeaders().getFirst("Host"));
            upstreamHeaders.put("Accept-Encoding", exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            upstreamHeaders.put("Connection", exchange.getRequestHeaders().getFirst("Connection"));
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, body.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        this.server.start();

        DevProxyManager.getInstance().setSourceHostAndPort("127.0.0.1:" + this.server.getAddress().getPort());

        Exchange exchange = new Exchange()
                .withRequestHeader("X-Dev-Proxy-Request", "forwarded")
                .withRequestHeader("Host", "caller.example")
                .withRequestHeader("Accept-Encoding", "gzip")
                .withRequestHeader("Connection", "close");
        DevProxyServerResource resource = new DevProxyServerResource();
        setField(resource, "request", exchange.request);
        setField(resource, "response", exchange.response);

        resource.getProxyPath("request-headers.txt");

        assertEquals(HttpURLConnection.HTTP_OK, exchange.status);
        assertEquals("forwarded", upstreamHeaders.get("X-Dev-Proxy-Request"));
        assertNotEquals("caller.example", upstreamHeaders.get("Host"));
        assertEquals("identity", upstreamHeaders.get("Accept-Encoding"));
        assertNotEquals("close", upstreamHeaders.get("Connection"));
        assertArrayEquals(body, exchange.outputStream.toByteArray());
    }

    @Test
    public void testProxyConnectionUsesTimeoutsAndPreservesRedirectHandling() throws Exception {
        DevProxyServerResource resource = new DevProxyServerResource();
        Method openProxyConnection = DevProxyServerResource.class.getDeclaredMethod("openProxyConnection", URL.class);
        openProxyConnection.setAccessible(true);

        HttpURLConnection con = (HttpURLConnection) openProxyConnection.invoke(resource, new URL("http://127.0.0.1:1/timeout-test.txt"));

        assertEquals(PROXY_CONNECT_TIMEOUT_MS, con.getConnectTimeout());
        assertEquals(PROXY_READ_TIMEOUT_MS, con.getReadTimeout());
        assertFalse(con.getInstanceFollowRedirects());

        con.disconnect();
    }

    @Test
    public void testProxyConnectionRejectsNonLoopbackTargets() throws Exception {
        DevProxyServerResource resource = new DevProxyServerResource();
        Method openProxyConnection = DevProxyServerResource.class.getDeclaredMethod("openProxyConnection", URL.class);
        openProxyConnection.setAccessible(true);

        assertProxyTargetRejected(openProxyConnection, resource, "http://example.com/asset.txt");
        assertProxyTargetRejected(openProxyConnection, resource, "https://127.0.0.1:5173/asset.txt");
    }

    @Test
    public void testProxyPreservesUpstreamRedirects() throws Exception {
        byte[] redirectBody = "redirect preserved".getBytes(StandardCharsets.UTF_8);
        byte[] targetBody = "target reached".getBytes(StandardCharsets.UTF_8);
        AtomicBoolean targetReached = new AtomicBoolean(false);

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/redirect.txt", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target.txt");
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, redirectBody.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(redirectBody);
            }
        });
        this.server.createContext("/target.txt", exchange -> {
            targetReached.set(true);
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, targetBody.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(targetBody);
            }
        });
        this.server.start();

        DevProxyManager.getInstance().setSourceHostAndPort("127.0.0.1:" + this.server.getAddress().getPort());

        Exchange exchange = new Exchange();
        DevProxyServerResource resource = new DevProxyServerResource();
        setField(resource, "request", exchange.request);
        setField(resource, "response", exchange.response);

        resource.getProxyPath("redirect.txt");

        assertEquals(HttpURLConnection.HTTP_MOVED_TEMP, exchange.status);
        assertEquals("/target.txt", exchange.getResponseHeader("Location"));
        assertArrayEquals(redirectBody, exchange.outputStream.toByteArray());
        assertFalse(targetReached.get());
    }

    @Test
    public void testProxyRewritesLocalAbsoluteRedirects() throws Exception {
        byte[] redirectBody = "local absolute redirect".getBytes(StandardCharsets.UTF_8);
        byte[] targetBody = "target reached".getBytes(StandardCharsets.UTF_8);
        AtomicBoolean targetReached = new AtomicBoolean(false);

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/absolute-redirect.txt", exchange -> {
            int port = this.server.getAddress().getPort();
            exchange.getResponseHeaders().add("Location", String.format("http://127.0.0.1:%d/target.txt?from=upstream#section", port));
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, redirectBody.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(redirectBody);
            }
        });
        this.server.createContext("/target.txt", exchange -> {
            targetReached.set(true);
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, targetBody.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(targetBody);
            }
        });
        this.server.start();

        DevProxyManager.getInstance().setSourceHostAndPort("127.0.0.1:" + this.server.getAddress().getPort());

        Exchange exchange = new Exchange();
        DevProxyServerResource resource = new DevProxyServerResource();
        setField(resource, "request", exchange.request);
        setField(resource, "response", exchange.response);

        resource.getProxyPath("absolute-redirect.txt");

        assertEquals(HttpURLConnection.HTTP_MOVED_TEMP, exchange.status);
        assertEquals("/target.txt?from=upstream#section", exchange.getResponseHeader("Location"));
        assertArrayEquals(redirectBody, exchange.outputStream.toByteArray());
        assertFalse(targetReached.get());
    }

    @Test
    public void testProxyRewritesProtocolRelativeLocalRedirects() throws Exception {
        byte[] redirectBody = "protocol-relative local redirect".getBytes(StandardCharsets.UTF_8);
        byte[] targetBody = "target reached".getBytes(StandardCharsets.UTF_8);
        AtomicBoolean targetReached = new AtomicBoolean(false);

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/protocol-relative-redirect.txt", exchange -> {
            int port = this.server.getAddress().getPort();
            exchange.getResponseHeaders().add("Location", String.format("//127.0.0.1:%d/target.txt?from=protocol#section", port));
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, redirectBody.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(redirectBody);
            }
        });
        this.server.createContext("/target.txt", exchange -> {
            targetReached.set(true);
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, targetBody.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(targetBody);
            }
        });
        this.server.start();

        DevProxyManager.getInstance().setSourceHostAndPort("127.0.0.1:" + this.server.getAddress().getPort());

        Exchange exchange = new Exchange();
        DevProxyServerResource resource = new DevProxyServerResource();
        setField(resource, "request", exchange.request);
        setField(resource, "response", exchange.response);

        resource.getProxyPath("protocol-relative-redirect.txt");

        assertEquals(HttpURLConnection.HTTP_MOVED_TEMP, exchange.status);
        assertEquals("/target.txt?from=protocol#section", exchange.getResponseHeader("Location"));
        assertArrayEquals(redirectBody, exchange.outputStream.toByteArray());
        assertFalse(targetReached.get());
    }

    @Test
    public void testProxyRewritesLoopbackAliasRedirects() throws Exception {
        byte[] redirectBody = "loopback alias redirect".getBytes(StandardCharsets.UTF_8);
        byte[] targetBody = "target reached".getBytes(StandardCharsets.UTF_8);
        AtomicBoolean targetReached = new AtomicBoolean(false);

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/loopback-alias-redirect.txt", exchange -> {
            int port = this.server.getAddress().getPort();
            exchange.getResponseHeaders().add("Location", String.format("http://localhost:%d/target.txt?from=alias#section", port));
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, redirectBody.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(redirectBody);
            }
        });
        this.server.createContext("/target.txt", exchange -> {
            targetReached.set(true);
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, targetBody.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(targetBody);
            }
        });
        this.server.start();

        DevProxyManager.getInstance().setSourceHostAndPort("127.0.0.1:" + this.server.getAddress().getPort());

        Exchange exchange = new Exchange();
        DevProxyServerResource resource = new DevProxyServerResource();
        setField(resource, "request", exchange.request);
        setField(resource, "response", exchange.response);

        resource.getProxyPath("loopback-alias-redirect.txt");

        assertEquals(HttpURLConnection.HTTP_MOVED_TEMP, exchange.status);
        assertEquals("/target.txt?from=alias#section", exchange.getResponseHeader("Location"));
        assertArrayEquals(redirectBody, exchange.outputStream.toByteArray());
        assertFalse(targetReached.get());
    }

    @Test
    public void testProxyPreservesExternalAbsoluteRedirects() throws Exception {
        byte[] redirectBody = "external absolute redirect".getBytes(StandardCharsets.UTF_8);
        String externalLocation = "http://example.com/target.txt?from=upstream#section";

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/external-redirect.txt", exchange -> {
            exchange.getResponseHeaders().add("Location", externalLocation);
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, redirectBody.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(redirectBody);
            }
        });
        this.server.start();

        DevProxyManager.getInstance().setSourceHostAndPort("127.0.0.1:" + this.server.getAddress().getPort());

        Exchange exchange = new Exchange();
        DevProxyServerResource resource = new DevProxyServerResource();
        setField(resource, "request", exchange.request);
        setField(resource, "response", exchange.response);

        resource.getProxyPath("external-redirect.txt");

        assertEquals(HttpURLConnection.HTTP_MOVED_TEMP, exchange.status);
        assertEquals(externalLocation, exchange.getResponseHeader("Location"));
        assertArrayEquals(redirectBody, exchange.outputStream.toByteArray());
    }

    @Test
    public void testProxyPreservesExternalProtocolRelativeRedirects() throws Exception {
        byte[] redirectBody = "external protocol-relative redirect".getBytes(StandardCharsets.UTF_8);
        String externalLocation = "//example.com/target.txt?from=upstream#section";

        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/external-protocol-relative-redirect.txt", exchange -> {
            exchange.getResponseHeaders().add("Location", externalLocation);
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, redirectBody.length);

            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(redirectBody);
            }
        });
        this.server.start();

        DevProxyManager.getInstance().setSourceHostAndPort("127.0.0.1:" + this.server.getAddress().getPort());

        Exchange exchange = new Exchange();
        DevProxyServerResource resource = new DevProxyServerResource();
        setField(resource, "request", exchange.request);
        setField(resource, "response", exchange.response);

        resource.getProxyPath("external-protocol-relative-redirect.txt");

        assertEquals(HttpURLConnection.HTTP_MOVED_TEMP, exchange.status);
        assertEquals(externalLocation, exchange.getResponseHeader("Location"));
        assertArrayEquals(redirectBody, exchange.outputStream.toByteArray());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
            gzipOutputStream.write(data);
        }

        return outputStream.toByteArray();
    }

    private static void assertProxyTargetRejected(Method openProxyConnection, DevProxyServerResource resource, String url) throws Exception {
        try {
            openProxyConnection.invoke(resource, new URL(url));
            fail("Expected developer proxy target to be rejected");
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IOException);
            assertTrue(e.getCause().getMessage().contains("loopback HTTP URL"));
        }
    }

    private static class Exchange {
        private final Map<String, String> requestHeaders = new LinkedHashMap<>();
        private final Map<String, String> responseHeaders = new LinkedHashMap<>();
        private final CapturingServletOutputStream outputStream = new CapturingServletOutputStream();
        private int status;
        private String contentType;
        private int contentLength;
        private boolean contentLengthSet;

        private final HttpServletRequest request;
        private final HttpServletResponse response;

        private Exchange() {
            this.request = (HttpServletRequest) Proxy.newProxyInstance(
                    DevProxyServerResourceTests.class.getClassLoader(),
                    new Class[] { HttpServletRequest.class },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getMethod":
                                return "GET";
                            case "getHeaderNames":
                                return Collections.enumeration(this.requestHeaders.keySet());
                            case "getHeader":
                                return this.requestHeaders.get((String) args[0]);
                            case "getQueryString":
                            case "getParameter":
                                return null;
                            case "getLocale":
                                return Locale.getDefault();
                            case "getRequestURI":
                                return "";
                            case "toString":
                                return "DevProxyTestRequest";
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });

            this.response = (HttpServletResponse) Proxy.newProxyInstance(
                    DevProxyServerResourceTests.class.getClassLoader(),
                    new Class[] { HttpServletResponse.class },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setStatus":
                                this.status = (Integer) args[0];
                                return null;
                            case "addHeader":
                                this.responseHeaders.put((String) args[0], (String) args[1]);
                                return null;
                            case "setContentType":
                                this.contentType = (String) args[0];
                                return null;
                            case "setContentLength":
                                this.contentLength = (Integer) args[0];
                                this.contentLengthSet = true;
                                return null;
                            case "getOutputStream":
                                return this.outputStream;
                            case "toString":
                                return "DevProxyTestResponse";
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private Exchange withRequestHeader(String headerName, String headerValue) {
            this.requestHeaders.put(headerName, headerValue);
            return this;
        }

        private String getResponseHeader(String headerName) {
            for (Map.Entry<String, String> entry : this.responseHeaders.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(headerName)) {
                    return entry.getValue();
                }
            }

            return null;
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class)
            return false;

        if (returnType == int.class)
            return 0;

        if (returnType == long.class)
            return 0L;

        return null;
    }

    private static class CapturingServletOutputStream extends ServletOutputStream {

        private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        @Override
        public void write(int b) {
            this.outputStream.write(b);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
        }

        private byte[] toByteArray() {
            return this.outputStream.toByteArray();
        }
    }

}
