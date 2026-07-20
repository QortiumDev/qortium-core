package org.qortium.controller.arbitrary;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.arbitrary.ArbitraryDataFolderSizeEstimator;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArbitraryDataFileManagerTests {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testTimedOutChunkRequestCanRetrySamePeer() {
		ArbitraryDataFileManager manager = ArbitraryDataFileManager.getInstance();
		String signature58 = "retrySignature";
		String hash58 = "retryHash";
		String peerAddress = "127.0.0.1:12345";
		long now = 1_000_000L;

		manager.clearTriedPeersForSignature(signature58);
		manager.arbitraryDataFileRequests.remove(hash58);
		manager.arbitraryDataFileRequests.put(hash58, now - ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT - 1);
		manager.recordChunkRequested(hash58, signature58, peerAddress);

		assertEquals(1, manager.getInFlightRequestCountForSignature(signature58));
		assertTrue(manager.getTriedPeersForChunk(signature58, hash58).contains(peerAddress));

		manager.cleanupRequestCache(now);

		assertEquals(0, manager.getInFlightRequestCountForSignature(signature58));
		assertFalse(manager.getTriedPeersForChunk(signature58, hash58).contains(peerAddress));
	}

	@Test
	public void testInFlightRequestCountIsScopedBySignature() {
		ArbitraryDataFileManager manager = ArbitraryDataFileManager.getInstance();
		String signatureA = "signatureA";
		String signatureB = "signatureB";

		manager.clearTriedPeersForSignature(signatureA);
		manager.clearTriedPeersForSignature(signatureB);
		manager.recordChunkRequested("hashA1", signatureA, "127.0.0.1:10001");
		manager.recordChunkRequested("hashA2", signatureA, "127.0.0.1:10002");
		manager.recordChunkRequested("hashB1", signatureB, "127.0.0.1:10003");

		try {
			assertEquals(2, manager.getInFlightRequestCountForSignature(signatureA));
			assertEquals(1, manager.getInFlightRequestCountForSignature(signatureB));
		} finally {
			manager.clearChunkReceived("hashA1", signatureA);
			manager.clearChunkReceived("hashA2", signatureA);
			manager.clearChunkReceived("hashB1", signatureB);
		}
	}

	@Test
	public void testRelayCacheWriteRespectsHeadroomAndTracksOverwriteDeltas() throws Exception {
		ArbitraryDataFileManager manager = ArbitraryDataFileManager.getInstance();
		ArbitraryDataStorageManager storageManager = ArbitraryDataStorageManager.getInstance();
		Path testRoot = Files.createTempDirectory("relay-cache-capacity");
		Path relayCacheDir = testRoot.resolve("relay-cache");
		Files.createDirectories(relayCacheDir);

		Object originalRelayCacheDir = FieldUtils.readField(manager, "relayCacheDir", true);
		java.util.concurrent.atomic.AtomicInteger fileCount =
				(java.util.concurrent.atomic.AtomicInteger) FieldUtils.readField(manager, "relayCacheFileCount", true);
		int originalFileCount = fileCount.get();
		java.util.concurrent.atomic.AtomicLong cacheSize =
				(java.util.concurrent.atomic.AtomicLong) FieldUtils.readField(manager, "relayCacheSize", true);
		long originalCacheSize = cacheSize.get();
		int originalCleanupTrigger = (Integer) FieldUtils.readField(manager, "RELAY_CACHE_CLEANUP_TRIGGER", true);
		Object originalStorageCapacity = FieldUtils.readField(storageManager, "storageCapacity", true);
		long originalEstimate = ArbitraryDataFolderSizeEstimator.getInstance().get();

		try {
			FieldUtils.writeField(manager, "relayCacheDir", relayCacheDir, true);
			fileCount.set(0);
			cacheSize.set(0L);
			FieldUtils.writeField(storageManager, "storageCapacity", 1_000L, true);
			ArbitraryDataFolderSizeEstimator.getInstance().set(800L);

			assertFalse(manager.saveToRelayCache("blocked", new byte[] { 1 }));
			assertFalse(Files.exists(relayCacheDir.resolve("blocked.tmp")));
			assertEquals(800L, ArbitraryDataFolderSizeEstimator.getInstance().get());

			ArbitraryDataFolderSizeEstimator.getInstance().set(700L);
			assertTrue(manager.saveToRelayCache("allowed", new byte[] { 1, 2, 3, 4, 5 }));
			assertEquals(705L, ArbitraryDataFolderSizeEstimator.getInstance().get());

			assertTrue(manager.saveToRelayCache("allowed", new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }));
			assertEquals(708L, ArbitraryDataFolderSizeEstimator.getInstance().get());

			assertFalse(manager.saveToRelayCache("allowed", new byte[20]));
			assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 },
					Files.readAllBytes(relayCacheDir.resolve("allowed.tmp")));
			assertEquals(708L, ArbitraryDataFolderSizeEstimator.getInstance().get());

			assertTrue(manager.saveToRelayCache("allowed", new byte[] { 1, 2, 3, 4 }));
			assertEquals(704L, ArbitraryDataFolderSizeEstimator.getInstance().get());

			// A failed atomic replacement must preserve the destination and remove staging files.
			Files.createDirectory(relayCacheDir.resolve("collision.tmp"));
			assertFalse(manager.saveToRelayCache("collision", new byte[] { 9 }));
			assertTrue(Files.isDirectory(relayCacheDir.resolve("collision.tmp")));
			try (Stream<Path> paths = Files.list(relayCacheDir)) {
				assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".part")));
			}
			assertEquals(704L, ArbitraryDataFolderSizeEstimator.getInstance().get());

			Path staleStagingFile = relayCacheDir.resolve("crash-orphan.part");
			Files.write(staleStagingFile, new byte[] { 6, 7, 8 });
			ArbitraryDataFolderSizeEstimator.getInstance().set(707L);
			java.lang.reflect.Method cleanupMethod = ArbitraryDataFileManager.class
					.getDeclaredMethod("cleanupRelayCache");
			cleanupMethod.setAccessible(true);
			cleanupMethod.invoke(manager);
			assertFalse(Files.exists(staleStagingFile));
			assertEquals(704L, ArbitraryDataFolderSizeEstimator.getInstance().get());

			assertTrue(manager.eraseRelayCache());
			assertEquals(700L, ArbitraryDataFolderSizeEstimator.getInstance().get());
		} finally {
			FieldUtils.writeField(manager, "relayCacheDir", originalRelayCacheDir, true);
			fileCount.set(originalFileCount);
			cacheSize.set(originalCacheSize);
			FieldUtils.writeField(manager, "RELAY_CACHE_CLEANUP_TRIGGER", originalCleanupTrigger, true);
			FieldUtils.writeField(storageManager, "storageCapacity", originalStorageCapacity, true);
			ArbitraryDataFolderSizeEstimator.getInstance().set(originalEstimate);
			FileUtils.deleteQuietly(testRoot.toFile());
		}
	}
}
