package org.qortium.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.qortium.settings.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.security.Security;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class BootstrapConfigurationTests {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

	private Object originalSettingsInstance;

	@Before
	public void captureOriginalSettingsInstance() throws IllegalAccessException {
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null)
			Security.insertProviderAt(new BouncyCastleProvider(), 1);
		this.originalSettingsInstance = FieldUtils.readStaticField(Settings.class, "instance", true);
	}

	@After
	public void restoreOriginalSettingsInstance() throws IllegalAccessException {
		FieldUtils.writeStaticField(Settings.class, "instance", this.originalSettingsInstance, true);
	}

	private Settings newSettingsInstance() throws ReflectiveOperationException {
		Constructor<Settings> constructor = Settings.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		return constructor.newInstance();
	}

	@Test
	public void testDefaultBootstrapSettingsAreDisabledAndEmpty() throws ReflectiveOperationException {
		Settings settings = newSettingsInstance();

		assertFalse(settings.getBootstrap());
		assertFalse(settings.hasBootstrapHostsConfigured());
		assertArrayEquals(new String[0], settings.getBootstrapHosts());
	}

	@Test
	public void testLegacyHostedBootstrapInputsAreInert() throws Exception {
		Path settingsPath = Files.createTempFile("retired-hosted-bootstrap", ".json");
		Files.write(settingsPath, ("{\"bootstrap\":true,\"bootstrapHosts\":[\"https://attacker.invalid\"],"
				+ "\"archiveFastReplayOnlyWhenBootstrapDisabled\":true}" + System.lineSeparator())
				.getBytes(StandardCharsets.UTF_8));

		Settings.fileInstance(settingsPath.toString());
		Settings settings = Settings.getInstance();

		assertFalse(settings.getBootstrap());
		assertArrayEquals(new String[0], settings.getBootstrapHosts());
		assertFalse(settings.hasBootstrapHostsConfigured());
		assertFalse(settings.isArchiveFastReplayOnlyWhenBootstrapDisabled());
		assertEquals(Boolean.FALSE, FieldUtils.readField(settings, "bootstrap", true));
		assertArrayEquals(new String[0], (String[]) FieldUtils.readField(settings, "bootstrapHosts", true));
		assertEquals(Boolean.FALSE, FieldUtils.readField(settings, "archiveFastReplayOnlyWhenBootstrapDisabled", true));
	}

	@Test
	public void testManagedProfilesExplicitlyRetireHostedBootstrap() throws Exception {
		for (String profile : List.of(
				"preview/settings-preview.json",
				"preview/settings-preview-seed.json",
				"preview/settings-preview-seed-netcup.json",
				"testnet/settings-test.json")) {
			Map<String, Object> settings = MAPPER.readValue(Files.readAllBytes(Path.of(profile)), MAP_TYPE);
			assertEquals(profile, Boolean.FALSE, settings.get("bootstrap"));
			assertEquals(profile, List.of(), settings.get("bootstrapHosts"));
		}
	}
}
