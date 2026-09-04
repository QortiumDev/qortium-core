package org.qortium.crosschain;

import org.junit.BeforeClass;
import org.junit.Test;
import org.json.JSONObject;
import org.qortium.api.model.crosschain.PirateChainBalance;
import org.qortium.api.model.crosschain.PirateChainSendRequest;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PirateChainApiContractTests {

	/**
	 * This class touches {@link PirateChain}, whose {@link Bitcoiny} superclass reads settings.
	 * Bootstrap settings so the class is exercised the same way regardless of test run order.
	 */
	@BeforeClass
	public static void beforeClass() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testBalanceParserUsesUpstreamVerifiedBalanceSemantics() throws Exception {
		PirateChainBalance balance = PirateChain.parseWalletBalances(
				"{\"zbalance\":1200,\"verified_zbalance\":900,\"spendable_zbalance\":800}");

		assertEquals(1200L, balance.zbalance);
		assertEquals(900L, balance.verified_zbalance);
	}

	@Test
	public void testBalanceParserAcceptsLegacyVerifiedField() throws Exception {
		PirateChainBalance balance = PirateChain.parseWalletBalances(
				"{\"zbalance\":1200,\"verified_zbalance\":900}");

		assertEquals(1200L, balance.zbalance);
		assertEquals(900L, balance.verified_zbalance);
	}

	@Test
	public void testVerifiedBalanceFallsBackToTotalAndIgnoresMalformedOptionalField() throws Exception {
		PirateChainBalance balance = PirateChain.parseWalletBalances("{\"zbalance\":1200}");
		PirateChainBalance malformedOptional = PirateChain.parseWalletBalances(
				"{\"zbalance\":1200,\"verified_zbalance\":{}}");

		assertEquals(1200L, balance.zbalance);
		assertEquals(1200L, balance.verified_zbalance);
		assertEquals(1200L, malformedOptional.zbalance);
		assertEquals(1200L, malformedOptional.verified_zbalance);
	}

	@Test
	public void testMalformedBalanceFailsClosed() {
		assertThrows(ForeignBlockchainException.class,
				() -> PirateChain.parseWalletBalances("{\"verified_zbalance\":900}"));
		assertThrows(ForeignBlockchainException.class,
				() -> PirateChain.parseWalletBalances("not-json"));
	}

	@Test
	public void testDefaultOffSendAndP2shPayloadContractsRemainStable() {
		PirateChainSendRequest sendRequest = new PirateChainSendRequest();
		sendRequest.receivingAddress = "zs-recipient";
		sendRequest.arrrAmount = 1234L;
		sendRequest.memo = "memo";
		JSONObject send = PirateChain.buildSendPayload("zs-input", sendRequest);
		assertEquals("send", PirateChain.SEND_COMMAND);
		assertPayment(send, "zs-input", "zs-recipient", 1234L);
		assertEquals("memo", send.getJSONArray("output").getJSONObject(0).getString("memo"));

		JSONObject fund = PirateChain.buildFundP2shPayload("zs-input", "t3-p2sh", 2345L, "script58");
		assertEquals("sendp2sh", PirateChain.FUND_P2SH_COMMAND);
		assertPayment(fund, "zs-input", "t3-p2sh", 2345L);
		assertEquals("script58", fund.getString("script"));

		JSONObject redeem = PirateChain.buildRedeemP2shPayload("t3-p2sh", "zs-recipient", 3456L,
				"script58", "funding58", 0, "secret58", "private58");
		assertEquals("redeemp2sh", PirateChain.REDEEM_P2SH_COMMAND);
		assertPayment(redeem, "t3-p2sh", "zs-recipient", 3456L);
		assertEquals("funding58", redeem.getString("txid"));
		assertEquals(0, redeem.getInt("locktime"));
		assertEquals("secret58", redeem.getString("secret"));
		assertEquals("private58", redeem.getString("privkey"));

		JSONObject refund = PirateChain.buildRedeemP2shPayload("t3-p2sh", "zs-recipient", 3456L,
				"script58", "funding58", 999, "", "private58");
		assertEquals(999, refund.getInt("locktime"));
		assertEquals("", refund.getString("secret"));
	}

	private static void assertPayment(JSONObject transaction, String inputAddress, String receivingAddress,
			long amount) {
		assertEquals(inputAddress, transaction.getString("input"));
		assertEquals(10_000L, transaction.getLong("fee"));
		JSONObject output = transaction.getJSONArray("output").getJSONObject(0);
		assertEquals(receivingAddress, output.getString("address"));
		assertEquals(amount, output.getLong("amount"));
	}
}
