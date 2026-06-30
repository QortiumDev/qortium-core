package org.qortium.test;

import com.google.common.hash.HashCode;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.controller.BlockMinter;
import org.qortium.controller.repository.ArchiveChunkImporter;
import org.qortium.crypto.Crypto;
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
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Integrity-gate tests for the archive-chunk fast-sync importer: a downloaded chunk is content-addressed
 * (its bytes must hash to the value the untrusted manifest advertises) and its fixed header must match the
 * declared range. These are the checks that defeat a tampered or lying chunk before it is ever written or
 * replayed. (Replay/state-building and the network requester are covered separately.)
 */
public class ArchiveChunkImporterTests extends Common {

	@Before
	public void beforeTest() throws DataException, IllegalAccessException {
		Common.useSettings("test-settings-block-archive.json");
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
		this.deleteArchiveDirectory();
		BlockArchiveReader.getInstance().invalidateFileListCache();
		FieldUtils.writeField(Settings.getInstance(), "defaultArchiveVersion", 1, true);
	}

	@After
	public void afterTest() throws DataException {
		this.deleteArchiveDirectory();
		BlockArchiveReader.getInstance().invalidateFileListCache();
	}

	@Test
	public void testGenuineChunkHashAccepted() throws Exception {
		byte[] chunkBytes = mintAndReadChunkBytes();
		String genuineSha256 = HashCode.fromBytes(Crypto.digest(chunkBytes)).toString();

		assertTrue("a chunk must verify against its own SHA-256", ArchiveChunkImporter.isChunkHashValid(chunkBytes, genuineSha256));
		// Case-insensitive on the hex.
		assertTrue(ArchiveChunkImporter.isChunkHashValid(chunkBytes, genuineSha256.toUpperCase()));
	}

	@Test
	public void testTamperedChunkRejected() throws Exception {
		byte[] chunkBytes = mintAndReadChunkBytes();
		String genuineSha256 = HashCode.fromBytes(Crypto.digest(chunkBytes)).toString();

		// Flip a single byte deep in the data segment: the hash must no longer match.
		byte[] tampered = chunkBytes.clone();
		tampered[tampered.length / 2] ^= 0x01;
		assertFalse("a tampered chunk must fail the SHA-256 check", ArchiveChunkImporter.isChunkHashValid(tampered, genuineSha256));

		// A null/garbage expected hash is also rejected.
		assertFalse(ArchiveChunkImporter.isChunkHashValid(chunkBytes, null));
		assertFalse(ArchiveChunkImporter.isChunkHashValid(chunkBytes, "not-a-real-hash"));
		assertFalse(ArchiveChunkImporter.isChunkHashValid(null, genuineSha256));
	}

	@Test
	public void testChunkHeaderValidation() throws Exception {
		byte[] chunkBytes = mintAndReadChunkBytes(); // genuine "2-250.dat"

		// Correct version + declared range passes.
		assertTrue(ArchiveChunkImporter.isChunkHeaderValid(chunkBytes, 2, 250));

		// Wrong declared start/end (a peer claiming a different range than the file actually holds) fails.
		assertFalse(ArchiveChunkImporter.isChunkHeaderValid(chunkBytes, 2, 251));
		assertFalse(ArchiveChunkImporter.isChunkHeaderValid(chunkBytes, 3, 250));

		// Corrupt the version field (first int) -> rejected.
		byte[] badVersion = chunkBytes.clone();
		badVersion[0] = (byte) 0x7F;
		badVersion[1] = (byte) 0xFF;
		assertFalse(ArchiveChunkImporter.isChunkHeaderValid(badVersion, 2, 250));

		// Too-short buffer -> rejected, not an exception.
		assertFalse(ArchiveChunkImporter.isChunkHeaderValid(new byte[0], 2, 250));
		assertFalse(ArchiveChunkImporter.isChunkHeaderValid(new byte[1], 2, 250));
		assertFalse(ArchiveChunkImporter.isChunkHeaderValid(new byte[4], 2, 250));
		assertFalse(ArchiveChunkImporter.isChunkHeaderValid(new byte[19], 2, 250));
		assertFalse(ArchiveChunkImporter.isChunkHeaderValid(null, 2, 250));

		// Exactly the fixed header size is enough for this header-only integrity check.
		ByteBuffer minimalHeader = ByteBuffer.allocate(5 * Integer.BYTES);
		minimalHeader.putInt(BlockArchiveReader.SUPPORTED_ARCHIVE_VERSION);
		minimalHeader.putInt(2);
		minimalHeader.putInt(250);
		minimalHeader.putInt(249);
		minimalHeader.putInt(0);
		assertTrue(ArchiveChunkImporter.isChunkHeaderValid(minimalHeader.array(), 2, 250));
	}

	/** Mint enough blocks to archive a single deterministic chunk "2-250.dat" and return its raw bytes. */
	private byte[] mintAndReadChunkBytes() throws DataException, InterruptedException, TransformationException, IOException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			for (int i = 0; i < 300; i++)
				BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));

			BlockArchiveWriter writer = new BlockArchiveWriter(0, 250, repository);
			writer.setShouldEnforceFileSizeTarget(false);
			writer.write();
		}

		BlockArchiveReader.getInstance().invalidateFileListCache();
		Path chunkPath = Paths.get(Settings.getInstance().getRepositoryPath(), "archive", "2-250.dat");
		return Files.readAllBytes(chunkPath);
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
