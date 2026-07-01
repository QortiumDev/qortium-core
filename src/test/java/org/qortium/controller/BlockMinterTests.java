package org.qortium.controller;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Test;
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
}
