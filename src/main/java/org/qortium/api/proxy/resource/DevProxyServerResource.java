package org.qortium.api.proxy.resource;

import org.qortium.api.ApiError;
import org.qortium.api.ApiExceptionFactory;
import org.qortium.api.HTMLParser;
import org.qortium.arbitrary.misc.Service;
import org.qortium.controller.DevProxyManager;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;


@Path("/")
public class DevProxyServerResource {

    private static final int PROXY_CONNECT_TIMEOUT_MS = 5000;
    private static final int PROXY_READ_TIMEOUT_MS = 30000;
    private static final int PROXY_STREAM_BUFFER_SIZE = 4096;

    private static final Set<String> PROXY_MANAGED_REQUEST_HEADERS = Set.of(
            "accept-encoding",
            "connection",
            "content-length",
            "host",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade"
    );

    private static final Set<String> PROXY_MANAGED_RESPONSE_HEADERS = Set.of(
            "connection",
            "content-encoding",
            "content-length",
            "content-security-policy",
            "content-type",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade"
    );

    @Context HttpServletRequest request;
    @Context HttpServletResponse response;
    @Context ServletContext context;


    @GET
    public HttpServletResponse getProxyIndex() {
        return this.proxy("/");
    }

    @GET
    @Path("{path:.*}")
    public HttpServletResponse getProxyPath(@PathParam("path") String inPath) {
        return this.proxy(inPath);
    }

