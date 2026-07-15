package org.qortium.notification;

import org.junit.Test;
import org.qortium.crosschain.BitcoinyTransaction;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ForeignIncomingPaymentDetectorTests {

	@Test
	public void testIncomingOutputsAreCombinedAndUseFirstAddress() throws Exception {
		BitcoinyTransaction transaction = transaction("incoming", List.of(), List.of(
				output("wallet-one-script", 100_000_000L),
				output("external-script", 50_000_000L),
				output("wallet-two-script", 25_000_000L)));

		ForeignIncomingPaymentDetector.Result result = ForeignIncomingPaymentDetector.detect(
				transaction,
				Map.of("wallet-one-script", "wallet-one", "wallet-two-script", "wallet-two"),
				txHash -> {
					throw new AssertionError("incoming transaction should not need a previous output");
				},
				500);

		assertEquals("wallet-one", result.address);
		assertEquals(125_000_000L, result.amount);
	}

	@Test
	public void testWalletInputMakesChangeTransactionOutgoing() throws Exception {
		BitcoinyTransaction previous = transaction("previous", List.of(),
				List.of(output("wallet-script", 200_000_000L)));
		BitcoinyTransaction transaction = transaction("outgoing",
				List.of(new BitcoinyTransaction.Input("", 0, "previous", 0)),
				List.of(output("external-script", 150_000_000L), output("wallet-script", 49_000_000L)));

		ForeignIncomingPaymentDetector.Result result = ForeignIncomingPaymentDetector.detect(
				transaction, Map.of("wallet-script", "wallet"), txHash -> previous, 500);

		assertNull(result);
	}

	private static BitcoinyTransaction transaction(String txHash, List<BitcoinyTransaction.Input> inputs,
			List<BitcoinyTransaction.Output> outputs) {
		return new BitcoinyTransaction(txHash, 100, 0, null, inputs, outputs);
	}

	private static BitcoinyTransaction.Output output(String script, long value) {
		return new BitcoinyTransaction.Output(script, value);
	}
}
