package org.qortium.api;

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
    private String accent;
    private String uiStyle;
    private boolean usingCustomRouting;

    public HTMLParser(String resourceId, String inPath, String prefix, boolean includeResourceIdInPrefix, byte[] data,
                      String qdnContext, Service service, String identifier, String theme, boolean usingCustomRouting, String lang, String textSize, String accent, String uiStyle) {
        String inPathWithoutFilename = inPath.contains("/") ? inPath.substring(0, inPath.lastIndexOf('/')) : String.format("/%s",inPath);

        // For the render context with a non-default identifier, fold the identifier into the base href as a
        // path segment after the resourceId, so relative links/assets inherit it (matching the path-segment
        // identifier route). The gateway already folds the identifier into the prefix, and domainMap does not
        // use a path-segment identifier, so only render appends it here. Encode spaces the same way the caller
        // encodes resourceId for the URL (space -> %20).
        String resourceIdInBase = resourceId;
        if (Objects.equals(qdnContext, "render") && includeResourceIdInPrefix
                && identifier != null && !identifier.isBlank() && !identifier.equals("default")) {
            resourceIdInBase = String.format("%s/%s", resourceId, identifier.replace(" ", "%20"));
        }

        this.qdnBase = includeResourceIdInPrefix ? String.format("%s/%s", prefix, resourceIdInBase) : prefix;
        this.qdnBaseWithPath = includeResourceIdInPrefix ? String.format("%s/%s%s", prefix, resourceIdInBase, inPathWithoutFilename) : String.format("%s%s", prefix, inPathWithoutFilename);
        this.data = data;
        this.qdnContext = qdnContext;
        this.resourceId = resourceId;
        this.service = service;
        this.identifier = identifier;
        this.path = inPath;
        this.theme = theme;
        this.lang = lang;
        this.textSize = textSize;
        this.accent = accent;
        this.uiStyle = uiStyle;
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
                "var _qdnContext=%s; var _qdnTheme=%s; var _qdnLang=%s; var _qdnTextSize=%s; var _qdnAccent=%s; var _qdnUiStyle=%s; var _qdnService=%s; var _qdnName=%s; var _qdnIdentifier=%s; var _qdnPath=%s; var _qdnBase=%s; var _qdnBaseWithPath=%s;",
                javaScriptStringLiteral(this.qdnContext),
                javaScriptStringLiteral(this.theme),
                javaScriptStringLiteral(this.lang),
                javaScriptStringLiteral(this.textSize),
                javaScriptStringLiteral(this.accent),
                javaScriptStringLiteral(this.uiStyle),
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
}
