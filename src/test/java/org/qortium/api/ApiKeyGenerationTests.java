package org.qortium.api;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.restricted.resource.AdminResource;
import org.qortium.settings.Settings;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ApiKeyGenerationTests extends ApiCommon {

	private static final Set<PosixFilePermission> API_KEY_FILE_PERMISSIONS =
			PosixFilePermissions.fromString("rw-------");

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
		assertApiKeyFileHasRestrictivePermissionsIfSupported();
	}

	@Test
	public void testExistingApiKeyPermissionsTightenedOnLoad() throws Exception {
		Files.createDirectories(this.apiKeyPath);
		Path apiKeyFile = this.getApiKeyFile();
		Files.writeString(apiKeyFile, ApiCommon.TEST_API_KEY, StandardCharsets.UTF_8);

		if (!this.supportsPosixPermissions(apiKeyFile)) {
			return;
		}

		Files.setPosixFilePermissions(apiKeyFile, PosixFilePermissions.fromString("rw-r--r--"));

		ApiKey apiKey = new ApiKey();

		assertEquals(ApiCommon.TEST_API_KEY, apiKey.toString());
		assertApiKeyFileHasRestrictivePermissionsIfSupported();
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
		assertApiKeyFileHasRestrictivePermissionsIfSupported();
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

	private void assertApiKeyFileHasRestrictivePermissionsIfSupported() throws Exception {
		Path apiKeyFile = this.getApiKeyFile();
		if (!this.supportsPosixPermissions(apiKeyFile)) {
			return;
		}

		assertEquals(API_KEY_FILE_PERMISSIONS, Files.getPosixFilePermissions(apiKeyFile));
	}

	private boolean supportsPosixPermissions(Path path) throws Exception {
		Path fileStorePath = Files.exists(path) ? path : path.getParent();
		if (fileStorePath == null || !Files.exists(fileStorePath)) {
			fileStorePath = this.testRoot;
		}

		return Files.getFileStore(fileStorePath).supportsFileAttributeView(PosixFileAttributeView.class);
	}

}
