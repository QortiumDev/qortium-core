package org.qortium.test.api;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.ApiError;
import org.qortium.api.ApiRequest;
import org.qortium.api.restricted.resource.AdminResource;
import org.qortium.controller.BootstrapNode;
import org.qortium.controller.RestartNode;
import org.qortium.controller.arbitrary.ArbitraryDataStorageManager.StoragePolicy;
import org.qortium.settings.Settings;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;

import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AdminApiTests extends ApiCommon {

	private AdminResource adminResource;

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		ApiCommon.installTestApiKey();
		releaseBootstrapApply();
		releaseRestartApply();
	}

	@Before
	public void buildResource() {
		this.adminResource = (AdminResource) ApiCommon.buildResource(AdminResource.class);
	}

	@After
	public void afterTest() throws Exception {
		releaseBootstrapApply();
		releaseRestartApply();
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

		assertApiError(org.qortium.api.ApiError.INVALID_CRITERIA,
				() -> this.adminResource.updateSettings(ApiCommon.TEST_API_KEY, "{\"apiKeyPath\":\"/tmp/qortium-api-key\"}"));
		assertEquals(StoragePolicy.NONE, Settings.getInstance().getStoragePolicy());
	}

	@Test
	public void testSettingsMetadata() throws Exception {
		Path settingsPath = createWritableApiSettings("{\"storagePolicy\":\"NONE\"}");
		Settings.fileInstance(settingsPath.toString());

		Settings.SettingsMetadata metadata = this.adminResource.settingsMetadata();

		assertEquals(settingsPath.toAbsolutePath().normalize().toString(), metadata.settingsPath);
		assertTrue(metadata.writable.containsKey("storagePolicy"));
		assertEquals("STORAGE_POLICY", metadata.writable.get("storagePolicy").type);
		assertEquals("LONG", metadata.writable.get("maxStorageCapacity").type);
		assertEquals(false, metadata.writable.get("maxStorageCapacity").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("listenPort").type);
		assertEquals(true, metadata.writable.get("listenPort").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("listenDataPort").type);
		assertEquals(true, metadata.writable.get("listenDataPort").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("maxPeers").type);
		assertEquals(true, metadata.writable.get("maxPeers").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("maxDataPeers").type);
		assertEquals(true, metadata.writable.get("maxDataPeers").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("minOutboundPeers").type);
		assertEquals(true, metadata.writable.get("minOutboundPeers").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("minBlockchainPeers").type);
		assertEquals(false, metadata.writable.get("minBlockchainPeers").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("minDataPeers").type);
		assertEquals(false, metadata.writable.get("minDataPeers").restartRequired);
		assertEquals("BOOLEAN", metadata.writable.get("apiKeyRemoteAccessEnabled").type);
		assertEquals(false, metadata.writable.get("apiKeyRemoteAccessEnabled").restartRequired);
		assertEquals("LONG", metadata.writable.get("publicApiWriteMaxBodySize").type);
		assertEquals(false, metadata.writable.get("publicApiWriteMaxBodySize").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("publicApiBuilderRequestsPerMinute").type);
		assertEquals(false, metadata.writable.get("publicApiBuilderRequestsPerMinute").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("publicApiBuilderRateLimitBurst").type);
		assertEquals(false, metadata.writable.get("publicApiBuilderRateLimitBurst").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("publicApiBuilderMaxConcurrentRequests").type);
		assertEquals(false, metadata.writable.get("publicApiBuilderMaxConcurrentRequests").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("publicApiProcessRequestsPerMinute").type);
		assertEquals(false, metadata.writable.get("publicApiProcessRequestsPerMinute").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("publicApiProcessRateLimitBurst").type);
		assertEquals(false, metadata.writable.get("publicApiProcessRateLimitBurst").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("publicApiProcessMaxConcurrentRequests").type);
		assertEquals(false, metadata.writable.get("publicApiProcessMaxConcurrentRequests").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("publicQdnApiMaxConcurrentRequests").type);
		assertEquals(false, metadata.writable.get("publicQdnApiMaxConcurrentRequests").restartRequired);
		assertEquals("PEER_VERSION", metadata.writable.get("minPeerVersion").type);
		assertEquals(true, metadata.writable.get("minPeerVersion").restartRequired);
		assertEquals("BOOLEAN", metadata.writable.get("allowConnectionsWithOlderPeerVersions").type);
		assertEquals(true, metadata.writable.get("allowConnectionsWithOlderPeerVersions").restartRequired);
		assertEquals("LONG", metadata.writable.get("chatMessageRetentionPeriod").type);
		assertEquals(false, metadata.writable.get("chatMessageRetentionPeriod").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("hsqldbCacheRows").type);
		assertEquals(true, metadata.writable.get("hsqldbCacheRows").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("hsqldbCacheSize").type);
		assertEquals(true, metadata.writable.get("hsqldbCacheSize").restartRequired);
		assertEquals("LONG", metadata.writable.get("qdnPublishMaxSize").type);
		assertEquals(false, metadata.writable.get("qdnPublishMaxSize").restartRequired);
		assertEquals("LONG", metadata.writable.get("publicQdnPublishMaxSize").type);
		assertEquals(false, metadata.writable.get("publicQdnPublishMaxSize").restartRequired);
		assertEquals("LONG", metadata.writable.get("publicQdnPublishChunkMaxSize").type);
		assertEquals(false, metadata.writable.get("publicQdnPublishChunkMaxSize").restartRequired);
		assertEquals("INTEGER", metadata.writable.get("publicQdnPublishChunkSessionLimit").type);
		assertEquals(false, metadata.writable.get("publicQdnPublishChunkSessionLimit").restartRequired);
		assertTrue(metadata.pendingRestart.isEmpty());
	}

	@Test
	public void testSettingsResponseOmitsSslKeystorePassword() throws Exception {
		Path settingsPath = createWritableApiSettings(
				"{\"sslKeystorePathname\":\"operator.jks\",\"sslKeystorePassword\":\"operator-secret\"}");
		Settings.fileInstance(settingsPath.toString());

		StringWriter writer = new StringWriter();
		ApiRequest.marshall(writer, this.adminResource.settings());
		String settingsJson = writer.toString();

		assertTrue(settingsJson.contains("\"sslKeystorePathname\""));
		assertTrue(settingsJson.contains("operator.jks"));
		assertFalse(settingsJson.contains("\"sslKeystorePassword\""));
		assertFalse(settingsJson.contains("operator-secret"));
		assertEquals("operator-secret", Settings.getInstance().getSslKeystorePassword());
	}

	@Test
	public void testSettingRequiresApiKey() {
		assertApiError(ApiError.UNAUTHORIZED, () -> this.adminResource.setting("repositoryPath"));

		AdminResource authenticatedAdminResource = (AdminResource) ApiCommon.buildResource(AdminResource.class, ApiCommon.TEST_API_KEY);

		assertNoApiError(() -> authenticatedAdminResource.setting("repositoryPath"));
	}

	@Test
	public void testBootstrapRejectsConcurrentApply() throws Exception {
		Path settingsPath = createWritableApiSettings("{\"bootstrapHosts\":[\"https://bootstrap.example\"]}");
		Settings.fileInstance(settingsPath.toString());
		AdminResource authenticatedAdminResource = (AdminResource) ApiCommon.buildResource(AdminResource.class, ApiCommon.TEST_API_KEY);

		assertTrue(tryAcquireBootstrapApply());

		assertApiError(ApiError.OPERATION_IN_PROGRESS, () -> authenticatedAdminResource.bootstrap(null));
	}

	@Test
	public void testRestartRejectsConcurrentApply() throws Exception {
		AdminResource authenticatedAdminResource = (AdminResource) ApiCommon.buildResource(AdminResource.class, ApiCommon.TEST_API_KEY);

		assertTrue(tryAcquireRestartApply());

		assertApiError(ApiError.OPERATION_IN_PROGRESS, () -> authenticatedAdminResource.restart(null));
	}

	private static Path createWritableApiSettings(String json) throws Exception {
		Path directory = Files.createTempDirectory("settings-api-test");
		Path settingsPath = directory.resolve("settings.json");
		Files.write(settingsPath, (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
		return settingsPath;
	}

	private static boolean tryAcquireBootstrapApply() throws Exception {
		Method method = BootstrapNode.class.getDeclaredMethod("tryAcquireBootstrapApply");
		method.setAccessible(true);
		return (Boolean) method.invoke(null);
	}

	private static void releaseBootstrapApply() throws Exception {
		Method method = BootstrapNode.class.getDeclaredMethod("releaseBootstrapApply");
		method.setAccessible(true);
		method.invoke(null);
	}

	private static boolean tryAcquireRestartApply() throws Exception {
		Method method = RestartNode.class.getDeclaredMethod("tryAcquireRestartApply");
		method.setAccessible(true);
		return (Boolean) method.invoke(null);
	}

	private static void releaseRestartApply() throws Exception {
		Method method = RestartNode.class.getDeclaredMethod("releaseRestartApply");
		method.setAccessible(true);
		method.invoke(null);
	}

}
