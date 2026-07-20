package org.qortium.arbitrary;

import com.google.common.io.Resources;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.api.HTMLParser;
import org.qortium.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortium.arbitrary.exception.MissingDataException;
import org.qortium.arbitrary.metadata.ArbitraryDataTransactionMetadata;
import org.qortium.arbitrary.misc.Service;
import org.qortium.controller.Controller;
import org.qortium.controller.arbitrary.ArbitraryMetadataManager;
import org.qortium.settings.Settings;
import org.qortium.utils.FilesystemUtils;
import org.qortium.utils.HttpRanges;
import org.qortium.utils.HttpRanges.InvalidHttpRangeException;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;

import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ArbitraryDataRenderer {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataRenderer.class);
    static final long MAX_HTML_REWRITE_SIZE = 5L * 1024 * 1024;
    private static final Set<String> KNOWN_FILE_EXTENSIONS = Set.of(
            "3gp", "aac", "avi", "bin", "bmp", "css", "csv", "gif", "gz", "htm", "html", "ico", "jpeg",
            "jpg", "js", "json", "m4a", "map", "mjs", "mkv", "mov", "mp3", "mp4", "oga", "ogg", "ogv",
            "otf", "pdf", "png", "svg", "tar", "ttf", "txt", "wasm", "wav", "webm", "webp", "woff",
            "woff2", "xml", "zip");

    private final String resourceId;
    private final ResourceIdType resourceIdType;
    private final Service service;
    private final String identifier;
    // Display settings default to empty so that, when the caller (host) does not
    // specify them, Core does NOT clobber the app with a hard-coded default. The
    // app then keeps the host's setting (e.g. via its own persistence) across
    // navigations instead of being reset to light/medium/green on every
    // param-less render request. The loading splash falls back to a cosmetic
    // default in getLoadingResponse().
    private String theme = "";
    private String lang = "en";
    private String textSize = "";
    private String accent = "";
    private String inPath;
    private final String secret58;
    private final String prefix;
    private final boolean includeResourceIdInPrefix;
    private final boolean async;
    private final String qdnContext;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final ServletContext context;
    // Metadata entryPoint, loaded lazily and cached for the lifetime of this render.
    private boolean entryPointLoaded = false;
    private String cachedEntryPoint = null;

    public ArbitraryDataRenderer(String resourceId, ResourceIdType resourceIdType, Service service, String identifier,
                                 String inPath, String secret58, String prefix, boolean includeResourceIdInPrefix, boolean async, String qdnContext,
                                 HttpServletRequest request, HttpServletResponse response, ServletContext context) {

        this.resourceId = resourceId;
        this.resourceIdType = resourceIdType;
        this.service = service;
        this.identifier = identifier != null ? identifier : "default";
        this.inPath = inPath;
        this.secret58 = secret58;
        this.prefix = prefix;
        this.includeResourceIdInPrefix = includeResourceIdInPrefix;
        this.async = async;
        this.qdnContext = qdnContext;
        this.request = request;
        this.response = response;
        this.context = context;
    }

    public HttpServletResponse render() {
        if (!inPath.startsWith("/")) {
            inPath = "/" + inPath;
        }

        // Don't render data if QDN is disabled
        if (!Settings.getInstance().isQdnEnabled()) {
            return ArbitraryDataRenderer.getResponse(response, 500, "QDN is disabled in settings");
        }

        ArbitraryDataReader arbitraryDataReader;
        try {
            arbitraryDataReader = new ArbitraryDataReader(resourceId, resourceIdType, service, identifier);
            arbitraryDataReader.setSecret58(secret58); // Optional, used for loading encrypted file hashes only

            if (!arbitraryDataReader.isCachedDataAvailable()) {
                // If async is requested, show a loading screen whilst build is in progress
                if (async) {
                    arbitraryDataReader.loadAsynchronously(false, 10);
                    return this.getLoadingResponse(service, resourceId, identifier, theme, accent);
                }

                // Otherwise, loop until we have data
                int attempts = 0;
                while (!Controller.isStopping()) {
                    attempts++;
                    if (!arbitraryDataReader.isBuilding()) {
                        try {
                            arbitraryDataReader.loadSynchronously(false);
                            break;
                        } catch (MissingDataException e) {
                            if (attempts > 5) {
                                // Give up after 5 attempts
                                return ArbitraryDataRenderer.getResponse(response, 404, "Data unavailable. Please try again later.");
                            }
                        }
                    }
                    Thread.sleep(3000L);
                }
            }

        } catch (Exception e) {
            LOGGER.info(String.format("Unable to load %s %s: %s", service, resourceId, e.getMessage()));
            return ArbitraryDataRenderer.getResponse(response, 500, "Error 500: Internal Server Error");
        }

        java.nio.file.Path path = arbitraryDataReader.getFilePath();
        if (path == null) {
            return ArbitraryDataRenderer.getResponse(response, 404, "Error 404: File Not Found");
        }
        String unzippedPath = path.toString();

        // Set path automatically for single file resources (except for apps, which handle routing differently)
        String[] files = ArrayUtils.removeElement(new File(unzippedPath).list(), ".qdn");
        if (files.length == 1 && this.service != Service.APP) {
            // This is a single file resource
            inPath = files[0];
        }

        try {
            String filename = this.getFilename(unzippedPath, inPath);
            Path filePath = ArbitraryDataRenderer.resolveRequestedFilePath(Paths.get(unzippedPath), filename);
            boolean usingCustomRouting = false;
            if (Files.isDirectory(filePath) && (!inPath.endsWith("/"))) {
                inPath = inPath + "/";
                filename = this.getFilename(unzippedPath, inPath);
                filePath = ArbitraryDataRenderer.resolveRequestedFilePath(Paths.get(unzippedPath), filename);
            }
            
            // If the file doesn't exist, we may be able to route the request elsewhere (SPA-style), or cleanup
            if (!Files.exists(filePath)) {
                // SPA-style routing: forward an unhandled *route* request (not a missing asset) to a
                // fallback file, so a client-side router can handle it. Opt-in: APP always; any other
                // service when it declares a metadata entryPoint. Missing assets still 404, so static
                // sites are unaffected.
                // Cheap, no-I/O check first: only route-like requests (not missing assets) may fall
                // back, so a missing static asset 404s without any metadata lookup.
                String acceptHeader = this.request != null ? this.request.getHeader("Accept") : null;
                if (isRouteLikeRequest(inPath, acceptHeader)) {
                    String entryPoint = this.getMetadataEntryPoint();
                    if (spaRoutingEnabled(this.service, entryPoint)) {
                        // Forward to the declared entryPoint, else the index-file convention.
                        Path fallbackPath = resolveFallbackFile(Paths.get(unzippedPath), entryPoint);
                        if (fallbackPath != null) {
                            filePath = fallbackPath;
                            filename = fallbackPath.getFileName().toString();
                            usingCustomRouting = true;
                        }
                    }
                }

                // Still nothing and this was the root request: the resource has no renderable entry,
                // so delete the unzipped cache.
                if (!Files.exists(filePath) && inPath.equals("/")) {
                    try {
                        FileUtils.deleteDirectory(new File(unzippedPath));
                    } catch (IOException e) {
                        LOGGER.debug("Unable to delete directory: {}", unzippedPath, e);
                    }
                }
            }

            if (HTMLParser.isHtmlFile(filename)) {
                // HTML file - needs to be parsed
                byte[] data = ArbitraryDataRenderer.readHtmlFileForRewrite(filePath);
                String encodedResourceId;

                if (resourceIdType == ResourceIdType.NAME) {
                    encodedResourceId = resourceId.replace(" ", "%20");
                } else {
                    encodedResourceId = resourceId;
                }
                HTMLParser htmlParser = new HTMLParser(encodedResourceId, inPath, prefix, includeResourceIdInPrefix, data, qdnContext, service, identifier, theme, usingCustomRouting, lang, textSize, accent);
                htmlParser.addAdditionalHeaderTags();
                response.addHeader(
                    "Content-Security-Policy",
                    "default-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                    "font-src 'self' data:; " +

                    // allow localhost for media
                    "media-src 'self' data: blob: http://127.0.0.1:* http://localhost:*; " +

                    "img-src 'self' data: blob:; " +

                    // emulator/Emscripten runtimes spawn workers from same-origin or blob URLs
                    "worker-src 'self' blob:; " +

                    "connect-src 'self' wss: blob:;"
                );
                response.setContentType(context.getMimeType(filename));
                response.setContentLength(htmlParser.getData().length);
                response.getOutputStream().write(htmlParser.getData());
            }
            else {
                // Regular file - can be streamed directly, and is seekable via HTTP byte ranges
                ArbitraryDataRenderer.streamNonHtmlFileResponse(this.request, response, context, filePath, filename);
            }
            return response;
        } catch (HtmlFileTooLargeException e) {
            LOGGER.info("Unable to render HTML file at path {}: {}", inPath, e.getMessage());
            return ArbitraryDataRenderer.getResponse(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Error 413: HTML File Too Large");
        } catch (FileNotFoundException | NoSuchFileException e) {
            LOGGER.info("Unable to serve file: {}", e.getMessage());
        } catch (IOException e) {
            LOGGER.info("Unable to serve file at path {}: {}", inPath, e.getMessage());
        }

        return ArbitraryDataRenderer.getResponse(response, 404, "Error 404: File Not Found");
    }

    static byte[] readHtmlFileForRewrite(Path filePath) throws IOException {
        long fileSize = Files.size(filePath);
        if (fileSize > MAX_HTML_REWRITE_SIZE) {
            throw new HtmlFileTooLargeException(fileSize);
        }

        return Files.readAllBytes(filePath);
    }

    static class HtmlFileTooLargeException extends IOException {

        private HtmlFileTooLargeException(long fileSize) {
            super(String.format("HTML file is too large: %d bytes (max size: %d bytes)", fileSize, MAX_HTML_REWRITE_SIZE));
        }

    }

    /**
     * Content-Security-Policy for a streamed (non-HTML) render asset. JavaScript assets — including
     * worker scripts — need to eval and instantiate WebAssembly and to spawn workers (e.g. EmulatorJS
     * / Emscripten), so they receive a script/worker-aware policy. Every other asset stays locked to
     * its own origin.
     */
    public static String contentSecurityPolicyForAsset(String filename) {
        if (isExecutableScriptAsset(filename)) {
            return "default-src 'self'; " +
                    "script-src 'self' 'unsafe-eval' 'wasm-unsafe-eval'; " +
                    "worker-src 'self' blob:; " +
                    "connect-src 'self' blob:;";
        }

        return "default-src 'self'";
    }

    private static boolean isExecutableScriptAsset(String filename) {
        if (filename == null) {
            return false;
        }

        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs");
    }

    /**
     * Streams a non-HTML render asset, honouring a single HTTP byte range.
     *
     * <p>Range support matters for embedded media: native audio/video elements only treat a
     * source as seekable when the server advertises {@code Accept-Ranges} and answers with
     * {@code 206}, so without this a scrubber cannot move outside already-buffered data.
     *
     * <p>The HTML path deliberately does not come through here — those bytes are rewritten in
     * memory by {@link org.qortium.api.HTMLParser}, so an offset into the on-disk file would
     * not correspond to what the client is being served.
     */
    static void streamNonHtmlFileResponse(HttpServletRequest request, HttpServletResponse response, ServletContext context, Path filePath, String filename) throws IOException {
        response.addHeader("Content-Security-Policy", contentSecurityPolicyForAsset(filename));
        response.setContentType(context.getMimeType(filename));

        // Open the file before measuring it, and take the size from the open channel rather than
        // from the path. QDN cache files are pruned and rebuilt in the background, so sizing the
        // path and then opening it separately leaves a window in which the file described by
        // Content-Length/Content-Range is not the file actually served. An open channel keeps
        // referring to the same content even if the path is replaced underneath us.
        try (SeekableByteChannel channel = Files.newByteChannel(filePath, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            response.setHeader("Accept-Ranges", "bytes");

            long[] requestedRange;
            try {
                requestedRange = HttpRanges.parse(request == null ? null : request.getHeader("Range"), fileSize);
            } catch (InvalidHttpRangeException e) {
                LOGGER.debug("Invalid range for {}: {}", filename, e.getMessage());
                response.setHeader("Content-Range", String.format("bytes */%d", fileSize));
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setContentLength(0);
                // Commit the response before returning. This method's caller hands the response back to
                // Jersey, which will otherwise try to marshal it as an entity because nothing was
                // written, fail, and let ApiExceptionMapper replace our 416 with a 400.
                response.flushBuffer();
                return;
            }

            long rangeStart = requestedRange == null ? 0 : requestedRange[0];
            long rangeEnd = requestedRange == null ? fileSize - 1 : requestedRange[1];
            long contentLength = requestedRange == null ? fileSize : rangeEnd - rangeStart + 1;

            if (requestedRange != null) {
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                response.setHeader("Content-Range", String.format("bytes %d-%d/%d", rangeStart, rangeEnd, fileSize));
            }

            setResponseContentLength(response, contentLength);

            if (rangeStart > 0) {
                channel.position(rangeStart);
            }

            OutputStream outputStream = response.getOutputStream();
            byte[] buffer = new byte[10240];
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
            long bytesRemaining = contentLength;

            while (bytesRemaining > 0) {
                byteBuffer.clear();
                byteBuffer.limit((int) Math.min(buffer.length, bytesRemaining));

                int bytesRead = channel.read(byteBuffer);
                if (bytesRead == -1) {
                    break;
                }

                outputStream.write(buffer, 0, bytesRead);
                bytesRemaining -= bytesRead;
            }

            if (bytesRemaining > 0) {
                // We promised contentLength bytes and cannot deliver them. The response is already
                // committed, so the client sees the Content-Length mismatch as a truncated transfer,
                // which is the honest outcome — but do not let it pass silently.
                LOGGER.warn("Truncated render response for {}: {} of {} bytes short, file changed while being served",
                        filename, bytesRemaining, contentLength);
            }
        }
    }

    private static void setResponseContentLength(HttpServletResponse response, long contentLength) {
        if (contentLength > Integer.MAX_VALUE) {
            response.setContentLengthLong(contentLength);
        } else {
            response.setContentLength((int) contentLength);
        }
    }

    static Path resolveRequestedFilePath(Path baseDirectory, String filename) throws IOException {
        return FilesystemUtils.resolveRelativePathInsideBase(baseDirectory, filename);
    }

    /**
     * The declared metadata entryPoint for this resource, or null. Loaded lazily and cached for the
     * lifetime of this render, so the metadata lookup happens at most once and only when needed.
     */
    private String getMetadataEntryPoint() {
        if (this.entryPointLoaded) {
            return this.cachedEntryPoint;
        }
        this.entryPointLoaded = true;
        try {
            ArbitraryDataResource resource = new ArbitraryDataResource(resourceId, resourceIdType, service, identifier);
            ArbitraryDataTransactionMetadata metadata = ArbitraryMetadataManager.getInstance().fetchMetadata(resource, true);
            this.cachedEntryPoint = (metadata != null) ? metadata.getEntryPoint() : null;
        } catch (Exception e) {
            LOGGER.debug("Unable to load metadata entryPoint for {}: {}", resourceId, e.getMessage());
            this.cachedEntryPoint = null;
        }
        return this.cachedEntryPoint;
    }

    /**
     * Whether SPA-style route forwarding applies to this resource: always for APP (back-compat), and
     * for any other service that declares an entryPoint (opt-in). A service without an entryPoint
     * (e.g. a plain WEBSITE) is unaffected and serves files as-is.
     */
    static boolean spaRoutingEnabled(Service service, String entryPoint) {
        return service == Service.APP || (entryPoint != null && !entryPoint.isEmpty());
    }

    /**
     * Whether a request looks like a client-side route (so it may fall back to an entry file) rather
     * than a missing file (which should 404). Extensionless paths are routes. Dotted paths with known
     * file extensions are files. Other dotted paths may still be routes when the browser is navigating.
     */
    static boolean isRouteLikeRequest(String requestPath, String acceptHeader) {
        String lastSegment = requestPath == null ? "" : requestPath;
        int slash = lastSegment.lastIndexOf('/');
        if (slash >= 0) {
            lastSegment = lastSegment.substring(slash + 1);
        }
        int dot = lastSegment.lastIndexOf('.');
        if (dot < 0) {
            return true;
        }
        String extension = lastSegment.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (KNOWN_FILE_EXTENSIONS.contains(extension)) {
            return false;
        }
        // A dotted path may still be a route if the browser is navigating to it.
        return acceptHeader != null && acceptHeader.contains("text/html");
    }

    /**
     * The file an unhandled route should forward to: the declared entryPoint if it exists, otherwise
     * the first existing index-convention file. Returns null if none is available.
     */
    static Path resolveFallbackFile(Path baseDirectory, String entryPoint) {
        if (entryPoint != null && !entryPoint.isEmpty()) {
            try {
                Path entryPointPath = resolveRequestedFilePath(baseDirectory, entryPoint);
                if (Files.exists(entryPointPath)) {
                    return entryPointPath;
                }
            } catch (IOException e) {
                // Unsafe/escaping entryPoint - ignore and fall through to the index convention.
            }
        }
        for (String indexFile : ArbitraryDataRenderer.indexFiles()) {
            Path indexPath = baseDirectory.resolve(indexFile);
            if (Files.exists(indexPath)) {
                return indexPath;
            }
        }
        return null;
    }

    private String getFilename(String directory, String userPath) {
        if (userPath == null || userPath.endsWith("/") || userPath.isEmpty()) {
            // Locate index file
            List<String> indexFiles = ArbitraryDataRenderer.indexFiles();
            for (String indexFile : indexFiles) {
                Path path = Paths.get(directory, indexFile);
                if (Files.exists(path)) {
                    return userPath + indexFile;
                }
            }
        }
        return userPath;
    }

    private HttpServletResponse getLoadingResponse(Service service, String name, String identifier, String theme, String accent) {
        String responseString = "";
        URL url = Resources.getResource("loading/index.html");
        try {
            responseString = Resources.toString(url, StandardCharsets.UTF_8);

            // Replace vars
            responseString = responseString.replace("%%SERVICE%%", escapeJavaScriptStringContents(service.toString()));
            responseString = responseString.replace("%%NAME%%", escapeJavaScriptStringContents(name));
            responseString = responseString.replace("%%IDENTIFIER%%", escapeJavaScriptStringContents(identifier));
            // The loading splash needs concrete colours; fall back cosmetically
            // when the host did not specify a theme/accent (see field defaults).
            String splashTheme = (theme == null || theme.isEmpty()) ? "light" : theme;
            String splashAccent = (accent == null || accent.isEmpty()) ? "green" : accent;
            responseString = responseString.replace("%%THEME%%", escapeJavaScriptStringContents(splashTheme));
            responseString = responseString.replace("%%ACCENT%%", escapeJavaScriptStringContents(splashAccent));

        } catch (IOException e) {
            LOGGER.info("Unable to show loading screen: {}", e.getMessage());
        }
        return ArbitraryDataRenderer.getHtmlResponse(response, 503, responseString);
    }

    public static HttpServletResponse getResponse(HttpServletResponse response, int responseCode, String responseString) {
        return ArbitraryDataRenderer.writeResponse(response, responseCode, "text/plain; charset=UTF-8", responseString);
    }

    private static HttpServletResponse getHtmlResponse(HttpServletResponse response, int responseCode, String responseString) {
        return ArbitraryDataRenderer.writeResponse(response, responseCode, "text/html; charset=UTF-8", responseString);
    }

    private static HttpServletResponse writeResponse(HttpServletResponse response, int responseCode, String contentType, String responseString) {
        try {
            byte[] responseData = responseString.getBytes(StandardCharsets.UTF_8);
            response.setStatus(responseCode);
            response.setContentType(contentType);
            response.setContentLength(responseData.length);
            response.getOutputStream().write(responseData);
        } catch (IOException e) {
            LOGGER.info("Error writing {} response", responseCode);
        }
        return response;
    }

    private static String escapeJavaScriptStringContents(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder output = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    output.append("\\\\");
                    break;
                case '"':
                    output.append("\\\"");
                    break;
                case '\b':
                    output.append("\\b");
                    break;
                case '\f':
                    output.append("\\f");
                    break;
                case '\n':
                    output.append("\\n");
                    break;
                case '\r':
                    output.append("\\r");
                    break;
                case '\t':
                    output.append("\\t");
                    break;
                case '<':
                    output.append("\\u003c");
                    break;
                case '>':
                    output.append("\\u003e");
                    break;
                case '&':
                    output.append("\\u0026");
                    break;
                case '\'':
                    output.append("\\u0027");
                    break;
                case '\u2028':
                    output.append("\\u2028");
                    break;
                case '\u2029':
                    output.append("\\u2029");
                    break;
                default:
                    if (c < 0x20) {
                        output.append(String.format("\\u%04x", (int) c));
                    } else {
                        output.append(c);
                    }
            }
        }
        return output.toString();
    }

    public static List<String> indexFiles() {
        List<String> indexFiles = new ArrayList<>();
        indexFiles.add("index.html");
        indexFiles.add("index.htm");
        indexFiles.add("default.html");
        indexFiles.add("default.htm");
        indexFiles.add("home.html");
        indexFiles.add("home.htm");
        return indexFiles;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }
    public void setLang(String lang) {
        this.lang = lang;
    }    
    public void setTextSize(String textSize) {
        this.textSize = textSize;
    }
    public void setAccent(String accent) {
        this.accent = accent;
    }

}
