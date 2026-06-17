package org.qortium.controller.repository;

import com.google.common.hash.HashCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.block.Block;
import org.qortium.crypto.Crypto;
import org.qortium.data.block.BlockData;
import org.qortium.repository.BlockArchiveReader;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.settings.Settings;
import org.qortium.transform.block.BlockTransformation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

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
 *       height may be replayed with the expensive per-signature crypto skipped ("trusted fast-replay"),
 *       because their history is transitively pinned: any altered sub-checkpoint block would change derived
 *       state and therefore the block signature at the checkpoint height, which the caller cross-binds against
 *       the pinned signature before trusting any of it.</li>
 *   <li>Blocks at and above the checkpoint are <b>fully validated</b>, exactly like normal sync.</li>
 *   <li>Derived state is always recomputed locally via {@link Block#process()}; no state blob is ever trusted.</li>
 * </ul>
 * Stateless utility; all methods are safe to unit-test without the network. Callers (the fast-sync manager)
 * are responsible for holding the blockchain lock and for the manifest/checkpoint cross-binding.
 */
public class ArchiveChunkImporter {

	private static final Logger LOGGER = LogManager.getLogger(ArchiveChunkImporter.class);

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
	 * per-signature crypto is skipped — the block minter/transactions signatures via
	 * {@link Block#isSignatureValid()} are not checked, and {@link Block#isValid(boolean)} is called with
	 * {@code trustedReplay = true} (skipping online-account signature / memory-PoW verification). At and above
	 * the checkpoint, full validation runs. Pass {@code trustedCheckpointHeight = 0} to fully validate every
	 * block (no skipping). {@link #replayBlock} commits with {@code repository.saveChanges()} after each block;
	 * a {@code DataException} is thrown (and changes left for the caller to roll back) on the first invalid
	 * block.
	 *
	 * @return the height successfully replayed up to
	 */
	public static int replayArchivedBlocks(Repository repository, int fromHeight, int toHeight, int trustedCheckpointHeight) throws DataException {
		BlockArchiveReader reader = BlockArchiveReader.getInstance();
		int lastHeight = fromHeight - 1;

		for (int height = fromHeight; height <= toHeight; ++height) {
			BlockTransformation blockTransformation = reader.fetchBlockAtHeight(height);
			if (blockTransformation == null || blockTransformation.getBlockData() == null)
				throw new DataException(String.format("Archive fast-replay: missing block at height %d", height));

			replayBlock(repository, blockTransformation, trustedCheckpointHeight);
			lastHeight = height;
		}

		return lastHeight;
	}

	/**
	 * Validates and processes a single archived block, applying the trusted-replay crypto-skip when the block
	 * is strictly below {@code trustedCheckpointHeight}. Commits via {@code saveChanges()} on success.
	 */
	public static void replayBlock(Repository repository, BlockTransformation blockTransformation, int trustedCheckpointHeight) throws DataException {
		BlockData blockData = blockTransformation.getBlockData();
		int height = blockData.getHeight();

		// Strictly below the pinned checkpoint -> trusted fast-replay (skip expensive crypto). The checkpoint
		// block itself and everything above it are fully validated, so the anchor is never trusted on faith.
		boolean trustedReplay = trustedCheckpointHeight > 0 && height < trustedCheckpointHeight;

		Block block = new Block(repository, blockData, blockTransformation.getTransactions(), blockTransformation.getAtStatesHash());

		// Block minter + transactions-set signatures (expensive). Skipped only under trusted fast-replay.
		if (!trustedReplay && !block.isSignatureValid())
			throw new DataException(String.format("Archive fast-replay: invalid block signature at height %d", height));

		Block.ValidationResult validationResult = block.isValid(trustedReplay);
		if (validationResult != Block.ValidationResult.OK)
			throw new DataException(String.format("Archive fast-replay: block at height %d failed validation: %s", height, validationResult.name()));

		block.process();
		repository.saveChanges();
	}
}
