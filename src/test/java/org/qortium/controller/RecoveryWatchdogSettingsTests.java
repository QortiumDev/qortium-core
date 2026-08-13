package org.qortium.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Fail-closed configuration coverage for development-only peer-claim orphaning. */
public class RecoveryWatchdogSettingsTests extends Common {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

	@After
	public void restoreDefaultSettings() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testAbsentSettingDisablesPeerClaimOrphaning() throws Exception {
		loadSettings("{}");
		assertFalse(Settings.getInstance().isDevelopmentPeerClaimOrphaningEnabled());
	}

	@Test
	public void testLegacyTrueSettingCannotEnablePeerClaimOrphaning() throws Exception {
		loadSettings("{\"isTestNet\":true,\"recoveryWatchdogEnabled\":true}");
		assertFalse(Settings.getInstance().isDevelopmentPeerClaimOrphaningEnabled());
	}

	@Test
	public void testDevelopmentOptInIsRestrictedToTestNetworks() throws Exception {
		loadSettings("{\"developmentPeerClaimOrphaningEnabled\":true}");
		assertFalse(Settings.getInstance().isDevelopmentPeerClaimOrphaningEnabled());

		loadSettings("{\"isTestNet\":true,\"developmentPeerClaimOrphaningEnabled\":true}");
		assertTrue(Settings.getInstance().isDevelopmentPeerClaimOrphaningEnabled());
	}

	@Test
	public void testShippedProfilesDeclareSafePolicy() throws Exception {
		for (String profile : new String[] {
				"preview/settings-preview.json",
				"preview/settings-preview-seed.json",
				"preview/settings-preview-seed-netcup.json"
		}) {
			Map<String, Object> settings = readSettings(Path.of(profile));
			assertEquals(profile, Boolean.FALSE, settings.get("developmentPeerClaimOrphaningEnabled"));
		}

		Map<String, Object> developmentSettings = readSettings(Path.of("testnet/settings-test.json"));
		assertEquals(Boolean.TRUE, developmentSettings.get("developmentPeerClaimOrphaningEnabled"));
	}

	private static void loadSettings(String json) throws Exception {
		Path directory = Files.createTempDirectory("recovery-watchdog-settings-test");
		Path settingsPath = directory.resolve("settings.json");
		Files.writeString(settingsPath, json);
		Settings.fileInstance(settingsPath.toString());
	}

	private static Map<String, Object> readSettings(Path path) throws Exception {
		return MAPPER.readValue(Files.readAllBytes(path), MAP_TYPE);
	}
}
