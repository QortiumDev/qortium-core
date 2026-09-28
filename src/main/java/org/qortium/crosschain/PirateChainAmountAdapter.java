package org.qortium.crosschain;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Exact ARRR amount parsing for the send contract.
 * <p>
 * Deliberately separate from the shared {@code org.qortium.api.AmountTypeAdapter}: that adapter
 * calls {@code setScale(8)} (which throws a bare ArithmeticException on more than 8 decimals) and
 * truncating {@code longValue()} (which silently wraps amounts above 2^63-1 atomic units). A spend
 * must never be rounded, wrapped or guessed, so this parser accepts only plain decimal text and
 * fails closed with a stable reason on everything else.
 */
public final class PirateChainAmountAdapter {

	/** Plain decimal text: no sign, no exponent, no leading zeros, at most 8 fractional digits. */
	static final Pattern PLAIN_DECIMAL = Pattern.compile("^(0|[1-9][0-9]*)(\\.[0-9]{1,8})?$");

	public static final int DECIMALS = 8;

	/**
	 * ARRR maximum supply (200,000,000 ARRR) in atomic units. Any single send above this is
	 * necessarily malformed input, never a real balance, so it is rejected before any wallet work.
	 */
	public static final long MAX_SUPPLY_ATOMIC = 2_000_000_000_000_000L;

	private PirateChainAmountAdapter() {
	}

	/**
	 * Parses coin-decimal text (e.g. {@code "1.00000001"}) into atomic units ({@code 100000001}).
	 *
	 * @throws IllegalArgumentException with a stable reason token when the text is not a plain
	 *         positive decimal with at most {@value #DECIMALS} fractional digits, is zero, or exceeds
	 *         the maximum supply
	 */
	public static long parseAtomic(String text) {
		if (text == null)
			throw new IllegalArgumentException("AMOUNT_MISSING");
		if (!PLAIN_DECIMAL.matcher(text).matches())
			throw new IllegalArgumentException("AMOUNT_NOT_PLAIN_DECIMAL");

		final long atomic;
		try {
			atomic = new BigDecimal(text).movePointRight(DECIMALS).longValueExact();
		} catch (ArithmeticException e) {
			// More than 8 decimals cannot happen here (regex), so this is only "does not fit a long".
			throw new IllegalArgumentException("AMOUNT_TOO_LARGE");
		}

		if (atomic <= 0)
			throw new IllegalArgumentException("AMOUNT_ZERO");
		if (atomic > MAX_SUPPLY_ATOMIC)
			throw new IllegalArgumentException("AMOUNT_TOO_LARGE");

		return atomic;
	}

}
