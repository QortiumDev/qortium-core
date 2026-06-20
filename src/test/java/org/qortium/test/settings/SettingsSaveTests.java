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
	public void testPlaintextElectrumSettingIsSaved() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"FOLLOWED\"}");
		Settings.fileInstance(settingsPath.toString());

		assertFalse(Settings.getInstance().isPlaintextElectrumServersAllowed());

		Settings.SettingsUpdateResult result = Settings.updateAndSave("{\"allowPlaintextElectrumServers\":true}");

		assertTrue(result.saved);
		assertTrue(result.updated.contains("allowPlaintextElectrumServers"));
		assertTrue(result.restartRequired.contains("allowPlaintextElectrumServers"));
		assertTrue(Settings.getInstance().isPlaintextElectrumServersAllowed());
		Map<String, Object> savedSettings = readSettings(settingsPath);
		assertEquals(Boolean.TRUE, savedSettings.get("allowPlaintextElectrumServers"));
	}

	@Test
	public void testDevProxySettingsAreSavedAndApplied() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"FOLLOWED\"}");
		Settings.fileInstance(settingsPath.toString());

		assertFalse(Settings.getInstance().isDevProxyEnabled());
		assertFalse(Settings.getInstance().isDevProxyUnsafeEvalEnabled());

		Settings.SettingsUpdateResult result = Settings.updateAndSave("{\"devProxyEnabled\":true,\"devProxyUnsafeEvalEnabled\":true}");

		assertTrue(result.saved);
		assertTrue(result.updated.contains("devProxyEnabled"));
		assertTrue(result.updated.contains("devProxyUnsafeEvalEnabled"));
		assertTrue(result.applied.contains("devProxyEnabled"));
		assertTrue(result.applied.contains("devProxyUnsafeEvalEnabled"));
		assertTrue(Settings.getInstance().isDevProxyEnabled());
		assertTrue(Settings.getInstance().isDevProxyUnsafeEvalEnabled());
		Map<String, Object> savedSettings = readSettings(settingsPath);
		assertEquals(Boolean.TRUE, savedSettings.get("devProxyEnabled"));
		assertEquals(Boolean.TRUE, savedSettings.get("devProxyUnsafeEvalEnabled"));
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
	public void testI2PSettingsRoundTripAndRestartRequired() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"FOLLOWED\"}");
		Settings.fileInstance(settingsPath.toString());

		Settings.SettingsUpdateResult result = Settings.updateAndSave(
				"{\"allowedTransports\":[\"I2P\",\"IP\"],\"i2pSamHost\":\"127.0.0.2\",\"i2pSamPort\":7000}");

		assertTrue(result.saved);
		// allowedTransports + SAM host/port change binding/transport setup -> restart required (not applied live).
		assertTrue(result.restartRequired.contains("allowedTransports"));
		assertFalse(result.applied.contains("allowedTransports"));
		assertTrue(result.restartRequired.contains("i2pSamHost"));
		assertTrue(result.restartRequired.contains("i2pSamPort"));

		// The in-memory value is still updated, so the derived getters reflect the new ordered list.
		assertTrue(Settings.getInstance().isI2PPreferred()); // I2P listed before IP
		assertTrue(Settings.getInstance().isI2PEnabled());
		assertTrue(Settings.getInstance().isIPAllowed());
		assertEquals("127.0.0.2", Settings.getInstance().getI2PSamHost());
		assertEquals(7000, Settings.getInstance().getI2PSamPort());

		Map<String, Object> saved = readSettings(settingsPath);
		assertEquals(java.util.List.of("I2P", "IP"), saved.get("allowedTransports"));
		assertEquals(7000, ((Number) saved.get("i2pSamPort")).intValue());
		assertEquals("127.0.0.2", saved.get("i2pSamHost"));
	}

	@Test
	public void testAllowedTransportsDerivedSemantics() throws Exception {
		Common.useDefaultSettings();
		Settings s = Settings.getInstance();

		// null field => effective default [IP, I2P]
		org.apache.commons.lang3.reflect.FieldUtils.writeField(s, "allowedTransports", null, true);
		assertTransports(s, true, true, false, false);

		// [IP, I2P] = direct primary, I2P fallback (old i2pEnabled=true, i2pPreferred=false)
		setTransports(s, Settings.Transport.IP, Settings.Transport.I2P);
		assertTransports(s, true, true, false, false);

		// [I2P, IP] = I2P preferred (old i2pPreferred=true)
		setTransports(s, Settings.Transport.I2P, Settings.Transport.IP);
		assertTransports(s, true, true, true, false);

		// [I2P] = I2P only (privacy: no direct TCP)
		setTransports(s, Settings.Transport.I2P);
		assertTransports(s, false, true, true, true);

		// [IP] = TCP only (old i2pEnabled=false)
		setTransports(s, Settings.Transport.IP);
		assertTransports(s, true, false, false, false);
	}

	/** Writable PATCH path (validateAllowedTransportsSetting) rejects bad values before any file write. */
	@Test
	public void testAllowedTransportsWritablePathRejectsBadValues() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"FOLLOWED\"}");
		Settings.fileInstance(settingsPath.toString());
		for (String bad : new String[] {"[]", "[\"IP\",\"IP\"]", "[\"TOR\"]", "[\"IP\",\"TOR\"]"}) {
			try {
				Settings.updateAndSave("{\"allowedTransports\":" + bad + "}");
				fail("Expected rejection of allowedTransports=" + bad);
			} catch (IllegalArgumentException expected) {
				// good
			}
		}
	}

	/**
	 * Load path (validate) must reject the same bad values a hand-edited settings.json could contain.
	 * MOXy binds the field as raw Strings precisely so unknown names like "TOR" are NOT silently
	 * dropped but surface here as a clear validation error.
	 */
	@Test
	public void testAllowedTransportsLoadPathRejectsBadValues() throws Exception {
		for (String bad : new String[] {"[]", "[\"IP\",\"IP\"]", "[\"TOR\"]", "[\"IP\",\"TOR\"]"}) {
			Path settingsPath = createSettingsFile("{\"allowedTransports\":" + bad + "}");
			try {
				Settings.fileInstance(settingsPath.toString());
				fail("Expected load-time rejection of allowedTransports=" + bad);
			} catch (RuntimeException expected) {
				// good — validate() throws on load
			}
		}
	}

	/** Load and writable paths must agree on case: lowercase names load and normalise to canonical Transports. */
	@Test
	public void testAllowedTransportsLoadNormalizesCase() throws Exception {
		Path settingsPath = createSettingsFile("{\"allowedTransports\":[\"i2p\",\" ip \"]}");
		Settings.fileInstance(settingsPath.toString());
		Settings s = Settings.getInstance();
		assertEquals(java.util.List.of(Settings.Transport.I2P, Settings.Transport.IP), s.getAllowedTransports());
		assertTransports(s, true, true, true, false);
	}

	private static void setTransports(Settings s, Settings.Transport... transports) throws Exception {
		// Field is bound as List<String> (raw names) so MOXy cannot silently drop unknown enums.
		java.util.List<String> names = new java.util.ArrayList<>();
		for (Settings.Transport t : transports)
			names.add(t.name());
		org.apache.commons.lang3.reflect.FieldUtils.writeField(s, "allowedTransports", names, true);
	}

	private static void assertTransports(Settings s, boolean ip, boolean i2pEnabled, boolean i2pPreferred, boolean i2pOnly) {
		assertEquals(ip, s.isIPAllowed());
		assertEquals(ip, s.isDirectAllowed());
		assertEquals(i2pEnabled, s.isI2PEnabled());
		assertEquals(i2pPreferred, s.isI2PPreferred());
		assertEquals(i2pOnly, s.isI2POnly());
	}

	@Test
	public void testInvalidI2PSamPortIsRejectedWithoutChangingFile() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"FOLLOWED\"}");
		Settings.fileInstance(settingsPath.toString());
		String originalJson = new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8);

		try {
			Settings.updateAndSave("{\"i2pSamPort\":70000}");
			fail("Expected out-of-range i2pSamPort to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("i2pSamPort"));
		}

		assertEquals(originalJson, new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8));
		assertEquals(7656, Settings.getInstance().getI2PSamPort()); // unchanged default
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
		String fingerprint = "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF";
		String normalisedFingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

		Settings.SettingsUpdateResult result = Settings.updateAndSave("{\"bitcoinyServers\":{\"btc\":{\"main\":{\"servers\":["
				+ "{\"hostName\":\" Custom.EXAMPLE.com \",\"port\":50002,\"connectionType\":\"ssl\",\"certificateSha256Fingerprint\":\"" + fingerprint + "\"},"
				+ "{\"hostName\":\"custom.example.com\",\"port\":50002,\"connectionType\":\"SSL\"}],"
				+ "\"disabledServers\":[{\"hostName\":\"Disabled.EXAMPLE.com\",\"port\":50001,\"connectionType\":\"tcp\"}]}}}}");

		assertTrue(result.saved);
		assertTrue(result.updated.contains("bitcoinyServers"));
		assertTrue(result.restartRequired.contains("bitcoinyServers"));

		Settings.BitcoinyServerSettings serverSettings = Settings.getInstance().getBitcoinyServerSettings("BTC", "MAIN");
		assertEquals(1, serverSettings.getServers().size());
		assertEquals(new Settings.BitcoinyServer("custom.example.com", 50002, "SSL"), serverSettings.getServers().get(0));
		assertEquals(normalisedFingerprint, serverSettings.getServers().get(0).getCertificateSha256Fingerprint());
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

		try {
			Settings.updateAndSave("{\"bitcoinyServers\":{\"BTC\":{\"MAIN\":{\"servers\":[{\"hostName\":\"bad.example.com\",\"port\":50002,\"connectionType\":\"SSL\",\"certificateSha256Fingerprint\":\"not-a-fingerprint\"}]}}}}");
			fail("Expected invalid Bitcoiny server certificate fingerprint to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("Invalid bitcoinyServers setting"));
		}

		assertEquals(originalJson, new String(Files.readAllBytes(settingsPath), StandardCharsets.UTF_8));
	}

	@Test
	public void testGeneratedSslKeystorePasswordIsSavedAndApplied() throws Exception {
		Path settingsPath = createSettingsFile("{\"storagePolicy\":\"FOLLOWED\"}");
		Settings.fileInstance(settingsPath.toString());

		assertEquals("default", Settings.getInstance().getSslKeystorePassword());

		String generatedPassword = Settings.ensureGeneratedSslKeystorePassword();

		assertFalse("default".equals(generatedPassword));
		assertTrue(generatedPassword.length() >= 40);
		assertEquals(generatedPassword, Settings.getInstance().getSslKeystorePassword());
		assertEquals(generatedPassword, readSettings(settingsPath).get("sslKeystorePassword"));
		assertEquals(generatedPassword, Settings.ensureGeneratedSslKeystorePassword());
	}

	@Test
	public void testConfiguredSslKeystorePasswordIsPreserved() throws Exception {
		Path settingsPath = createSettingsFile("{\"sslKeystorePassword\":\"operator-set\"}");
		Settings.fileInstance(settingsPath.toString());

		assertEquals("operator-set", Settings.ensureGeneratedSslKeystorePassword());
		assertEquals("operator-set", Settings.getInstance().getSslKeystorePassword());
		assertEquals("operator-set", readSettings(settingsPath).get("sslKeystorePassword"));
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
