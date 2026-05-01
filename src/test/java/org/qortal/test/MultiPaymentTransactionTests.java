package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.data.PaymentData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.MultiPaymentTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.transaction.MultiPaymentTransaction;
import org.qortal.transaction.Transaction.ValidationResult;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class MultiPaymentTransactionTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testZeroFeeMultiPaymentUsesSharedPaymentValidation() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			MultiPaymentTransaction multiPaymentTransaction = buildMultiPaymentTransaction(repository, alice, bob, 0L);

			assertEquals(ValidationResult.OK, multiPaymentTransaction.isValid());
		}
	}

	private MultiPaymentTransaction buildMultiPaymentTransaction(Repository repository, PrivateKeyAccount sender, PrivateKeyAccount recipient, long fee) {
		long timestamp = System.currentTimeMillis();
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, sender.getPublicKey(), fee, null);
		PaymentData paymentData = new PaymentData(recipient.getAddress(), Asset.QORT, 1L);
		MultiPaymentTransactionData transactionData = new MultiPaymentTransactionData(baseTransactionData, Collections.singletonList(paymentData));

		return new MultiPaymentTransaction(repository, transactionData);
	}
}
