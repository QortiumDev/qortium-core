package org.qortium.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.TransferPrivsTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.utils.Amounts;

import static org.junit.Assert.assertEquals;

public class TransferPrivsDisableWindowTests extends Common {

	private static final long DISABLED_WINDOW_TIMESTAMP = 1708000000000L;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testTransferPrivsStillValidInsideFormerDisableWindow() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount sender = Common.getTestAccount(repository, "alice");
			Account recipient = Common.generateRandomSeedAccount(repository);

			long fee = 1L * Amounts.MULTIPLIER;

			BaseTransactionData baseTransactionData = new BaseTransactionData(DISABLED_WINDOW_TIMESTAMP, Group.NO_GROUP, sender.getPublicKey(), fee, null);
			TransactionData transactionData = new TransferPrivsTransactionData(baseTransactionData, recipient.getAddress());
			Transaction transaction = Transaction.fromData(repository, transactionData);

			assertEquals("Transfer privs should remain valid inside the former disable window", ValidationResult.OK, transaction.isValid());
		}
	}
}
