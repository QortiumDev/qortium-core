package org.qortium.controller;

import java.util.List;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.block.Block;
import org.qortium.block.BlockChain;
import org.qortium.data.block.BlockData;
import org.qortium.data.block.BlockSummaryData;
import org.qortium.data.network.PeerData;
import org.qortium.network.Network;
import org.qortium.network.Peer;
import org.qortium.network.PeerAddress;
import org.qortium.repository.DataException;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.utils.NTP;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StaleChainCatchUpTests extends Common {

	private static final int TEST_PARENT_HEIGHT = 10;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws IllegalAccessException {
		FieldUtils.writeField(Network.getInstance(), "immutableHandshakedPeers", List.of(), true);
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
	}

	@Test
	public void testStaleTipCanCatchUpWhenNextMinimumTimestampIsValid() {
		BlockData latestBlockData = blockDataAtTimestamp(1_000_000L);
		long nextMinimumTimestamp = Block.calcMinimumTimestamp(latestBlockData);
		long now = nextMinimumTimestamp - BlockChain.getInstance().getBlockTimestampMargin();
		long minLatestBlockTimestamp = latestBlockData.getTimestamp() + 1L;

		assertTrue(Controller.isStaleChainCatchUpActive(latestBlockData, minLatestBlockTimestamp, now));
	}

	@Test
	public void testRecentTipDoesNotUseCatchUpMode() {
		BlockData latestBlockData = blockDataAtTimestamp(1_000_000L);
		long now = Block.calcMinimumTimestamp(latestBlockData);
		long minLatestBlockTimestamp = latestBlockData.getTimestamp() - 1L;

		assertFalse(Controller.isStaleChainCatchUpActive(latestBlockData, minLatestBlockTimestamp, now));
	}

	@Test
	public void testStaleTipDoesNotCatchUpBeforeNextMinimumTimestampIsValid() {
		BlockData latestBlockData = blockDataAtTimestamp(1_000_000L);
		long nextMinimumTimestamp = Block.calcMinimumTimestamp(latestBlockData);
		long now = nextMinimumTimestamp - BlockChain.getInstance().getBlockTimestampMargin() - 1L;
		long minLatestBlockTimestamp = latestBlockData.getTimestamp() + 1L;

		assertFalse(Controller.isStaleChainCatchUpActive(latestBlockData, minLatestBlockTimestamp, now));
	}

	@Test
	public void testCatchUpModeRequiresNtpTime() {
		BlockData latestBlockData = blockDataAtTimestamp(1_000_000L);
		long minLatestBlockTimestamp = latestBlockData.getTimestamp() + 1L;

		assertFalse(Controller.isStaleChainCatchUpActive(latestBlockData, minLatestBlockTimestamp, null));
	}

	@Test
	public void testLiveControllerRecognizesStaleCatchUpTip() throws DataException {
		try (var repository = RepositoryManager.getRepository()) {
			BlockData latestBlockData = repository.getBlockRepository().getLastBlock();
			long desiredNow = latestBlockData.getTimestamp() + 10 * 60 * 1000L;

			try {
				NTP.setFixedOffset(desiredNow - System.currentTimeMillis());
				Controller.getInstance().refillLatestBlocksCache();

				assertTrue(Controller.getInstance().isStaleChainCatchUpActive());
			} finally {
				NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
			}
		}
	}

	@Test
	public void testFreshHigherPeerEnablesSyncRecoveryButDisablesCatchUpMinting() throws Exception {
		try (var repository = RepositoryManager.getRepository()) {
			BlockData latestBlockData = repository.getBlockRepository().getLastBlock();
			long desiredNow = latestBlockData.getTimestamp() + 10 * 60 * 1000L;
			NTP.setFixedOffset(desiredNow - System.currentTimeMillis());
			Controller.getInstance().refillLatestBlocksCache();

			Peer peer = new CurrentVersionPeer("198.51.100.10:24892");
			peer.setChainTipData(new BlockSummaryData(latestBlockData.getHeight() + 1,
					new byte[128], Common.getTestAccount(repository, "alice-reward-share").getPublicKey(), desiredNow));
			FieldUtils.writeField(Network.getInstance(), "immutableHandshakedPeers", List.of(peer), true);

			assertTrue("fresh higher peers must keep stale-tip synchronization recovery active",
					Controller.getInstance().isStaleChainSynchronizationActive());
			assertFalse("fresh higher peers must prevent stale catch-up minting",
					Controller.getInstance().isStaleChainCatchUpActive());
		}
	}

	private static BlockData blockDataAtTimestamp(long timestamp) {
		return new BlockData(Block.CURRENT_VERSION, new byte[128], 0, 0L, new byte[64], TEST_PARENT_HEIGHT, timestamp,
				new byte[32], new byte[64], 0, 0L);
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
