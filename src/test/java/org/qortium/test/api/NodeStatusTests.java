package org.qortium.test.api;

import org.junit.Test;
import org.qortium.api.model.NodeStatus;
import org.qortium.api.model.NodeStatus.PeerConnectionStats;
import org.qortium.api.model.NodeStatus.SyncPhase;
import org.qortium.api.model.NodeStatus.SyncProgress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NodeStatusTests {

	@Test
	public void testPeerTargetProgressShowsBehind() {
		SyncProgress progress = NodeStatus.calculateSyncProgress(250, null, false, 500, false, false, 2, 2);

		assertEquals(Integer.valueOf(500), progress.syncTargetHeight);
		assertEquals(Integer.valueOf(250), progress.syncBlocksRemaining);
		assertEquals(Integer.valueOf(50), progress.syncPercent);
		assertEquals(SyncPhase.BEHIND, progress.syncPhase);
	}

	@Test
	public void testActiveSyncBehindTargetDoesNotReportComplete() {
		SyncProgress progress = NodeStatus.calculateSyncProgress(499, 500, true, null, false, false, 2, 2);

		assertEquals(Integer.valueOf(500), progress.syncTargetHeight);
		assertEquals(Integer.valueOf(1), progress.syncBlocksRemaining);
		assertEquals(Integer.valueOf(99), progress.syncPercent);
		assertEquals(SyncPhase.SYNCHRONIZING, progress.syncPhase);
	}

	@Test
	public void testActiveSyncAtTargetReportsComplete() {
		SyncProgress progress = NodeStatus.calculateSyncProgress(500, 500, true, null, false, false, 2, 2);

		assertEquals(Integer.valueOf(500), progress.syncTargetHeight);
		assertEquals(Integer.valueOf(0), progress.syncBlocksRemaining);
		assertEquals(Integer.valueOf(100), progress.syncPercent);
		assertEquals(SyncPhase.SYNCHRONIZING, progress.syncPhase);
	}

	@Test
	public void testNoTargetWithoutEnoughPeersShowsConnecting() {
		SyncProgress progress = NodeStatus.calculateSyncProgress(250, null, false, null, false, false, 1, 2);

		assertNull(progress.syncTargetHeight);
		assertNull(progress.syncBlocksRemaining);
		assertNull(progress.syncPercent);
		assertEquals(SyncPhase.CONNECTING, progress.syncPhase);
	}

	@Test
	public void testNoTargetWithEnoughPeersShowsStale() {
		SyncProgress progress = NodeStatus.calculateSyncProgress(250, null, false, null, false, false, 2, 2);

		assertNull(progress.syncTargetHeight);
		assertNull(progress.syncBlocksRemaining);
		assertNull(progress.syncPercent);
		assertEquals(SyncPhase.STALE, progress.syncPhase);
	}

	@Test
	public void testLiteModeReportsLiteAndComplete() {
		SyncProgress progress = NodeStatus.calculateSyncProgress(250, null, false, null, false, true, 0, 2);

		assertEquals(Integer.valueOf(250), progress.syncTargetHeight);
		assertEquals(Integer.valueOf(0), progress.syncBlocksRemaining);
		assertEquals(Integer.valueOf(100), progress.syncPercent);
		assertEquals(SyncPhase.LITE, progress.syncPhase);
	}

	@Test
	public void testSyncedNodeReportsComplete() {
		SyncProgress progress = NodeStatus.calculateSyncProgress(500, null, false, null, true, false, 2, 2);

		assertEquals(Integer.valueOf(500), progress.syncTargetHeight);
		assertEquals(Integer.valueOf(0), progress.syncBlocksRemaining);
		assertEquals(Integer.valueOf(100), progress.syncPercent);
		assertEquals(SyncPhase.SYNCED, progress.syncPhase);
	}

	@Test
	public void testPeerConnectionStatsSplitInboundAndOutbound() {
		PeerConnectionStats stats = NodeStatus.calculatePeerConnectionStats(5, 2, true, true, false);

		assertEquals(5, stats.totalConnections);
		assertEquals(3, stats.inboundConnections);
		assertEquals(2, stats.outboundConnections);
		assertTrue(stats.inboundReachable);
		assertTrue(stats.listenSocketAvailable);
		assertFalse(stats.portMapped);
	}

	@Test
	public void testPeerConnectionStatsBoundsInvalidCounts() {
		PeerConnectionStats stats = NodeStatus.calculatePeerConnectionStats(2, 5, false, true, true);

		assertEquals(2, stats.totalConnections);
		assertEquals(0, stats.inboundConnections);
		assertEquals(2, stats.outboundConnections);
		assertFalse(stats.inboundReachable);
		assertTrue(stats.listenSocketAvailable);
		assertTrue(stats.portMapped);
	}

}
