package org.qortal.test.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.api.proxy.resource.DevProxyServerResource;
import org.qortal.controller.DevProxyManager;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

public class DevProxyServerResourceTests {

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
        assertNotEquals("gzip", upstreamHeaders.get("Accept-Encoding"));
        assertNotEquals("close", upstreamHeaders.get("Connection"));
        assertArrayEquals(body, exchange.outputStream.toByteArray());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class Exchange {
        private final Map<String, String> requestHeaders = new LinkedHashMap<>();
        private final Map<String, String> responseHeaders = new LinkedHashMap<>();
        private final CapturingServletOutputStream outputStream = new CapturingServletOutputStream();
        private int status;
        private String contentType;
        private int contentLength;

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
