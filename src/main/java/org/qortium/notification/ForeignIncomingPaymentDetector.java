package org.qortium.notification;

import org.qortium.crosschain.BitcoinyTransaction;

import java.util.Locale;
import java.util.Map;

/** Pure incoming-payment classification for raw Bitcoiny transactions. */
final class ForeignIncomingPaymentDetector {

	@FunctionalInterface
	interface TransactionLookup {
		BitcoinyTransaction get(String txHash) throws Exception;
	}

	static final class Result {
		final String address;
		final long amount;

		private Result(String address, long amount) {
			this.address = address;
			this.amount = amount;
		}
	}

	private ForeignIncomingPaymentDetector() {
	}

	static Result detect(BitcoinyTransaction transaction, Map<String, String> addressByScriptPubKey,
			TransactionLookup transactionLookup, int maximumInputs) throws Exception {
		if (transaction.inputs.size() > maximumInputs)
			return null;

		for (BitcoinyTransaction.Input input : transaction.inputs) {
			if (input.outputTxHash == null || input.outputVout < 0 || isZeroHash(input.outputTxHash))
				continue;

			BitcoinyTransaction previousTransaction = transactionLookup.get(input.outputTxHash);
			if (input.outputVout >= previousTransaction.outputs.size())
				return null;
			String previousScript = previousTransaction.outputs.get(input.outputVout).scriptPubKey;
			if (previousScript != null && addressByScriptPubKey.containsKey(previousScript.toLowerCase(Locale.ROOT)))
				return null;
		}

		long amount = 0L;
		String address = null;
		for (BitcoinyTransaction.Output output : transaction.outputs) {
			if (output.scriptPubKey == null)
				continue;
			String outputAddress = addressByScriptPubKey.get(output.scriptPubKey.toLowerCase(Locale.ROOT));
			if (outputAddress == null)
				continue;
			if (address == null)
				address = outputAddress;
			amount = Math.addExact(amount, output.value);
		}

		return address == null || amount <= 0L ? null : new Result(address, amount);
	}

	private static boolean isZeroHash(String txHash) {
		for (int index = 0; index < txHash.length(); ++index)
			if (txHash.charAt(index) != '0')
				return false;
		return true;
	}
}
