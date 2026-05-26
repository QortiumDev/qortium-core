package org.qortium.test.minting;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.logging.log4j.Level;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.block.Block;
import org.qortium.block.BlockChain;
import org.qortium.controller.BlockMinter;
import org.qortium.data.block.BlockData;
import org.qortium.data.transaction.PaymentTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.*;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transform.TransformationException;
import org.qortium.transform.block.BlockTransformer;
import org.qortium.utils.NTP;

import java.util.*;

import static org.junit.Assert.*;

public class BatchRewardTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings("test-settings-v2-reward-levels.json");
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testBatchReward() throws DataException, IllegalAccessException {
		// Set reward batching to every 10 blocks, starting at block 20, looking back the last 3 blocks for online accounts
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 20, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 10, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 3, true);

		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountUtils.setMintingData(repository, "alice", 1);

			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);
			final int aliceStartingBlocksMinted = AccountUtils.getBlocksMinted(repository, "alice");

			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			Long blockReward = BlockUtils.getNextBlockReward(repository);

			// Deploy an AT so we have transaction fees in each block
			// This also mints block 2
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, Common.getTestAccount(repository, "bob"), AtUtils.buildSimpleAT(), 1_00000000L);
			assertEquals(repository.getBlockRepository().getBlockchainHeight(), 2);

			long expectedBalance = initialBalances.get("alice").get(Asset.NATIVE) + blockReward + deployAtTransaction.getTransactionData().getFee();
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, expectedBalance);
			long aliceCurrentBalance = expectedBalance;

			AccountUtils.assertBlocksMinted(repository, "alice", aliceStartingBlocksMinted + 1);

			// Mint blocks 3-20
			Block block;
			for (int i=3; i<=20; i++) {
				expectedBalance = aliceCurrentBalance + BlockUtils.getNextBlockReward(repository);
				block = BlockUtils.mintBlockWithReorgs(repository, 10);
				expectedBalance += block.getBlockData().getTotalFees();
				assertFalse(block.isBatchRewardDistributionActive());
				assertTrue(block.isRewardDistributionBlock());
				AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, expectedBalance);
				aliceCurrentBalance = expectedBalance;
			}
			assertEquals(repository.getBlockRepository().getBlockchainHeight(), 20);

			AccountUtils.assertBlocksMinted(repository, "alice", aliceStartingBlocksMinted + 19);

			// Mint blocks 21-29
			long expectedFees = 0L;
			for (int i=21; i<=29; i++) {

				// Create payment transaction so that an additional fee is added to the next block
				Account recipient = AccountUtils.createRandomAccount(repository);
				TransactionData paymentTransactionData = new PaymentTransactionData(TestTransaction.generateBase(bob), recipient.getAddress(), 100000L);
				TransactionUtils.signAndImportValid(repository, paymentTransactionData, bob);

				block = BlockUtils.mintBlockWithReorgs(repository, 8);
				expectedFees += block.getBlockData().getTotalFees();

				// Batch distribution now active
				assertTrue(block.isBatchRewardDistributionActive());

				// It's not a distribution block because we haven't reached the batch size yet
				assertFalse(block.isRewardDistributionBlock());
			}
			assertEquals(repository.getBlockRepository().getBlockchainHeight(), 29);

			AccountUtils.assertBlocksMinted(repository, "alice", aliceStartingBlocksMinted + 19);

			// No payouts since block 20 due to batching (to be paid at block 30)
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, expectedBalance);

			// Block reward to be used for next batch payout
			blockReward = BlockUtils.getNextBlockReward(repository);

			// Mint block 30
			block = BlockUtils.mintBlockWithReorgs(repository, 9);
			assertEquals(repository.getBlockRepository().getBlockchainHeight(), 30);

			expectedFees += block.getBlockData().getTotalFees();
			assertTrue(expectedFees > 0);

			AccountUtils.assertBlocksMinted(repository, "alice", aliceStartingBlocksMinted + 29);

			// Batch distribution still active
			assertTrue(block.isBatchRewardDistributionActive());

			// It's a distribution block
			assertTrue(block.isRewardDistributionBlock());

			// Balance should increase by the block reward multiplied by the batch size
			expectedBalance = aliceCurrentBalance + (blockReward * BlockChain.getInstance().getBlockRewardBatchSize()) + expectedFees;
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, expectedBalance);

			// Mint blocks 31-39
			for (int i=31; i<=39; i++) {
				block = BlockUtils.mintBlockWithReorgs(repository, 13);

				// Batch distribution still active
				assertTrue(block.isBatchRewardDistributionActive());

				// It's not a distribution block because we haven't reached the batch size yet
				assertFalse(block.isRewardDistributionBlock());
			}
			assertEquals(repository.getBlockRepository().getBlockchainHeight(), 39);

			AccountUtils.assertBlocksMinted(repository, "alice", aliceStartingBlocksMinted + 29);

			// No payouts since block 30 due to batching (to be paid at block 40)
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, expectedBalance);

			// Batch distribution still active
			assertTrue(block.isBatchRewardDistributionActive());

			// It's not a distribution block
			assertFalse(block.isRewardDistributionBlock());
		}
	}

	@Test
	public void testBatchRewardOnlineAccounts() throws DataException, IllegalAccessException {
		// Set reward batching to every 10 blocks, starting at block 0, looking back the last 3 blocks for online accounts
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 10, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 3, true);

		try (final Repository repository = RepositoryManager.getRepository()) {

			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, "dilbert");

			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			PrivateKeyAccount chloeSelfShare = Common.getTestAccount(repository, "chloe-reward-share");
			PrivateKeyAccount dilbertSelfShare = Common.getTestAccount(repository, "dilbert-reward-share");

			// Create self shares for bob, chloe and dilbert
			AccountUtils.generateSelfShares(repository, List.of(bob, chloe, dilbert));

			// Mint blocks 2-6
			for (int i=2; i<=6; i++) {
				Block block = BlockUtils.mintBlockWithReorgs(repository, 5);
				assertTrue(block.isBatchRewardDistributionActive());
				assertFalse(block.isRewardDistributionBlock());
			}

			// Mint block 7
			List<PrivateKeyAccount> onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare);
			Block block7 = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(2, block7.getBlockData().getOnlineAccountsCount());

			// Mint block 8
			onlineAccounts = Arrays.asList(aliceSelfShare, chloeSelfShare);
			Block block8 = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(2, block8.getBlockData().getOnlineAccountsCount());

			// Mint block 9
			onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare, dilbertSelfShare);
			Block block9 = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(3, block9.getBlockData().getOnlineAccountsCount());

			// Mint block 10
			Block block10 = BlockUtils.mintBlockWithReorgs(repository, 11);

			// Online accounts should be included from block 8
			assertEquals(3, block10.getBlockData().getOnlineAccountsCount());

			assertEquals(repository.getBlockRepository().getBlockchainHeight(), 10);

			// It's a distribution block
			assertTrue(block10.isBatchRewardDistributionBlock());
		}
	}

	@Test
	public void testBatchReward100Blocks() throws DataException, IllegalAccessException {
		// Set reward batching to every 100 blocks, starting at block 100, looking back the last 10 blocks for online accounts
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 100, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 100, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 10, true);

		try (final Repository repository = RepositoryManager.getRepository()) {

			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, "dilbert");

			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			PrivateKeyAccount chloeSelfShare = Common.getTestAccount(repository, "chloe-reward-share");

			// Create self shares for bob, chloe and dilbert
			AccountUtils.generateSelfShares(repository, List.of(bob, chloe, dilbert));

			// Mint blocks 2-100 - these should be regular non-batched reward distribution blocks
			for (int i=2; i<=100; i++) {
				Block block = BlockUtils.mintBlockWithReorgs(repository, 2);
				assertFalse(block.isBatchRewardDistributionActive());
				assertTrue(block.isRewardDistributionBlock());
				assertFalse(block.isBatchRewardDistributionBlock());
				assertTrue(block.isOnlineAccountsBlock());
			}

			// Mint blocks 101-189 - these should have no online accounts or rewards
			for (int i=101; i<=189; i++) {
				Block block = BlockUtils.mintBlockWithReorgs(repository, 2);
				assertTrue(block.isBatchRewardDistributionActive());
				assertFalse(block.isRewardDistributionBlock());
				assertFalse(block.isBatchRewardDistributionBlock());
				assertFalse(block.isOnlineAccountsBlock());
				assertEquals(0, block.getBlockData().getOnlineAccountsCount());
			}

			// Mint blocks 190-198 - these should have online accounts but no rewards
			for (int i=190; i<=198; i++) {
				List<PrivateKeyAccount> onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare);
				Block block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
				assertTrue(block.isBatchRewardDistributionActive());
				assertFalse(block.isRewardDistributionBlock());
				assertFalse(block.isBatchRewardDistributionBlock());
				assertTrue(block.isOnlineAccountsBlock());
				assertEquals(2, block.getBlockData().getOnlineAccountsCount());
			}

			// Mint block 199 - same as above, but with more online accounts
			List<PrivateKeyAccount> onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare, chloeSelfShare);
			Block block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertTrue(block.isBatchRewardDistributionActive());
			assertFalse(block.isRewardDistributionBlock());
			assertFalse(block.isBatchRewardDistributionBlock());
			assertTrue(block.isOnlineAccountsBlock());
			assertEquals(3, block.getBlockData().getOnlineAccountsCount());

			// Mint block 200
			Block block200 = BlockUtils.mintBlockWithReorgs(repository, 12);

			// Online accounts should be included from block 199
			assertEquals(3, block200.getBlockData().getOnlineAccountsCount());

			assertEquals(repository.getBlockRepository().getBlockchainHeight(), 200);

			// It's a distribution block (which is technically also an online accounts block)
			assertTrue(block200.isBatchRewardDistributionBlock());
			assertTrue(block200.isRewardDistributionBlock());
			assertTrue(block200.isBatchRewardDistributionActive());
			assertTrue(block200.isOnlineAccountsBlock());
		}
	}

	@Test
	public void testBatchRewardHighestOnlineAccountsCount() throws DataException, IllegalAccessException {
		// Set reward batching to every 10 blocks, starting at block 0, looking back the last 3 blocks for online accounts
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 10, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 3, true);

		try (final Repository repository = RepositoryManager.getRepository()) {

			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, "dilbert");

			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			PrivateKeyAccount chloeSelfShare = Common.getTestAccount(repository, "chloe-reward-share");
			PrivateKeyAccount dilbertSelfShare = Common.getTestAccount(repository, "dilbert-reward-share");

			// Create self shares for bob, chloe and dilbert
			AccountUtils.generateSelfShares(repository, List.of(bob, chloe, dilbert));

			// Mint blocks 2-6
			for (int i=2; i<=6; i++) {
				Block block = BlockUtils.mintBlockWithReorgs(repository, 3);
				assertTrue(block.isBatchRewardDistributionActive());
				assertFalse(block.isRewardDistributionBlock());
			}

			// Capture initial balances now that the online accounts test is ready to begin
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);

			// Mint block 7
			List<PrivateKeyAccount> onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare);
			Block block7 = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(2, block7.getBlockData().getOnlineAccountsCount());

			// Mint block 8
			onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare, chloeSelfShare);
			Block block8 = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(3, block8.getBlockData().getOnlineAccountsCount());

			// Mint block 9
			onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare, dilbertSelfShare);
			Block block9 = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(3, block9.getBlockData().getOnlineAccountsCount());

			// Mint block 10
			Block block10 = BlockUtils.mintBlockWithReorgs(repository, 7);

			// Online accounts should be included from block 8
			assertEquals(3, block10.getBlockData().getOnlineAccountsCount());

			// Dilbert's balance should remain the same as he wasn't included in block 8
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, initialBalances.get("dilbert").get(Asset.NATIVE));

			// Alice, Bob, and Chloe's balances should have increased, as they were all included in block 8 (and therefore block 10)
			AccountUtils.assertBalanceGreaterThan(repository, "alice", Asset.NATIVE, initialBalances.get("alice").get(Asset.NATIVE));
			AccountUtils.assertBalanceGreaterThan(repository, "bob", Asset.NATIVE, initialBalances.get("bob").get(Asset.NATIVE));
			AccountUtils.assertBalanceGreaterThan(repository, "chloe", Asset.NATIVE, initialBalances.get("chloe").get(Asset.NATIVE));

			assertEquals(repository.getBlockRepository().getBlockchainHeight(), 10);

			// It's a distribution block
			assertTrue(block10.isBatchRewardDistributionBlock());
		}
	}

	@Test
	public void testBatchRewardNoOnlineAccounts() throws DataException, IllegalAccessException {
		// Set reward batching to every 10 blocks, starting at block 0, looking back the last 3 blocks for online accounts
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 10, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 3, true);

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");

			// Mint blocks 2-6 with no online accounts
			for (int i=2; i<=6; i++) {
				Block block = BlockMinter.mintTestingBlockUnvalidatedWithoutOnlineAccounts(repository, aliceSelfShare);
				assertNotNull("Minted block must not be null", block);
				assertTrue(block.isBatchRewardDistributionActive());
				assertFalse(block.isRewardDistributionBlock());
			}

			// Mint block 7 with no online accounts
			Block block7;
			try (LogLevelOverride ignored = LogLevelOverride.setLevel(Block.class, Level.FATAL)) {
				block7 = BlockMinter.mintTestingBlockUnvalidatedWithoutOnlineAccounts(repository, aliceSelfShare);
				assertNull("Minted block must be null", block7);
			}

			// Mint block 7, this time with an online account
			List<PrivateKeyAccount> onlineAccounts = Arrays.asList(aliceSelfShare);
			block7 = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertNotNull("Minted block must not be null", block7);
			assertEquals(1, block7.getBlockData().getOnlineAccountsCount());

			// Mint block 8 with no online accounts
			Block block8;
			try (LogLevelOverride ignored = LogLevelOverride.setLevel(Block.class, Level.FATAL)) {
				block8 = BlockMinter.mintTestingBlockUnvalidatedWithoutOnlineAccounts(repository, aliceSelfShare);
				assertNull("Minted block must be null", block8);
			}

			// Mint block 8, this time with an online account
			block8 = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertNotNull("Minted block must not be null", block8);
			assertEquals(1, block8.getBlockData().getOnlineAccountsCount());

			// Mint block 9 with no online accounts
			Block block9;
			try (LogLevelOverride ignored = LogLevelOverride.setLevel(Block.class, Level.FATAL)) {
				block9 = BlockMinter.mintTestingBlockUnvalidatedWithoutOnlineAccounts(repository, aliceSelfShare);
				assertNull("Minted block must be null", block9);
			}

			// Mint block 9, this time with an online account
			block9 = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertNotNull("Minted block must not be null", block9);
			assertEquals(1, block9.getBlockData().getOnlineAccountsCount());

			// Mint block 10
			Block block10 = BlockUtils.mintBlockWithReorgs(repository, 8);
			assertEquals(repository.getBlockRepository().getBlockchainHeight(), 10);

			// It's a distribution block
			assertTrue(block10.isBatchRewardDistributionBlock());
		}
	}

	@Test
	public void testMissingOnlineAccountsInDistributionBlock() throws DataException, IllegalAccessException {
		// Set reward batching to every 10 blocks, starting at block 0, looking back the last 3 blocks for online accounts
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 10, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 3, true);

		try (final Repository repository = RepositoryManager.getRepository()) {

			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, "dilbert");

			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			PrivateKeyAccount chloeSelfShare = Common.getTestAccount(repository, "chloe-reward-share");

			// Create self shares for bob, chloe and dilbert
			AccountUtils.generateSelfShares(repository, List.of(bob, chloe, dilbert));

			// Mint blocks 2-6
			for (int i=2; i<=6; i++) {
				Block block = BlockUtils.mintBlockWithReorgs(repository, 9);
				assertTrue(block.isBatchRewardDistributionActive());
				assertFalse(block.isRewardDistributionBlock());
			}

			// Mint blocks 7-9
			for (int i=7; i<=9; i++) {
				List<PrivateKeyAccount> onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare, chloeSelfShare);
				Block block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
				assertEquals(3, block.getBlockData().getOnlineAccountsCount());
			}

			// Mint block 10
			Block block10 = Block.mint(repository, repository.getBlockRepository().getLastBlock(), aliceSelfShare);
			assertNotNull(block10);

			// Remove online accounts (incorrect as there should be 3)
			block10.getBlockData().setEncodedOnlineAccounts(new byte[0]);

			block10.sign();
			block10.clearOnlineAccountsValidationCache();

			// Must be invalid because online accounts don't match
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNTS_INVALID, block10.isValid());
		}
	}

	@Test
	public void testSignaturesIncludedInDistributionBlock() throws DataException, IllegalAccessException {
		// Set reward batching to every 10 blocks, starting at block 0, looking back the last 3 blocks for online accounts
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 10, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 3, true);

		try (final Repository repository = RepositoryManager.getRepository()) {

			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, "dilbert");

			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			PrivateKeyAccount chloeSelfShare = Common.getTestAccount(repository, "chloe-reward-share");

			// Create self shares for bob, chloe and dilbert
			AccountUtils.generateSelfShares(repository, List.of(bob, chloe, dilbert));

			// Mint blocks 2-6
			for (int i=2; i<=6; i++) {
				Block block = BlockUtils.mintBlockWithReorgs(repository, 4);
				assertTrue(block.isBatchRewardDistributionActive());
				assertFalse(block.isRewardDistributionBlock());
			}

			// Mint blocks 7-9
			for (int i=7; i<=9; i++) {
				List<PrivateKeyAccount> onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare, chloeSelfShare);
				Block block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
				assertEquals(3, block.getBlockData().getOnlineAccountsCount());
			}

			// Mint block 10
			BlockData previousBlock = repository.getBlockRepository().getLastBlock();
			Block block10 = Block.mint(repository, previousBlock, aliceSelfShare);
			assertNotNull(block10);

			// Include online accounts signatures
			block10.getBlockData().setOnlineAccountsSignatures(previousBlock.getOnlineAccountsSignatures());

			block10.sign();
			block10.clearOnlineAccountsValidationCache();

			// Must be invalid because signatures aren't allowed to be included
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNTS_INVALID, block10.isValid());
		}
	}

	@Test
	public void testOnlineAccountsTimestampIncludedInDistributionBlock() throws DataException, IllegalAccessException {
		// Set reward batching to every 10 blocks, starting at block 0, looking back the last 3 blocks for online accounts
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 10, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 3, true);

		try (final Repository repository = RepositoryManager.getRepository()) {

			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, "dilbert");

			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			PrivateKeyAccount chloeSelfShare = Common.getTestAccount(repository, "chloe-reward-share");

			// Create self shares for bob, chloe and dilbert
			AccountUtils.generateSelfShares(repository, List.of(bob, chloe, dilbert));

			// Mint blocks 2-6
			for (int i=2; i<=6; i++) {
				Block block = BlockUtils.mintBlockWithReorgs(repository, 6);
				assertTrue(block.isBatchRewardDistributionActive());
				assertFalse(block.isRewardDistributionBlock());
			}

			// Mint blocks 7-9
			for (int i=7; i<=9; i++) {
				List<PrivateKeyAccount> onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare, chloeSelfShare);
				Block block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
				assertEquals(3, block.getBlockData().getOnlineAccountsCount());
			}

			// Mint block 10
			BlockData previousBlock = repository.getBlockRepository().getLastBlock();
			Block block10 = Block.mint(repository, previousBlock, aliceSelfShare);
			assertNotNull(block10);

			// Include online accounts timestamp
			block10.getBlockData().setOnlineAccountsTimestamp(previousBlock.getOnlineAccountsTimestamp());

			block10.sign();
			block10.clearOnlineAccountsValidationCache();

			// Must be invalid because timestamp isn't allowed to be included
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNTS_INVALID, block10.isValid());
		}
	}

	@Test
	public void testIncorrectOnlineAccountsCountInDistributionBlock() throws DataException, IllegalAccessException {
		// Set reward batching to every 10 blocks, starting at block 0, looking back the last 3 blocks for online accounts
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 10, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 3, true);

		try (final Repository repository = RepositoryManager.getRepository()) {

			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, "dilbert");

			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			PrivateKeyAccount chloeSelfShare = Common.getTestAccount(repository, "chloe-reward-share");

			// Create self shares for bob, chloe and dilbert
			AccountUtils.generateSelfShares(repository, List.of(bob, chloe, dilbert));

			// Mint blocks 2-6
			for (int i=2; i<=6; i++) {
				Block block = BlockUtils.mintBlockWithReorgs(repository, 5);
				assertTrue(block.isBatchRewardDistributionActive());
				assertFalse(block.isRewardDistributionBlock());
			}

			// Mint blocks 7-9
			for (int i=7; i<=9; i++) {
				List<PrivateKeyAccount> onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare, chloeSelfShare);
				Block block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
				assertEquals(3, block.getBlockData().getOnlineAccountsCount());
			}

			// Mint block 10
			BlockData previousBlock = repository.getBlockRepository().getLastBlock();
			Block block10 = Block.mint(repository, previousBlock, aliceSelfShare);
			assertNotNull(block10);

			// Update online accounts count so that it is incorrect
			block10.getBlockData().setOnlineAccountsCount(10);

			block10.sign();
			block10.clearOnlineAccountsValidationCache();

			// Must be invalid because online accounts count is incorrect
			assertEquals(Block.ValidationResult.ONLINE_ACCOUNTS_INVALID, block10.isValid());
		}
	}

	@Test
	public void testBatchRewardBlockSerialization() throws DataException, IllegalAccessException, TransformationException {
		// Set reward batching to every 10 blocks, starting at block 0, looking back the last 3 blocks for online accounts
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 10, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 3, true);

		try (final Repository repository = RepositoryManager.getRepository()) {

			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, "dilbert");

			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			PrivateKeyAccount chloeSelfShare = Common.getTestAccount(repository, "chloe-reward-share");
			PrivateKeyAccount dilbertSelfShare = Common.getTestAccount(repository, "dilbert-reward-share");

			// Create self shares for bob, chloe and dilbert
			AccountUtils.generateSelfShares(repository, List.of(bob, chloe, dilbert));

			// Mint blocks 2-6
			Block block = null;
			for (int i=2; i<=6; i++) {
				block = BlockUtils.mintBlockWithReorgs(repository, 7);
				assertTrue(block.isBatchRewardDistributionActive());
				assertFalse(block.isRewardDistributionBlock());
			}

			// Test serialising and deserializing a block with no online accounts
			BlockData block6Data = block.getBlockData();
			byte[] block6Bytes = BlockTransformer.toBytes(block);
			BlockData block6DataDeserialized = BlockTransformer.fromBytes(block6Bytes).getBlockData();
			BlockUtils.assertEqual(block6Data, block6DataDeserialized);

			// Capture initial balances now that the online accounts test is ready to begin
			Map<String, Map<Long, Long>> initialBalances = AccountUtils.getBalances(repository, Asset.NATIVE);

			// Mint block 7
			List<PrivateKeyAccount> onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare);
			Block block7 = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(2, block7.getBlockData().getOnlineAccountsCount());

			// Mint block 8
			onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare, chloeSelfShare);
			Block block8 = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(3, block8.getBlockData().getOnlineAccountsCount());

			// Mint block 9
			onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare, dilbertSelfShare);
			Block block9 = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertEquals(3, block9.getBlockData().getOnlineAccountsCount());

			// Mint block 10
			Block block10 = BlockUtils.mintBlockWithReorgs(repository, 15);

			// Online accounts should be included from block 8
			assertEquals(3, block10.getBlockData().getOnlineAccountsCount());

			// Dilbert's balance should remain the same as he wasn't included in block 8
			AccountUtils.assertBalance(repository, "dilbert", Asset.NATIVE, initialBalances.get("dilbert").get(Asset.NATIVE));

			// Alice, Bob, and Chloe's balances should have increased, as they were all included in block 8 (and therefore block 10)
			AccountUtils.assertBalanceGreaterThan(repository, "alice", Asset.NATIVE, initialBalances.get("alice").get(Asset.NATIVE));
			AccountUtils.assertBalanceGreaterThan(repository, "bob", Asset.NATIVE, initialBalances.get("bob").get(Asset.NATIVE));
			AccountUtils.assertBalanceGreaterThan(repository, "chloe", Asset.NATIVE, initialBalances.get("chloe").get(Asset.NATIVE));

			assertEquals(repository.getBlockRepository().getBlockchainHeight(), 10);

			// It's a distribution block
			assertTrue(block10.isBatchRewardDistributionBlock());
		}
	}

	@Test
	public void testUnconfirmableRewardShares() throws DataException, IllegalAccessException {
		// Reward-share changes should be delayed whenever batch rewards are active and the
		// target block is in the online-account capture/distribution window.
		Common.useSettings("test-settings-v2-reward-scaling.json");

		// Set reward batching to every 100 blocks, starting at block 0, looking back the last 10 blocks for online accounts
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 100, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 10, true);

		try (final Repository repository = RepositoryManager.getRepository()) {

			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, "dilbert");

			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");
			PrivateKeyAccount bobSelfShare = Common.getTestAccount(repository, "bob-reward-share");
			PrivateKeyAccount chloeSelfShare = Common.getTestAccount(repository, "chloe-reward-share");

			// Create self shares for bob, chloe and dilbert
			AccountUtils.generateSelfShares(repository, List.of(bob, chloe, dilbert));

			// Mint blocks 1-89 - these should have no online accounts or rewards
			for (int i=1; i<89; i++) {
				Block block = BlockUtils.mintBlockWithReorgs(repository, 2);
				assertTrue(block.isBatchRewardDistributionActive());
				assertFalse(block.isRewardDistributionBlock());
				assertFalse(block.isBatchRewardDistributionBlock());
				assertFalse(block.isOnlineAccountsBlock());
				assertEquals(0, block.getBlockData().getOnlineAccountsCount());
			}

			// Mint blocks 90-98 - these should have online accounts but no rewards
			for (int i=90; i<=98; i++) {
				List<PrivateKeyAccount> onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare, chloeSelfShare);
				Block block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
				assertTrue(block.isBatchRewardDistributionActive());
				assertFalse(block.isRewardDistributionBlock());
				assertFalse(block.isBatchRewardDistributionBlock());
				assertTrue(block.isOnlineAccountsBlock());
				assertEquals(3, block.getBlockData().getOnlineAccountsCount());
			}

			// Cancel Chloe's reward share
			TransactionData transactionData = AccountUtils.createRewardShare(repository, chloe, chloe, -100, 10000000L);
			TransactionUtils.signAndImportValid(repository, transactionData, chloe);

			// Mint block 99 - Chloe's account should still be included as the reward share cancellation is delayed
			List<PrivateKeyAccount> onlineAccounts = Arrays.asList(aliceSelfShare, bobSelfShare, chloeSelfShare);
			Block block = BlockMinter.mintTestingBlock(repository, onlineAccounts.toArray(new PrivateKeyAccount[0]));
			assertTrue(block.isBatchRewardDistributionActive());
			assertFalse(block.isRewardDistributionBlock());
			assertFalse(block.isBatchRewardDistributionBlock());
			assertTrue(block.isOnlineAccountsBlock());
			assertEquals(3, block.getBlockData().getOnlineAccountsCount());

			// Mint block 100
			Block block100 = BlockUtils.mintBlockWithReorgs(repository, 12);

			// Online accounts should be included from block 99
			assertEquals(3, block100.getBlockData().getOnlineAccountsCount());

			assertEquals(repository.getBlockRepository().getBlockchainHeight(), 100);

			// It's a distribution block (which is technically also an online accounts block)
			assertTrue(block100.isBatchRewardDistributionBlock());
			assertTrue(block100.isRewardDistributionBlock());
			assertTrue(block100.isBatchRewardDistributionActive());
			assertTrue(block100.isOnlineAccountsBlock());
		}
	}

	@Test
	public void testUnconfirmableRewardShareBlocks() throws DataException, IllegalAccessException {
		// Reward-share changes should be unconfirmable only during the active batch
		// capture/distribution window, not in ordinary non-batched heights.
		Common.useSettings("test-settings-v2-reward-scaling.json");

		// Set reward batching to every 100 blocks, starting at block 0, looking back the last 10 blocks for online accounts
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 100, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 10, true);

		try (final Repository repository = RepositoryManager.getRepository()) {

			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, "dilbert");

			// Create self shares for bob, chloe and dilbert
			AccountUtils.generateSelfShares(repository, List.of(bob, chloe, dilbert));

			// Create transaction to cancel chloe's reward share
			TransactionData rewardShareTransactionData = AccountUtils.createRewardShare(repository, chloe, chloe, -100, 10000000L);
			Transaction rewardShareTransaction = Transaction.fromData(repository, rewardShareTransactionData);

			// Mint a block
			BlockUtils.mintBlock(repository);

			// Check block heights up to 89 - transaction should be confirmable
			for (int height=2; height<89; height++) {
				assertEquals(true, rewardShareTransaction.isConfirmableAtHeight(height));
			}

			// Check block heights 90-100 - transaction should not be confirmable
			for (int height=90; height<=100; height++) {
				assertEquals(false, rewardShareTransaction.isConfirmableAtHeight(height));
			}

			// Check block heights 101-189 - transaction should be confirmable again
			for (int height=101; height<189; height++) {
				assertEquals(true, rewardShareTransaction.isConfirmableAtHeight(height));
			}
		}
	}

}
