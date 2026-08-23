package org.qortium.controller;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.block.Block;
import org.qortium.data.block.BlockData;
import org.qortium.data.block.BlockSummaryData;
import org.qortium.data.block.CommonBlockData;
import org.qortium.data.network.PeerData;
import org.qortium.network.Peer;
import org.qortium.network.PeerAddress;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.utils.NTP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Decision and peer-comparison coverage for bounded stale-fork recovery. */
public class SynchronizerStaleForkRecoveryTests extends Common {

	private static final BigInteger HEAVIER_LOCAL_BLOCK = BigInteger.TEN;
	private static final BigInteger LIGHTER_PEER_BLOCK = BigInteger.ONE;
	private boolean previousSingleNodeTestnet;

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		this.previousSingleNodeTestnet = Settings.getInstance().isSingleNodeTestnet();
		FieldUtils.writeField(Settings.getInstance(), "singleNodeTestnet", true, true);
	}

	@After
	public void afterTest() throws IllegalAccessException {
		FieldUtils.writeField(Settings.getInstance(), "singleNodeTestnet", this.previousSingleNodeTestnet, true);
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
	}

	@Test
	public void testStaleShortForkRetainsAgreedHigherBranchDespiteLocalWeight() {
		assertTrue(Synchronizer.isStaleForkRecoveryOverrideEligible(true,
				103_110, 103_109, 103_239, 2));

		assertTrue(Synchronizer.shouldRetainPeerChain(true,
				103_110, 103_109, 103_239, 2,
				HEAVIER_LOCAL_BLOCK, LIGHTER_PEER_BLOCK));
	}

	@Test
	public void testRecentLocalForkStillUsesNormalChainWeight() {
		assertFalse(Synchronizer.isStaleForkRecoveryOverrideEligible(false,
				103_110, 103_109, 103_239, 2));

		assertFalse(Synchronizer.shouldRetainPeerChain(false,
				103_110, 103_109, 103_239, 2,
				HEAVIER_LOCAL_BLOCK, LIGHTER_PEER_BLOCK));
	}

	@Test
	public void testStaleRecoveryRequiresBranchAgreement() {
		assertFalse(Synchronizer.isStaleForkRecoveryOverrideEligible(true,
				103_110, 103_109, 103_239, 1));

		assertFalse(Synchronizer.shouldRetainPeerChain(true,
				103_110, 103_109, 103_239, 1,
				HEAVIER_LOCAL_BLOCK, LIGHTER_PEER_BLOCK));
	}

	@Test
	public void testStaleRecoveryRequiresStrictlyHigherPeer() {
		assertFalse(Synchronizer.isStaleForkRecoveryOverrideEligible(true,
				103_110, 103_109, 103_110, 2));
		assertFalse(Synchronizer.isStaleForkRecoveryOverrideEligible(true,
				103_110, 103_109, 103_109, 2));
	}

	@Test
	public void testStaleRecoveryIsDepthBounded() {
		assertTrue(Synchronizer.isStaleForkRecoveryOverrideEligible(true,
				103_110, 103_107, 103_239, 2));
		assertFalse(Synchronizer.isStaleForkRecoveryOverrideEligible(true,
				103_110, 103_106, 103_239, 2));
	}

	@Test
	public void testSuperiorPeerWeightStillUsesNormalSynchronization() {
		assertTrue(Synchronizer.shouldRetainPeerChain(false,
				103_110, 103_109, 103_239, 1,
				LIGHTER_PEER_BLOCK, HEAVIER_LOCAL_BLOCK));
		assertTrue(Synchronizer.shouldRetainPeerChain(false,
				103_110, 103_109, 103_239, 1,
				HEAVIER_LOCAL_BLOCK, HEAVIER_LOCAL_BLOCK));
	}

	@Test
	public void testBranchAgreementCountsDistinctHigherPeerIdentities() {
		byte[] commonSignature = new byte[] { 1 };
		byte[] agreedFirstSignature = new byte[] { 2 };
		TestPeer first = peer("198.51.100.1:24892", "node-a", 103_239,
				commonSignature, agreedFirstSignature);
		TestPeer second = peer("198.51.100.2:24892", "node-b", 103_238,
				commonSignature, agreedFirstSignature);
		TestPeer duplicateIdentity = peer("198.51.100.3:24892", "node-a", 103_237,
				commonSignature, agreedFirstSignature);
		TestPeer differentBranch = peer("198.51.100.4:24892", "node-c", 103_240,
				commonSignature, new byte[] { 3 });
		TestPeer equalHeight = peer("198.51.100.5:24892", "node-d", 103_110,
				commonSignature, agreedFirstSignature);
		TestPeer staleTip = peer("198.51.100.6:24892", "node-e", 103_241,
				commonSignature, agreedFirstSignature, 999L);
		TestPeer nonSequential = peer("198.51.100.7:24892", "node-f", 103_242,
				commonSignature, agreedFirstSignature);
		nonSequential.getCommonBlockData().setBlockSummariesAfterCommonBlock(List.of(
				new BlockSummaryData(103_111, agreedFirstSignature, new byte[32], 1_001L)));

		assertEquals(2, Synchronizer.countDistinctPeersOnSameBranch(first,
				List.of(first, second, duplicateIdentity, differentBranch, equalHeight, staleTip, nonSequential),
				103_110, 1_000L));
		assertEquals(0, Synchronizer.countDistinctPeersOnSameBranch(staleTip,
				List.of(first, second, staleTip), 103_110, 1_000L));
		assertEquals(0, Synchronizer.countDistinctPeersOnSameBranch(nonSequential,
				List.of(first, second, nonSequential), 103_110, 1_000L));
	}

	@Test
	public void testPeerComparisonRetainsAgreedHigherBranchOverHeavierOneBlockFork() throws Exception {
		try (Repository repository = RepositoryManager.getRepository()) {
			BlockData commonBlock = new BlockData(repository.getBlockRepository().getLastBlock());
			PrivateKeyAccount localMinter = Common.getTestAccount(repository, "alice-reward-share");
			PrivateKeyAccount peerMinter = localMinter;
			BlockMinter.mintTestingBlock(repository, localMinter);
			BlockData localTip = new BlockData(repository.getBlockRepository().getLastBlock());
			assertTrue("fixture requires at least one online account", localTip.getOnlineAccountsCount() > 0);
			int peerOnlineAccountsCount = localTip.getOnlineAccountsCount() - 1;

			BlockSummaryData localSummary = weightedSummary(repository, localTip.getHeight(),
					localTip.getSignature(), localMinter.getPublicKey(), localTip.getOnlineAccountsCount());
			BlockSummaryData peerFirstSummary = weightedSummary(repository, localTip.getHeight(),
					new byte[] { 41 }, peerMinter.getPublicKey(), peerOnlineAccountsCount);
			BigInteger localWeight = Block.calcChainWeight(commonBlock.getHeight(), commonBlock.getSignature(),
					List.of(localSummary), localTip.getHeight());
			BigInteger peerWeight = Block.calcChainWeight(commonBlock.getHeight(), commonBlock.getSignature(),
					List.of(peerFirstSummary), localTip.getHeight());
			assertTrue("fixture must reproduce the heavier one-block local fork", localWeight.compareTo(peerWeight) > 0);

			long now = localTip.getTimestamp() + 10 * 60 * 1000L;
			NTP.setFixedOffset(now - System.currentTimeMillis());
			List<BlockSummaryData> peerBranch = List.of(peerFirstSummary,
					weightedSummary(repository, localTip.getHeight() + 1, new byte[] { 42 },
							peerMinter.getPublicKey(), peerOnlineAccountsCount),
					weightedSummary(repository, localTip.getHeight() + 2, new byte[] { 43 },
							peerMinter.getPublicKey(), peerOnlineAccountsCount));
			BlockSummaryData peerTip = new BlockSummaryData(localTip.getHeight() + 2,
					peerBranch.get(2).getSignature(), peerMinter.getPublicKey(), now);
			BlockSummaryData commonSummary = new BlockSummaryData(commonBlock);

			TestPeer first = peerWithCachedBranch("198.51.100.11:24892", "node-live-a",
					peerTip, commonSummary, peerBranch);
			TestPeer second = peerWithCachedBranch("198.51.100.12:24892", "node-live-b",
					peerTip, commonSummary, peerBranch);
			List<Peer> candidates = new ArrayList<>(List.of(first, second));

			List<Peer> retained = Synchronizer.getInstance().comparePeers(candidates);

			assertEquals("the agreed higher branch must reach atomic synchronization", 2, retained.size());
			assertTrue(retained.contains(first));
			assertTrue(retained.contains(second));
		}
	}

	private static BlockSummaryData weightedSummary(Repository repository, int height, byte[] signature,
			byte[] minterPublicKey, int onlineAccountsCount) throws Exception {
		BlockSummaryData summary = new BlockSummaryData(height, signature, minterPublicKey, onlineAccountsCount);
		summary.setMinterLevel(Account.getRewardShareEffectiveMintingLevel(repository, minterPublicKey));
		return summary;
	}

	private static TestPeer peerWithCachedBranch(String address, String nodeId, BlockSummaryData tip,
			BlockSummaryData common, List<BlockSummaryData> branch) {
		TestPeer peer = new TestPeer(address, nodeId);
		peer.setChainTipData(tip);
		CommonBlockData commonBlockData = new CommonBlockData(common, tip);
		commonBlockData.setBlockSummariesAfterCommonBlock(new ArrayList<>(branch));
		peer.setCommonBlockData(commonBlockData);
		return peer;
	}

	private static TestPeer peer(String address, String nodeId, int tipHeight,
			byte[] commonSignature, byte[] firstReplacementSignature) {
		return peer(address, nodeId, tipHeight, commonSignature, firstReplacementSignature, 2_000L);
	}

	private static TestPeer peer(String address, String nodeId, int tipHeight,
			byte[] commonSignature, byte[] firstReplacementSignature, long tipTimestamp) {
		TestPeer peer = new TestPeer(address, nodeId);
		BlockSummaryData tip = new BlockSummaryData(tipHeight, new byte[] { 9 }, new byte[32], tipTimestamp);
		peer.setChainTipData(tip);

		BlockSummaryData common = new BlockSummaryData(103_109, commonSignature, new byte[32], 1_000L);
		CommonBlockData commonBlockData = new CommonBlockData(common, tip);
		commonBlockData.setBlockSummariesAfterCommonBlock(List.of(
				new BlockSummaryData(103_110, firstReplacementSignature, new byte[32], 1_001L)));
		peer.setCommonBlockData(commonBlockData);
		return peer;
	}

	private static final class TestPeer extends Peer {
		private TestPeer(String address, String nodeId) {
			super(new PeerData(PeerAddress.fromString(address)), Peer.NETWORK);
			setPeersNodeId(nodeId);
		}
	}
}
