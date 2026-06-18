package org.qortium.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.block.BlockChain;
import org.qortium.controller.repository.ArchiveChunkImporter;
import org.qortium.data.block.ArchiveChunkData;
import org.qortium.data.block.ArchiveManifest;
import org.qortium.network.Network;
import org.qortium.network.Peer;
import org.qortium.network.message.ArchiveChunkMessage;
import org.qortium.network.message.ArchiveManifestMessage;
import org.qortium.network.message.GetArchiveChunkMessage;
import org.qortium.network.message.GetArchiveManifestMessage;
import org.qortium.network.message.Message;
import org.qortium.network.message.MessageType;
import org.qortium.repository.BlockArchiveReader;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.transform.block.BlockTransformation;
import org.qortium.utils.Base58;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Requester/consumer of the archive-chunk fast-sync. On a fresh node (and only when explicitly enabled), this
 * downloads byte-identical block-archive {@code .dat} chunks from archive-advertising peers, verifies each
 * against its SHA-256 (content-addressing — a tampered chunk is rejected), cross-binds the chunk covering the
 * release-pinned checkpoint against the pinned block signature, imports the chunks, and replays their blocks
 * with the checkpoint-gated crypto-skip ({@link ArchiveChunkImporter}). It then hands off to normal sync.
 * <p>
 * Trust: the release-pinned checkpoint is the only anchor; the manifest is untrusted/recomputable. The feature
 * is enabled by default ({@link Settings#isArchiveFastReplayEnabled()}) but inert unless the chain config pins a
 * checkpoint — so it only engages on a net that has one (currently previewnet); when disabled, or with no
 * checkpoint, this manager's {@link #run()} returns immediately. It is a one-shot startup bootstrap — it never
 * re-arms — and cooperates with the {@link Synchronizer} via the blockchain lock.
 */
public class ArchiveFastSyncManager extends Thread {

	private static final Logger LOGGER = LogManager.getLogger(ArchiveFastSyncManager.class);

	/** Peer capability name advertised by archive-serving nodes (mirror of Handshake.ARCHIVE_HEIGHT_CAPABILITY). */
	private static final String ARCHIVE_HEIGHT_CAPABILITY = "ARCHIVE_HEIGHT";

	/** Only fast-sync a node that is effectively at genesis; a populated node uses normal block-by-block sync. */
	private static final int MAX_START_HEIGHT_FOR_FAST_SYNC = 2;
	/** Sanity cap on a single chunk's declared size, to reject a malicious oversized manifest entry. */
	private static final long MAX_CHUNK_BYTES = 256L * 1024 * 1024;
	/** Generous per-block byte allowance used to bound the total a manifest may claim for the replayed range. */
	private static final long MAX_REPLAY_BYTES_PER_BLOCK = 256L * 1024;
	/** Absolute ceiling on the total declared bytes of the replayed range (defence against an oversized manifest). */
	private static final long MAX_TOTAL_REPLAY_BYTES = 8L * 1024 * 1024 * 1024;
	/** Let peer handshakes settle before the one-shot attempt. */
	private static final long INITIAL_SETTLE_MS = 20 * 1000L;
	private static final long RETRY_INTERVAL_MS = 30 * 1000L;
	private static final int MAX_ATTEMPTS = 3;

	private static ArchiveFastSyncManager instance;

	private volatile boolean isStopping = false;
	private boolean completed = false;

	private ArchiveFastSyncManager() {
	}

	public static synchronized ArchiveFastSyncManager getInstance() {
		if (instance == null)
			instance = new ArchiveFastSyncManager();

		return instance;
	}

	@Override
	public void run() {
		Thread.currentThread().setName("Archive Fast-Sync Manager");

		if (!shouldRun())
			return;

		try {
			Thread.sleep(INITIAL_SETTLE_MS);

			for (int attempt = 1; attempt <= MAX_ATTEMPTS && !isStopping && !completed; attempt++) {
				try {
					if (attemptFastSync())
						completed = true;
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				} catch (Exception e) {
					LOGGER.warn("Archive fast-sync attempt {} failed: {}", attempt, e.getMessage());
				}

				if (!completed && !isStopping)
					Thread.sleep(RETRY_INTERVAL_MS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public void shutdown() {
		isStopping = true;
		this.interrupt();
	}

	/** Master gate. Off by default; only runs for a non-lite, archive-enabled node with a pinned checkpoint. */
	private boolean shouldRun() {
		Settings settings = Settings.getInstance();

		if (!settings.isArchiveFastReplayEnabled())
			return false;
		if (settings.isLite())
			return false;
		if (!settings.isArchiveEnabled())
			return false;
		// Defer to a configured bootstrap, which builds a full DB on its own.
		if (settings.isArchiveFastReplayOnlyWhenBootstrapDisabled() && settings.getBootstrap() && settings.hasBootstrapHostsConfigured())
			return false;
		// The checkpoint is the only trust anchor; never fast-replay without one.
		if (BlockChain.getInstance().getCheckpoints().isEmpty())
			return false;

		return true;
	}

	/**
	 * @return true if the attempt reached a terminal state (success, or nothing to do) and should not be
	 *         retried; false if a transient failure (e.g. no peer yet) warrants a retry.
	 */
	private boolean attemptFastSync() throws InterruptedException, DataException {
		if (Controller.getInstance().getChainHeight() > MAX_START_HEIGHT_FOR_FAST_SYNC) {
			// e.g. a local genesis-bootstrap minter produced block 2 during the settle window — fast-sync no
			// longer applies. Log at INFO so an operator who enabled it isn't left wondering why it didn't engage.
			LOGGER.info("Archive fast-sync: chain already past genesis; leaving to normal sync");
			return true;
		}

		BlockChain.Checkpoint checkpoint = lowestCheckpoint();
		int checkpointHeight = checkpoint.height;
		byte[] pinnedSignature = Base58.decode(checkpoint.signature);

		Peer peer = selectArchivePeer(checkpointHeight);
		if (peer == null) {
			LOGGER.debug("Archive fast-sync: no peer advertising archive height >= {} yet", checkpointHeight);
			return false; // retry — a suitable peer may handshake shortly
		}

		ArchiveManifest manifest = fetchManifest(peer);
		if (manifest == null || manifest.getArchiveVersion() != BlockArchiveReader.SUPPORTED_ARCHIVE_VERSION) {
			LOGGER.debug("Archive fast-sync: peer {} returned no usable manifest", peer);
			return false;
		}

		List<ArchiveChunkData> chunks = selectContiguousChunksCoveringCheckpoint(manifest, checkpointHeight);
		if (chunks == null) {
			// The peer's manifest doesn't contiguously cover height 2..checkpoint, so the anchor can't be
			// proven. Refuse to fast-replay and let normal sync handle it.
			LOGGER.info("Archive fast-sync: manifest does not cover the checkpoint at height {}; skipping", checkpointHeight);
			return true;
		}

		if (!isWithinReplayBudget(chunks, checkpointHeight)) {
			// The manifest claims more data than a sane archive of this height could hold — refuse rather than
			// commit to a huge download. Terminal: fall back to normal sync rather than retry an oversized peer.
			LOGGER.info("Archive fast-sync: manifest exceeds the replay budget for checkpoint {}; skipping", checkpointHeight);
			return true;
		}

		// Download chunks one at a time, verifying and writing each to disk before freeing it, so peak memory is a
		// single chunk regardless of range length. The checkpoint-spanning chunk (the last selected) is fetched
		// first so a bogus manifest is rejected by the cheap cross-bind after one chunk, not the whole prefix.
		List<ArchiveChunkData> written = new ArrayList<>();
		ArchiveChunkData checkpointChunk = chunks.get(chunks.size() - 1);
		try {
			if (!downloadVerifyWrite(peer, checkpointChunk, written)) {
				deleteChunks(written);
				return false; // retry — a different peer may serve it
			}

			// Cheap early cross-bind: reject an obviously-wrong chain before staging the rest of the prefix.
			if (!isCheckpointSignatureOnDisk(checkpointHeight, pinnedSignature)) {
				LOGGER.warn("Archive fast-sync: checkpoint cross-bind FAILED at height {}; discarding", checkpointHeight);
				deleteChunks(written);
				return false;
			}

			for (int i = 0; i < chunks.size() - 1; i++) {
				if (isStopping) {
					deleteChunks(written);
					return false;
				}
				if (!downloadVerifyWrite(peer, chunks.get(i), written)) {
					LOGGER.info("Archive fast-sync: failed to fetch/verify chunk {}-{}", chunks.get(i).getStartHeight(), chunks.get(i).getEndHeight());
					deleteChunks(written);
					return false; // retry — a different peer may serve it
				}
			}

			return importStagedChunks(chunks, checkpointHeight, pinnedSignature, written);
		} catch (RuntimeException e) {
			deleteChunks(written);
			throw e;
		}
	}

	/**
	 * Rejects a manifest whose selected chunks would total more bytes — or more chunks — than a sane archive up
	 * to {@code checkpointHeight} could hold. Bounds memory, disk and bandwidth against a hostile manifest.
	 */
	// Package-private for direct unit testing.
	static boolean isWithinReplayBudget(List<ArchiveChunkData> chunks, int checkpointHeight) {
		long heightFloor = Math.max(checkpointHeight, 1);
		// Can't legitimately have more chunks than blocks below the checkpoint.
		if (chunks.size() > heightFloor)
			return false;

		long budget = Math.min(MAX_TOTAL_REPLAY_BYTES, heightFloor * MAX_REPLAY_BYTES_PER_BLOCK);
		long total = 0;
		for (ArchiveChunkData chunk : chunks) {
			long size = chunk.getSize();
			if (size <= 0 || size > MAX_CHUNK_BYTES)
				return false;
			total += size;
			if (total > budget)
				return false;
		}
		return true;
	}

	/** Downloads, verifies and writes one chunk to the local archive; the byte[] is freed once written. */
	private boolean downloadVerifyWrite(Peer peer, ArchiveChunkData chunk, List<ArchiveChunkData> written) throws InterruptedException {
		byte[] bytes = fetchAndVerifyChunk(peer, chunk);
		if (bytes == null)
			return false;

		try {
			ArchiveChunkImporter.writeChunkFile(chunk.getStartHeight(), chunk.getEndHeight(), bytes);
		} catch (IOException e) {
			LOGGER.warn("Archive fast-sync: couldn't write chunk {}-{} to disk: {}", chunk.getStartHeight(), chunk.getEndHeight(), e.getMessage());
			return false;
		}
		written.add(chunk);
		return true;
	}

	/** Cheap byte-compare of the on-disk block at {@code checkpointHeight} against the pinned signature. */
	private static boolean isCheckpointSignatureOnDisk(int checkpointHeight, byte[] pinnedSignature) {
		BlockTransformation checkpointBlock = BlockArchiveReader.getInstance().fetchBlockAtHeight(checkpointHeight);
		return checkpointBlock != null && checkpointBlock.getBlockData() != null
				&& Arrays.equals(checkpointBlock.getBlockData().getSignature(), pinnedSignature);
	}

	/** Lowest-height pinned checkpoint (the conservative anchor if several are configured). */
	private static BlockChain.Checkpoint lowestCheckpoint() {
		return BlockChain.getInstance().getCheckpoints().stream()
				.min(Comparator.comparingInt(c -> c.height))
				.orElseThrow(() -> new IllegalStateException("no checkpoint"));
	}

	private Peer selectArchivePeer(int minimumArchiveHeight) {
		Peer best = null;
		int bestHeight = 0;
		for (Peer peer : Network.getInstance().getImmutableHandshakedPeers()) {
			if (Controller.hasMisbehaved.test(peer) || Controller.hasOldVersion.test(peer) || Controller.hasInvalidSigner.test(peer))
				continue;

			int archiveHeight = peerArchiveHeight(peer);
			if (archiveHeight >= minimumArchiveHeight && archiveHeight > bestHeight) {
				best = peer;
				bestHeight = archiveHeight;
			}
		}
		return best;
	}

	private static int peerArchiveHeight(Peer peer) {
		Object capability = peer.getPeerCapability(ARCHIVE_HEIGHT_CAPABILITY);
		return (capability instanceof Integer) ? (Integer) capability : 0;
	}

	private ArchiveManifest fetchManifest(Peer peer) throws InterruptedException {
		Message response = peer.getResponseWithTimeout(new GetArchiveManifestMessage(), Peer.SYNC_RESPONSE_TIMEOUT);
		if (response == null || response.getType() != MessageType.ARCHIVE_MANIFEST)
			return null;

		return ((ArchiveManifestMessage) response).getManifest();
	}

	/**
	 * Returns the contiguous chunk list starting at height 2 up to and including the chunk that spans
	 * {@code checkpointHeight}, or null if the manifest doesn't contiguously cover that range (in which case
	 * the checkpoint anchor cannot be proven and fast-replay must be refused).
	 */
	// Package-private for direct unit testing of this manifest-coverage gate.
	static List<ArchiveChunkData> selectContiguousChunksCoveringCheckpoint(ArchiveManifest manifest, int checkpointHeight) {
		List<ArchiveChunkData> sorted = new ArrayList<>(manifest.getChunks());
		sorted.sort(Comparator.comparingInt(ArchiveChunkData::getStartHeight));

		List<ArchiveChunkData> selected = new ArrayList<>();
		int expectedStart = 2;
		for (ArchiveChunkData chunk : sorted) {
			if (chunk.getStartHeight() != expectedStart || chunk.getEndHeight() < chunk.getStartHeight())
				return null; // gap or malformed range -> contiguity broken

			selected.add(chunk);

			if (chunk.getStartHeight() <= checkpointHeight && checkpointHeight <= chunk.getEndHeight())
				return selected; // reached the chunk spanning the checkpoint

			expectedStart = chunk.getEndHeight() + 1;
		}
		return null; // checkpoint not covered
	}

	/** Downloads a chunk by paged 8 MiB slices, reassembles it, and verifies SHA-256 + header. Null on failure. */
	private byte[] fetchAndVerifyChunk(Peer peer, ArchiveChunkData chunk) throws InterruptedException {
		long declaredSize = chunk.getSize();
		if (declaredSize <= 0 || declaredSize > MAX_CHUNK_BYTES)
			return null;

		byte[] buffer = new byte[(int) declaredSize];
		int offset = 0;
		while (offset < buffer.length) {
			if (isStopping)
				return null;

			int want = Math.min(ArchiveChunkMessage.MAX_SLICE_LENGTH, buffer.length - offset);
			Message response = peer.getResponseWithTimeout(new GetArchiveChunkMessage(chunk.getStartHeight(), offset, want), Peer.FETCH_BLOCKS_TIMEOUT);
			if (response == null || response.getType() != MessageType.ARCHIVE_CHUNK)
				return null; // timeout, or peer can't serve this (GenericUnknownMessage)

			ArchiveChunkMessage sliceMessage = (ArchiveChunkMessage) response;
			if (sliceMessage.getTotalSize() != buffer.length || sliceMessage.getOffset() != offset)
				return null; // the peer disagrees on the chunk's total size / requested offset

			byte[] data = sliceMessage.getData();
			// An honest server always returns exactly the requested slice length (it pages at MAX_SLICE_LENGTH and
			// we never ask past the end), so require it — this also defeats a slow-loris peer that would otherwise
			// drip 1-byte slices to stretch the download across millions of round-trips.
			if (data == null || data.length != want)
				return null;

			System.arraycopy(data, 0, buffer, offset, data.length);
			offset += data.length;
		}

		if (!ArchiveChunkImporter.isChunkHashValid(buffer, chunk.getSha256()))
			return null; // tampered / wrong bytes
		if (!ArchiveChunkImporter.isChunkHeaderValid(buffer, chunk.getStartHeight(), chunk.getEndHeight()))
			return null; // header doesn't match the declared range

		return buffer;
	}

	/**
	 * Imports the already-staged (downloaded, verified, on-disk) chunks by replaying {@code [2, toHeight]}
	 * <b>atomically</b> under the blockchain lock: the whole range is one repository transaction (savepoint),
	 * committed only if every block — including the fully validated checkpoint block — validates and the
	 * checkpoint cross-binds against the pinned signature. Any failure rolls the whole range back and deletes
	 * the staged chunks, so a forged sub-checkpoint prefix can never be left durably on disk or in the DB.
	 * <p>
	 * The cross-bind is enforced two ways: a cheap byte-compare of the on-disk checkpoint block's signature
	 * against the pinned value (catches an honest divergence or a self-signed forgery before the expensive
	 * replay), and — decisively — the checkpoint block's full validation during replay ({@code height == H_cp}
	 * uses {@code trustedReplay = false}, so {@link Block#isSignatureValid()} recomputes the signature and
	 * {@link Block#isValid(boolean)} checks reference/eligibility against rebuilt state). The byte-compare alone
	 * is spoofable (an attacker can copy the pinned signature into a forged block's signature field), so the
	 * atomic commit gated on that full validation is what actually anchors the prefix.
	 */
	private boolean importStagedChunks(List<ArchiveChunkData> chunks, int checkpointHeight, byte[] pinnedSignature,
			List<ArchiveChunkData> written) throws InterruptedException, DataException {
		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		if (!blockchainLock.tryLock(5, TimeUnit.SECONDS)) {
			LOGGER.debug("Archive fast-sync: couldn't acquire blockchain lock; will retry");
			deleteChunks(written);
			return false;
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Re-check under the lock: a concurrent Synchronizer may have advanced the chain already.
			if (repository.getBlockRepository().getBlockchainHeight() > MAX_START_HEIGHT_FOR_FAST_SYNC) {
				LOGGER.info("Archive fast-sync: chain advanced before we got the lock; leaving to normal sync");
				deleteChunks(written);
				return true;
			}

			// Authoritative cross-bind (full set now on disk), before any block is replayed.
			if (!isCheckpointSignatureOnDisk(checkpointHeight, pinnedSignature)) {
				LOGGER.warn("Archive fast-sync: checkpoint cross-bind FAILED at height {}; discarding staged chunks", checkpointHeight);
				deleteChunks(written);
				return false;
			}

			int toHeight = chunks.get(chunks.size() - 1).getEndHeight();
			// Atomic replay: nothing is committed unless the entire range — including the fully validated
			// checkpoint block — succeeds. A forged sub-checkpoint prefix fails at H_cp and is rolled back.
			repository.setSavepoint();
			try {
				ArchiveChunkImporter.replayArchivedBlocks(repository, 2, toHeight, checkpointHeight);
				repository.saveChanges();
			} catch (DataException e) {
				repository.rollbackToSavepoint();
				deleteChunks(written);
				LOGGER.warn("Archive fast-sync: replay failed and was rolled back: {}", e.getMessage());
				return false;
			}

			// Refresh the in-memory chain-tip cache so we don't keep advertising the old (genesis) height to
			// peers / the API / systray until the next applied block would otherwise self-heal it.
			Controller.getInstance().refillLatestBlocksCache();

			LOGGER.info("Archive fast-sync: replayed to height {} (checkpoint {} verified). Handing off to normal sync.", toHeight, checkpointHeight);
		} finally {
			blockchainLock.unlock();
		}

		Synchronizer.getInstance().requestSync();
		return true;
	}

	private static void deleteChunks(List<ArchiveChunkData> chunks) {
		Path archivePath = Paths.get(Settings.getInstance().getRepositoryPath(), "archive").toAbsolutePath();
		for (ArchiveChunkData chunk : chunks) {
			try {
				Files.deleteIfExists(archivePath.resolve(String.format("%d-%d.dat", chunk.getStartHeight(), chunk.getEndHeight())));
			} catch (IOException e) {
				LOGGER.debug("Couldn't remove rejected chunk {}-{}: {}", chunk.getStartHeight(), chunk.getEndHeight(), e.getMessage());
			}
		}
		BlockArchiveReader.getInstance().invalidateFileListCache();
	}
}
