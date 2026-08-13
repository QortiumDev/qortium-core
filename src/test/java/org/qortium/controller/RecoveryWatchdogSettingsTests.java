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

/** Compatibility coverage proving retired peer-claim orphaning inputs remain inert. */
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
		assertPeerClaimOrphaningDisabled();
	}

	@Test
	public void testLegacyTrueSettingCannotEnablePeerClaimOrphaning() throws Exception {
		loadSettings("{\"isTestNet\":true,\"recoveryWatchdogEnabled\":true}");
		assertPeerClaimOrphaningDisabled();
	}

	@Test
	public void testRetiredDevelopmentOptInCannotEnablePeerClaimOrphaning() throws Exception {
		loadSettings("{\"developmentPeerClaimOrphaningEnabled\":true}");
		assertPeerClaimOrphaningDisabled();

		loadSettings("{\"isTestNet\":true,\"developmentPeerClaimOrphaningEnabled\":true}");
		assertPeerClaimOrphaningDisabled();

		loadSettings("{\"isTestNet\":true,\"recoveryWatchdogEnabled\":true,"
				+ "\"developmentPeerClaimOrphaningEnabled\":true}");
		assertPeerClaimOrphaningDisabled();
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
			assertEquals(profile, Boolean.FALSE, settings.get("recoveryWatchdogEnabled"));
		}

		Map<String, Object> developmentSettings = readSettings(Path.of("testnet/settings-test.json"));
		assertEquals(Boolean.FALSE, developmentSettings.get("developmentPeerClaimOrphaningEnabled"));
		assertEquals(Boolean.FALSE, developmentSettings.get("recoveryWatchdogEnabled"));
	}

	private static void loadSettings(String json) throws Exception {
		Path directory = Files.createTempDirectory("recovery-watchdog-settings-test");
		Path settingsPath = directory.resolve("settings.json");
		Files.writeString(settingsPath, json);
		Settings.fileInstance(settingsPath.toString());
	}

	@SuppressWarnings("deprecation")
	private static void assertPeerClaimOrphaningDisabled() {
		assertFalse(Settings.getInstance().isRecoveryWatchdogEnabled());
		assertFalse(Settings.getInstance().isDevelopmentPeerClaimOrphaningEnabled());
	}

	private static Map<String, Object> readSettings(Path path) throws Exception {
		return MAPPER.readValue(Files.readAllBytes(path), MAP_TYPE);
	}
}
