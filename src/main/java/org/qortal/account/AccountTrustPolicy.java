package org.qortal.account;

import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustPreviewData;
import org.qortal.data.account.AccountTrustStatus;

import java.util.Collections;
import java.util.List;

public final class AccountTrustPolicy {

	public static final long STARTING_ENERGY = 1_000_000L;
	public static final int MANAGER_ENERGY_HOPS = 4;
	public static final AccountRatingCategory ACTIVE_WEIGHT_CATEGORY = AccountRatingCategory.SUBJECT;

	private static final int SUSPICIOUS_MIN_RATER_COUNT = 2;
	private static final int SUSPICIOUS_MIN_RATING_CONFIDENCE = 2;

	private AccountTrustPolicy() {
	}

	public static LevelDecision decideLevel(AccountRatingCategory category, long rawScore,
			List<AccountTrustPreviewData.CategoryImpact> impacts) {
		AccountRatingCategory effectiveCategory = effectiveCategory(category);
		List<AccountTrustPreviewData.CategoryImpact> effectiveImpacts = effectiveImpacts(impacts);

		LevelDecision suspiciousDecision = suspiciousDecisionForCategory(effectiveCategory, effectiveImpacts);
		if (meetsSuspiciousRequirements(effectiveCategory, suspiciousDecision, effectiveImpacts))
			return suspiciousDecision;

		if (rawScore < 0)
			return zeroLevelDecision(effectiveCategory, effectiveImpacts);

		switch (effectiveCategory) {
			case MANAGER:
				return calculateManagerLevel(effectiveImpacts);

			case TRAINER:
				return calculateTrainerLevel(effectiveImpacts);

			case PLAYER:
				return calculatePlayerLevel(effectiveImpacts);

			case SUBJECT:
			default:
				return calculateSubjectLevel(effectiveImpacts);
		}
	}

	public static AccountTrustStatus mapLevelToStatus(int level) {
		if (level < 0)
			return AccountTrustStatus.SUSPICIOUS;
		if (level >= 3)
			return AccountTrustStatus.GOLD;
		if (level == 2)
			return AccountTrustStatus.SILVER;
		if (level == 1)
			return AccountTrustStatus.BRONZE;
		return AccountTrustStatus.UNVERIFIED;
	}

	public static long getLevelThreshold(AccountRatingCategory category, int level) {
		switch (effectiveCategory(category)) {
			case MANAGER:
				return level == 2 ? 200_000L : 1_000L;

			case TRAINER:
				return level == 2 ? 1_000_000L : 500_000L;

			case PLAYER:
				if (level == 3)
					return 3_000_000L;
				return level == 2 ? 2_000_000L : 1_000_000L;

			case SUBJECT:
			default:
				if (level == 4)
					return 150_000_000L;
				if (level == 3)
					return 100_000_000L;
				return level == 2 ? 50_000_000L : 10_000_000L;
		}
	}

	public static long getLevelScoreCap(AccountRatingCategory category, int level) {
		return getLevelThreshold(category, level) / 2L;
	}

	public static long getSuspiciousLevelScoreCap(AccountRatingCategory category) {
		return getLevelScoreCap(category, 1);
	}

	public static long getSuspiciousThreshold(AccountRatingCategory category) {
		return -getLevelThreshold(category, 1);
	}

	public static int getSuspiciousMinRaterCount() {
		return SUSPICIOUS_MIN_RATER_COUNT;
	}

	public static int getSuspiciousMinRatingConfidence() {
		return SUSPICIOUS_MIN_RATING_CONFIDENCE;
	}

	private static LevelDecision calculateManagerLevel(List<AccountTrustPreviewData.CategoryImpact> impacts) {
		LevelDecision level2 = decisionForLevel(AccountRatingCategory.MANAGER, 2, impacts);
		if (level2.levelScore >= getLevelThreshold(AccountRatingCategory.MANAGER, 2))
			return level2;

		LevelDecision level1 = decisionForLevel(AccountRatingCategory.MANAGER, 1, impacts);
		if (level1.levelScore >= getLevelThreshold(AccountRatingCategory.MANAGER, 1))
			return level1;

		return zeroLevelDecision(AccountRatingCategory.MANAGER, impacts);
	}

	private static LevelDecision calculateTrainerLevel(List<AccountTrustPreviewData.CategoryImpact> impacts) {
		LevelDecision level2 = decisionForLevel(AccountRatingCategory.TRAINER, 2, impacts);
		if (level2.levelScore >= getLevelThreshold(AccountRatingCategory.TRAINER, 2))
			return level2;

		LevelDecision level1 = decisionForLevel(AccountRatingCategory.TRAINER, 1, impacts);
		if (level1.levelScore >= getLevelThreshold(AccountRatingCategory.TRAINER, 1))
			return level1;

		return zeroLevelDecision(AccountRatingCategory.TRAINER, impacts);
	}

	private static LevelDecision calculatePlayerLevel(List<AccountTrustPreviewData.CategoryImpact> impacts) {
		LevelDecision level3 = decisionForLevel(AccountRatingCategory.PLAYER, 3, impacts);
		if (level3.levelScore >= getLevelThreshold(AccountRatingCategory.PLAYER, 3)
				&& (hasImpact(impacts, 2, 3) || countImpacts(impacts, 2, 2) >= 2))
			return level3;

		LevelDecision level2 = decisionForLevel(AccountRatingCategory.PLAYER, 2, impacts);
		if (level2.levelScore >= getLevelThreshold(AccountRatingCategory.PLAYER, 2) && hasImpact(impacts, 1, 2))
			return level2;

		LevelDecision level1 = decisionForLevel(AccountRatingCategory.PLAYER, 1, impacts);
		if (level1.levelScore >= getLevelThreshold(AccountRatingCategory.PLAYER, 1))
			return level1;

		return zeroLevelDecision(AccountRatingCategory.PLAYER, impacts);
	}

