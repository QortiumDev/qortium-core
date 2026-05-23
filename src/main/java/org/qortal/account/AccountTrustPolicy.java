package org.qortal.account;

import org.qortal.block.BlockChain;
import org.qortal.block.BlockChain.AccountTrustSettings;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustCategoryImpactData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AccountTrustPolicy {

	private AccountTrustPolicy() {
	}

	public static long getStartingEnergy() {
		return settings().getStartingEnergy();
	}

	public static long getStartingEnergy(Repository repository, int height) throws DataException {
		return BlockChain.getInstance().getAccountTrustStartingEnergy(repository, height);
	}

	public static int getManagerEnergyHops() {
		return settings().getManagerEnergyHops();
	}

	public static int getManagerEnergyHops(Repository repository, int height) throws DataException {
		return BlockChain.getInstance().getAccountTrustManagerEnergyHops(repository, height);
	}

	public static DecisionSettings getDecisionSettings() {
		AccountTrustSettings settings = settings();
		return new DecisionSettings(settings.getPositiveMinBranchCount(), settings.getSuspiciousMinRaterCount(),
				settings.getSuspiciousMinBranchCount(), settings.getSuspiciousMinRatingConfidence());
	}

	public static DecisionSettings getDecisionSettings(Repository repository, int height) throws DataException {
		return getDecisionSettings();
	}

	public static AccountRatingCategory getActiveWeightCategory() {
		return settings().getActiveWeightCategory();
	}

	public static int getVoteWeightPercent(AccountTrustStatus status) {
		return settings().getVoteWeightPercent(status);
	}

	public static int getVoteWeightPercent(Repository repository, int height, AccountTrustStatus status)
			throws DataException {
		return BlockChain.getInstance().getAccountTrustStatusVoteWeightPercent(repository, height, status);
	}

	public static int getVoteWeightPercent(int[] voteWeightPercents, AccountTrustStatus status) {
		return BlockChain.getAccountTrustStatusVoteWeightPercent(voteWeightPercents, status);
	}

	public static int[] getVoteWeightPercents() {
		return BlockChain.getInstance().getAccountTrustStatusVoteWeightPercents();
	}

	public static int[] getVoteWeightPercents(Repository repository, int height) throws DataException {
		return BlockChain.getInstance().getAccountTrustStatusVoteWeightPercents(repository, height);
	}

	public static int calculateEffectiveVoteWeight(int blocksMinted, AccountTrustStatus status) {
		int voteWeightPercent = getVoteWeightPercent(status);
		return calculateEffectiveVoteWeight(blocksMinted, voteWeightPercent);
	}

	public static int calculateEffectiveVoteWeight(Repository repository, int height, int blocksMinted,
			AccountTrustStatus status) throws DataException {
		int voteWeightPercent = getVoteWeightPercent(repository, height, status);
		return calculateEffectiveVoteWeight(blocksMinted, voteWeightPercent);
	}

	public static int calculateEffectiveVoteWeight(int[] voteWeightPercents, int blocksMinted, AccountTrustStatus status) {
		int voteWeightPercent = getVoteWeightPercent(voteWeightPercents, status);
		return calculateEffectiveVoteWeight(blocksMinted, voteWeightPercent);
	}

	public static int calculateEffectiveVoteWeight(int blocksMinted, int voteWeightPercent) {
		if (blocksMinted <= 0 || voteWeightPercent <= 0)
			return 0;

		long effectiveWeight = (long) blocksMinted * voteWeightPercent / 100;
		return effectiveWeight > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) effectiveWeight;
	}

	public static LevelDecision decideLevel(AccountRatingCategory category, long rawScore,
			List<AccountTrustCategoryImpactData> impacts) {
		return decideLevel(category, rawScore, impacts, getDecisionSettings());
	}

	public static LevelDecision decideLevel(AccountRatingCategory category, long rawScore,
			List<AccountTrustCategoryImpactData> impacts, DecisionSettings decisionSettings) {
		AccountRatingCategory effectiveCategory = effectiveCategory(category);
		List<AccountTrustCategoryImpactData> effectiveImpacts = effectiveImpacts(impacts);
		DecisionSettings effectiveDecisionSettings = effectiveDecisionSettings(decisionSettings);

		LevelDecision suspiciousDecision = suspiciousDecisionForCategory(effectiveCategory, effectiveImpacts);
		if (meetsSuspiciousRequirements(effectiveCategory, suspiciousDecision, effectiveImpacts,
				effectiveDecisionSettings))
			return suspiciousDecision;

		if (rawScore < 0)
			return zeroLevelDecision(effectiveCategory, effectiveImpacts);

		switch (effectiveCategory) {
			case MANAGER:
				return calculateManagerLevel(effectiveImpacts, effectiveDecisionSettings);

			case TRAINER:
				return calculateTrainerLevel(effectiveImpacts, effectiveDecisionSettings);

			case PLAYER:
				return calculatePlayerLevel(effectiveImpacts, effectiveDecisionSettings);

			case SUBJECT:
			default:
				return calculateSubjectLevel(effectiveImpacts, effectiveDecisionSettings);
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
		return settings().getLevelThreshold(effectiveCategory(category), level);
	}

	public static long getLevelScoreCap(AccountRatingCategory category, int level) {
		return settings().getLevelScoreCap(effectiveCategory(category), level);
	}

	public static long getSuspiciousLevelScoreCap(AccountRatingCategory category) {
		return settings().getSuspiciousLevelScoreCap(effectiveCategory(category));
	}

	public static long getSuspiciousThreshold(AccountRatingCategory category) {
		return settings().getSuspiciousThreshold(effectiveCategory(category));
	}

	public static int getSuspiciousMinRaterCount() {
		return getDecisionSettings().getSuspiciousMinRaterCount();
	}

	public static int getPositiveMinBranchCount() {
		return getDecisionSettings().getPositiveMinBranchCount();
	}

	public static int getSuspiciousMinBranchCount() {
		return getDecisionSettings().getSuspiciousMinBranchCount();
	}

	public static int getSuspiciousMinRatingConfidence() {
		return getDecisionSettings().getSuspiciousMinRatingConfidence();
	}

	public static int getAccountRatingChangeCooldownBlocks() {
		return BlockChain.getInstance().getAccountRatingChangeCooldownBlocks();
	}

	public static int getAccountRatingChangeCooldownBlocks(Repository repository, int height) throws DataException {
		return BlockChain.getInstance().getAccountRatingChangeCooldownBlocks(repository, height);
	}

	private static LevelDecision calculateManagerLevel(List<AccountTrustCategoryImpactData> impacts,
			DecisionSettings decisionSettings) {
		LevelDecision level2 = decisionForLevel(AccountRatingCategory.MANAGER, 2, impacts);
		if (level2.levelScore >= getLevelThreshold(AccountRatingCategory.MANAGER, 2)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings))
			return level2;

		LevelDecision level1 = decisionForLevel(AccountRatingCategory.MANAGER, 1, impacts);
		if (level1.levelScore >= getLevelThreshold(AccountRatingCategory.MANAGER, 1)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings))
			return level1;

		return zeroLevelDecision(AccountRatingCategory.MANAGER, impacts);
	}

	private static LevelDecision calculateTrainerLevel(List<AccountTrustCategoryImpactData> impacts,
			DecisionSettings decisionSettings) {
		LevelDecision level2 = decisionForLevel(AccountRatingCategory.TRAINER, 2, impacts);
		if (level2.levelScore >= getLevelThreshold(AccountRatingCategory.TRAINER, 2)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings))
			return level2;

		LevelDecision level1 = decisionForLevel(AccountRatingCategory.TRAINER, 1, impacts);
		if (level1.levelScore >= getLevelThreshold(AccountRatingCategory.TRAINER, 1)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings))
			return level1;

		return zeroLevelDecision(AccountRatingCategory.TRAINER, impacts);
	}

	private static LevelDecision calculatePlayerLevel(List<AccountTrustCategoryImpactData> impacts,
			DecisionSettings decisionSettings) {
		LevelDecision level3 = decisionForLevel(AccountRatingCategory.PLAYER, 3, impacts);
		if (level3.levelScore >= getLevelThreshold(AccountRatingCategory.PLAYER, 3)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings)
				&& (hasImpact(impacts, 2, 3) || countImpacts(impacts, 2, 2) >= 2))
			return level3;

		LevelDecision level2 = decisionForLevel(AccountRatingCategory.PLAYER, 2, impacts);
		if (level2.levelScore >= getLevelThreshold(AccountRatingCategory.PLAYER, 2)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings) && hasImpact(impacts, 1, 2))
			return level2;

		LevelDecision level1 = decisionForLevel(AccountRatingCategory.PLAYER, 1, impacts);
		if (level1.levelScore >= getLevelThreshold(AccountRatingCategory.PLAYER, 1)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings))
			return level1;

		return zeroLevelDecision(AccountRatingCategory.PLAYER, impacts);
	}

	private static LevelDecision calculateSubjectLevel(List<AccountTrustCategoryImpactData> impacts,
			DecisionSettings decisionSettings) {
		LevelDecision level4 = decisionForLevel(AccountRatingCategory.SUBJECT, 4, impacts);
		if (level4.levelScore >= getLevelThreshold(AccountRatingCategory.SUBJECT, 4)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings)
				&& (hasImpact(impacts, 3, 3) || countImpacts(impacts, 3, 2) >= 2))
			return level4;

		LevelDecision level3 = decisionForLevel(AccountRatingCategory.SUBJECT, 3, impacts);
		if (level3.levelScore >= getLevelThreshold(AccountRatingCategory.SUBJECT, 3)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings)
				&& (hasImpact(impacts, 2, 3) || countImpacts(impacts, 2, 2) >= 2))
			return level3;

		LevelDecision level2 = decisionForLevel(AccountRatingCategory.SUBJECT, 2, impacts);
		if (level2.levelScore >= getLevelThreshold(AccountRatingCategory.SUBJECT, 2)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings) && hasImpact(impacts, 1, 2))
			return level2;

		LevelDecision level1 = decisionForLevel(AccountRatingCategory.SUBJECT, 1, impacts);
		if (level1.levelScore >= getLevelThreshold(AccountRatingCategory.SUBJECT, 1)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings) && hasImpact(impacts, 1, 1))
			return level1;

		return zeroLevelDecision(AccountRatingCategory.SUBJECT, impacts);
	}

	private static LevelDecision zeroLevelDecision(AccountRatingCategory category,
			List<AccountTrustCategoryImpactData> impacts) {
		long levelScoreCap = getLevelScoreCap(category, 1);
		return new LevelDecision(0, calculateCappedLevelScore(impacts, levelScoreCap), levelScoreCap);
	}

	private static LevelDecision decisionForLevel(AccountRatingCategory category, int level,
			List<AccountTrustCategoryImpactData> impacts) {
		long levelScoreCap = getLevelScoreCap(category, level);
		return new LevelDecision(level, calculateCappedLevelScore(impacts, levelScoreCap), levelScoreCap);
	}

	private static LevelDecision suspiciousDecisionForCategory(AccountRatingCategory category,
			List<AccountTrustCategoryImpactData> impacts) {
		long levelScoreCap = getSuspiciousLevelScoreCap(category);
		return new LevelDecision(-1, calculateCappedLevelScore(impacts, levelScoreCap), levelScoreCap);
	}

	private static boolean meetsSuspiciousRequirements(AccountRatingCategory category, LevelDecision suspiciousDecision,
			List<AccountTrustCategoryImpactData> impacts, DecisionSettings decisionSettings) {
		return suspiciousDecision.levelScore <= getSuspiciousThreshold(category)
				&& countNegativeRaters(impacts, decisionSettings.getSuspiciousMinRatingConfidence())
						>= decisionSettings.getSuspiciousMinRaterCount()
				&& countNegativeTrustBranches(impacts, decisionSettings.getSuspiciousMinRatingConfidence())
						>= decisionSettings.getSuspiciousMinBranchCount();
	}

	private static boolean meetsPositiveBranchRequirement(List<AccountTrustCategoryImpactData> impacts,
			DecisionSettings decisionSettings) {
		return countPositiveTrustBranches(impacts) >= decisionSettings.getPositiveMinBranchCount();
	}

	private static long calculateCappedLevelScore(List<AccountTrustCategoryImpactData> impacts, long impactCap) {
		long levelScore = 0L;

		for (AccountTrustCategoryImpactData impact : impacts) {
			long impactValue = impact.getImpact();
			if (impactValue > impactCap)
				impactValue = impactCap;
			else if (impactValue < -impactCap)
				impactValue = -impactCap;

			levelScore = saturatedAdd(levelScore, impactValue);
		}

		return levelScore;
	}

	private static boolean hasImpact(List<AccountTrustCategoryImpactData> impacts, int minLevel, int minConfidence) {
		return impacts.stream().anyMatch(impact -> impact.getEvaluatorLevel() >= minLevel
				&& impact.getRatingConfidence() >= minConfidence && impact.getImpact() > 0);
	}

	private static long countImpacts(List<AccountTrustCategoryImpactData> impacts, int minLevel, int minConfidence) {
		return impacts.stream().filter(impact -> impact.getEvaluatorLevel() >= minLevel
				&& impact.getRatingConfidence() >= minConfidence && impact.getImpact() > 0).count();
	}

	private static long countPositiveTrustBranches(List<AccountTrustCategoryImpactData> impacts) {
		Set<String> trustBranchKeys = new HashSet<>();
		for (AccountTrustCategoryImpactData impact : impacts) {
			if (impact.getImpact() <= 0)
				continue;

			if (impact.getTrustBranchKeys() != null)
				trustBranchKeys.addAll(impact.getTrustBranchKeys());
		}

		return trustBranchKeys.size();
	}

	private static long countNegativeRaters(List<AccountTrustCategoryImpactData> impacts, int minConfidence) {
		return impacts.stream()
				.filter(impact -> impact.getRatingConfidence() >= minConfidence && impact.getImpact() < 0)
				.map(AccountTrustCategoryImpactData::getRaterAddress)
				.distinct()
				.count();
	}

	private static long countNegativeTrustBranches(List<AccountTrustCategoryImpactData> impacts, int minConfidence) {
		Set<String> trustBranchKeys = new HashSet<>();
		for (AccountTrustCategoryImpactData impact : impacts) {
			if (impact.getRatingConfidence() < minConfidence || impact.getImpact() >= 0)
				continue;

			if (impact.getTrustBranchKeys() != null)
				trustBranchKeys.addAll(impact.getTrustBranchKeys());
		}

		return trustBranchKeys.size();
	}

	private static AccountRatingCategory effectiveCategory(AccountRatingCategory category) {
		return category == null ? AccountRatingCategory.SUBJECT : category;
	}

	private static AccountTrustSettings settings() {
		return BlockChain.getInstance().getAccountTrustSettings();
	}

	private static List<AccountTrustCategoryImpactData> effectiveImpacts(
			List<AccountTrustCategoryImpactData> impacts) {
		return impacts == null ? Collections.emptyList() : impacts;
	}

	private static DecisionSettings effectiveDecisionSettings(DecisionSettings decisionSettings) {
		return decisionSettings == null ? getDecisionSettings() : decisionSettings;
	}

	private static long saturatedAdd(long left, long right) {
		long result = left + right;
		if (((left ^ result) & (right ^ result)) < 0)
			return right < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;

		return result;
	}

	public static final class DecisionSettings {
		private final int positiveMinBranchCount;
		private final int suspiciousMinRaterCount;
		private final int suspiciousMinBranchCount;
		private final int suspiciousMinRatingConfidence;

		public DecisionSettings(int positiveMinBranchCount, int suspiciousMinRaterCount,
				int suspiciousMinBranchCount, int suspiciousMinRatingConfidence) {
			this.positiveMinBranchCount = positiveMinBranchCount;
			this.suspiciousMinRaterCount = suspiciousMinRaterCount;
			this.suspiciousMinBranchCount = suspiciousMinBranchCount;
			this.suspiciousMinRatingConfidence = suspiciousMinRatingConfidence;
		}

		public int getPositiveMinBranchCount() {
			return this.positiveMinBranchCount;
		}

		public int getSuspiciousMinRaterCount() {
			return this.suspiciousMinRaterCount;
		}

		public int getSuspiciousMinBranchCount() {
			return this.suspiciousMinBranchCount;
		}

		public int getSuspiciousMinRatingConfidence() {
			return this.suspiciousMinRatingConfidence;
		}
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
