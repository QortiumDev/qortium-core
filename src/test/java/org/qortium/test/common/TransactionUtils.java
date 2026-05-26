package org.qortium.test.common;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.utils.NTP;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TransactionUtils {

	public static long nextTimestamp(Repository repository) throws DataException {
		Long now = NTP.getTime();
		long nextBlockTimestamp = repository.getBlockRepository().getLastBlock().getTimestamp() + 1;

		return now == null ? nextBlockTimestamp : Math.min(now, nextBlockTimestamp);
	}

	/** Signs transaction using given account and attempts to import into unconfirmed pile, returning validation result. */
	public static ValidationResult signAndImport(Repository repository, TransactionData transactionData, PrivateKeyAccount signingAccount) throws DataException {
		Transaction transaction = Transaction.fromData(repository, transactionData);
		transaction.sign(signingAccount);

		// Add to unconfirmed
		assertTrue("Transaction's signature should be valid", transaction.isSignatureValid());

		// We might need to wait until transaction's timestamp is valid for the block we're about to mint
		try {
			Thread.sleep(1L);
		} catch (InterruptedException e) {
		}

		return transaction.importAsUnconfirmed();
	}

	/** Signs transaction using given account and imports into unconfirmed pile, checking transaction is valid. */
	public static void signAndImportValid(Repository repository, TransactionData transactionData, PrivateKeyAccount signingAccount) throws DataException {
		Transaction transaction = Transaction.fromData(repository, transactionData);
		transaction.sign(signingAccount);

		// Add to unconfirmed
		assertTrue("Transaction's signature should be valid", transaction.isSignatureValid());

		// We might need to wait until transaction's timestamp is valid for the block we're about to mint
		try {
			Thread.sleep(1L);
		} catch (InterruptedException e) {
		}

		ValidationResult result = transaction.importAsUnconfirmed();
		assertEquals("Transaction invalid", ValidationResult.OK, result);
	}

	/** Signs transaction using given account and mints a new block.<br> See {@link BlockUtils#mintBlock(Repository)} */
	public static void signAndMint(Repository repository, TransactionData transactionData, PrivateKeyAccount signingAccount) throws DataException {
		signAndImportValid(repository, transactionData, signingAccount);

		// Mint block
		BlockUtils.mintBlock(repository);
	}

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, TransactionType txType, boolean wantValid) throws DataException {
		try {
			Class <?> clazz = Class.forName(String.join("", TestTransaction.class.getPackage().getName(), ".", txType.className, "TestTransaction"));

			try {
				Method method = clazz.getDeclaredMethod("randomTransaction", Repository.class, PrivateKeyAccount.class, boolean.class);
				return (TransactionData) method.invoke(null, repository, account, wantValid);
			} catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
				throw new RuntimeException(String.format("Transaction subclass constructor not found for transaction type \"%s\"", txType.name()), e);
			}
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(String.format("Transaction subclass not found for transaction type \"%s\"", txType.name()), e);
		}
	}

	public static void deleteUnconfirmedTransactions(Repository repository) throws DataException {
		List<TransactionData> unconfirmedTransactions = repository.getTransactionRepository().getUnconfirmedTransactions();

		for (TransactionData transactionData : unconfirmedTransactions)
			repository.getTransactionRepository().delete(transactionData);

		repository.saveChanges();
	}

}
