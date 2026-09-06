package org.qortium.test;

import org.junit.After;
import org.junit.Test;
import org.qortium.settings.Settings;
import org.qortium.settings.Settings.DomainMap;
import org.qortium.test.common.Common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Domain-map entries may now name a service and identifier, so a vanity host can front an APP (or any
 * renderable service), not only a WEBSITE. Entries are validated at settings load, since a typo would
 * otherwise only show up as a 404 on the live host.
 */
public class DomainMapSettingsTests extends Common {

	@After
	public void restoreDefaultSettings() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testServiceDefaultsToWebsiteAndIdentifierToNull() throws Exception {
		useSettingsJson("{\"domainMap\":[{\"domain\":\"example.com\",\"name\":\"Alice\"}]}");

		Map<String, DomainMap> entries = Settings.getInstance().getDomainMapEntries();
		DomainMap entry = entries.get("example.com");
		assertNotNull(entry);
		assertEquals("Alice", entry.getName());
		assertEquals("WEBSITE", entry.getService());
		assertNull(entry.getIdentifier());

		// An apex domain keeps its www. alias
		assertTrue(entries.containsKey("www.example.com"));
		assertEquals(entry, entries.get("www.example.com"));
	}

	@Test
	public void testAppEntryWithIdentifierAndCaseInsensitiveHost() throws Exception {
		useSettingsJson("{\"domainMap\":[{\"domain\":\"7R15.Qortium.com\",\"name\":\"7R15M3G157U5\",\"service\":\"app\",\"identifier\":\"Donation\"}]}");

		Map<String, DomainMap> entries = Settings.getInstance().getDomainMapEntries();
		DomainMap entry = entries.get("7r15.qortium.com");
		assertNotNull(entry);
		assertEquals("7R15M3G157U5", entry.getName());
		assertEquals("APP", entry.getService());
		assertEquals("Donation", entry.getIdentifier());

		// A subdomain gets no www. alias
		assertFalse(entries.containsKey("www.7r15.qortium.com"));
	}

	@Test
	public void testUnknownServiceIsRejectedAtLoad() throws Exception {
		assertRejected("{\"domainMap\":[{\"domain\":\"example.com\",\"name\":\"Alice\",\"service\":\"WEBSITES\"}]}",
				"unknown service WEBSITES");
	}

	@Test
	public void testBlankNameIsRejectedAtLoad() throws Exception {
		assertRejected("{\"domainMap\":[{\"domain\":\"example.com\",\"name\":\" \"}]}",
				"must have a name");
	}

	@Test
	public void testGatewayUrlIsNormalisedToAnOrigin() throws Exception {
		useSettingsJson("{\"domainMapGatewayUrl\":\"https://qdn.example/\"}");
		assertEquals("https://qdn.example", Settings.getInstance().getDomainMapGatewayUrl());

		useSettingsJson("{}");
		assertNull(Settings.getInstance().getDomainMapGatewayUrl());
	}

	@Test
	public void testGatewayUrlMustBeAnHttpOriginWithoutPath() throws Exception {
		assertRejected("{\"domainMapGatewayUrl\":\"https://qdn.example/APP\"}", "domainMapGatewayUrl");
		assertRejected("{\"domainMapGatewayUrl\":\"qdn.example\"}", "domainMapGatewayUrl");
		assertRejected("{\"domainMapGatewayUrl\":\"ftp://qdn.example\"}", "domainMapGatewayUrl");
		assertRejected("{\"domainMapGatewayUrl\":\"https://qdn.example?x=1\"}", "domainMapGatewayUrl");
	}

	private static void assertRejected(String json, String expectedMessageFragment) throws Exception {
		try {
			useSettingsJson(json);
			fail("Settings should have been rejected: " + json);
		} catch (RuntimeException e) {
			String message = String.valueOf(e.getMessage()) + " / " + (e.getCause() != null ? e.getCause().getMessage() : "");
			assertTrue(message, message.contains(expectedMessageFragment));
		}
	}

	private static void useSettingsJson(String json) throws Exception {
		Path directory = Files.createTempDirectory("domain-map-settings-test");
		Path settingsPath = directory.resolve("settings.json");
		Files.write(settingsPath, (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
		Settings.fileInstance(settingsPath.toString());
	}
}
