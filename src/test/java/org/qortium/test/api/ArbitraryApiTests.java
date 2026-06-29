package org.qortium.test.api;

import com.google.common.primitives.Bytes;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.ApiError;
import org.qortium.api.resource.ArbitraryResource;
import org.qortium.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortium.arbitrary.ArbitraryDataResource;
import org.qortium.arbitrary.misc.Service;
import org.qortium.controller.Controller;
import org.qortium.controller.arbitrary.ArbitraryDataRenderManager;
import org.qortium.data.transaction.RegisterNameTransactionData;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
				Paths.get("uploads-temp").toAbsolutePath().normalize().resolve("APP").resolve("QortiumHomeTest").resolve("qortium-chat"),
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
