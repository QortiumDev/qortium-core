package org.qortium.controller;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.data.block.BlockSummaryData;
import org.qortium.data.network.PeerData;
import org.qortium.network.Network;
import org.qortium.network.Peer;
import org.qortium.network.PeerAddress;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.utils.NTP;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Recovery-mode state transitions when fresh and stale peers coexist. */
public class SynchronizerRecoveryModeTests extends Common {

	private Synchronizer synchronizer;
	private long now;

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		Controller.getInstance().refillLatestBlocksCache();
		this.synchronizer = Synchronizer.getInstance();
		resetRecoveryState();
		this.now = NTP.getTime();
	}

	@After
	public void afterTest() throws Exception {
		FieldUtils.writeField(Network.getInstance(), "immutableHandshakedPeers", List.of(), true);
		resetRecoveryState();
	}

	@Test
	public void testMixedFreshAndStalePeersExitRecoveryMode() throws Exception {
		FieldUtils.writeField(this.synchronizer, "recoveryMode", true, true);
		Peer freshPeer = peerAt(20, this.now);
		Peer stalePeer = peerAt(21, 1L);
		FieldUtils.writeField(Network.getInstance(), "immutableHandshakedPeers", List.of(freshPeer, stalePeer), true);

		this.synchronizer.potentiallySynchronize();

		assertFalse("one recent peer must restore normal synchronization policy",
				this.synchronizer.getRecoveryMode());
	}

	@Test
	public void testAllStalePeersRemainInRecoveryMode() throws Exception {
		FieldUtils.writeField(this.synchronizer, "recoveryMode", true, true);
		FieldUtils.writeField(Network.getInstance(), "immutableHandshakedPeers",
				List.of(peerAt(20, 1L), peerAt(21, 2L)), true);

		this.synchronizer.potentiallySynchronize();

		assertTrue(this.synchronizer.getRecoveryMode());
	}

	@Test
	public void testMixedPeersImmediatelyRestoreRecentOnlyPolicy() throws Exception {
		FieldUtils.writeField(this.synchronizer, "recoveryMode", true, true);
		Peer freshPeer = peerAt(20, this.now);
		Peer stalePeer = peerAt(21, 1L);

		List<Peer> selectedPeers = this.synchronizer.applyRecoveryModePeerPolicy(true,
				List.of(freshPeer, stalePeer));

		assertFalse(this.synchronizer.getRecoveryMode());
		assertEquals(List.of(freshPeer), selectedPeers);
	}

	@Test
	public void testFreshOnlyPeersExitRecoveryMode() throws Exception {
		FieldUtils.writeField(this.synchronizer, "recoveryMode", true, true);
		Peer freshPeer = peerAt(20, this.now);

		List<Peer> selectedPeers = this.synchronizer.applyRecoveryModePeerPolicy(true, List.of(freshPeer));

		assertFalse(this.synchronizer.getRecoveryMode());
		assertEquals(List.of(freshPeer), selectedPeers);
	}

	@Test
	public void testStaleOnlyPeersStayAvailableInRecoveryMode() throws Exception {
		FieldUtils.writeField(this.synchronizer, "recoveryMode", true, true);
		Peer stalePeer = peerAt(20, 1L);

		List<Peer> selectedPeers = this.synchronizer.applyRecoveryModePeerPolicy(true, List.of(stalePeer));

		assertTrue(this.synchronizer.getRecoveryMode());
		assertEquals(List.of(stalePeer), selectedPeers);
	}

	@Test
	public void testUnavailablePeersEnterRecoveryOnlyAfterTimeout() throws Exception {
		long originalRecoveryModeTimeout = Settings.getInstance().getRecoveryModeTimeout();
		try {
			FieldUtils.writeField(Settings.getInstance(), "recoveryModeTimeout", 1_000L, true);

			assertFalse(this.synchronizer.checkRecoveryModeForPeers(true, List.of()));
			assertFalse((boolean) FieldUtils.readField(this.synchronizer, "peersAvailable", true));

			FieldUtils.writeField(this.synchronizer, "timePeersLastAvailable", this.now - 1_001L, true);
			assertTrue(this.synchronizer.checkRecoveryModeForPeers(true, List.of()));
		} finally {
			FieldUtils.writeField(Settings.getInstance(), "recoveryModeTimeout", originalRecoveryModeTimeout, true);
		}
	}

	@Test
	public void testNoHandshakedPeersDoNotChangeRecoveryState() throws Exception {
		FieldUtils.writeField(this.synchronizer, "recoveryMode", true, true);

		assertTrue(this.synchronizer.checkRecoveryModeForPeers(false, List.of()));
		assertTrue(this.synchronizer.getRecoveryMode());
	}

	private Peer peerAt(int height, long timestamp) {
		Peer peer = new CurrentVersionPeer("198.51.100." + height + ":24892");
		peer.setChainTipData(new BlockSummaryData(height, new byte[] {(byte) height}, new byte[32], timestamp));
		return peer;
	}

	private void resetRecoveryState() throws Exception {
		FieldUtils.writeField(this.synchronizer, "recoveryMode", false, true);
		FieldUtils.writeField(this.synchronizer, "peersAvailable", true, true);
		FieldUtils.writeField(this.synchronizer, "timePeersLastAvailable", 0L, true);
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
	}

	private static final class CurrentVersionPeer extends Peer {
		private CurrentVersionPeer(String address) {
			super(new PeerData(PeerAddress.fromString(address)), Peer.NETWORK);
		}

		@Override
		public boolean isAtLeastVersion(String minVersionString) {
			return true;
		}
	}
}
