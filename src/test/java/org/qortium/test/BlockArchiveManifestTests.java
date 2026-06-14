package org.qortium.test;

import com.google.common.hash.HashCode;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.controller.BlockMinter;
import org.qortium.crypto.Crypto;
import org.qortium.data.block.ArchiveChunkData;
import org.qortium.data.block.ArchiveManifest;
import org.qortium.repository.BlockArchiveReader;
import org.qortium.repository.BlockArchiveWriter;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.transform.TransformationException;
import org.qortium.utils.NTP;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Tests the block-archive chunk/manifest read API used by archive distribution:
 * chunk enumeration, raw chunk bytes, and the reproducible manifest (range + SHA-256 + size).
 */
public class BlockArchiveManifestTests extends Common {

	@Before
	public void beforeTest() throws DataException, IllegalAccessException {
		Common.useSettings("test-settings-block-archive.json");
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
		this.deleteArchiveDirectory();
		BlockArchiveReader.getInstance().invalidateFileListCache();

		// Pin the archive version so the manifest format is the Qortium baseline.
		FieldUtils.writeField(Settings.getInstance(), "defaultArchiveVersion", 1, true);
	}

	@After
	public void afterTest() throws DataException {
		this.deleteArchiveDirectory();
		BlockArchiveReader.getInstance().invalidateFileListCache();
	}

	@Test
	public void testManifestMatchesArchiveChunks() throws DataException, InterruptedException, TransformationException, IOException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Mint enough blocks to archive into two chunks.
			for (int i = 0; i < 500; i++)
				BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));

			// Write two archive chunks: blocks 2-250 and 251-450 (file size target disabled so a
			// fixed, predictable range goes into each file).
			writeChunk(repository, 0, 250);   // start clamps to 2 -> "2-250.dat"
			writeChunk(repository, 251, 450); // -> "251-450.dat"

			BlockArchiveReader reader = BlockArchiveReader.getInstance();
			reader.invalidateFileListCache();

			// Chunk ranges are enumerated, sorted ascending by start height.
			List<int[]> ranges = reader.getArchiveChunkRanges();
			assertEquals(2, ranges.size());
			assertArrayEquals(new int[] { 2, 250 }, ranges.get(0));
			assertArrayEquals(new int[] { 251, 450 }, ranges.get(1));

			// Raw chunk bytes match the on-disk file; an unknown start height returns null.
			Path firstChunkPath = Paths.get(Settings.getInstance().getRepositoryPath(), "archive", "2-250.dat");
			byte[] expectedFirstChunk = Files.readAllBytes(firstChunkPath);
			assertArrayEquals(expectedFirstChunk, reader.fetchRawChunkBytesForStartHeight(2));
			assertNull(reader.fetchRawChunkBytesForStartHeight(9999));

			// Manifest: one entry per chunk, each with the correct range, size and SHA-256.
			ArchiveManifest manifest = reader.buildArchiveManifest();
			assertEquals(BlockArchiveReader.SUPPORTED_ARCHIVE_VERSION, manifest.getArchiveVersion());
			assertEquals(2, manifest.getChunks().size());

			ArchiveChunkData firstChunk = manifest.getChunks().get(0);
			assertEquals(2, firstChunk.getStartHeight());
			assertEquals(250, firstChunk.getEndHeight());
			assertEquals(expectedFirstChunk.length, firstChunk.getSize());
			assertEquals(HashCode.fromBytes(Crypto.digest(expectedFirstChunk)).toString(), firstChunk.getSha256());

			ArchiveChunkData secondChunk = manifest.getChunks().get(1);
			assertEquals(251, secondChunk.getStartHeight());
			assertEquals(450, secondChunk.getEndHeight());
		}
	}

	@Test
	public void testManifestIsReproducible() throws DataException, InterruptedException, TransformationException, IOException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			for (int i = 0; i < 300; i++)
				BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));

			writeChunk(repository, 0, 250);

			BlockArchiveReader reader = BlockArchiveReader.getInstance();
			reader.invalidateFileListCache();

			// Building the manifest twice over the same archive yields an identical result — the
			// basis for content-addressed, multi-source chunk distribution and trusted fast-replay.
			ArchiveManifest first = reader.buildArchiveManifest();
			reader.invalidateFileListCache();
			ArchiveManifest second = reader.buildArchiveManifest();

			assertEquals(first, second);
			assertEquals(first.getChunks(), second.getChunks());
		}
	}

	private void writeChunk(Repository repository, int startHeight, int endHeight)
			throws DataException, InterruptedException, TransformationException, IOException {
		BlockArchiveWriter writer = new BlockArchiveWriter(startHeight, endHeight, repository);
		writer.setShouldEnforceFileSizeTarget(false);
		assertEquals(BlockArchiveWriter.BlockArchiveWriteResult.OK, writer.write());
	}

	private void deleteArchiveDirectory() {
		Path archivePath = Paths.get(Settings.getInstance().getRepositoryPath(), "archive").toAbsolutePath();
		try {
			FileUtils.deleteDirectory(archivePath.toFile());
		} catch (IOException e) {
			// Ignore cleanup failures
		}
	}
}
