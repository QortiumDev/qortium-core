package org.qortium.at;

import org.ciyam.at.ExecutionException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Focused overflow-trip coverage for the checked AT monetary aggregation helper used by
 * {@link ChainATAPI} (per-asset multi-payment reads and pending platform payouts). The helper
 * must add exactly like a plain sum for every non-overflowing pair, but surface an overflow as a
 * deterministic {@link ExecutionException} (the AT's fatal-error path) rather than silently
 * wrapping into a smaller, consensus-valid but nonsensical amount.
 */
public class ChainAtCheckedArithmeticTests {

	@Test
	public void testCheckedSumMatchesPlainAdditionWhenNoOverflow() throws ExecutionException {
		assertEquals(0L, ChainATAPI.checkedAtSum(0L, 0L));
		assertEquals(300L, ChainATAPI.checkedAtSum(100L, 200L));
		assertEquals(Long.MAX_VALUE, ChainATAPI.checkedAtSum(Long.MAX_VALUE - 1L, 1L));
	}

	@Test
	public void testCheckedSumThrowsDeterministicallyOnOverflow() {
		try {
			ChainATAPI.checkedAtSum(Long.MAX_VALUE, 1L);
			fail("overflowing AT monetary aggregation must throw, not wrap");
		} catch (ExecutionException e) {
			// expected: deterministic AT fatal-error path
		}
	}

	@Test
	public void testCheckedSumOverflowNeverWrapsToSmallerAmount() {
		// A running total near the ceiling plus a large addend would wrap to a negative/smaller value
		// under plain long addition; the checked helper must refuse it instead.
		try {
			ChainATAPI.checkedAtSum(Long.MAX_VALUE - 5L, 100L);
			fail("aggregation that would wrap must throw");
		} catch (ExecutionException e) {
			// expected
		}
	}
}
