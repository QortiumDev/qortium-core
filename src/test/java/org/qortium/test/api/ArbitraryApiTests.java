package org.qortium.test.api;

import com.google.common.primitives.Bytes;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.json.JSONObject;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.ApiError;
import org.qortium.api.resource.ArbitraryResource;
import org.qortium.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortium.arbitrary.ArbitraryDataResource;
import org.qortium.arbitrary.misc.Service;
import org.qortium.controller.Controller;
import org.qortium.controller.arbitrary.ArbitraryDataCleanupManager;
import org.qortium.controller.arbitrary.ArbitraryDataRenderManager;
import org.qortium.data.arbitrary.ArbitraryResourceStatus;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.ArbitraryUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.RegisterNameTransaction;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.Base58;
import org.qortium.crypto.Crypto;
import org.qortium.utils.FilesystemUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.servlet.ServletContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ArbitraryApiTests extends ApiCommon {

	private ArbitraryResource arbitraryResource;

	@Before
	public void buildResource() throws Exception {
		this.arbitraryResource = (ArbitraryResource) ApiCommon.buildResource(ArbitraryResource.class);
		FieldUtils.writeField(Settings.getInstance(), "singleNodeTestnet", true, true);
		Controller.getInstance().refillLatestBlocksCache();
	}

	@Test
	public void testSearch() {
		Integer[] startingBlocks = new Integer[] { null, 0, 1, 999999999 };
		Integer[] blockLimits = new Integer[] { null, 0, 1, 999999999 };
		Integer[] txGroupIds = new Integer[] { null, 0, 1, 999999999 };
		Service[] services = new Service[] { Service.WEBSITE, Service.GIT_REPOSITORY, Service.BLOG_COMMENT };
		String[] names = new String[] { null, "Test" };
		String[] addresses = new String[] { null, this.aliceAddress };
		ConfirmationStatus[] confirmationStatuses = new ConfirmationStatus[] { ConfirmationStatus.UNCONFIRMED, ConfirmationStatus.CONFIRMED, ConfirmationStatus.BOTH };

		for (Integer startBlock : startingBlocks)
			for (Integer blockLimit : blockLimits)
				for (Integer txGroupId : txGroupIds)
					for (Service service : services)
						for (String name : names)
							for (String address : addresses)
								for (ConfirmationStatus confirmationStatus : confirmationStatuses) {
									if (confirmationStatus != ConfirmationStatus.CONFIRMED && (startBlock != null || blockLimit != null))
										continue;

									assertNotNull(this.arbitraryResource.searchTransactions(startBlock, blockLimit, txGroupId, service, name, address, confirmationStatus, 20, null, null));
									assertNotNull(this.arbitraryResource.searchTransactions(startBlock, blockLimit, txGroupId, service, name, address, confirmationStatus, 1, 1, true));
								}

		assertNotNull(this.arbitraryResource.searchTransactions(null, null, null, Service.APP, null, this.aliceAddress, null, 10, null, true));
	}

	@Test
	public void testDefaultStatusEndpointHonorsIdentifierQueryParameter() throws Exception {
		String name = "status-query-identifier";
		String identifier = "published-identifier";
		registerName(name);
		publishTestResource(name, identifier);

		ApiCommon.installTestApiKey();
		try {
			ArbitraryResource resource = (ArbitraryResource) ApiCommon
					.buildResource(ArbitraryResource.class, ApiCommon.TEST_API_KEY);
			ArbitraryResourceStatus defaultStatus = resource
					.getDefaultResourceStatus(Service.APP, name, null, false);
			ArbitraryResourceStatus queryStatus = resource
					.getDefaultResourceStatus(Service.APP, name, identifier, false);
			ArbitraryResourceStatus pathStatus = resource
					.getResourceStatus(Service.APP, name, identifier, false);

			assertEquals(pathStatus.getStatus(), queryStatus.getStatus());
			assertFalse(defaultStatus.getStatus().equals(queryStatus.getStatus()));
		} finally {
			ApiCommon.clearTestApiKey();
		}
	}

	@Test
	public void testRelayModeCompatibilityEndpointIsAlwaysEnabled() {
		ApiCommon.installTestApiKey();
		try {
			ArbitraryResource resource = (ArbitraryResource) ApiCommon
					.buildResource(ArbitraryResource.class, ApiCommon.TEST_API_KEY);
			assertTrue(resource.getRelayMode(ApiCommon.TEST_API_KEY));
		} finally {
			ApiCommon.clearTestApiKey();
		}
	}

	@Test
	public void testPreviewPathWorksWithoutName() throws Exception {
		ApiCommon.installTestApiKey();
		try {
			ArbitraryResource resource = (ArbitraryResource) ApiCommon.buildResource(ArbitraryResource.class, ApiCommon.TEST_API_KEY);

			Path previewDir = Files.createTempDirectory("qortium-preview-test");
			Files.writeString(previewDir.resolve("video.mp4"), "not really a video", StandardCharsets.UTF_8);

			String previewPath = resource.previewPath(ApiCommon.TEST_API_KEY, "VIDEO", previewDir.toString());

			assertTrue(previewPath.startsWith("/render/hash/"));
			assertTrue(previewPath.contains("?secret="));

			String hash58 = previewPath.substring("/render/hash/".length(), previewPath.indexOf('?'));
			assertTrue(ArbitraryDataRenderManager.getInstance().isAuthorized(new ArbitraryDataResource(hash58, null, null, null)));
		} finally {
			ApiCommon.clearTestApiKey();
		}
	}

	@Test
	public void testPreviewPathRejectsMissingPathAndInvalidService() {
		ApiCommon.installTestApiKey();
		try {
			ArbitraryResource resource = (ArbitraryResource) ApiCommon.buildResource(ArbitraryResource.class, ApiCommon.TEST_API_KEY);

			assertApiError(ApiError.INVALID_CRITERIA, () -> resource.previewPath(ApiCommon.TEST_API_KEY, "VIDEO", ""));
			assertApiError(ApiError.INVALID_CRITERIA, () -> resource.previewPath(ApiCommon.TEST_API_KEY, "NOT_A_SERVICE", "/tmp/preview"));
		} finally {
			ApiCommon.clearTestApiKey();
		}
	}

	@Test
	public void testPreviewPathRequiresApiKey() {
		assertApiError(ApiError.UNAUTHORIZED, () -> this.arbitraryResource.previewPath(null, "VIDEO", "/tmp/preview"));
	}

	private static String base64(byte[] content) {
		return Base64.getEncoder().encodeToString(content);
	}

	private static ByteArrayInputStream stream(String content) {
		return stream(content.getBytes(StandardCharsets.UTF_8));
	}

	private static ByteArrayInputStream stream(byte[] content) {
		return new ByteArrayInputStream(content);
	}

	private static byte[] zipWebsite(String html) throws IOException {
		ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
			zip.putNextEntry(new ZipEntry("index.html"));
			zip.write(html.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return zipBytes.toByteArray();
	}

	@Test
	public void testPreviewUploadSingleFileWorksWithoutName() throws Exception {
		ApiCommon.installTestApiKey();
		try {
			ArbitraryResource resource = (ArbitraryResource) ApiCommon.buildResource(ArbitraryResource.class, ApiCommon.TEST_API_KEY);

			byte[] content = "not really a video".getBytes(StandardCharsets.UTF_8);
			String previewPath = resource.previewUpload(ApiCommon.TEST_API_KEY, "VIDEO", "video.mp4", false, base64(content));

			assertTrue(previewPath.startsWith("/render/hash/"));
			assertTrue(previewPath.contains("?secret="));

			String hash58 = previewPath.substring("/render/hash/".length(), previewPath.indexOf('?'));
			assertTrue(ArbitraryDataRenderManager.getInstance().isAuthorized(new ArbitraryDataResource(hash58, null, null, null)));
		} finally {
			ApiCommon.clearTestApiKey();
		}
	}

	@Test
	public void testPreviewUploadSingleHtmlBecomesWebsite() throws Exception {
		ApiCommon.installTestApiKey();
		try {
			ArbitraryResource resource = (ArbitraryResource) ApiCommon.buildResource(ArbitraryResource.class, ApiCommon.TEST_API_KEY);

			byte[] html = "<html><body>preview</body></html>".getBytes(StandardCharsets.UTF_8);
			String previewPath = resource.previewUpload(ApiCommon.TEST_API_KEY, "WEBSITE", "page.html", false, base64(html));

			assertTrue(previewPath.startsWith("/render/hash/"));
			assertTrue(previewPath.contains("?secret="));
		} finally {
			ApiCommon.clearTestApiKey();
		}
	}

	@Test
	public void testPreviewUploadArchiveWorks() throws Exception {
		ApiCommon.installTestApiKey();
		try {
			ArbitraryResource resource = (ArbitraryResource) ApiCommon.buildResource(ArbitraryResource.class, ApiCommon.TEST_API_KEY);

			// A website is uploaded as a ZIP of its directory.
			ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
			try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
				zip.putNextEntry(new ZipEntry("index.html"));
				zip.write("<html><body>preview</body></html>".getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}

			String previewPath = resource.previewUpload(ApiCommon.TEST_API_KEY, "WEBSITE", null, true, base64(zipBytes.toByteArray()));

			assertTrue(previewPath.startsWith("/render/hash/"));
			assertTrue(previewPath.contains("?secret="));
		} finally {
			ApiCommon.clearTestApiKey();
		}
	}

	@Test
	public void testPreviewUploadRejectsMissingContentAndInvalidService() {
		ApiCommon.installTestApiKey();
		try {
			ArbitraryResource resource = (ArbitraryResource) ApiCommon.buildResource(ArbitraryResource.class, ApiCommon.TEST_API_KEY);

			assertApiError(ApiError.INVALID_CRITERIA,
					() -> resource.previewUpload(ApiCommon.TEST_API_KEY, "VIDEO", "video.mp4", false, null));
			assertApiError(ApiError.INVALID_CRITERIA,
					() -> resource.previewUpload(ApiCommon.TEST_API_KEY, "NOT_A_SERVICE", "f", false, base64(new byte[] { 1, 2, 3 })));
		} finally {
			ApiCommon.clearTestApiKey();
		}
	}

	@Test
	public void testPreviewUploadRequiresApiKey() {
		assertApiError(ApiError.UNAUTHORIZED,
				() -> this.arbitraryResource.previewUpload(null, "VIDEO", "video.mp4", false, base64(new byte[] { 1, 2, 3 })));
	}

	@Test
	public void testPublicQdnPublishBuildEndpointsDoNotRequireApiKey() throws Exception {
		String name = "public-qdn-api-test";
		registerName(name);

		String base64Transaction = this.arbitraryResource.postBase64EncodedDataPublic(
				"APP", name, "public", null, null, null, null, "index.html", 0L,
				base64("<html>public</html>".getBytes(StandardCharsets.UTF_8)));
		assertUnsignedArbitraryTransaction(base64Transaction);

		ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
			zip.putNextEntry(new ZipEntry("index.html"));
			zip.write("<html><body>public</body></html>".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		String zipTransaction = this.arbitraryResource.postZippedDataPublic(
				"APP", name, "public-zip", null, null, null, null, 0L, null,
				base64(zipBytes.toByteArray()));
		assertUnsignedArbitraryTransaction(zipTransaction);

		publishTestResource(name, "public");
		String deleteTransaction = this.arbitraryResource.deleteResourceOnChainPublic(Service.APP, name, "public", 0L);
		assertUnsignedArbitraryTransaction(deleteTransaction);
	}

	@Test
	public void testPublicStagedDataEndpointReturnsExactContentAddressedArtifacts() throws Exception {
		String name = "public-qdn-attestation-test";
		registerName(name);
		byte[] source = new byte[org.qortium.arbitrary.ArbitraryDataFile.CHUNK_SIZE + 1];
		new Random(0x4154544553544cL).nextBytes(source);

		String rawTransaction = this.arbitraryResource.postBase64EncodedDataPublic(
				"APP", name, "attested", null, null, null, null, "payload.bin", 0L,
				base64(source));
		ArbitraryTransactionData transactionData = parseUnsignedArbitraryTransaction(rawTransaction);

		assertPublicStagedArtifact(transactionData.getData());
		assertNotNull("Chunked publishes should expose a metadata hash", transactionData.getMetadataHash());
		assertPublicStagedArtifact(transactionData.getMetadataHash());

		writePublicAttestationFixtureIfRequested(name);
	}

	private void writePublicAttestationFixtureIfRequested(String name) throws Exception {
		String outputPath = System.getProperty("qortium.publicQdnAttestationFixture");
		if (outputPath == null || outputPath.isBlank())
			return;

		byte[] sourceZip = multiFileFixtureZip();
		String rawTransaction = this.arbitraryResource.postZippedDataPublic(
				"APP", name, "fixture-multi-zip", "Core attestation fixture",
				"Generated by ArbitraryApiTests", null, null, 0L, "index.html", base64(sourceZip));
		ArbitraryTransactionData transactionData = parseUnsignedArbitraryTransaction(rawTransaction);
		assertEquals(ArbitraryTransactionData.DataType.DATA_HASH, transactionData.getDataType());
		assertEquals(ArbitraryTransactionData.Compression.ZIP, transactionData.getCompression());
		assertNotNull(transactionData.getMetadataHash());

		Response mainResponse = this.arbitraryResource.getPublicStagedData(Base58.encode(transactionData.getData()));
		Response metadataResponse = this.arbitraryResource.getPublicStagedData(Base58.encode(transactionData.getMetadataHash()));
		assertEquals(200, mainResponse.getStatus());
		assertEquals(200, metadataResponse.getStatus());

		JSONObject fixture = new JSONObject();
		fixture.put("schemaVersion", 1);
		fixture.put("generatedBy", "ArbitraryApiTests using Core public ZIP builder");
		fixture.put("sourceZipBase64", base64(sourceZip));
		fixture.put("unsignedTransactionBase58", rawTransaction);
		fixture.put("dataType", transactionData.getDataType().name());
		fixture.put("compression", transactionData.getCompression().name());
		fixture.put("name", transactionData.getName());
		fixture.put("identifier", transactionData.getIdentifier());
		fixture.put("publicKey58", Base58.encode(transactionData.getSenderPublicKey()));
		fixture.put("service", transactionData.getServiceInt());
		fixture.put("txGroupId", transactionData.getTxGroupId());
		fixture.put("dataHash58", Base58.encode(transactionData.getData()));
		fixture.put("metadataHash58", Base58.encode(transactionData.getMetadataHash()));
		fixture.put("secret58", Base58.encode(transactionData.getSecret()));
		fixture.put("stagedMainBase64", base64(Files.readAllBytes(((File) mainResponse.getEntity()).toPath())));
		fixture.put("stagedMetadataBase64", base64(Files.readAllBytes(((File) metadataResponse.getEntity()).toPath())));
		Files.writeString(Path.of(outputPath), fixture.toString(2) + System.lineSeparator(), StandardCharsets.UTF_8);
	}

	private static byte[] multiFileFixtureZip() throws IOException {
		byte[] binary = new byte[4096];
		new Random(0x51444e4154544553L).nextBytes(binary);

		ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
			ZipEntry index = new ZipEntry("index.html");
			index.setTime(0L);
			zip.putNextEntry(index);
			zip.write("<html><body>Core attestation fixture</body></html>".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();

			ZipEntry blob = new ZipEntry("assets/blob.bin");
			blob.setTime(0L);
			zip.putNextEntry(blob);
			zip.write(binary);
			zip.closeEntry();
		}
		return zipBytes.toByteArray();
	}

	@Test
	public void testPublicStagedDataEndpointRejectsInvalidMissingAndMismatchedHashes() throws Exception {
		assertEquals(400, this.arbitraryResource.getPublicStagedData("not-base58-0").getStatus());
		assertEquals(400, this.arbitraryResource.getPublicStagedData(Base58.encode(new byte[31])).getStatus());

		byte[] missingHash = Crypto.digest("missing-public-staged-data".getBytes(StandardCharsets.UTF_8));
		assertEquals(404, this.arbitraryResource.getPublicStagedData(Base58.encode(missingHash)).getStatus());

		byte[] unregisteredContent = "private-unregistered-data".getBytes(StandardCharsets.UTF_8);
		byte[] expectedHash = Crypto.digest(unregisteredContent);
		org.qortium.arbitrary.ArbitraryDataFile staged =
				org.qortium.arbitrary.ArbitraryDataFile.fromHash(expectedHash, null);
		Path stagedPath = staged.getFilePath();
		Files.createDirectories(stagedPath.getParent());
		Files.write(stagedPath, unregisteredContent);
		try {
			assertEquals("Hash knowledge must not expose unregistered _misc artifacts", 404,
					this.arbitraryResource.getPublicStagedData(Base58.encode(expectedHash)).getStatus());
		} finally {
			Files.deleteIfExists(stagedPath);
		}

		String name = "public-qdn-hash-mismatch-test";
		registerName(name);
		byte[] publicSource = new byte[1024];
		new Random(0x484153484d49534dL).nextBytes(publicSource);
		ArbitraryTransactionData transactionData = parseUnsignedArbitraryTransaction(
				this.arbitraryResource.postBase64EncodedDataPublic(
						"APP", name, "mismatch", null, null, null, null, "payload.bin", 0L,
						base64(publicSource)));
		assertEquals(ArbitraryTransactionData.DataType.DATA_HASH, transactionData.getDataType());
		org.qortium.arbitrary.ArbitraryDataFile registered =
				org.qortium.arbitrary.ArbitraryDataFile.fromHash(transactionData.getData(), null);
		byte[] original = Files.readAllBytes(registered.getFilePath());
		try {
			Files.write(registered.getFilePath(), "corrupt".getBytes(StandardCharsets.UTF_8));
			assertEquals(409, this.arbitraryResource
					.getPublicStagedData(Base58.encode(transactionData.getData())).getStatus());
		} finally {
			Files.write(registered.getFilePath(), original);
		}
	}

	private void assertPublicStagedArtifact(byte[] expectedHash) throws Exception {
		Response response = this.arbitraryResource.getPublicStagedData(Base58.encode(expectedHash));
		assertEquals(200, response.getStatus());
		assertEquals("no-store", response.getHeaderString("Cache-Control"));
		assertEquals("nosniff", response.getHeaderString("X-Content-Type-Options"));
		File stagedFile = (File) response.getEntity();
		assertTrue(stagedFile.isFile());
		assertEquals(stagedFile.length(), Long.parseLong(response.getHeaderString("Content-Length")));
		assertArrayEquals(expectedHash, Crypto.digestFileStream(stagedFile));
	}

	private static ArbitraryTransactionData parseUnsignedArbitraryTransaction(String rawTransaction58) throws Exception {
		byte[] rawBytes = Base58.decode(rawTransaction58);
		return (ArbitraryTransactionData) TransactionTransformer.fromBytes(
				Bytes.concat(rawBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]));
	}

	@Test
	public void testPublicQdnPublishBuildEndpointRejectsOversizedBase64Payload() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "publicQdnPublishMaxSize", 8L, true);

		String name = "public-qdn-size-test";
		registerName(name);

		assertApiError(ApiError.INVALID_DATA,
				() -> this.arbitraryResource.postBase64EncodedDataPublic(
						"APP", name, "public", null, null, null, null, "index.html", 0L,
						base64("0123456789".getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	public void testPublicQdnPublishBuildEndpointRejectsOversizedZipAfterExtraction() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "publicQdnPublishMaxSize", 200L, true);

		String name = "public-qdn-zip-size-test";
		registerName(name);

		ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
			zip.putNextEntry(new ZipEntry("index.html"));
			zip.write(new byte[1024]);
			zip.closeEntry();
		}

		assertApiError(ApiError.INVALID_DATA,
				() -> this.arbitraryResource.postZippedDataPublic(
						"APP", name, "public-zip", null, null, null, null, 0L, null,
						base64(zipBytes.toByteArray())));
	}

	@Test
	public void testStreamedQdnPublishBuildEndpointsReturnUnsignedTransactions() throws Exception {
		ApiCommon.installTestApiKey();
		try {
			ArbitraryResource privateResource = (ArbitraryResource) ApiCommon.buildResource(ArbitraryResource.class, ApiCommon.TEST_API_KEY);

			String privateRawName = "private-qdn-stream-raw-test";
			registerName(privateRawName);
			String privateRawTransaction = privateResource.postUpload(ApiCommon.TEST_API_KEY,
					"APP", privateRawName, null, null, null, null, "index.html", 0L,
					null, false, false, stream("<html>private raw</html>"));
			assertUnsignedArbitraryTransaction(privateRawTransaction);

			String privateZipName = "private-qdn-stream-zip-test";
			registerName(privateZipName);
			String privateZipTransaction = privateResource.postUpload(ApiCommon.TEST_API_KEY,
					"APP", privateZipName, null, null, null, null, "site.zip", 0L,
					null, false, true, stream(zipWebsite("<html>private zip</html>")));
			assertUnsignedArbitraryTransaction(privateZipTransaction);
		} finally {
			ApiCommon.clearTestApiKey();
		}

		String publicRawName = "public-qdn-stream-raw-test";
		registerName(publicRawName);
		String publicRawTransaction = this.arbitraryResource.postUploadPublic(
				"APP", publicRawName, null, null, null, null, "index.html", 0L,
				null, false, false, stream("<html>public raw</html>"));
		assertUnsignedArbitraryTransaction(publicRawTransaction);

		String publicZipName = "public-qdn-stream-zip-test";
		registerName(publicZipName);
		String publicZipTransaction = this.arbitraryResource.postUploadPublic(
				"APP", publicZipName, null, null, null, null, "site.zip", 0L,
				null, false, true, stream(zipWebsite("<html>public zip</html>")));
		assertUnsignedArbitraryTransaction(publicZipTransaction);
	}

	@Test
	public void testPublicStreamedQdnPublishRejectsOversizedPayloadWhileCopying() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "publicQdnPublishMaxSize", 8L, true);

		String name = "public-qdn-stream-size-test";
		registerName(name);

		assertApiError(ApiError.INVALID_DATA,
				() -> this.arbitraryResource.postUploadPublic(
						"APP", name, null, null, null, null, "index.html", 0L,
						null, false, false, stream("0123456789")));
	}

	@Test
	public void testPublicChunkFinalizeReturnsUnsignedTransaction() throws Exception {
		String name = "public-qdn-chunk-test";
		registerName(name);

		Response firstChunk = this.arbitraryResource.uploadChunkNoIdentifierPublic(
				"APP", name, stream("<html>chunk"), 0);
		Response secondChunk = this.arbitraryResource.uploadChunkNoIdentifierPublic(
				"APP", name, stream("ed</html>"), 1);
		assertEquals(200, firstChunk.getStatus());
		assertEquals(200, secondChunk.getStatus());

		String transaction = this.arbitraryResource.finalizeUploadNoIdentifierPublic(
				"APP", name, null, null, null, null, "index.html", 0L, null, false, false);
		assertUnsignedArbitraryTransaction(transaction);
	}

	@Test
	public void testStaleUploadTempReaperDeletesOldChunksOnly() throws Exception {
		long now = System.currentTimeMillis() + 2 * 60 * 60 * 1000L;
		Path staleChunkDirectory = Paths.get("uploads-temp", "APP", "stale-reaper-test", "old");
		Path activeChunkDirectory = Paths.get("uploads-temp", "APP", "stale-reaper-test", "active");

		try {
			Files.createDirectories(staleChunkDirectory);
			Files.createDirectories(activeChunkDirectory);
			Path staleChunk = staleChunkDirectory.resolve("chunk_0");
			Path activeChunk = activeChunkDirectory.resolve("chunk_0");
			Files.writeString(staleChunk, "old", StandardCharsets.UTF_8);
			Files.writeString(activeChunk, "active", StandardCharsets.UTF_8);

			FileTime oldTime = FileTime.fromMillis(now - 60 * 60 * 1000L);
			FileTime activeTime = FileTime.fromMillis(now);
			Files.setLastModifiedTime(staleChunk, oldTime);
			Files.setLastModifiedTime(staleChunkDirectory, oldTime);
			Files.setLastModifiedTime(activeChunk, activeTime);
			Files.setLastModifiedTime(activeChunkDirectory, activeTime);

			ArbitraryDataCleanupManager.getInstance().cleanupUploadsTempDirectory(now, 30 * 60 * 1000L);

			assertFalse(Files.exists(staleChunkDirectory));
			assertTrue(Files.exists(activeChunkDirectory));
		} finally {
			FileUtils.deleteDirectory(Paths.get("uploads-temp", "APP", "stale-reaper-test").toFile());
		}
	}

	@Test
	public void testPrivateQdnPublishBuildEndpointsStillRequireApiKey() {
		assertApiError(ApiError.UNAUTHORIZED,
				() -> this.arbitraryResource.postBase64EncodedData(null, "APP", "missing", null, null, null,
						null, "index.html", 0L, null, base64(new byte[] { 1, 2, 3 })));
		assertApiError(ApiError.UNAUTHORIZED,
				() -> this.arbitraryResource.postZippedData(null, "APP", "missing", null, null, null,
						null, 0L, null, null, base64(new byte[] { 1, 2, 3 })));
		assertApiError(ApiError.UNAUTHORIZED,
				() -> this.arbitraryResource.deleteResourceOnChain(null, Service.APP, "missing", "identifier", 0L));
	}

	private static void registerName(String name) throws Exception {
		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			RegisterNameTransactionData transactionData = new RegisterNameTransactionData(
					TestTransaction.generateBase(alice), name, "");
			transactionData.setFee(new RegisterNameTransaction(null, null).getUnitFee(transactionData.getTimestamp()));
			TransactionUtils.signAndMint(repository, transactionData, alice);
		}
	}

	private static void publishTestResource(String name, String identifier) throws Exception {
		Path path = ArbitraryUtils.generateRandomDataPath(32);
		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			ArbitraryUtils.createAndMintTxn(repository, Base58.encode(alice.getPublicKey()), path, name, identifier,
					org.qortium.data.transaction.ArbitraryTransactionData.Method.PUT, Service.APP, alice);
		}
	}

	private static void publishZeroByteTestResource(String name, String identifier) throws Exception {
		Path path = Files.createTempDirectory("qdn-download-zero-byte");
		Files.createFile(path.resolve("file.txt"));

		try (Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			ArbitraryUtils.createAndMintTxn(repository, Base58.encode(alice.getPublicKey()), path, name, identifier,
					org.qortium.data.transaction.ArbitraryTransactionData.Method.PUT, Service.APP, alice);
		} finally {
			FileUtils.deleteDirectory(path.toFile());
		}
	}

	private static void assertUnsignedArbitraryTransaction(String rawTransaction58) throws Exception {
		byte[] rawBytes = Base58.decode(rawTransaction58);
		assertTrue("Unsigned transaction bytes should not be empty", rawBytes.length > 0);

		TransactionData transactionData = TransactionTransformer.fromBytes(
				Bytes.concat(rawBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]));
		assertEquals(TransactionType.ARBITRARY, transactionData.getType());
	}

	@Test
	public void testAttachmentContentDispositionSanitizesHeaderValue() throws Exception {
		Method method = ArbitraryResource.class.getDeclaredMethod("buildAttachmentContentDisposition", String.class);
		method.setAccessible(true);

		String header = (String) method.invoke(null, "bad\r\nname<script>.txt");

		assertTrue(header.startsWith("attachment"));
		assertFalse(header.contains("\r"));
		assertFalse(header.contains("\n"));
		assertFalse(header.contains("<"));
		assertFalse(header.contains(">"));
		assertTrue(header.contains(".txt"));
	}

	@Test
	public void testAttachmentContentDispositionUsesFallbackExtension() throws Exception {
		Method method = ArbitraryResource.class.getDeclaredMethod("sanitizeAttachmentFilename", String.class);
		method.setAccessible(true);

		assertEquals("download.bin", method.invoke(null, ""));
		assertEquals("unsafename.bin", method.invoke(null, "unsafe/name"));
	}

	@Test
	public void testUploadChunkDirectoryStaysInsideUploadBase() throws Exception {
		Method method = ArbitraryResource.class.getDeclaredMethod("resolveUploadChunkDirectory", String.class, String.class, String.class);
		method.setAccessible(true);

		Path path = (Path) method.invoke(null, "APP", "QortiumHomeTest", "qortium-chat");

		assertEquals(
				FilesystemUtils.getUploadsTempPath().toAbsolutePath().normalize().resolve("APP").resolve("QortiumHomeTest").resolve("qortium-chat"),
				path
		);
	}

	@Test
	public void testUploadChunkDirectoryRejectsTraversalSegments() throws Exception {
		Method method = ArbitraryResource.class.getDeclaredMethod("resolveUploadChunkDirectory", String.class, String.class, String.class);
		method.setAccessible(true);

		assertInvocationThrowsIOException(method, "APP", "../outside", null);
		assertInvocationThrowsIOException(method, "APP", "name", "../outside");
	}

	@Test
	public void testUploadChunkFileRejectsNegativeIndex() throws Exception {
		Method method = ArbitraryResource.class.getDeclaredMethod("resolveUploadChunkFile", Path.class, int.class);
		method.setAccessible(true);

		assertInvocationThrowsIOException(method, Paths.get("uploads-temp"), -1);
	}

	@Test
	public void testUploadTempFileRejectsNestedFilename() throws Exception {
		Method method = ArbitraryResource.class.getDeclaredMethod("resolveUploadTempFile", Path.class, String.class);
		method.setAccessible(true);

		assertInvocationThrowsIOException(method, Paths.get("uploads-temp"), "nested/file.txt");
	}

	@Test
	public void testCopyUploadChunkReplacesExistingChunkWithinLimit() throws Exception {
		Path uploadDir = Files.createTempDirectory("qortium-upload-chunks");
		try {
			Path chunkFile = uploadDir.resolve("chunk_0");
			Files.write(chunkFile, "old".getBytes(StandardCharsets.UTF_8));

			copyUploadChunk("12345", chunkFile, 5);

			assertEquals("12345", Files.readString(chunkFile));
			assertNoTemporaryUploadChunks(uploadDir);
		} finally {
			FileUtils.deleteDirectory(uploadDir.toFile());
		}
	}

	@Test
	public void testCopyUploadChunkRejectsTotalUploadOverflow() throws Exception {
		Path uploadDir = Files.createTempDirectory("qortium-upload-chunks");
		try {
			Path existingChunkFile = uploadDir.resolve("chunk_0");
			Path rejectedChunkFile = uploadDir.resolve("chunk_1");
			Files.write(existingChunkFile, "1234".getBytes(StandardCharsets.UTF_8));

			try {
				copyUploadChunk("56", rejectedChunkFile, 5);
				org.junit.Assert.fail("Expected oversized upload chunk to be rejected");
			} catch (InvocationTargetException e) {
				assertEquals("UploadChunkTooLargeException", e.getCause().getClass().getSimpleName());
			}

			assertEquals("1234", Files.readString(existingChunkFile));
			assertFalse(Files.exists(rejectedChunkFile));
			assertNoTemporaryUploadChunks(uploadDir);
		} finally {
			FileUtils.deleteDirectory(uploadDir.toFile());
		}
	}

	@Test
	public void testHttpRangeParserSupportsStandardForms() throws Exception {
		assertArrayEquals(new long[] { 100, 200 }, parseHttpRange("bytes=100-200", 1000));
		assertArrayEquals(new long[] { 100, 999 }, parseHttpRange("bytes=100-", 1000));
		assertArrayEquals(new long[] { 500, 999 }, parseHttpRange("bytes=-500", 1000));
		assertArrayEquals(new long[] { 0, 999 }, parseHttpRange("bytes=-5000", 1000));
		assertArrayEquals(new long[] { 0, 999 }, parseHttpRange("bytes=0-9999", 1000));
		assertEquals(null, parseHttpRange(null, 1000));
		assertEquals(null, parseHttpRange(" ", 1000));
	}

	@Test
	public void testHttpRangeParserRejectsMalformedAndUnsatisfiableRanges() throws Exception {
		assertInvalidHttpRange("items=0-1", 1000);
		assertInvalidHttpRange("bytes=abc-", 1000);
		assertInvalidHttpRange("bytes=-", 1000);
		assertInvalidHttpRange("bytes=-0", 1000);
		assertInvalidHttpRange("bytes=100-99", 1000);
		assertInvalidHttpRange("bytes=1000-", 1000);
		assertInvalidHttpRange("bytes=0-1,2-3", 1000);
		assertInvalidHttpRange("bytes=0-0", 0);
	}

	@Test
	public void testRawDownloadContentTypeDowngradesExecutableHtml() throws Exception {
		assertEquals("application/octet-stream", getRawDownloadContentType("text/html"));
		assertEquals("application/octet-stream", getRawDownloadContentType("text/html; charset=UTF-8"));
		assertEquals("application/octet-stream", getRawDownloadContentType("application/xhtml+xml"));
		assertEquals("image/png", getRawDownloadContentType("image/png"));
		assertEquals("application/octet-stream", getRawDownloadContentType(null));
	}

	@Test
	public void testGetDownloadResponseUsesNoSniffHeaderForRawContent() throws Exception {
		String name = "qdn-nosniff-raw";
		registerName(name);
		publishTestResource(name, null);

		ApiCommon.installTestApiKey();
		try {
			ArbitraryResource resource = (ArbitraryResource) ApiCommon.buildResource(ArbitraryResource.class);
			DownloadExchange exchange = new DownloadExchange();
			exchange.requestHeaders.put("X-API-KEY", ApiCommon.TEST_API_KEY);
			FieldUtils.writeField(resource, "request", exchange.request, true);
			FieldUtils.writeField(resource, "response", exchange.response, true);
			FieldUtils.writeField(resource, "context", exchange.context, true);

			resource.get(Service.APP, name, null, null, false, false, 5, false, null);

			assertEquals("nosniff", exchange.getResponseHeader("X-Content-Type-Options"));
		} finally {
			ApiCommon.clearTestApiKey();
		}
	}

	@Test
	public void testGetDownloadResponseUsesNoSniffHeaderForZeroByteRawContent() throws Exception {
		String name = "qdn-nosniff-raw-zero-byte";
		registerName(name);
		publishZeroByteTestResource(name, null);

		ApiCommon.installTestApiKey();
		try {
			ArbitraryResource resource = (ArbitraryResource) ApiCommon.buildResource(ArbitraryResource.class);
			DownloadExchange exchange = new DownloadExchange();
			exchange.requestHeaders.put("X-API-KEY", ApiCommon.TEST_API_KEY);
			FieldUtils.writeField(resource, "request", exchange.request, true);
			FieldUtils.writeField(resource, "response", exchange.response, true);
			FieldUtils.writeField(resource, "context", exchange.context, true);

			resource.get(Service.APP, name, null, null, false, false, 5, false, null);

			assertEquals("nosniff", exchange.getResponseHeader("X-Content-Type-Options"));
		} finally {
			ApiCommon.clearTestApiKey();
		}
	}

	@Test
	public void testGetDownloadResponseUsesNoSniffHeaderForBase64Encoding() throws Exception {
		String name = "qdn-nosniff-base64";
		registerName(name);
		publishTestResource(name, null);

		ApiCommon.installTestApiKey();
		try {
			ArbitraryResource resource = (ArbitraryResource) ApiCommon.buildResource(ArbitraryResource.class);
			DownloadExchange exchange = new DownloadExchange();
			exchange.requestHeaders.put("X-API-KEY", ApiCommon.TEST_API_KEY);
			FieldUtils.writeField(resource, "request", exchange.request, true);
			FieldUtils.writeField(resource, "response", exchange.response, true);
			FieldUtils.writeField(resource, "context", exchange.context, true);

			resource.get(Service.APP, name, null, "base64", false, false, 5, false, null);

			assertEquals("nosniff", exchange.getResponseHeader("X-Content-Type-Options"));
		} finally {
			ApiCommon.clearTestApiKey();
		}
	}

	private static long[] parseHttpRange(String rangeHeader, long fileSize) throws Exception {
		Method method = ArbitraryResource.class.getDeclaredMethod("parseHttpRangeHeader", String.class, long.class);
		method.setAccessible(true);
		return (long[]) method.invoke(null, rangeHeader, fileSize);
	}

	private static String getRawDownloadContentType(String mimeType) throws Exception {
		Method method = ArbitraryResource.class.getDeclaredMethod("getRawDownloadContentType", String.class);
		method.setAccessible(true);
		return (String) method.invoke(null, mimeType);
	}

	private static class DownloadExchange {
		private final Map<String, String> requestHeaders = new LinkedHashMap<>();
		private final Map<String, String> responseHeaders = new LinkedHashMap<>();
		private final CapturingServletOutputStream outputStream;
		private final HttpServletRequest request;
		private final HttpServletResponse response;
		private final ServletContext context;
		private boolean responseCommitted;
		private boolean outputStreamUsed;
		private boolean writerUsed;

		private DownloadExchange() {
			this.outputStream = new CapturingServletOutputStream(() -> this.responseCommitted = true);

			this.request = (HttpServletRequest) Proxy.newProxyInstance(
					ArbitraryApiTests.class.getClassLoader(),
					new Class[] { HttpServletRequest.class },
					(proxy, method, args) -> {
						switch (method.getName()) {
							case "getMethod":
								return "GET";
							case "getHeaderNames":
								return Collections.enumeration(this.requestHeaders.keySet());
							case "getHeader":
								return this.requestHeaders.get((String) args[0]);
							case "getQueryString":
								return null;
							case "getParameter":
								return null;
							case "getLocale":
								return Locale.getDefault();
							case "getRequestURI":
								return "";
							case "toString":
								return "ArbitraryApiTestRequest";
							default:
								return defaultValue(method.getReturnType());
						}
					});

			this.response = (HttpServletResponse) Proxy.newProxyInstance(
					ArbitraryApiTests.class.getClassLoader(),
					new Class[] { HttpServletResponse.class },
					(proxy, method, args) -> {
						switch (method.getName()) {
							case "setHeader":
								this.responseHeaders.put((String) args[0], (String) args[1]);
								return null;
							case "addHeader":
								this.responseHeaders.put((String) args[0], (String) args[1]);
								return null;
							case "setContentType":
								this.responseHeaders.put("Content-Type", (String) args[0]);
								return null;
							case "setContentLength":
								return null;
							case "setContentLengthLong":
								return null;
							case "setStatus":
								return null;
							case "getOutputStream":
								if (this.writerUsed) {
									throw new IllegalStateException("getOutputStream() called after getWriter()");
								}
								this.outputStreamUsed = true;
								return this.outputStream;
							case "getWriter":
								if (this.outputStreamUsed) {
									throw new IllegalStateException("getWriter() called after getOutputStream()");
								}
								this.writerUsed = true;
								return new PrintWriter(this.outputStream.outputStream, true);
							case "isCommitted":
								return this.responseCommitted;
							case "toString":
								return "ArbitraryApiTestResponse";
							default:
								return defaultValue(method.getReturnType());
						}
					});

			this.context = (ServletContext) Proxy.newProxyInstance(
					ArbitraryApiTests.class.getClassLoader(),
					new Class[] { ServletContext.class },
					(proxy, method, args) -> {
						switch (method.getName()) {
							case "getMimeType":
								return null;
							default:
								return defaultValue(method.getReturnType());
						}
					});
		}

		private String getResponseHeader(String headerName) {
			for (Map.Entry<String, String> entry : this.responseHeaders.entrySet()) {
				if (entry.getKey().equalsIgnoreCase(headerName))
					return entry.getValue();
			}

			return null;
		}
	}

	private static class CapturingServletOutputStream extends ServletOutputStream {

		private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		private final Runnable onWrite;

		private CapturingServletOutputStream(Runnable onWrite) {
			this.onWrite = onWrite;
		}

		@Override
		public void write(int b) {
			this.outputStream.write(b);
			if (this.onWrite != null) {
				this.onWrite.run();
			}
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setWriteListener(WriteListener writeListener) {
		}
	}

	private static Object defaultValue(Class<?> returnType) {
		if (returnType == boolean.class)
			return false;

		if (returnType == int.class)
			return 0;

		if (returnType == long.class)
			return 0L;

		return null;
	}

	private static void copyUploadChunk(String chunkData, Path chunkFile, long maxTotalSize) throws Exception {
		Method method = ArbitraryResource.class.getDeclaredMethod("copyUploadChunk", java.io.InputStream.class, Path.class, long.class);
		method.setAccessible(true);
		method.invoke(null, new ByteArrayInputStream(chunkData.getBytes(StandardCharsets.UTF_8)), chunkFile, maxTotalSize);
	}

	private static void assertNoTemporaryUploadChunks(Path uploadDir) throws IOException {
		try (Stream<Path> paths = Files.list(uploadDir)) {
			assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".chunk-upload-")));
		}
	}

	private static void assertInvalidHttpRange(String rangeHeader, long fileSize) throws Exception {
		Method method = ArbitraryResource.class.getDeclaredMethod("parseHttpRangeHeader", String.class, long.class);
		method.setAccessible(true);

		try {
			method.invoke(null, rangeHeader, fileSize);
			org.junit.Assert.fail("Expected invalid HTTP range: " + rangeHeader);
		} catch (InvocationTargetException e) {
			assertEquals("InvalidHttpRangeException", e.getCause().getClass().getSimpleName());
		}
	}

	private static void assertInvocationThrowsIOException(Method method, Object... args) throws Exception {
		try {
			method.invoke(null, args);
			org.junit.Assert.fail("Expected IOException");
		} catch (InvocationTargetException e) {
			assertTrue(e.getCause() instanceof IOException);
		}
	}

}