    private HttpServletResponse proxy(String inPath) {
        try {
            String source = DevProxyManager.getInstance().getSourceHostAndPort();

            if (!inPath.startsWith("/")) {
                inPath = "/" + inPath;
            }

            String queryString = request.getQueryString();

            // Open URL
            URL url = buildProxyUrl(source, inPath, queryString);
            HttpURLConnection con = this.openProxyConnection(url);

            // Proxy the request data
            this.proxyRequestToConnection(request, con);

            int responseCode;
            try {
                // Make the request and proxy the response code
                responseCode = con.getResponseCode();
            }
            catch (ConnectException e) {

                // Try converting localhost / 127.0.0.1 to IPv6 [::1]
                String fallbackSource = loopbackIpv6FallbackSource(source);
                if (fallbackSource.equals(source)) {
                    throw e;
                }
                source = fallbackSource;

                // Retry connection
                url = buildProxyUrl(source, inPath, queryString);
                con = this.openProxyConnection(url);
                this.proxyRequestToConnection(request, con);
                responseCode = con.getResponseCode();
            }

            response.setStatus(responseCode);

            // Proxy the response data back to the caller
            this.proxyConnectionToResponse(con, response, inPath, responseCode, source);

        } catch (IOException e) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA, e.getMessage());
        }

        return response;
    }

    private static URL buildProxyUrl(String source, String inPath, String queryString) throws IOException {
        URI sourceUri = parseLoopbackProxySource(source);
        String safePath = inPath.startsWith("/") ? inPath : "/" + inPath;

        try {
            return new URI("http", null, sourceUri.getHost(), effectiveHttpPort(sourceUri), safePath, queryString, null).toURL();
        } catch (IllegalArgumentException | URISyntaxException e) {
            throw new IOException("Invalid developer proxy URL", e);
        }
    }

    private static URI parseLoopbackProxySource(String source) throws IOException {
        try {
            URI sourceUri = URI.create("http://" + source);
            if (sourceUri.getHost() == null ||
                    sourceUri.getUserInfo() != null ||
                    (sourceUri.getRawPath() != null && !sourceUri.getRawPath().isEmpty()) ||
                    sourceUri.getRawQuery() != null ||
                    sourceUri.getRawFragment() != null ||
                    !"loopback".equals(normalizeLoopbackRedirectHost(sourceUri.getHost()))) {
                throw new IOException("Developer proxy source must be a loopback HTTP host and port");
            }

            return sourceUri;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid developer proxy source", e);
        }
    }

    private static String loopbackIpv6FallbackSource(String source) throws IOException {
        URI sourceUri = parseLoopbackProxySource(source);
        String host = sourceUri.getHost();
        if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) {
            return String.format("[::1]:%d", effectiveHttpPort(sourceUri));
        }

        return source;
    }

    private HttpURLConnection openProxyConnection(URL url) throws IOException {
        if (!isAllowedLoopbackProxyUrl(url)) {
            throw new IOException("Developer proxy target must be a loopback HTTP URL");
        }

        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(PROXY_CONNECT_TIMEOUT_MS);
        con.setReadTimeout(PROXY_READ_TIMEOUT_MS);
        con.setInstanceFollowRedirects(false);
        return con;
    }

    private void proxyRequestToConnection(HttpServletRequest request, HttpURLConnection con) throws ProtocolException {
        // Proxy the request method
        con.setRequestMethod(request.getMethod());

        // Proxy the request headers
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            String headerValue = request.getHeader(headerName);
            if (headerName != null && headerValue != null && !isProxyManagedRequestHeader(headerName)) {
                con.setRequestProperty(headerName, headerValue);
            }
        }

        con.setRequestProperty("Accept-Encoding", "identity");

        // This proxy currently exposes GET handlers only. If non-GET handlers are added,
        // request bodies and related content headers must be forwarded deliberately.
    }

    private static boolean isProxyManagedRequestHeader(String headerName) {
        return PROXY_MANAGED_REQUEST_HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
    }

    private static boolean isProxyManagedResponseHeader(String headerName) {
        return PROXY_MANAGED_RESPONSE_HEADERS.contains(headerName.toLowerCase(Locale.ROOT));
    }

    private void proxyConnectionToResponse(HttpURLConnection con, HttpServletResponse response, String inPath, int responseCode, String source) throws IOException {
        // Proxy the response headers
        for (Map.Entry<String, List<String>> header : con.getHeaderFields().entrySet()) {
            String headerName = header.getKey();
            List<String> headerValues = header.getValue();
            if (headerName == null || headerValues == null || isProxyManagedResponseHeader(headerName)) {
                continue;
            }

            for (String headerValue : headerValues) {
                if (headerValue != null) {
                    if ("location".equals(headerName.toLowerCase(Locale.ROOT))) {
                        headerValue = rewriteProxyLocation(headerValue, source);
                    }

                    response.addHeader(headerName, headerValue);
                }
            }
        }

        // Extract filename
        String filename = "";
        if (inPath.contains("/")) {
            String[] parts = inPath.split("/");
            if (parts.length > 0) {
                filename = parts[parts.length - 1];
            }
        }

        if (isProxyHtmlResponse(filename, con.getContentType())) {
            this.proxyHtmlConnectionToResponse(con, response, inPath, responseCode);
        }
        else {
            this.proxyNonHtmlConnectionToResponse(con, response, responseCode);
        }
    }

    private void proxyHtmlConnectionToResponse(HttpURLConnection con, HttpServletResponse response, String inPath, int responseCode) throws IOException {
        byte[] data = readProxyResponseData(con, responseCode);

        String lang = request.getParameter("lang");
        if (lang == null || lang.isBlank()) {
            lang = "en"; // fallback
        }

        String theme = request.getParameter("theme");
        if (theme == null || theme.isBlank()) {
            theme = "light";
        }

        String textSize = request.getParameter("textSize");
        if (textSize == null || textSize.isBlank()) {
            textSize = "medium";
        }

        HTMLParser htmlParser = new HTMLParser("", inPath, "", false, data, "proxy", Service.APP, null, theme, true, lang, textSize);
        htmlParser.addAdditionalHeaderTags();
        response.addHeader("Content-Security-Policy", "default-src 'self' 'unsafe-inline' 'unsafe-eval'; media-src 'self' data: blob:; img-src 'self' data: blob:; connect-src 'self' ws:; font-src 'self' data:;");
        response.setContentType(con.getContentType());
        response.setContentLength(htmlParser.getData().length);
        response.getOutputStream().write(htmlParser.getData());
    }

    private void proxyNonHtmlConnectionToResponse(HttpURLConnection con, HttpServletResponse response, int responseCode) throws IOException {
        response.addHeader("Content-Security-Policy", "default-src 'self'");
        response.setContentType(con.getContentType());
        if (con.getContentEncoding() != null) {
            response.addHeader("Content-Encoding", con.getContentEncoding());
        }

        int contentLength = con.getContentLength();
        if (contentLength >= 0) {
            response.setContentLength(contentLength);
        }

        streamProxyResponseData(con, response, responseCode);
    }

    private static boolean isProxyHtmlResponse(String filename, String contentType) {
        if (HTMLParser.isHtmlFile(filename)) {
            return true;
        }

        return contentType != null && contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim().equals("text/html");
    }

    private static InputStream getProxyResponseStream(HttpURLConnection con, int responseCode) throws IOException {
        return responseCode >= HttpURLConnection.HTTP_BAD_REQUEST
                ? con.getErrorStream()
                : con.getInputStream();
    }

    private static byte[] readProxyResponseData(HttpURLConnection con, int responseCode) throws IOException {
        InputStream responseStream = getProxyResponseStream(con, responseCode);
        if (responseStream == null) {
            return new byte[0];
        }

        try (InputStream inputStream = getDecodedHtmlResponseStream(responseStream, con.getContentEncoding());
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[PROXY_STREAM_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            return outputStream.toByteArray();
        }
    }

    private static InputStream getDecodedHtmlResponseStream(InputStream inputStream, String contentEncoding) throws IOException {
        if (contentEncoding == null || contentEncoding.isBlank() || "identity".equalsIgnoreCase(contentEncoding.trim())) {
            return inputStream;
        }

        String normalizedEncoding = contentEncoding.toLowerCase(Locale.ROOT).trim();
        try {
            if ("gzip".equals(normalizedEncoding) || "x-gzip".equals(normalizedEncoding)) {
                return new GZIPInputStream(inputStream);
            }
            if ("deflate".equals(normalizedEncoding)) {
                return new InflaterInputStream(inputStream);
            }
        } catch (IOException e) {
            inputStream.close();
            throw e;
        }

        inputStream.close();
        throw new IOException("Unsupported HTML content encoding: " + contentEncoding);
    }

    private static void streamProxyResponseData(HttpURLConnection con, HttpServletResponse response, int responseCode) throws IOException {
        InputStream responseStream = getProxyResponseStream(con, responseCode);
        if (responseStream == null) {
            return;
        }

        try (InputStream inputStream = responseStream) {
            OutputStream outputStream = response.getOutputStream();
            byte[] buffer = new byte[PROXY_STREAM_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }
    }

    private static String rewriteProxyLocation(String location, String source) {
        URI locationUri;
        URI sourceUri;
        try {
            locationUri = URI.create(location);
            sourceUri = URI.create("http://" + source);
        } catch (IllegalArgumentException e) {
            return location;
        }

        String locationScheme = locationUri.getScheme();
        if (locationScheme != null && !"http".equalsIgnoreCase(locationScheme)) {
            return location;
        }

        if (locationUri.getHost() == null) {
            return location;
        }

        String locationHost = normalizeLoopbackRedirectHost(locationUri.getHost());
        String sourceHost = normalizeLoopbackRedirectHost(sourceUri.getHost());
        if (!"loopback".equals(locationHost) || !locationHost.equals(sourceHost) ||
                effectiveHttpPort(locationUri) != effectiveHttpPort(sourceUri)) {
            return location;
        }

        StringBuilder rewrittenLocation = new StringBuilder();
        String rawPath = locationUri.getRawPath();
        rewrittenLocation.append(rawPath == null || rawPath.isEmpty() ? "/" : rawPath);

        if (locationUri.getRawQuery() != null) {
            rewrittenLocation.append("?").append(locationUri.getRawQuery());
        }

        if (locationUri.getRawFragment() != null) {
            rewrittenLocation.append("#").append(locationUri.getRawFragment());
        }

        return rewrittenLocation.toString();
    }

    private static String normalizeLoopbackRedirectHost(String host) {
        if (host == null) {
            return null;
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
            normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
        }

        if ("localhost".equals(normalizedHost) || "127.0.0.1".equals(normalizedHost) || "::1".equals(normalizedHost)) {
            return "loopback";
        }

        return normalizedHost;
    }

    private static boolean isAllowedLoopbackProxyUrl(URL url) {
        if (url == null || !"http".equalsIgnoreCase(url.getProtocol())) {
            return false;
        }

        return "loopback".equals(normalizeLoopbackRedirectHost(url.getHost()));
    }

    private static int effectiveHttpPort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : 80;
    }

}
