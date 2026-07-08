package org.qortium.api;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PublicApiAccessHandlerTests extends Common {

	private Settings settings;

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		this.settings = Settings.getInstance();
	}

	@Test
	public void testDefaultOnlyAllowsApiWhitelist() {
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"127.0.0.1", "GET", "/admin/settings", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/admin/status", this.settings));
	}

	@Test
	public void testPublicReadOnlyPathsAreAllowedWhenConfigured() throws Exception {
		enablePublicApi();

		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/admin/status", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/peers/known", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/peers/data/known", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"2001:db8::1", "GET", "/admin/status", this.settings));
	}

	@Test
	public void testPublicWildcardPathsAllowBaseAndNestedReadEndpoints() throws Exception {
		enablePublicApi();

		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/arbitrary", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/arbitrary/resources/search", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/arbitrary/WEBSITE/QortiumHome", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/arbitrary/WEBSITE/QortiumHome/default", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/render/APP/QortiumHomeTest", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/render/WEBSITE/QortiumHomeTest/index.html", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/names", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/names/search", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/transactions/search", this.settings));
	}

	@Test
	public void testPublicRequestsCannotUseOtherReadEndpoints() throws Exception {
		enablePublicApi();

		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/admin/settings", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/admin/update", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/admin/update", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/peers", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/peers/known/diagnostics", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/peers/data/known/diagnostics", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/admin/info", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/lists/followedQdn", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/utils/random", this.settings));
	}

	@Test
	public void testPublicRequestsCannotUseNonGetMethods() throws Exception {
		enablePublicApi();

		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/admin/status", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "DELETE", "/peers/known", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "DELETE", "/peers/data/known", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/arbitrary/WEBSITE/QortiumHome", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/render/authorize/APP/QortiumHomeTest/home-test", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "DELETE", "/arbitrary/resource/WEBSITE/QortiumHome/default", this.settings));
	}

	@Test
	public void testPublicQdnBuildNamespaceIsAllowedWithoutGenericArbitraryWrites() throws Exception {
		enablePublicApi();

		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/arbitrary/public/APP/QortiumHome/base64", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/arbitrary/public/APP/QortiumHome/qortium-chat/zip", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/arbitrary/public/resource/APP/QortiumHome/qortium-chat/delete", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/arbitrary/APP/QortiumHome/base64", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/arbitrary/compute", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/transactions/sign", this.settings));
	}

	@Test
	public void testPreviewSettingsExposePublicReadsAndKeylessBuildsOnly() throws Exception {
		assertPreviewSettingsExposePublicReadsAndKeylessBuildsOnly(Path.of("preview/settings-preview.json"));
		assertPreviewSettingsExposePublicReadsAndKeylessBuildsOnly(Path.of("preview/settings-preview-seed.json"));
		assertPreviewSettingsExposePublicReadsAndKeylessBuildsOnly(Path.of("preview/settings-preview-seed-netcup.json"));
	}

	@Test
	public void testNormalPreviewSettingsIncludeChainAndDataBootstrapPeers() throws Exception {
		JSONObject settingsJson = new JSONObject(Files.readString(Path.of("preview/settings-preview.json")));
		JSONArray initialPeers = settingsJson.getJSONArray("initialPeers");
		JSONArray initialDataPeers = settingsJson.getJSONArray("initialDataPeers");

		assertTrue(jsonArrayContains(initialPeers, "146.103.42.59:24892"));
		assertTrue(jsonArrayContains(initialPeers, "185.207.104.78:24892"));
		assertTrue(jsonArrayContains(initialPeers, "3u25ana5e5hvriqqiuh6fcetxezsqm7la276ljtjxaoxt767n4hq.b32.i2p"));
		assertTrue(jsonArrayContains(initialPeers, "zqcackxkhjzfbbc6daigc73zqhzdpgwua3mjc7xgn3hwjed5z3ca.b32.i2p"));
		assertTrue(jsonArrayContains(initialPeers, "q25q6gbn2x67x5sos5fgcr5td2xzazzkibovavthha6dpjg3cc6a.b32.i2p"));
		assertTrue(jsonArrayContains(initialDataPeers, "146.103.42.59:24894"));
		assertTrue(jsonArrayContains(initialDataPeers, "185.207.104.78:24894"));
		assertTrue(jsonArrayContains(initialDataPeers, "hg3seiuul4pcz6a2svatdahzudphbm464vwqcmiejc77kumglwaq.b32.i2p"));
		assertTrue(jsonArrayContains(initialDataPeers, "qhk6g5hl7vqf5fmlgj6knbajtiszotaf2w26fwjapsr75kbz7fma.b32.i2p"));
		assertTrue(jsonArrayContains(initialDataPeers, "ksbtmej35uvkhhedslrk6zekvjijxaegwnueavcm4ws5zzsadhrq.b32.i2p"));
	}

	@Test
	public void testValidApiKeyAllowsRemoteRequestsToAnyEndpoint() {
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/render/authorize/APP/QortiumHomeTest/home-test",
				"node-api-key", "node-api-key", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/admin/settings",
				"node-api-key", "node-api-key", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"2001:db8::1", "POST", "/arbitrary/APP/QortiumHome/base64",
				"node-api-key", "node-api-key", this.settings));
	}

	@Test
	public void testMissingOrWrongApiKeyDoesNotAllowRemoteRequests() {
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/render/authorize/APP/QortiumHomeTest/home-test",
				null, "node-api-key", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/render/authorize/APP/QortiumHomeTest/home-test",
				"", "node-api-key", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/render/authorize/APP/QortiumHomeTest/home-test",
				"wrong-api-key", "node-api-key", this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/render/authorize/APP/QortiumHomeTest/home-test",
				"node-api-key", "node-api-key-longer", this.settings));
	}

	@Test
	public void testApiKeyIsNotMatchedWhenNodeKeyIsAbsent() {
		// A node without a generated key must never treat a passed key as valid,
		// including the degenerate blank-equals-blank case.
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/admin/settings",
				"anything", null, this.settings));
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/admin/settings",
				"", "", this.settings));
	}

	@Test
	public void testApiKeyRemoteAccessCanBeDisabled() throws Exception {
		FieldUtils.writeField(this.settings, "apiKeyRemoteAccessEnabled", false, true);

		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/render/authorize/APP/QortiumHomeTest/home-test",
				"node-api-key", "node-api-key", this.settings));
		// Loopback requests are still allowed via the regular API whitelist.
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"127.0.0.1", "GET", "/admin/settings",
				"node-api-key", "node-api-key", this.settings));
	}

	@Test
	public void testPublicPathsStillApplyAlongsideApiKeyRemoteAccess() throws Exception {
		enablePublicApi();

		// Public read paths stay open without any key ...
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/render/APP/QortiumHomeTest",
				null, "node-api-key", this.settings));
		// ... a wrong key doesn't take them away ...
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/render/APP/QortiumHomeTest",
				"wrong-api-key", "node-api-key", this.settings));
		// ... and a wrong key still can't reach restricted paths.
		assertFalse(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "POST", "/render/authorize/APP/QortiumHomeTest/home-test",
				"wrong-api-key", "node-api-key", this.settings));
	}

	@Test
	public void testFullApiWhitelistTakesPrecedenceOverPublicPaths() throws Exception {
		enablePublicApi();

		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"127.0.0.1", "DELETE", "/peers/known", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"127.0.0.1", "DELETE", "/peers/data/known", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"127.0.0.1", "GET", "/admin/settings", this.settings));
	}

	@Test
	public void testDisabledFullApiWhitelistAllowsAllRequests() throws Exception {
		FieldUtils.writeField(this.settings, "apiWhitelistEnabled", false, true);

		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"203.0.113.10", "GET", "/admin/settings", this.settings));
		assertTrue(PublicApiAccessHandler.isRequestAllowed(
				"2001:db8::1", "POST", "/peers", this.settings));
	}

	private void enablePublicApi() throws Exception {
		FieldUtils.writeField(this.settings, "publicApiWhitelistEnabled", true, true);
		FieldUtils.writeField(this.settings, "publicApiWhitelist", new String[] {"0.0.0.0/0", "::/0"}, true);
		FieldUtils.writeField(this.settings, "publicApiPaths", new String[] {
				"GET /admin/status",
				"GET /peers/known",
				"GET /peers/data/known",
				"GET /arbitrary/*",
				"GET /render/*",
				"GET /names/*",
				"GET /transactions/*",
				"POST /arbitrary/public/*"
		}, true);
	}

	private static void assertPreviewSettingsExposePublicReadsAndKeylessBuildsOnly(Path settingsPath) throws Exception {
		JSONObject settingsJson = new JSONObject(Files.readString(settingsPath));
		JSONArray publicApiPaths = settingsJson.getJSONArray("publicApiPaths");

		assertTrue(settingsPath + " should enable preview QDN auth bypass",
				settingsJson.getBoolean("qdnAuthBypassEnabled"));
		assertTrue(settingsPath + " should set the public QDN publish size guard",
				settingsJson.getLong("publicQdnPublishMaxSize") == 104857600L);
		assertTrue(settingsPath + " should allow public render reads",
				jsonArrayContains(publicApiPaths, "GET /render/*"));
		assertTrue(settingsPath + " should allow public known data peer reads",
				jsonArrayContains(publicApiPaths, "GET /peers/data/known"));
		assertTrue(settingsPath + " should allow keyless public chat builds",
				jsonArrayContains(publicApiPaths, "POST /chat/public/build"));
		assertTrue(settingsPath + " should allow keyless public QDN publish/delete builds",
				jsonArrayContains(publicApiPaths, "POST /arbitrary/public/*"));
		assertTrue(settingsPath + " should allow unsigned transaction conversion",
				jsonArrayContains(publicApiPaths, "POST /transactions/convert"));
		assertTrue(settingsPath + " should allow signed transaction submission",
				jsonArrayContains(publicApiPaths, "POST /transactions/process"));
		assertFalse(settingsPath + " should not expose render authorization writes",
				jsonArrayContains(publicApiPaths, "POST /render/authorize/*"));
		assertFalse(settingsPath + " should not expose generic QDN publish writes",
				jsonArrayContains(publicApiPaths, "POST /arbitrary/*"));
		assertFalse(settingsPath + " should not expose server-side transaction signing",
				jsonArrayContains(publicApiPaths, "POST /transactions/sign"));
	}

	private static boolean jsonArrayContains(JSONArray array, String value) {
		for (int i = 0; i < array.length(); ++i)
			if (value.equals(array.getString(i)))
				return true;

		return false;
	}

}
