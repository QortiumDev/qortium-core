package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.crypto.MemoryPoW;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.transaction.PaymentTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;

import java.lang.reflect.Method;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MempowFeePolicyTests extends Common {

	private static final int TEST_MEMPOW_BUFFER_SIZE = 8 * 1024;
	private static final int TEST_MEMPOW_DIFFICULTY = 4;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testMempowFeeAlternativeTypePolicy() throws Exception {
		EnumSet<TransactionType> excludedTypes = EnumSet.of(
				TransactionType.GENESIS,
				TransactionType.ARBITRARY,
				TransactionType.MESSAGE,
				TransactionType.CHAT,
				TransactionType.PUBLICIZE,
				TransactionType.AT,
				TransactionType.REWARD_SHARE,
				TransactionType.PRESENCE);

		for (TransactionType transactionType : TransactionType.values()) {
			boolean canUseMempow = invokeStaticCanUseMempowFeeAlternative(transactionType);
			assertEquals(transactionType.name(), transactionType.supportsMempowFeeAlternative(), canUseMempow);

			if (excludedTypes.contains(transactionType)) {
				assertFalse(transactionType.name(), canUseMempow);
			} else {
				assertTrue(transactionType.name(), canUseMempow);
			}
		}
	}

	@Test
	public void testNormalTransactionFeePolicyAcceptsPaidFeeWithoutNonce() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			PaymentTransaction paidFeeTransaction = buildPaymentTransaction(repository, alice, bob.getAddress(), 0L);
			paidFeeTransaction.getTransactionData().setFee(paidFeeTransaction.calcRecommendedFee());
			assertTrue(invokeHasPaidFee(paidFeeTransaction));
			assertEquals(ValidationResult.OK, invokeIsFeeValid(paidFeeTransaction));
		}
	}

	@Test
	public void testNormalTransactionFeePolicyRejectsMissingOrInvalidNonce() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			PaymentTransaction missingNonceTransaction = buildPaymentTransaction(repository, alice, bob.getAddress(), 0L);
			assertTrue(invokeCanUseMempowFeeAlternative(missingNonceTransaction));
			assertFalse(invokeHasPaidFee(missingNonceTransaction));
			assertEquals(ValidationResult.INSUFFICIENT_FEE, invokeIsFeeValid(missingNonceTransaction));

			PaymentTransaction invalidNonceTransaction = buildPaymentTransaction(repository, alice, bob.getAddress(), 0L);
			setInvalidMempowNonce(invalidNonceTransaction);
			assertEquals(ValidationResult.INSUFFICIENT_FEE, invokeIsFeeValid(invalidNonceTransaction));
		}
	}

	@Test
	public void testNormalTransactionFeePolicyAcceptsValidNonce() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			PaymentTransaction mempowTransaction = buildPaymentTransaction(repository, alice, bob.getAddress(), 0L);
			computeValidMempowNonce(mempowTransaction);

			assertFalse(invokeHasPaidFee(mempowTransaction));
			assertEquals(ValidationResult.OK, invokeIsFeeValid(mempowTransaction));
		}
	}

	@Test
	public void testNormalTransactionFeePolicyRejectsNegativeFeeEvenWithValidNonce() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			PaymentTransaction negativeFeeTransaction = buildPaymentTransaction(repository, alice, bob.getAddress(), -1L);
			computeValidMempowNonce(negativeFeeTransaction);

			assertEquals(ValidationResult.NEGATIVE_FEE, invokeIsFeeValid(negativeFeeTransaction));
		}
	}

	private PaymentTransaction buildPaymentTransaction(Repository repository, PrivateKeyAccount sender, String recipient, long fee) {
		long timestamp = System.currentTimeMillis();
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, sender.getPublicKey(), fee, null);
		PaymentTransactionData paymentTransactionData = new PaymentTransactionData(baseTransactionData, recipient, 1L);
		return new FastMempowPaymentTransaction(repository, paymentTransactionData);
	}

	private static void computeValidMempowNonce(Transaction transaction) throws TransformationException {
		TransactionData transactionData = transaction.getTransactionData();
		byte[] transactionBytes = TransactionTransformer.toBytesForSigning(transactionData);
		TransactionTransformer.clearMempowFeeNonce(transactionBytes);

		int nonce = 0;
		while (!MemoryPoW.verify2(transactionBytes, TEST_MEMPOW_BUFFER_SIZE, TEST_MEMPOW_DIFFICULTY, nonce))
			++nonce;

		transactionData.setNonce(nonce);
	}

	private static void setInvalidMempowNonce(Transaction transaction) throws TransformationException {
		TransactionData transactionData = transaction.getTransactionData();
		byte[] transactionBytes = TransactionTransformer.toBytesForSigning(transactionData);
		TransactionTransformer.clearMempowFeeNonce(transactionBytes);

		int nonce = 0;
		while (MemoryPoW.verify2(transactionBytes, TEST_MEMPOW_BUFFER_SIZE, TEST_MEMPOW_DIFFICULTY, nonce))
			++nonce;

		transactionData.setNonce(nonce);
	}

	private static boolean invokeStaticCanUseMempowFeeAlternative(TransactionType transactionType) throws Exception {
		Method method = Transaction.class.getDeclaredMethod("canUseMempowFeeAlternative", TransactionType.class);
		method.setAccessible(true);
		return (boolean) method.invoke(null, transactionType);
	}

	private static boolean invokeCanUseMempowFeeAlternative(Transaction transaction) throws Exception {
		Method method = Transaction.class.getDeclaredMethod("canUseMempowFeeAlternative");
		method.setAccessible(true);
		return (boolean) method.invoke(transaction);
	}

	private static boolean invokeHasPaidFee(Transaction transaction) throws Exception {
		Method method = Transaction.class.getDeclaredMethod("hasPaidFee");
		method.setAccessible(true);
		return (boolean) method.invoke(transaction);
	}

	private static ValidationResult invokeIsFeeValid(Transaction transaction) throws Exception {
		Method method = Transaction.class.getDeclaredMethod("isFeeValid");
		method.setAccessible(true);
		return (ValidationResult) method.invoke(transaction);
	}

	private static class FastMempowPaymentTransaction extends PaymentTransaction {
		private FastMempowPaymentTransaction(Repository repository, TransactionData transactionData) {
			super(repository, transactionData);
		}

		@Override
		protected int getMempowFeeAlternativeBufferSize() {
			return TEST_MEMPOW_BUFFER_SIZE;
		}

		@Override
		protected int getMempowFeeAlternativeDifficulty() {
			return TEST_MEMPOW_DIFFICULTY;
		}
	}
}
