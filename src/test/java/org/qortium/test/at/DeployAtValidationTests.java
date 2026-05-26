package org.qortium.test.at;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.DeployAtTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.Transaction;

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
					org.qortium.asset.Asset.NATIVE
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
					org.qortium.asset.Asset.NATIVE
			);

			DeployAtTransaction transaction = new DeployAtTransaction(repository, transactionData);
			transactionData.setFee(transaction.calcRecommendedFee());

			assertEquals("Malformed AT creation bytes should still be rejected", Transaction.ValidationResult.INVALID_CREATION_BYTES, transaction.isValidUnconfirmed());
		}
	}
}
