package org.qortium.data.account;

public final class AccountRating {

	public static final int MIN_RATING = -4;
	public static final int MAX_RATING = 4;
	public static final int NO_RATING = 0;

	private AccountRating() {
	}

	public static boolean isValid(int rating) {
		return rating >= MIN_RATING && rating <= MAX_RATING;
	}

	public static boolean isActive(int rating) {
		return rating != NO_RATING;
	}

	public static boolean isPositive(int rating) {
		return rating > NO_RATING;
	}

	public static boolean isNegative(int rating) {
		return rating < NO_RATING;
	}

	public static int getConfidence(int rating) {
		return Math.abs(rating);
	}

	public static String getDirection(int rating) {
		if (isPositive(rating))
			return "POSITIVE";

		if (isNegative(rating))
			return "NEGATIVE";

		return "NONE";
	}

	public static int calculateImpact(int rating, int evaluatorWeight) {
		return saturatedInt(calculateImpactLong(rating, evaluatorWeight));
	}

	public static long calculateImpactLong(int rating, long evaluatorWeight) {
		if (!isActive(rating) || evaluatorWeight <= 0)
			return 0;

		long multiplier = rating;
		if (isNegative(rating))
			multiplier *= 4L;

		return saturatedMultiply(evaluatorWeight, multiplier);
	}

	public static long saturatedMultiply(long left, long right) {
		if (left == 0L || right == 0L)
			return 0L;

		long result = left * right;
		if (result / right == left)
			return result;

		return (left > 0L) == (right > 0L) ? Long.MAX_VALUE : Long.MIN_VALUE;
	}

	private static int saturatedInt(long value) {
		if (value > Integer.MAX_VALUE)
			return Integer.MAX_VALUE;

		if (value < Integer.MIN_VALUE)
			return Integer.MIN_VALUE;

		return (int) value;
	}
}
