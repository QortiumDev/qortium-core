package org.qortal.account;

import org.qortal.block.BlockChain;
import org.qortal.block.BlockChain.AccountTrustCategoryPolicy;
import org.qortal.block.BlockChain.AccountTrustLevelPolicy;
import org.qortal.block.BlockChain.AccountTrustSettings;
import org.qortal.block.AccountTrustCategoryPolicyCodec;
import org.qortal.block.ChainParameter;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustCategoryPoliciesData;
import org.qortal.data.account.AccountTrustCategoryImpactData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.blockchain.ChainParameterData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import java.math.BigInteger;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
		return new DecisionSettings(BlockChain.getInstance().getAccountTrustPositiveMinBranchCount(repository, height),
				BlockChain.getInstance().getAccountTrustSuspiciousMinRaterCount(repository, height),
				BlockChain.getInstance().getAccountTrustSuspiciousMinBranchCount(repository, height),
				BlockChain.getInstance().getAccountTrustSuspiciousMinRatingConfidence(repository, height));
	}

	public static CategoryPolicySettings getCategoryPolicySettings() {
		return CategoryPolicySettings.from(settings());
	}

	public static CategoryPolicySettings getCategoryPolicySettings(Repository repository, int height)
			throws DataException {
		ChainParameterData categoryPoliciesUpdate = repository.getChainParameterRepository()
				.getEffectiveParameter(ChainParameter.ACCOUNT_TRUST_CATEGORY_POLICIES.id, height);
		if (categoryPoliciesUpdate != null)
			return CategoryPolicySettings.from(AccountTrustCategoryPolicyCodec.decode(categoryPoliciesUpdate.getValue()));

		return getCategoryPolicySettings();
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
		return decideLevel(category, rawScore, impacts, getDecisionSettings(), getCategoryPolicySettings());
	}

	public static LevelDecision decideLevel(AccountRatingCategory category, long rawScore,
			List<AccountTrustCategoryImpactData> impacts, DecisionSettings decisionSettings) {
		return decideLevel(category, rawScore, impacts, decisionSettings, getCategoryPolicySettings());
	}

	public static LevelDecision decideLevel(AccountRatingCategory category, long rawScore,
			List<AccountTrustCategoryImpactData> impacts, DecisionSettings decisionSettings,
			CategoryPolicySettings categoryPolicySettings) {
		AccountRatingCategory effectiveCategory = effectiveCategory(category);
		List<AccountTrustCategoryImpactData> effectiveImpacts = effectiveImpacts(impacts);
		DecisionSettings effectiveDecisionSettings = effectiveDecisionSettings(decisionSettings);
		CategoryPolicySettings effectiveCategoryPolicySettings = effectiveCategoryPolicySettings(categoryPolicySettings);

		LevelDecision suspiciousDecision = suspiciousDecisionForCategory(effectiveCategory, effectiveImpacts,
				effectiveCategoryPolicySettings);
		if (meetsSuspiciousRequirements(effectiveCategory, suspiciousDecision, effectiveImpacts,
				effectiveDecisionSettings, effectiveCategoryPolicySettings))
			return suspiciousDecision;

		if (rawScore < 0)
			return zeroLevelDecision(effectiveCategory, effectiveImpacts, effectiveCategoryPolicySettings);

		switch (effectiveCategory) {
			case MANAGER:
				return calculateManagerLevel(effectiveImpacts, effectiveDecisionSettings, effectiveCategoryPolicySettings);

			case TRAINER:
				return calculateTrainerLevel(effectiveImpacts, effectiveDecisionSettings, effectiveCategoryPolicySettings);

			case PLAYER:
				return calculatePlayerLevel(effectiveImpacts, effectiveDecisionSettings, effectiveCategoryPolicySettings);

			case SUBJECT:
			default:
				return calculateSubjectLevel(effectiveImpacts, effectiveDecisionSettings, effectiveCategoryPolicySettings);
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
		return getCategoryPolicySettings().getLevelThreshold(category, level);
	}

	public static long getLevelScoreCap(AccountRatingCategory category, int level) {
		return getCategoryPolicySettings().getLevelScoreCap(category, level);
	}

	public static long getSuspiciousLevelScoreCap(AccountRatingCategory category) {
		return getCategoryPolicySettings().getSuspiciousLevelScoreCap(category);
	}

	public static long getSuspiciousThreshold(AccountRatingCategory category) {
		return getCategoryPolicySettings().getSuspiciousThreshold(category);
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
			DecisionSettings decisionSettings, CategoryPolicySettings categoryPolicySettings) {
		LevelDecision level2 = decisionForLevel(AccountRatingCategory.MANAGER, 2, impacts, categoryPolicySettings);
		if (level2.levelScore >= categoryPolicySettings.getLevelThreshold(AccountRatingCategory.MANAGER, 2)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings))
			return level2;

		LevelDecision level1 = decisionForLevel(AccountRatingCategory.MANAGER, 1, impacts, categoryPolicySettings);
		if (level1.levelScore >= categoryPolicySettings.getLevelThreshold(AccountRatingCategory.MANAGER, 1)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings))
			return level1;

		return zeroLevelDecision(AccountRatingCategory.MANAGER, impacts, categoryPolicySettings);
	}

	private static LevelDecision calculateTrainerLevel(List<AccountTrustCategoryImpactData> impacts,
			DecisionSettings decisionSettings, CategoryPolicySettings categoryPolicySettings) {
		LevelDecision level2 = decisionForLevel(AccountRatingCategory.TRAINER, 2, impacts, categoryPolicySettings);
		if (level2.levelScore >= categoryPolicySettings.getLevelThreshold(AccountRatingCategory.TRAINER, 2)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings))
			return level2;

		LevelDecision level1 = decisionForLevel(AccountRatingCategory.TRAINER, 1, impacts, categoryPolicySettings);
		if (level1.levelScore >= categoryPolicySettings.getLevelThreshold(AccountRatingCategory.TRAINER, 1)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings))
			return level1;

		return zeroLevelDecision(AccountRatingCategory.TRAINER, impacts, categoryPolicySettings);
	}

	private static LevelDecision calculatePlayerLevel(List<AccountTrustCategoryImpactData> impacts,
			DecisionSettings decisionSettings, CategoryPolicySettings categoryPolicySettings) {
		LevelDecision level3 = decisionForLevel(AccountRatingCategory.PLAYER, 3, impacts, categoryPolicySettings);
		if (level3.levelScore >= categoryPolicySettings.getLevelThreshold(AccountRatingCategory.PLAYER, 3)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings)
				&& (hasImpact(impacts, 2, 3) || countImpacts(impacts, 2, 2) >= 2))
			return level3;

		LevelDecision level2 = decisionForLevel(AccountRatingCategory.PLAYER, 2, impacts, categoryPolicySettings);
		if (level2.levelScore >= categoryPolicySettings.getLevelThreshold(AccountRatingCategory.PLAYER, 2)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings) && hasImpact(impacts, 1, 2))
			return level2;

		LevelDecision level1 = decisionForLevel(AccountRatingCategory.PLAYER, 1, impacts, categoryPolicySettings);
		if (level1.levelScore >= categoryPolicySettings.getLevelThreshold(AccountRatingCategory.PLAYER, 1)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings))
			return level1;

		return zeroLevelDecision(AccountRatingCategory.PLAYER, impacts, categoryPolicySettings);
	}

	private static LevelDecision calculateSubjectLevel(List<AccountTrustCategoryImpactData> impacts,
			DecisionSettings decisionSettings, CategoryPolicySettings categoryPolicySettings) {
		LevelDecision level4 = decisionForLevel(AccountRatingCategory.SUBJECT, 4, impacts, categoryPolicySettings);
		if (level4.levelScore >= categoryPolicySettings.getLevelThreshold(AccountRatingCategory.SUBJECT, 4)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings)
				&& (hasImpact(impacts, 3, 3) || countImpacts(impacts, 3, 2) >= 2))
			return level4;

		LevelDecision level3 = decisionForLevel(AccountRatingCategory.SUBJECT, 3, impacts, categoryPolicySettings);
		if (level3.levelScore >= categoryPolicySettings.getLevelThreshold(AccountRatingCategory.SUBJECT, 3)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings)
				&& (hasImpact(impacts, 2, 3) || countImpacts(impacts, 2, 2) >= 2))
			return level3;

		LevelDecision level2 = decisionForLevel(AccountRatingCategory.SUBJECT, 2, impacts, categoryPolicySettings);
		if (level2.levelScore >= categoryPolicySettings.getLevelThreshold(AccountRatingCategory.SUBJECT, 2)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings) && hasImpact(impacts, 1, 2))
			return level2;

		LevelDecision level1 = decisionForLevel(AccountRatingCategory.SUBJECT, 1, impacts, categoryPolicySettings);
		if (level1.levelScore >= categoryPolicySettings.getLevelThreshold(AccountRatingCategory.SUBJECT, 1)
				&& meetsPositiveBranchRequirement(impacts, decisionSettings) && hasImpact(impacts, 1, 1))
			return level1;

		return zeroLevelDecision(AccountRatingCategory.SUBJECT, impacts, categoryPolicySettings);
	}

	private static LevelDecision zeroLevelDecision(AccountRatingCategory category,
			List<AccountTrustCategoryImpactData> impacts, CategoryPolicySettings categoryPolicySettings) {
		long levelScoreCap = categoryPolicySettings.getLevelScoreCap(category, 1);
		return new LevelDecision(0, calculateCappedLevelScore(impacts, levelScoreCap), levelScoreCap);
	}

	private static LevelDecision decisionForLevel(AccountRatingCategory category, int level,
			List<AccountTrustCategoryImpactData> impacts, CategoryPolicySettings categoryPolicySettings) {
		long levelScoreCap = categoryPolicySettings.getLevelScoreCap(category, level);
		return new LevelDecision(level, calculateCappedLevelScore(impacts, levelScoreCap), levelScoreCap);
	}

	private static LevelDecision suspiciousDecisionForCategory(AccountRatingCategory category,
			List<AccountTrustCategoryImpactData> impacts, CategoryPolicySettings categoryPolicySettings) {
		long levelScoreCap = categoryPolicySettings.getSuspiciousLevelScoreCap(category);
		return new LevelDecision(-1, calculateCappedLevelScore(impacts, levelScoreCap), levelScoreCap);
	}

	private static boolean meetsSuspiciousRequirements(AccountRatingCategory category, LevelDecision suspiciousDecision,
			List<AccountTrustCategoryImpactData> impacts, DecisionSettings decisionSettings,
			CategoryPolicySettings categoryPolicySettings) {
		return suspiciousDecision.levelScore <= categoryPolicySettings.getSuspiciousThreshold(category)
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

	private static CategoryPolicySettings effectiveCategoryPolicySettings(CategoryPolicySettings categoryPolicySettings) {
		return categoryPolicySettings == null ? getCategoryPolicySettings() : categoryPolicySettings;
	}

	private static long saturatedAdd(long left, long right) {
		long result = left + right;
		if (((left ^ result) & (right ^ result)) < 0)
			return right < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;

		return result;
	}

	public static final class CategoryPolicySettings {
		private final Map<AccountRatingCategory, CategoryPolicy> policiesByCategory;

		private CategoryPolicySettings(Map<AccountRatingCategory, CategoryPolicy> policiesByCategory) {
			this.policiesByCategory = Collections.unmodifiableMap(new EnumMap<>(policiesByCategory));
		}

		private static CategoryPolicySettings from(AccountTrustSettings settings) {
			Map<AccountRatingCategory, CategoryPolicy> policiesByCategory = new EnumMap<>(AccountRatingCategory.class);
			for (AccountTrustCategoryPolicy categoryPolicy : settings.categoryPolicies)
				policiesByCategory.put(effectiveCategory(categoryPolicy.category), CategoryPolicy.from(categoryPolicy));

			return new CategoryPolicySettings(policiesByCategory);
		}

		private static CategoryPolicySettings from(AccountTrustCategoryPoliciesData categoryPolicies) {
			Map<AccountRatingCategory, CategoryPolicy> policiesByCategory = new EnumMap<>(AccountRatingCategory.class);
			for (AccountTrustCategoryPoliciesData.CategoryPolicy categoryPolicy : categoryPolicies.getCategoryPolicies())
				policiesByCategory.put(effectiveCategory(categoryPolicy.getCategory()), CategoryPolicy.from(categoryPolicy));

			return new CategoryPolicySettings(policiesByCategory);
		}

		public long getLevelThreshold(AccountRatingCategory category, int level) {
			return getCategoryPolicy(category).getLevelThreshold(level);
		}

		public long getLevelScoreCap(AccountRatingCategory category, int level) {
			return getCategoryPolicy(category).getLevelScoreCap(level);
		}

		public long getSuspiciousThreshold(AccountRatingCategory category) {
			return getCategoryPolicy(category).suspiciousThreshold;
		}

		public long getSuspiciousLevelScoreCap(AccountRatingCategory category) {
			return getCategoryPolicy(category).suspiciousLevelScoreCap;
		}

		public int getMaximumConfiguredLevel(AccountRatingCategory category) {
			return getCategoryPolicy(category).maximumConfiguredLevel;
		}

		public boolean canReachSuspiciousThresholds(int suspiciousMinRaterCount) {
			if (suspiciousMinRaterCount <= 0)
				return false;

			for (CategoryPolicy policy : this.policiesByCategory.values())
				if (!policy.canReachSuspiciousThreshold(suspiciousMinRaterCount))
					return false;

			return true;
		}

		private CategoryPolicy getCategoryPolicy(AccountRatingCategory category) {
			CategoryPolicy policy = this.policiesByCategory.get(effectiveCategory(category));
			if (policy == null)
				throw new IllegalStateException("Missing account trust category policy");

			return policy;
		}
	}

	private static final class CategoryPolicy {
		private final Map<Integer, LevelPolicy> levelsByLevel;
		private final long suspiciousThreshold;
		private final long suspiciousLevelScoreCap;
		private final int maximumConfiguredLevel;

		private CategoryPolicy(Map<Integer, LevelPolicy> levelsByLevel, long suspiciousThreshold,
				long suspiciousLevelScoreCap, int maximumConfiguredLevel) {
			this.levelsByLevel = Collections.unmodifiableMap(new HashMap<>(levelsByLevel));
			this.suspiciousThreshold = suspiciousThreshold;
			this.suspiciousLevelScoreCap = suspiciousLevelScoreCap;
			this.maximumConfiguredLevel = maximumConfiguredLevel;
		}

		private static CategoryPolicy from(AccountTrustCategoryPolicy categoryPolicy) {
			Map<Integer, LevelPolicy> levelsByLevel = new HashMap<>();
			int maximumConfiguredLevel = 0;
			for (AccountTrustLevelPolicy levelPolicy : categoryPolicy.levels) {
				levelsByLevel.put(levelPolicy.level, new LevelPolicy(levelPolicy.threshold, levelPolicy.cap));
				if (levelPolicy.level > maximumConfiguredLevel)
					maximumConfiguredLevel = levelPolicy.level;
			}

			return new CategoryPolicy(levelsByLevel, categoryPolicy.suspiciousThreshold, categoryPolicy.suspiciousCap,
					maximumConfiguredLevel);
		}

		private static CategoryPolicy from(AccountTrustCategoryPoliciesData.CategoryPolicy categoryPolicy) {
			Map<Integer, LevelPolicy> levelsByLevel = new HashMap<>();
			int maximumConfiguredLevel = 0;
			for (AccountTrustCategoryPoliciesData.LevelPolicy levelPolicy : categoryPolicy.getLevels()) {
				levelsByLevel.put(levelPolicy.getLevel(), new LevelPolicy(levelPolicy.getThreshold(),
						levelPolicy.getLevelScoreCap()));
				if (levelPolicy.getLevel() > maximumConfiguredLevel)
					maximumConfiguredLevel = levelPolicy.getLevel();
			}

			return new CategoryPolicy(levelsByLevel, categoryPolicy.getSuspiciousThreshold(),
					categoryPolicy.getSuspiciousLevelScoreCap(), maximumConfiguredLevel);
		}

		private long getLevelThreshold(int level) {
			return getLevelPolicy(level).threshold;
		}

		private long getLevelScoreCap(int level) {
			return getLevelPolicy(level).levelScoreCap;
		}

		private boolean canReachSuspiciousThreshold(int suspiciousMinRaterCount) {
			BigInteger requiredScore = BigInteger.valueOf(this.suspiciousThreshold).negate();
			BigInteger maximumScore = BigInteger.valueOf(this.suspiciousLevelScoreCap)
					.multiply(BigInteger.valueOf(suspiciousMinRaterCount));
			return maximumScore.compareTo(requiredScore) >= 0;
		}

		private LevelPolicy getLevelPolicy(int level) {
			LevelPolicy policy = this.levelsByLevel.get(level);
			if (policy == null)
				throw new IllegalStateException("Missing account trust level policy");

			return policy;
		}
	}

	private static final class LevelPolicy {
		private final long threshold;
		private final long levelScoreCap;

		private LevelPolicy(long threshold, long levelScoreCap) {
			this.threshold = threshold;
			this.levelScoreCap = levelScoreCap;
		}
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
