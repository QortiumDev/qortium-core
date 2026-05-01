package org.qortal.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.TransferPrivsTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountUtils;
import org.qortal.test.common.Common;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.utils.Amounts;

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
			Account recipient = AccountUtils.createRandomAccount(repository);

			long fee = 1L * Amounts.MULTIPLIER;

			BaseTransactionData baseTransactionData = new BaseTransactionData(DISABLED_WINDOW_TIMESTAMP, Group.NO_GROUP, null,
					sender.getPublicKey(), fee, null);
			TransactionData transactionData = new TransferPrivsTransactionData(baseTransactionData, recipient.getAddress());
			Transaction transaction = Transaction.fromData(repository, transactionData);

			assertEquals("Transfer privs should remain valid inside the former disable window", ValidationResult.OK, transaction.isValid());
		}
	}
}
