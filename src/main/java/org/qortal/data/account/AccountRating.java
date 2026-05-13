package org.qortal.data.account;

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
		if (!isActive(rating) || evaluatorWeight <= 0)
			return 0;

		long impact = (long) evaluatorWeight * rating;
		if (isNegative(rating))
			impact *= 4L;

		if (impact > Integer.MAX_VALUE)
			return Integer.MAX_VALUE;

		if (impact < Integer.MIN_VALUE)
			return Integer.MIN_VALUE;

		return (int) impact;
	}
}
