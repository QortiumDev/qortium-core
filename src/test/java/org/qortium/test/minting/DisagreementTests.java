package org.qortium.test.minting;

import io.druid.extendedset.intset.ConciseSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.controller.BlockMinter;
import org.qortium.controller.OnlineAccountsManager;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.block.BlockData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transform.block.BlockTransformer;
import org.roaringbitmap.IntIterator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class DisagreementTests extends Common {

	private static final int CANCEL_SHARE_PERCENT = -1;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	/**
	 * Testing minting a block when there is a signed online account timestamp present
	 * for a non-self payout reward-share.
	 * <p>
	 * Something like:
	 * <ul>
	 * <li>Mint block, with tx to create reward-share R</li>
	 * <li>Sign current timestamp with R</li>
	 * <li>Mint block and confirm R is not included as an online account</li>
	 * <li>Mint block, with tx to cancel reward-share R</li>
	 * <li>Mint another block: R's timestamp should still be excluded</li>
	 * </ul>
	 * 
	 * @throws DataException
	 */
	@Test
	public void testOnlineAccounts() throws DataException {
		final int sharePercent = 12_80;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount mintingAccount = Common.getTestAccount(repository, "alice-reward-share");
			TestAccount signingAccount = Common.getTestAccount(repository, "alice");

			// Create reward-share
			byte[] testRewardSharePrivateKey = AccountUtils.rewardShare(repository, "alice", "bob", sharePercent);
			PrivateKeyAccount testRewardShareAccount = new PrivateKeyAccount(repository, testRewardSharePrivateKey);

			// Confirm reward-share info set correctly
			RewardShareData testRewardShareData = repository.getAccountRepository().getRewardShare(testRewardShareAccount.getPublicKey());
			assertNotNull(testRewardShareData);

			// Create signed timestamps
			OnlineAccountsManager.getInstance().ensureTestingAccountsOnline(mintingAccount, testRewardShareAccount);

			// Mint another block
			BlockMinter.mintTestingBlockRetainingTimestamps(repository, mintingAccount);

			// Confirm non-self reward-share's signed timestamp is not included
			BlockData blockData = repository.getBlockRepository().getLastBlock();
			List<RewardShareData> rewardSharesData = fetchRewardSharesForBlock(repository, blockData);
			boolean doesContainRewardShare = rewardSharesData.stream().anyMatch(rewardShareData -> Arrays.equals(rewardShareData.getRewardSharePublicKey(), testRewardShareData.getRewardSharePublicKey()));
			assertFalse(doesContainRewardShare);

			// Cancel reward-share
			TransactionData cancelRewardShareTransactionData = AccountUtils.createRewardShare(repository, "alice", "bob", CANCEL_SHARE_PERCENT);
			TransactionUtils.signAndImportValid(repository, cancelRewardShareTransactionData, signingAccount);
			BlockMinter.mintTestingBlockRetainingTimestamps(repository, mintingAccount);

			// Confirm reward-share no longer exists in repository
			RewardShareData cancelledRewardShareData = repository.getAccountRepository().getRewardShare(testRewardShareAccount.getPublicKey());
			assertNull("Reward-share shouldn't exist", cancelledRewardShareData);

			// Attempt to mint with cancelled reward-share
			BlockMinter.mintTestingBlockRetainingTimestamps(repository, mintingAccount);

			// Confirm reward-share's signed timestamp is NOT included
			blockData = repository.getBlockRepository().getLastBlock();
			rewardSharesData = fetchRewardSharesForBlock(repository, blockData);
			doesContainRewardShare = rewardSharesData.stream().anyMatch(rewardShareData -> Arrays.equals(rewardShareData.getRewardSharePublicKey(), testRewardShareData.getRewardSharePublicKey()));
			assertFalse(doesContainRewardShare);
		}
	}

	private List<RewardShareData> fetchRewardSharesForBlock(Repository repository, BlockData blockData) throws DataException {
		byte[] encodedOnlineAccounts = blockData.getEncodedOnlineAccounts();
		ConciseSet accountIndexes = BlockTransformer.decodeOnlineAccounts(encodedOnlineAccounts);

		List<RewardShareData> rewardSharesData = new ArrayList<>();

		IntIterator iterator = accountIndexes.iterator();
		while (iterator.hasNext()) {
			int accountIndex = iterator.next();

			RewardShareData rewardShareData = repository.getAccountRepository().getSelfShareByIndex(accountIndex);
			rewardSharesData.add(rewardShareData);
		}

		return rewardSharesData;
	}

}
