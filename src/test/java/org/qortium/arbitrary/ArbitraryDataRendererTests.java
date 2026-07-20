package org.qortium.arbitrary;

import org.junit.Test;
import org.qortium.arbitrary.misc.Service;

import javax.servlet.ServletContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

            ArbitraryDataRenderer.streamNonHtmlFileResponse(exchange.request, exchange.response, exchange.context, filePath, "asset.txt");

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
    public void testJavaScriptAssetResponseAllowsWorkerEval() throws Exception {
        byte[] body = "self.postMessage('ok');".getBytes(StandardCharsets.UTF_8);
        Path directory = Files.createTempDirectory("qdn-renderer");
        Path filePath = directory.resolve("extractzip.worker.js");
        Files.write(filePath, body);

        try {
            Exchange exchange = new Exchange("text/javascript");

            ArbitraryDataRenderer.streamNonHtmlFileResponse(exchange.request, exchange.response, exchange.context, filePath, "extractzip.worker.js");

            String csp = exchange.responseHeaders.get("Content-Security-Policy");
            assertTrue("script-src should allow unsafe-eval: " + csp,
                    csp.contains("script-src 'self' 'unsafe-eval' 'wasm-unsafe-eval'"));
            assertTrue("wasm-unsafe-eval should be present: " + csp, csp.contains("'wasm-unsafe-eval'"));
            assertTrue("workers from blob: should be allowed: " + csp, csp.contains("worker-src 'self' blob:"));
            assertEquals("text/javascript", exchange.contentType);
            assertArrayEquals(body, exchange.outputStream.toByteArray());
        } finally {
            Files.deleteIfExists(filePath);
            Files.deleteIfExists(directory);
        }
    }

    // Byte-range support on the render path is what makes embedded audio/video seekable:
    // native media elements only allow scrubbing past buffered data when the server
    // advertises Accept-Ranges and answers a Range request with 206.

    @Test
    public void testMediaResponseWithoutRangeAdvertisesRangeSupport() throws Exception {
        byte[] body = mediaBody();
        Exchange exchange = streamMedia(body, null);

        assertEquals(HttpServletResponse.SC_OK, exchange.status);
        assertEquals("bytes", exchange.responseHeaders.get("Accept-Ranges"));
        assertNull(exchange.responseHeaders.get("Content-Range"));
        assertEquals(body.length, exchange.contentLength);
        assertArrayEquals(body, exchange.outputStream.toByteArray());
    }

    @Test
    public void testMediaResponseServesBoundedRange() throws Exception {
        byte[] body = mediaBody();
        Exchange exchange = streamMedia(body, "bytes=5-9");

        assertEquals(HttpServletResponse.SC_PARTIAL_CONTENT, exchange.status);
        assertEquals("bytes", exchange.responseHeaders.get("Accept-Ranges"));
        assertEquals("bytes 5-9/" + body.length, exchange.responseHeaders.get("Content-Range"));
        assertEquals(5, exchange.contentLength);
        assertArrayEquals(slice(body, 5, 9), exchange.outputStream.toByteArray());

        // A partial response must keep the same protections as a full one
        assertEquals("default-src 'self'", exchange.responseHeaders.get("Content-Security-Policy"));
        assertEquals("audio/mpeg", exchange.contentType);
    }

    @Test
    public void testMediaResponseServesOpenEndedRange() throws Exception {
        byte[] body = mediaBody();
        Exchange exchange = streamMedia(body, "bytes=40-");

        assertEquals(HttpServletResponse.SC_PARTIAL_CONTENT, exchange.status);
        assertEquals("bytes 40-" + (body.length - 1) + "/" + body.length, exchange.responseHeaders.get("Content-Range"));
        assertEquals(body.length - 40, exchange.contentLength);
        assertArrayEquals(slice(body, 40, body.length - 1), exchange.outputStream.toByteArray());
    }

    @Test
    public void testMediaResponseServesSuffixRange() throws Exception {
        byte[] body = mediaBody();
        Exchange exchange = streamMedia(body, "bytes=-4");

        assertEquals(HttpServletResponse.SC_PARTIAL_CONTENT, exchange.status);
        assertEquals("bytes " + (body.length - 4) + "-" + (body.length - 1) + "/" + body.length,
                exchange.responseHeaders.get("Content-Range"));
        assertEquals(4, exchange.contentLength);
        assertArrayEquals(slice(body, body.length - 4, body.length - 1), exchange.outputStream.toByteArray());
    }

    @Test
    public void testMediaResponseClampsRangeEndToFileSize() throws Exception {
        byte[] body = mediaBody();
        Exchange exchange = streamMedia(body, "bytes=60-999999");

        assertEquals(HttpServletResponse.SC_PARTIAL_CONTENT, exchange.status);
        assertEquals("bytes 60-" + (body.length - 1) + "/" + body.length, exchange.responseHeaders.get("Content-Range"));
        assertEquals(body.length - 60, exchange.contentLength);
        assertArrayEquals(slice(body, 60, body.length - 1), exchange.outputStream.toByteArray());
    }

    @Test
    public void testMediaResponseRejectsRangeStartingPastEndOfFile() throws Exception {
        byte[] body = mediaBody();
        Exchange exchange = streamMedia(body, "bytes=" + body.length + "-");

        assertEquals(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE, exchange.status);
        assertEquals("bytes */" + body.length, exchange.responseHeaders.get("Content-Range"));
        assertEquals(0, exchange.contentLength);
        assertEquals(0, exchange.outputStream.toByteArray().length);

        // The 416 must be committed here. Without this the caller returns an uncommitted response
        // to Jersey, which marshals it as an entity, fails, and ships a 400 instead.
        exchange.callIndex("flushBuffer");
    }

    @Test
    public void testMediaResponseRejectsMalformedRangeUnit() throws Exception {
        byte[] body = mediaBody();
        Exchange exchange = streamMedia(body, "items=0-1");

        assertEquals(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE, exchange.status);
        assertEquals(0, exchange.outputStream.toByteArray().length);
        exchange.callIndex("flushBuffer");
    }

    @Test
    public void testMediaResponseRejectsMultipartRange() throws Exception {
        byte[] body = mediaBody();
        Exchange exchange = streamMedia(body, "bytes=0-1,4-5");

        // Serving only the first part of a multipart request would silently give the client
        // less than it asked for, so it is refused outright
        assertEquals(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE, exchange.status);
        assertEquals(0, exchange.outputStream.toByteArray().length);
        exchange.callIndex("flushBuffer");
    }

    private static byte[] mediaBody() {
        byte[] body = new byte[128];
        for (int i = 0; i < body.length; ++i) {
            body[i] = (byte) i;
        }

        return body;
    }

    private static byte[] slice(byte[] body, int startInclusive, int endInclusive) {
        byte[] slice = new byte[endInclusive - startInclusive + 1];
        System.arraycopy(body, startInclusive, slice, 0, slice.length);
        return slice;
    }

    /**
     * QDN cache files are pruned and rebuilt while they are being served. Content-Length and
     * Content-Range are now derived from the open channel rather than from the path, so they
     * describe the file actually being read. If that file is nevertheless truncated in place
     * mid-response, the loop must stop cleanly and report a short body rather than spin, throw,
     * or silently claim success.
     */
    @Test
    public void testMediaResponseHandlesFileTruncatedWhileStreaming() throws Exception {
        // Larger than the 10240-byte copy buffer so the body takes several reads and the file
        // can be disturbed after the first one.
        byte[] body = new byte[50_000];
        for (int i = 0; i < body.length; ++i) {
            body[i] = (byte) i;
        }

        Path directory = Files.createTempDirectory("qdn-renderer");
        Path filePath = directory.resolve("clip.mp3");
        Files.write(filePath, body);

        try {
            Exchange exchange = new Exchange("audio/mpeg", null);
            exchange.outputStream.onFirstWrite = () -> {
                try (RandomAccessFile randomAccessFile = new RandomAccessFile(filePath.toFile(), "rw")) {
                    randomAccessFile.setLength(10_240);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            };

            ArbitraryDataRenderer.streamNonHtmlFileResponse(
                    exchange.request, exchange.response, exchange.context, filePath, "clip.mp3");

            // The declared length still describes the file as opened...
            assertEquals(body.length, exchange.contentLength);
            // ...but only the surviving bytes could be sent, and the call returned normally.
            assertTrue(
                    "Expected a short body after truncation, got " + exchange.outputStream.toByteArray().length,
                    exchange.outputStream.toByteArray().length < body.length);
            assertArrayEquals(slice(body, 0, 10_239), exchange.outputStream.toByteArray());
        } finally {
            Files.deleteIfExists(filePath);
            Files.deleteIfExists(directory);
        }
    }

    private static Exchange streamMedia(byte[] body, String rangeHeader) throws Exception {
        Path directory = Files.createTempDirectory("qdn-renderer");
        Path filePath = directory.resolve("clip.mp3");
        Files.write(filePath, body);

        try {
            Exchange exchange = new Exchange("audio/mpeg", rangeHeader);
            ArbitraryDataRenderer.streamNonHtmlFileResponse(exchange.request, exchange.response, exchange.context, filePath, "clip.mp3");
            return exchange;
        } finally {
            Files.deleteIfExists(filePath);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void testPlainTextResponseSetsContentTypeAndUtf8Length() {
        Exchange exchange = new Exchange("text/plain");
        String body = "Invalid \u03c0";

        ArbitraryDataRenderer.getResponse(exchange.response, 404, body);

        assertEquals(404, exchange.status);
        assertEquals("text/plain; charset=UTF-8", exchange.contentType);
        assertEquals(body.getBytes(StandardCharsets.UTF_8).length, exchange.contentLength);
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), exchange.outputStream.toByteArray());
    }

    @Test
    public void testJavaScriptStringEscaperForLoadingTemplateData() throws Exception {
        Method escapeJavaScriptStringContents = ArbitraryDataRenderer.class.getDeclaredMethod("escapeJavaScriptStringContents", String.class);
        escapeJavaScriptStringContents.setAccessible(true);

        assertEquals("\\u003c/script\\u003e\\n\\u0026\\u0027\\\"", escapeJavaScriptStringContents.invoke(null, "</script>\n&'\""));
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

    @Test
    public void testResolveRequestedFilePathTreatsLeadingSlashAsResourceRoot() throws Exception {
        Path directory = Files.createTempDirectory("qdn-renderer");

        try {
            Path resolved = ArbitraryDataRenderer.resolveRequestedFilePath(directory, "/nested/index.html");

            assertEquals(directory.resolve("nested/index.html").normalize(), resolved);
        } finally {
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void testResolveRequestedFilePathRejectsParentTraversal() throws Exception {
        Path directory = Files.createTempDirectory("qdn-renderer");

        try {
            ArbitraryDataRenderer.resolveRequestedFilePath(directory, "/../outside.txt");
            fail("Expected parent traversal to be rejected");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("outside of the target dir"));
        } finally {
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void testResolveRequestedFilePathRejectsBackslashTraversal() throws Exception {
        Path directory = Files.createTempDirectory("qdn-renderer");

        try {
            ArbitraryDataRenderer.resolveRequestedFilePath(directory, "..\\outside.txt");
            fail("Expected backslash parent traversal to be rejected");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("outside of the target dir"));
        } finally {
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void testResolveRequestedFilePathRejectsInvalidPath() throws Exception {
        Path directory = Files.createTempDirectory("qdn-renderer");

        try {
            ArbitraryDataRenderer.resolveRequestedFilePath(directory, "bad\u0000path");
            fail("Expected invalid path to be rejected");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("invalid"));
        } finally {
            Files.deleteIfExists(directory);
        }
    }

    // --- Smart SPA-routing fallback ---

    private static final String HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    @Test
    public void appAlwaysHasRouting() {
        assertTrue(ArbitraryDataRenderer.spaRoutingEnabled(Service.APP, null));
        assertTrue(ArbitraryDataRenderer.spaRoutingEnabled(Service.APP, "index.html"));
    }

    @Test
    public void plainWebsiteIsUnaffected() {
        // A WEBSITE without an entryPoint keeps static behaviour (no route forwarding)
        assertFalse(ArbitraryDataRenderer.spaRoutingEnabled(Service.WEBSITE, null));
        assertFalse(ArbitraryDataRenderer.spaRoutingEnabled(Service.WEBSITE, ""));
    }

    @Test
    public void websiteWithEntryPointOptsIn() {
        assertTrue(ArbitraryDataRenderer.spaRoutingEnabled(Service.WEBSITE, "index.html"));
    }

    @Test
    public void extensionlessPathsAreRoutes() {
        assertTrue(ArbitraryDataRenderer.isRouteLikeRequest("/dashboard", null));
        assertTrue(ArbitraryDataRenderer.isRouteLikeRequest("/users/42", null));
        assertTrue(ArbitraryDataRenderer.isRouteLikeRequest("/", null));
    }

    @Test
    public void missingAssetsAreNotRoutes() {
        // These must NOT forward, so a missing asset cleanly 404s instead of serving HTML
        assertFalse(ArbitraryDataRenderer.isRouteLikeRequest("/styles.css", "text/css,*/*;q=0.1"));
        assertFalse(ArbitraryDataRenderer.isRouteLikeRequest("/app.js", "*/*"));
        assertFalse(ArbitraryDataRenderer.isRouteLikeRequest("/data.json", null));
        assertFalse(ArbitraryDataRenderer.isRouteLikeRequest("/logo.png", "image/avif,image/webp,*/*"));
    }

    @Test
    public void missingHtmlFilesAreNotRoutes() {
        assertFalse(ArbitraryDataRenderer.isRouteLikeRequest("/player.html", HTML_ACCEPT));
        assertFalse(ArbitraryDataRenderer.isRouteLikeRequest("/nested/player.HTML", HTML_ACCEPT));
    }

    @Test
    public void dottedPathIsRouteOnlyWhenBrowserNavigating() {
        assertTrue(ArbitraryDataRenderer.isRouteLikeRequest("/report.2024", HTML_ACCEPT));
        assertFalse(ArbitraryDataRenderer.isRouteLikeRequest("/report.2024", "*/*"));
    }

    @Test
    public void forwardsToDeclaredEntryPoint() throws IOException {
        Path dir = dirWith("index.html", "main.html");
        // A non-conventional entry file is honoured ahead of the index convention
        assertEquals("main.html", ArbitraryDataRenderer.resolveFallbackFile(dir, "main.html").getFileName().toString());
    }

    @Test
    public void forwardsToIndexWhenNoEntryPoint() throws IOException {
        Path dir = dirWith("index.html", "about.html");
        assertEquals("index.html", ArbitraryDataRenderer.resolveFallbackFile(dir, null).getFileName().toString());
    }

    @Test
    public void fallsBackToIndexWhenEntryPointMissing() throws IOException {
        Path dir = dirWith("index.html");
        assertEquals("index.html", ArbitraryDataRenderer.resolveFallbackFile(dir, "does-not-exist.html").getFileName().toString());
    }

    @Test
    public void unsafeEntryPointDoesNotEscape() throws IOException {
        Path dir = dirWith("index.html");
        // A traversal entryPoint must be ignored and fall back to the index, never serve outside the base
        assertEquals("index.html", ArbitraryDataRenderer.resolveFallbackFile(dir, "../secret").getFileName().toString());
    }

    @Test
    public void noFallbackWhenNothingAvailable() throws IOException {
        Path dir = dirWith("about.html", "contact.html"); // no index, no entryPoint
        assertNull(ArbitraryDataRenderer.resolveFallbackFile(dir, null));
    }

    private static Path dirWith(String... fileNames) throws IOException {
        Path dir = Files.createTempDirectory("renderer-fallback-test");
        dir.toFile().deleteOnExit();
        for (String fileName : fileNames) {
            Path file = dir.resolve(fileName);
            Files.write(file, "<html></html>".getBytes(StandardCharsets.UTF_8));
            file.toFile().deleteOnExit();
        }
        return dir;
    }

    private static class Exchange {

        private final Map<String, String> requestHeaders = new LinkedHashMap<>();
        private final Map<String, String> responseHeaders = new LinkedHashMap<>();
        private final List<String> responseCalls = new ArrayList<>();
        private final CapturingServletOutputStream outputStream = new CapturingServletOutputStream();
        private final ServletContext context;
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private int status = HttpServletResponse.SC_OK;
        private String contentType;
        private long contentLength;

        private Exchange(String mimeType) {
            this(mimeType, null);
        }

        private Exchange(String mimeType, String rangeHeader) {
            if (rangeHeader != null) {
                this.requestHeaders.put("Range", rangeHeader);
            }

            this.request = (HttpServletRequest) Proxy.newProxyInstance(
                    ArbitraryDataRendererTests.class.getClassLoader(),
                    new Class[] { HttpServletRequest.class },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getHeader":
                                return this.requestHeaders.get((String) args[0]);
                            case "toString":
                                return "ArbitraryDataRendererTestRequest";
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });

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
                            case "setStatus":
                                this.status = (Integer) args[0];
                                this.responseCalls.add(method.getName());
                                return null;
                            case "addHeader":
                            case "setHeader":
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
                            case "flushBuffer":
                                this.responseCalls.add(method.getName());
                                return null;
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
        // Lets a test disturb the file on disk part-way through the response, which is the only
        // way to reach the short-read branch deterministically.
        private Runnable onFirstWrite;
        private boolean written;

        @Override
        public void write(int b) {
            this.fireFirstWrite();
            this.outputStream.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            this.fireFirstWrite();
            this.outputStream.write(b, off, len);
        }

        private void fireFirstWrite() {
            if (this.written) {
                return;
            }

            this.written = true;

            if (this.onFirstWrite != null) {
                this.onFirstWrite.run();
            }
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
