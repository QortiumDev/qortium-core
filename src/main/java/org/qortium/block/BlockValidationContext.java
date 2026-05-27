package org.qortium.block;

import org.qortium.data.transaction.TransactionData;
import org.qortium.utils.ByteArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-local context used only during block transaction validation.
 * Allows transactions in the same block to be found by signature before they
 * are saved to the repository (e.g. so GROUP_APPROVAL can resolve a
 * same-block pending transaction).
 * <p>
 * Must be set at the start of {@link Block#areTransactionsValid()} and
 * cleared in a finally block so the ThreadLocal is never left set.
 */
public final class BlockValidationContext {

	private static final ThreadLocal<Map<ByteArray, TransactionData>> CURRENT_BLOCK_TRANSACTIONS = new ThreadLocal<>();
	private static final ThreadLocal<List<TransactionData>> CURRENT_BLOCK_TRANSACTIONS_IN_ORDER = new ThreadLocal<>();
	private static final ThreadLocal<Integer> CURRENT_TRANSACTION_INDEX = new ThreadLocal<>();

	private BlockValidationContext() {
	}

	/**
	 * Set the current block's transaction data for validation.
	 * Call this at the start of block transaction validation.
	 *
	 * @param transactions list of transaction data in the block
	 */
	public static void set(List<TransactionData> transactions) {
		if (transactions == null || transactions.isEmpty()) {
			CURRENT_BLOCK_TRANSACTIONS.set(Collections.emptyMap());
			CURRENT_BLOCK_TRANSACTIONS_IN_ORDER.set(Collections.emptyList());
			CURRENT_TRANSACTION_INDEX.remove();
			return;
		}
		Map<ByteArray, TransactionData> bySignature = new HashMap<>(transactions.size());
		for (TransactionData data : transactions) {
			if (data != null && data.getSignature() != null) {
				bySignature.put(ByteArray.wrap(data.getSignature()), data);
			}
		}
		CURRENT_BLOCK_TRANSACTIONS.set(Collections.unmodifiableMap(bySignature));
		CURRENT_BLOCK_TRANSACTIONS_IN_ORDER.set(Collections.unmodifiableList(new ArrayList<>(transactions)));
		CURRENT_TRANSACTION_INDEX.remove();
	}

	/**
	 * Look up transaction data by signature from the current block being validated.
	 *
	 * @param signature transaction signature
	 * @return the transaction data if present in the current block context, otherwise null
	 */
	public static TransactionData getBySignature(byte[] signature) {
		if (signature == null) {
			return null;
		}
		Map<ByteArray, TransactionData> map = CURRENT_BLOCK_TRANSACTIONS.get();
		if (map == null) {
			return null;
		}
		return map.get(ByteArray.wrap(signature));
	}

	/**
	 * Set which transaction in the current block is currently being validated.
	 *
	 * @param index zero-based block transaction index
	 */
	public static void setCurrentTransactionIndex(int index) {
		CURRENT_TRANSACTION_INDEX.set(index);
	}

	/**
	 * Return earlier transactions from the block currently being validated.
	 *
	 * @return transactions before the current transaction, or an empty list outside block validation
	 */
	public static List<TransactionData> getPriorTransactions() {
		List<TransactionData> transactions = CURRENT_BLOCK_TRANSACTIONS_IN_ORDER.get();
		Integer currentIndex = CURRENT_TRANSACTION_INDEX.get();

		if (transactions == null || transactions.isEmpty() || currentIndex == null || currentIndex <= 0)
			return Collections.emptyList();

		int boundedIndex = Math.min(currentIndex, transactions.size());
		return transactions.subList(0, boundedIndex);
	}

	/**
	 * Clear the current block context. Must be called in a finally block after validation.
	 */
	public static void clear() {
		CURRENT_BLOCK_TRANSACTIONS.remove();
		CURRENT_BLOCK_TRANSACTIONS_IN_ORDER.remove();
		CURRENT_TRANSACTION_INDEX.remove();
	}
}
