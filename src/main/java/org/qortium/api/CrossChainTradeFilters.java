package org.qortium.api;

import org.qortium.api.model.CrossChainOfferSummary;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.TradeDirection;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.data.crosschain.TradeBotData;

public final class CrossChainTradeFilters {

	private CrossChainTradeFilters() {
	}

	public static boolean hasForeignForeignPairFilter(ForeignBlockchainRegistry.Entry offeredForeignBlockchain,
			ForeignBlockchainRegistry.Entry requestedForeignBlockchain) {
		return offeredForeignBlockchain != null || requestedForeignBlockchain != null;
	}

	public static boolean matchesTradeData(CrossChainTradeData crossChainTradeData,
			ForeignBlockchainRegistry.Entry foreignBlockchain,
			ForeignBlockchainRegistry.Entry offeredForeignBlockchain,
			ForeignBlockchainRegistry.Entry requestedForeignBlockchain,
			Long localAssetId) {
		return matchesForeignBlockchain(crossChainTradeData, foreignBlockchain)
				&& matchesForeignForeignPair(crossChainTradeData, offeredForeignBlockchain, requestedForeignBlockchain)
				&& matchesLocalAsset(crossChainTradeData, localAssetId);
	}

	public static boolean matchesOfferSummary(CrossChainOfferSummary offerSummary,
			ForeignBlockchainRegistry.Entry foreignBlockchain,
			ForeignBlockchainRegistry.Entry offeredForeignBlockchain,
			ForeignBlockchainRegistry.Entry requestedForeignBlockchain,
			Long localAssetId) {
		return matchesForeignBlockchain(offerSummary, foreignBlockchain)
				&& matchesForeignForeignPair(offerSummary, offeredForeignBlockchain, requestedForeignBlockchain)
				&& matchesLocalAsset(offerSummary, localAssetId);
	}

	public static boolean matchesTradeBotData(TradeBotData tradeBotData,
			ForeignBlockchainRegistry.Entry foreignBlockchain,
			ForeignBlockchainRegistry.Entry offeredForeignBlockchain,
			ForeignBlockchainRegistry.Entry requestedForeignBlockchain) {
		return matchesForeignBlockchain(tradeBotData, foreignBlockchain)
				&& matchesForeignForeignPair(tradeBotData, offeredForeignBlockchain, requestedForeignBlockchain);
	}

	public static boolean matchesForeignBlockchain(CrossChainTradeData crossChainTradeData,
			ForeignBlockchainRegistry.Entry foreignBlockchain) {
		if (crossChainTradeData == null)
			return false;

		if (foreignBlockchain == null)
			return true;

		String blockchainName = foreignBlockchain.name();
		return blockchainName.equals(crossChainTradeData.foreignBlockchain)
				|| blockchainName.equals(crossChainTradeData.offeredForeignBlockchain)
				|| blockchainName.equals(crossChainTradeData.requestedForeignBlockchain);
	}

	public static boolean matchesForeignBlockchain(CrossChainOfferSummary offerSummary,
			ForeignBlockchainRegistry.Entry foreignBlockchain) {
		if (offerSummary == null)
			return false;

		if (foreignBlockchain == null)
			return true;

		String blockchainName = foreignBlockchain.name();
		return blockchainName.equals(offerSummary.getForeignBlockchain())
				|| blockchainName.equals(offerSummary.getOfferedForeignBlockchain())
				|| blockchainName.equals(offerSummary.getRequestedForeignBlockchain());
	}

	public static boolean matchesForeignBlockchain(TradeBotData tradeBotData,
			ForeignBlockchainRegistry.Entry foreignBlockchain) {
		if (tradeBotData == null)
			return false;

		if (foreignBlockchain == null)
			return true;

		String blockchainName = foreignBlockchain.name();
		return blockchainName.equals(tradeBotData.getForeignBlockchain())
				|| blockchainName.equals(tradeBotData.getOfferedForeignBlockchain())
				|| blockchainName.equals(tradeBotData.getRequestedForeignBlockchain());
	}

	public static boolean matchesLocalAsset(CrossChainTradeData crossChainTradeData, Long localAssetId) {
		if (crossChainTradeData == null)
			return false;

		if (localAssetId == null)
			return true;

		return crossChainTradeData.tradeDirection != TradeDirection.SELL_FOREIGN_FOR_FOREIGN
				&& crossChainTradeData.localAssetId == localAssetId;
	}

	private static boolean matchesLocalAsset(CrossChainOfferSummary offerSummary, Long localAssetId) {
		if (offerSummary == null)
			return false;

		if (localAssetId == null)
			return true;

		return offerSummary.getTradeDirection() != TradeDirection.SELL_FOREIGN_FOR_FOREIGN
				&& offerSummary.getLocalAssetId() == localAssetId;
	}

	private static boolean matchesForeignForeignPair(CrossChainTradeData crossChainTradeData,
			ForeignBlockchainRegistry.Entry offeredForeignBlockchain,
			ForeignBlockchainRegistry.Entry requestedForeignBlockchain) {
		if (!hasForeignForeignPairFilter(offeredForeignBlockchain, requestedForeignBlockchain))
			return true;

		if (crossChainTradeData == null || crossChainTradeData.tradeDirection != TradeDirection.SELL_FOREIGN_FOR_FOREIGN)
			return false;

		return matchesBlockchainName(crossChainTradeData.offeredForeignBlockchain, offeredForeignBlockchain)
				&& matchesBlockchainName(crossChainTradeData.requestedForeignBlockchain, requestedForeignBlockchain);
	}

	private static boolean matchesForeignForeignPair(CrossChainOfferSummary offerSummary,
			ForeignBlockchainRegistry.Entry offeredForeignBlockchain,
			ForeignBlockchainRegistry.Entry requestedForeignBlockchain) {
		if (!hasForeignForeignPairFilter(offeredForeignBlockchain, requestedForeignBlockchain))
			return true;

		if (offerSummary == null || offerSummary.getTradeDirection() != TradeDirection.SELL_FOREIGN_FOR_FOREIGN)
			return false;

		return matchesBlockchainName(offerSummary.getOfferedForeignBlockchain(), offeredForeignBlockchain)
				&& matchesBlockchainName(offerSummary.getRequestedForeignBlockchain(), requestedForeignBlockchain);
	}

	private static boolean matchesForeignForeignPair(TradeBotData tradeBotData,
			ForeignBlockchainRegistry.Entry offeredForeignBlockchain,
			ForeignBlockchainRegistry.Entry requestedForeignBlockchain) {
		if (!hasForeignForeignPairFilter(offeredForeignBlockchain, requestedForeignBlockchain))
			return true;

		if (tradeBotData == null)
			return false;

		return matchesBlockchainName(tradeBotData.getOfferedForeignBlockchain(), offeredForeignBlockchain)
				&& matchesBlockchainName(tradeBotData.getRequestedForeignBlockchain(), requestedForeignBlockchain);
	}

	private static boolean matchesBlockchainName(String blockchainName, ForeignBlockchainRegistry.Entry filter) {
		return filter == null || filter.name().equals(blockchainName);
	}

}
