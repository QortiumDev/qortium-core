package org.qortium.crosschain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * Exact-decimal parsing vectors for the ARRR send contract. The shared
 * {@code AmountTypeAdapter} rounds/truncates; this one must fail closed on everything that is not
 * plain positive decimal text with at most 8 fractional digits.
 */
public class PirateChainAmountAdapterTests {

	private static String reject(String text) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> PirateChainAmountAdapter.parseAtomic(text));
		return e.getMessage();
	}

	@Test
	public void testAcceptsPlainDecimalsExactly() {
		assertEquals(150_000_000L, PirateChainAmountAdapter.parseAtomic("1.5"));
		assertEquals(100_000_001L, PirateChainAmountAdapter.parseAtomic("1.00000001"));
		assertEquals(1L, PirateChainAmountAdapter.parseAtomic("0.00000001"));
		assertEquals(100_000_000L, PirateChainAmountAdapter.parseAtomic("1"));
		assertEquals(123_456_789_000L, PirateChainAmountAdapter.parseAtomic("1234.56789"));
		assertEquals(10_000L, PirateChainAmountAdapter.parseAtomic("0.0001"));
		// The cap is the ARRR maximum supply: 200,000,000 ARRR = 2e16 atomic units, asserted as a
		// literal so a wrong constant cannot be self-consistent with the test.
		assertEquals(20_000_000_000_000_000L, PirateChainAmountAdapter.MAX_SUPPLY_ATOMIC);
		assertEquals(20_000_000_000_000_000L, PirateChainAmountAdapter.parseAtomic("200000000"));
		assertEquals(19_999_999_999_999_999L, PirateChainAmountAdapter.parseAtomic("199999999.99999999"));
		assertEquals(2_000_000_000_000_000L, PirateChainAmountAdapter.parseAtomic("20000000"));
	}

	@Test
	public void testRejectsZeroAndNegative() {
		assertEquals("AMOUNT_ZERO", reject("0"));
		assertEquals("AMOUNT_ZERO", reject("0.0"));
		assertEquals("AMOUNT_ZERO", reject("0.00000000"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("-1"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("-0.00000001"));
	}

	@Test
	public void testRejectsMissingOrMalformedText() {
		assertEquals("AMOUNT_MISSING", reject(null));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject(""));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject(" 1"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("1 "));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("+1"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("1."));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject(".5"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("01"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("00.5"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("1,5"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("1_000"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("0x10"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("NaN"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("Infinity"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("１.５")); // full-width digits
	}

	@Test
	public void testRejectsExponentsAndRounding() {
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("1e8"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("1E-8"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("1.5e0"));
		// 9 decimals: the shared adapter would throw a bare ArithmeticException; this must be typed.
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("1.000000001"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("0.000000001"));
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", reject("1.123456789"));
	}

	@Test
	public void testRejectsOverflowAndAboveMaxSupply() {
		// Above the ARRR maximum supply but still a valid long.
		assertEquals("AMOUNT_TOO_LARGE", reject("200000000.00000001"));
		assertEquals("AMOUNT_TOO_LARGE", reject("200000001"));
		assertEquals("AMOUNT_TOO_LARGE", reject("2000000000"));
		// Above Long.MAX_VALUE atomic units: the shared adapter's longValue() would silently wrap.
		assertEquals("AMOUNT_TOO_LARGE", reject("92233720368.54775808"));
		assertEquals("AMOUNT_TOO_LARGE", reject("99999999999999999999"));
	}
}
