package org.qortium.block;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Focused overflow-trip coverage for the checked per-block fee accumulation used when a block's
 * total fees and per-block AT fees are reconstructed from untrusted, network-supplied fields
 * ({@link Block#addCheckedFees}). Reconstruction must sum exactly like plain addition for every
 * realistic block, but an overflow must make the block invalid deterministically (an
 * {@link ArithmeticException} on every node) rather than wrap into a smaller apparent total.
 */
public class BlockFeeOverflowTests {

	@Test
	public void testAddCheckedFeesMatchesPlainAdditionWhenNoOverflow() {
		assertEquals(0L, Block.addCheckedFees(0L, 0L));
		assertEquals(1_500L, Block.addCheckedFees(1_000L, 500L));
		assertEquals(Long.MAX_VALUE, Block.addCheckedFees(Long.MAX_VALUE - 10L, 10L));
	}

	@Test
	public void testAddCheckedFeesThrowsDeterministicallyOnOverflow() {
		try {
			Block.addCheckedFees(Long.MAX_VALUE, 1L);
			fail("overflowing block-fee reconstruction must throw, not wrap");
		} catch (ArithmeticException e) {
			// expected: deterministic block-invalid path
		}
	}

	@Test
	public void testAddCheckedFeesOverflowNeverWrapsToSmallerTotal() {
		try {
			Block.addCheckedFees(Long.MAX_VALUE - 3L, 50L);
			fail("fee total that would wrap must throw");
		} catch (ArithmeticException e) {
			// expected
		}
	}
}
