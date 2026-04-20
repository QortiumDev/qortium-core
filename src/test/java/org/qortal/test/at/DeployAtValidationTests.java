package org.qortal.test.at;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AtUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.DeployAtTransaction;
import org.qortal.transaction.Transaction;

import static org.junit.Assert.assertEquals;

public class DeployAtValidationTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testValidSimpleAtDeploymentAllowed() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long fundingAmount = 1_00000000L;
			byte[] creationBytes = AtUtils.buildSimpleAT();

			DeployAtTransactionData transactionData = new DeployAtTransactionData(
					TestTransaction.generateBase(deployer),
					"Test AT",
					"Test AT",
					"Test",
					"TEST",
					creationBytes,
					fundingAmount,
					org.qortal.asset.Asset.QORT
			);

			DeployAtTransaction transaction = new DeployAtTransaction(repository, transactionData);
			transactionData.setFee(transaction.calcRecommendedFee());

			Transaction.ValidationResult validationResult = TransactionUtils.signAndImport(repository, transactionData, deployer);
			assertEquals("A structurally valid AT should deploy without an allowlist", Transaction.ValidationResult.OK, validationResult);
		}
	}

	@Test
	public void testMalformedCreationBytesStillRejected() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long fundingAmount = 1_00000000L;
			byte[] creationBytes = new byte[] {0, 1};

			DeployAtTransactionData transactionData = new DeployAtTransactionData(
					TestTransaction.generateBase(deployer),
					"Bad AT",
					"Bad AT",
					"Test",
					"TEST",
					creationBytes,
					fundingAmount,
					org.qortal.asset.Asset.QORT
			);

			DeployAtTransaction transaction = new DeployAtTransaction(repository, transactionData);
			transactionData.setFee(transaction.calcRecommendedFee());

			assertEquals("Malformed AT creation bytes should still be rejected", Transaction.ValidationResult.INVALID_CREATION_BYTES, transaction.isValidUnconfirmed());
		}
	}
}
