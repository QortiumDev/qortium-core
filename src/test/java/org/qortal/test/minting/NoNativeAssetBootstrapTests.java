package org.qortal.test.minting;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.data.account.RewardShareData;
import org.qortal.data.asset.AssetData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.IssueAssetTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestChainBootstrapUtils;
import org.qortal.test.common.TransactionUtils;
import org.qortal.utils.Amounts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NoNativeAssetBootstrapTests extends Common {

	private static final String NO_NATIVE_SETTINGS = "test-settings-v2-no-native-asset.json";
	private static final long INITIAL_NATIVE_QUANTITY = 1_000_000L * Amounts.MULTIPLIER;

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings(NO_NATIVE_SETTINGS);
	}

	@Test
	public void testMintingBeforeNativeAssetDoesNotCreateNativeBalances() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			bootstrapAliceMinter(repository);
			assertNativeAssetAbsent(repository);

			int startingHeight = repository.getBlockRepository().getBlockchainHeight();
			int startingBlocksMinted = AccountUtils.getBlocksMinted(repository, "alice");

			BlockUtils.mintBlock(repository);

			assertEquals(startingHeight + 1, repository.getBlockRepository().getBlockchainHeight());
			assertEquals(startingBlocksMinted + 1, AccountUtils.getBlocksMinted(repository, "alice"));
			assertNativeAssetAbsent(repository);

			BlockUtils.orphanLastBlock(repository);

			assertEquals(startingHeight, repository.getBlockRepository().getBlockchainHeight());
			assertEquals(startingBlocksMinted, AccountUtils.getBlocksMinted(repository, "alice"));
			assertNativeAssetAbsent(repository);
		}
	}

	@Test
	public void testFirstIssuedAssetBecomesNativeRewardAsset() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			bootstrapAliceMinter(repository);
			TestChainBootstrapUtils.ensureDevelopmentAdmin(repository, "alice");
			repository.saveChanges();

			BlockUtils.mintBlock(repository);
			assertNativeAssetAbsent(repository);

			issueInitialNativeAsset(repository);

			AssetData nativeAssetData = repository.getAssetRepository().fromAssetName("BOOTSTRAP");
			assertEquals(Asset.NATIVE, (long) nativeAssetData.getAssetId());
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, INITIAL_NATIVE_QUANTITY);

			long nextBlockReward = BlockUtils.getNextBlockReward(repository);
			BlockUtils.mintBlock(repository);

			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, INITIAL_NATIVE_QUANTITY + nextBlockReward);
		}
	}

	@Test
	public void testNativeAssetCanBootstrapWithZeroQuantity() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			bootstrapAliceMinter(repository);
			TestChainBootstrapUtils.ensureDevelopmentAdmin(repository, "alice");
			repository.saveChanges();

			BlockUtils.mintBlock(repository);
			assertNativeAssetAbsent(repository);

			issueInitialNativeAsset(repository, "ZERO_NATIVE", 0L);

			AssetData nativeAssetData = repository.getAssetRepository().fromAssetName("ZERO_NATIVE");
			assertEquals(Asset.NATIVE, (long) nativeAssetData.getAssetId());
			assertEquals(0L, nativeAssetData.getQuantity());
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, 0L);

			long nextBlockReward = BlockUtils.getNextBlockReward(repository);
			BlockUtils.mintBlock(repository);

			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, nextBlockReward);
		}
	}

	private static void issueInitialNativeAsset(Repository repository) throws DataException {
		issueInitialNativeAsset(repository, "BOOTSTRAP", INITIAL_NATIVE_QUANTITY);
	}

	private static void issueInitialNativeAsset(Repository repository, String assetName, long quantity) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		long timestamp = TransactionUtils.nextTimestamp(repository);
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, alice.getPublicKey(), 0L, null);
		IssueAssetTransactionData transactionData = new IssueAssetTransactionData(baseTransactionData,
				assetName, "Bootstrap native asset", quantity, true, "{}", false);

		TransactionUtils.signAndMint(repository, transactionData, alice);
	}

	private static void bootstrapAliceMinter(Repository repository) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		PrivateKeyAccount aliceRewardShare = Common.getTestAccount(repository, "alice-reward-share");

		alice.ensureAccount();
		TestChainBootstrapUtils.ensureMintingGroupMember(repository, "alice");

		RewardShareData rewardShareData = new RewardShareData(alice.getPublicKey(), alice.getAddress(),
				alice.getAddress(), aliceRewardShare.getPublicKey(), 100_00);
		repository.getAccountRepository().save(rewardShareData);

		repository.saveChanges();
	}

	private static void assertNativeAssetAbsent(Repository repository) throws DataException {
		assertFalse(repository.getAssetRepository().assetExists(Asset.NATIVE));
		assertTrue(repository.getAccountRepository().getAssetBalances(Asset.NATIVE, false).isEmpty());
	}

}
