package org.qortal.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortal.api.ApiError;
import org.qortal.api.resource.CrossChainResource;
import org.qortal.test.common.ApiCommon;

public class CrossChainApiTests extends ApiCommon {

	private static final String SPECIFIC_BLOCKCHAIN = null;
	private static final String SPECIFIC_BLOCKCHAIN_ALIAS = "LTC";
	private static final String UNKNOWN_BLOCKCHAIN = "UNKNOWN";

	private CrossChainResource crossChainResource;

	@Before
	public void buildResource() {
		this.crossChainResource = (CrossChainResource) ApiCommon.buildResource(CrossChainResource.class);
	}

	@Test
	public void testGetTradeOffers() {
		assertNoApiError((limit, offset, reverse) -> this.crossChainResource.getTradeOffers(SPECIFIC_BLOCKCHAIN, null, limit, offset, reverse));
		assertNoApiError((limit, offset, reverse) -> this.crossChainResource.getTradeOffers(SPECIFIC_BLOCKCHAIN_ALIAS, null, limit, offset, reverse));
	}

	@Test
	public void testGetCompletedTrades() {
		long minimumTimestamp = System.currentTimeMillis();
		assertNoApiError((limit, offset, reverse) -> this.crossChainResource.getCompletedTrades(SPECIFIC_BLOCKCHAIN, null, minimumTimestamp, null, null, limit, offset, reverse));
		assertNoApiError((limit, offset, reverse) -> this.crossChainResource.getCompletedTrades(SPECIFIC_BLOCKCHAIN_ALIAS, null, minimumTimestamp, null, null, limit, offset, reverse));
	}

	@Test
	public void testInvalidGetCompletedTrades() {
		Integer limit = null;
		Integer offset = null;
		Boolean reverse = null;

		assertApiError(ApiError.INVALID_CRITERIA, () -> this.crossChainResource.getCompletedTrades(SPECIFIC_BLOCKCHAIN, null, -1L /*minimumTimestamp*/, null, null, limit, offset, reverse));
		assertApiError(ApiError.INVALID_CRITERIA, () -> this.crossChainResource.getCompletedTrades(SPECIFIC_BLOCKCHAIN, null, 0L /*minimumTimestamp*/, null, null, limit, offset, reverse));
		assertApiError(ApiError.INVALID_CRITERIA, () -> this.crossChainResource.getTradeOffers(UNKNOWN_BLOCKCHAIN, null, limit, offset, reverse));
		assertApiError(ApiError.INVALID_CRITERIA, () -> this.crossChainResource.getCompletedTrades(UNKNOWN_BLOCKCHAIN, null, null, null, null, limit, offset, reverse));
	}

}
