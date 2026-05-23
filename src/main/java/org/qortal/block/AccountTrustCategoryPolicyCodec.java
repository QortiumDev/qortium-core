package org.qortal.block;

import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustCategoryPoliciesData;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class AccountTrustCategoryPolicyCodec {

	public static final int ENCODED_LONG_COUNT = 30;
	public static final int ENCODED_LENGTH = ENCODED_LONG_COUNT * Long.BYTES;

	private static final AccountRatingCategory[] CATEGORY_ORDER = new AccountRatingCategory[] {
			AccountRatingCategory.SUBJECT,
			AccountRatingCategory.PLAYER,
			AccountRatingCategory.TRAINER,
			AccountRatingCategory.MANAGER
	};

	private AccountTrustCategoryPolicyCodec() {
	}

	public static byte[] encode(AccountTrustCategoryPoliciesData categoryPolicies, int suspiciousMinRaterCount) {
		validate(categoryPolicies, suspiciousMinRaterCount);

		Map<AccountRatingCategory, AccountTrustCategoryPoliciesData.CategoryPolicy> policiesByCategory =
				buildCategoryPolicyMap(categoryPolicies);
		ByteBuffer byteBuffer = ByteBuffer.allocate(ENCODED_LENGTH);

		for (AccountRatingCategory category : CATEGORY_ORDER) {
			AccountTrustCategoryPoliciesData.CategoryPolicy categoryPolicy = policiesByCategory.get(category);
			Map<Integer, AccountTrustCategoryPoliciesData.LevelPolicy> levelsByLevel =
					buildLevelPolicyMap(categoryPolicy, category);

			for (int level : requiredLevels(category)) {
				AccountTrustCategoryPoliciesData.LevelPolicy levelPolicy = levelsByLevel.get(level);
				byteBuffer.putLong(levelPolicy.getThreshold());
				byteBuffer.putLong(levelPolicy.getLevelScoreCap());
			}

			byteBuffer.putLong(categoryPolicy.getSuspiciousThreshold());
			byteBuffer.putLong(categoryPolicy.getSuspiciousLevelScoreCap());
		}

		return byteBuffer.array();
	}

	public static AccountTrustCategoryPoliciesData decode(byte[] value) {
		if (value == null || value.length != ENCODED_LENGTH)
			throw new IllegalArgumentException("Account trust category policy value must be 240 bytes");

		ByteBuffer byteBuffer = ByteBuffer.wrap(value);
		AccountTrustCategoryPoliciesData.CategoryPolicy[] categoryPolicies =
				new AccountTrustCategoryPoliciesData.CategoryPolicy[CATEGORY_ORDER.length];

		for (int i = 0; i < CATEGORY_ORDER.length; ++i) {
			AccountRatingCategory category = CATEGORY_ORDER[i];
			int[] requiredLevels = requiredLevels(category);
			AccountTrustCategoryPoliciesData.LevelPolicy[] levelPolicies =
					new AccountTrustCategoryPoliciesData.LevelPolicy[requiredLevels.length];

			for (int j = 0; j < requiredLevels.length; ++j) {
				long threshold = byteBuffer.getLong();
				long levelScoreCap = byteBuffer.getLong();
				levelPolicies[j] = new AccountTrustCategoryPoliciesData.LevelPolicy(requiredLevels[j],
						threshold, levelScoreCap);
			}

			long suspiciousThreshold = byteBuffer.getLong();
			long suspiciousLevelScoreCap = byteBuffer.getLong();
			categoryPolicies[i] = new AccountTrustCategoryPoliciesData.CategoryPolicy(category,
					Arrays.asList(levelPolicies), suspiciousThreshold, suspiciousLevelScoreCap);
		}

		return new AccountTrustCategoryPoliciesData(Arrays.asList(categoryPolicies));
	}

	public static void validate(AccountTrustCategoryPoliciesData categoryPolicies, int suspiciousMinRaterCount) {
		if (suspiciousMinRaterCount <= 0)
			throw new IllegalArgumentException("Suspicious rater count must be positive");

		Map<AccountRatingCategory, AccountTrustCategoryPoliciesData.CategoryPolicy> policiesByCategory =
				buildCategoryPolicyMap(categoryPolicies);

		for (AccountRatingCategory category : CATEGORY_ORDER)
			validateCategoryPolicy(policiesByCategory.get(category), category, suspiciousMinRaterCount);
	}

	public static boolean isValid(byte[] value, int suspiciousMinRaterCount) {
		try {
			validate(decode(value), suspiciousMinRaterCount);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static Map<AccountRatingCategory, AccountTrustCategoryPoliciesData.CategoryPolicy> buildCategoryPolicyMap(
			AccountTrustCategoryPoliciesData categoryPolicies) {
		if (categoryPolicies == null || categoryPolicies.getCategoryPolicies() == null)
			throw new IllegalArgumentException("Account trust category policies are missing");

		Map<AccountRatingCategory, AccountTrustCategoryPoliciesData.CategoryPolicy> policiesByCategory =
				new EnumMap<>(AccountRatingCategory.class);
		Set<AccountRatingCategory> requiredCategories = new HashSet<>(Arrays.asList(CATEGORY_ORDER));

		for (AccountTrustCategoryPoliciesData.CategoryPolicy categoryPolicy : categoryPolicies.getCategoryPolicies()) {
			if (categoryPolicy == null)
				throw new IllegalArgumentException("Account trust category policy is missing");

			AccountRatingCategory category = categoryPolicy.getCategory();
			if (category == null || !requiredCategories.contains(category))
				throw new IllegalArgumentException("Unexpected account trust category policy: " + category);

			if (policiesByCategory.put(category, categoryPolicy) != null)
				throw new IllegalArgumentException("Duplicate account trust category policy: " + category);
		}

		for (AccountRatingCategory requiredCategory : CATEGORY_ORDER)
			if (!policiesByCategory.containsKey(requiredCategory))
				throw new IllegalArgumentException("Missing account trust category policy: " + requiredCategory);

		return policiesByCategory;
	}

	private static void validateCategoryPolicy(AccountTrustCategoryPoliciesData.CategoryPolicy categoryPolicy,
			AccountRatingCategory category, int suspiciousMinRaterCount) {
		Map<Integer, AccountTrustCategoryPoliciesData.LevelPolicy> levelsByLevel =
				buildLevelPolicyMap(categoryPolicy, category);

		for (int level : requiredLevels(category))
			validateLevelPolicy(levelsByLevel.get(level), category);

		long suspiciousThreshold = categoryPolicy.getSuspiciousThreshold();
		if (suspiciousThreshold >= 0)
			throw new IllegalArgumentException("Account trust suspicious threshold must be negative for category "
					+ category);

		long suspiciousLevelScoreCap = categoryPolicy.getSuspiciousLevelScoreCap();
		BigInteger suspiciousRequiredScore = BigInteger.valueOf(suspiciousThreshold).negate();
		if (suspiciousLevelScoreCap <= 0
				|| BigInteger.valueOf(suspiciousLevelScoreCap).compareTo(suspiciousRequiredScore) >= 0)
			throw new IllegalArgumentException("Account trust suspicious cap must be positive and less than the "
					+ "threshold magnitude for category " + category);

		BigInteger maximumSuspiciousScore = BigInteger.valueOf(suspiciousLevelScoreCap)
				.multiply(BigInteger.valueOf(suspiciousMinRaterCount));
		if (maximumSuspiciousScore.compareTo(suspiciousRequiredScore) < 0)
			throw new IllegalArgumentException("Account trust suspicious cap and rater count cannot reach threshold "
					+ "for category " + category);
	}

	private static Map<Integer, AccountTrustCategoryPoliciesData.LevelPolicy> buildLevelPolicyMap(
			AccountTrustCategoryPoliciesData.CategoryPolicy categoryPolicy, AccountRatingCategory category) {
		if (categoryPolicy.getLevels() == null)
			throw new IllegalArgumentException("Account trust category policy is missing levels: " + category);

		Map<Integer, AccountTrustCategoryPoliciesData.LevelPolicy> levelsByLevel = new HashMap<>();
		Set<Integer> requiredLevels = new HashSet<>();
		for (int level : requiredLevels(category))
			requiredLevels.add(level);

		for (AccountTrustCategoryPoliciesData.LevelPolicy levelPolicy : categoryPolicy.getLevels()) {
			if (levelPolicy == null)
				throw new IllegalArgumentException("Account trust category policy contains a missing level: " + category);

			if (!requiredLevels.contains(levelPolicy.getLevel()))
				throw new IllegalArgumentException("Unexpected account trust level " + levelPolicy.getLevel()
						+ " for category " + category);

			if (levelsByLevel.put(levelPolicy.getLevel(), levelPolicy) != null)
				throw new IllegalArgumentException("Duplicate account trust level " + levelPolicy.getLevel()
						+ " for category " + category);
		}

		for (int requiredLevel : requiredLevels(category))
			if (!levelsByLevel.containsKey(requiredLevel))
				throw new IllegalArgumentException("Missing account trust level " + requiredLevel
						+ " for category " + category);

		return levelsByLevel;
	}

	private static void validateLevelPolicy(AccountTrustCategoryPoliciesData.LevelPolicy levelPolicy,
			AccountRatingCategory category) {
		if (levelPolicy.getThreshold() <= 0)
			throw new IllegalArgumentException("Account trust level threshold must be greater than 0 for category "
					+ category);

		if (levelPolicy.getLevelScoreCap() <= 0 || levelPolicy.getLevelScoreCap() >= levelPolicy.getThreshold())
			throw new IllegalArgumentException("Account trust level cap must be positive and less than the threshold "
					+ "for category " + category);
	}

	private static int[] requiredLevels(AccountRatingCategory category) {
		switch (category) {
			case MANAGER:
			case TRAINER:
				return new int[] { 1, 2 };

			case PLAYER:
				return new int[] { 1, 2, 3 };

			case SUBJECT:
			default:
				return new int[] { 1, 2, 3, 4 };
		}
	}
}
