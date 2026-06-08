package org.qortium.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.qortium.arbitrary.misc.Service;

public class HTMLParser {

    private static final Logger LOGGER = LogManager.getLogger(HTMLParser.class);

    private String qdnBase;
    private String qdnBaseWithPath;
    private byte[] data;
    private String qdnContext;
    private String resourceId;
    private Service service;
    private String identifier;
    private String path;
    private String theme;
    private String lang;
    private String textSize;
    private boolean usingCustomRouting;

    public HTMLParser(String resourceId, String inPath, String prefix, boolean includeResourceIdInPrefix, byte[] data,
                      String qdnContext, Service service, String identifier, String theme, boolean usingCustomRouting, String lang, String textSize) {
        String inPathWithoutFilename = inPath.contains("/") ? inPath.substring(0, inPath.lastIndexOf('/')) : String.format("/%s",inPath);
        this.qdnBase = includeResourceIdInPrefix ? String.format("%s/%s", prefix, resourceId) : prefix;
        this.qdnBaseWithPath = includeResourceIdInPrefix ? String.format("%s/%s%s", prefix, resourceId, inPathWithoutFilename) : String.format("%s%s", prefix, inPathWithoutFilename);
        this.data = data;
        this.qdnContext = qdnContext;
        this.resourceId = resourceId;
        this.service = service;
        this.identifier = identifier;
        this.path = inPath;
        this.theme = theme;
        this.lang = lang;
        this.textSize = textSize;
        this.usingCustomRouting = usingCustomRouting;
    }

    public void addAdditionalHeaderTags() {
        String fileContents = new String(data);
        Document document = Jsoup.parse(fileContents);
        Elements head = document.getElementsByTag("head");
        if (!head.isEmpty()) {
            Element headElement = head.get(0);

            // Add q-apps script tag
            Element qAppsScriptElement = new Element("script")
                    .attr("src", String.format("/apps/q-apps.js?time=%d", System.currentTimeMillis()));
            headElement.prependChild(qAppsScriptElement);

            // Add q-apps gateway script tag if in gateway mode
            if (Objects.equals(this.qdnContext, "gateway")) {
                Element qAppsGatewayScriptElement = new Element("script")
                        .attr("src", String.format("/apps/q-apps-gateway.js?time=%d", System.currentTimeMillis()));
                headElement.prependChild(qAppsGatewayScriptElement);
            }

            // Escape and add vars
            String qdnContextVar = String.format(
                "var _qdnContext=%s; var _qdnTheme=%s; var _qdnLang=%s; var _qdnTextSize=%s; var _qdnService=%s; var _qdnName=%s; var _qdnIdentifier=%s; var _qdnPath=%s; var _qdnBase=%s; var _qdnBaseWithPath=%s;",
                javaScriptStringLiteral(this.qdnContext),
                javaScriptStringLiteral(this.theme),
                javaScriptStringLiteral(this.lang),
                javaScriptStringLiteral(this.textSize),
                javaScriptStringLiteral(this.service.toString()),
                javaScriptStringLiteral(this.resourceId),
                javaScriptStringLiteral(this.identifier),
                javaScriptStringLiteral(this.path),
                javaScriptStringLiteral(this.qdnBase),
                javaScriptStringLiteral(this.qdnBaseWithPath)
              );
            Element qdnContextElement = new Element("script");
            qdnContextElement.appendChild(new DataNode(qdnContextVar));
            headElement.prependChild(qdnContextElement);

            // Add base href tag
            // Exclude the path if this request was routed back to the index automatically
            String baseHref = this.usingCustomRouting ? this.qdnBase : this.qdnBaseWithPath;
            Element baseElement = new Element("base").attr("href", baseHref + "/");
            headElement.prependChild(baseElement);

            // Add meta charset tag
            Element metaCharsetElement = new Element("meta").attr("charset", "UTF-8");
            headElement.prependChild(metaCharsetElement);

        }
        
        // For render context with non-default identifier, modify all relative script and link tags
        // to include the identifier query parameter (base tag doesn't reliably preserve query params)
        if (Objects.equals(this.qdnContext, "render") && this.identifier != null && !this.identifier.isBlank() && !this.identifier.equals("default")) {
            // Modify script tags
            Elements scripts = document.select("script[src]");
            scripts.forEach(script -> {
                String src = script.attr("src");
                // Only modify relative URLs (not absolute URLs starting with / or http)
                if (!src.startsWith("/") && !src.startsWith("http")) {
                    script.attr("src", appendQueryParameter(src, "identifier", this.identifier));
                }
            });
            
            // Modify link tags (CSS, etc.)
            Elements links = document.select("link[href]");
            links.forEach(link -> {
                String href = link.attr("href");
                // Only modify relative URLs
                if (!href.startsWith("/") && !href.startsWith("http")) {
                    link.attr("href", appendQueryParameter(href, "identifier", this.identifier));
                }
            });
        }
        
        String html = document.html();
        this.data = html.getBytes();
    }

    public static boolean isHtmlFile(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm") || path.isEmpty()) {
            return true;
        }
        return false;
    }

    public byte[] getData() {
        return this.data;
    }

    private static String javaScriptStringLiteral(String value) {
        if (value == null) {
            value = "";
        }

        StringBuilder output = new StringBuilder(value.length() + 2);
        output.append('"');
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
        output.append('"');
        return output.toString();
    }

    private static String appendQueryParameter(String url, String name, String value) {
        int fragmentIndex = url.indexOf('#');
        String baseUrl = fragmentIndex >= 0 ? url.substring(0, fragmentIndex) : url;
        String fragment = fragmentIndex >= 0 ? url.substring(fragmentIndex) : "";
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + urlEncode(name) + "=" + urlEncode(value) + fragment;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
