package org.qortium.test;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.crypto.MemoryPoW;
import org.qortium.data.PaymentData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.GroupApprovalTransactionData;
import org.qortium.data.transaction.IssueAssetTransactionData;
import org.qortium.data.transaction.JoinGroupTransactionData;
import org.qortium.data.transaction.MultiPaymentTransactionData;
import org.qortium.data.transaction.PaymentTransactionData;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.TransferAssetTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.transaction.PaymentTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

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

	@Test
	public void testNormalTransactionFeePolicyRepresentativeTypes() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			for (RepresentativeTransactionCase transactionCase : buildRepresentativeTransactionCases()) {
				assertRepresentativeTypeFeePolicy(repository, alice, bob, transactionCase);
			}
		}
	}

	@Test
	public void testZeroFeeMempowTransactionDoesNotMoveFeeBalance() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			PaymentTransaction mempowTransaction = buildPaymentTransaction(repository, alice, bob.getAddress(), 0L);
			computeValidMempowNonce(mempowTransaction);
			assertEquals(ValidationResult.OK, invokeIsFeeValid(mempowTransaction));

			long startingBalance = alice.getConfirmedBalance(Asset.NATIVE);

			mempowTransaction.processReferencesAndFees();
			assertEquals(startingBalance, alice.getConfirmedBalance(Asset.NATIVE));

			mempowTransaction.orphanReferencesAndFees();
			assertEquals(startingBalance, alice.getConfirmedBalance(Asset.NATIVE));

			repository.discardChanges();
		}
	}

	@Test
	public void testLowFeeMempowTransactionMovesDeclaredFeeBalance() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			long declaredFee = 1L;

			PaymentTransaction mempowTransaction = buildPaymentTransaction(repository, alice, bob.getAddress(), declaredFee);
			computeValidMempowNonce(mempowTransaction);
			assertEquals(ValidationResult.OK, invokeIsFeeValid(mempowTransaction));

			long startingBalance = alice.getConfirmedBalance(Asset.NATIVE);

			mempowTransaction.processReferencesAndFees();
			assertEquals(startingBalance - declaredFee, alice.getConfirmedBalance(Asset.NATIVE));

			mempowTransaction.orphanReferencesAndFees();
			assertEquals(startingBalance, alice.getConfirmedBalance(Asset.NATIVE));

			repository.discardChanges();
		}
	}

	private PaymentTransaction buildPaymentTransaction(Repository repository, PrivateKeyAccount sender, String recipient, long fee) {
		BaseTransactionData baseTransactionData = buildBaseTransactionData(sender, fee);
		PaymentTransactionData paymentTransactionData = new PaymentTransactionData(baseTransactionData, recipient, 1L);
		return new FastMempowPaymentTransaction(repository, paymentTransactionData);
	}

	private static BaseTransactionData buildBaseTransactionData(PrivateKeyAccount sender, long fee) {
		long timestamp = System.currentTimeMillis();
		return new BaseTransactionData(timestamp, Group.NO_GROUP, sender.getPublicKey(), fee, null);
	}

	private List<RepresentativeTransactionCase> buildRepresentativeTransactionCases() {
		return Arrays.asList(
				new RepresentativeTransactionCase("PAYMENT", (sender, recipient, fee) ->
						new PaymentTransactionData(buildBaseTransactionData(sender, fee), recipient.getAddress(), 1L)),
				new RepresentativeTransactionCase("MULTI_PAYMENT", (sender, recipient, fee) ->
						new MultiPaymentTransactionData(buildBaseTransactionData(sender, fee),
								Collections.singletonList(new PaymentData(recipient.getAddress(), Asset.NATIVE, 1L)))),
				new RepresentativeTransactionCase("TRANSFER_ASSET", (sender, recipient, fee) ->
						new TransferAssetTransactionData(buildBaseTransactionData(sender, fee), recipient.getAddress(), 1L, Asset.NATIVE)),
				new RepresentativeTransactionCase("REGISTER_NAME", (sender, recipient, fee) ->
						new RegisterNameTransactionData(buildBaseTransactionData(sender, fee), "mempow-test-name", "mempow test data")),
				new RepresentativeTransactionCase("ISSUE_ASSET", (sender, recipient, fee) ->
						new IssueAssetTransactionData(buildBaseTransactionData(sender, fee), "MEMPOW_TEST", "mempow test asset", 1L, true, "{}", false)),
				new RepresentativeTransactionCase("GROUP_APPROVAL", (sender, recipient, fee) ->
						new GroupApprovalTransactionData(buildBaseTransactionData(sender, fee), new byte[64], true)),
				new RepresentativeTransactionCase("JOIN_GROUP", (sender, recipient, fee) ->
						new JoinGroupTransactionData(buildBaseTransactionData(sender, fee), 2)));
	}

	private void assertRepresentativeTypeFeePolicy(Repository repository, PrivateKeyAccount sender, PrivateKeyAccount recipient,
			RepresentativeTransactionCase transactionCase) throws Exception {
		Transaction paidFeeTransaction = transactionCase.build(repository, sender, recipient, 0L);
		paidFeeTransaction.getTransactionData().setFee(paidFeeTransaction.calcRecommendedFee());
		assertTrue(transactionCase.name, invokeHasPaidFee(paidFeeTransaction));
		assertEquals(transactionCase.name, ValidationResult.OK, invokeIsFeeValid(paidFeeTransaction));

		Transaction missingNonceTransaction = transactionCase.build(repository, sender, recipient, 0L);
		assertTrue(transactionCase.name, invokeCanUseMempowFeeAlternative(missingNonceTransaction));
		assertFalse(transactionCase.name, invokeHasPaidFee(missingNonceTransaction));
		assertEquals(transactionCase.name, ValidationResult.INSUFFICIENT_FEE, invokeIsFeeValid(missingNonceTransaction));

		Transaction invalidNonceTransaction = transactionCase.build(repository, sender, recipient, 0L);
		setInvalidMempowNonce(invalidNonceTransaction);
		assertEquals(transactionCase.name, ValidationResult.INSUFFICIENT_FEE, invokeIsFeeValid(invalidNonceTransaction));

		Transaction zeroFeeMempowTransaction = transactionCase.build(repository, sender, recipient, 0L);
		computeValidMempowNonce(zeroFeeMempowTransaction);
		assertEquals(transactionCase.name, ValidationResult.OK, invokeIsFeeValid(zeroFeeMempowTransaction));

		Transaction lowFeeMempowTransaction = transactionCase.build(repository, sender, recipient, 1L);
		computeValidMempowNonce(lowFeeMempowTransaction);
		assertEquals(transactionCase.name, ValidationResult.OK, invokeIsFeeValid(lowFeeMempowTransaction));

		Transaction negativeFeeTransaction = transactionCase.build(repository, sender, recipient, -1L);
		computeValidMempowNonce(negativeFeeTransaction);
		assertEquals(transactionCase.name, ValidationResult.NEGATIVE_FEE, invokeIsFeeValid(negativeFeeTransaction));
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

	private static class FastMempowTestTransaction extends Transaction {
		private FastMempowTestTransaction(Repository repository, TransactionData transactionData) {
			super(repository, transactionData);
		}

		@Override
		public List<String> getRecipientAddresses() {
			return Collections.emptyList();
		}

		@Override
		public ValidationResult isValid() {
			return ValidationResult.OK;
		}

		@Override
		public void preProcess() {
			// Nothing to do
		}

		@Override
		public void process() {
			// Nothing to do
		}

		@Override
		public void orphan() {
			// Nothing to do
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

	private static class RepresentativeTransactionCase {
		private final String name;
		private final RepresentativeTransactionDataBuilder transactionDataBuilder;

		private RepresentativeTransactionCase(String name, RepresentativeTransactionDataBuilder transactionDataBuilder) {
			this.name = name;
			this.transactionDataBuilder = transactionDataBuilder;
		}

		private Transaction build(Repository repository, PrivateKeyAccount sender, PrivateKeyAccount recipient, long fee) {
			return new FastMempowTestTransaction(repository, this.transactionDataBuilder.build(sender, recipient, fee));
		}
	}

	private interface RepresentativeTransactionDataBuilder {
		TransactionData build(PrivateKeyAccount sender, PrivateKeyAccount recipient, long fee);
	}
}
