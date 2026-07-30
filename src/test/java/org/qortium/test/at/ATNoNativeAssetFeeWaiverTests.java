package org.qortium.test.at;

import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.at.ATStateData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.AssetUtils;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
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
