package org.qortium.controller.arbitrary;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Covers the pure effectiveBatchSize() decision behind chain-first QDN yielding
 * (Settings.qdnYieldDuringSync / qdnSyncYieldBatchSize): while the node is not caught up with the
 * chain, the per-peer chunk batch size is capped at qdnSyncYieldBatchSize; once caught up, or when
 * yielding is disabled, the normal (initial/max) batch size passes through unchanged.
 *
 * Also covers the pure arithmetic behind the feedback-based (AIMD) chunk-batching window
 * (Settings.qdnAdaptiveBatching): nextWindowAfterCleanInterval() (additive increase, capped at max) and
 * nextWindowAfterExpiry() (multiplicative decrease, floored at 1). The sync-yield cap above (effectiveBatchSize)
 * is applied AFTER the AIMD window, unchanged, so both sets of tests exercise independent, composable stages.
 */
public class ArbitraryDataFileRequestThreadYieldTests {

	@Test
	public void testUpToDateUsesDesiredBatchSizeRegardlessOfYieldSetting() {
		assertEquals(40, ArbitraryDataFileRequestThread.effectiveBatchSize(true, 40, true, 1));
		assertEquals(10, ArbitraryDataFileRequestThread.effectiveBatchSize(true, 10, true, 1));
		assertEquals(40, ArbitraryDataFileRequestThread.effectiveBatchSize(true, 40, false, 1));
	}

	@Test
	public void testNotUpToDateAndYieldingCapsAtSyncYieldBatchSize() {
		assertEquals(1, ArbitraryDataFileRequestThread.effectiveBatchSize(false, 40, true, 1));
		assertEquals(1, ArbitraryDataFileRequestThread.effectiveBatchSize(false, 10, true, 1));
	}

	@Test
	public void testNotUpToDateButYieldDisabledUsesDesiredBatchSize() {
		assertEquals(40, ArbitraryDataFileRequestThread.effectiveBatchSize(false, 40, false, 1));
		assertEquals(10, ArbitraryDataFileRequestThread.effectiveBatchSize(false, 10, false, 1));
	}

	@Test
	public void testCapNeverRaisesBatchSizeAboveDesired() {
		// If the configured yield batch size is larger than what was already desired (e.g. a small
		// initial ramp-up batch), the smaller desired size still wins - yielding must never increase
		// the effective batch size.
		assertEquals(5, ArbitraryDataFileRequestThread.effectiveBatchSize(false, 5, true, 20));
	}

	@Test
	public void testYieldBatchSizeEqualToDesiredIsUnaffected() {
		assertEquals(1, ArbitraryDataFileRequestThread.effectiveBatchSize(false, 1, true, 1));
	}

	@Test
	public void testAimdAdditiveIncreaseGrowsByOneCappedAtMax() {
		assertEquals(11, ArbitraryDataFileRequestThread.nextWindowAfterCleanInterval(10, 40));
		assertEquals(40, ArbitraryDataFileRequestThread.nextWindowAfterCleanInterval(39, 40));
		// Already at max: stays at max, never exceeds it.
		assertEquals(40, ArbitraryDataFileRequestThread.nextWindowAfterCleanInterval(40, 40));
	}

	@Test
	public void testAimdAdditiveIncreaseFromOne() {
		assertEquals(2, ArbitraryDataFileRequestThread.nextWindowAfterCleanInterval(1, 40));
	}

	@Test
	public void testAimdMultiplicativeDecreaseHalvesFlooredAtOne() {
		assertEquals(20, ArbitraryDataFileRequestThread.nextWindowAfterExpiry(40));
		assertEquals(5, ArbitraryDataFileRequestThread.nextWindowAfterExpiry(10));
		assertEquals(1, ArbitraryDataFileRequestThread.nextWindowAfterExpiry(1));
		assertEquals(1, ArbitraryDataFileRequestThread.nextWindowAfterExpiry(0));
	}

	@Test
	public void testAimdWindowClimbsThenCollapsesOnExpiry() {
		// Simulate several clean intervals climbing the window, then one expiry collapsing it.
		int window = 10;
		int max = 40;
		for (int i = 0; i < 5; i++) {
			window = ArbitraryDataFileRequestThread.nextWindowAfterCleanInterval(window, max);
		}
		assertEquals(15, window);

		window = ArbitraryDataFileRequestThread.nextWindowAfterExpiry(window);
		assertEquals(7, window);
	}
}
