package org.qortium.test.minting;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.block.BlockChain.AccountLevelShareBin;
import org.qortium.block.BlockChain.RewardByHeight;
import org.qortium.block.ChainParameter;
import org.qortium.controller.BlockMinter;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.ChainParameterUpdateTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.utils.Amounts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class RewardTests extends Common {
	private static final Logger LOGGER = LogManager.getLogger(RewardTests.class);

	private static final long[] LEVEL_SHARES = new long[] {
			0L,
			1_818_182L,
			3_636_364L,
			5_454_545L,
			7_272_727L,
			9_090_909L,
			10_909_091L,
			12_727_273L,
			14_545_455L,
			16_363_636L,
			18_181_818L
	};

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testRewardShareBinsUseIndividualLevelWeights() {
		List<AccountLevelShareBin> shareBins = BlockChain.getInstance().getAccountLevelShareBins();
		AccountLevelShareBin[] shareBinsByLevel = BlockChain.getInstance().getShareBinsByAccountLevel();

		assertEquals(10, shareBins.size());
		assertEquals(10, shareBinsByLevel.length);

		long totalShare = 0;
		for (int level = 1; level <= 10; ++level) {
			AccountLevelShareBin shareBin = shareBins.get(level - 1);

			assertEquals(level, shareBin.id);
			assertEquals(1, shareBin.levels.size());
			assertEquals(Integer.valueOf(level), shareBin.levels.get(0));
			assertEquals(LEVEL_SHARES[level], shareBin.share);
			assertEquals(level, shareBinsByLevel[level - 1].id);

			totalShare += shareBin.share;
		}

		assertEquals(Amounts.MULTIPLIER, totalShare);
	}

	@Test
	public void testEffectiveRewardShareWeightsChangeDistribution() throws DataException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			seedRewardLevelTestAccounts(repository);

			List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();
			mintingAndOnlineAccounts.add(Common.getTestAccount(repository, "alice-reward-share"));

			byte[] chloeRewardSharePrivateKey = AccountUtils.rewardShare(repository, "chloe", "chloe", 0);
			mintingAndOnlineAccounts.add(new PrivateKeyAccount(repository, chloeRewardSharePrivateKey));

			byte[] dilbertRewardSharePrivateKey = AccountUtils.rewardShare(repository, "dilbert", "dilbert", 0);
			mintingAndOnlineAccounts.add(new PrivateKeyAccount(repository, dilbertRewardSharePrivateKey));

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			int activationHeight = repository.getBlockRepository().getBlockchainHeight()
					+ getApprovalSettlementBlockCount(repository)
					+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
					+ 20;
			int[] weights = new int[] { 3, 1, 1, 1, 1, 1, 1, 1, 1, 1 };

			ChainParameterUpdateTransactionData transactionData = new ChainParameterUpdateTransactionData(
					TestTransaction.generateBase(alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
					ChainParameter.REWARD_SHARE_WEIGHTS.id, activationHeight,
					ChainParameter.REWARD_SHARE_WEIGHTS.encodeIntArrayValue(weights));
			TransactionUtils.signAndMint(repository, transactionData, alice);
			GroupUtils.approveTransaction(repository, "alice", transactionData.getSignature(), true);
			BlockUtils.mintBlocks(repository, getApprovalSettlementBlockCount(repository));

			int blocksUntilActivation = activationHeight - 1 - repository.getBlockRepository().getBlockchainHeight();
			assertTrue(blocksUntilActivation >= 0);
			BlockUtils.mintBlocks(repository, blocksUntilActivation);
			assertEquals(activationHeight - 1, repository.getBlockRepository().getBlockchainHeight());

			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.NATIVE);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.NATIVE);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.NATIVE);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.NATIVE);

			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(activationHeight, repository.getBlockRepository().getBlockchainHeight());

			Map<String, Long> expectedRewards = calculateExpectedRewardsByAccount(repository, blockReward,
					new String[] { "alice", "chloe", "dilbert" }, activationHeight);

			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance + expectedRewards.get("alice"));
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance);
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance + expectedRewards.get("chloe"));
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance + expectedRewards.get("dilbert"));
		}
	}

	@Test
	public void testSimpleReward() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			mintAliceToLevel(repository, 1);

			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);

			Long blockReward = BlockUtils.getNextBlockReward(repository);

			BlockUtils.mintBlock(repository);

			long expectedBalance = initialBalances.get("alice").get(Asset.NATIVE) + blockReward;
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, expectedBalance);
		}
	}

	@Test
	public void testRewards() throws DataException {
		List<RewardByHeight> rewardsByHeight = BlockChain.getInstance().getBlockRewardsByHeight();

		try (final Repository repository = RepositoryManager.getRepository()) {
			mintAliceToLevel(repository, 1);

			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);

			Long expectedBalance = initialBalances.get("alice").get(Asset.NATIVE);
			int targetHeight = rewardsByHeight.get(rewardsByHeight.size() - 1).height;

			while (repository.getBlockRepository().getBlockchainHeight() < targetHeight) {
				int nextHeight = repository.getBlockRepository().getBlockchainHeight() + 1;
				long blockReward = BlockChain.getInstance().getRewardAtHeight(repository, nextHeight);

				BlockUtils.mintBlock(repository);

				expectedBalance += blockReward;
			}

			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, expectedBalance);
		}
	}

	@Test
	public void testRewardSharing() throws DataException {
		final int share = 12_80; // 12.80%

		try (final Repository repository = RepositoryManager.getRepository()) {
			mintAliceToLevel(repository, 1);

			AccountUtils.rewardShare(repository, "alice", "bob", share);
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");

			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);
			Long blockReward = BlockUtils.getNextBlockReward(repository);

			BlockMinter.mintTestingBlock(repository, aliceSelfShare);

			// Bob receives 12.8% of Alice's normalized minter reward.
			long bobShare = (blockReward * share) / 100L / 100L;
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, initialBalances.get("bob").get(Asset.NATIVE) + bobShare);

			long aliceShare = blockReward - bobShare;
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, initialBalances.get("alice").get(Asset.NATIVE) + aliceShare);
		}
	}

	@Test
	public void testMultipleRewardShareRecipientsUseMinterReward() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			mintAliceToLevel(repository, 1);

			AccountUtils.rewardShare(repository, "alice", "bob", 25_00);
			AccountUtils.rewardShare(repository, "alice", "chloe", 25_00);
			AccountUtils.rewardShare(repository, "alice", "dilbert", 50_00);

			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");

			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);
			long blockReward = BlockUtils.getNextBlockReward(repository);

			BlockMinter.mintTestingBlock(repository, aliceSelfShare);

			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE,
					initialBalances.get("bob").get(Asset.NATIVE) + (blockReward * 25L) / 100L);
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE,
					initialBalances.get("chloe").get(Asset.NATIVE) + (blockReward * 25L) / 100L);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE,
					initialBalances.get("dilbert").get(Asset.NATIVE) + (blockReward * 50L) / 100L);
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE,
					initialBalances.get("alice").get(Asset.NATIVE));
		}
	}


	/** Use Alice-Chloe reward-share to confirm recipients do not gain account levels from shared rewards. */
	@Test
	public void testRewardShareRecipientDoesNotGainLevel() throws DataException {
		List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			assertEquals(0, (int) chloe.getLevel());

			AccountUtils.rewardShare(repository, "alice", "chloe", 100_00);
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");

			final int minterBlocksNeeded = cumulativeBlocksByLevel.get(1);
			// Mint enough blocks that Chloe would have reached level 1 under the old recipient-credit behavior.
			for (int bc = 0; bc < minterBlocksNeeded; ++bc)
				BlockMinter.mintTestingBlock(repository, aliceSelfShare);

			assertEquals(0, (int) chloe.getLevel());

			// Orphan back to genesis block
			BlockUtils.orphanToBlock(repository, 1);

			assertEquals(0, (int) chloe.getLevel());
		}
	}

	/** Test that normalized rewards flow only to eligible online minters. */
	@Test
	public void testOnlyOnlineMintersReceiveRewards() throws DataException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			seedRewardLevelTestAccounts(repository);

			List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();

			// Alice self-share online
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			mintingAndOnlineAccounts.add(aliceSelfShare);

			// Bob self-share NOT online

			// Chloe self-share online
			byte[] chloeRewardSharePrivateKey = AccountUtils.rewardShare(repository, "chloe", "chloe", 0);
			PrivateKeyAccount chloeRewardShareAccount = new PrivateKeyAccount(repository, chloeRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(chloeRewardShareAccount);

			// Dilbert self-share NOT online

			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);
			long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			long expectedReward = blockReward / 2;

			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, initialBalances.get("alice").get(Asset.NATIVE) + expectedReward);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, initialBalances.get("bob").get(Asset.NATIVE));
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, initialBalances.get("chloe").get(Asset.NATIVE) + expectedReward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, initialBalances.get("dilbert").get(Asset.NATIVE));
		}
	}

	/** Check that a single online minter receives the full normalized reward. */
	@Test
	public void testSingleOnlineMinterReceivesFullReward() throws DataException {
		Common.useSettings("test-settings-v2-reward-scaling.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			seedRewardScalingTestAccounts(repository);

			// Dilbert needs to create a self-share
			byte[] dilbertSelfSharePrivateKey = AccountUtils.rewardShare(repository, "dilbert", "dilbert", 0); // Block minted by Alice
			PrivateKeyAccount dilbertSelfShareAccount = new PrivateKeyAccount(repository, dilbertSelfSharePrivateKey);

			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);

			long blockReward = BlockUtils.getNextBlockReward(repository);

			BlockMinter.mintTestingBlock(repository, dilbertSelfShareAccount);

			// Dilbert is the only eligible online minter, so he receives the full normalized reward.

			long dilbertExpectedBalance = initialBalances.get("dilbert").get(Asset.NATIVE);
			dilbertExpectedBalance += blockReward;

			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertExpectedBalance);
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, initialBalances.get("alice").get(Asset.NATIVE));

			// Dilbert should continue receiving the full block reward while he is the only online minter.
			blockReward = BlockUtils.getNextBlockReward(repository);

			BlockMinter.mintTestingBlock(repository, dilbertSelfShareAccount);

			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertExpectedBalance + blockReward);
		}
	}

	/** Test rewards for level 1 and 2 accounts. */
	@Test
	public void testLevel1And2Rewards() throws DataException, IllegalAccessException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		// The lowest share bin is the reward floor, so it remains active even below the minimum account count.
		FieldUtils.writeField(BlockChain.getInstance(), "minAccountsToActivateShareBin", 4, true);

		try (final Repository repository = RepositoryManager.getRepository()) {
			seedRewardLevelTestAccounts(repository);

			List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();

			// Alice self share online
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			mintingAndOnlineAccounts.add(aliceSelfShare);
			byte[] chloeRewardSharePrivateKey;
			// Bob self-share NOT online

			// Chloe self share online
			try {
				chloeRewardSharePrivateKey = AccountUtils.rewardShare(repository, "chloe", "chloe", 0);
			} catch (IllegalArgumentException ex) {
				LOGGER.error("FAILED {}", ex.getLocalizedMessage(), ex);
				throw ex;
			}
			PrivateKeyAccount chloeRewardShareAccount = new PrivateKeyAccount(repository, chloeRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(chloeRewardShareAccount);

			// Dilbert self share online
			byte[] dilbertRewardSharePrivateKey = AccountUtils.rewardShare(repository, "dilbert", "dilbert", 0);
			PrivateKeyAccount dilbertRewardShareAccount = new PrivateKeyAccount(repository, dilbertRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(dilbertRewardShareAccount);

			// Ensure that the levels are as we expect
			assertEquals(1, (int) Common.getTestAccount(repository, "alice").getLevel());
			assertEquals(1, (int) Common.getTestAccount(repository, "bob").getLevel());
			assertEquals(1, (int) Common.getTestAccount(repository, "chloe").getLevel());
			assertEquals(2, (int) Common.getTestAccount(repository, "dilbert").getLevel());

			// Now that everyone is at level 1 or 2, we can capture initial balances
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.NATIVE);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.NATIVE);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.NATIVE);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.NATIVE);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are at the correct height and block reward value
			assertEquals(4, (int) repository.getBlockRepository().getLastBlock().getHeight());
			assertEquals(10000000000L, blockReward);

			/*
			 * Alice, Chloe, and Dilbert are 'online'. Bob is offline.
			 * Alice and Chloe are level 1, Dilbert is level 2.
			 * Alice, Chloe, and Dilbert should receive equal shares of the normalized level 1 and 2 reward.
			 */

			final long expectedReward = blockReward / 3; // The reward is split between Alice, Chloe, and Dilbert

			// Validate the balances to ensure that the fixed distribution is being applied.
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance+expectedReward);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance); // Bob not online so his balance remains the same
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance+expectedReward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance+expectedReward);

		}
	}

	/** Test rewards for level 3 and 4 accounts */
	@Test
	public void testLevel3And4Rewards() throws DataException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			seedRewardLevelTestAccounts(repository);

			List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
			List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();

			// Alice self share online
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			mintingAndOnlineAccounts.add(aliceSelfShare);

			// Bob self-share online
			byte[] bobRewardSharePrivateKey = AccountUtils.rewardShare(repository, "bob", "bob", 0);
			PrivateKeyAccount bobRewardShareAccount = new PrivateKeyAccount(repository, bobRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(bobRewardShareAccount);

			// Chloe self share online
			byte[] chloeRewardSharePrivateKey = AccountUtils.rewardShare(repository, "chloe", "chloe", 0);
			PrivateKeyAccount chloeRewardShareAccount = new PrivateKeyAccount(repository, chloeRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(chloeRewardShareAccount);

			// Dilbert self share online
			byte[] dilbertRewardSharePrivateKey = AccountUtils.rewardShare(repository, "dilbert", "dilbert", 0);
			PrivateKeyAccount dilbertRewardShareAccount = new PrivateKeyAccount(repository, dilbertRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(dilbertRewardShareAccount);

			// Mint enough blocks to bump testAccount levels to 3 and 4
			final int minterBlocksNeeded = cumulativeBlocksByLevel.get(4) - 20; // 20 blocks before level 4, so that the test accounts reach the correct levels
			for (int bc = 0; bc < minterBlocksNeeded; ++bc)
				BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure that the levels are as we expect
			assertEquals(3, (int) Common.getTestAccount(repository, "alice").getLevel());
			assertEquals(3, (int) Common.getTestAccount(repository, "bob").getLevel());
			assertEquals(3, (int) Common.getTestAccount(repository, "chloe").getLevel());
			assertEquals(4, (int) Common.getTestAccount(repository, "dilbert").getLevel());

			// Now that everyone is at level 3 or 4, we can capture initial balances
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.NATIVE);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.NATIVE);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.NATIVE);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.NATIVE);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are using the correct block reward value
			assertEquals(100000000L, blockReward);

			/*
			 * Alice, Bob, Chloe, and Dilbert are 'online'.
			 * Alice, Bob, and Chloe are level 3; Dilbert is level 4.
			 * Alice, Chloe, and Bob share the normalized level 3 reward.
			 * Dilbert receives the normalized level 4 reward.
			 */

			long[] expectedRewards = distributeByShareBins(blockReward,
					new long[] { LEVEL_SHARES[3], LEVEL_SHARES[4] },
					new int[] { 3, 1 });
			final long expectedLevel3Reward = expectedRewards[0];
			final long expectedLevel4Reward = expectedRewards[1];

			// Validate the balances to ensure that the fixed distribution is being applied.
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance+expectedLevel3Reward);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance+expectedLevel3Reward);
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance+expectedLevel3Reward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance+expectedLevel4Reward);

		}
	}

	/** Test rewards for level 5 and 6 accounts */
	@Test
	public void testLevel5And6Rewards() throws DataException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			seedRewardLevelTestAccounts(repository);

			List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
			List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();

			// Alice self share online
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			mintingAndOnlineAccounts.add(aliceSelfShare);

			// Bob self-share not initially online

			// Chloe self share online
			byte[] chloeRewardSharePrivateKey = AccountUtils.rewardShare(repository, "chloe", "chloe", 0);
			PrivateKeyAccount chloeRewardShareAccount = new PrivateKeyAccount(repository, chloeRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(chloeRewardShareAccount);

			// Dilbert self share online
			byte[] dilbertRewardSharePrivateKey = AccountUtils.rewardShare(repository, "dilbert", "dilbert", 0);
			PrivateKeyAccount dilbertRewardShareAccount = new PrivateKeyAccount(repository, dilbertRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(dilbertRewardShareAccount);

			// Mint enough blocks to bump testAccount levels to 5 and 6
			final int minterBlocksNeeded = cumulativeBlocksByLevel.get(6) - 20; // 20 blocks before level 6, so that the test accounts reach the correct levels
			for (int bc = 0; bc < minterBlocksNeeded; ++bc)
				BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Bob self-share now comes online
			byte[] bobRewardSharePrivateKey = AccountUtils.rewardShare(repository, "bob", "bob", 0);
			PrivateKeyAccount bobRewardShareAccount = new PrivateKeyAccount(repository, bobRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(bobRewardShareAccount);

			// Ensure that the levels are as we expect
			assertEquals(5, (int) Common.getTestAccount(repository, "alice").getLevel());
			assertEquals(1, (int) Common.getTestAccount(repository, "bob").getLevel());
			assertEquals(5, (int) Common.getTestAccount(repository, "chloe").getLevel());
			assertEquals(6, (int) Common.getTestAccount(repository, "dilbert").getLevel());

			// Now that everyone is at level 5 or 6 (except Bob who has only just started minting, so is at level 1), we can capture initial balances
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.NATIVE);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.NATIVE);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.NATIVE);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.NATIVE);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are using the correct block reward value
			assertEquals(100000000L, blockReward);

			/*
			 * Alice, Bob, Chloe, and Dilbert are 'online'.
			 * Bob is level 1; Alice and Chloe are level 5; Dilbert is level 6.
			 * Alice and Chloe share the normalized level 5 reward.
			 * Dilbert receives the normalized level 6 reward.
			 * Bob receives the normalized level 1 reward.
			 */

			long[] expectedRewards = distributeByShareBins(blockReward,
					new long[] { LEVEL_SHARES[1], LEVEL_SHARES[5], LEVEL_SHARES[6] },
					new int[] { 1, 2, 1 });
			final long expectedLevel1Reward = expectedRewards[0];
			final long expectedLevel5Reward = expectedRewards[1];
			final long expectedLevel6Reward = expectedRewards[2];

			// Validate the balances to ensure that the fixed distribution is being applied.
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance+expectedLevel5Reward);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance+expectedLevel1Reward);
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance+expectedLevel5Reward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance+expectedLevel6Reward);

		}
	}

	/** Test rewards for level 7 and 8 accounts */
	@Test
	public void testLevel7And8Rewards() throws DataException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			seedRewardLevelTestAccounts(repository);

			List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
			List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();

			// Alice self share online
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			mintingAndOnlineAccounts.add(aliceSelfShare);

			// Bob self-share NOT online

			// Chloe self share online
			byte[] chloeRewardSharePrivateKey = AccountUtils.rewardShare(repository, "chloe", "chloe", 0);
			PrivateKeyAccount chloeRewardShareAccount = new PrivateKeyAccount(repository, chloeRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(chloeRewardShareAccount);

			// Dilbert self share online
			byte[] dilbertRewardSharePrivateKey = AccountUtils.rewardShare(repository, "dilbert", "dilbert", 0);
			PrivateKeyAccount dilbertRewardShareAccount = new PrivateKeyAccount(repository, dilbertRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(dilbertRewardShareAccount);

			// Mint enough blocks to bump testAccount levels to 7 and 8
			final int minterBlocksNeeded = cumulativeBlocksByLevel.get(8) - 20; // 20 blocks before level 8, so that the test accounts reach the correct levels
			for (int bc = 0; bc < minterBlocksNeeded; ++bc)
				BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure that the levels are as we expect
			assertEquals(7, (int) Common.getTestAccount(repository, "alice").getLevel());
			assertEquals(1, (int) Common.getTestAccount(repository, "bob").getLevel());
			assertEquals(7, (int) Common.getTestAccount(repository, "chloe").getLevel());
			assertEquals(8, (int) Common.getTestAccount(repository, "dilbert").getLevel());

			// Now that everyone is at level 7 or 8 (except Bob who has only just started minting, so is at level 1), we can capture initial balances
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.NATIVE);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.NATIVE);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.NATIVE);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.NATIVE);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are using the correct block reward value
			assertEquals(100000000L, blockReward);

			/*
			 * Alice, Chloe, and Dilbert are 'online'.
			 * Alice and Chloe are level 7; Dilbert is level 8.
			 * Alice and Chloe share the normalized level 7 reward.
			 * Dilbert receives the normalized level 8 reward.
			 */

			long[] expectedRewards = distributeByShareBins(blockReward,
					new long[] { LEVEL_SHARES[7], LEVEL_SHARES[8] },
					new int[] { 2, 1 });
			final long expectedLevel7Reward = expectedRewards[0];
			final long expectedLevel8Reward = expectedRewards[1];

			// Validate the balances to ensure that the fixed distribution is being applied.
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance+expectedLevel7Reward);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance); // Bob not online so his balance remains the same
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance+expectedLevel7Reward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance+expectedLevel8Reward);

			// Orphan and ensure balances return to their previous values
			BlockUtils.orphanBlocks(repository, 1);

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance);
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance);

		}
	}

	/** Test rewards for level 9 and 10 accounts */
	@Test
	public void testLevel9And10Rewards() throws DataException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			seedRewardLevelTestAccounts(repository);

			List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
			List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();

			// Alice self share online
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			mintingAndOnlineAccounts.add(aliceSelfShare);

			// Bob self-share not initially online

			// Chloe self share online
			byte[] chloeRewardSharePrivateKey = AccountUtils.rewardShare(repository, "chloe", "chloe", 0);
			PrivateKeyAccount chloeRewardShareAccount = new PrivateKeyAccount(repository, chloeRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(chloeRewardShareAccount);

			// Dilbert self share online
			byte[] dilbertRewardSharePrivateKey = AccountUtils.rewardShare(repository, "dilbert", "dilbert", 0);
			PrivateKeyAccount dilbertRewardShareAccount = new PrivateKeyAccount(repository, dilbertRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(dilbertRewardShareAccount);

			// Mint enough blocks to bump testAccount levels to 9 and 10
			final int minterBlocksNeeded = cumulativeBlocksByLevel.get(10) - 20; // 20 blocks before level 10, so that the test accounts reach the correct levels
			for (int bc = 0; bc < minterBlocksNeeded; ++bc)
				BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Bob self-share now comes online
			byte[] bobRewardSharePrivateKey = AccountUtils.rewardShare(repository, "bob", "bob", 0);
			PrivateKeyAccount bobRewardShareAccount = new PrivateKeyAccount(repository, bobRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(bobRewardShareAccount);

			// Ensure that the levels are as we expect
			assertEquals(9, (int) Common.getTestAccount(repository, "alice").getLevel());
			assertEquals(1, (int) Common.getTestAccount(repository, "bob").getLevel());
			assertEquals(9, (int) Common.getTestAccount(repository, "chloe").getLevel());
			assertEquals(10, (int) Common.getTestAccount(repository, "dilbert").getLevel());

			// Now that everyone is at level 7 or 8 (except Bob who has only just started minting, so is at level 1), we can capture initial balances
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.NATIVE);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.NATIVE);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.NATIVE);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.NATIVE);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are using the correct block reward value
			assertEquals(100000000L, blockReward);

			/*
			 * Alice, Bob, Chloe, and Dilbert are 'online'.
			 * Bob is level 1; Alice and Chloe are level 9; Dilbert is level 10.
			 * Alice and Chloe share the normalized level 9 reward.
			 * Dilbert receives the normalized level 10 reward.
			 * Bob receives the normalized level 1 reward.
			 */

			long[] expectedRewards = distributeByShareBins(blockReward,
					new long[] { LEVEL_SHARES[1], LEVEL_SHARES[9], LEVEL_SHARES[10] },
					new int[] { 1, 2, 1 });
			final long expectedLevel1Reward = expectedRewards[0];
			final long expectedLevel9Reward = expectedRewards[1];
			final long expectedLevel10Reward = expectedRewards[2];

			// Validate the balances to ensure that the fixed distribution is being applied.
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance+expectedLevel9Reward);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance+expectedLevel1Reward);
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance+expectedLevel9Reward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance+expectedLevel10Reward);

			// Orphan and ensure balances return to their previous values
			BlockUtils.orphanBlocks(repository, 1);

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance);
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance);

		}
	}

	/** Test rewards for level 7 and 8 accounts, when the tier doesn't yet have enough minters in it. */
	@Test
	public void testLevel7And8RewardsPreActivation() throws DataException, IllegalAccessException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		// Set minAccountsToActivateShareBin to 4 so populated share bins above the floor are considered inactive.
		FieldUtils.writeField(BlockChain.getInstance(), "minAccountsToActivateShareBin", 4, true);

		try (final Repository repository = RepositoryManager.getRepository()) {
			seedRewardLevelTestAccounts(repository);

			List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
			List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();

			// Alice self share online
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			mintingAndOnlineAccounts.add(aliceSelfShare);

			// Bob self-share NOT online

			// Chloe self share online
			byte[] chloeRewardSharePrivateKey = AccountUtils.rewardShare(repository, "chloe", "chloe", 0);
			PrivateKeyAccount chloeRewardShareAccount = new PrivateKeyAccount(repository, chloeRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(chloeRewardShareAccount);

			// Dilbert self share online
			byte[] dilbertRewardSharePrivateKey = AccountUtils.rewardShare(repository, "dilbert", "dilbert", 0);
			PrivateKeyAccount dilbertRewardShareAccount = new PrivateKeyAccount(repository, dilbertRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(dilbertRewardShareAccount);

			// Mint enough blocks to bump testAccount levels to 7 and 8
			final int minterBlocksNeeded = cumulativeBlocksByLevel.get(8) - 20; // 20 blocks before level 8, so that the test accounts reach the correct levels
			for (int bc = 0; bc < minterBlocksNeeded; ++bc)
				BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure that the levels are as we expect
			assertEquals(7, (int) Common.getTestAccount(repository, "alice").getLevel());
			assertEquals(1, (int) Common.getTestAccount(repository, "bob").getLevel());
			assertEquals(7, (int) Common.getTestAccount(repository, "chloe").getLevel());
			assertEquals(8, (int) Common.getTestAccount(repository, "dilbert").getLevel());

			// Now that everyone is at level 7 or 8 (except Bob who has only just started minting, so is at level 1), we can capture initial balances
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.NATIVE);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.NATIVE);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.NATIVE);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.NATIVE);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are using the correct block reward value
			assertEquals(100000000L, blockReward);

			/*
			 * Alice, Chloe, and Dilbert are 'online'.
			 * Alice and Chloe are level 7; Dilbert is level 8.
			 * Level 7 and 8 is not yet activated, so its rewards cascade to the level 1 and 2 floor.
			 * There are no lower-level online minters.
			 * Alice, Chloe, and Dilbert should receive equal shares of the normalized levels 1 to 8 reward.
			 */

			final long expectedLevel1To8Reward = blockReward / 3; // The reward is split between Alice, Chloe, and Dilbert

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance+expectedLevel1To8Reward);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance); // Bob not online so his balance remains the same
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance+expectedLevel1To8Reward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance+expectedLevel1To8Reward);

			// Orphan and ensure balances return to their previous values
			BlockUtils.orphanBlocks(repository, 1);

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance);
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance);

		}
	}

	/** Test rewards for level 9 and 10 accounts, when populated upper tiers do not have enough minters. */
	@Test
	public void testLevel9And10RewardsPreActivation() throws DataException, IllegalAccessException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		// Set minAccountsToActivateShareBin to 4 so populated share bins above the floor are considered inactive.
		FieldUtils.writeField(BlockChain.getInstance(), "minAccountsToActivateShareBin", 4, true);

		try (final Repository repository = RepositoryManager.getRepository()) {
			seedRewardLevelTestAccounts(repository);

			List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
			List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();

			// Alice self share online
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			mintingAndOnlineAccounts.add(aliceSelfShare);

			// Bob self-share not initially online

			// Chloe self share online
			byte[] chloeRewardSharePrivateKey = AccountUtils.rewardShare(repository, "chloe", "chloe", 0);
			PrivateKeyAccount chloeRewardShareAccount = new PrivateKeyAccount(repository, chloeRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(chloeRewardShareAccount);

			// Dilbert self share online
			byte[] dilbertRewardSharePrivateKey = AccountUtils.rewardShare(repository, "dilbert", "dilbert", 0);
			PrivateKeyAccount dilbertRewardShareAccount = new PrivateKeyAccount(repository, dilbertRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(dilbertRewardShareAccount);

			// Mint enough blocks to bump testAccount levels to 9 and 10
			final int minterBlocksNeeded = cumulativeBlocksByLevel.get(10) - 20; // 20 blocks before level 10, so that the test accounts reach the correct levels
			for (int bc = 0; bc < minterBlocksNeeded; ++bc)
				BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Bob self-share now comes online
			byte[] bobRewardSharePrivateKey = AccountUtils.rewardShare(repository, "bob", "bob", 0);
			PrivateKeyAccount bobRewardShareAccount = new PrivateKeyAccount(repository, bobRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(bobRewardShareAccount);

			// Ensure that the levels are as we expect
			assertEquals(9, (int) Common.getTestAccount(repository, "alice").getLevel());
			assertEquals(1, (int) Common.getTestAccount(repository, "bob").getLevel());
			assertEquals(9, (int) Common.getTestAccount(repository, "chloe").getLevel());
			assertEquals(10, (int) Common.getTestAccount(repository, "dilbert").getLevel());

			// Now that everyone is at level 9 or 10 (except Bob who has only just started minting, so is at level 1), we can capture initial balances
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.NATIVE);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.NATIVE);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.NATIVE);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.NATIVE);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are using the correct block reward value
			assertEquals(100000000L, blockReward);

			/*
			 * Alice, Bob, Chloe, and Dilbert are 'online'.
			 * Bob is level 1; Alice and Chloe are level 9; Dilbert is level 10.
			 * Populated share bins above the floor are under the minimum count, so their rewards and minters
			 * cascade down to the level 1 and 2 floor.
			 * Alice, Bob, Chloe, and Dilbert should receive equal shares of the normalized reward.
			 */

			final long expectedReward = blockReward / 4;

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance+expectedReward);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance+expectedReward);
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance+expectedReward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance+expectedReward);

			// Orphan and ensure balances return to their previous values
			BlockUtils.orphanBlocks(repository, 1);

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance);
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance);

		}
	}

	/** Test rewards for level 7 and 8 accounts, when the tier reaches the minimum number of accounts */
	@Test
	public void testLevel7And8RewardsPreAndPostActivation() throws DataException, IllegalAccessException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		// Set minAccountsToActivateShareBin to 3 so the level 7-8 bin is inactive until three minters reach it.
		FieldUtils.writeField(BlockChain.getInstance(), "minAccountsToActivateShareBin", 3, true);

		try (final Repository repository = RepositoryManager.getRepository()) {
			seedRewardLevelTestAccounts(repository);

			List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();
			List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();

			// Alice self share online
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			mintingAndOnlineAccounts.add(aliceSelfShare);

			// Bob self-share NOT online

			// Chloe self share online
			byte[] chloeRewardSharePrivateKey = AccountUtils.rewardShare(repository, "chloe", "chloe", 0);
			PrivateKeyAccount chloeRewardShareAccount = new PrivateKeyAccount(repository, chloeRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(chloeRewardShareAccount);

			// Dilbert self share online
			byte[] dilbertRewardSharePrivateKey = AccountUtils.rewardShare(repository, "dilbert", "dilbert", 0);
			PrivateKeyAccount dilbertRewardShareAccount = new PrivateKeyAccount(repository, dilbertRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(dilbertRewardShareAccount);

			// Mint enough blocks to bump two of the testAccount levels to 7
			final int minterBlocksNeeded = cumulativeBlocksByLevel.get(7) - 12; // 12 blocks before level 7, so that dilbert and alice have reached level 7, but chloe will reach it in the next 2 blocks
			for (int bc = 0; bc < minterBlocksNeeded; ++bc)
				BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure that the levels are as we expect
			assertEquals(7, (int) Common.getTestAccount(repository, "alice").getLevel());
			assertEquals(1, (int) Common.getTestAccount(repository, "bob").getLevel());
			assertEquals(6, (int) Common.getTestAccount(repository, "chloe").getLevel());
			assertEquals(7, (int) Common.getTestAccount(repository, "dilbert").getLevel());

			// Now that dilbert has reached level 7, we can capture initial balances
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.NATIVE);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.NATIVE);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.NATIVE);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.NATIVE);

			// Mint a block
			long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are using the correct block reward value
			assertEquals(100000000L, blockReward);

			/*
			 * Alice, Chloe, and Dilbert are 'online'.
			 * Chloe is level 6; Alice and Dilbert are level 7.
			 * Level 7 and 8 is not yet activated, so its rewards are added to the level 5 and 6 share bin.
			 * The combined level 5 to 8 bin reaches the minimum account count.
			 * Alice, Chloe, and Dilbert should receive equal shares of the normalized levels 5 to 8 reward.
			 */

			final long expectedLevel5To8Reward = blockReward / 3; // The reward is split between Alice, Chloe, and Dilbert

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance+expectedLevel5To8Reward);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance); // Bob not online so his balance remains the same
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance+expectedLevel5To8Reward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance+expectedLevel5To8Reward);

			// Ensure that the levels are as we expect
			assertEquals(7, (int) Common.getTestAccount(repository, "alice").getLevel());
			assertEquals(1, (int) Common.getTestAccount(repository, "bob").getLevel());
			assertEquals(6, (int) Common.getTestAccount(repository, "chloe").getLevel());
			assertEquals(7, (int) Common.getTestAccount(repository, "dilbert").getLevel());

			// Capture pre-activation balances
			Map<String, Map<Long, Long>> preActivationBalances = AccountUtils.getBalances(repository, Asset.NATIVE);
			final long alicePreActivationBalance = preActivationBalances.get("alice").get(Asset.NATIVE);
			final long bobPreActivationBalance = preActivationBalances.get("bob").get(Asset.NATIVE);
			final long chloePreActivationBalance = preActivationBalances.get("chloe").get(Asset.NATIVE);
			final long dilbertPreActivationBalance = preActivationBalances.get("dilbert").get(Asset.NATIVE);

			// Mint another block
			blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure that the levels are as we expect (chloe has now increased to level 7; level 7-8 is now activated)
			assertEquals(7, (int) Common.getTestAccount(repository, "alice").getLevel());
			assertEquals(1, (int) Common.getTestAccount(repository, "bob").getLevel());
			assertEquals(7, (int) Common.getTestAccount(repository, "chloe").getLevel());
			assertEquals(7, (int) Common.getTestAccount(repository, "dilbert").getLevel());

			/*
			 * Alice, Chloe, and Dilbert are 'online'.
			 * Alice, Chloe, and Dilbert are level 7.
			 * Level 7 and 8 is now activated, so its rewards are paid out in the normal way.
			 * Alice, Chloe, and Dilbert should receive equal shares of the normalized level 7 and 8 reward.
			 */

			final long expectedLevel7To8Reward = blockReward / 3; // The reward is split between Alice, Chloe, and Dilbert

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, alicePreActivationBalance+expectedLevel7To8Reward);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobPreActivationBalance); // Bob not online so his balance remains the same
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloePreActivationBalance+expectedLevel7To8Reward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertPreActivationBalance+expectedLevel7To8Reward);


			// Orphan and ensure balances return to their pre-activation values
			BlockUtils.orphanBlocks(repository, 1);

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, alicePreActivationBalance);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobPreActivationBalance);
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloePreActivationBalance);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertPreActivationBalance);


			// Orphan again and ensure balances return to their initial values
			BlockUtils.orphanBlocks(repository, 1);

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance);
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance);

		}
	}

	/** Test rewards for level 1 and 2 accounts at a higher height using the fixed baseline share layout. */
	@Test
	public void testLevel1And2RewardsShareBinsV2() throws DataException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			seedRewardLevelTestAccounts(repository);

			List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();

			// Alice self share online
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			mintingAndOnlineAccounts.add(aliceSelfShare);
			byte[] chloeRewardSharePrivateKey;
			// Bob self-share NOT online

			// Mint some blocks so Chloe and Dilbert can start minting while we test the higher-height baseline rewards
			for (int i=0; i<990; i++)
				BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Chloe self share comes online
			try {
				chloeRewardSharePrivateKey = AccountUtils.rewardShare(repository, "chloe", "chloe", 0);
			} catch (IllegalArgumentException ex) {
				LOGGER.error("FAILED {}", ex.getLocalizedMessage(), ex);
				throw ex;
			}
			PrivateKeyAccount chloeRewardShareAccount = new PrivateKeyAccount(repository, chloeRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(chloeRewardShareAccount);

			// Dilbert self share comes online
			byte[] dilbertRewardSharePrivateKey = AccountUtils.rewardShare(repository, "dilbert", "dilbert", 0);
			PrivateKeyAccount dilbertRewardShareAccount = new PrivateKeyAccount(repository, dilbertRewardSharePrivateKey);
			mintingAndOnlineAccounts.add(dilbertRewardShareAccount);

			// Mint 6 more blocks so the level 1 and 2 accounts are ready for the higher-height reward check
			for (int i=0; i<6; i++)
				BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure that the levels are as we expect
			assertEquals(10, (int) Common.getTestAccount(repository, "alice").getLevel());
			assertEquals(1, (int) Common.getTestAccount(repository, "bob").getLevel());
			assertEquals(1, (int) Common.getTestAccount(repository, "chloe").getLevel());
			assertEquals(2, (int) Common.getTestAccount(repository, "dilbert").getLevel());

			// Now that everyone is at level 1 or 2, we can capture initial balances
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.NATIVE);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.NATIVE);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.NATIVE);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.NATIVE);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are at the correct height and block reward value
			assertEquals(1000, (int) repository.getBlockRepository().getLastBlock().getHeight());
			assertEquals(100000000L, blockReward);

			long[] expectedRewards = distributeByShareBins(blockReward,
					new long[] { LEVEL_SHARES[1], LEVEL_SHARES[2], LEVEL_SHARES[10] },
					new int[] { 1, 1, 1 });
			final long expectedChloeReward = expectedRewards[0];
			final long expectedDilbertReward = expectedRewards[1];
			final long expectedAliceReward = expectedRewards[2];

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance+expectedAliceReward);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance); // Bob not online so his balance remains the same
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance+expectedChloeReward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance+expectedDilbertReward);

			BlockUtils.orphanBlocks(repository, 1);
			assertEquals(999, (int) repository.getBlockRepository().getLastBlock().getHeight());

			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, aliceInitialBalance);
			AccountUtils.assertBalance(repository, "bob", Asset.NATIVE, bobInitialBalance); // Bob not online so his balance remains the same
			AccountUtils.assertBalance(repository, "chloe", Asset.NATIVE, chloeInitialBalance);
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, dilbertInitialBalance);
		}
	}

	private static void seedRewardLevelTestAccounts(Repository repository) throws DataException {
		AccountUtils.setMintingData(repository, "alice", 1);
		AccountUtils.setMintingData(repository, "bob", 1);
		AccountUtils.setMintingData(repository, "chloe", 1);
		AccountUtils.setMintingData(repository, "dilbert", 2);
	}

	private static void seedRewardScalingTestAccounts(Repository repository) throws DataException {
		AccountUtils.setMintingData(repository, "alice", 1);
		AccountUtils.setMintingData(repository, "bob", 1);
		AccountUtils.setMintingData(repository, "chloe", 1);
		AccountUtils.setMintingData(repository, "dilbert", 8);
	}

	private static int getApprovalSettlementBlockCount(Repository repository) throws DataException {
		GroupData groupData = repository.getGroupRepository().fromGroupId(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID);
		return Math.max(2, groupData.getMinimumBlockDelay() + 1);
	}

	private static void mintAliceToLevel(Repository repository, int level) throws DataException {
		while (Common.getTestAccount(repository, "alice").getLevel() < level)
			BlockUtils.mintBlock(repository);
	}

	private static Map<String, Long> calculateExpectedRewardsByAccount(Repository repository, long blockReward,
			String[] accountNames, int activationHeight) throws DataException {
		List<AccountLevelShareBin> shareBins = BlockChain.getInstance().getAccountLevelShareBins(repository, activationHeight);
		AccountLevelShareBin[] shareBinsByLevel = BlockChain.getInstance().getShareBinsByAccountLevel(repository, activationHeight);

		Map<String, Integer> accountBinIds = new HashMap<>();
		Map<Integer, Integer> accountCountsByBinId = new HashMap<>();
		for (String accountName : accountNames) {
			int level = Common.getTestAccount(repository, accountName).getLevel();
			AccountLevelShareBin shareBin = shareBinsByLevel[level - 1];

			accountBinIds.put(accountName, shareBin.id);
			accountCountsByBinId.merge(shareBin.id, 1, Integer::sum);
		}

		List<Integer> orderedBinIds = new ArrayList<>();
		List<Long> orderedShares = new ArrayList<>();
		List<Integer> orderedAccountCounts = new ArrayList<>();
		for (AccountLevelShareBin shareBin : shareBins) {
			Integer accountCount = accountCountsByBinId.get(shareBin.id);
			if (accountCount == null)
				continue;

			orderedBinIds.add(shareBin.id);
			orderedShares.add(shareBin.share);
			orderedAccountCounts.add(accountCount);
		}

		long[] shares = new long[orderedShares.size()];
		int[] accountCounts = new int[orderedAccountCounts.size()];
		for (int i = 0; i < orderedShares.size(); ++i) {
			shares[i] = orderedShares.get(i);
			accountCounts[i] = orderedAccountCounts.get(i);
		}

		long[] rewardsByBin = distributeByShareBins(blockReward, shares, accountCounts);
		Map<Integer, Long> rewardByBinId = new HashMap<>();
		for (int i = 0; i < orderedBinIds.size(); ++i)
			rewardByBinId.put(orderedBinIds.get(i), rewardsByBin[i]);

		Map<String, Long> expectedRewards = new LinkedHashMap<>();
		for (String accountName : accountNames)
			expectedRewards.put(accountName, rewardByBinId.get(accountBinIds.get(accountName)));

		return expectedRewards;
	}

	private static long[] distributeByShareBins(long blockReward, long[] shares, int[] accountCounts) {
		long totalShares = 0;
		for (long share : shares)
			totalShares += share;

		long[] normalizedShares = new long[shares.length];
		long remainingShare = Amounts.MULTIPLIER;
		for (int i = 0; i < shares.length; ++i) {
			if (i == shares.length - 1) {
				normalizedShares[i] = remainingShare;
			} else {
				normalizedShares[i] = Amounts.scaledDivide(shares[i], totalShares);
				remainingShare -= normalizedShares[i];
			}
		}

		long distributionTotal = blockReward;
		long[] perAccountRewards = new long[shares.length];
		for (int i = 0; i < shares.length; ++i) {
			long distributionAmount = Amounts.roundDownScaledMultiply(distributionTotal, normalizedShares[i]);
			perAccountRewards[i] = distributionAmount / accountCounts[i];

			long sharedAmount = perAccountRewards[i] * accountCounts[i];
			long undistributedAmount = distributionAmount - sharedAmount;
			if (undistributedAmount > 0 && normalizedShares[i] < Amounts.MULTIPLIER)
				distributionTotal += Amounts.scaledDivide(undistributedAmount, Amounts.MULTIPLIER - normalizedShares[i]);
		}

		return perAccountRewards;
	}

}
