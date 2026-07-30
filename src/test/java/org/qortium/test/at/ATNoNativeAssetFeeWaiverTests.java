package org.qortium.test.at;

import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.at.ATStateData;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.GroupApprovalTransactionData;
import org.qortium.data.transaction.IssueAssetTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.TransferAssetTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.AssetUtils;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.utils.Amounts;
import org.qortium.utils.NTP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Runtime half of no-native-asset AT support: from {@code atNoNativeAssetFeeWaiverHeight}, a chain
 * whose native asset does not exist charges no AT step fees, so a non-native-working AT can execute
 * despite holding no native balance. Below the trigger — and on any chain whose native asset exists —
 * behaviour must be unchanged.
 */
public class ATNoNativeAssetFeeWaiverTests extends Common {

	private static final String WAIVER_SETTINGS = "test-settings-v2-no-native-asset-at-fee-waiver.json";
	private static final String NO_WAIVER_SETTINGS = "test-settings-v2-no-native-asset-deployat.json";
	private static final long TEST_ASSET_ID = 1L;
	private static final long FUNDING_AMOUNT = 1_00000000L;
	private static final long NATIVE_FEE_RESERVE = 2L * Amounts.MULTIPLIER;

	@Test
	public void testAtSkippedBelowWaiverTriggerThenExecutesFeeFreeFromTrigger() throws DataException {
		Common.useSettings(WAIVER_SETTINGS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			bootstrapAliceMinter(repository);

			DeployAtTransaction deployAtTransaction = deployNonNativeAt(repository);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			int deployHeight = repository.getBlockRepository().getBlockchainHeight();
			long waiverHeight = BlockChain.getInstance().getAtNoNativeAssetFeeWaiverHeight();
			assertTrue("test chain must leave room below the waiver trigger", deployHeight + 1 < waiverHeight);

			// Below the trigger the AT cannot fund step fees, so every round is skipped
			while (repository.getBlockRepository().getBlockchainHeight() + 1 < waiverHeight) {
				BlockUtils.mintBlock(repository);

				ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);
				assertEquals("AT must not execute below the waiver trigger", deployHeight, (int) atStateData.getHeight());
			}

			// The block at the trigger runs the AT's first round, charging no step fees
			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);
			assertEquals("AT must execute in the trigger block", waiverHeight, (long) atStateData.getHeight());
			assertEquals("waived round must charge zero fees", 0L, (long) atStateData.getFees());
			assertEquals("AT must still hold no native balance", 0L, atAccount.getConfirmedBalance(Asset.NATIVE));
			assertEquals("working-asset funding must be untouched by fees", FUNDING_AMOUNT, atAccount.getConfirmedBalance(TEST_ASSET_ID));
			assertFalse("native asset must still not exist", repository.getAssetRepository().assetExists(Asset.NATIVE));
		}
	}

	@Test
	public void testAtStaysSkippedWithoutWaiverTrigger() throws DataException {
		Common.useSettings(NO_WAIVER_SETTINGS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			bootstrapAliceMinter(repository);

			DeployAtTransaction deployAtTransaction = deployNonNativeAt(repository);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			int deployHeight = repository.getBlockRepository().getBlockchainHeight();

			for (int i = 0; i < 5; ++i) {
				BlockUtils.mintBlock(repository);

				ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);
				assertEquals("AT must never execute without the waiver trigger", deployHeight, (int) atStateData.getHeight());
			}
		}
	}

	@Test
	public void testFeesResumeOnceNativeAssetIsBootstrapped() throws DataException {
		Common.useSettings(WAIVER_SETTINGS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			bootstrapAliceMinter(repository);
			TestChainBootstrapUtils.ensureDevelopmentAdmin(repository, "alice");
			repository.saveChanges();

			DeployAtTransaction deployAtTransaction = deployNonNativeAt(repository);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			// Reach the waiver trigger; the AT takes its first, fee-free round there
			long waiverHeight = BlockChain.getInstance().getAtNoNativeAssetFeeWaiverHeight();
			while (repository.getBlockRepository().getBlockchainHeight() < waiverHeight)
				BlockUtils.mintBlock(repository);

			ATStateData waivedStateData = repository.getATRepository().getLatestATState(atAddress);
			assertEquals(waiverHeight, (long) waivedStateData.getHeight());
			assertEquals(0L, (long) waivedStateData.getFees());

			// Development-group-approved bootstrap creates the native asset mid-chain, ending the waiver
			issueBootstrapNativeAsset(repository);
			assertTrue(repository.getAssetRepository().assetExists(Asset.NATIVE));
			int bootstrapHeight = repository.getBlockRepository().getBlockchainHeight();

			// Every round the AT took while the waiver was live must have been free
			ATStateData preFundingStateData = repository.getATRepository().getLatestATState(atAddress);
			assertEquals("waived rounds must all be free", 0L, (long) preFundingStateData.getFees());

			// Fees now apply again, and the AT holds no native balance — so even an incoming
			// working-asset transfer must not buy it another round
			transferToAt(repository, atAddress, TEST_ASSET_ID, 1L * Amounts.MULTIPLIER);
			BlockUtils.mintBlock(repository);
			BlockUtils.mintBlock(repository);

			ATStateData starvedStateData = repository.getATRepository().getLatestATState(atAddress);
			assertTrue("AT must not execute past the bootstrap without native funding",
					starvedStateData.getHeight() <= bootstrapHeight);
			assertEquals(preFundingStateData.getHeight(), starvedStateData.getHeight());
			assertEquals(0L, atAccount.getConfirmedBalance(Asset.NATIVE));

			// Native funding restores execution — and the round must charge real fees again.
			// The funding block itself runs ATs against the parent state (still unfunded), so the
			// AT's single fee-charged round happens in the one block minted after it.
			long nativeFunding = 2L * Amounts.MULTIPLIER;
			transferToAt(repository, atAddress, Asset.NATIVE, nativeFunding);
			BlockUtils.mintBlock(repository);

			ATStateData fundedStateData = repository.getATRepository().getLatestATState(atAddress);
			assertTrue("AT must execute again once native-funded", fundedStateData.getHeight() > bootstrapHeight);
			assertTrue("post-bootstrap rounds must charge step fees", fundedStateData.getFees() > 0);
			assertEquals(nativeFunding - fundedStateData.getFees(), atAccount.getConfirmedBalance(Asset.NATIVE));
		}
	}

	@Test
	public void testWaiverDoesNotApplyWhenNativeAssetExists() throws DataException {
		// Default test chain: native asset in genesis AND atNoNativeAssetFeeWaiverHeight active from 0
		Common.useDefaultSettings();

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long assetId = AssetUtils.issueAsset(repository, "alice", "AT-FEE-GUARD", 100L * Amounts.MULTIPLIER, true);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, AtUtils.buildSimpleAT(),
					FUNDING_AMOUNT, assetId, NATIVE_FEE_RESERVE);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);
			assertTrue("step fees must still be charged while the native asset exists", atStateData.getFees() > 0);
			assertEquals(NATIVE_FEE_RESERVE - atStateData.getFees(), atAccount.getConfirmedBalance(Asset.NATIVE));
		}
	}

	private static DeployAtTransaction deployNonNativeAt(Repository repository) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		return AtUtils.doDeployAT(repository, alice, AtUtils.buildSimpleAT(), FUNDING_AMOUNT, TEST_ASSET_ID, 0L);
	}

	/** Development-group-gated runtime bootstrap of the native asset (submit, approve, wait out the group block delay). */
	private static void issueBootstrapNativeAsset(Repository repository) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		long timestamp = TransactionUtils.nextTimestamp(repository);
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID,
				alice.getPublicKey(), 0L, null);
		IssueAssetTransactionData issueTransactionData = new IssueAssetTransactionData(baseTransactionData,
				"BOOTSTRAP", "Bootstrap native asset", 1_000_000L * Amounts.MULTIPLIER, true, "{}", false);
		issueTransactionData.setRequestedAssetId(Asset.NATIVE);
		TransactionUtils.signAndMint(repository, issueTransactionData, alice);

		timestamp = TransactionUtils.nextTimestamp(repository);
		baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, alice.getPublicKey(), 0L, null);
		GroupApprovalTransactionData approvalTransactionData = new GroupApprovalTransactionData(baseTransactionData,
				issueTransactionData.getSignature(), true);
		TransactionUtils.signAndMint(repository, approvalTransactionData, alice);

		GroupData groupData = repository.getGroupRepository().fromGroupId(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID);
		BlockUtils.mintBlocks(repository, groupData.getMinimumBlockDelay());
	}

	private static void transferToAt(Repository repository, String atAddress, long assetId, long amount) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

		long timestamp = TransactionUtils.nextTimestamp(repository);
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, alice.getPublicKey(), 0L, null);
		TransactionData transactionData = new TransferAssetTransactionData(baseTransactionData, atAddress, amount, assetId);

		TransactionUtils.signAndMint(repository, transactionData, alice);
	}

	private static void bootstrapAliceMinter(Repository repository) throws DataException {
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());

		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		PrivateKeyAccount aliceRewardShare = Common.getTestAccount(repository, "alice-reward-share");

		alice.ensureAccount();
		TestChainBootstrapUtils.ensureMintingGroupMember(repository, "alice");

		RewardShareData rewardShareData = new RewardShareData(alice.getPublicKey(), alice.getAddress(),
				alice.getAddress(), aliceRewardShare.getPublicKey(), 100_00);
		repository.getAccountRepository().save(rewardShareData);

		repository.saveChanges();
	}
}
