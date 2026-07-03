package org.qortium.controller;

import org.junit.Test;
import org.qortium.controller.Synchronizer.RecoveryWatchdogAction;

import static org.junit.Assert.assertEquals;

/**
 * Pure unit tests for the Tier-3 fork-recovery watchdog gate logic
 * ({@link Synchronizer#decideRecoveryWatchdogAction}). No chain / network / NTP access.
 */
public class RecoveryWatchdogTests {

	private static final int MIN_PEERS = 2;
	private static final long THRESHOLD = 5 * 60 * 1000L;   // 5 min
	private static final long COOLDOWN = 10 * 60 * 1000L;   // 10 min
	private static final int CEILING = 3;
	private static final long NOW = 2_000_000_000L;

	private static RecoveryWatchdogAction decide(boolean stale, int higherPeers, boolean activeEpisode,
			long stuckSince, long lastOrphan, int orphanCount) {
		return Synchronizer.decideRecoveryWatchdogAction(stale, higherPeers, activeEpisode,
				NOW, stuckSince, lastOrphan, orphanCount, MIN_PEERS, THRESHOLD, COOLDOWN, CEILING);
	}

	@Test
	public void testHealthyNodeIsNeverActioned() {
		// Not stale -> never acts, even with an apparently-established episode and many higher peers.
		assertEquals(RecoveryWatchdogAction.NONE,
				decide(false, 5, true, NOW - THRESHOLD * 2, 0L, 0));
	}

	@Test
	public void testIsolatedOrSubQuorumDoesNotAct() {
		// Stale but no fresh-higher-peer quorum (dead-network / isolated minter) -> NONE.
		assertEquals(RecoveryWatchdogAction.NONE, decide(true, 0, false, 0L, 0L, 0));
		assertEquals(RecoveryWatchdogAction.NONE, decide(true, MIN_PEERS - 1, false, 0L, 0L, 0));
	}

	@Test
	public void testNewEpisodeArmsTheTimer() {
		// Stale + quorum + no active episode for this tip -> start the sustained timer.
		assertEquals(RecoveryWatchdogAction.ARM, decide(true, MIN_PEERS, false, 0L, 0L, 0));
	}

	@Test
	public void testSustainedGateWaitsUntilThreshold() {
		// Active episode but not stuck long enough yet -> WAIT.
		assertEquals(RecoveryWatchdogAction.WAIT,
				decide(true, MIN_PEERS, true, NOW - (THRESHOLD - 1), 0L, 0));
	}

	@Test
	public void testCooldownGateWaits() {
		// Past the sustained threshold but within cooldown since the last orphan -> WAIT.
		assertEquals(RecoveryWatchdogAction.WAIT,
				decide(true, MIN_PEERS, true, NOW - (THRESHOLD + 1), NOW - (COOLDOWN - 1), 0));
	}

	@Test
	public void testOrphanCeilingGateWaits() {
		// Past threshold and cooldown, but the per-episode orphan ceiling is reached -> WAIT.
		assertEquals(RecoveryWatchdogAction.WAIT,
				decide(true, MIN_PEERS, true, NOW - (THRESHOLD + 1), NOW - (COOLDOWN + 1), CEILING));
	}

	@Test
	public void testAllGatesPassedTriggersOrphan() {
		// Sustained, cooled down, under the ceiling -> ORPHAN.
		assertEquals(RecoveryWatchdogAction.ORPHAN,
				decide(true, MIN_PEERS, true, NOW - (THRESHOLD + 1), NOW - (COOLDOWN + 1), 0));
		// One below the ceiling still acts.
		assertEquals(RecoveryWatchdogAction.ORPHAN,
				decide(true, MIN_PEERS, true, NOW - (THRESHOLD + 1), NOW - (COOLDOWN + 1), CEILING - 1));
	}

	@Test
	public void testFirstOrphanNotBlockedByCooldown() {
		// lastWatchdogOrphanTimestamp == 0 means we have never orphaned, so cooldown must not block.
		assertEquals(RecoveryWatchdogAction.ORPHAN,
				decide(true, MIN_PEERS, true, NOW - (THRESHOLD + 1), 0L, 0));
	}

	@Test
	public void testThresholdBoundaryIsInclusive() {
		// Exactly at the threshold (elapsed == threshold) is "sustained enough" (gate uses '<').
		assertEquals(RecoveryWatchdogAction.ORPHAN,
				decide(true, MIN_PEERS, true, NOW - THRESHOLD, 0L, 0));
	}

	@Test
	public void testPeerArchiveHeightBlocksNormalBodySyncAtArchiveFloor() {
		assertEquals(false, Synchronizer.canServeBlockViaNormalSync(30000, 30000));
		assertEquals(true, Synchronizer.canServeBlockViaNormalSync(30000, 30001));
	}

	@Test
	public void testMissingPeerArchiveCapabilityAllowsNormalBodySync() {
		assertEquals(true, Synchronizer.canServeBlockViaNormalSync(0, 30000));
	}
}
