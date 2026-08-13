package org.qortium.controller;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Test;
import org.qortium.block.Block;
import org.qortium.data.block.BlockData;
import org.qortium.data.block.BlockSummaryData;
import org.qortium.data.block.CommonBlockData;
import org.qortium.data.network.PeerData;
import org.qortium.network.Network;
import org.qortium.network.Peer;
import org.qortium.network.PeerAddress;
import org.qortium.test.common.Common;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BlockMinterTests extends Common {

	@After
	public void after() throws Exception {
		FieldUtils.writeField(Network.getInstance(), "immutableHandshakedPeers", List.of(), true);
	}

	@Test
	public void testHigherWeightChainCheckUsesStableCommonBlockSnapshot() throws Exception {
		CommonBlockData commonBlockData = new CommonBlockData(
				new BlockSummaryData(1, new byte[] { 1 }, new byte[] { 2 }, 0L),
				new BlockSummaryData(2, new byte[] { 3 }, new byte[] { 4 }, 1L));
		Peer peer = new ClearingCommonBlockPeer(commonBlockData);

		FieldUtils.writeField(Network.getInstance(), "immutableHandshakedPeers", List.of(peer), true);

		Method method = BlockMinter.class.getDeclaredMethod("higherWeightChainExists",
				org.qortium.repository.Repository.class, BigInteger.class);
		method.setAccessible(true);

		boolean result = (boolean) method.invoke(new BlockMinter(), null, BigInteger.ONE);

		assertFalse(result);
	}

	@Test
	public void testGenesisMintingDefersToFreshSignedHigherPeer() {
		BlockData genesis = blockAt(1, 1_000L);
		Peer peer = peerAt(2, 2_000L, true, true);

		assertTrue(BlockMinter.shouldDeferGenesisMinting(genesis, List.of(peer), 1_500L));
	}

	@Test
	public void testGenesisMintingDoesNotDeferToIneligibleClaims() {
		BlockData genesis = blockAt(1, 1_000L);

		assertFalse(BlockMinter.shouldDeferGenesisMinting(genesis,
				List.of(peerAt(1, 2_000L, true, true)), 1_500L));
		assertFalse(BlockMinter.shouldDeferGenesisMinting(genesis,
				List.of(peerAt(2, 1_499L, true, true)), 1_500L));
		assertFalse(BlockMinter.shouldDeferGenesisMinting(genesis,
				List.of(peerAt(2, 2_000L, false, true)), 1_500L));
		assertFalse(BlockMinter.shouldDeferGenesisMinting(genesis,
				List.of(peerAt(2, 2_000L, true, false)), 1_500L));
		assertFalse(BlockMinter.shouldDeferGenesisMinting(blockAt(2, 1_000L),
				List.of(peerAt(3, 2_000L, true, true)), 1_500L));
	}

	private static BlockData blockAt(int height, long timestamp) {
		return new BlockData(Block.CURRENT_VERSION, new byte[128], 0, 0L, new byte[64], height, timestamp,
				new byte[32], new byte[64], 0, 0L);
	}

	private static Peer peerAt(int height, long timestamp, boolean signed, boolean currentVersion) {
		Peer peer = new VersionedPeer("198.51.100." + height + ":24892", currentVersion);
		peer.setChainTipData(new BlockSummaryData(height, signed ? new byte[] {(byte) height} : null,
				new byte[32], timestamp));
		return peer;
	}

	private static class ClearingCommonBlockPeer extends Peer {
		private CommonBlockData commonBlockData;

		private ClearingCommonBlockPeer(CommonBlockData commonBlockData) {
			super(new PeerData(PeerAddress.fromString("198.51.100.10:24892")), Peer.NETWORK);
			this.commonBlockData = commonBlockData;
		}

		@Override
		public CommonBlockData getCommonBlockData() {
			CommonBlockData currentCommonBlockData = this.commonBlockData;
			this.commonBlockData = null;
			return currentCommonBlockData;
		}
	}

	private static final class VersionedPeer extends Peer {
		private final boolean currentVersion;

		private VersionedPeer(String address, boolean currentVersion) {
			super(new PeerData(PeerAddress.fromString(address)), Peer.NETWORK);
			this.currentVersion = currentVersion;
		}

		@Override
		public boolean isAtLeastVersion(String minVersionString) {
			return this.currentVersion;
		}
	}
}
