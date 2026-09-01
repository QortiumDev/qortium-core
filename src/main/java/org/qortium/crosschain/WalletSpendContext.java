package org.qortium.crosschain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded watch-only data needed for independent client-side spend construction. */
public class WalletSpendContext {

	private final List<WalletSpendContextUtxo> outputs;
	private final Map<String, byte[]> previousTransactions;

	public WalletSpendContext(List<WalletSpendContextUtxo> outputs, Map<String, byte[]> previousTransactions) {
		this.outputs = Collections.unmodifiableList(new ArrayList<>(outputs));

		Map<String, byte[]> transactionsCopy = new LinkedHashMap<>();
		previousTransactions.forEach((txHash, rawTransaction) -> transactionsCopy.put(txHash, rawTransaction.clone()));
		this.previousTransactions = Collections.unmodifiableMap(transactionsCopy);
	}

	public List<WalletSpendContextUtxo> getOutputs() {
		return this.outputs;
	}

	public Map<String, byte[]> getPreviousTransactions() {
		Map<String, byte[]> transactionsCopy = new LinkedHashMap<>();
		this.previousTransactions.forEach((txHash, rawTransaction) -> transactionsCopy.put(txHash, rawTransaction.clone()));
		return Collections.unmodifiableMap(transactionsCopy);
	}
}
