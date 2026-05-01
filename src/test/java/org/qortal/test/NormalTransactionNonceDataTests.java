package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.transaction.PaymentTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NormalTransactionNonceDataTests extends Common {

	private static final int PAYMENT_NONCE = 1234567;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testPaymentSerializationPreservesNonce() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PaymentTransactionData paymentTransactionData = buildPaymentTransactionData(repository, alice, PAYMENT_NONCE);
			Transaction transaction = new PaymentTransaction(repository, paymentTransactionData);
			transaction.sign(alice);

			byte[] transactionBytes = TransactionTransformer.toBytes(paymentTransactionData);
			assertEquals(TransactionTransformer.getDataLength(paymentTransactionData), transactionBytes.length);

			PaymentTransactionData deserializedTransactionData = (PaymentTransactionData) TransactionTransformer.fromBytes(transactionBytes);
			assertEquals(PAYMENT_NONCE, deserializedTransactionData.getNonce());
			assertArrayEquals(paymentTransactionData.getSignature(), deserializedTransactionData.getSignature());

			byte[] reserializedTransactionBytes = TransactionTransformer.toBytes(deserializedTransactionData);
			assertArrayEquals(transactionBytes, reserializedTransactionBytes);
		}
	}

	@Test
	public void testPaymentNonceIsPartOfSignedBytes() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PaymentTransactionData paymentTransactionData = buildPaymentTransactionData(repository, alice, PAYMENT_NONCE);
			Transaction transaction = new PaymentTransaction(repository, paymentTransactionData);
			transaction.sign(alice);

			assertTrue(transaction.isSignatureValid());

			paymentTransactionData.setNonce(PAYMENT_NONCE + 1);
			assertFalse(transaction.isSignatureValid());
		}
	}

	@Test
	public void testPaymentRepositoryPreservesNonce() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PaymentTransactionData paymentTransactionData = buildPaymentTransactionData(repository, alice, PAYMENT_NONCE);

			TransactionUtils.signAndImportValid(repository, paymentTransactionData, alice);

			TransactionData fetchedTransactionData = repository.getTransactionRepository().fromSignature(paymentTransactionData.getSignature());
			assertTrue(fetchedTransactionData instanceof PaymentTransactionData);
			assertEquals(PAYMENT_NONCE, fetchedTransactionData.getNonce());
		}
	}

	private PaymentTransactionData buildPaymentTransactionData(Repository repository, PrivateKeyAccount sender, int nonce) throws DataException {
		PrivateKeyAccount recipient = Common.getTestAccount(repository, "bob");
		long timestamp = System.currentTimeMillis();
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, sender.getPublicKey(), 0L, nonce, null);
		PaymentTransactionData paymentTransactionData = new PaymentTransactionData(baseTransactionData, recipient.getAddress(), 1L);
		PaymentTransaction paymentTransaction = new PaymentTransaction(repository, paymentTransactionData);
		paymentTransactionData.setFee(paymentTransaction.calcRecommendedFee());

		return paymentTransactionData;
	}
}
