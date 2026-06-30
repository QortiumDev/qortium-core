package org.qortium.controller.repository;

import com.google.common.hash.HashCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.block.Block;
import org.qortium.crypto.Crypto;
import org.qortium.data.block.BlockData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.BlockArchiveReader;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.settings.Settings;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.block.BlockTransformation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Verifies and imports a downloaded block-archive {@code .dat} chunk, then replays its blocks into the
 * repository to build derived chain state.
 * <p>
 * This is the consumer half of the archive-chunk fast-sync. The trust model (see {@code docs}/design):
 * <ul>
 *   <li>The chunk is <b>content-addressed</b>: its bytes must hash (SHA-256, same recipe as
 *       {@link BlockArchiveReader#buildArchiveManifest()}) to the value the (untrusted) manifest advertises.
 *       A tampered chunk fails this and is discarded.</li>
 *   <li>The release-pinned <b>checkpoint</b> is the only trust anchor. Blocks strictly below the checkpoint
 *       height may be replayed with only the expensive <em>online-account</em> signature + memory-PoW crypto
 *       skipped ("trusted fast-replay", see {@link Block#areOnlineAccountsValid(boolean)}). The block
 *       <em>signature</em> — and every transaction's own signature — is STILL verified at every height via
 *       {@link Block#isSignatureValid()}. This is essential: the block reference/signature chain that the
 *       checkpoint cross-binds commits only to stored signature bytes and minter/online-account identity, NOT
 *       to derived state or transaction content, so without per-block signature verification an attacker could
 *       preserve genuine block signatures up to the checkpoint while substituting forged transactions below it.
 *       The online-account set itself is committed by the (verified) minter signature, so skipping the
 *       per-account consent signatures below a checkpoint the real chain already established is safe.</li>
 *   <li>Blocks at and above the checkpoint are <b>fully validated</b>, exactly like normal sync.</li>
 *   <li>Derived state is always recomputed locally via {@link Block#process()}; no state blob is ever trusted.</li>
 * </ul>
 * Stateless utility; all methods are safe to unit-test without the network. Callers (the fast-sync manager)
 * are responsible for holding the blockchain lock and for the manifest/checkpoint cross-binding.
 */
public class ArchiveChunkImporter {

	private static final Logger LOGGER = LogManager.getLogger(ArchiveChunkImporter.class);
	private static final int PROGRESS_LOG_BLOCK_INTERVAL = 500;
	private static final long PROGRESS_LOG_TIME_INTERVAL_MS = 30_000L;

	@FunctionalInterface
	public interface ReplayProgressListener {
		void onReplayProgress(int height, int fromHeight, int toHeight);
	}

	private ArchiveChunkImporter() {
	}

	/**
	 * Returns whether {@code chunkBytes} hashes to {@code expectedSha256Hex}, using the identical recipe the
	 * serving side uses in {@link BlockArchiveReader#buildArchiveManifest()}
	 * ({@code HashCode.fromBytes(Crypto.digest(bytes)).toString()}). Case-insensitive on the hex.
	 */
	public static boolean isChunkHashValid(byte[] chunkBytes, String expectedSha256Hex) {
		if (chunkBytes == null || expectedSha256Hex == null)
			return false;

		String actual = HashCode.fromBytes(Crypto.digest(chunkBytes)).toString();
		return actual.equalsIgnoreCase(expectedSha256Hex);
	}

	/**
	 * Sanity-checks the fixed {@code .dat} header (version, startHeight, endHeight) against the declared range.
	 * The on-disk format begins with five big-endian ints: version, startHeight, endHeight, blockCount,
	 * variableHeaderLength (see {@code BlockArchiveWriter}/{@code BlockArchiveReader}).
	 */
	public static boolean isChunkHeaderValid(byte[] chunkBytes, int expectedStartHeight, int expectedEndHeight) {
		if (chunkBytes == null || chunkBytes.length < 5 * Integer.BYTES)
			return false;

		ByteBuffer buffer = ByteBuffer.wrap(chunkBytes); // big-endian by default, matching DataOutputStream.writeInt
		int version = buffer.getInt();
		int startHeight = buffer.getInt();
		int endHeight = buffer.getInt();

		return version == BlockArchiveReader.SUPPORTED_ARCHIVE_VERSION
				&& startHeight == expectedStartHeight
				&& endHeight == expectedEndHeight;
	}

	/**
	 * Atomically writes a verified chunk to the local archive directory as {@code <start>-<end>.dat} and
	 * invalidates the reader's file-list cache so the new file is picked up. The caller MUST have verified
	 * {@link #isChunkHashValid} and {@link #isChunkHeaderValid} first.
	 */
	public static void writeChunkFile(int startHeight, int endHeight, byte[] chunkBytes) throws IOException {
		Path archivePath = Paths.get(Settings.getInstance().getRepositoryPath(), "archive").toAbsolutePath();
		Files.createDirectories(archivePath);

		Path target = archivePath.resolve(String.format("%d-%d.dat", startHeight, endHeight));
		Path temp = archivePath.resolve(String.format(".%d-%d.dat.tmp", startHeight, endHeight));

		Files.write(temp, chunkBytes);
		try {
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			// Fall back to a non-atomic move if the filesystem doesn't support ATOMIC_MOVE+REPLACE together.
			LOGGER.warn("Archive chunk {}-{} atomic move failed ({}); falling back to replace-existing move",
					startHeight, endHeight, e.getClass().getSimpleName());
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temp);
		}

		BlockArchiveReader.getInstance().invalidateFileListCache();
		LOGGER.debug("Imported archive chunk {}-{} ({} bytes)", startHeight, endHeight, chunkBytes.length);
	}

	/**
	 * Replays archived blocks {@code [fromHeight, toHeight]} (already present in the local archive) into the
	 * repository, building derived state via {@link Block#process()}.
	 * <p>
	 * For each block strictly below {@code trustedCheckpointHeight} (when that is &gt; 0), the expensive
	 * online-account signature / memory-PoW crypto is skipped by calling {@link Block#isValid(boolean)} with
	 * {@code trustedReplay = true}. The block minter signature and every transaction signature are still checked
	 * via {@link Block#isSignatureValid()} at every height. At and above the checkpoint, full validation runs.
	 * Pass {@code trustedCheckpointHeight = 0} to fully validate every block (no skipping).
	 * <p>
	 * This method does <b>not</b> commit: it leaves all changes pending in the repository transaction so the
	 * caller can apply the whole range atomically. The caller <b>must</b> wrap the call in
	 * {@code repository.setSavepoint()} and then either {@code saveChanges()} on success or
	 * {@code rollbackToSavepoint()} on the {@code DataException} thrown by the first invalid block. This
	 * all-or-nothing discipline is what makes the checkpoint cross-bind sound: no sub-checkpoint block is ever
	 * durably committed unless the (fully validated) checkpoint block in the same range also validates, so a
	 * forged sub-checkpoint prefix that fails at the checkpoint is rolled back rather than left on disk.
	 *
	 * @return the height successfully replayed up to
	 */
	public static int replayArchivedBlocks(Repository repository, int fromHeight, int toHeight, int trustedCheckpointHeight) throws DataException {
		return replayArchivedBlocks(repository, fromHeight, toHeight, trustedCheckpointHeight, null);
	}

	public static int replayArchivedBlocks(Repository repository, int fromHeight, int toHeight, int trustedCheckpointHeight,
			ReplayProgressListener progressListener) throws DataException {
		BlockArchiveReader reader = BlockArchiveReader.getInstance();
		int lastHeight = fromHeight - 1;
		long replayStartNanos = System.nanoTime();
		long lastProgressLogNanos = replayStartNanos;
		int totalBlocks = Math.max(0, toHeight - fromHeight + 1);

		LOGGER.info("Archive fast-replay: replaying blocks {}-{} ({} blocks; checkpoint height {})",
				fromHeight, toHeight, totalBlocks, trustedCheckpointHeight);

		for (int height = fromHeight; height <= toHeight; ++height) {
			BlockTransformation blockTransformation = reader.fetchBlockAtHeight(height);
			if (blockTransformation == null || blockTransformation.getBlockData() == null)
				throw new DataException(String.format("Archive fast-replay: missing block at height %d", height));

			replayBlock(repository, blockTransformation, trustedCheckpointHeight);
			lastHeight = height;

			long elapsedMillis = elapsedMillisSince(replayStartNanos);
			if (progressListener != null)
				progressListener.onReplayProgress(height, fromHeight, toHeight);

			long nowNanos = System.nanoTime();
			int replayedBlocks = height - fromHeight + 1;
			boolean reachedInterval = replayedBlocks % PROGRESS_LOG_BLOCK_INTERVAL == 0;
			boolean reachedTime = TimeUnit.NANOSECONDS.toMillis(nowNanos - lastProgressLogNanos) >= PROGRESS_LOG_TIME_INTERVAL_MS;
			boolean reachedEnd = height == toHeight;
			if (reachedInterval || reachedTime || reachedEnd) {
				LOGGER.info("Archive fast-replay: replayed block {} of {} ({}%, {}/{} blocks, elapsed {} ms, {} blocks/sec)",
						height, toHeight, calculatePercent(replayedBlocks, totalBlocks), replayedBlocks, totalBlocks,
						elapsedMillis, formatBlocksPerSecond(replayedBlocks, elapsedMillis));
				lastProgressLogNanos = nowNanos;
			}
		}

		return lastHeight;
	}

	/**
	 * Validates and processes a single archived block, applying the trusted-replay crypto-skip when the block
	 * is strictly below {@code trustedCheckpointHeight}. Does <b>not</b> commit — changes are left pending in
	 * the repository transaction for the caller to commit or roll back atomically (see
	 * {@link #replayArchivedBlocks}).
	 */
	public static void replayBlock(Repository repository, BlockTransformation blockTransformation, int trustedCheckpointHeight) throws DataException {
		BlockData blockData = blockTransformation.getBlockData();
		int height = blockData.getHeight();

		// Strictly below the pinned checkpoint -> trusted fast-replay (skip ONLY the expensive online-account
		// signature + memory-PoW crypto). Block/transaction signatures are still fully verified at every height.
		boolean trustedReplay = trustedCheckpointHeight > 0 && height < trustedCheckpointHeight;

		Block block = new Block(repository, blockData, blockTransformation.getTransactions(), blockTransformation.getAtStatesHash());

		// ALWAYS verify the block signature, at every height — including below the checkpoint. isSignatureValid()
		// is the only path that re-derives the block's transactions-signature and verifies every transaction's own
		// signature (BlockTransformer.getBytesForTransactionsSignature -> Transaction.isSignatureValid). The block
		// reference/signature chain that the checkpoint cross-binds commits only to STORED signature bytes, not to
		// derived state or transaction content, so skipping this would let an attacker keep genuine block signatures
		// (preserving the chain up to the pinned checkpoint) while substituting forged transactions below it — which
		// areTransactionsValid() does not signature-check. It is also cheap (a few Ed25519 verifies per block)
		// relative to the online-account crypto the trusted replay actually skips.
		long blockStartNanos = System.nanoTime();
		long signatureStartNanos = blockStartNanos;
		if (!block.isSignatureValid())
			throw new DataException(String.format("Archive fast-replay: invalid block signature at height %d", height));
		long signatureMillis = elapsedMillisSince(signatureStartNanos);

		long validationStartNanos = System.nanoTime();
		Block.ValidationResult validationResult = block.isValid(trustedReplay);
		if (validationResult != Block.ValidationResult.OK)
			throw new DataException(String.format("Archive fast-replay: block at height %d failed validation: %s", height, validationResult.name()));
		long validationMillis = elapsedMillisSince(validationStartNanos);

		// Archive-replayed blocks carry their transactions straight from the archive. Unlike normal sync —
		// where a block's transactions are saved as unconfirmed when they arrive, *before* the block is
		// processed — these Transaction rows don't exist yet, so linkTransactionsToBlock()'s BlockTransaction
		// foreign key would have no parent. Persist them first (with an initial approval status, as the
		// unconfirmed-import path does) so process()/linkTransactionsToBlock() can link and confirm them.
		// AT transactions are created and saved by Block.processTransactions, so skip those here.
		long saveTransactionsStartNanos = System.nanoTime();
		int savedTransactions = 0;
		for (TransactionData transactionData : blockTransformation.getTransactions()) {
			if (transactionData.getType() == TransactionType.AT)
				continue;
			Transaction transaction = Transaction.fromData(repository, transactionData);
			transaction.setInitialApprovalStatus();
			repository.getTransactionRepository().save(transactionData);
			savedTransactions++;
		}
		long saveTransactionsMillis = elapsedMillisSince(saveTransactionsStartNanos);

		long processStartNanos = System.nanoTime();
		block.process();
		long processMillis = elapsedMillisSince(processStartNanos);
		LOGGER.debug("Archive fast-replay: block {} replayed (trustedReplay={}, transactions={}, savedTransactions={}, signature={} ms, validation={} ms, saveTransactions={} ms, process={} ms, total={} ms)",
				height, trustedReplay, blockTransformation.getTransactions().size(), savedTransactions, signatureMillis,
				validationMillis, saveTransactionsMillis, processMillis, elapsedMillisSince(blockStartNanos));
		// Intentionally no saveChanges() here: the caller commits the whole replayed range atomically so that a
		// forged sub-checkpoint prefix that fails at the checkpoint block is rolled back, never durably stored.
	}

	private static int calculatePercent(int replayedBlocks, int totalBlocks) {
		if (totalBlocks <= 0)
			return 100;

		return (int) Math.min(100, (Math.max(0L, replayedBlocks) * 100L) / totalBlocks);
	}

	private static long elapsedMillisSince(long startNanos) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
	}

	private static String formatBlocksPerSecond(int replayedBlocks, long elapsedMillis) {
		if (elapsedMillis <= 0)
			return "n/a";

		return String.format(Locale.ROOT, "%.2f", replayedBlocks * 1000.0d / elapsedMillis);
	}
}
