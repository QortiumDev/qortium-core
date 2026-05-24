package org.qortal.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.data.account.AccountData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.PublicizeTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.transaction.PaymentTransaction;
import org.qortal.transaction.PublicizeTransaction;
import org.qortal.transaction.Transaction.ApprovalStatus;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PublicizeTransactionTests extends Common {

	private static final int TEST_MEMPOW_BUFFER_SIZE = 8 * 1024;
	private static final int TEST_MEMPOW_DIFFICULTY = 4;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testPublicizeSerializationPreservesSharedNonce() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PublicizeTransactionData transactionData = buildPublicizeTransactionData(TransactionUtils.nextTimestamp(repository), alice, 0L, 1234);
			PublicizeTransaction publicizeTransaction = new PublicizeTransaction(repository, transactionData);
			transactionData.setFee(publicizeTransaction.calcRecommendedFee());
			publicizeTransaction.sign(alice);

			byte[] transactionBytes = TransactionTransformer.toBytes(transactionData);
			assertEquals(TransactionTransformer.getDataLength(transactionData), transactionBytes.length);

			PublicizeTransactionData deserializedTransactionData = (PublicizeTransactionData) TransactionTransformer.fromBytes(transactionBytes);
			assertEquals(1234, deserializedTransactionData.getNonce());
			assertArrayEquals(transactionData.getSignature(), deserializedTransactionData.getSignature());
			assertArrayEquals(transactionBytes, TransactionTransformer.toBytes(deserializedTransactionData));
		}
	}

	@Test
	public void testPublicizeAcceptsPaidFeeWithoutNonce() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			long timestamp = TransactionUtils.nextTimestamp(repository);
			PublicizeTransactionData transactionData = buildPublicizeTransactionData(timestamp, alice, 0L, null);
			PublicizeTransaction publicizeTransaction = new PublicizeTransaction(repository, transactionData);
			transactionData.setFee(publicizeTransaction.calcRecommendedFee());

			assertEquals(ValidationResult.OK, publicizeTransaction.isFeeValid());
			assertEquals(ValidationResult.OK, publicizeTransaction.isValid());
		}
	}

	@Test
	public void testPublicizeRejectsZeroFeeWithoutValidNonce() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			long timestamp = TransactionUtils.nextTimestamp(repository);

			PublicizeTransactionData missingNonceData = buildPublicizeTransactionData(timestamp, alice, 0L, null);
			FastMempowPublicizeTransaction missingNonceTransaction = new FastMempowPublicizeTransaction(repository, missingNonceData);
			assertEquals(ValidationResult.INSUFFICIENT_FEE, missingNonceTransaction.isFeeValid());

			PublicizeTransactionData invalidNonceData = buildPublicizeTransactionData(timestamp, alice, 0L, -1);
			FastMempowPublicizeTransaction invalidNonceTransaction = new FastMempowPublicizeTransaction(repository, invalidNonceData);
			assertEquals(ValidationResult.INSUFFICIENT_FEE, invalidNonceTransaction.isFeeValid());
		}
	}

	@Test
	public void testPublicizeAcceptsZeroFeeWithValidNonce() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PublicizeTransactionData transactionData = buildPublicizeTransactionData(TransactionUtils.nextTimestamp(repository), alice, 0L, 0);
			FastMempowPublicizeTransaction publicizeTransaction = new FastMempowPublicizeTransaction(repository, transactionData);

			publicizeTransaction.computeNonce();

			assertEquals(ValidationResult.OK, publicizeTransaction.isFeeValid());
			assertEquals(ValidationResult.OK, publicizeTransaction.isValid());
		}
	}

	@Test
	public void testPublicizeProcessPublishesPublicKeyForUnknownAccount() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount newAccount = Common.generateRandomSeedAccount(repository);
			assertNull(repository.getAccountRepository().getAccount(newAccount.getAddress()));

			PublicizeTransactionData transactionData = buildPublicizeTransactionData(TransactionUtils.nextTimestamp(repository), newAccount, 0L, 0);
			FastMempowPublicizeTransaction publicizeTransaction = new FastMempowPublicizeTransaction(repository, transactionData);
			publicizeTransaction.computeNonce();
			publicizeTransaction.sign(newAccount);
			transactionData.setApprovalStatus(ApprovalStatus.NOT_REQUIRED);

			assertEquals(ValidationResult.OK, publicizeTransaction.isFeeValid());
			assertEquals(ValidationResult.OK, publicizeTransaction.isValid());

			publicizeTransaction.process();
			publicizeTransaction.processReferencesAndFees();

			AccountData accountData = repository.getAccountRepository().getAccount(newAccount.getAddress());
			assertNotNull(accountData);
			assertArrayEquals(newAccount.getPublicKey(), accountData.getPublicKey());

			repository.discardChanges();
		}
	}

	@Test
	public void testConfirmedDuplicatePublicizeRejected() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			PublicizeTransactionData firstTransactionData = buildPublicizeTransactionData(TransactionUtils.nextTimestamp(repository), alice, 0L, null);
			PublicizeTransaction firstPublicizeTransaction = new PublicizeTransaction(repository, firstTransactionData);
			firstTransactionData.setFee(firstPublicizeTransaction.calcRecommendedFee());
			TransactionUtils.signAndMint(repository, firstTransactionData, alice);

			PublicizeTransactionData duplicateTransactionData = buildPublicizeTransactionData(TransactionUtils.nextTimestamp(repository), alice, 0L, null);
			PublicizeTransaction duplicatePublicizeTransaction = new PublicizeTransaction(repository, duplicateTransactionData);
			duplicateTransactionData.setFee(duplicatePublicizeTransaction.calcRecommendedFee());

			assertEquals(ValidationResult.TRANSACTION_ALREADY_EXISTS, duplicatePublicizeTransaction.isValid());
		}
	}

	@Test
	public void testPaymentToUnknownAddressCreatesMinimalRecipientAccount() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			Account recipient = Common.generateRandomSeedAccount(repository);
			assertNull(repository.getAccountRepository().getAccount(recipient.getAddress()));

			PaymentTransactionData paymentTransactionData = buildPaymentTransactionData(repository, alice, recipient.getAddress(), 1L);
			TransactionUtils.signAndMint(repository, paymentTransactionData, alice);

			AccountData recipientData = repository.getAccountRepository().getAccount(recipient.getAddress());
			assertNotNull(recipientData);
			assertNull(recipientData.getPublicKey());
			assertEquals(1L, recipient.getConfirmedBalance(Asset.NATIVE));
		}
	}

	private static PublicizeTransactionData buildPublicizeTransactionData(long timestamp, PrivateKeyAccount sender, long fee, Integer nonce) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, sender.getPublicKey(), fee, nonce, null);
		PublicizeTransactionData transactionData = new PublicizeTransactionData(baseTransactionData, nonce != null ? nonce : 0);
		transactionData.setNonce(nonce);
		return transactionData;
	}

	private static PaymentTransactionData buildPaymentTransactionData(Repository repository, PrivateKeyAccount sender, String recipient, long amount) throws DataException {
		BaseTransactionData baseTransactionData = new BaseTransactionData(TransactionUtils.nextTimestamp(repository), Group.NO_GROUP, sender.getPublicKey(), 0L, null);
		PaymentTransactionData transactionData = new PaymentTransactionData(baseTransactionData, recipient, amount);
		PaymentTransaction paymentTransaction = new PaymentTransaction(repository, transactionData);
		transactionData.setFee(paymentTransaction.calcRecommendedFee());
		return transactionData;
	}

	private static class FastMempowPublicizeTransaction extends PublicizeTransaction {
		private FastMempowPublicizeTransaction(Repository repository, PublicizeTransactionData transactionData) {
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
