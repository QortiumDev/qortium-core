package org.qortium.test.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;
import org.qortium.controller.arbitrary.ArbitraryDataStorageManager.StoragePolicy;
import org.qortium.settings.Settings;
import org.qortium.settings.Settings.AutoUpdateMode;
import org.qortium.test.common.Common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SettingsSaveTests extends Common {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<LinkedHashMap<String, Object>>() {};

	@After
	public void restoreDefaultSettings() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testAllowedSettingsAreSavedAndApplied() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"FOLLOWED\"}");
		Settings.fileInstance(settingsPath.toString());

		Settings.SettingsUpdateResult result = Settings.updateAndSave("{\"bootstrapHosts\":[\" https://bootstrap.example \"],\"qdnEnabled\":false}");

		assertTrue(result.saved);
		assertEquals(settingsPath.toAbsolutePath().normalize().toString(), result.settingsPath);
		assertTrue(result.updated.contains("bootstrapHosts"));
		assertTrue(result.updated.contains("qdnEnabled"));
		assertTrue(result.applied.contains("bootstrapHosts"));
		assertTrue(result.restartRequired.contains("qdnEnabled"));
		assertArrayEquals(new String[] {"https://bootstrap.example"}, Settings.getInstance().getBootstrapHosts());
		assertFalse(Settings.getInstance().isQdnEnabled());

		Map<String, Object> savedSettings = readSettings(settingsPath);
		assertEquals(Boolean.FALSE, savedSettings.get("qdnEnabled"));
		assertTrue(savedSettings.containsKey("bootstrapHosts"));
	}

	@Test
	public void testAutoUpdateModeIsSavedAndApplied() throws Exception {
		Path settingsPath = createSettingsFile("{\"autoUpdateMode\":\"INSTALL\"}");
		Settings.fileInstance(settingsPath.toString());

		assertEquals(AutoUpdateMode.INSTALL, Settings.getInstance().getAutoUpdateMode());

		Settings.SettingsUpdateResult result = Settings.updateAndSave("{\"autoUpdateMode\":\"notify\"}");

		assertTrue(result.saved);
		assertTrue(result.updated.contains("autoUpdateMode"));
		assertTrue(result.restartRequired.contains("autoUpdateMode"));
		assertEquals(AutoUpdateMode.NOTIFY, Settings.getInstance().getAutoUpdateMode());
		Map<String, Object> savedSettings = readSettings(settingsPath);
		assertEquals("NOTIFY", savedSettings.get("autoUpdateMode"));
	}

	@Test
	public void testAutoUpdateEnabledSettingIsRejectedWithoutChangingFile() throws Exception {
		Path settingsPath = createSettingsFile("{\"autoUpdateMode\":\"CHECK_ONLY\"}");
		Settings.fileInstance(settingsPath.toString());
		String originalJson = new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8);

		try {
			Settings.updateAndSave("{\"autoUpdateEnabled\":true}");
			fail("Expected autoUpdateEnabled to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("not writable"));
		}

		assertEquals(originalJson, new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8));
		assertEquals(AutoUpdateMode.CHECK_ONLY, Settings.getInstance().getAutoUpdateMode());
	}

	@Test
	public void testAutoUpdateReposSettingIsRejectedWithoutChangingFile() throws Exception {
		Path settingsPath = createSettingsFile("{\"autoUpdateMode\":\"CHECK_ONLY\"}");
		Settings.fileInstance(settingsPath.toString());
		String originalJson = new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8);

		try {
			Settings.updateAndSave("{\"autoUpdateRepos\":[\"https://example.com/%s\"]}");
			fail("Expected autoUpdateRepos to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("not writable"));
		}

		assertEquals(originalJson, new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8));
		assertEquals(AutoUpdateMode.CHECK_ONLY, Settings.getInstance().getAutoUpdateMode());
	}

	@Test
	public void testInvalidAutoUpdateModeIsRejectedWithoutChangingFile() throws Exception {
		Path settingsPath = createSettingsFile("{\"autoUpdateMode\":\"OFF\"}");
		Settings.fileInstance(settingsPath.toString());
		String originalJson = new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8);

		try {
			Settings.updateAndSave("{\"autoUpdateMode\":\"AUTOMATIC\"}");
			fail("Expected invalid auto-update mode to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("AUTOMATIC"));
		}

		assertEquals(originalJson, new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8));
		assertEquals(AutoUpdateMode.OFF, Settings.getInstance().getAutoUpdateMode());
	}

	@Test
	public void testNullRemovesAllowedSetting() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"NONE\"}");
		Settings.fileInstance(settingsPath.toString());

		Settings.SettingsUpdateResult result = Settings.updateAndSave("{\"storagePolicy\":null}");

		assertTrue(result.saved);
		assertTrue(result.removed.contains("storagePolicy"));
		assertEquals(StoragePolicy.FOLLOWED_OR_VIEWED, Settings.getInstance().getStoragePolicy());
		assertFalse(readSettings(settingsPath).containsKey("storagePolicy"));
	}

	@Test
	public void testQdnAuthBypassDefaultsToDisabled() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"FOLLOWED\"}");
		Settings.fileInstance(settingsPath.toString());

		assertFalse(Settings.getInstance().isQDNAuthBypassEnabled());
	}

	@Test
	public void testQdnAuthBypassCanBeExplicitlyEnabled() throws Exception {
		Path settingsPath = createSettingsFile("{\"qdnAuthBypassEnabled\":true}");
		Settings.fileInstance(settingsPath.toString());

		assertTrue(Settings.getInstance().isQDNAuthBypassEnabled());
	}

	@Test
	public void testGatewayModeForcesQdnAuthBypass() throws Exception {
		Path settingsPath = createSettingsFile("{\"gatewayEnabled\":true,\"qdnAuthBypassEnabled\":false}");
		Settings.fileInstance(settingsPath.toString());

		assertTrue(Settings.getInstance().isQDNAuthBypassEnabled());
	}

	@Test
	public void testDisallowedSettingIsRejectedWithoutChangingFile() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"FOLLOWED\"}");
		Settings.fileInstance(settingsPath.toString());
		String originalJson = new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8);

		try {
			Settings.updateAndSave("{\"apiKeyPath\":\"/tmp/qortium-api-key\"}");
			fail("Expected disallowed setting to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("not writable"));
		}

		assertEquals(originalJson, new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8));
		assertEquals(StoragePolicy.FOLLOWED, Settings.getInstance().getStoragePolicy());
	}

	@Test
	public void testInvalidSettingValueIsRejectedWithoutChangingFile() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"FOLLOWED\"}");
		Settings.fileInstance(settingsPath.toString());
		String originalJson = new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8);

		try {
			Settings.updateAndSave("{\"qdnEnabled\":\"yes\"}");
			fail("Expected invalid setting value to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("boolean"));
		}

		assertEquals(originalJson, new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8));
		assertTrue(Settings.getInstance().isQdnEnabled());
	}

	@Test
	public void testInvalidMergedSettingsAreRejectedWithoutChangingFile() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"FOLLOWED\"}");
		Settings.fileInstance(settingsPath.toString());
		String originalJson = new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8);

		try {
			Settings.updateAndSave("{\"bitcoinyNetworks\":{\"BTC\":\"UNKNOWN\"}}");
			fail("Expected invalid merged settings to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("Invalid settings patch"));
		}

		assertEquals(originalJson, new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8));
		assertEquals(StoragePolicy.FOLLOWED, Settings.getInstance().getStoragePolicy());
	}

	@Test
	public void testBitcoinyServersAreSavedAndNormalised() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"FOLLOWED\"}");
		Settings.fileInstance(settingsPath.toString());

		Settings.SettingsUpdateResult result = Settings.updateAndSave("{\"bitcoinyServers\":{\"btc\":{\"main\":{\"servers\":["
				+ "{\"hostName\":\" Custom.EXAMPLE.com \",\"port\":50002,\"connectionType\":\"ssl\"},"
				+ "{\"hostName\":\"custom.example.com\",\"port\":50002,\"connectionType\":\"SSL\"}],"
				+ "\"disabledServers\":[{\"hostName\":\"Disabled.EXAMPLE.com\",\"port\":50001,\"connectionType\":\"tcp\"}]}}}}");

		assertTrue(result.saved);
		assertTrue(result.updated.contains("bitcoinyServers"));
		assertTrue(result.restartRequired.contains("bitcoinyServers"));

		Settings.BitcoinyServerSettings serverSettings = Settings.getInstance().getBitcoinyServerSettings("BTC", "MAIN");
		assertEquals(1, serverSettings.getServers().size());
		assertEquals(new Settings.BitcoinyServer("custom.example.com", 50002, "SSL"), serverSettings.getServers().get(0));
		assertEquals(1, serverSettings.getDisabledServers().size());
		assertEquals(new Settings.BitcoinyServer("disabled.example.com", 50001, "TCP"), serverSettings.getDisabledServers().get(0));

		Map<String, Object> savedSettings = readSettings(settingsPath);
		Map<String, Object> savedBitcoinyServers = (Map<String, Object>) savedSettings.get("bitcoinyServers");
		assertTrue(savedBitcoinyServers.containsKey("BTC"));
	}

	@Test
	public void testInvalidBitcoinyServerCoinIsRejectedWithoutChangingFile() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"FOLLOWED\"}");
		Settings.fileInstance(settingsPath.toString());
		String originalJson = new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8);

		try {
			Settings.updateAndSave("{\"bitcoinyServers\":{\"NOPE\":{\"MAIN\":{\"servers\":[]}}}}");
			fail("Expected unsupported Bitcoiny server coin to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("Invalid bitcoinyServers setting"));
		}

		assertEquals(originalJson, new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8));
	}

	@Test
	public void testInvalidBitcoinyServerNetworkIsRejectedWithoutChangingFile() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"FOLLOWED\"}");
		Settings.fileInstance(settingsPath.toString());
		String originalJson = new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8);

		try {
			Settings.updateAndSave("{\"bitcoinyServers\":{\"BTC\":{\"UNKNOWN\":{\"servers\":[]}}}}");
			fail("Expected unsupported Bitcoiny server network to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("Invalid bitcoinyServers setting"));
		}

		assertEquals(originalJson, new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8));
	}

	@Test
	public void testInvalidBitcoinyServerFieldsAreRejectedWithoutChangingFile() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"FOLLOWED\"}");
		Settings.fileInstance(settingsPath.toString());
		String originalJson = new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8);

		try {
			Settings.updateAndSave("{\"bitcoinyServers\":{\"BTC\":{\"MAIN\":{\"servers\":[{\"hostName\":\"bad.example.com\",\"port\":0,\"connectionType\":\"SSL\"}]}}}}");
			fail("Expected invalid Bitcoiny server port to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("Invalid bitcoinyServers setting"));
		}

		assertEquals(originalJson, new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8));

		try {
			Settings.updateAndSave("{\"bitcoinyServers\":{\"BTC\":{\"MAIN\":{\"servers\":[{\"hostName\":\"bad.example.com\",\"port\":50002,\"connectionType\":\"WS\"}]}}}}");
			fail("Expected invalid Bitcoiny server connection type to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("Invalid bitcoinyServers setting"));
		}

		assertEquals(originalJson, new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8));

		try {
			Settings.updateAndSave("{\"bitcoinyServers\":{\"BTC\":{\"MAIN\":{\"servers\":[{\"port\":50002,\"connectionType\":\"SSL\"}]}}}}");
			fail("Expected missing Bitcoiny server host to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("Invalid bitcoinyServers setting"));
		}

		assertEquals(originalJson, new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8));
	}

	@Test
	public void testUserPathRedirectSavesFinalSettingsFile() throws Exception {
		Path directory = Files.createTempDirectory("settings-save-test");
		Path redirectDirectory = Files.createDirectory(directory.resolve("redirected"));
		Path rootSettingsPath = directory.resolve("settings.json");
		Path redirectedSettingsPath = redirectDirectory.resolve("settings.json");

		write(rootSettingsPath, String.format("{\"userPath\":\"%s\"}", redirectDirectory.toString().replace("\\", "\\\\")));
		write(redirectedSettingsPath, "{\"storagePolicy\":\"NONE\"}");

		Settings.fileInstance(rootSettingsPath.toString());
		assertEquals(redirectedSettingsPath.toAbsolutePath().normalize(), Settings.getActiveSettingsPath());

		Settings.updateAndSave("{\"storagePolicy\":\"FOLLOWED\"}");

		assertEquals("FOLLOWED", readSettings(redirectedSettingsPath).get("storagePolicy"));
		assertFalse(readSettings(rootSettingsPath).containsKey("storagePolicy"));
		assertEquals(StoragePolicy.FOLLOWED, Settings.getInstance().getStoragePolicy());
	}

	private static Path createSettingsFile(String json) throws Exception {
		Path directory = Files.createTempDirectory("settings-save-test");
		Path settingsPath = directory.resolve("settings.json");
		write(settingsPath, json);
		return settingsPath;
	}

	private static void write(Path path, String value) throws Exception {
		Files.write(path, (value + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
	}

	private static Map<String, Object> readSettings(Path path) throws Exception {
		return MAPPER.readValue(Files.readAllBytes(path), MAP_TYPE);
	}

}
