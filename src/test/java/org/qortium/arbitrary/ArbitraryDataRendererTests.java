package org.qortium.arbitrary;

import org.junit.Test;

import javax.servlet.ServletContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ArbitraryDataRendererTests {

    @Test
    public void testNonHtmlFileResponseSetsContentLengthBeforeStreaming() throws Exception {
        byte[] body = "rendered asset body".getBytes(StandardCharsets.UTF_8);
        Path directory = Files.createTempDirectory("qdn-renderer");
        Path filePath = directory.resolve("asset.txt");
        Files.write(filePath, body);

        try {
            Exchange exchange = new Exchange("text/plain");

            ArbitraryDataRenderer.streamNonHtmlFileResponse(exchange.response, exchange.context, filePath, "asset.txt");

            assertEquals("default-src 'self'", exchange.responseHeaders.get("Content-Security-Policy"));
            assertEquals("text/plain", exchange.contentType);
            assertEquals(body.length, exchange.contentLength);
            assertArrayEquals(body, exchange.outputStream.toByteArray());
            assertTrue(exchange.callIndex("setContentLength") < exchange.callIndex("getOutputStream"));
        } finally {
            Files.deleteIfExists(filePath);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void testHtmlFileForRewriteReadsUnderLimitHtml() throws Exception {
        byte[] body = "<html><head></head><body>small html</body></html>".getBytes(StandardCharsets.UTF_8);
        Path directory = Files.createTempDirectory("qdn-renderer");
        Path filePath = directory.resolve("index.html");
        Files.write(filePath, body);

        try {
            assertArrayEquals(body, ArbitraryDataRenderer.readHtmlFileForRewrite(filePath));
        } finally {
            Files.deleteIfExists(filePath);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void testHtmlFileForRewriteRejectsOversizedHtmlBeforeReading() throws Exception {
        Path directory = Files.createTempDirectory("qdn-renderer");
        Path filePath = directory.resolve("index.html");
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(filePath.toFile(), "rw")) {
            randomAccessFile.setLength(ArbitraryDataRenderer.MAX_HTML_REWRITE_SIZE + 1);
        }

        try {
            ArbitraryDataRenderer.readHtmlFileForRewrite(filePath);
            fail("Expected oversized HTML file to be rejected");
        } catch (ArbitraryDataRenderer.HtmlFileTooLargeException e) {
            assertTrue(e.getMessage().contains(Long.toString(ArbitraryDataRenderer.MAX_HTML_REWRITE_SIZE)));
        } finally {
            Files.deleteIfExists(filePath);
            Files.deleteIfExists(directory);
        }
    }

    private static class Exchange {

        private final Map<String, String> responseHeaders = new LinkedHashMap<>();
        private final List<String> responseCalls = new ArrayList<>();
        private final CapturingServletOutputStream outputStream = new CapturingServletOutputStream();
        private final ServletContext context;
        private final HttpServletResponse response;
        private String contentType;
        private long contentLength;

        private Exchange(String mimeType) {
            this.context = (ServletContext) Proxy.newProxyInstance(
                    ArbitraryDataRendererTests.class.getClassLoader(),
                    new Class[] { ServletContext.class },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getMimeType":
                                return mimeType;
                            case "toString":
                                return "ArbitraryDataRendererTestContext";
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });

            this.response = (HttpServletResponse) Proxy.newProxyInstance(
                    ArbitraryDataRendererTests.class.getClassLoader(),
                    new Class[] { HttpServletResponse.class },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "addHeader":
                                this.responseHeaders.put((String) args[0], (String) args[1]);
                                this.responseCalls.add(method.getName());
                                return null;
                            case "setContentType":
                                this.contentType = (String) args[0];
                                this.responseCalls.add(method.getName());
                                return null;
                            case "setContentLength":
                                this.contentLength = (Integer) args[0];
                                this.responseCalls.add(method.getName());
                                return null;
                            case "setContentLengthLong":
                                this.contentLength = (Long) args[0];
                                this.responseCalls.add(method.getName());
                                return null;
                            case "getOutputStream":
                                this.responseCalls.add(method.getName());
                                return this.outputStream;
                            case "toString":
                                return "ArbitraryDataRendererTestResponse";
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private int callIndex(String methodName) {
            int index = this.responseCalls.indexOf(methodName);
            assertTrue("Expected response call: " + methodName, index >= 0);
            return index;
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }

        if (returnType == int.class) {
            return 0;
        }

        if (returnType == long.class) {
            return 0L;
        }

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
