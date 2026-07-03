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
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.BlockArchiveReader;
import org.qortium.repository.BlockArchiveWriter;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.NTP;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
		assertFalse(ArchiveChunkImporter.isChunkHeaderValid(new byte[4], 2, 250));
		assertFalse(ArchiveChunkImporter.isChunkHeaderValid(null, 2, 250));
	}

	@Test
	public void testReplayPersistsArchivedTransactionsBeforeBlockLink() throws Exception {
		byte[] paymentSignature;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TransactionData paymentData = TransactionUtils.randomTransaction(repository,
					Common.getTestAccount(repository, "alice"), TransactionType.PAYMENT, true);
			TransactionUtils.signAndMint(repository, paymentData, Common.getTestAccount(repository, "alice"));
			paymentSignature = paymentData.getSignature();

			assertEquals("payment block height before archive", 2,
					repository.getTransactionRepository().fromSignature(paymentSignature).getBlockHeight().intValue());

			for (int height = repository.getBlockRepository().getBlockchainHeight(); height < 300; height++)
				BlockMinter.mintTestingBlock(repository, Common.getTestAccount(repository, "alice-reward-share"));

			BlockArchiveWriter writer = new BlockArchiveWriter(0, 250, repository);
			writer.setShouldEnforceFileSizeTarget(false);
			writer.write();

			BlockUtils.orphanToBlock(repository, 1);
			TransactionData orphanedPayment = repository.getTransactionRepository().fromSignature(paymentSignature);
			assertNotNull("orphaning should leave the payment transaction as unconfirmed", orphanedPayment);
			assertNull("orphaned payment transaction should no longer be linked to a block", orphanedPayment.getBlockHeight());

			// A fresh archive fast-sync database will not have this transaction preloaded as unconfirmed.
			// Remove it so replay has to recreate the normal sync invariant before block linking.
			repository.getTransactionRepository().delete(orphanedPayment);
			repository.saveChanges();
			assertNull("payment transaction should be absent before archive replay",
					repository.getTransactionRepository().fromSignature(paymentSignature));

			BlockArchiveReader.getInstance().invalidateFileListCache();
			List<Integer> progressHeights = new ArrayList<>();
			// Use trusted replay above this range so this regression isolates the transaction persistence
			// invariant instead of re-testing online-account nonce validation.
			int replayedHeight = ArchiveChunkImporter.replayArchivedBlocks(repository, 2, 250, 251, progressHeights::add);
			repository.saveChanges();

			assertEquals(250, replayedHeight);
			assertFalse("archive replay should report progress", progressHeights.isEmpty());
			assertEquals(Integer.valueOf(250), progressHeights.get(progressHeights.size() - 1));
			assertEquals(250, repository.getBlockRepository().getBlockchainHeight());

			TransactionData replayedPayment = repository.getTransactionRepository().fromSignature(paymentSignature);
			assertNotNull("archived payment transaction should be saved before block link", replayedPayment);
			assertEquals(2, replayedPayment.getBlockHeight().intValue());
			TransactionData firstTransactionAtHeight = repository.getTransactionRepository().fromHeightAndSequence(2, 0);
			assertNotNull("archived payment transaction should be linked at height 2 sequence 0", firstTransactionAtHeight);
			assertArrayEquals(paymentSignature, firstTransactionAtHeight.getSignature());
		}
	}

	@Test
	public void testReplayCancellationCanBeRolledBack() throws Exception {
		mintAndReadChunkBytes();

		try (final Repository repository = RepositoryManager.getRepository()) {
			BlockUtils.orphanToBlock(repository, 1);
			assertEquals(1, repository.getBlockRepository().getBlockchainHeight());

			AtomicInteger cancellationChecks = new AtomicInteger();
			repository.setSavepoint();
			try {
				ArchiveChunkImporter.replayArchivedBlocks(repository, 2, 250, 251, null,
						() -> cancellationChecks.incrementAndGet() > 10);
				fail("archive replay should stop when cancellation is requested");
			} catch (DataException e) {
				assertTrue(e.getMessage().contains("Archive fast-replay interrupted"));
				repository.rollbackToSavepoint();
				repository.discardChanges();
			}

			assertEquals("cancelled archive replay must not leave partial blocks committed", 1,
					repository.getBlockRepository().getBlockchainHeight());
		}
	}

	@Test
	public void testReplayCanResumeAfterCommittedSegment() throws Exception {
		mintAndReadChunkBytes();

		try (final Repository repository = RepositoryManager.getRepository()) {
			BlockUtils.orphanToBlock(repository, 1);
			assertEquals(1, repository.getBlockRepository().getBlockchainHeight());

			int firstSegmentHeight = ArchiveChunkImporter.replayArchivedBlocks(repository, 2, 50, 251);
			repository.saveChanges();

			assertEquals(50, firstSegmentHeight);
			assertEquals(50, repository.getBlockRepository().getBlockchainHeight());

			int finalHeight = ArchiveChunkImporter.replayArchivedBlocks(repository, 51, 250, 251);
			repository.saveChanges();

			assertEquals(250, finalHeight);
			assertEquals(250, repository.getBlockRepository().getBlockchainHeight());
		}
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
