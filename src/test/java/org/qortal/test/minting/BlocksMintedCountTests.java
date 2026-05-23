package org.qortal.test.minting;

import org.apache.logging.log4j.Level;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.Block;
import org.qortal.block.BlockChain;
import org.qortal.controller.BlockMinter;
import org.qortal.controller.OnlineAccountsManager;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.RewardShareData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.LogLevelOverride;
import org.qortal.test.common.TestAccount;

import java.util.List;

import static org.junit.Assert.*;

public class BlocksMintedCountTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testNonSelfShare() throws DataException {
		final int sharePercent = 12_80;

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Create reward-share
			byte[] testRewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "bob", sharePercent);
			PrivateKeyAccount testRewardShareAccount = new PrivateKeyAccount(repository, testRewardSharePrivateKey);

			// Confirm reward-share info set correctly
			RewardShareData testRewardShareData = repository.getAccountRepository().getRewardShare(testRewardShareAccount.getPublicKey());
			assertNotNull(testRewardShareData);
			assertFalse("Non-self reward-share should not be a minting key",
					Account.canRewardShareMint(repository, testRewardShareAccount.getPublicKey()));

			OnlineAccountsManager.getInstance().ensureTestingAccountsOnline(testRewardShareAccount);
			try (LogLevelOverride ignored = LogLevelOverride.setLevel(Block.class, Level.FATAL)) {
				assertNull("Non-self reward-share should not mint blocks",
						BlockMinter.mintTestingBlockRetainingTimestamps(repository, testRewardShareAccount));
			}
		}
	}

	@Test
	public void testSelfShare() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount testRewardShareAccount = Common.getTestAccount(repository, "alice-reward-share");

			// Confirm reward-share exists
			RewardShareData testRewardShareData = repository.getAccountRepository().getRewardShare(testRewardShareAccount.getPublicKey());
			assertNotNull(testRewardShareData);

			testRewardShare(repository, testRewardShareAccount, +1, 0);
		}
	}

	@Test
	public void testMixedShares() throws DataException {
		final int sharePercent = 12_80;

		try (final Repository repository = RepositoryManager.getRepository()) {
			// Fetch usual minting account
			PrivateKeyAccount mintingAccount = Common.getTestAccount(repository, "alice-reward-share");

			// Create reward-share
			byte[] testRewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "bob", sharePercent);
			PrivateKeyAccount testRewardShareAccount = new PrivateKeyAccount(repository, testRewardSharePrivateKey);

			// Confirm reward-share info set correctly
			RewardShareData testRewardShareData = repository.getAccountRepository().getRewardShare(testRewardShareAccount.getPublicKey());
			assertNotNull(testRewardShareData);

			// Create signed timestamps
			OnlineAccountsManager.getInstance().ensureTestingAccountsOnline(mintingAccount, testRewardShareAccount);

			// The non-self reward-share is only a payout record, so Alice's self-share remains the minting key.
			// Bob only receives reward-share payouts, so his blocksMinted count should not increase.
			testRewardShareRetainingTimestamps(repository, mintingAccount, +1, 0);
		}
	}

	@Test
	public void testLevelSetting() {

		boolean exceptionThrown = false;

		try (final Repository repository = RepositoryManager.getRepository()) {

			// get the Alice's reward share account
			PrivateKeyAccount aliceMintingAccount = Common.getTestAccount(repository, "alice-reward-share");

			// Seed Alice at level 1 so orphan/remint checks do not drop below the current block-minting guard.
			AccountUtils.setMintingData(repository, "alice", 1);

			// Confirm reward-share exists
			RewardShareData aliceRewardShareData = repository.getAccountRepository().getRewardShare(aliceMintingAccount.getPublicKey());
			assertNotNull(aliceRewardShareData);

			// mint 40 blocks
			for( int i = 0; i < 40; i++ ) {
				// Create signed timestamps
				OnlineAccountsManager.getInstance().ensureTestingAccountsOnline(aliceMintingAccount);

				// Mint another block
				BlockMinter.mintTestingBlockRetainingTimestamps(repository, aliceMintingAccount);

				// assert Alice's minting data after another block minted
				assertMintingData(repository, "alice");

				// orphan the block
				BlockUtils.orphanLastBlock(repository);

				// assert the orphaning
				assertMintingData(repository, "alice");

				// mint another block to reverse the orphaning
				BlockMinter.mintTestingBlockRetainingTimestamps(repository, aliceMintingAccount);
			}
		}
		catch (DataException e) {
			exceptionThrown = true;
		}

		assertFalse(exceptionThrown);
	}

	/**
	 * Assert Minting Data
	 *
	 * @param repository the data repository
	 * @param name the name of the minting account
	 * @throws DataException
	 */
	private static void assertMintingData(Repository repository, String name) throws DataException {

		// get the test account data
		TestAccount testAccount = Common.getTestAccount(repository, name);
		AccountData testAccountData = repository.getAccountRepository().getAccount(testAccount.getAddress());

		List<Integer> blocksNeededByLevel = BlockChain.getInstance().getBlocksNeededByLevel();

		int height = repository.getBlockRepository().getBlockchainHeight();
		int blocksLeft = testAccountData.getBlocksMinted();

		int index = 0;
		int expectedLevel = 0;

		// update expected level based on the blocks needed by level list entries
		while( blocksNeededByLevel.size() > index ) {

			Integer blocksNeededByThisLevel = blocksNeededByLevel.get(index);
			if( blocksNeededByThisLevel <= blocksLeft ) {
				expectedLevel++;
				blocksLeft -= blocksNeededByThisLevel;
			}
			else {
				break;
			}

			index++;
		}

		// print and assert the expected and derived numbers
		System.out.println(String.format("height = %s,expectedLevel = %s, blocksMinted = %s", height, expectedLevel, testAccountData.getBlocksMinted()) );
		assertEquals( expectedLevel, testAccountData.getLevel() );
	}

	private void testRewardShare(Repository repository, PrivateKeyAccount testRewardShareAccount, int aliceDelta, int bobDelta) throws DataException {
		// Create signed timestamps
		OnlineAccountsManager.getInstance().ensureTestingAccountsOnline(testRewardShareAccount);

		testRewardShareRetainingTimestamps(repository, testRewardShareAccount, aliceDelta, bobDelta);
	}

	private void testRewardShareRetainingTimestamps(Repository repository, PrivateKeyAccount mintingAccount, int aliceDelta, int bobDelta) throws DataException {
		// Fetch pre-mint blocks minted counts
		int alicePreMintCount = getBlocksMinted(repository, "alice");
		int bobPreMintCount = getBlocksMinted(repository, "bob");

		// Mint another block
		BlockMinter.mintTestingBlockRetainingTimestamps(repository, mintingAccount);

		// Fetch post-mint blocks minted counts
		int alicePostMintCount = getBlocksMinted(repository, "alice");
		int bobPostMintCount = getBlocksMinted(repository, "bob");

		// Check both accounts
		assertEquals("Alice's post-mint blocks-minted count incorrect", alicePreMintCount + aliceDelta, alicePostMintCount);
		assertEquals("Bob's post-mint blocks-minted count incorrect", bobPreMintCount + bobDelta, bobPostMintCount);

		// Orphan latest block
		BlockUtils.orphanLastBlock(repository);

		// Fetch post-orphan blocks minted counts
		int alicePostOrphanCount = getBlocksMinted(repository, "alice");
		int bobPostOrphanCount = getBlocksMinted(repository, "bob");

		// Check blocks minted counts reverted correctly
		assertEquals("Alice's post-orphan blocks-minted count incorrect", alicePreMintCount, alicePostOrphanCount);
		assertEquals("Bob's post-orphan blocks-minted count incorrect", bobPreMintCount, bobPostOrphanCount);
	}

	private int getBlocksMinted(Repository repository, String name) throws DataException {
		TestAccount testAccount = Common.getTestAccount(repository, name);
		return repository.getAccountRepository().getAccount(testAccount.getAddress()).getBlocksMinted();
	}
}
