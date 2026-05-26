package org.qortium.test;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.data.PaymentData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.MultiPaymentTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.transaction.MultiPaymentTransaction;
import org.qortium.transaction.Transaction.ValidationResult;

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
		PaymentData paymentData = new PaymentData(recipient.getAddress(), Asset.NATIVE, 1L);
		MultiPaymentTransactionData transactionData = new MultiPaymentTransactionData(baseTransactionData, Collections.singletonList(paymentData));

		return new MultiPaymentTransaction(repository, transactionData);
	}
}
