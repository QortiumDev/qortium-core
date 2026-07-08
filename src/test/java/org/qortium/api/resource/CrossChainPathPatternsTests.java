package org.qortium.api.resource;

import org.junit.Test;

import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CrossChainPathPatternsTests {

	private static final Pattern BITCOINY_BLOCKCHAIN_SEGMENT = Pattern.compile(
			"^" + CrossChainPathPatterns.BITCOINY_BLOCKCHAIN_SEGMENT + "$");

	@Test
	public void testBitcoinyBlockchainSegmentAcceptsCoinNames() {
		assertTrue(BITCOINY_BLOCKCHAIN_SEGMENT.matcher("LITECOIN").matches());
		assertTrue(BITCOINY_BLOCKCHAIN_SEGMENT.matcher("bitcoin").matches());
		assertTrue(BITCOINY_BLOCKCHAIN_SEGMENT.matcher("DOGE").matches());
	}

	@Test
	public void testBitcoinyBlockchainSegmentRejectsReservedCrossChainRoutes() {
		for (String reserved : new String[] {
				"blockchains",
				"tradeoffers",
				"tradeoffer",
				"trades",
				"trade",
				"signedfees",
				"unsignedfees",
				"ledger",
				"price",
				"p2sh",
				"txactivity",
				"htlc",
				"tradebot",
				"arrr"
		}) {
			assertFalse(reserved, BITCOINY_BLOCKCHAIN_SEGMENT.matcher(reserved).matches());
			assertFalse(reserved.toUpperCase(), BITCOINY_BLOCKCHAIN_SEGMENT.matcher(reserved.toUpperCase()).matches());
		}
	}

	@Test
	public void testBitcoinyBlockchainSegmentRejectsMultiSegmentPaths() {
		assertFalse(BITCOINY_BLOCKCHAIN_SEGMENT.matcher("LITECOIN/height").matches());
	}
}
