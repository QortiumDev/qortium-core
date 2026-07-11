package org.qortium.test.at;

import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.DeployAtTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.utils.NTP;

import static org.junit.Assert.assertEquals;

public class DeployAtNoNativeAssetTests extends Common {

	private static final String PRE_TRIGGER_SETTINGS = "test-settings-v2-no-native-asset.json";
	private static final String POST_TRIGGER_SETTINGS = "test-settings-v2-no-native-asset-deployat.json";
	private static final long TEST_ASSET_ID = 1L;
	private static final long FUNDING_AMOUNT = 1_00000000L;

	@Test
	public void testNonNativeAtRequiresNativeAssetBeforeDeployAtWorkingAssetTrigger() throws DataException {
		useSettings(PRE_TRIGGER_SETTINGS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			DeployAtTransaction transaction = buildDeployAtTransaction(repository, 0L);

			assertEquals(Transaction.ValidationResult.ASSET_DOES_NOT_EXIST, transaction.isValidUnconfirmed());
		}
	}

	@Test
	public void testNonNativeAtDoesNotRequireNativeAssetAfterDeployAtWorkingAssetTrigger() throws DataException {
		useSettings(POST_TRIGGER_SETTINGS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			DeployAtTransaction transaction = buildDeployAtTransaction(repository, 0L);

			assertEquals(Transaction.ValidationResult.OK, transaction.isValidUnconfirmed());
		}
	}

	@Test
	public void testNonNativeAtStillRequiresNativeAssetForNativeFeeReserve() throws DataException {
		useSettings(POST_TRIGGER_SETTINGS);

		try (final Repository repository = RepositoryManager.getRepository()) {
			DeployAtTransaction transaction = buildDeployAtTransaction(repository, 1L);

			assertEquals(Transaction.ValidationResult.ASSET_DOES_NOT_EXIST, transaction.isValidUnconfirmed());
		}
	}

	private DeployAtTransaction buildDeployAtTransaction(Repository repository, long nativeFeeReserve) throws DataException {
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());

		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		DeployAtTransactionData transactionData = new DeployAtTransactionData(
				TestTransaction.generateBase(alice),
				"Test AT",
				"Test AT",
				"Test",
				"TEST",
				AtUtils.buildSimpleAT(),
				FUNDING_AMOUNT,
				TEST_ASSET_ID,
				nativeFeeReserve
		);

		return new DeployAtTransaction(repository, transactionData);
	}
}