	private static LevelDecision calculateSubjectLevel(List<AccountTrustPreviewData.CategoryImpact> impacts) {
		LevelDecision level4 = decisionForLevel(AccountRatingCategory.SUBJECT, 4, impacts);
		if (level4.levelScore >= getLevelThreshold(AccountRatingCategory.SUBJECT, 4)
				&& (hasImpact(impacts, 3, 3) || countImpacts(impacts, 3, 2) >= 2))
			return level4;

		LevelDecision level3 = decisionForLevel(AccountRatingCategory.SUBJECT, 3, impacts);
		if (level3.levelScore >= getLevelThreshold(AccountRatingCategory.SUBJECT, 3)
				&& (hasImpact(impacts, 2, 3) || countImpacts(impacts, 2, 2) >= 2))
			return level3;

		LevelDecision level2 = decisionForLevel(AccountRatingCategory.SUBJECT, 2, impacts);
		if (level2.levelScore >= getLevelThreshold(AccountRatingCategory.SUBJECT, 2) && hasImpact(impacts, 1, 2))
			return level2;

		LevelDecision level1 = decisionForLevel(AccountRatingCategory.SUBJECT, 1, impacts);
		if (level1.levelScore >= getLevelThreshold(AccountRatingCategory.SUBJECT, 1) && hasImpact(impacts, 1, 1))
			return level1;

		return zeroLevelDecision(AccountRatingCategory.SUBJECT, impacts);
	}

	private static LevelDecision zeroLevelDecision(AccountRatingCategory category,
			List<AccountTrustPreviewData.CategoryImpact> impacts) {
		long levelScoreCap = getLevelScoreCap(category, 1);
		return new LevelDecision(0, calculateCappedLevelScore(impacts, levelScoreCap), levelScoreCap);
	}

	private static LevelDecision decisionForLevel(AccountRatingCategory category, int level,
			List<AccountTrustPreviewData.CategoryImpact> impacts) {
		long levelScoreCap = getLevelScoreCap(category, level);
		return new LevelDecision(level, calculateCappedLevelScore(impacts, levelScoreCap), levelScoreCap);
	}

	private static LevelDecision suspiciousDecisionForCategory(AccountRatingCategory category,
			List<AccountTrustPreviewData.CategoryImpact> impacts) {
		long levelScoreCap = getSuspiciousLevelScoreCap(category);
		return new LevelDecision(-1, calculateCappedLevelScore(impacts, levelScoreCap), levelScoreCap);
	}

	private static boolean meetsSuspiciousRequirements(AccountRatingCategory category, LevelDecision suspiciousDecision,
			List<AccountTrustPreviewData.CategoryImpact> impacts) {
		return suspiciousDecision.levelScore <= getSuspiciousThreshold(category)
				&& countNegativeImpacts(impacts, SUSPICIOUS_MIN_RATING_CONFIDENCE) >= SUSPICIOUS_MIN_RATER_COUNT;
	}

	private static long calculateCappedLevelScore(List<AccountTrustPreviewData.CategoryImpact> impacts, long impactCap) {
		long levelScore = 0L;

		for (AccountTrustPreviewData.CategoryImpact impact : impacts) {
			long impactValue = impact.getImpact();
			if (impactValue > impactCap)
				impactValue = impactCap;
			else if (impactValue < -impactCap)
				impactValue = -impactCap;

			levelScore = saturatedAdd(levelScore, impactValue);
		}

		return levelScore;
	}

	private static boolean hasImpact(List<AccountTrustPreviewData.CategoryImpact> impacts, int minLevel, int minConfidence) {
		return impacts.stream().anyMatch(impact -> impact.getEvaluatorLevel() >= minLevel
				&& impact.getRatingConfidence() >= minConfidence && impact.getImpact() > 0);
	}

	private static long countImpacts(List<AccountTrustPreviewData.CategoryImpact> impacts, int minLevel, int minConfidence) {
		return impacts.stream().filter(impact -> impact.getEvaluatorLevel() >= minLevel
				&& impact.getRatingConfidence() >= minConfidence && impact.getImpact() > 0).count();
	}

	private static long countNegativeImpacts(List<AccountTrustPreviewData.CategoryImpact> impacts, int minConfidence) {
		return impacts.stream()
				.filter(impact -> impact.getRatingConfidence() >= minConfidence && impact.getImpact() < 0)
				.map(AccountTrustPreviewData.CategoryImpact::getRaterAddress)
				.distinct()
				.count();
	}

	private static AccountRatingCategory effectiveCategory(AccountRatingCategory category) {
		return category == null ? AccountRatingCategory.SUBJECT : category;
	}

	private static List<AccountTrustPreviewData.CategoryImpact> effectiveImpacts(
			List<AccountTrustPreviewData.CategoryImpact> impacts) {
		return impacts == null ? Collections.emptyList() : impacts;
	}

	private static long saturatedAdd(long left, long right) {
		long result = left + right;
		if (((left ^ result) & (right ^ result)) < 0)
			return right < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;

		return result;
	}

	public static final class LevelDecision {
		private final int level;
		private final long levelScore;
		private final long levelScoreCap;

		private LevelDecision(int level, long levelScore, long levelScoreCap) {
			this.level = level;
			this.levelScore = levelScore;
			this.levelScoreCap = levelScoreCap;
		}

		public int getLevel() {
			return this.level;
		}

		public long getLevelScore() {
			return this.levelScore;
		}

		public long getLevelScoreCap() {
			return this.levelScoreCap;
		}
	}
}
