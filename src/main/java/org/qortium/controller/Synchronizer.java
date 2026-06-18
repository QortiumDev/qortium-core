package org.qortium.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.account.Account;
import org.qortium.account.PublicKeyAccount;
import org.qortium.block.Block;
import org.qortium.block.Block.ValidationResult;
import org.qortium.block.BlockChain;
import org.qortium.data.block.BlockData;
import org.qortium.data.block.BlockSummaryData;
import org.qortium.data.block.CommonBlockData;
import org.qortium.data.transaction.RewardShareTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.event.Event;
import org.qortium.event.EventBus;
import org.qortium.network.Network;
import org.qortium.network.Peer;
import org.qortium.network.message.*;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.transaction.Transaction;
import org.qortium.utils.Base58;
import org.qortium.utils.ByteArray;
import org.qortium.utils.NTP;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.qortium.network.Peer.FETCH_BLOCKS_TIMEOUT;
import static org.qortium.network.Peer.SYNC_RESPONSE_TIMEOUT;

public class Synchronizer extends Thread {

	private static final Logger LOGGER = LogManager.getLogger(Synchronizer.class);

	/** Max number of new blocks we aim to add to chain tip in each sync round */
	private static final int SYNC_BATCH_SIZE = 1000; // XXX move to Settings?

	/** Initial jump back of block height when searching for common block with peer */
	private static final int INITIAL_BLOCK_STEP = 8;
	/** Maximum jump back of block height when searching for common block with peer */
	private static final int MAXIMUM_BLOCK_STEP = 128;

	/** Maximum difference in block height between tip and peer's common block before peer is considered TOO DIVERGENT */
	private static final int MAXIMUM_COMMON_DELTA = 240; // XXX move to Settings?

	/** Maximum number of block signatures we ask from peer in one go */
	private static final int MAXIMUM_REQUEST_SIZE = 200; // XXX move to Settings?

	/** Maximum number of consecutive failed sync attempts before marking peer as misbehaved */
	private static final int MAX_CONSECUTIVE_FAILED_SYNC_ATTEMPTS = 3;

	private boolean running;

	/** Latest block signatures from other peers that we know are on inferior chains. */
	List<ByteArray> inferiorChainSignatures = new ArrayList<>();

	/** Recovery mode, which is used to bring back a stalled network */
	private boolean recoveryMode = false;
	private boolean peersAvailable = true; // peersAvailable must default to true
	private long timePeersLastAvailable = 0;

	// Keep track of the size of the last re-org, so it can be logged
	private int lastReorgSize;

	/** Synchronization object for sync variables below */
	public final Object syncLock = new Object();
	/** Whether we are attempting to synchronize. */
	private volatile boolean isSynchronizing = false;
	/** Temporary estimate of synchronization progress for SysTray use. */
	private volatile int syncPercent = 0;
	/** Temporary estimate of blocks remaining for SysTray use. */
	private volatile int blocksRemaining = 0;
	/** Current synchronization target height, if actively synchronizing. */
	private volatile Integer syncTargetHeight = null;

	private static volatile boolean requestSync = false;
	private boolean syncRequestPending = false;
	/**
	 * Wall-clock time (ms) of the last sync attempt, used to pace the heartbeat below.
	 * Initialised to 0 so the first eligible loop iteration arms one sync attempt at boot.
	 */
	private long lastSyncAttemptTimestamp = 0L;

	/**
	 * How often, at most, the run-loop heartbeat re-arms a sync attempt while we are not up to
	 * date and no sync is already pending. The only other sync trigger is an inbound peer
	 * BLOCK_SUMMARIES broadcast (Controller.requestSync()); without this heartbeat, if those
	 * broadcasts stop being processed — or every attempt completes as a no-op and clears the
	 * flag — the node would never retry and could sit behind the network indefinitely.
	 */
	private static final long SYNC_HEARTBEAT_INTERVAL = 60 * 1000L;

	/** Fork-recovery watchdog (Tier 3): how often, at most, the watchdog evaluates, inside the 1s run-loop. */
	private static final long WATCHDOG_CHECK_INTERVAL = 30 * 1000L;
	/** Fork-recovery watchdog: minimum number of distinct fresh, healthy, strictly-higher peers required to act. */
	private static final int RECOVERY_WATCHDOG_MIN_PEERS = 2;
	/**
	 * Fork-recovery watchdog: absolute ceiling on how many of our own tip blocks the watchdog will
	 * discard within one stuck episode (no intervening forward progress). Guarantees the watchdog can
	 * never approach MAXIMUM_COMMON_DELTA and can never become a deep-reorg tool.
	 */
	private static final int RECOVERY_WATCHDOG_MAX_ORPHAN_DEPTH_CEILING = 3;

	/** Fork-recovery watchdog state (all touched only on the Synchronizer thread). */
	private long lastWatchdogCheckTimestamp = 0L;          // System.currentTimeMillis() pacing
	private long watchdogStuckSince = 0L;                  // NTP time the current stuck episode began; 0 = not stuck
	private byte[] watchdogStuckTipSignature = null;       // our tip sig the episode is keyed to
	private long lastWatchdogOrphanTimestamp = 0L;         // NTP time of last orphan, for cooldown
	private int watchdogOrphanCount = 0;                   // consecutive orphans without forward progress
	private int watchdogHighWaterHeight = 0;               // highest tip height seen; forward progress resets the orphan budget

	// Keep track of invalid blocks so that we don't keep trying to sync them
	private Map<ByteArray, Long> invalidBlockSignatures = Collections.synchronizedMap(new HashMap<>());
	public Long timeValidBlockLastReceived = null;
	public Long timeInvalidBlockLastReceived = null;

	private static Synchronizer instance;

	public enum SynchronizationResult {
		OK, NOTHING_TO_DO, GENESIS_ONLY, NO_COMMON_BLOCK, TOO_DIVERGENT, NO_REPLY, INFERIOR_CHAIN, INVALID_DATA, NO_BLOCKCHAIN_LOCK, REPOSITORY_ISSUE, SHUTTING_DOWN, CHAIN_TIP_TOO_OLD
    }

	public static class NewChainTipEvent implements Event {
		private final BlockData priorChainTip;
		private final BlockData newChainTip;

		public NewChainTipEvent(BlockData priorChainTip, BlockData newChainTip) {
			this.priorChainTip = priorChainTip;
			this.newChainTip = newChainTip;
		}

		public BlockData getPriorChainTip() {
			return this.priorChainTip;
		}

		public BlockData getNewChainTip() {
			return this.newChainTip;
		}
	}

	// Constructors

	private Synchronizer() {
		this.running = true;
	}

	public static Synchronizer getInstance() {
		if (instance == null) {
			instance = new Synchronizer();
			instance.setPriority(Settings.getInstance().getSynchronizerThreadPriority());

			LOGGER.info("thread priority = " + instance.getPriority());
		}

		return instance;
	}


	@Override
	public void run() {
		Thread.currentThread().setName("Synchronizer");

		if (Settings.getInstance().isLite()) {
			// Lite nodes don't need to sync
			return;
		}

		try {
			while (running && !Controller.isStopping()) {
				Thread.sleep(1000);

				// Heartbeat: periodically re-arm a sync attempt when we are not up to date and
				// no sync is already requested/pending/running. This guarantees the node keeps
				// retrying synchronization even if inbound BLOCK_SUMMARIES broadcasts stop
				// arriving or every attempt no-ops and clears requestSync. Gating on
				// !isUpToDate() keeps healthy nodes quiet (no redundant peer round-trips), and
				// skipping while a request is pending avoids starving transaction import, which
				// defers on isSyncRequestPending().
				if (!requestSync && !syncRequestPending && !isSynchronizing
						&& System.currentTimeMillis() - lastSyncAttemptTimestamp >= SYNC_HEARTBEAT_INTERVAL
						&& !Controller.getInstance().isUpToDate()) {
					requestSync = true;
				}

				if (requestSync) {
					requestSync = false;
					lastSyncAttemptTimestamp = System.currentTimeMillis();
					boolean success = Synchronizer.getInstance().potentiallySynchronize();
					if (!success) {
						// Something went wrong, so try again next time
						requestSync = true;
					}
					// Remember that we have a pending sync request if this attempt failed
					syncRequestPending = !success;
				}

				// Fork-recovery watchdog (paced): detect a stale tip wedged behind a fresh higher
				// peer quorum and auto-orphan our short top fork so normal sync can adopt the chain.
				// Wrapped defensively so a watchdog fault can never terminate the synchronizer.
				if (!isSynchronizing && System.currentTimeMillis() - lastWatchdogCheckTimestamp >= WATCHDOG_CHECK_INTERVAL) {
					lastWatchdogCheckTimestamp = System.currentTimeMillis();
					try {
						checkStuckSelfMintedFork();
					} catch (Exception e) {
						LOGGER.warn("Fork-recovery watchdog check failed: {}", e.getMessage());
					}
				}
			}
		} catch (InterruptedException e) {
			// Clear interrupted flag so we can shutdown trim threads
			Thread.interrupted();
			// Fall-through to exit
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
		}
	}

	public void shutdown() {
		this.running = false;
		this.interrupt();
	}

	/** Outcome of the Tier-3 fork-recovery watchdog gate logic (see {@link #decideRecoveryWatchdogAction}). */
	/* package */ enum RecoveryWatchdogAction {
		/** Not wedged behind a live network (not stale, or no fresh higher-peer quorum) — clear stuck state. */
		NONE,
		/** Stuck behind a quorum; (re)start the sustained-stuck timer for the current tip. */
		ARM,
		/** Stuck, but the sustained-duration / cooldown / orphan-ceiling gates are not all satisfied yet. */
		WAIT,
		/** All gates satisfied — orphan one of our own top blocks and request a sync. */
		ORPHAN
	}

	/**
	 * Pure decision function for the fork-recovery watchdog gates, factored out for unit testing
	 * (no chain / network / NTP access). Given the observed condition and the watchdog's timers,
	 * returns what the watchdog should do this tick. The caller performs the side effects.
	 *
	 * @param ourTipStale            our chain tip is older than the "recent" threshold (we are behind)
	 * @param higherPeerCount        number of distinct fresh, healthy, strictly-higher peers
	 * @param haveActiveEpisodeForTip a stuck-episode timer is already running for the CURRENT tip signature
	 * @param now                    current NTP time (ms)
	 * @param watchdogStuckSince     NTP time the current episode began (0 if none)
	 * @param lastWatchdogOrphanTimestamp NTP time of the last orphan (0 if none)
	 * @param watchdogOrphanCount    consecutive orphans this episode without forward progress
	 * @param minPeers               minimum quorum of higher peers required to act
	 * @param stuckThresholdMillis   how long the condition must persist before acting
	 * @param cooldownMillis         minimum gap between orphan actions
	 * @param maxOrphanCeiling       hard cap on orphans per stuck episode
	 */
	/* package */ static RecoveryWatchdogAction decideRecoveryWatchdogAction(
			boolean ourTipStale, int higherPeerCount, boolean haveActiveEpisodeForTip,
			long now, long watchdogStuckSince, long lastWatchdogOrphanTimestamp, int watchdogOrphanCount,
			int minPeers, long stuckThresholdMillis, long cooldownMillis, int maxOrphanCeiling) {

		// Only act when we are stale AND behind a quorum of fresh higher peers.
		if (!ourTipStale || higherPeerCount < minPeers)
			return RecoveryWatchdogAction.NONE;

		// Start/refresh the sustained-stuck timer when this is a new episode or our tip moved.
		if (!haveActiveEpisodeForTip)
			return RecoveryWatchdogAction.ARM;

		// Sustained-duration gate.
		if (now - watchdogStuckSince < stuckThresholdMillis)
			return RecoveryWatchdogAction.WAIT;

		// Cooldown gate.
		if (lastWatchdogOrphanTimestamp != 0L && now - lastWatchdogOrphanTimestamp < cooldownMillis)
			return RecoveryWatchdogAction.WAIT;

		// Hard ceiling on own-block orphans per stuck episode.
		if (watchdogOrphanCount >= maxOrphanCeiling)
			return RecoveryWatchdogAction.WAIT;

		return RecoveryWatchdogAction.ORPHAN;
	}

