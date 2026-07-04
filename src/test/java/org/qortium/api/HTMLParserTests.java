package org.qortium.api;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.Test;
import org.qortium.arbitrary.misc.Service;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HTMLParserTests {

	@Test
	public void testAdditionalHeaderTagsEscapeQdnMetadata() {
		String maliciousValue = "name\"</script><script>alert(1)</script>";
		HTMLParser htmlParser = new HTMLParser(
				maliciousValue,
				"/index.html",
				"/render/APP",
				true,
				"<html><head><title>Example</title></head><body></body></html>".getBytes(StandardCharsets.UTF_8),
				"render",
				Service.APP,
				"identifier\"</script>",
				"dark\"</script>",
				false,
				"en\"</script>",
				"extra-large\"</script>",
				"green\"</script>");

		htmlParser.addAdditionalHeaderTags();

		String renderedHtml = new String(htmlParser.getData(), StandardCharsets.UTF_8);
		Document document = Jsoup.parse(renderedHtml);
		assertEquals(2, document.select("head script").size());
		assertEquals(1, document.select("head script:not([src])").size());

		Element metadataScript = document.selectFirst("head script:not([src])");
		assertNotNull(metadataScript);
		String scriptData = metadataScript.data();
		assertTrue(scriptData.contains("var _qdnTextSize=\"extra-large\\\"\\u003c/script\\u003e\";"));
		assertTrue(scriptData.contains("var _qdnAccent=\"green\\\"\\u003c/script\\u003e\";"));
		assertTrue(scriptData.contains("\\u003c/script\\u003e\\u003cscript\\u003ealert(1)\\u003c/script\\u003e"));
		assertFalse(scriptData.contains("</script><script>alert(1)</script>"));

		Element base = document.selectFirst("head base[href]");
		assertNotNull(base);
		assertTrue(renderedHtml.contains("&lt;/script&gt;&lt;script&gt;alert(1)&lt;/script&gt;"));
	}

	@Test
	public void testRenderIdentifierIsRoutedThroughBaseHref() {
		HTMLParser htmlParser = new HTMLParser(
				"Example",
				"/index.html",
				"/render/APP",
				true,
				("<html><head>" +
						"<script src=\"app.js#frag\"></script>" +
						"<script src=\"/apps/platform.js\"></script>" +
						"<link rel=\"stylesheet\" href=\"style.css?x=1#frag\">" +
						"</head><body></body></html>").getBytes(StandardCharsets.UTF_8),
				"render",
				Service.APP,
				"id value<&",
				null,
				false,
				null,
				null,
				null);

		htmlParser.addAdditionalHeaderTags();

		Document document = Jsoup.parse(new String(htmlParser.getData(), StandardCharsets.UTF_8));
		Element base = document.selectFirst("head base[href]");
		Element relativeScript = document.selectFirst("script[src^=app.js]");
		Element absoluteScript = document.selectFirst("script[src=/apps/platform.js]");
		Element stylesheet = document.selectFirst("link[href^=style.css]");

		assertNotNull(base);
		assertNotNull(relativeScript);
		assertNotNull(absoluteScript);
		assertNotNull(stylesheet);
		assertEquals("/render/APP/Example/id%20value<&/", base.attr("href"));
		assertEquals("app.js#frag", relativeScript.attr("src"));
		assertEquals("/apps/platform.js", absoluteScript.attr("src"));
		assertEquals("style.css?x=1#frag", stylesheet.attr("href"));
	}

}
