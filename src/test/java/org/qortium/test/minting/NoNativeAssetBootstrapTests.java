package org.qortium.test.minting;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.asset.AssetData;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.GroupApprovalTransactionData;
import org.qortium.data.transaction.IssueAssetTransactionData;
import org.qortium.data.transaction.JoinGroupTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.AccountUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ApprovalStatus;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.utils.Amounts;
import org.qortium.utils.NTP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NoNativeAssetBootstrapTests extends Common {

	private static final String NO_NATIVE_SETTINGS = "test-settings-v2-no-native-asset.json";
	private static final long INITIAL_NATIVE_QUANTITY = 1_000_000L * Amounts.MULTIPLIER;

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings(NO_NATIVE_SETTINGS);
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
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
	public void testNativeAssetBootstrapRequiresDevelopmentGroup() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			bootstrapAliceMinter(repository);
			assertNativeAssetAbsent(repository);

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			IssueAssetTransactionData transactionData = buildIssueAssetTransactionData(repository,
					"NO_GROUP_NATIVE", INITIAL_NATIVE_QUANTITY, Group.NO_GROUP);
			transactionData.setRequestedAssetId(Asset.NATIVE);

			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertEquals(ValidationResult.INVALID_TX_GROUP_ID, result);
			assertNativeAssetAbsent(repository);

			transactionData = buildIssueAssetTransactionData(repository,
					"MINTING_GROUP_NATIVE", INITIAL_NATIVE_QUANTITY, TestChainBootstrapUtils.MINTING_GROUP_ID);
			transactionData.setRequestedAssetId(Asset.NATIVE);

			result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertEquals(ValidationResult.INVALID_TX_GROUP_ID, result);
			assertNativeAssetAbsent(repository);
		}
	}

	@Test
	public void testGroupJoinCanUseMempowBeforeNativeAssetExists() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			bootstrapAliceMinter(repository);
			assertNativeAssetAbsent(repository);

			int previousDifficulty = setFeeAlternativeDifficulty(1);

			try {
				PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
				long timestamp = TransactionUtils.nextTimestamp(repository);
				BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP,
						bob.getPublicKey(), 0L, null);
				JoinGroupTransactionData transactionData = new JoinGroupTransactionData(baseTransactionData,
						TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, null);
				Transaction transaction = Transaction.fromData(repository, transactionData);
				transaction.computeMempowFeeNonce();

				TransactionUtils.signAndMint(repository, transactionData, bob);

				assertTrue(repository.getGroupRepository()
						.memberExists(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, bob.getAddress()));
				assertNativeAssetAbsent(repository);
			} finally {
				setFeeAlternativeDifficulty(previousDifficulty);
			}
		}
	}

	@Test
	public void testNormalAssetIssuanceBeforeNativeAssetStartsAtOne() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			bootstrapAliceMinter(repository);
			assertNativeAssetAbsent(repository);

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			IssueAssetTransactionData firstAssetTransactionData = buildIssueAssetTransactionData(repository,
					"FIRST_NORMAL", INITIAL_NATIVE_QUANTITY, Group.NO_GROUP);
			TransactionUtils.signAndMint(repository, firstAssetTransactionData, alice);

			AssetData firstAssetData = repository.getAssetRepository().fromAssetName("FIRST_NORMAL");
			assertEquals(1L, (long) firstAssetData.getAssetId());
			AccountUtils.assertBalance(repository, "alice", 1L, INITIAL_NATIVE_QUANTITY);
			assertNativeAssetAbsent(repository);

			IssueAssetTransactionData secondAssetTransactionData = buildIssueAssetTransactionData(repository,
					"SECOND_NORMAL", INITIAL_NATIVE_QUANTITY, Group.NO_GROUP);
			TransactionUtils.signAndMint(repository, secondAssetTransactionData, alice);

			AssetData secondAssetData = repository.getAssetRepository().fromAssetName("SECOND_NORMAL");
			assertEquals(2L, (long) secondAssetData.getAssetId());
			AccountUtils.assertBalance(repository, "alice", 2L, INITIAL_NATIVE_QUANTITY);
			assertNativeAssetAbsent(repository);
		}
	}

	@Test
	public void testNativeAssetBootstrapCreatesNativeRewardAsset() throws DataException {
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
	public void testNativeAssetBootstrapAfterNormalAssetStillUsesZero() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			bootstrapAliceMinter(repository);
			TestChainBootstrapUtils.ensureDevelopmentAdmin(repository, "alice");
			repository.saveChanges();

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			IssueAssetTransactionData normalAssetTransactionData = buildIssueAssetTransactionData(repository,
					"PRE_NATIVE_NORMAL", INITIAL_NATIVE_QUANTITY, Group.NO_GROUP);
			TransactionUtils.signAndMint(repository, normalAssetTransactionData, alice);

			AssetData normalAssetData = repository.getAssetRepository().fromAssetName("PRE_NATIVE_NORMAL");
			assertEquals(1L, (long) normalAssetData.getAssetId());
			assertNativeAssetAbsent(repository);

			issueInitialNativeAsset(repository);

			AssetData nativeAssetData = repository.getAssetRepository().fromAssetName("BOOTSTRAP");
			assertEquals(Asset.NATIVE, (long) nativeAssetData.getAssetId());
			AccountUtils.assertBalance(repository, "alice", Asset.NATIVE, INITIAL_NATIVE_QUANTITY);
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

	@Test
	public void testNativeAssetBootstrapCanOnlyHappenOnce() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			bootstrapAliceMinter(repository);
			TestChainBootstrapUtils.ensureDevelopmentAdmin(repository, "alice");
			repository.saveChanges();

			issueInitialNativeAsset(repository);

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			IssueAssetTransactionData secondNativeTransactionData = buildIssueAssetTransactionData(repository,
					"SECOND_NATIVE", INITIAL_NATIVE_QUANTITY, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID);
			secondNativeTransactionData.setRequestedAssetId(Asset.NATIVE);

			ValidationResult result = TransactionUtils.signAndImport(repository, secondNativeTransactionData, alice);
			assertEquals(ValidationResult.ASSET_ALREADY_EXISTS, result);
		}
	}

	@Test
	public void testOnlyNativeAssetIdCanBeRequested() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			bootstrapAliceMinter(repository);

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			IssueAssetTransactionData transactionData = buildIssueAssetTransactionData(repository,
					"REQUESTED_NON_NATIVE", INITIAL_NATIVE_QUANTITY, Group.NO_GROUP);
			transactionData.setRequestedAssetId(1L);

			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertEquals(ValidationResult.ASSET_DOES_NOT_EXIST, result);
			assertNativeAssetAbsent(repository);
		}
	}

	@Test
	public void testZeroQuantityNormalAssetIsRejectedBeforeNativeAssetExists() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			bootstrapAliceMinter(repository);

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			IssueAssetTransactionData transactionData = buildIssueAssetTransactionData(repository,
					"ZERO_NORMAL", 0L, Group.NO_GROUP);

			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertEquals(ValidationResult.INVALID_QUANTITY, result);
			assertNativeAssetAbsent(repository);
		}
	}

	private static void issueInitialNativeAsset(Repository repository) throws DataException {
		issueInitialNativeAsset(repository, "BOOTSTRAP", INITIAL_NATIVE_QUANTITY);
	}

	private static void issueInitialNativeAsset(Repository repository, String assetName, long quantity) throws DataException {
		IssueAssetTransactionData transactionData = submitNativeAssetBootstrapForApproval(repository, assetName, quantity);
		approveNativeAssetBootstrap(repository, transactionData);
	}

	private static IssueAssetTransactionData submitNativeAssetBootstrapForApproval(Repository repository,
			String assetName, long quantity) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		IssueAssetTransactionData transactionData = buildIssueAssetTransactionData(repository,
				assetName, quantity, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID);
		transactionData.setRequestedAssetId(Asset.NATIVE);

		TransactionUtils.signAndMint(repository, transactionData, alice);
		assertEquals(ApprovalStatus.PENDING, getApprovalStatus(repository, transactionData));
		assertNativeAssetAbsent(repository);

		return transactionData;
	}

	private static IssueAssetTransactionData buildIssueAssetTransactionData(Repository repository, String assetName,
			long quantity, int txGroupId) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		long timestamp = TransactionUtils.nextTimestamp(repository);
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, alice.getPublicKey(), 0L, null);
		return new IssueAssetTransactionData(baseTransactionData,
				assetName, "Bootstrap native asset", quantity, true, "{}", false);
	}

	private static void approveNativeAssetBootstrap(Repository repository, IssueAssetTransactionData pendingTransactionData) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		long timestamp = TransactionUtils.nextTimestamp(repository);
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, alice.getPublicKey(), 0L, null);
		GroupApprovalTransactionData approvalTransactionData = new GroupApprovalTransactionData(baseTransactionData,
				pendingTransactionData.getSignature(), true);

		TransactionUtils.signAndMint(repository, approvalTransactionData, alice);

		GroupData groupData = repository.getGroupRepository().fromGroupId(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID);
		BlockUtils.mintBlocks(repository, groupData.getMinimumBlockDelay());

		assertEquals(ApprovalStatus.APPROVED, getApprovalStatus(repository, pendingTransactionData));
	}

	private static ApprovalStatus getApprovalStatus(Repository repository, IssueAssetTransactionData transactionData) throws DataException {
		return repository.getTransactionRepository().fromSignature(transactionData.getSignature()).getApprovalStatus();
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

	private static int setFeeAlternativeDifficulty(int difficulty) throws IllegalAccessException {
		Object mempowSettings = FieldUtils.readField(BlockChain.getInstance(), "mempowSettings", true);
		Integer previousDifficulty = (Integer) FieldUtils.readField(mempowSettings, "feeAlternativeDifficulty", true);
		FieldUtils.writeField(mempowSettings, "feeAlternativeDifficulty", difficulty, true);
		return previousDifficulty;
	}

}
