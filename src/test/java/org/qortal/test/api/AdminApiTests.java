package org.qortal.test.api;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.api.restricted.resource.AdminResource;
import org.qortal.controller.arbitrary.ArbitraryDataStorageManager.StoragePolicy;
import org.qortal.repository.DataException;
import org.qortal.settings.Settings;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.Common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AdminApiTests extends ApiCommon {

	private AdminResource adminResource;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		ApiCommon.installTestApiKey();
	}

	@Before
	public void buildResource() {
		this.adminResource = (AdminResource) ApiCommon.buildResource(AdminResource.class);
	}

	@After
	public void afterTest() throws DataException {
		ApiCommon.clearTestApiKey();
		Common.useDefaultSettings();
	}

	@Test
	public void testInfo() {
		assertNotNull(this.adminResource.info());
	}

	@Test
	public void testSummary() {
		assertNotNull(this.adminResource.summary());
	}

	@Test
	public void testGetMintingAccounts() {
		assertNotNull(this.adminResource.getMintingAccounts());
	}

	@Test
	public void testUpdateSettings() throws Exception {
		Path settingsPath = createWritableApiSettings("{\"storagePolicy\":\"NONE\"}");
		Settings.fileInstance(settingsPath.toString());

		Settings.SettingsUpdateResult result = this.adminResource.updateSettings(ApiCommon.TEST_API_KEY, "{\"storagePolicy\":\"FOLLOWED\"}");

		assertTrue(result.saved);
		assertTrue(result.updated.contains("storagePolicy"));
		assertEquals(StoragePolicy.FOLLOWED, Settings.getInstance().getStoragePolicy());
	}

	@Test
	public void testUpdateSettingsRejectsDisallowedSetting() throws Exception {
		Path settingsPath = createWritableApiSettings("{\"storagePolicy\":\"NONE\"}");
		Settings.fileInstance(settingsPath.toString());

		assertApiError(org.qortal.api.ApiError.INVALID_CRITERIA,
				() -> this.adminResource.updateSettings(ApiCommon.TEST_API_KEY, "{\"apiKey\":\"abcdefgh\"}"));
		assertEquals(StoragePolicy.NONE, Settings.getInstance().getStoragePolicy());
	}

	private static Path createWritableApiSettings(String json) throws Exception {
		Path directory = Files.createTempDirectory("settings-api-test");
		Path settingsPath = directory.resolve("settings.json");
		Files.write(settingsPath, (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
		return settingsPath;
	}

}
