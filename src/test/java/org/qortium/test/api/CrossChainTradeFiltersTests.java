package org.qortium.test.api;

import org.junit.Test;
import org.qortium.api.CrossChainTradeFilters;
import org.qortium.api.model.CrossChainOfferSummary;
import org.qortium.asset.Asset;
import org.qortium.crosschain.AcctMode;
import org.qortium.crosschain.BitcoinyForeignForeignACCTv1;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.TradeDirection;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.data.crosschain.TradeBotData;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CrossChainTradeFiltersTests extends Common {

	@Test
	public void testForeignForeignTradeDataFilters() {
		CrossChainTradeData tradeData = foreignForeignTradeData();
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");
		ForeignBlockchainRegistry.Entry litecoin = ForeignBlockchainRegistry.fromString("LITECOIN");

		assertTrue(CrossChainTradeFilters.matchesTradeData(tradeData, bitcoin, null, null, null));
		assertTrue(CrossChainTradeFilters.matchesTradeData(tradeData, litecoin, null, null, null));
		assertTrue(CrossChainTradeFilters.matchesTradeData(tradeData, null, bitcoin, litecoin, null));
		assertTrue(CrossChainTradeFilters.matchesTradeData(tradeData, null, bitcoin, null, null));
		assertTrue(CrossChainTradeFilters.matchesTradeData(tradeData, null, null, litecoin, null));

		assertFalse(CrossChainTradeFilters.matchesTradeData(tradeData, null, litecoin, bitcoin, null));
		assertFalse(CrossChainTradeFilters.matchesTradeData(tradeData, null, null, null, Asset.NATIVE));
	}

	@Test
	public void testForeignForeignOfferSummaryFilters() {
		CrossChainOfferSummary offerSummary = new CrossChainOfferSummary(foreignForeignTradeData(), 1234L);
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");
		ForeignBlockchainRegistry.Entry litecoin = ForeignBlockchainRegistry.fromString("LITECOIN");

		assertTrue(CrossChainTradeFilters.matchesOfferSummary(offerSummary, bitcoin, null, null, null));
		assertTrue(CrossChainTradeFilters.matchesOfferSummary(offerSummary, litecoin, null, null, null));
		assertTrue(CrossChainTradeFilters.matchesOfferSummary(offerSummary, null, bitcoin, litecoin, null));
		assertTrue(CrossChainTradeFilters.matchesOfferSummary(offerSummary, null, bitcoin, null, null));
		assertTrue(CrossChainTradeFilters.matchesOfferSummary(offerSummary, null, null, litecoin, null));

		assertFalse(CrossChainTradeFilters.matchesOfferSummary(offerSummary, null, litecoin, bitcoin, null));
		assertFalse(CrossChainTradeFilters.matchesOfferSummary(offerSummary, null, null, null, Asset.NATIVE));
	}

	@Test
	public void testForeignForeignTradeBotDataFilters() {
		TradeBotData tradeBotData = foreignForeignTradeBotData();
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");
		ForeignBlockchainRegistry.Entry litecoin = ForeignBlockchainRegistry.fromString("LITECOIN");

		assertTrue(CrossChainTradeFilters.matchesTradeBotData(tradeBotData, bitcoin, null, null));
		assertTrue(CrossChainTradeFilters.matchesTradeBotData(tradeBotData, litecoin, null, null));
		assertTrue(CrossChainTradeFilters.matchesTradeBotData(tradeBotData, null, bitcoin, litecoin));
		assertTrue(CrossChainTradeFilters.matchesTradeBotData(tradeBotData, null, bitcoin, null));
		assertTrue(CrossChainTradeFilters.matchesTradeBotData(tradeBotData, null, null, litecoin));

		assertFalse(CrossChainTradeFilters.matchesTradeBotData(tradeBotData, null, litecoin, bitcoin));
	}

	private static CrossChainTradeData foreignForeignTradeData() {
		CrossChainTradeData tradeData = new CrossChainTradeData();
		tradeData.atAddress = "ATforeignforeign";
		tradeData.creatorAddress = "creator";
		tradeData.creatorTradeAddress = "trade";
		tradeData.tradeDirection = TradeDirection.SELL_FOREIGN_FOR_FOREIGN;
		tradeData.localAssetId = Asset.NATIVE;
		tradeData.mode = AcctMode.OFFERING;
		tradeData.offeredForeignBlockchain = "BITCOIN";
		tradeData.offeredForeignAmount = 100_000L;
		tradeData.requestedForeignBlockchain = "LITECOIN";
		tradeData.requestedForeignAmount = 1_000_000L;
		tradeData.tradeTimeout = 120;
		tradeData.acctName = BitcoinyForeignForeignACCTv1.NAME;
		return tradeData;
	}

	private static TradeBotData foreignForeignTradeBotData() {
		TradeBotData tradeBotData = new TradeBotData(new byte[32], BitcoinyForeignForeignACCTv1.NAME,
				"MAKER_WAITING_FOR_AT_CONFIRM", 0, "creator", "ATforeignforeign", 0L,
				Asset.NATIVE, 0L, new byte[32], new byte[20], "trade",
				new byte[32], new byte[20], null, new byte[32], new byte[20],
				100_000L, null, null, null, null);
		tradeBotData.setOfferedForeignBlockchain("BITCOIN");
		tradeBotData.setOfferedForeignAmount(100_000L);
		tradeBotData.setRequestedForeignBlockchain("LITECOIN");
		tradeBotData.setRequestedForeignAmount(1_000_000L);
		return tradeBotData;
	}

}