	/**
	 * Tier 3 fork-recovery watchdog.
	 *
	 * Detects the narrow "wedged behind a live network" state — our chain tip is STALE (we are
	 * behind), a quorum of fresh, healthy, strictly-higher peers exists, and normal synchronization
	 * has been unable to advance us for a sustained period (e.g. we minted a short fork that wins the
	 * 1-block weight comparison, so sync refuses the longer network chain) — and recovers by
	 * orphaning a single one of our own top blocks back toward the common block, then requesting a
	 * sync. Normal, fully-validated synchronization (preProcess()+isValid()) then adopts the network
	 * chain; this method NEVER imports peer blocks and NEVER force-syncs.
	 *
	 * Safety: only fires when our tip is stale (a healthy node at the network tip never qualifies);
	 * requires a distinct fresh-higher-peer quorum reusing the same vetted filter as Tier 2's
	 * hasFreshHigherPeer (so a solo/most-advanced minter of a dead network is never affected — that
	 * is stale-chain catch-up's job); orphans at most one block per action, hard-capped at
	 * RECOVERY_WATCHDOG_MAX_ORPHAN_DEPTH_CEILING per stuck episode (forward progress resets the
	 * budget), far below MAXIMUM_COMMON_DELTA; paced, sustained-gated and cooldown-gated against
	 * thrash. Because the orphaned-to block is itself stale, the BlockMinter "tip not recent -> sync
	 * instead" gate plus Tier 2 (no stale-catch-up mint while a fresh higher peer exists) keep
	 * minting OFF afterwards, so the node syncs rather than re-minting the fork.
	 */
	private void checkStuckSelfMintedFork() throws DataException {
		if (!Settings.getInstance().isRecoveryWatchdogEnabled())
			return;

		final Long now = NTP.getTime();
		if (now == null)
			return; // NTP not synced yet — do nothing this round

		final BlockData tip = Controller.getInstance().getChainTip();
		if (tip == null || tip.getHeight() == null || tip.getSignature() == null)
			return;

		final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
		if (minLatestBlockTimestamp == null)
			return;

		// A node at the network tip is never stale, so a healthy node yields NONE here. The quorum
		// uses the same vetted filter as Tier 2's hasFreshHigherPeer, so a solo / most-advanced
		// minter of a dead network (no fresh higher peer) also yields NONE — stale-chain catch-up
		// governs that case, not this watchdog.
		final boolean ourTipStale = tip.getTimestamp() < minLatestBlockTimestamp;
		final int higherPeerCount = ourTipStale ? countFreshHigherPeers(tip.getHeight()) : 0;
		final boolean haveActiveEpisodeForTip =
				watchdogStuckSince != 0L && Arrays.equals(watchdogStuckTipSignature, tip.getSignature());

		final RecoveryWatchdogAction action = decideRecoveryWatchdogAction(
				ourTipStale, higherPeerCount, haveActiveEpisodeForTip,
				now, watchdogStuckSince, lastWatchdogOrphanTimestamp, watchdogOrphanCount,
				RECOVERY_WATCHDOG_MIN_PEERS,
				Settings.getInstance().getRecoveryWatchdogStuckThresholdMillis(),
				Settings.getInstance().getRecoveryWatchdogCooldownMillis(),
				RECOVERY_WATCHDOG_MAX_ORPHAN_DEPTH_CEILING);

		switch (action) {
			case NONE:
				resetWatchdogStuckState();
				return;

			case ARM:
				// Begin (or refresh) the sustained-stuck timer for this tip. Forward progress since
				// the last episode (our height rose past the high-water mark) refills the orphan budget.
				if (tip.getHeight() > watchdogHighWaterHeight) {
					watchdogOrphanCount = 0;
					watchdogHighWaterHeight = tip.getHeight();
				}
				watchdogStuckSince = now;
				watchdogStuckTipSignature = tip.getSignature();
				LOGGER.info("Fork-recovery watchdog: stale tip at height {} behind {} fresh higher peer(s); will recover if it persists {}s",
						tip.getHeight(), higherPeerCount, Settings.getInstance().getRecoveryWatchdogStuckThresholdMillis() / 1000);
				return;

			case WAIT:
				// Stuck, but the sustained-duration / cooldown / orphan-ceiling gates are not all met yet.
				return;

			case ORPHAN:
				break; // perform the bounded orphan below
		}

		// Re-read the tip immediately before acting; abort if it moved so we orphan exactly one
		// block from the CURRENT tip (never a deeper reorg computed against a stale height).
		final BlockData freshTip = Controller.getInstance().getChainTip();
		if (freshTip == null || freshTip.getHeight() == null || freshTip.getSignature() == null
				|| !Arrays.equals(freshTip.getSignature(), tip.getSignature()))
			return;

		final int targetHeight = freshTip.getHeight() - 1;
		if (targetHeight <= 0)
			return;

		// Respect the archive floor (mirrors AdminResource /orphan). For depth 1 this never trips.
		if (Settings.getInstance().isTopOnly() || Settings.getInstance().isArchiveEnabled()) {
			try (final Repository repository = RepositoryManager.getRepository()) {
				int oldestBlock = repository.getBlockArchiveRepository().getBlockArchiveHeight() + 100;
				if (targetHeight <= oldestBlock) {
					LOGGER.warn("Fork-recovery watchdog: not orphaning to {} because it is at/below the archive floor {}", targetHeight, oldestBlock);
					resetWatchdogStuckState();
					return;
				}
			}
		}

		// Discard only our own top block back to the common height; BlockChain.orphan uses a
		// non-blocking tryLock and returns false if minting/sync holds the blockchain lock.
		boolean orphaned = BlockChain.orphan(targetHeight);
		if (!orphaned)
			return; // lock busy — retry next paced tick (stuck condition persists)

		LOGGER.info("Fork-recovery watchdog: orphaned our stale block {} back to height {} (behind {} fresh higher peer(s), stuck {}s); requesting sync to adopt the network chain",
				freshTip.getHeight(), targetHeight, higherPeerCount, (now - watchdogStuckSince) / 1000);

		watchdogOrphanCount++;
		lastWatchdogOrphanTimestamp = now;
		// Restart the sustained timer for any subsequent episode, and prompt an immediate, fully
		// validated sync to adopt the heavier/longer network chain.
		watchdogStuckSince = 0L;
		watchdogStuckTipSignature = null;
		requestSync = true;
	}

	private void resetWatchdogStuckState() {
		this.watchdogStuckSince = 0L;
		this.watchdogStuckTipSignature = null;
	}

	/**
	 * Counts distinct handshaked peers advertising a fresh (recent) chain tip strictly higher than
	 * the given height, using the same healthy-peer filter set as Controller.hasFreshHigherPeer.
	 */
	private int countFreshHigherPeers(int ourHeight) {
		List<Peer> peers = new ArrayList<>(Network.getInstance().getImmutableHandshakedPeers());

		peers.removeIf(Controller.hasMisbehaved);
		peers.removeIf(Controller.hasOldVersion);
		peers.removeIf(Controller.hasInvalidSigner);
		peers.removeIf(Controller.hasNoRecentBlock);

		int count = 0;
		for (Peer peer : peers) {
			final BlockSummaryData peerChainTipData = peer.getChainTipData();
			if (peerChainTipData != null && peerChainTipData.getSignature() != null && peerChainTipData.getHeight() > ourHeight)
				count++;
		}

		return count;
	}



	public boolean isSynchronizing() {
		return this.isSynchronizing;
	}

	public boolean isSyncRequestPending() {
		return this.syncRequestPending;
	}

	public Integer getSyncPercent() {
		synchronized (this.syncLock) {
			if (this.isSynchronizing)
				return this.syncPercent;

			final Long now = NTP.getTime();
			if (now == null)
				return null;
	
			// Report as 100% synced if the latest block is within the last 60 mins
			final long minLatestBlockTimestamp = now - (60 * 60 * 1000L);
			if (Controller.getInstance().isUpToDate(minLatestBlockTimestamp)) {
				return 100;
			}
	
			return null;
		}
	}

	public Integer getBlocksRemaining() {
		synchronized (this.syncLock) {
			if (this.isSynchronizing)
				return this.blocksRemaining;

			Long now = NTP.getTime();
			if (now == null)
				return null;

			// Report as 0 blocks remaining if the latest block is within the last 60 mins
			final long minLatestBlockTimestamp = now - (60 * 60 * 1000L);
			if (Controller.getInstance().isUpToDate(minLatestBlockTimestamp)) {
				return 0;
			}

			return null;
		}
	}

	public Integer getSyncTargetHeight() {
		synchronized (this.syncLock) {
			return this.isSynchronizing ? this.syncTargetHeight : null;
		}
	}

	public void requestSync() {
		requestSync = true;
	}

	public boolean isSyncRequested() {
		return requestSync;
	}

	public boolean getRecoveryMode() {
		return this.recoveryMode;
	}


	public boolean potentiallySynchronize() throws InterruptedException {
		// Already synchronizing via another thread?
		if (this.isSynchronizing)
			return true;

	

		// Needs a mutable copy of the unmodifiableList
		List<Peer> peers = new ArrayList<>(Network.getInstance().getImmutableHandshakedPeers());
		final int initialPeerCount = peers.size();
		LOGGER.trace(String.format("Starting sync attempt with %d handshaked peer(s)", initialPeerCount));

		// Disregard peers that have "misbehaved" recently
		int beforeCount = peers.size();
		List<Peer> misbehavedPeers = peers.stream().filter(Controller.hasMisbehaved).collect(Collectors.toList());
		peers.removeIf(Controller.hasMisbehaved);
		if (!misbehavedPeers.isEmpty()) {
			LOGGER.trace(String.format("Filtered out %d misbehaved peer(s): %s", misbehavedPeers.size(), 
					misbehavedPeers.stream().map(Peer::toString).collect(Collectors.joining(", "))));
		}

		// Disregard peers that only have genesis block
		beforeCount = peers.size();
		List<Peer> genesisOnlyPeers = peers.stream().filter(Controller.hasOnlyGenesisBlock).collect(Collectors.toList());
		peers.removeIf(Controller.hasOnlyGenesisBlock);
		if (!genesisOnlyPeers.isEmpty()) {
			LOGGER.trace(String.format("Filtered out %d peer(s) with only genesis block: %s", genesisOnlyPeers.size(),
					genesisOnlyPeers.stream().map(Peer::toString).collect(Collectors.joining(", "))));
		}

		// Disregard peers that don't have a recent block, unless recovery mode is active.
		// Recovery mode is the path that lets a delayed or stalled network continue from older block timestamps.
		beforeCount = peers.size();
		boolean enteredRecoveryFromStalePeers = false;
		boolean staleChainCatchUpActive = Controller.getInstance().isStaleChainCatchUpActive();
		if (!this.recoveryMode && !staleChainCatchUpActive) {
			List<Peer> peersBeforeRecentFilter = new ArrayList<>(peers);
			List<Peer> noRecentBlockPeers = peers.stream().filter(Controller.hasNoRecentBlock).collect(Collectors.toList());
			peers.removeIf(Controller.hasNoRecentBlock);
			if (!noRecentBlockPeers.isEmpty()) {
				LOGGER.trace(String.format("Filtered out %d peer(s) without recent block: %s", noRecentBlockPeers.size(),
						noRecentBlockPeers.stream().map(Peer::toString).collect(Collectors.joining(", "))));
			}

			if (this.checkRecoveryModeForPeers(peers)) {
				peers = peersBeforeRecentFilter;
				enteredRecoveryFromStalePeers = true;
				LOGGER.debug("Recovery mode active; allowing peers with older chain tips for synchronization");
			}
		} else if (staleChainCatchUpActive) {
			LOGGER.debug("Stale chain catch-up active; skipping recent-block peer filter");
		} else {
			LOGGER.debug("Recovery mode active; skipping recent-block peer filter");
		}

		// Disregard peers that are on an old version
		beforeCount = peers.size();
		List<Peer> oldVersionPeers = peers.stream().filter(Controller.hasOldVersion).collect(Collectors.toList());
		peers.removeIf(Controller.hasOldVersion);
		if (!oldVersionPeers.isEmpty()) {
			LOGGER.trace(String.format("Filtered out %d peer(s) with old version: %s", oldVersionPeers.size(),
					oldVersionPeers.stream().map(Peer::toString).collect(Collectors.joining(", "))));
		}

		if (!enteredRecoveryFromStalePeers && !staleChainCatchUpActive) {
			if (!this.recoveryMode || peers.stream().noneMatch(Controller.hasNoRecentBlock))
				checkRecoveryModeForPeers(peers);
		}

		// Check we have enough peers to potentially synchronize
		final int minBlockchainPeers = Settings.getInstance().getMinBlockchainPeers();
		if (peers.size() < minBlockchainPeers) {
			LOGGER.trace(String.format("Not enough peers for sync. Required: %d, Available: %d (filtered from %d handshaked peers)", 
					minBlockchainPeers, peers.size(), initialPeerCount));
			return true;
		}

		// Disregard peers that have no block signature or the same block signature as us
		beforeCount = peers.size();
		List<Peer> noOrSameBlockPeers = peers.stream().filter(Controller.hasNoOrSameBlock).collect(Collectors.toList());
		peers.removeIf(Controller.hasNoOrSameBlock);
		if (!noOrSameBlockPeers.isEmpty()) {
			LOGGER.trace(String.format("Filtered out %d peer(s) with no or same block signature: %s", noOrSameBlockPeers.size(),
					noOrSameBlockPeers.stream().map(Peer::toString).collect(Collectors.joining(", "))));
		}

		// Disregard peers that are on the same block as last sync attempt and we didn't like their chain
		beforeCount = peers.size();
		List<Peer> inferiorChainPeers = peers.stream().filter(Controller.hasInferiorChainTip).collect(Collectors.toList());
		peers.removeIf(Controller.hasInferiorChainTip);
		if (!inferiorChainPeers.isEmpty()) {
			LOGGER.trace(String.format("Filtered out %d peer(s) with inferior chain tip: %s", inferiorChainPeers.size(),
					inferiorChainPeers.stream().map(Peer::toString).collect(Collectors.joining(", "))));
		}

		// Disregard peers that have a block with an invalid signer
		beforeCount = peers.size();
		List<Peer> invalidSignerPeers = peers.stream().filter(Controller.hasInvalidSigner).collect(Collectors.toList());
		peers.removeIf(Controller.hasInvalidSigner);
		if (!invalidSignerPeers.isEmpty()) {
			LOGGER.trace(String.format("Filtered out %d peer(s) with invalid signer: %s", invalidSignerPeers.size(),
					invalidSignerPeers.stream().map(Peer::toString).collect(Collectors.joining(", "))));
		}

		final int peersBeforeComparison = peers.size();

		// Request recent block summaries from the remaining peers, and locate our common block with each
		Synchronizer.getInstance().findCommonBlocksWithPeers(peers);

		// Compare the peers against each other, and against our chain, which will return an updated list excluding those without common blocks
		peers = Synchronizer.getInstance().comparePeers(peers);

		// We may have added more inferior chain tips when comparing peers, so remove any peers that are currently on those chains
		peers.removeIf(Controller.hasInferiorChainTip);

		// Remove any peers that are no longer on a recent block since the last check, unless recovery or catch-up mode is active.
		if (!this.recoveryMode && !staleChainCatchUpActive)
			peers.removeIf(Controller.hasNoRecentBlock);

		final int peersRemoved = peersBeforeComparison - peers.size();
		if (peersRemoved > 0 && !peers.isEmpty())
			LOGGER.debug(String.format("Ignoring %d peers on inferior chains. Peers remaining: %d", peersRemoved, peers.size()));

		if (peers.isEmpty()) {
			LOGGER.trace(String.format("No suitable peers available for synchronization after filtering. Started with %d handshaked peer(s), filtered down to 0", initialPeerCount));
			return true;
		}

		if (peers.size() > 1) {
			StringBuilder finalPeersString = new StringBuilder();
			for (Peer peer : peers)
				finalPeersString = finalPeersString.length() > 0 ? finalPeersString.append(", ").append(peer) : finalPeersString.append(peer);
			LOGGER.debug(String.format("Choosing synchronization peer from: [%s]", finalPeersString.toString()));
		}

		Peer peer;
		if (staleChainCatchUpActive) {
			peer = getBestStaleCatchUpPeer(peers);
			LOGGER.debug("Stale chain catch-up active; selected peer {} with height {}, ts {}", peer,
					peer.getChainTipData().getHeight(), peer.getChainTipData().getTimestamp());
		} else {
			// Pick random peer to sync with
			int index = new SecureRandom().nextInt(peers.size());
			peer = peers.get(index);
		}
		
	

		SynchronizationResult syncResult = actuallySynchronize(peer, false);
		if (syncResult == SynchronizationResult.NO_BLOCKCHAIN_LOCK) {
			// No blockchain lock - force a retry by returning false
			return false;
		}

		return true;
	}

