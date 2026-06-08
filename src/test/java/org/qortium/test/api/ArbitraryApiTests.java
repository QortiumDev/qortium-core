package org.qortium.test.api;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.resource.ArbitraryResource;
import org.qortium.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortium.arbitrary.misc.Service;
import org.qortium.test.common.ApiCommon;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ArbitraryApiTests extends ApiCommon {

	private ArbitraryResource arbitraryResource;

	@Before
	public void buildResource() {
		this.arbitraryResource = (ArbitraryResource) ApiCommon.buildResource(ArbitraryResource.class);
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

	private static long[] parseHttpRange(String rangeHeader, long fileSize) throws Exception {
		Method method = ArbitraryResource.class.getDeclaredMethod("parseHttpRangeHeader", String.class, long.class);
		method.setAccessible(true);
		return (long[]) method.invoke(null, rangeHeader, fileSize);
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
