package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.transaction.PaymentTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transaction.Transaction.ValidationResult;

import java.lang.reflect.Method;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MempowFeePolicyTests extends Common {

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

			if (excludedTypes.contains(transactionType)) {
				assertFalse(transactionType.name(), canUseMempow);
			} else {
				assertTrue(transactionType.name(), canUseMempow);
			}
		}
	}

	@Test
	public void testDefaultFeePolicyStillRequiresFeeUntilNonceSupport() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			PaymentTransaction insufficientFeeTransaction = buildPaymentTransaction(repository, alice, bob.getAddress(), 0L);
			assertTrue(invokeCanUseMempowFeeAlternative(insufficientFeeTransaction));
			assertFalse(invokeHasPaidFee(insufficientFeeTransaction));
			assertEquals(ValidationResult.INSUFFICIENT_FEE, invokeIsFeeValid(insufficientFeeTransaction));

			PaymentTransaction paidFeeTransaction = buildPaymentTransaction(repository, alice, bob.getAddress(),
					insufficientFeeTransaction.calcRecommendedFee());
			assertTrue(invokeHasPaidFee(paidFeeTransaction));
			assertEquals(ValidationResult.OK, invokeIsFeeValid(paidFeeTransaction));
		}
	}

	private PaymentTransaction buildPaymentTransaction(Repository repository, PrivateKeyAccount sender, String recipient, long fee) {
		long timestamp = System.currentTimeMillis();
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, sender.getPublicKey(), fee, null);
		PaymentTransactionData paymentTransactionData = new PaymentTransactionData(baseTransactionData, recipient, 1L);
		return new PaymentTransaction(repository, paymentTransactionData);
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
}