	public SynchronizationResult actuallySynchronize(Peer peer, boolean force) throws InterruptedException {
		boolean hasStatusChanged = false;
		BlockData priorChainTip = Controller.getInstance().getChainTip();
		int priorHeight = priorChainTip != null ? priorChainTip.getHeight() : Controller.getInstance().getChainHeight();
		Integer peerTargetHeight = getPeerTargetHeight(peer);

		synchronized (this.syncLock) {
			updateSyncProgressLocked(priorHeight, peerTargetHeight);

			// Only update SysTray if we're potentially changing height
			if (this.syncPercent < 100) {
				this.isSynchronizing = true;
				hasStatusChanged = true;
			}
		}
		peer.setSyncInProgress(true);

		if (hasStatusChanged)
			Controller.getInstance().updateSysTray();

		try {
			SynchronizationResult syncResult = Synchronizer.getInstance().synchronize(peer, force);
			switch (syncResult) {
				case GENESIS_ONLY:
				case NO_COMMON_BLOCK:
				case TOO_DIVERGENT:
				case INVALID_DATA: {
					// These are more serious results that warrant a cool-off
					LOGGER.info(String.format("Failed to synchronize with peer %s (%s) - cooling off", peer, syncResult.name()));

					// Don't use this peer again for a while
					Network.getInstance().peerMisbehaved(peer);
					break;
				}

				case INFERIOR_CHAIN: {
					// Update our list of inferior chain tips
					ByteArray inferiorChainSignature = ByteArray.wrap(peer.getChainTipData().getSignature());
					if (!inferiorChainSignatures.contains(inferiorChainSignature))
						inferiorChainSignatures.add(inferiorChainSignature);

					// These are minor failure results so fine to try again
					LOGGER.debug(() -> String.format("Refused to synchronize with peer %s (%s)", peer, syncResult.name()));

					// Notify peer of our superior chain
					Message message = Network.getInstance().buildHeightOrChainTipInfo();
					if (message == null || !peer.sendMessage(message))
						peer.disconnect("failed to notify peer of our superior chain");
					break;
				}

				case NO_REPLY:
				case NO_BLOCKCHAIN_LOCK:
				case REPOSITORY_ISSUE:
				case CHAIN_TIP_TOO_OLD:
					// These are minor failure results so fine to try again
					LOGGER.debug(() -> String.format("Failed to synchronize with peer %s (%s)", peer, syncResult.name()));
					break;

				case SHUTTING_DOWN:
					// Just quietly exit
					break;

				case OK:
					// fall-through...
				case NOTHING_TO_DO: {
					// Update our list of inferior chain tips
					ByteArray inferiorChainSignature = ByteArray.wrap(peer.getChainTipData().getSignature());
					if (!inferiorChainSignatures.contains(inferiorChainSignature))
						inferiorChainSignatures.add(inferiorChainSignature);

					LOGGER.debug(() -> String.format("Synchronized with peer %s (%s)", peer, syncResult.name()));
					break;
				}
			}

			if (!running) {
				// We've stopped
				return SynchronizationResult.SHUTTING_DOWN;
			}

			// Has our chain tip changed?
			BlockData newChainTip;

			try (final Repository repository = RepositoryManager.getRepository()) {
				newChainTip = repository.getBlockRepository().getLastBlock();
			} catch (DataException e) {
				LOGGER.warn(String.format("Repository issue when trying to fetch post-synchronization chain tip: %s", e.getMessage()));
				return syncResult;
			}

			if (!Arrays.equals(newChainTip.getSignature(), priorChainTip.getSignature())) {
				// Reset our cache of inferior chains
				inferiorChainSignatures.clear();

				Network.getInstance().broadcastOurChain();

				EventBus.INSTANCE.notify(new NewChainTipEvent(priorChainTip, newChainTip));
			}

			return syncResult;
		} finally {
			synchronized (this.syncLock) {
				this.isSynchronizing = false;
				this.syncTargetHeight = null;
				this.blocksRemaining = 0;
			}
			peer.setSyncInProgress(false);
		}
	}

	private static Integer getPeerTargetHeight(Peer peer) {
		BlockSummaryData chainTipData = peer.getChainTipData();
		return chainTipData != null ? chainTipData.getHeight() : null;
	}

	private void updateSyncProgressLocked(int currentHeight, Integer targetHeight) {
		if (targetHeight == null || targetHeight <= 0) {
			this.syncTargetHeight = null;
			this.syncPercent = 0;
			this.blocksRemaining = 0;
			return;
		}

		this.syncTargetHeight = Math.max(currentHeight, targetHeight);
		this.blocksRemaining = Math.max(0, this.syncTargetHeight - currentHeight);
		this.syncPercent = calculateSyncPercent(currentHeight, this.syncTargetHeight);

		if (currentHeight < this.syncTargetHeight)
			this.syncPercent = Math.min(this.syncPercent, 99);
	}

	private static int calculateSyncPercent(int currentHeight, int targetHeight) {
		if (targetHeight <= 0)
			return 0;

		long boundedHeight = Math.max(0, (long) currentHeight);
		return (int) Math.min(100, (boundedHeight * 100L) / targetHeight);
	}

	private boolean checkRecoveryModeForPeers(List<Peer> qualifiedPeers) {
		List<Peer> handshakedPeers = Network.getInstance().getImmutableHandshakedPeers();

		if (!handshakedPeers.isEmpty()) {
			// There is at least one handshaked peer
			if (qualifiedPeers.isEmpty()) {
				// There are no 'qualified' peers - i.e. peers that have a recent block we can sync to
				boolean werePeersAvailable = peersAvailable;
				peersAvailable = false;

				// If peers only just became unavailable, update our record of the time they were last available
				if (werePeersAvailable)
					timePeersLastAvailable = NTP.getTime();

				// If enough time has passed, enter recovery mode, which lifts some restrictions on who we can sync with and when we can mint
				long recoveryModeTimeout = Settings.getInstance().getRecoveryModeTimeout();
				if (NTP.getTime() - timePeersLastAvailable > recoveryModeTimeout) {
					if (!recoveryMode) {
						LOGGER.info(String.format("Peers have been unavailable for %d minutes. Entering recovery mode...", recoveryModeTimeout/60/1000));
						recoveryMode = true;
					}
				}
			} else {
				// We now have at least one peer with a recent block, so we can exit recovery mode and sync normally
				peersAvailable = true;
				if (recoveryMode) {
					LOGGER.info("Peers have become available again. Exiting recovery mode...");
					recoveryMode = false;
				}
			}
		}
		return recoveryMode;
	}

	public void addInferiorChainSignature(byte[] inferiorSignature) {
		// Update our list of inferior chain tips
		ByteArray inferiorChainSignature = ByteArray.wrap(inferiorSignature);
		if (!inferiorChainSignatures.contains(inferiorChainSignature))
			inferiorChainSignatures.add(inferiorChainSignature);
	}

	private static Peer getBestStaleCatchUpPeer(List<Peer> peers) {
		return peers.stream()
				.max((left, right) -> Controller.compareChainTipsByHeightThenTimestamp(left.getChainTipData(), right.getChainTipData()))
				.orElseThrow(IllegalArgumentException::new);
	}


