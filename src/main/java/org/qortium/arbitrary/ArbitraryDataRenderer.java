package org.qortium.arbitrary;

import com.google.common.io.Resources;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.api.HTMLParser;
import org.qortium.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortium.arbitrary.exception.MissingDataException;
import org.qortium.arbitrary.misc.Service;
import org.qortium.controller.Controller;
import org.qortium.settings.Settings;
import org.qortium.utils.FilesystemUtils;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ArbitraryDataRenderer {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataRenderer.class);
    static final long MAX_HTML_REWRITE_SIZE = 5L * 1024 * 1024;

    private final String resourceId;
    private final ResourceIdType resourceIdType;
    private final Service service;
    private final String identifier;
    private String theme = "light";
    private String lang = "en"; 
    private String textSize = "medium";
    private String accent = "green";
    private String inPath;
    private final String secret58;
    private final String prefix;
    private final boolean includeResourceIdInPrefix;
    private final boolean async;
    private final String qdnContext;
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final ServletContext context;

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
            
            // If the file doesn't exist, we may need to route the request elsewhere, or cleanup
            if (!Files.exists(filePath)) {
                if (inPath.equals("/")) {
                    // Delete the unzipped folder if no index file was found
                    try {
                        FileUtils.deleteDirectory(new File(unzippedPath));
                    } catch (IOException e) {
                        LOGGER.debug("Unable to delete directory: {}", unzippedPath, e);
                    }
                }

                // If this is an app, then forward all unhandled requests to the index, to give the app the option to route it
                if (this.service == Service.APP) {
                    // Locate index file
                    List<String> indexFiles = ArbitraryDataRenderer.indexFiles();
                    for (String indexFile : indexFiles) {
                        Path indexPath = Paths.get(unzippedPath, indexFile);
                        if (Files.exists(indexPath)) {
                            // Forward request to index file
                            filePath = indexPath;
                            filename = indexFile;
                            usingCustomRouting = true;
                            break;
                        }
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
                    "connect-src 'self' wss: blob:;"
                );
                response.setContentType(context.getMimeType(filename));
                response.setContentLength(htmlParser.getData().length);
                response.getOutputStream().write(htmlParser.getData());
            }
            else {
                // Regular file - can be streamed directly
                ArbitraryDataRenderer.streamNonHtmlFileResponse(response, context, filePath, filename);
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

    static void streamNonHtmlFileResponse(HttpServletResponse response, ServletContext context, Path filePath, String filename) throws IOException {
        response.addHeader("Content-Security-Policy", "default-src 'self'");
        response.setContentType(context.getMimeType(filename));
        setResponseContentLength(response, Files.size(filePath));

        try (InputStream inputStream = Files.newInputStream(filePath)) {
            OutputStream outputStream = response.getOutputStream();
            int bytesRead;
            byte[] buffer = new byte[10240];
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
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
            responseString = responseString.replace("%%THEME%%", escapeJavaScriptStringContents(theme));
            responseString = responseString.replace("%%ACCENT%%", escapeJavaScriptStringContents(accent));

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
