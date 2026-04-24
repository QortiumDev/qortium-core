package org.qortal.test.minting;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.block.BlockChain;
import org.qortal.block.BlockChain.RewardByHeight;
import org.qortal.controller.BlockMinter;
import org.qortal.data.account.AccountBalanceData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.utils.Amounts;
import org.qortal.utils.Base58;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class RewardTests extends Common {
	private static final Logger LOGGER = LogManager.getLogger(RewardTests.class);
	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testSimpleReward() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT);

			Long blockReward = BlockUtils.getNextBlockReward(repository);

			BlockUtils.mintBlock(repository);

			long expectedBalance = initialBalances.get("alice").get(Asset.QORT) + blockReward;
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, expectedBalance);
		}
	}

	@Test
	public void testRewards() throws DataException {
		List<RewardByHeight> rewardsByHeight = BlockChain.getInstance().getBlockRewardsByHeight();

		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT);

			int rewardIndex = rewardsByHeight.size() - 1;

			RewardByHeight rewardInfo = rewardsByHeight.get(rewardIndex);
			Long expectedBalance = initialBalances.get("alice").get(Asset.QORT);

			for (int height = rewardInfo.height; height > 1; --height) {
				if (height < rewardInfo.height) {
					--rewardIndex;
					rewardInfo = rewardsByHeight.get(rewardIndex);
				}

				BlockUtils.mintBlock(repository);

				expectedBalance += rewardInfo.reward;
			}

			AccountUtils.assertBalance(repository, "alice", Asset.QORT, expectedBalance);
		}
	}

	@Test
	public void testRewardSharing() throws DataException {
		final int share = 12_80; // 12.80%

		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] rewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "bob", share);
			PrivateKeyAccount rewardShareAccount = new PrivateKeyAccount(repository, rewardSharePrivateKey);

			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT);
			Long blockReward = BlockUtils.getNextBlockReward(repository);

			BlockMinter.mintTestingBlock(repository, rewardShareAccount);

			// Alice is the online level 1 minter admin, so Bob receives 12.8% of Alice's combined reward.

			long level1And2Share = (blockReward * 6L) / 100L;
			long minterAdminShare = (blockReward - level1And2Share) / 2;
			long bobShare = ((level1And2Share + minterAdminShare) * share) / 100L / 100L;
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, initialBalances.get("bob").get(Asset.QORT) + bobShare);

			long aliceShare = blockReward - bobShare;
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, initialBalances.get("alice").get(Asset.QORT) + aliceShare);
		}
	}


	@Test
	public void testLegacyQoraReward() throws DataException {
		Common.useSettings("test-settings-v2-qora-holder-extremes.json");

		long qoraHoldersShare = BlockChain.getInstance().getQoraHoldersShareAtHeight(1);
		BigInteger qoraHoldersShareBI = BigInteger.valueOf(qoraHoldersShare);

		long qoraPerQort = BlockChain.getInstance().getQoraPerQortReward();
		BigInteger qoraPerQortBI = BigInteger.valueOf(qoraPerQort);

		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);

			Long blockReward = BlockUtils.getNextBlockReward(repository);
			BigInteger blockRewardBI = BigInteger.valueOf(blockReward);

			// Fetch all legacy QORA holder balances
			List<AccountBalanceData> qoraHolders = repository.getAccountRepository().getAssetBalances(Asset.LEGACY_QORA, true);
			long totalQoraHeld = 0L;
			for (AccountBalanceData accountBalanceData : qoraHolders)
				totalQoraHeld += accountBalanceData.getBalance();
			BigInteger totalQoraHeldBI = BigInteger.valueOf(totalQoraHeld);

			BlockUtils.mintBlock(repository);

			/*
			 * Example:
			 *
			 * Block reward is 100 QORT, QORA-holders' share is 0.01 (1%) = 1 QORT
			 *
			 * We hold 100 QORA
			 * Someone else holds 28 QORA
			 * Total QORA held: 128 QORA
			 *
			 * Our portion of that is 100 QORA / 128 QORA * 1 QORT = 0.78125 QORT
			 *
			 * QORA holders earn at most 1 QORT per 250 QORA held.
			 *
			 * So we can earn at most 100 QORA / 250 QORAperQORT = 0.4 QORT
			 *
			 * Thus our block earning should be capped to 0.4 QORT.
			 */

			// Expected reward
			long qoraHoldersReward = blockRewardBI.multiply(qoraHoldersShareBI).divide(Amounts.MULTIPLIER_BI).longValue();
			assertTrue("QORA-holders share of block reward should be less than total block reward", qoraHoldersReward < blockReward);
			assertFalse("QORA-holders share of block reward should not be negative!", qoraHoldersReward < 0);
			BigInteger qoraHoldersRewardBI = BigInteger.valueOf(qoraHoldersReward);

			long ourQoraHeld = initialBalances.get("chloe").get(Asset.LEGACY_QORA);
			BigInteger ourQoraHeldBI = BigInteger.valueOf(ourQoraHeld);
			long ourQoraReward = qoraHoldersRewardBI.multiply(ourQoraHeldBI).divide(totalQoraHeldBI).longValue();
			assertTrue("Our QORA-related reward should be less than total QORA-holders share of block reward", ourQoraReward < qoraHoldersReward);
			assertFalse("Our QORA-related reward should not be negative!", ourQoraReward < 0);

			long ourQortFromQoraCap = Amounts.scaledDivide(ourQoraHeldBI, qoraPerQortBI);
			assertTrue("Our QORT-from-QORA cap should be greater than zero", ourQortFromQoraCap > 0);

			long expectedReward = Math.min(ourQoraReward, ourQortFromQoraCap);
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, initialBalances.get("chloe").get(Asset.QORT) + expectedReward);

			AccountUtils.assertBalance(repository, "chloe", Asset.QORT_FROM_QORA, initialBalances.get("chloe").get(Asset.QORT_FROM_QORA) + expectedReward);
		}
	}

	@Test
	public void testLegacyQoraRewardCapNotExceeded() throws DataException {
		Common.useSettings("test-settings-v2-qora-holder.json");

		long qoraPerQort = BlockChain.getInstance().getQoraPerQortReward();

		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);

			// Mint lots of blocks
			for (int i = 0; i < 100; ++i)
				BlockUtils.mintBlock(repository);

			long qortReward = AccountUtils.getBalance(repository, "dilbert", Asset.QORT) - initialBalances.get("dilbert").get(Asset.QORT);
			long qortFromQoraReward = AccountUtils.getBalance(repository, "dilbert", Asset.QORT_FROM_QORA) - initialBalances.get("dilbert").get(Asset.QORT_FROM_QORA);
			long maxReward = Amounts.scaledDivide(initialBalances.get("dilbert").get(Asset.LEGACY_QORA), qoraPerQort);

			assertEquals(qortReward, qortFromQoraReward);
			assertTrue(qortReward > 0);
			assertTrue(qortReward <= maxReward);
		}
	}

	/** Use Alice-Chloe reward-share to bump Chloe from level 0 to level 1, then check orphaning works as expected. */
	@Test
	public void testLevel1() throws DataException {
		List<Integer> cumulativeBlocksByLevel = BlockChain.getInstance().getCumulativeBlocksByLevel();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			assertEquals(0, (int) chloe.getLevel());

			// Alice needs to mint block containing REWARD_SHARE BEFORE Alice loses minting privs
			byte[] aliceChloeRewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "chloe", 0); // Block minted by Alice
			PrivateKeyAccount aliceChloeRewardShareAccount = new PrivateKeyAccount(repository, aliceChloeRewardSharePrivateKey);

			final int minterBlocksNeeded = cumulativeBlocksByLevel.get(1);
			// Mint enough blocks to bump testAccount level
			for (int bc = 0; bc < minterBlocksNeeded; ++bc)
				BlockMinter.mintTestingBlock(repository, aliceChloeRewardShareAccount);

			assertEquals(1, (int) chloe.getLevel());

			// Orphan back to genesis block
			BlockUtils.orphanToBlock(repository, 1);

			assertEquals(0, (int) chloe.getLevel());
		}
	}

	/** Test that founders no longer receive a special reward bucket. */
	@Test
	public void testFounderRewardReplacement() throws DataException {
		Common.useSettings("test-settings-v2-founder-rewards.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT);
			Long blockReward = BlockUtils.getNextBlockReward(repository);

			List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();

			// Alice to mint, therefore online
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			mintingAndOnlineAccounts.add(aliceSelfShare);

			// Bob self-share NOT online

			// Chloe self-share and reward-share with Dilbert both online
			PrivateKeyAccount chloeSelfShare = Common.getTestAccount(repository, "chloe-reward-share");
			mintingAndOnlineAccounts.add(chloeSelfShare);

			PrivateKeyAccount chloeDilbertRewardShare = new PrivateKeyAccount(repository, Base58.decode("HuiyqLipUN1V9p1HZfLhyEwmEA6BTaT2qEfjgkwPViV4"));
			mintingAndOnlineAccounts.add(chloeDilbertRewardShare);

			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Alice is the only group admin, so she receives the full admin replacement share.
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, initialBalances.get("alice").get(Asset.QORT) + blockReward);

			// Other founders are online, but founder status no longer creates a reward bucket.
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, initialBalances.get("bob").get(Asset.QORT));
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, initialBalances.get("chloe").get(Asset.QORT));
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, initialBalances.get("dilbert").get(Asset.QORT));
		}
	}

	/** Check admin replacement rewards when no minter admin is online. */
	@Test
	public void testAdminReplacementWithoutOnlineMinterAdmin() throws DataException {
		Common.useSettings("test-settings-v2-reward-scaling.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Dilbert needs to create a self-share
			byte[] dilbertSelfSharePrivateKey = AccountUtils.rewardShare(repository, "dilbert", "dilbert", 0); // Block minted by Alice
			PrivateKeyAccount dilbertSelfShareAccount = new PrivateKeyAccount(repository, dilbertSelfSharePrivateKey);

			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);

			long blockReward = BlockUtils.getNextBlockReward(repository);

			BlockMinter.mintTestingBlock(repository, dilbertSelfShareAccount);

			/*
			 * Dilbert is only account 'online'.
			 * Alice is a dev admin but is not online.
			 * Some legacy QORA holders.
			 *
			 * So Dilbert should receive the combined level 5 to 8 share from the inactive
			 * level 7/8 bin, and Alice should receive the remaining admin replacement share.
			 */

			final long qoraHoldersShare = BlockChain.getInstance().getQoraHoldersShareAtHeight(1);
			final int level5To8SharePercent = 45_00;
			final long level5To8Share = Amounts.roundDownScaledMultiply(blockReward, level5To8SharePercent * 10000L);
			final long qoraShare = Amounts.roundDownScaledMultiply(blockReward, qoraHoldersShare);

			long dilbertExpectedBalance = initialBalances.get("dilbert").get(Asset.QORT);
			dilbertExpectedBalance += level5To8Share;

			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertExpectedBalance);
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, initialBalances.get("alice").get(Asset.QORT) + blockReward - level5To8Share - qoraShare);

			// After several blocks, the legacy QORA holder is still eligible at the fixed 1% baseline
			for (int i = 0; i < 10; ++i)
				BlockUtils.mintBlock(repository);

			// Dilbert should continue receiving the non-QORA share of the block reward
			blockReward = BlockUtils.getNextBlockReward(repository);

			BlockMinter.mintTestingBlock(repository, dilbertSelfShareAccount);

			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertExpectedBalance + Amounts.roundDownScaledMultiply(blockReward, level5To8SharePercent * 10000L));
		}
	}

	/** Check leftover legacy QORA reward goes to admins. */
	@Test
	public void testLeftoverReward() throws DataException {
		Common.useSettings("test-settings-v2-leftover-reward.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);

			long blockReward = BlockUtils.getNextBlockReward(repository);

			BlockUtils.mintBlock(repository); // Block minted by Alice self-share

			// Chloe maxxes out her legacy QORA reward so the leftover flows to the admin replacement bucket.

			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			final long chloeQortFromQora = chloe.getConfirmedBalance(Asset.QORT_FROM_QORA);

			long expectedBalance = initialBalances.get("alice").get(Asset.QORT) + blockReward - chloeQortFromQora;
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, expectedBalance);
		}
	}

	/** Test rewards for level 1 and 2 accounts. */
	@Test
	public void testLevel1And2Rewards() throws DataException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		try (final Repository repository = RepositoryManager.getRepository()) {

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
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.QORT);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.QORT);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.QORT);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.QORT);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are at the correct height and block reward value
			assertEquals(4, (int) repository.getBlockRepository().getLastBlock().getHeight());
			assertEquals(10000000000L, blockReward);

			/*
			 * Alice, Chloe, and Dilbert are 'online'. Bob is offline.
			 * Alice and Chloe are level 1, Dilbert is level 2.
			 * No legacy QORA holders.
			 *
			 * Alice, Chloe, and Dilbert should receive equal shares of the 6% block reward for Level 1 and 2.
			 * Alice should also receive the remaining admin replacement reward.
			 */

			// Level 1 and 2 always share the same reward in Qortium.
			final int level1And2SharePercent = 6_00; // 6%
			final long level1And2ShareAmount = (blockReward * level1And2SharePercent) / 100L / 100L;
			final long expectedReward = level1And2ShareAmount / 3; // The reward is split between Alice, Chloe, and Dilbert
			final long expectedAdminReward = blockReward - level1And2ShareAmount; // Alice should receive the remaining admin reward

			// Validate the balances to ensure that the fixed distribution is being applied.
			assertEquals(600000000, level1And2ShareAmount);
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance+expectedAdminReward+expectedReward);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance); // Bob not online so his balance remains the same
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance+expectedReward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance+expectedReward);

		}
	}

	/** Test rewards for level 3 and 4 accounts */
	@Test
	public void testLevel3And4Rewards() throws DataException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		try (final Repository repository = RepositoryManager.getRepository()) {

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
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.QORT);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.QORT);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.QORT);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.QORT);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are using the correct block reward value
			assertEquals(100000000L, blockReward);

			/*
			 * Alice, Bob, Chloe, and Dilbert are 'online'.
			 * Alice, Bob, and Chloe are level 3; Dilbert is level 4.
			 * No legacy QORA holders.
			 *
			 * Alice, Chloe, Bob and Dilbert should receive equal shares of the 13% block reward for level 3 and 4.
			 * Alice should also receive the remaining admin replacement reward.
			 */

			// Level 3 and 4 always share the same reward in Qortium.
			final int level3And4SharePercent = 13_00; // 13%
			final long level3And4ShareAmount = (blockReward * level3And4SharePercent) / 100L / 100L;
			final long expectedReward = level3And4ShareAmount / 4; // The reward is split between Alice, Bob, Chloe, and Dilbert
			final long expectedAdminReward = blockReward - level3And4ShareAmount; // Alice should receive the remaining admin reward

			// Validate the balances to ensure that the fixed distribution is being applied.
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance+expectedAdminReward+expectedReward);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance+expectedReward);
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance+expectedReward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance+expectedReward);

		}
	}

	/** Test rewards for level 5 and 6 accounts */
	@Test
	public void testLevel5And6Rewards() throws DataException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		try (final Repository repository = RepositoryManager.getRepository()) {

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
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.QORT);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.QORT);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.QORT);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.QORT);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are using the correct block reward value
			assertEquals(100000000L, blockReward);

			/*
			 * Alice, Bob, Chloe, and Dilbert are 'online'.
			 * Bob is level 1; Alice and Chloe are level 5; Dilbert is level 6.
			 * No legacy QORA holders.
			 *
			 * Alice, Chloe, and Dilbert should receive equal shares of the 19% block reward for level 5 and 6.
			 * Bob should receive all of the level 1 and 2 reward (6%)
			 * Alice should also receive the remaining admin replacement reward.
			 */

			// Level 5 and 6 always share the same reward in Qortium.
			final int level1And2SharePercent = 6_00; // 6%
			final int level5And6SharePercent = 19_00; // 19%
			final long level1And2ShareAmount = (blockReward * level1And2SharePercent) / 100L / 100L;
			final long level5And6ShareAmount = (blockReward * level5And6SharePercent) / 100L / 100L;
			final long expectedLevel1And2Reward = level1And2ShareAmount; // The reward is given entirely to Bob
			final long expectedLevel5And6Reward = level5And6ShareAmount / 3; // The reward is split between Alice, Chloe, and Dilbert
			final long expectedAdminReward = blockReward - level1And2ShareAmount - level5And6ShareAmount; // Alice should receive the remaining admin reward

			// Validate the balances to ensure that the fixed distribution is being applied.
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance+expectedAdminReward+expectedLevel5And6Reward);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance+expectedLevel1And2Reward);
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance+expectedLevel5And6Reward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance+expectedLevel5And6Reward);

		}
	}

	/** Test rewards for level 7 and 8 accounts */
	@Test
	public void testLevel7And8Rewards() throws DataException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		try (final Repository repository = RepositoryManager.getRepository()) {

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
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.QORT);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.QORT);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.QORT);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.QORT);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are using the correct block reward value
			assertEquals(100000000L, blockReward);

			/*
			 * Alice, Chloe, and Dilbert are 'online'.
			 * Alice and Chloe are level 7; Dilbert is level 8.
			 * No legacy QORA holders.
			 *
			 * Alice, Chloe, and Dilbert should receive equal shares of the 26% block reward for level 7 and 8.
			 * Alice should also receive the remaining admin replacement reward.
			 */

			// Level 7 and 8 always share the same reward in Qortium.
			final int level7And8SharePercent = 26_00; // 26%
			final long level7And8ShareAmount = (blockReward * level7And8SharePercent) / 100L / 100L;
			final long expectedLevel7And8Reward = level7And8ShareAmount / 3; // The reward is split between Alice, Chloe, and Dilbert
			final long expectedAdminReward = blockReward - level7And8ShareAmount; // Alice should receive the remaining admin reward

			// Validate the balances to ensure that the fixed distribution is being applied.
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance+expectedAdminReward+expectedLevel7And8Reward);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance); // Bob not online so his balance remains the same
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance+expectedLevel7And8Reward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance+expectedLevel7And8Reward);

			// Orphan and ensure balances return to their previous values
			BlockUtils.orphanBlocks(repository, 1);

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance);
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance);

		}
	}

	/** Test rewards for level 9 and 10 accounts */
	@Test
	public void testLevel9And10Rewards() throws DataException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		try (final Repository repository = RepositoryManager.getRepository()) {

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
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.QORT);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.QORT);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.QORT);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.QORT);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are using the correct block reward value
			assertEquals(100000000L, blockReward);

			/*
			 * Alice, Bob, Chloe, and Dilbert are 'online'.
			 * Bob is level 1; Alice and Chloe are level 9; Dilbert is level 10.
			 * No legacy QORA holders.
			 *
			 * Alice, Chloe, and Dilbert should receive equal shares of the 32% block reward for level 9 and 10.
			 * Bob should receive all of the level 1 and 2 reward (6%)
			 * Alice should also receive the remaining admin replacement reward.
			 */

			// Level 9 and 10 always share the same reward in Qortium.
			final int level1And2SharePercent = 6_00; // 6%
			final int level9And10SharePercent = 32_00; // 32%
			final long level1And2ShareAmount = (blockReward * level1And2SharePercent) / 100L / 100L;
			final long level9And10ShareAmount = (blockReward * level9And10SharePercent) / 100L / 100L;
			final long expectedLevel1And2Reward = level1And2ShareAmount; // The reward is given entirely to Bob
			final long expectedLevel9And10Reward = level9And10ShareAmount / 3; // The reward is split between Alice, Chloe, and Dilbert
			final long expectedAdminReward = blockReward - level1And2ShareAmount - level9And10ShareAmount; // Alice should receive the remaining admin reward

			// Validate the balances to ensure that the fixed distribution is being applied.
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance+expectedAdminReward+expectedLevel9And10Reward);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance+expectedLevel1And2Reward);
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance+expectedLevel9And10Reward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance+expectedLevel9And10Reward);

			// Orphan and ensure balances return to their previous values
			BlockUtils.orphanBlocks(repository, 1);

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance);
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance);

		}
	}

	/** Test rewards for level 7 and 8 accounts, when the tier doesn't yet have enough minters in it */
	@Test
	public void testLevel7And8RewardsPreActivation() throws DataException, IllegalAccessException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		// Set minAccountsToActivateShareBin to 4 so that share bins 7-8 and 9-10 are considered inactive
		FieldUtils.writeField(BlockChain.getInstance(), "minAccountsToActivateShareBin", 4, true);

		try (final Repository repository = RepositoryManager.getRepository()) {

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
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.QORT);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.QORT);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.QORT);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.QORT);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are using the correct block reward value
			assertEquals(100000000L, blockReward);

			/*
			 * Alice, Chloe, and Dilbert are 'online'.
			 * Alice and Chloe are level 7; Dilbert is level 8.
			 * No legacy QORA holders.
			 *
			 * Level 7 and 8 is not yet activated, so its rewards are added to the level 5 and 6 share bin.
			 * There are no level 5 and 6 online.
			 * Alice, Chloe, and Dilbert should receive equal shares of the 45% block reward for levels 5 to 8.
			 * Alice should also receive the remaining admin replacement reward.
			 */

			final int level5To8SharePercent = 45_00; // 45% (combined 19% and 26%)
			final long level5To8ShareAmount = (blockReward * level5To8SharePercent) / 100L / 100L;
			final long expectedLevel5To8Reward = level5To8ShareAmount / 3; // The reward is split between Alice, Chloe, and Dilbert
			final long expectedAdminReward = blockReward - level5To8ShareAmount; // Alice should receive the remaining admin reward

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance+expectedAdminReward+expectedLevel5To8Reward);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance); // Bob not online so his balance remains the same
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance+expectedLevel5To8Reward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance+expectedLevel5To8Reward);

			// Orphan and ensure balances return to their previous values
			BlockUtils.orphanBlocks(repository, 1);

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance);
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance);

		}
	}

	/** Test rewards for level 9 and 10 accounts, when the tier doesn't yet have enough minters in it.
	 * Tier 7-8 isn't activated either, so the rewards and minters are all moved to tier 5-6. */
	@Test
	public void testLevel9And10RewardsPreActivation() throws DataException, IllegalAccessException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		// Set minAccountsToActivateShareBin to 4 so that share bins 7-8 and 9-10 are considered inactive
		FieldUtils.writeField(BlockChain.getInstance(), "minAccountsToActivateShareBin", 4, true);

		try (final Repository repository = RepositoryManager.getRepository()) {

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
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.QORT);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.QORT);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.QORT);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.QORT);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are using the correct block reward value
			assertEquals(100000000L, blockReward);

			/*
			 * Alice, Bob, Chloe, and Dilbert are 'online'.
			 * Bob is level 1; Alice and Chloe are level 9; Dilbert is level 10.
			 * No legacy QORA holders.
			 *
			 * Levels 7+8, and 9+10 are not yet activated, so their rewards are added to the level 5 and 6 share bin.
			 * There are no levels 5-8 online.
			 * Bob should receive all of the level 1 and 2 reward (6%).
			 * Alice, Chloe, and Dilbert should receive equal shares of the 77% block reward for levels 5 to 10.
			 * Alice should also receive the remaining admin replacement reward.
			 */

			final int level1And2SharePercent = 6_00; // 6%
			final int level5To10SharePercent = 77_00; // 77% (combined 19%, 26%, and 32%)
			final long level1And2ShareAmount = (blockReward * level1And2SharePercent) / 100L / 100L;
			final long level5To10ShareAmount = (blockReward * level5To10SharePercent) / 100L / 100L;
			final long expectedLevel1And2Reward = level1And2ShareAmount; // The reward is given entirely to Bob
			final long expectedLevel5To10Reward = level5To10ShareAmount / 3; // The reward is split between Alice, Chloe, and Dilbert
			final long expectedAdminReward = blockReward - level1And2ShareAmount - level5To10ShareAmount; // Alice should receive the remaining admin reward

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance+expectedAdminReward+expectedLevel5To10Reward);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance+expectedLevel1And2Reward);
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance+expectedLevel5To10Reward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance+expectedLevel5To10Reward);

			// Orphan and ensure balances return to their previous values
			BlockUtils.orphanBlocks(repository, 1);

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance);
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance);

		}
	}

	/** Test rewards for level 7 and 8 accounts, when the tier reaches the minimum number of accounts */
	@Test
	public void testLevel7And8RewardsPreAndPostActivation() throws DataException, IllegalAccessException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		// Set minAccountsToActivateShareBin to 3 so that share bins 7-8 and 9-10 are considered inactive at first
		FieldUtils.writeField(BlockChain.getInstance(), "minAccountsToActivateShareBin", 3, true);

		try (final Repository repository = RepositoryManager.getRepository()) {

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
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.QORT);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.QORT);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.QORT);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.QORT);

			// Mint a block
			long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are using the correct block reward value
			assertEquals(100000000L, blockReward);

			/*
			 * Alice, Chloe, and Dilbert are 'online'.
			 * Chloe is level 6; Alice and Dilbert are level 7.
			 * No legacy QORA holders.
			 *
			 * Level 7 and 8 is not yet activated, so its rewards are added to the level 5 and 6 share bin.
			 * Alice, Chloe, and Dilbert should receive equal shares of the 45% block reward for levels 5 to 8.
			 * Alice should also receive the remaining admin replacement reward.
			 */

			final int level5To8SharePercent = 45_00; // 45% (combined 19% and 26%)
			final long level5To8ShareAmount = (blockReward * level5To8SharePercent) / 100L / 100L;
			final long expectedLevel5To8Reward = level5To8ShareAmount / 3; // The reward is split between Alice, Chloe, and Dilbert
			final long expectedAdminReward = blockReward - level5To8ShareAmount; // Alice should receive the remaining admin reward

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance+expectedAdminReward+expectedLevel5To8Reward);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance); // Bob not online so his balance remains the same
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance+expectedLevel5To8Reward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance+expectedLevel5To8Reward);

			// Ensure that the levels are as we expect
			assertEquals(7, (int) Common.getTestAccount(repository, "alice").getLevel());
			assertEquals(1, (int) Common.getTestAccount(repository, "bob").getLevel());
			assertEquals(6, (int) Common.getTestAccount(repository, "chloe").getLevel());
			assertEquals(7, (int) Common.getTestAccount(repository, "dilbert").getLevel());

			// Capture pre-activation balances
			Map<String, Map<Long, Long>> preActivationBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);
			final long alicePreActivationBalance = preActivationBalances.get("alice").get(Asset.QORT);
			final long bobPreActivationBalance = preActivationBalances.get("bob").get(Asset.QORT);
			final long chloePreActivationBalance = preActivationBalances.get("chloe").get(Asset.QORT);
			final long dilbertPreActivationBalance = preActivationBalances.get("dilbert").get(Asset.QORT);

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
			 * No legacy QORA holders.
			 *
			 * Level 7 and 8 is now activated, so its rewards are paid out in the normal way.
			 * Alice, Chloe, and Dilbert should receive equal shares of the 26% block reward for levels 7 to 8.
			 * Alice should also receive the remaining admin replacement reward.
			 */

			final int level7To8SharePercent = 26_00; // 26%
			final long level7To8ShareAmount = (blockReward * level7To8SharePercent) / 100L / 100L;
			final long expectedLevel7To8Reward = level7To8ShareAmount / 3; // The reward is split between Alice, Chloe, and Dilbert
			final long expectedSecondAdminReward = blockReward - level7To8ShareAmount; // Alice should receive the remaining admin reward

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, alicePreActivationBalance+expectedSecondAdminReward+expectedLevel7To8Reward);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobPreActivationBalance); // Bob not online so his balance remains the same
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloePreActivationBalance+expectedLevel7To8Reward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertPreActivationBalance+expectedLevel7To8Reward);


			// Orphan and ensure balances return to their pre-activation values
			BlockUtils.orphanBlocks(repository, 1);

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, alicePreActivationBalance);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobPreActivationBalance);
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloePreActivationBalance);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertPreActivationBalance);


			// Orphan again and ensure balances return to their initial values
			BlockUtils.orphanBlocks(repository, 1);

			// Validate the balances
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance);
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance);

		}
	}

	/** Test rewards for level 1 and 2 accounts at a higher height using the fixed baseline share layout. */
	@Test
	public void testLevel1And2RewardsShareBinsV2() throws DataException {
		Common.useSettings("test-settings-v2-reward-levels.json");

		try (final Repository repository = RepositoryManager.getRepository()) {

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
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.QORT);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.QORT);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.QORT);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.QORT);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are at the correct height and block reward value
			assertEquals(1000, (int) repository.getBlockRepository().getLastBlock().getHeight());
			assertEquals(100000000L, blockReward);

			final int level1And2SharePercent = 6_00; // 6%
			final long level1And2ShareAmount = (blockReward * level1And2SharePercent) / 100L / 100L;
			final long expectedLevel1And2Reward = level1And2ShareAmount / 2; // The reward is split between Chloe and Dilbert
			final long expectedAliceReward = blockReward - level1And2ShareAmount; // Alice receives her high-level bin plus the admin remainder

			// Validate the balances
			assertEquals(6000000, level1And2ShareAmount);
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance+expectedAliceReward);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance); // Bob not online so his balance remains the same
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance+expectedLevel1And2Reward);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance+expectedLevel1And2Reward);

			BlockUtils.orphanBlocks(repository, 1);
			assertEquals(999, (int) repository.getBlockRepository().getLastBlock().getHeight());

			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance); // Bob not online so his balance remains the same
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance);
		}
	}

	/** Test rewards for level 1 and 2 accounts with legacy QORA holders using the fixed baseline share layout. */
	@Test
	public void testLevel1And2RewardsShareBinsV2WithQoraHolders() throws DataException {
		Common.useSettings("test-settings-v2-qora-holder-extremes.json");

		try (final Repository repository = RepositoryManager.getRepository()) {

			List<PrivateKeyAccount> mintingAndOnlineAccounts = new ArrayList<>();

			// Some legacy QORA holders exist (Bob and Chloe)

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
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.QORT, Asset.LEGACY_QORA, Asset.QORT_FROM_QORA);
			final long aliceInitialBalance = initialBalances.get("alice").get(Asset.QORT);
			final long bobInitialBalance = initialBalances.get("bob").get(Asset.QORT);
			final long chloeInitialBalance = initialBalances.get("chloe").get(Asset.QORT);
			final long dilbertInitialBalance = initialBalances.get("dilbert").get(Asset.QORT);

			// Mint a block
			final long blockReward = BlockUtils.getNextBlockReward(repository);
			BlockMinter.mintTestingBlock(repository, mintingAndOnlineAccounts.toArray(new PrivateKeyAccount[0]));

			// Ensure we are at the correct height and block reward value
			assertEquals(1000, (int) repository.getBlockRepository().getLastBlock().getHeight());
			assertEquals(100000000L, blockReward);

			final int level1And2SharePercent = 6_00; // 6%
			final int qoraSharePercent = 1_00; // 1%
			final long qoraShareAmount = (blockReward * qoraSharePercent) / 100L / 100L;
			final long level1And2ShareAmount = (blockReward * level1And2SharePercent) / 100L / 100L;
			final long expectedLevel1And2Reward = level1And2ShareAmount / 2; // The reward is split between Chloe and Dilbert
			final long expectedAliceReward = blockReward - level1And2ShareAmount - qoraShareAmount; // Alice receives her high-level bin plus the admin remainder

			// Validate the balances
			assertEquals(6000000, level1And2ShareAmount);
			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance+expectedAliceReward);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance); // Bob not online so his balance remains the same
			// Chloe is a QORA holder and will receive additional QORT, so it's not easy to pre-calculate her balance
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance+expectedLevel1And2Reward);

			BlockUtils.orphanBlocks(repository, 1);
			assertEquals(999, (int) repository.getBlockRepository().getLastBlock().getHeight());

			AccountUtils.assertBalance(repository, "alice", Asset.QORT, aliceInitialBalance);
			AccountUtils.assertBalance(repository, "bob", Asset.QORT, bobInitialBalance); // Bob not online so his balance remains the same
			AccountUtils.assertBalance(repository, "chloe", Asset.QORT, chloeInitialBalance);
			AccountUtils.assertBalance(repository, "dilbert", Asset.QORT, dilbertInitialBalance);
		}
	}
}