	/**
	 * Iterate through a list of supplied peers, and attempt to find our common block with each.
	 * If a common block is found, its summary will be retained in the peer's commonBlockSummary property, for processing later.
	 * <p>
	 * Will return <tt>SynchronizationResult.OK</tt> on success.
	 * <p>
	 * @param peers
	 * @return SynchronizationResult.OK if the process completed successfully, or a different SynchronizationResult if something went wrong.
	 * @throws InterruptedException
	 */
	public SynchronizationResult findCommonBlocksWithPeers(List<Peer> peers) throws InterruptedException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			try {

				if (peers.isEmpty())
					return SynchronizationResult.NOTHING_TO_DO;

				// If our latest block is very old, it's best that we don't try and determine the best peers to sync to.
				// This is because it can involve very large chain comparisons, which is too intensive.
				// In reality, most forking problems occur near the chain tips, so we will reserve this functionality for those situations.
				final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
				if (minLatestBlockTimestamp == null)
					return SynchronizationResult.REPOSITORY_ISSUE;

				final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();
				boolean staleChainCatchUpActive = Controller.isStaleChainCatchUpActive(ourLatestBlockData, minLatestBlockTimestamp, NTP.getTime());
				if (ourLatestBlockData.getTimestamp() < minLatestBlockTimestamp) {
					if (!this.recoveryMode && !staleChainCatchUpActive) {
						LOGGER.debug(String.format("Our latest block is very old, so we won't collect common block info from peers"));
						return SynchronizationResult.NOTHING_TO_DO;
					}

					if (this.recoveryMode)
						LOGGER.debug("Recovery mode active; collecting common block info despite older local chain tip");
					else
						LOGGER.debug("Stale chain catch-up active; collecting common block info despite older local chain tip");
				}

				LOGGER.debug(String.format("Searching for common blocks with %d peers...", peers.size()));
				final long startTime = System.currentTimeMillis();
				int commonBlocksFound = 0;
				boolean wereNewRequestsMade = false;

				for (Peer peer : peers) {
					// Are we shutting down?
					if (Controller.isStopping())
						return SynchronizationResult.SHUTTING_DOWN;

					// Check if we can use the cached common block data, by comparing the peer's current chain tip against the peer's chain tip when we last found our common block
					if (peer.canUseCachedCommonBlockData()) {
						LOGGER.debug(String.format("Skipping peer %s because we already have the latest common block data in our cache. Cached common block sig is %.08s", peer, Base58.encode(peer.getCommonBlockData().getCommonBlockSummary().getSignature())));
						commonBlocksFound++;
						continue;
					}

					// Cached data is stale, so clear it and repopulate
					peer.setCommonBlockData(null);

					// Search for the common block
					Synchronizer.getInstance().findCommonBlockWithPeer(peer, repository);
					if (peer.getCommonBlockData() != null)
						commonBlocksFound++;

					// This round wasn't served entirely from the cache, so we may want to log the results
					wereNewRequestsMade = true;
				}

				if (wereNewRequestsMade) {
					final long totalTimeTaken = System.currentTimeMillis() - startTime;
					LOGGER.debug(String.format("Finished searching for common blocks with %d peer%s. Found: %d. Total time taken: %d ms", peers.size(), (peers.size() != 1 ? "s" : ""), commonBlocksFound, totalTimeTaken));
				}

				return SynchronizationResult.OK;
			} finally {
				repository.discardChanges(); // Free repository locks, if any, also in case anything went wrong
			}
		} catch (DataException e) {
			LOGGER.error("Repository issue during synchronization with peer", e);
			return SynchronizationResult.REPOSITORY_ISSUE;
		}
	}

	/**
	 * Attempt to find the find our common block with supplied peer.
	 * If a common block is found, its summary will be retained in the peer's commonBlockSummary property, for processing later.
	 * <p>
	 * Will return <tt>SynchronizationResult.OK</tt> on success.
	 * <p>
	 * @param peer
	 * @param repository
	 * @return SynchronizationResult.OK if the process completed successfully, or a different SynchronizationResult if something went wrong.
	 * @throws InterruptedException
	 */
	public SynchronizationResult findCommonBlockWithPeer(Peer peer, Repository repository) throws InterruptedException {
		try {
			final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();
			final int ourInitialHeight = ourLatestBlockData.getHeight();

			BlockSummaryData peerChainTipData = peer.getChainTipData();
			int peerHeight = peerChainTipData.getHeight();
			byte[] peersLastBlockSignature = peerChainTipData.getSignature();

			byte[] ourLastBlockSignature = ourLatestBlockData.getSignature();
			LOGGER.debug(String.format("Fetching summaries from peer %s at height %d, sig %.8s, ts %d; our height %d, sig %.8s, ts %d", peer,
					peerHeight, Base58.encode(peersLastBlockSignature), peerChainTipData.getTimestamp(),
					ourInitialHeight, Base58.encode(ourLastBlockSignature), ourLatestBlockData.getTimestamp()));

			List<BlockSummaryData> peerBlockSummaries = new ArrayList<>();
			SynchronizationResult findCommonBlockResult = fetchSummariesFromCommonBlock(repository, peer, ourInitialHeight, false, peerBlockSummaries, false);
			if (findCommonBlockResult != SynchronizationResult.OK) {
				// Logging performed by fetchSummariesFromCommonBlock() above
				peer.setCommonBlockData(null);
				return findCommonBlockResult;
			}

			// First summary is common block
			final BlockData commonBlockData = repository.getBlockRepository().fromSignature(peerBlockSummaries.get(0).getSignature());
			final BlockSummaryData commonBlockSummary = new BlockSummaryData(commonBlockData);
			final int commonBlockHeight = commonBlockData.getHeight();
			final byte[] commonBlockSig = commonBlockData.getSignature();
			final String commonBlockSig58 = Base58.encode(commonBlockSig);
			LOGGER.debug(String.format("Common block with peer %s is at height %d, sig %.8s, ts %d", peer,
					commonBlockHeight, commonBlockSig58, commonBlockData.getTimestamp()));
			peerBlockSummaries.remove(0);

			// Store the common block summary against the peer, and the current chain tip (for caching)
			peer.setCommonBlockData(new CommonBlockData(commonBlockSummary, peerChainTipData));

			return SynchronizationResult.OK;
		} catch (DataException e) {
			LOGGER.error("Repository issue during synchronization with peer", e);
			return SynchronizationResult.REPOSITORY_ISSUE;
		}
	}


	/**
	 * Compare a list of peers to determine the best peer(s) to sync to next.
	 * <p>
	 * Will return a filtered list of peers on success, or an identical list of peers on failure.
	 * This allows us to fall back to legacy behaviour (random selection from the entire list of peers), if we are unable to make the comparison.
	 * <p>
	 * @param peers
	 * @return a list of peers, possibly filtered.
	 * @throws InterruptedException
	 */
	public List<Peer> comparePeers(List<Peer> peers) throws InterruptedException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			try {

				// If our latest block is very old, it's best that we don't try and determine the best peers to sync to.
				// This is because it can involve very large chain comparisons, which is too intensive.
				// In reality, most forking problems occur near the chain tips, so we will reserve this functionality for those situations.
				Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
				if (minLatestBlockTimestamp == null)
					return peers;

				final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();
				boolean staleChainCatchUpActive = Controller.isStaleChainCatchUpActive(ourLatestBlockData, minLatestBlockTimestamp, NTP.getTime());
				if (ourLatestBlockData.getTimestamp() < minLatestBlockTimestamp) {
					if (!this.recoveryMode && !staleChainCatchUpActive) {
						LOGGER.debug(String.format("Our latest block is very old, so we won't filter the peers list"));
						return peers;
					}

					if (this.recoveryMode)
						LOGGER.debug("Recovery mode active; comparing peers despite older local chain tip");
					else
						LOGGER.debug("Stale chain catch-up active; comparing peers despite older local chain tip");
				}

				LOGGER.debug("Using same-length chain weight consensus algorithm");

				// Retrieve a list of unique common blocks from this list of peers
				List<BlockSummaryData> commonBlocks = this.uniqueCommonBlocks(peers);

				// Order common blocks by height, in ascending order
				// This is essential for the logic below to make the correct decisions when discarding chains - do not remove
				commonBlocks.sort((b1, b2) -> Integer.valueOf(b1.getHeight()).compareTo(Integer.valueOf(b2.getHeight())));

				// Get our latest height
				final int ourHeight = ourLatestBlockData.getHeight();

				// Create a placeholder to track of common blocks that we can discard due to being inferior chains
				int dropPeersAfterCommonBlockHeight = 0;

				NumberFormat accurateFormatter = new DecimalFormat("0.################E0");

				// Remove peers with no common block data
				Iterator iterator = peers.iterator();
				while (iterator.hasNext()) {
					Peer peer = (Peer) iterator.next();
					if (peer.getCommonBlockData() == null) {
						LOGGER.debug(String.format("Removed peer %s because it has no common block data", peer));
						iterator.remove();
					}
				}

				// Loop through each group of common blocks
				for (BlockSummaryData commonBlockSummary : commonBlocks) {
					List<Peer> peersSharingCommonBlock = peers.stream().filter(peer -> peer.getCommonBlockData().getCommonBlockSummary().equals(commonBlockSummary)).collect(Collectors.toList());

					// Check if we need to discard this group of peers
					if (dropPeersAfterCommonBlockHeight > 0) {
						if (commonBlockSummary.getHeight() > dropPeersAfterCommonBlockHeight) {
							// We have already determined that the correct chain diverged from a lower height. We are safe to skip these peers.
							for (Peer peer : peersSharingCommonBlock) {
								LOGGER.debug(String.format("Peer %s has common block at height %d but the superior chain is at height %d. Removing it from this round.", peer, commonBlockSummary.getHeight(), dropPeersAfterCommonBlockHeight));
								//this.addInferiorChainSignature(peer.getChainTipData().getLastBlockSignature());
							}
							continue;
						}
					}

					// Calculate the length of the shortest peer chain sharing this common block, including our chain
					final int ourAdditionalBlocksAfterCommonBlock = ourHeight - commonBlockSummary.getHeight();
					int minChainLength = this.calculateMinChainLengthOfPeers(peersSharingCommonBlock, commonBlockSummary);

					// Fetch block summaries from each peer
					Iterator peersSharingCommonBlockIterator = peersSharingCommonBlock.iterator();
					while (peersSharingCommonBlockIterator.hasNext()) {
						Peer peer = (Peer) peersSharingCommonBlockIterator.next();

						// If we're shutting down, just return the latest peer list
						if (Controller.isStopping())
							return peers;

						// Count the number of blocks this peer has beyond our common block
						final BlockSummaryData peerChainTipData = peer.getChainTipData();
						final int peerHeight = peerChainTipData.getHeight();
						final byte[] peerLastBlockSignature = peerChainTipData.getSignature();
						final int peerAdditionalBlocksAfterCommonBlock = peerHeight - commonBlockSummary.getHeight();
						// Limit the number of blocks we are comparing. FUTURE: we could request more in batches, but there may not be a case when this is needed
						int summariesRequired = Math.min(peerAdditionalBlocksAfterCommonBlock, MAXIMUM_REQUEST_SIZE);

						// Check if we can use the cached common block summaries, by comparing the peer's current chain tip against the peer's chain tip when we last found our common block
						boolean useCachedSummaries = false;
						if (peer.canUseCachedCommonBlockData()) {
							if (peer.getCommonBlockData().getBlockSummariesAfterCommonBlock() != null) {
								if (peer.getCommonBlockData().getBlockSummariesAfterCommonBlock().size() == summariesRequired) {
									LOGGER.trace(String.format("Using cached block summaries for peer %s", peer));
									useCachedSummaries = true;
								}
							}
						}

						if (!useCachedSummaries) {
							if (summariesRequired > 0) {
								LOGGER.trace(String.format("Requesting %d block summar%s from peer %s after common block %.8s. Peer height: %d", summariesRequired, (summariesRequired != 1 ? "ies" : "y"), peer, Base58.encode(commonBlockSummary.getSignature()), peerHeight));

								// Forget any cached summaries
								peer.getCommonBlockData().setBlockSummariesAfterCommonBlock(null);

								// Request new block summaries
								List<BlockSummaryData> blockSummaries = this.getBlockSummaries(peer, commonBlockSummary.getSignature(), summariesRequired);
								if (blockSummaries != null) {
									LOGGER.trace(String.format("Peer %s returned %d block summar%s", peer, blockSummaries.size(), (blockSummaries.size() != 1 ? "ies" : "y")));

									boolean fullPeerRangeRequested = summariesRequired == peerAdditionalBlocksAfterCommonBlock;

									if (blockSummaries.size() < summariesRequired)
										// This could mean that the peer has re-orged. Exclude this peer until they return the summaries we expect.
										LOGGER.debug(String.format("Peer %s returned %d block summar%s instead of expected %d - excluding them from this round", peer, blockSummaries.size(), (blockSummaries.size() != 1 ? "ies" : "y"), summariesRequired));
									else if (fullPeerRangeRequested && blockSummaryWithSignature(peerLastBlockSignature, blockSummaries) == null)
										// We requested the full range, so the peer's reported tip should be present.
										LOGGER.debug(String.format("Peer %s didn't return a block summary with signature %.8s - excluding them from this round", peer, Base58.encode(peerLastBlockSignature)));
									else
										// All looks good, so store the retrieved block summaries in the peer's cache
										peer.getCommonBlockData().setBlockSummariesAfterCommonBlock(blockSummaries);
								}
							} else {
								// There are no block summaries after this common block
								peer.getCommonBlockData().setBlockSummariesAfterCommonBlock(null);
							}
						}

						// Ignore this peer if it holds an invalid block
						if (this.containsInvalidBlockSummary(peer.getCommonBlockData().getBlockSummariesAfterCommonBlock())) {
							LOGGER.debug("Ignoring peer %s because it holds an invalid block", peer);
							peers.remove(peer);
							peersSharingCommonBlockIterator.remove();
							continue;
						}

						// Reduce minChainLength if needed. If we don't have any blocks, this peer will be excluded from chain weight comparisons later in the process, so we shouldn't update minChainLength
						List <BlockSummaryData> peerBlockSummaries = peer.getCommonBlockData().getBlockSummariesAfterCommonBlock();
						if (peerBlockSummaries != null && !peerBlockSummaries.isEmpty())
							if (peerBlockSummaries.size() < minChainLength)
								minChainLength = peerBlockSummaries.size();
					}

					// Fetch our corresponding block summaries. Limit to MAXIMUM_REQUEST_SIZE, in order to make the comparison fairer, as peers have been limited too
					final int ourSummariesRequired = Math.min(ourAdditionalBlocksAfterCommonBlock, MAXIMUM_REQUEST_SIZE);
					LOGGER.trace(String.format("About to fetch our block summaries from %d to %d. Our height: %d", commonBlockSummary.getHeight() + 1, commonBlockSummary.getHeight() + ourSummariesRequired, ourHeight));
					List<BlockSummaryData> ourBlockSummaries = repository.getBlockRepository().getBlockSummaries(commonBlockSummary.getHeight() + 1, commonBlockSummary.getHeight() + ourSummariesRequired);
					if (ourBlockSummaries.isEmpty()) {
						LOGGER.debug(String.format("We don't have any block summaries so can't compare our chain against peers with this common block. We can still compare them against each other."));
					}
					else {
						populateBlockSummariesMinterLevels(repository, ourBlockSummaries);
						// Reduce minChainLength if we have less summaries
						if (ourBlockSummaries.size() < minChainLength)
							minChainLength = ourBlockSummaries.size();
					}

					// Create array to hold peers for comparison
					List<Peer> superiorPeersForComparison = new ArrayList<>();

					// Calculate max height for chain weight comparisons
					int maxHeightForChainWeightComparisons = commonBlockSummary.getHeight() + minChainLength;

					// Calculate our chain weight
					BigInteger ourChainWeight = BigInteger.valueOf(0);
					if (!ourBlockSummaries.isEmpty())
						ourChainWeight = Block.calcChainWeight(commonBlockSummary.getHeight(), commonBlockSummary.getSignature(), ourBlockSummaries, maxHeightForChainWeightComparisons);

					LOGGER.debug(String.format("Our chain weight based on %d blocks is %s", minChainLength, accurateFormatter.format(ourChainWeight)));

					LOGGER.debug(String.format("Listing peers with common block %.8s...", Base58.encode(commonBlockSummary.getSignature())));
					for (Peer peer : peersSharingCommonBlock) {
						BlockSummaryData peerChainTipData = peer.getChainTipData();
						final int peerHeight = peerChainTipData.getHeight();
						final Long peerLastBlockTimestamp = peerChainTipData.getTimestamp();
						final int peerAdditionalBlocksAfterCommonBlock = peerHeight - commonBlockSummary.getHeight();
						final CommonBlockData peerCommonBlockData = peer.getCommonBlockData();

						if (peerCommonBlockData == null || peerCommonBlockData.getBlockSummariesAfterCommonBlock() == null || peerCommonBlockData.getBlockSummariesAfterCommonBlock().isEmpty()) {
							// No response - remove this peer for now
							LOGGER.debug(String.format("Peer %s doesn't have any block summaries - removing it from this round", peer));
							peers.remove(peer);
							continue;
						}

						// If peer is out of date (since our last check), we should exclude it from this round unless recovery mode is active.
						minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
						staleChainCatchUpActive = Controller.isStaleChainCatchUpActive(ourLatestBlockData, minLatestBlockTimestamp, NTP.getTime());
						if (!this.recoveryMode && !staleChainCatchUpActive && (peerLastBlockTimestamp == null || peerLastBlockTimestamp < minLatestBlockTimestamp)) {
							LOGGER.debug(String.format("Peer %s is out of date - removing it from this round", peer));
							peers.remove(peer);
							continue;
						}

						final List<BlockSummaryData> peerBlockSummariesAfterCommonBlock = peerCommonBlockData.getBlockSummariesAfterCommonBlock();
						populateBlockSummariesMinterLevels(repository, peerBlockSummariesAfterCommonBlock);

						// Calculate cumulative chain weight of this blockchain subset, from common block to highest mutual block held by all peers in this group.
						LOGGER.debug(String.format("About to calculate chain weight based on %d blocks for peer %s with common block %.8s (peer has %d blocks after common block)", minChainLength, peer, Base58.encode(commonBlockSummary.getSignature()), peerAdditionalBlocksAfterCommonBlock));
						BigInteger peerChainWeight = Block.calcChainWeight(commonBlockSummary.getHeight(), commonBlockSummary.getSignature(), peerBlockSummariesAfterCommonBlock, maxHeightForChainWeightComparisons);
						peer.getCommonBlockData().setChainWeight(peerChainWeight);
						LOGGER.debug(String.format("Chain weight of peer %s based on %d blocks (%d - %d) is %s", peer, minChainLength, peerBlockSummariesAfterCommonBlock.get(0).getHeight(), peerBlockSummariesAfterCommonBlock.get(peerBlockSummariesAfterCommonBlock.size()-1).getHeight(), accurateFormatter.format(peerChainWeight)));

						// Compare against our chain - if our blockchain has greater weight then don't synchronize with peer (or any others in this group)
						if (ourChainWeight.compareTo(peerChainWeight) > 0) {
							// This peer is on an inferior chain - remove it
							LOGGER.debug(String.format("Peer %s is on an inferior chain to us - removing it from this round", peer));
							peers.remove(peer);
						}
						else {
							// Our chain is inferior or equal
							LOGGER.debug(String.format("Peer %s is on an equal or better chain to us. We will compare the other peers sharing this common block against each other, and drop all peers sharing higher common blocks.", peer));
							dropPeersAfterCommonBlockHeight = commonBlockSummary.getHeight();
							superiorPeersForComparison.add(peer);
						}
					}

					// Now that we have selected the best peers, compare them against each other and remove any with lower weights
					if (!superiorPeersForComparison.isEmpty()) {
						BigInteger bestChainWeight = null;
						for (Peer peer : superiorPeersForComparison) {
							// Increase bestChainWeight if needed
							if (bestChainWeight == null || peer.getCommonBlockData().getChainWeight().compareTo(bestChainWeight) >= 0)
								bestChainWeight = peer.getCommonBlockData().getChainWeight();
						}
						for (Peer peer : superiorPeersForComparison) {
							// Check if we should discard an inferior peer
							if (peer.getCommonBlockData().getChainWeight().compareTo(bestChainWeight) < 0) {
								BigInteger difference = bestChainWeight.subtract(peer.getCommonBlockData().getChainWeight());
								LOGGER.debug(String.format("Peer %s has a lower chain weight (difference: %s) than other peer(s) in this group - removing it from this round.", peer, accurateFormatter.format(difference)));
								peers.remove(peer);
							}
						}
						// FUTURE: we may want to prefer peers with additional blocks, and compare the additional blocks against each other.
						// This would fast track us to the best candidate for the latest block.
						// Right now, peers with the exact same chain as us are treated equally to those with an additional block.
					}
				}

				return peers;
			} finally {
				repository.discardChanges(); // Free repository locks, if any, also in case anything went wrong
			}
		} catch (DataException e) {
			LOGGER.error("Repository issue during peer comparison", e);
			return peers;
		}
	}

	private List<BlockSummaryData> uniqueCommonBlocks(List<Peer> peers) {
		List<BlockSummaryData> commonBlocks = new ArrayList<>();

		for (Peer peer : peers) {
			if (peer.getCommonBlockData() != null && peer.getCommonBlockData().getCommonBlockSummary() != null) {
				LOGGER.trace(String.format("Peer %s has common block %.8s", peer, Base58.encode(peer.getCommonBlockData().getCommonBlockSummary().getSignature())));

				BlockSummaryData commonBlockSummary = peer.getCommonBlockData().getCommonBlockSummary();
				if (!commonBlocks.contains(commonBlockSummary))
					commonBlocks.add(commonBlockSummary);
			}
			else {
				LOGGER.trace(String.format("Peer %s has no common block data. Skipping...", peer));
			}
		}

		return commonBlocks;
	}

	private int calculateMinChainLengthOfPeers(List<Peer> peersSharingCommonBlock, BlockSummaryData commonBlockSummary) {
		// Calculate the length of the shortest peer chain sharing this common block
		int minChainLength = 0;
		for (Peer peer : peersSharingCommonBlock) {
			final int peerHeight = peer.getChainTipData().getHeight();
			final int peerAdditionalBlocksAfterCommonBlock = peerHeight - commonBlockSummary.getHeight();

			if (peerAdditionalBlocksAfterCommonBlock < minChainLength || minChainLength == 0)
				minChainLength = peerAdditionalBlocksAfterCommonBlock;
		}
		return minChainLength;
	}

	private BlockSummaryData blockSummaryWithSignature(byte[] signature, List<BlockSummaryData> blockSummaries) {
		if (blockSummaries != null)
			return blockSummaries.stream().filter(blockSummary -> Arrays.equals(blockSummary.getSignature(), signature)).findAny().orElse(null);
		return null;
	}



	/* Invalid block signature tracking */

	public Map<ByteArray, Long> getInvalidBlockSignatures() {
		return this.invalidBlockSignatures;
	}

	private void addInvalidBlockSignature(byte[] signature) {
		Long now = NTP.getTime();
		if (now == null) {
			return;
		}

		// Add or update existing entry
		invalidBlockSignatures.put(ByteArray.wrap(signature), now);
	}
	private void deleteOlderInvalidSignatures(Long now) {
		if (now == null) {
			return;
		}

		// Delete signatures with older timestamps
		Iterator it = invalidBlockSignatures.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry pair = (Map.Entry)it.next();
			Long lastSeen = (Long) pair.getValue();

			// Remove signature if we haven't seen it for more than 1 hour
			if (now - lastSeen > 60 * 60 * 1000L) {
				it.remove();
			}
		}
	}
	public boolean containsInvalidBlockSummary(List<BlockSummaryData> blockSummaries) {
		if (blockSummaries == null || invalidBlockSignatures == null) {
			return false;
		}

		// Loop through our known invalid blocks and check each one against supplied block summaries
		for (ByteArray invalidSignature : invalidBlockSignatures.keySet()) {
			for (BlockSummaryData blockSummary : blockSummaries) {
				byte[] signature = blockSummary.getSignature();
				if (Arrays.equals(signature, invalidSignature.value)) {
					return true;
				}
			}
		}
		return false;
	}
	private boolean containsInvalidBlockSignature(List<byte[]> blockSignatures) {
		if (blockSignatures == null || invalidBlockSignatures == null) {
			return false;
		}

		// Loop through our known invalid blocks and check each one against supplied block signatures
		for (ByteArray invalidSignature : invalidBlockSignatures.keySet()) {
			for (byte[] signature : blockSignatures) {
				if (Arrays.equals(signature, invalidSignature.value)) {
					return true;
				}
			}
		}
		return false;
	}


	/**
	 * Attempt to synchronize blockchain with peer.
	 * <p>
	 * Will return <tt>true</tt> if synchronization succeeded,
	 * even if no changes were made to our blockchain.
	 * <p>
	 * @param peer
	 * @return false if something went wrong, true otherwise.
	 * @throws InterruptedException
	 */
	public SynchronizationResult synchronize(Peer peer, boolean force) throws InterruptedException {
		// Make sure we're the only thread modifying the blockchain
		// If we're already synchronizing with another peer then this will also return fast
		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		if (!blockchainLock.tryLock(3, TimeUnit.SECONDS)) {
			// Wasn't peer's fault we couldn't sync
			LOGGER.info("Synchronizer couldn't acquire blockchain lock");
			return SynchronizationResult.NO_BLOCKCHAIN_LOCK;
		}
		final long lockHeldStartMillis = System.currentTimeMillis();

		try {
			try (final Repository repository = RepositoryManager.getRepository()) {
				try {
					final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();
					final int ourInitialHeight = ourLatestBlockData.getHeight();

					BlockSummaryData peerChainTipData = peer.getChainTipData();
					int peerHeight = peerChainTipData.getHeight();
					byte[] peersLastBlockSignature = peerChainTipData.getSignature();

					byte[] ourLastBlockSignature = ourLatestBlockData.getSignature();
					String syncString = String.format("Synchronizing with peer %s at height %d, sig %.8s, ts %d; our height %d, sig %.8s, ts %d", peer,
							peerHeight, Base58.encode(peersLastBlockSignature), peerChainTipData.getTimestamp(),
							ourInitialHeight, Base58.encode(ourLastBlockSignature), ourLatestBlockData.getTimestamp());
					LOGGER.info(syncString);

					// Reset last re-org size as we are starting a new sync round
					this.lastReorgSize = 0;

					// Set the initial value of timeValidBlockLastReceived if it's null
					Long now = NTP.getTime();
					if (this.timeValidBlockLastReceived == null) {
						this.timeValidBlockLastReceived = now;
					}

					// Delete invalid signatures with older timestamps
					this.deleteOlderInvalidSignatures(now);

					List<BlockSummaryData> peerBlockSummaries = new ArrayList<>();
					SynchronizationResult findCommonBlockResult = fetchSummariesFromCommonBlock(repository, peer, ourInitialHeight, force, peerBlockSummaries, true);
					if (findCommonBlockResult != SynchronizationResult.OK) {
						// Logging performed by fetchSummariesFromCommonBlock() above
						// Clear our common block cache for this peer
						peer.setCommonBlockData(null);
						return findCommonBlockResult;
					}

					// First summary is common block
					final BlockData commonBlockData = repository.getBlockRepository().fromSignature(peerBlockSummaries.get(0).getSignature());
					final int commonBlockHeight = commonBlockData.getHeight();
					final byte[] commonBlockSig = commonBlockData.getSignature();
					final String commonBlockSig58 = Base58.encode(commonBlockSig);
					LOGGER.debug(String.format("Common block with peer %s is at height %d, sig %.8s, ts %d", peer,
							commonBlockHeight, commonBlockSig58, commonBlockData.getTimestamp()));
					peerBlockSummaries.remove(0);

					// If common block height is higher than peer's last reported height
					// then peer must have a very recent sync. Update our idea of peer's height.
					if (commonBlockHeight > peerHeight) {
						LOGGER.debug(String.format("Peer height %d was lower than common block height %d - using higher value", peerHeight, commonBlockHeight));
						peerHeight = commonBlockHeight;
					}

					// If common block is peer's latest block then we simply have the same, or longer, chain to peer, so exit now
					if (commonBlockHeight == peerHeight) {
						if (peerHeight == ourInitialHeight)
							LOGGER.debug(String.format("We have the same blockchain as peer %s", peer));
						else
							LOGGER.debug(String.format("We have the same blockchain as peer %s, but longer", peer));

						return SynchronizationResult.NOTHING_TO_DO;
					}

					// Unless we're doing a forced sync, we might need to compare blocks after common block
					if (!force && ourInitialHeight > commonBlockHeight) {
						SynchronizationResult chainCompareResult = compareChains(repository, commonBlockData, ourLatestBlockData, peer, peerHeight, peerBlockSummaries);
						if (chainCompareResult != SynchronizationResult.OK)
							return chainCompareResult;
					}

					SynchronizationResult syncResult = null;
					if (commonBlockHeight < ourInitialHeight) {
						// Peer's chain is better, sync to that one
						syncResult = syncToPeerChain(repository, commonBlockData, ourInitialHeight, peer, peerHeight, peerBlockSummaries);
					} else {
						// Simply fetch and apply blocks as they arrive
						syncResult = applyNewBlocks(repository, commonBlockData, ourInitialHeight, peer, peerHeight, peerBlockSummaries);
					}

					if (syncResult != SynchronizationResult.OK)
						return syncResult;

					// Commit
					repository.saveChanges();

					// Create string for logging
					final BlockData newLatestBlockData = repository.getBlockRepository().getLastBlock();
					String syncLog = String.format("Synchronized with peer %s to height %d, sig %.8s, ts: %d", peer,
							newLatestBlockData.getHeight(), Base58.encode(newLatestBlockData.getSignature()),
							newLatestBlockData.getTimestamp());

					// Append re-org info
					if (this.lastReorgSize > 0) {
						syncLog = syncLog.concat(String.format(", size: %d", this.lastReorgSize));
					}

					// Log sync info
					LOGGER.info(syncLog);

					return SynchronizationResult.OK;
				} finally {
					repository.discardChanges(); // Free repository locks, if any, also in case anything went wrong
				}
			} catch (DataException e) {
				LOGGER.error("Repository issue during synchronization with peer", e);
				return SynchronizationResult.REPOSITORY_ISSUE;
			}
		} finally {
			final long lockHeldMillis = System.currentTimeMillis() - lockHeldStartMillis;
			if (lockHeldMillis >= 2000L) {
				LOGGER.info("Synchronizer held blockchain lock for {} ms with peer {}", lockHeldMillis, peer);
			}
			blockchainLock.unlock();
		}
	}

	/**
	 * Returns list of peer's block summaries starting with common block with peer.
	 * 
	 * @param peer
	 * @return block summaries, or empty list if no common block, or null if there was an issue
	 * @throws DataException
	 * @throws InterruptedException
	 */
	public SynchronizationResult fetchSummariesFromCommonBlock(Repository repository, Peer peer, int ourHeight, boolean force, List<BlockSummaryData> blockSummariesFromCommon, boolean infoLogWhenNotFound) throws DataException, InterruptedException {
		// Start by asking for a few recent block hashes as this will cover a majority of reorgs
		// Failing that, back off exponentially
		int step = INITIAL_BLOCK_STEP;

		int testHeight = Math.max(ourHeight - step, 1);
		BlockData testBlockData = null;

		List<BlockSummaryData> blockSummariesBatch = null;

		while (testHeight >= 1) {
			// Are we shutting down?
			if (Controller.isStopping())
				return SynchronizationResult.SHUTTING_DOWN;

			// Fetch our block signature at this height
			testBlockData = repository.getBlockRepository().fromHeight(testHeight);
			if (testBlockData == null) {
				// Not found? But we've locked the blockchain and height is below blockchain's tip!
				LOGGER.error("Failed to get block at height lower than blockchain tip during synchronization?");
				return SynchronizationResult.REPOSITORY_ISSUE;
			}

			// Ask for block signatures since test block's signature
			byte[] testSignature = testBlockData.getSignature();
			LOGGER.trace(String.format("Requesting %d summar%s after height %d", step, (step != 1 ? "ies": "y"), testHeight));
			blockSummariesBatch = this.getBlockSummaries(peer, testSignature, step);

			if (blockSummariesBatch == null) {
				if (infoLogWhenNotFound)
					LOGGER.info(String.format("Error while trying to find common block with peer %s", peer));
				else
					LOGGER.debug(String.format("Error while trying to find common block with peer %s", peer));
				
				// No response - give up this time
				return SynchronizationResult.NO_REPLY;
			}

			LOGGER.trace(String.format("Received %s summar%s", blockSummariesBatch.size(), (blockSummariesBatch.size() != 1 ? "ies" : "y")));

			// Empty list means remote peer is unaware of test signature OR has no new blocks after test signature
			if (!blockSummariesBatch.isEmpty())
				// We have entries so we have found a common block
				break;

			// No blocks after genesis block?
			if (testHeight == 1) {
				BlockSummaryData peerChainTipData = peer.getChainTipData();
				if (peerChainTipData != null
						&& peerChainTipData.getHeight() == 1
						&& Arrays.equals(peerChainTipData.getSignature(), testSignature)) {
					break;
				}

				LOGGER.info(String.format("Failure to find common block with peer %s", peer));
				return SynchronizationResult.NO_COMMON_BLOCK;
			}

			// If common block is too far behind us then we're on massively different forks so give up.
			if (!force && testHeight < ourHeight - MAXIMUM_COMMON_DELTA) {
				LOGGER.info(String.format("Blockchain too divergent with peer %s", peer));
				peer.setLastTooDivergentTime(NTP.getTime());
				return SynchronizationResult.TOO_DIVERGENT;
			}

			step <<= 1;
			step = Math.min(step, MAXIMUM_BLOCK_STEP);

			testHeight = Math.max(testHeight - step, 1);
		}

		// Peer not considered too divergent
		peer.setLastTooDivergentTime(0L);

		// Prepend test block's summary as first block summary, as summaries returned are *after* test block
		BlockSummaryData testBlockSummary = new BlockSummaryData(testBlockData);
		blockSummariesFromCommon.add(0, testBlockSummary);
		blockSummariesFromCommon.addAll(blockSummariesBatch);

		// Trim summaries so that first summary is common block.
		// Currently we work forward from common block until we hit a block we don't have
		// TODO: rewrite as modified binary search!
		int i;
		for (i = 1; i < blockSummariesFromCommon.size(); ++i) {
			if (Controller.isStopping())
				return SynchronizationResult.SHUTTING_DOWN;

			if (!repository.getBlockRepository().exists(blockSummariesFromCommon.get(i).getSignature()))
				break;
		}

		// Note: index i - 1 isn't cleared: List.subList is fromIndex inclusive to toIndex exclusive
		blockSummariesFromCommon.subList(0, i - 1).clear();

		return SynchronizationResult.OK;
	}

	private SynchronizationResult compareChains(Repository repository, BlockData commonBlockData, BlockData ourLatestBlockData,
			Peer peer, int peerHeight, List<BlockSummaryData> peerBlockSummaries) throws DataException, InterruptedException {
		final int commonBlockHeight = commonBlockData.getHeight();
		final byte[] commonBlockSig = commonBlockData.getSignature();

		// If our latest block is very old, we're very behind and should ditch our fork.
		final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
		if (minLatestBlockTimestamp == null)
			return SynchronizationResult.REPOSITORY_ISSUE;

		if (ourLatestBlockData.getTimestamp() < minLatestBlockTimestamp) {
			LOGGER.info(String.format("Ditching our chain after height %d", commonBlockHeight));
		} else {
			// Compare chain weights

			LOGGER.debug(String.format("Comparing chains from block %d with peer %s", commonBlockHeight + 1, peer));

			// Fetch remaining peer's block summaries (which we also use to fill signatures list)
			int peerBlockCount = peerHeight - commonBlockHeight;

			while (peerBlockSummaries.size() < peerBlockCount) {
				if (Controller.isStopping())
					return SynchronizationResult.SHUTTING_DOWN;

				int lastSummaryHeight = commonBlockHeight + peerBlockSummaries.size();
				byte[] previousSignature;
				if (peerBlockSummaries.isEmpty())
					previousSignature = commonBlockSig;
				else
					previousSignature = peerBlockSummaries.get(peerBlockSummaries.size() - 1).getSignature();

				List<BlockSummaryData> moreBlockSummaries = this.getBlockSummaries(peer, previousSignature, peerBlockCount - peerBlockSummaries.size());

				if (moreBlockSummaries == null || moreBlockSummaries.isEmpty()) {
					LOGGER.info(String.format("Peer %s failed to respond with block summaries after height %d, sig %.8s", peer,
							lastSummaryHeight, Base58.encode(previousSignature)));
					return SynchronizationResult.NO_REPLY;
				}

				// Check peer sent valid heights
				for (int i = 0; i < moreBlockSummaries.size(); ++i) {
					if (Controller.isStopping())
						return SynchronizationResult.SHUTTING_DOWN;

					++lastSummaryHeight;

					BlockSummaryData blockSummary = moreBlockSummaries.get(i);

					if (blockSummary.getHeight() != lastSummaryHeight) {
						LOGGER.info(String.format("Peer %s responded with invalid block summary for height %d, sig %.8s", peer,
								lastSummaryHeight, Base58.encode(blockSummary.getSignature())));
						return SynchronizationResult.NO_REPLY;
					}
				}

				peerBlockSummaries.addAll(moreBlockSummaries);
			}

			// Fetch our corresponding block summaries
			List<BlockSummaryData> ourBlockSummaries = repository.getBlockRepository().getBlockSummaries(commonBlockHeight + 1, ourLatestBlockData.getHeight());

			// Populate minter account levels for both lists of block summaries
			populateBlockSummariesMinterLevels(repository, ourBlockSummaries);
			populateBlockSummariesMinterLevels(repository, peerBlockSummaries);

			final int mutualHeight = commonBlockHeight + Math.min(ourBlockSummaries.size(), peerBlockSummaries.size());

			// Calculate cumulative chain weights of both blockchain subsets, from common block to highest mutual block.
			BigInteger ourChainWeight = Block.calcChainWeight(commonBlockHeight, commonBlockSig, ourBlockSummaries, mutualHeight);
			BigInteger peerChainWeight = Block.calcChainWeight(commonBlockHeight, commonBlockSig, peerBlockSummaries, mutualHeight);

			NumberFormat accurateFormatter = new DecimalFormat("0.################E0");
			LOGGER.debug(String.format("commonBlockHeight: %d, commonBlockSig: %.8s, ourBlockSummaries.size(): %d, peerBlockSummaries.size(): %d", commonBlockHeight, Base58.encode(commonBlockSig), ourBlockSummaries.size(), peerBlockSummaries.size()));
			LOGGER.debug(String.format("Our chain weight: %s, peer's chain weight: %s (higher is better)", accurateFormatter.format(ourChainWeight), accurateFormatter.format(peerChainWeight)));

			// If our blockchain has greater weight then don't synchronize with peer
			if (ourChainWeight.compareTo(peerChainWeight) >= 0) {
				LOGGER.debug(String.format("Not synchronizing with peer %s as we have better blockchain", peer));
				return SynchronizationResult.INFERIOR_CHAIN;
			}
		}

		return SynchronizationResult.OK;
	}

	private SynchronizationResult syncToPeerChain(Repository repository, BlockData commonBlockData, int ourInitialHeight,
												  Peer peer, final int peerHeight, List<BlockSummaryData> peerBlockSummaries) throws DataException, InterruptedException {
		final int commonBlockHeight = commonBlockData.getHeight();
		final byte[] commonBlockSig = commonBlockData.getSignature();
		String commonBlockSig58 = Base58.encode(commonBlockSig);

		byte[] latestPeerSignature = commonBlockSig;
		int height = commonBlockHeight;

		LOGGER.debug(() -> String.format("Fetching peer %s chain from height %d, sig %.8s", peer, commonBlockHeight, commonBlockSig58));

		final int maxRetries = Settings.getInstance().getMaxRetries();

		// Overall plan: fetch peer's blocks first, then orphan, then apply

		// Convert any leftover (post-common) block summaries into signatures to request from peer
		List<byte[]> peerBlockSignatures = peerBlockSummaries.stream().map(BlockSummaryData::getSignature).collect(Collectors.toList());

		// Keep a list of blocks received so far
		List<Block> peerBlocks = new ArrayList<>();

		// Calculate the total number of additional blocks this peer has beyond the common block
		int additionalPeerBlocksAfterCommonBlock = peerHeight - commonBlockHeight;
		// Subtract the number of signatures that we already have, as we don't need to request them again
		int numberSignaturesRequired = additionalPeerBlocksAfterCommonBlock - peerBlockSignatures.size();

		int retryCount = 0;

		// Keep fetching blocks from peer until we reach their tip, or reach a count of MAXIMUM_COMMON_DELTA blocks.
		// We need to limit the total number, otherwise too much can be loaded into memory, causing an
		// OutOfMemoryException. This is common when syncing from 1000+ blocks behind the chain tip, after starting
		// from a small fork that didn't become part of the main chain. This causes the entire sync process to
		// use syncToPeerChain(), resulting in potentially thousands of blocks being held in memory if the limit
		// below isn't applied.
		while (height < peerHeight && peerBlocks.size() <= MAXIMUM_COMMON_DELTA) {
			if (Controller.isStopping())
				return SynchronizationResult.SHUTTING_DOWN;

			// Ensure we don't request more than MAXIMUM_REQUEST_SIZE
			int numberRequested = Math.min(numberSignaturesRequired, MAXIMUM_REQUEST_SIZE);

			// Do we need more signatures?
			if (peerBlockSignatures.isEmpty() && numberRequested > 0) {
				LOGGER.trace(String.format("Requesting %d signature%s after height %d, sig %.8s",
						numberRequested, (numberRequested != 1 ? "s" : ""), height, Base58.encode(latestPeerSignature)));

				peerBlockSignatures = this.getBlockSignatures(peer, latestPeerSignature, numberRequested);

				if (peerBlockSignatures == null || peerBlockSignatures.isEmpty()) {
					LOGGER.info(String.format("Peer %s failed to respond with more block signatures after height %d, sig %.8s", peer,
							height, Base58.encode(latestPeerSignature)));

					// Clear our cache of common block summaries for this peer, as they are likely to be invalid
					CommonBlockData cachedCommonBlockData = peer.getCommonBlockData();
					if (cachedCommonBlockData != null)
						cachedCommonBlockData.setBlockSummariesAfterCommonBlock(null);

                    // If we have already received newer blocks from this peer that what we have already, go ahead and apply them
                    if (!peerBlocks.isEmpty()) {
						final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();
						final Block peerLatestBlock = peerBlocks.get(peerBlocks.size() - 1);
						final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
						if (ourLatestBlockData != null && peerLatestBlock != null && minLatestBlockTimestamp != null) {

							// If our latest block is very old....
							if (ourLatestBlockData.getTimestamp() < minLatestBlockTimestamp) {
								// ... and we have received a block that is more recent than our latest block ...
								if (peerLatestBlock.getBlockData().getTimestamp() > ourLatestBlockData.getTimestamp()) {
									// ... then apply the blocks, as it takes us a step forward.
									// This is particularly useful when starting up a node that was on a small fork when it was last shut down.
									// In these cases, we now allow the node to sync forward, and get onto the main chain again.
									// Without this, we would require that the node syncs ENTIRELY with this peer,
									// and any problems downloading a block would cause all progress to be lost.
									LOGGER.debug(String.format("Newly received blocks are %d ms newer than our latest block - so we will apply them", peerLatestBlock.getBlockData().getTimestamp() - ourLatestBlockData.getTimestamp()));
									break;
								}
							}
						}
                    }
					// Otherwise, give up and move on to the next peer, to avoid putting our chain into an outdated or incomplete state
                    return SynchronizationResult.NO_REPLY;
                }

				numberSignaturesRequired = peerHeight - height - peerBlockSignatures.size();
				LOGGER.trace(String.format("Received %s signature%s", peerBlockSignatures.size(), (peerBlockSignatures.size() != 1 ? "s" : "")));
			}

			if (peerBlockSignatures.isEmpty()) {
				LOGGER.trace(String.format("No more signatures or blocks to request from peer %s", peer));
				break;
			}

			// Catch a block with an invalid signature before orphaning, so that we retain our existing valid candidate
			if (this.containsInvalidBlockSignature(peerBlockSignatures)) {
				LOGGER.info(String.format("Peer %s sent invalid block signature: %.8s", peer, Base58.encode(latestPeerSignature)));
				return SynchronizationResult.INVALID_DATA;
			}

			// Final check to make sure the peer isn't out of date, unless recovery mode is active.
			if (peer.getChainTipData() != null) {
				final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
				final Long peerLastBlockTimestamp = peer.getChainTipData().getTimestamp();
				final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();
				final boolean staleChainCatchUpActive = Controller.isStaleChainCatchUpActive(ourLatestBlockData, minLatestBlockTimestamp, NTP.getTime());
				if (!this.recoveryMode && !staleChainCatchUpActive && (peerLastBlockTimestamp == null || peerLastBlockTimestamp < minLatestBlockTimestamp)) {
					LOGGER.info(String.format("Peer %s is out of date, so abandoning sync attempt", peer));
					return SynchronizationResult.CHAIN_TIP_TOO_OLD;
				}
			}

			byte[] nextPeerSignature = peerBlockSignatures.get(0);
			int nextHeight = height + 1;

			LOGGER.trace(String.format("Fetching block %d, sig %.8s from %s", nextHeight, Base58.encode(nextPeerSignature), peer));
			Block newBlock = this.fetchBlock(repository, peer, nextPeerSignature);

			if (newBlock == null) {
				LOGGER.info(String.format("Peer %s failed to respond with block for height %d, sig %.8s", peer,
						nextHeight, Base58.encode(nextPeerSignature)));

				if (retryCount >= maxRetries) {
					// If we have already received newer blocks from this peer that what we have already, go ahead and apply them
					if (!peerBlocks.isEmpty()) {
						final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();
						final Block peerLatestBlock = peerBlocks.get(peerBlocks.size() - 1);
						final Long minLatestBlockTimestamp = Controller.getMinimumLatestBlockTimestamp();
						if (ourLatestBlockData != null && peerLatestBlock != null && minLatestBlockTimestamp != null) {

							// If our latest block is very old....
							if (ourLatestBlockData.getTimestamp() < minLatestBlockTimestamp) {
								// ... and we have received a block that is more recent than our latest block ...
								if (peerLatestBlock.getBlockData().getTimestamp() > ourLatestBlockData.getTimestamp()) {
									// ... then apply the blocks, as it takes us a step forward.
									// This is particularly useful when starting up a node that was on a small fork when it was last shut down.
									// In these cases, we now allow the node to sync forward, and get onto the main chain again.
									// Without this, we would require that the node syncs ENTIRELY with this peer,
									// and any problems downloading a block would cause all progress to be lost.
									LOGGER.debug(String.format("Newly received blocks are %d ms newer than our latest block - so we will apply them", peerLatestBlock.getBlockData().getTimestamp() - ourLatestBlockData.getTimestamp()));
									break;
								}
							}
						}
					}
					// Otherwise, give up and move on to the next peer, to avoid putting our chain into an outdated or incomplete state
					return SynchronizationResult.NO_REPLY;

				} else {
					// Re-fetch signatures, in case the peer is now on a different fork
					peerBlockSignatures.clear();
					numberSignaturesRequired = peerHeight - height;

					// Retry until retryCount reaches maxRetries
					retryCount++;
					int triesRemaining = maxRetries - retryCount;
					LOGGER.info(String.format("Re-issuing request to peer %s (%d attempt%s remaining)", peer, triesRemaining, (triesRemaining != 1 ? "s" : "")));
					continue;
				}
			}

			// Reset retryCount because the last request succeeded
			retryCount = 0;

			LOGGER.trace(String.format("Fetched block %d, sig %.8s from %s", nextHeight, Base58.encode(latestPeerSignature), peer));

			if (!newBlock.isSignatureValid()) {
				LOGGER.info(String.format("Peer %s sent block with invalid signature for height %d, sig %.8s", peer,
						nextHeight, Base58.encode(latestPeerSignature)));
				return SynchronizationResult.INVALID_DATA;
			}

			// Transactions are transmitted without approval status so determine that now
			for (Transaction transaction : newBlock.getTransactions())
				transaction.setInitialApprovalStatus();

			peerBlocks.add(newBlock);

			// Now that we've received this block, we can increase our height and move on to the next one
			latestPeerSignature = nextPeerSignature;
			peerBlockSignatures.remove(0);
			++height;
		}

		// Unwind to common block (unless common block is our latest block)
		int ourHeight = ourInitialHeight;
		LOGGER.debug(String.format("Orphaning blocks back to common block height %d, sig %.8s. Our height: %d", commonBlockHeight, commonBlockSig58, ourHeight));
		int reorgSize = ourHeight - commonBlockHeight;

		BlockData orphanBlockData = repository.getBlockRepository().fromHeight(ourInitialHeight);
		while (ourHeight > commonBlockHeight) {
			if (Controller.isStopping())
				return SynchronizationResult.SHUTTING_DOWN;

			Block block = new Block(repository, orphanBlockData);
			block.orphan();

			LOGGER.trace(String.format("Orphaned block height %d, sig %.8s", ourHeight, Base58.encode(orphanBlockData.getSignature())));

			repository.saveChanges();

			--ourHeight;
			orphanBlockData = repository.getBlockRepository().fromHeight(ourHeight);

			repository.discardChanges(); // clear transaction status to prevent deadlocks
			Controller.getInstance().onOrphanedBlock(orphanBlockData);
		}

		LOGGER.debug(String.format("Orphaned blocks back to height %d, sig %.8s - applying new blocks from peer %s", commonBlockHeight, commonBlockSig58, peer));

		for (Block newBlock : peerBlocks) {
			if (Controller.isStopping())
				return SynchronizationResult.SHUTTING_DOWN;

			newBlock.preProcess();

			ValidationResult blockResult = newBlock.isValid();
			if (blockResult != ValidationResult.OK) {
				LOGGER.info(String.format("Peer %s sent invalid block for height %d, sig %.8s: %s", peer,
						newBlock.getBlockData().getHeight(), Base58.encode(newBlock.getSignature()), blockResult.name()));
				this.addInvalidBlockSignature(newBlock.getSignature());
				this.timeInvalidBlockLastReceived = NTP.getTime();
				return SynchronizationResult.INVALID_DATA;
			}

			// Block is valid
			this.timeValidBlockLastReceived = NTP.getTime();

			// Save transactions attached to this block
			for (Transaction transaction : newBlock.getTransactions()) {
				TransactionData transactionData = transaction.getTransactionData();
				repository.getTransactionRepository().save(transactionData);
			}

			newBlock.process();

			LOGGER.trace(String.format("Processed block height %d, sig %.8s", newBlock.getBlockData().getHeight(), Base58.encode(newBlock.getBlockData().getSignature())));

			repository.saveChanges();

			synchronized (this.syncLock) {
				updateSyncProgressLocked(newBlock.getBlockData().getHeight(), getPeerTargetHeight(peer));
			}

			Controller.getInstance().onNewBlock(newBlock.getBlockData());
		}

		this.lastReorgSize = reorgSize;
		return SynchronizationResult.OK;
	}

    private SynchronizationResult applyNewBlocks(Repository repository, BlockData commonBlockData, int ourInitialHeight,
                                                 Peer peer, int peerHeight, List<BlockSummaryData> peerBlockSummaries) throws InterruptedException, DataException {

        //final BlockData ourLatestBlockData = repository.getBlockRepository().getLastBlock();

        int blocksBehind = peerHeight - ourInitialHeight;
        if (Settings.getInstance().isFastSyncEnabled() && blocksBehind >= MAXIMUM_REQUEST_SIZE) {
            // This peer supports syncing multiple blocks at once via GetBlocksMessage, and it is enabled in the settings
            return this.applyNewBlocksUsingFastSync(repository, commonBlockData, ourInitialHeight, peer, peerHeight, peerBlockSummaries);
        }
        else {
            // Fast sync is disabled in the settings, or the peer is close enough to use slow sync
            return this.applyNewBlocksUsingSlowSync(repository, commonBlockData, ourInitialHeight, peer, peerHeight, peerBlockSummaries);
        }
    }

    private SynchronizationResult applyNewBlocksUsingFastSync(Repository repository, BlockData commonBlockData, int ourInitialHeight,
                                                              Peer peer, int peerHeight, List<BlockSummaryData> peerBlockSummaries) throws InterruptedException, DataException {
        LOGGER.debug(String.format("Fetching new blocks from peer %s using fast sync", peer));

        final int commonBlockHeight = commonBlockData.getHeight();
        final byte[] commonBlockSig = commonBlockData.getSignature();
        byte[] latestPeerSignature = commonBlockSig;

        int ourHeight = ourInitialHeight;

        // Fetch, and apply, blocks from peer
        int maxBatchHeight = commonBlockHeight + SYNC_BATCH_SIZE;

        // Ensure that we don't request more blocks than specified in the settings
        int maxBlocksPerRequest = Settings.getInstance().getMaxBlocksPerRequest();

        while (ourHeight < peerHeight && ourHeight < maxBatchHeight) {
            if (Controller.isStopping())
                return SynchronizationResult.SHUTTING_DOWN;

            int numberRequested = Math.min(maxBatchHeight - ourHeight, maxBlocksPerRequest);

            List<Block> blocks = this.fetchBlocks(repository, peer, latestPeerSignature, numberRequested);

            if (blocks == null || blocks.isEmpty()) {
                LOGGER.warn(String.format("Peer %s failed to respond with more blocks after height %d, sig %.8s", peer,
                        ourHeight, Base58.encode(latestPeerSignature)));
                return SynchronizationResult.NO_REPLY;
            }

            LOGGER.debug("Received {} blocks after height {}, sig {} from {}", blocks.size(), ourHeight, Base58.encode(latestPeerSignature), peer);

            boolean errorInBatch = false;
            SynchronizationResult errorCode = SynchronizationResult.OK;

            repository.setSavepoint();

            for (Block newBlock : blocks) {
                if (Controller.isStopping()){
                    errorInBatch = true;
                    errorCode = SynchronizationResult.SHUTTING_DOWN;
                    break;
                }

                // Increment height at the start, but we'll only use it if block processing succeeds
                int expectedHeight = ourHeight + 1;

                if (newBlock == null) {
                    LOGGER.debug(String.format("Peer %s failed to respond with block for height %d, sig %.8s", peer,
                            expectedHeight, Base58.encode(latestPeerSignature)));
                    errorInBatch = true;
                    errorCode = SynchronizationResult.NO_REPLY;
                    break; // Stop processing batch - can't trust subsequent blocks
                }

                if (!newBlock.isSignatureValid()) {
                    LOGGER.debug(String.format("Peer %s sent block with invalid signature for height %d, sig %.8s", peer,
                            expectedHeight, Base58.encode(latestPeerSignature)));
                    errorInBatch = true;
                    errorCode = SynchronizationResult.INVALID_DATA;
                    break; // Stop processing batch - can't trust subsequent blocks
                }

                // Set the repository, because we couldn't do that when originally constructing the Block
                newBlock.setRepository(repository);

                // Transactions are transmitted without approval status so determine that now
                for (Transaction transaction : newBlock.getTransactions()) {
                    transaction.setInitialApprovalStatus();
                }

                ValidationResult blockResult = newBlock.isValid();
                if (blockResult != ValidationResult.OK) {
                    LOGGER.warn(String.format("Peer %s sent invalid block for height %d, sig %.8s: %s", peer,
                            expectedHeight, Base58.encode(latestPeerSignature), blockResult.name()));
                    errorInBatch = true;
                    errorCode = SynchronizationResult.INVALID_DATA;
                    break; // Stop processing batch - can't trust subsequent blocks
                }

                // Block is valid - now we can increment height and process it
                ++ourHeight;

                // Save transactions attached to this block
                for (Transaction transaction : newBlock.getTransactions()) {
                    TransactionData transactionData = transaction.getTransactionData();
                    repository.getTransactionRepository().save(transactionData);
                }

                newBlock.process();

                LOGGER.trace(String.format("Processed block height %d, sig %.8s", newBlock.getBlockData().getHeight(), Base58.encode(newBlock.getBlockData().getSignature())));

                Controller.getInstance().onNewBlock(newBlock.getBlockData());

                // Update latestPeerSignature so that subsequent batches start requesting from the correct block
                latestPeerSignature = newBlock.getSignature();
            }

            if(errorInBatch) {  // if error
                repository.rollbackToSavepoint();
                return errorCode;
            }
            else{
                repository.saveChanges();
            }
        }
        return SynchronizationResult.OK;
    }

    private SynchronizationResult applyNewBlocksUsingSlowSync(Repository repository, BlockData commonBlockData, int ourInitialHeight,
                                                              Peer peer, int peerHeight, List<BlockSummaryData> peerBlockSummaries) throws InterruptedException, DataException {
        LOGGER.debug(String.format("Fetching new blocks from peer %s using slow sync", peer));

        final int commonBlockHeight = commonBlockData.getHeight();
        final byte[] commonBlockSig = commonBlockData.getSignature();

        int ourHeight = ourInitialHeight;

        // Fetch, and apply, blocks from peer
        byte[] latestPeerSignature = commonBlockSig;
        int maxBatchHeight = commonBlockHeight + SYNC_BATCH_SIZE;

        // Convert any block summaries from above into signatures to request from peer
        List<byte[]> peerBlockSignatures = peerBlockSummaries.stream().map(BlockSummaryData::getSignature).collect(Collectors.toList());

        while (ourHeight < peerHeight && ourHeight < maxBatchHeight) {
            if (Controller.isStopping())
                return SynchronizationResult.SHUTTING_DOWN;

            // Do we need more signatures?
            if (peerBlockSignatures.isEmpty()) {
                int numberRequested = Math.min(maxBatchHeight - ourHeight, MAXIMUM_REQUEST_SIZE);

                LOGGER.trace(String.format("Requesting %d signature%s after height %d, sig %.8s",
                        numberRequested, (numberRequested != 1 ? "s": ""), ourHeight, Base58.encode(latestPeerSignature)));

                peerBlockSignatures = this.getBlockSignatures(peer, latestPeerSignature, numberRequested);

                if (peerBlockSignatures == null || peerBlockSignatures.isEmpty()) {
                    LOGGER.info(String.format("Peer %s failed to respond with more block signatures after height %d, sig %.8s", peer,
                            ourHeight, Base58.encode(latestPeerSignature)));
                    return SynchronizationResult.NO_REPLY;
                }

                LOGGER.trace(String.format("Received %s signature%s", peerBlockSignatures.size(), (peerBlockSignatures.size() != 1 ? "s" : "")));
            }

            latestPeerSignature = peerBlockSignatures.get(0);
            peerBlockSignatures.remove(0);
            ++ourHeight;

            LOGGER.trace(String.format("Fetching block %d, sig %.8s from %s", ourHeight, Base58.encode(latestPeerSignature), peer));
            Block newBlock = this.fetchBlock(repository, peer, latestPeerSignature);
            LOGGER.trace(String.format("Fetched block %d, sig %.8s from %s", ourHeight, Base58.encode(latestPeerSignature), peer));

            if (newBlock == null) {
                LOGGER.info(String.format("Peer %s failed to respond with block for height %d, sig %.8s", peer,
                        ourHeight, Base58.encode(latestPeerSignature)));
                return SynchronizationResult.NO_REPLY;
            }

            if (!newBlock.isSignatureValid()) {
                LOGGER.info(String.format("Peer %s sent block with invalid signature for height %d, sig %.8s", peer,
                        ourHeight, Base58.encode(latestPeerSignature)));
                return SynchronizationResult.INVALID_DATA;
            }

            // Transactions are transmitted without approval status so determine that now
            for (Transaction transaction : newBlock.getTransactions())
                transaction.setInitialApprovalStatus();

            ValidationResult blockResult = newBlock.isValid();
            if (blockResult != ValidationResult.OK) {
                LOGGER.info(String.format("Peer %s sent invalid block for height %d, sig %.8s: %s", peer,
                        ourHeight, Base58.encode(latestPeerSignature), blockResult.name()));
                return SynchronizationResult.INVALID_DATA;
            }

            // Save transactions attached to this block
            for (Transaction transaction : newBlock.getTransactions()) {
                TransactionData transactionData = transaction.getTransactionData();
                repository.getTransactionRepository().save(transactionData);
            }

            newBlock.process();

            LOGGER.trace(String.format("Processed block height %d, sig %.8s", newBlock.getBlockData().getHeight(), Base58.encode(newBlock.getBlockData().getSignature())));

            repository.saveChanges();

            Controller.getInstance().onNewBlock(newBlock.getBlockData());
        }

        return SynchronizationResult.OK;
    }

	private List<BlockSummaryData> getBlockSummaries(Peer peer, byte[] parentSignature, int numberRequested) throws InterruptedException {
		Message getBlockSummariesMessage = new GetBlockSummariesMessage(parentSignature, numberRequested);

		// Use shorter timeout for sync operations to avoid blocking transaction processing
		Message message = peer.getResponseWithTimeout(getBlockSummariesMessage, SYNC_RESPONSE_TIMEOUT);
		if (message == null)
			return null;

		if (message.getType() == MessageType.BLOCK_SUMMARIES) {
			BlockSummariesMessage blockSummariesMessage = (BlockSummariesMessage) message;
			return blockSummariesMessage.getBlockSummaries();
		}

		return null;
	}

	private List<byte[]> getBlockSignatures(Peer peer, byte[] parentSignature, int numberRequested) throws InterruptedException {
		Message getSignaturesMessage = new GetSignaturesMessage(parentSignature, numberRequested);

		// Use shorter timeout for sync operations to avoid blocking transaction processing
		Message message = peer.getResponseWithTimeout(getSignaturesMessage, SYNC_RESPONSE_TIMEOUT);
		if (message == null || message.getType() != MessageType.SIGNATURES)
			return null;

		SignaturesMessage signaturesMessage = (SignaturesMessage) message;

		return signaturesMessage.getSignatures();
	}

	private Block fetchBlock(Repository repository, Peer peer, byte[] signature) throws InterruptedException {
		Message getBlockMessage = new GetBlockMessage(signature);

		// Use shorter timeout for sync operations to avoid blocking transaction processing
		Message message = peer.getResponseWithTimeout(getBlockMessage, SYNC_RESPONSE_TIMEOUT);
		if (message == null) {
			peer.getPeerData().incrementFailedSyncCount();
			if (peer.getPeerData().getFailedSyncCount() >= MAX_CONSECUTIVE_FAILED_SYNC_ATTEMPTS) {
				// Several failed attempts, so mark peer as misbehaved
				LOGGER.info("Marking peer {} as misbehaved due to {} failed sync attempts", peer, peer.getPeerData().getFailedSyncCount());
				Network.getInstance().peerMisbehaved(peer);
			}
			return null;
		}

		// Reset failed sync count now that we have a block response
		// FUTURE: we could move this to the end of the sync process, but to reduce risk this can be done
		// at a later stage. For now we are only defending against serialization errors or no responses.
		peer.getPeerData().setFailedSyncCount(0);

		switch (message.getType()) {
			case BLOCK: {
				BlockMessage blockMessage = (BlockMessage) message;
				return new Block(repository, blockMessage.getBlockData(), blockMessage.getTransactions(), blockMessage.getAtStatesHash());
			}

			default:
				return null;
		}
	}

    private List<Block> fetchBlocks(Repository repository, Peer peer, byte[] parentSignature, int numberRequested) throws InterruptedException {
        LOGGER.trace("Building GetBlocksMessage with parentSignature: {}, numberRequested: {}", parentSignature, numberRequested);
        Message getBlocksMessage = new GetBlocksMessage(parentSignature, numberRequested);

        Message message = peer.getResponseWithTimeout(getBlocksMessage, FETCH_BLOCKS_TIMEOUT);
        if (message == null || message.getType() != MessageType.BLOCKS) {
            LOGGER.warn("Received a null BLOCKS payload from {}", peer);
            return null;
        }

        BlocksMessage blocksMessage = (BlocksMessage) message;
        if (blocksMessage == null || blocksMessage.getBlocks() == null) {
            return null;
        }

        return blocksMessage.getBlocks();
    }

	public void populateBlockSummariesMinterLevels(Repository repository, List<BlockSummaryData> blockSummaries) throws DataException {
		final int firstBlockHeight = blockSummaries.get(0).getHeight();

		for (int i = 0; i < blockSummaries.size(); ++i) {
			if (Controller.isStopping())
				return;

			BlockSummaryData blockSummary = blockSummaries.get(i);

			// Minter is always an online self-share, so find actual minter and get their effective minting level.
			Integer minterLevel = Account.getRewardShareEffectiveMintingLevelIfPresent(repository, blockSummary.getMinterPublicKey());
			if (minterLevel == null) {
				// It looks like this block's minter's reward-share has been cancelled.
				// So search for REWARD_SHARE transactions since common block to find missing minter info
				List<byte[]> transactionSignatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(Transaction.TransactionType.REWARD_SHARE, null, firstBlockHeight, null);

				for (byte[] transactionSignature : transactionSignatures) {
					RewardShareTransactionData transactionData = (RewardShareTransactionData) repository.getTransactionRepository().fromSignature(transactionSignature);

					if (transactionData != null && Arrays.equals(transactionData.getRewardSharePublicKey(), blockSummary.getMinterPublicKey())) {
						Account rewardShareMinter = new PublicKeyAccount(repository, transactionData.getMinterPublicKey());
						if (!rewardShareMinter.getAddress().equals(transactionData.getRecipient()))
							continue;

						minterLevel = rewardShareMinter.getEffectiveMintingLevel();
						break;
					}
				}

				if (minterLevel == null) {
					// We don't want to throw, or use an unknown level, as this will kill Controller thread and make client unstable.
					// So we log this but use 1 instead
					LOGGER.debug(() -> String.format("Unable to resolve effective minter level for reward-share %s - using 1 instead!", Base58.encode(blockSummary.getMinterPublicKey())));
					minterLevel = 1;
				}
			}

			blockSummary.setMinterLevel(minterLevel);
		}
	}

}
