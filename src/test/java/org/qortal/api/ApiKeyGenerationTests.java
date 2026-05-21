package org.qortal.api;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.api.restricted.resource.AdminResource;
import org.qortal.settings.Settings;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.Common;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ApiKeyGenerationTests extends ApiCommon {

	private Path testRoot;
	private Path apiKeyPath;

	@Before
	public void beforeTest() throws Exception {
		ApiCommon.clearTestApiKey();

		this.testRoot = Files.createTempDirectory("api-key-generation");
		this.apiKeyPath = this.testRoot.resolve("keys");

		FieldUtils.writeField(Settings.getInstance(), "apiKeyPath", this.apiKeyPath.toString(), true);
	}

	@After
	public void afterTest() throws Exception {
		ApiCommon.clearTestApiKey();

		if (this.testRoot != null) {
			FileUtils.deleteDirectory(this.testRoot.toFile());
		}

		Common.useDefaultSettings();
	}

	@Test
	public void testGenerateApiKeyRequiresExistingKey() {
		AdminResource adminResource = (AdminResource) ApiCommon.buildResource(AdminResource.class);

		assertApiError(ApiError.UNAUTHORIZED, () -> adminResource.generateApiKey(null));
		assertFalse(Files.exists(this.getApiKeyFile()));
	}

	@Test
	public void testInitializeApiKeyGeneratesMissingKey() throws Exception {
		ApiKey apiKey = ApiService.getInstance().initializeApiKey();

		assertTrue(apiKey.generated());
		assertSame(apiKey, ApiService.getInstance().getApiKey());
		assertTrue(Files.exists(this.getApiKeyFile()));
		assertEquals(apiKey.toString(), Files.readString(this.getApiKeyFile()));
	}

	@Test
	public void testInitializeApiKeyPersistsLegacySettingsKey() throws Exception {
		String legacyApiKey = "legacy-test-api-key";
		FieldUtils.writeField(Settings.getInstance(), "apiKey", legacyApiKey, true);

		ApiKey apiKey = ApiService.getInstance().initializeApiKey();

		assertTrue(apiKey.generated());
		assertEquals(legacyApiKey, apiKey.toString());
		assertEquals(legacyApiKey, Files.readString(this.getApiKeyFile()));
	}

	@Test
	public void testAuthenticatedGenerateApiKeyRotatesAndPersistsKey() throws Exception {
		ApiCommon.installTestApiKey();
		AdminResource adminResource = (AdminResource) ApiCommon.buildResource(AdminResource.class,
				ApiCommon.TEST_API_KEY);

		String newApiKey = adminResource.generateApiKey(ApiCommon.TEST_API_KEY);

		assertNotEquals(ApiCommon.TEST_API_KEY, newApiKey);
		assertEquals(newApiKey, ApiService.getInstance().getApiKey().toString());
		assertEquals(newApiKey, Files.readString(this.getApiKeyFile()));
	}

	@Test
	public void testInMemoryApiKeyRequiresAuthWhenBackingFileMissing() {
		ApiCommon.installTestApiKey();
		AdminResource adminResource = (AdminResource) ApiCommon.buildResource(AdminResource.class);

		assertFalse(Files.exists(this.getApiKeyFile()));
		assertApiError(ApiError.UNAUTHORIZED, () -> adminResource.generateApiKey(null));
		assertFalse(Files.exists(this.getApiKeyFile()));
		assertEquals(ApiCommon.TEST_API_KEY, ApiService.getInstance().getApiKey().toString());
	}

	private Path getApiKeyFile() {
		return this.apiKeyPath.resolve("apikey.txt");
	}

}
