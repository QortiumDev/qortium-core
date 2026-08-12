package org.qortium.controller.arbitrary;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Covers the pure effectiveBatchSize() decision behind chain-first QDN yielding
 * (Settings.qdnYieldDuringSync / qdnSyncYieldBatchSize): while the node is not caught up with the
 * chain, the per-peer chunk batch size is capped at qdnSyncYieldBatchSize; once caught up, or when
 * yielding is disabled, the normal (initial/max) batch size passes through unchanged.
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
}
