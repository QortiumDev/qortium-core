package org.qortium.test;

import org.junit.Test;
import org.qortium.controller.Controller;
import org.qortium.data.block.BlockData;
import org.qortium.data.block.BlockSummaryData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StaleCatchUpTests {

	@Test
	public void testPeerTipAheadRequiresHigherHeightAndNewerTimestamp() {
		BlockData ourTip = blockData(10, 1_000L);

		assertTrue(Controller.isPeerTipAheadOf(ourTip, blockSummaryData(11, 1_001L)));

		assertFalse(Controller.isPeerTipAheadOf(ourTip, blockSummaryData(10, 1_001L)));
		assertFalse(Controller.isPeerTipAheadOf(ourTip, blockSummaryData(11, 1_000L)));
		assertFalse(Controller.isPeerTipAheadOf(ourTip, blockSummaryData(11, 999L)));
		assertFalse(Controller.isPeerTipAheadOf(ourTip, null));
		assertFalse(Controller.isPeerTipAheadOf(null, blockSummaryData(11, 1_001L)));
		assertFalse(Controller.isPeerTipAheadOf(blockData(null, 1_000L), blockSummaryData(11, 1_001L)));
		assertFalse(Controller.isPeerTipAheadOf(ourTip, blockSummaryData(11, null)));
	}

	@Test
	public void testChainTipComparisonPrefersHeightThenTimestamp() {
		BlockSummaryData lowerHeightNewerTimestamp = blockSummaryData(10, 5_000L);
		BlockSummaryData higherHeightOlderTimestamp = blockSummaryData(11, 4_000L);
		BlockSummaryData olderSameHeight = blockSummaryData(11, 4_000L);
		BlockSummaryData newerSameHeight = blockSummaryData(11, 4_001L);

		assertTrue(Controller.compareChainTipsByHeightThenTimestamp(lowerHeightNewerTimestamp, higherHeightOlderTimestamp) < 0);
		assertTrue(Controller.compareChainTipsByHeightThenTimestamp(olderSameHeight, newerSameHeight) < 0);
		assertTrue(Controller.compareChainTipsByHeightThenTimestamp(newerSameHeight, olderSameHeight) > 0);
		assertTrue(Controller.compareChainTipsByHeightThenTimestamp(null, olderSameHeight) < 0);
		assertEquals(0, Controller.compareChainTipsByHeightThenTimestamp(null, null));
	}

	private static BlockData blockData(Integer height, long timestamp) {
		return new BlockData(4, null, 0, 0L, null, height, timestamp,
				new byte[32], null, 0, 0L);
	}

	private static BlockSummaryData blockSummaryData(int height, Long timestamp) {
		return new BlockSummaryData(height, new byte[64], new byte[32], null,
				timestamp, 0, null);
	}

}
