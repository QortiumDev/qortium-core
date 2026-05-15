package org.qortal.account;

import org.qortal.block.BlockChain;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountRating;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountTrustDerivationData;
import org.qortal.data.account.AccountTrustPreviewData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Groups;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class AccountTrustDerivation {

	private static final long STARTING_ENERGY = 1_000_000L;
	private static final int MANAGER_ENERGY_HOPS = 4;
	private static final int LIST_IMPACT_LIMIT = 5;
	private static final int SUSPICIOUS_MIN_RATER_COUNT = 2;
	private static final int SUSPICIOUS_MIN_RATING_CONFIDENCE = 2;

	public static Result derive(Repository repository, String targetAddress) throws DataException {
		DerivedGraph graph = deriveGraph(repository, getLiveMintingSeedHeight(repository));
		return graph.buildResult(targetAddress, null);
	}

	public static List<AccountTrustDerivationData> deriveAll(Repository repository) throws DataException {
		return deriveAll(repository, getLiveMintingSeedHeight(repository));
	}

	public static List<AccountTrustDerivationData> deriveAll(Repository repository, int mintingSeedHeight) throws DataException {
		DerivedGraph graph = deriveGraph(repository, mintingSeedHeight);
		List<AccountTrustDerivationData> derivedAccounts = new ArrayList<>();

		for (String accountAddress : graph.accountAddresses) {
			Result result = graph.buildResult(accountAddress, LIST_IMPACT_LIMIT);
			derivedAccounts.add(new AccountTrustDerivationData(graph.publicKeysByAddress.get(accountAddress), accountAddress,
					result.getDerivedTrustStatus(), result.isMintingSeedMember(), result.getCategories()));
		}

		return derivedAccounts;
	}

	public static void refreshSnapshots(Repository repository, int snapshotHeight, long snapshotTimestamp) throws DataException {
		repository.getAccountRatingRepository().replaceTrustDerivationSnapshots(deriveAll(repository, snapshotHeight),
				snapshotHeight, snapshotTimestamp);
	}

	private static int getLiveMintingSeedHeight(Repository repository) throws DataException {
		return repository.getBlockRepository().getBlockchainHeight() + 1;
	}

	private static DerivedGraph deriveGraph(Repository repository, int mintingSeedHeight) throws DataException {
		List<AccountRatingData> allRatings = repository.getAccountRatingRepository()
				.getRatings(null, null, null, null, null, null);
		Map<AccountRatingCategory, List<AccountRatingData>> ratingsByCategory = groupRatingsByCategory(allRatings);
		Set<String> seedAddresses = getMintingSeedAddresses(repository, mintingSeedHeight);
		Map<String, Long> seedScores = buildSeedScores(seedAddresses);
		Map<String, Long> managerEnergy = flowManagerEnergy(ratingsByCategory.get(AccountRatingCategory.MANAGER), seedScores);

		Map<String, CategoryScore> managerScores = deriveCategory(ratingsByCategory.get(AccountRatingCategory.MANAGER),
				managerEnergy, AccountRatingCategory.MANAGER);
		Map<String, CategoryScore> trainerScores = deriveCategory(ratingsByCategory.get(AccountRatingCategory.TRAINER),
				managerScores, AccountRatingCategory.TRAINER);
		Map<String, CategoryScore> playerScores = deriveCategory(ratingsByCategory.get(AccountRatingCategory.PLAYER),
				trainerScores, AccountRatingCategory.PLAYER);
		Map<String, CategoryScore> subjectScores = deriveCategory(ratingsByCategory.get(AccountRatingCategory.SUBJECT),
				playerScores, AccountRatingCategory.SUBJECT);

		Map<AccountRatingCategory, Map<String, CategoryScore>> scoresByCategory = new EnumMap<>(AccountRatingCategory.class);
		scoresByCategory.put(AccountRatingCategory.SUBJECT, subjectScores);
		scoresByCategory.put(AccountRatingCategory.PLAYER, playerScores);
		scoresByCategory.put(AccountRatingCategory.TRAINER, trainerScores);
		scoresByCategory.put(AccountRatingCategory.MANAGER, managerScores);

		return new DerivedGraph(seedAddresses, collectAccountAddresses(seedAddresses, allRatings, scoresByCategory),
				buildKnownPublicKeysByAddress(repository, seedAddresses, allRatings),
				buildInboundCountsByCategory(ratingsByCategory), scoresByCategory);
	}

	private static Map<AccountRatingCategory, List<AccountRatingData>> groupRatingsByCategory(List<AccountRatingData> ratings) {
		Map<AccountRatingCategory, List<AccountRatingData>> ratingsByCategory = new EnumMap<>(AccountRatingCategory.class);
		for (AccountRatingCategory category : AccountRatingCategory.values())
			ratingsByCategory.put(category, new ArrayList<>());

		for (AccountRatingData rating : ratings)
			ratingsByCategory.get(rating.getCategory()).add(rating);

		return ratingsByCategory;
	}

	private static Set<String> collectAccountAddresses(Set<String> seedAddresses, List<AccountRatingData> allRatings,
			Map<AccountRatingCategory, Map<String, CategoryScore>> scoresByCategory) {
		Set<String> accountAddresses = new TreeSet<>();
		accountAddresses.addAll(seedAddresses);

		for (AccountRatingData rating : allRatings) {
			accountAddresses.add(rating.getTargetAddress());
			accountAddresses.add(rating.getRaterAddress());
		}

		for (Map<String, CategoryScore> scores : scoresByCategory.values())
			accountAddresses.addAll(scores.keySet());

		return accountAddresses;
	}

	private static Map<String, byte[]> buildKnownPublicKeysByAddress(Repository repository, Set<String> seedAddresses,
			List<AccountRatingData> allRatings) throws DataException {
		Map<String, byte[]> publicKeysByAddress = new HashMap<>();

		for (AccountRatingData rating : allRatings) {
			publicKeysByAddress.putIfAbsent(rating.getTargetAddress(), rating.getTargetPublicKey());
			publicKeysByAddress.putIfAbsent(rating.getRaterAddress(), rating.getRaterPublicKey());
		}

		for (String seedAddress : seedAddresses) {
			if (publicKeysByAddress.containsKey(seedAddress))
				continue;

			AccountData accountData = repository.getAccountRepository().getAccount(seedAddress);
			if (accountData != null && accountData.getPublicKey() != null)
				publicKeysByAddress.put(seedAddress, accountData.getPublicKey());
		}

		return publicKeysByAddress;
	}

	private static Map<AccountRatingCategory, Map<String, AccountTrustPreviewData.RatingCounts>> buildInboundCountsByCategory(
			Map<AccountRatingCategory, List<AccountRatingData>> ratingsByCategory) {
		Map<AccountRatingCategory, Map<String, AccountTrustPreviewData.RatingCounts>> inboundCountsByCategory = new EnumMap<>(
				AccountRatingCategory.class);

		for (AccountRatingCategory category : AccountRatingCategory.values()) {
			Map<String, AccountTrustPreviewData.RatingCounts> inboundCounts = new HashMap<>();
			for (AccountRatingData rating : ratingsByCategory.get(category)) {
				AccountTrustPreviewData.RatingCounts ratingCounts = inboundCounts.computeIfAbsent(rating.getTargetAddress(),
						ignored -> new AccountTrustPreviewData.RatingCounts());
				ratingCounts.addRating(rating.getRating());
			}

			inboundCountsByCategory.put(category, inboundCounts);
		}

		return inboundCountsByCategory;
	}

	private static Set<String> getMintingSeedAddresses(Repository repository, int mintingSeedHeight) throws DataException {
		List<Integer> mintingGroupIds = Groups.getGroupIdsToMint(BlockChain.getInstance(), mintingSeedHeight);
		Set<String> seedAddresses = new HashSet<>(Groups.getAllMembers(repository.getGroupRepository(), mintingGroupIds));
		seedAddresses.remove(Group.NULL_OWNER_ADDRESS);
		return seedAddresses;
	}

	private static Map<String, Long> buildSeedScores(Set<String> seedAddresses) {
		Map<String, Long> seedScores = new HashMap<>();
		if (seedAddresses.isEmpty())
			return seedScores;

		long scorePerSeed = STARTING_ENERGY / seedAddresses.size();
		for (String seedAddress : seedAddresses)
			seedScores.put(seedAddress, scorePerSeed);

		return seedScores;
	}

	private static Map<String, Long> flowManagerEnergy(List<AccountRatingData> managerRatings, Map<String, Long> seedScores) {
		Map<String, Long> energy = new HashMap<>(seedScores);
		Map<String, Integer> positiveRatingScales = calculatePositiveRatingScales(managerRatings);

		for (int hop = 0; hop < MANAGER_ENERGY_HOPS; ++hop) {
			Map<String, Long> nextEnergy = new HashMap<>();

			for (AccountRatingData rating : managerRatings) {
				if (!AccountRating.isPositive(rating.getRating()))
					continue;

				long raterEnergy = energy.getOrDefault(rating.getRaterAddress(), 0L);
				if (raterEnergy <= 0L)
					continue;

				Integer positiveRatingScale = positiveRatingScales.get(rating.getRaterAddress());
				if (positiveRatingScale == null || positiveRatingScale <= 0)
					continue;

				long targetEnergy = AccountRating.saturatedMultiply(raterEnergy,
						AccountRating.getConfidence(rating.getRating())) / positiveRatingScale;
				if (targetEnergy != 0L)
					nextEnergy.merge(rating.getTargetAddress(), targetEnergy, AccountTrustDerivation::saturatedAdd);
			}

			energy = nextEnergy;
		}

		return energy;
	}

	private static Map<String, CategoryScore> deriveCategory(List<AccountRatingData> ratings, Map<String, ?> evaluatorScores,
			AccountRatingCategory targetCategory) {
		Map<String, CategoryScore> scores = new HashMap<>();

		for (AccountRatingData rating : ratings) {
			String raterAddress = rating.getRaterAddress();
			EvaluatorScore evaluatorScore = getEvaluatorScore(evaluatorScores, raterAddress);
			long evaluatorWeight = Math.max(evaluatorScore.score, 0L);
			long impact = AccountRating.calculateImpactLong(rating.getRating(), evaluatorWeight);

			CategoryScore targetScore = scores.computeIfAbsent(rating.getTargetAddress(), ignored -> new CategoryScore());
			if (impact != 0L) {
				targetScore.score = saturatedAdd(targetScore.score, impact);
				targetScore.impacts.add(new AccountTrustPreviewData.CategoryImpact(rating.getRaterPublicKey(),
						raterAddress, evaluatorScore.level, evaluatorScore.score, rating.getRating(), impact));
			}
		}

		for (CategoryScore score : scores.values())
			score.apply(calculateLevelDecision(targetCategory, score.score, score.impacts));

		return scores;
	}

	private static Map<String, Integer> calculatePositiveRatingScales(List<AccountRatingData> ratings) {
		Map<String, Integer> positiveRatingScales = new HashMap<>();
		for (AccountRatingData rating : ratings) {
			if (AccountRating.isPositive(rating.getRating()))
				positiveRatingScales.merge(rating.getRaterAddress(), AccountRating.getConfidence(rating.getRating()), Integer::sum);
		}

		return positiveRatingScales;
	}

	private static EvaluatorScore getEvaluatorScore(Map<String, ?> evaluatorScores, String raterAddress) {
		Object score = evaluatorScores.get(raterAddress);
		if (score instanceof CategoryScore) {
			CategoryScore categoryScore = (CategoryScore) score;
			return new EvaluatorScore(categoryScore.score, categoryScore.level);
		}

		if (score instanceof Long)
			return new EvaluatorScore((Long) score, 0);

		return new EvaluatorScore(0L, 0);
	}

	private static AccountTrustPreviewData.CategoryTrust buildCategoryTrust(AccountRatingCategory category, String targetAddress,
			Map<String, CategoryScore> scores, Map<String, AccountTrustPreviewData.RatingCounts> inboundCountsByAddress,
			Integer maxImpacts) {
		CategoryScore score = scores.get(targetAddress);
		if (score == null) {
			score = new CategoryScore();
			score.apply(calculateLevelDecision(category, score.score, score.impacts));
		}

		AccountTrustPreviewData.RatingCounts inboundCounts = inboundCountsByAddress.get(targetAddress);
		if (inboundCounts == null)
			inboundCounts = new AccountTrustPreviewData.RatingCounts();

		score.impacts.sort(Comparator
				.comparingLong((AccountTrustPreviewData.CategoryImpact impact) -> Math.abs(impact.getImpact()))
				.reversed()
				.thenComparing(AccountTrustPreviewData.CategoryImpact::getRaterAddress));

		List<AccountTrustPreviewData.CategoryImpact> impacts = new ArrayList<>(score.impacts);
		if (maxImpacts != null && maxImpacts >= 0 && impacts.size() > maxImpacts)
			impacts = new ArrayList<>(impacts.subList(0, maxImpacts));

		return new AccountTrustPreviewData.CategoryTrust(category, score.score, score.levelScore, score.levelScoreCap,
				score.level, mapLevelToStatus(score.level), inboundCounts, impacts);
	}

	private static LevelDecision calculateLevelDecision(AccountRatingCategory category, long score,
			List<AccountTrustPreviewData.CategoryImpact> impacts) {
		LevelDecision suspiciousDecision = suspiciousDecisionForCategory(category, impacts);
		if (meetsSuspiciousRequirements(category, suspiciousDecision, impacts))
			return suspiciousDecision;

		if (score < 0)
			return zeroLevelDecision(category, impacts);

		switch (category) {
			case MANAGER:
				return calculateManagerLevel(impacts);

			case TRAINER:
				return calculateTrainerLevel(impacts);

			case PLAYER:
				return calculatePlayerLevel(impacts);

			case SUBJECT:
			default:
				return calculateSubjectLevel(impacts);
		}
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

	private static long getLevelScoreCap(AccountRatingCategory category, int level) {
		return getLevelThreshold(category, level) / 2L;
	}

	private static long getSuspiciousLevelScoreCap(AccountRatingCategory category) {
		return getLevelScoreCap(category, 1);
	}

	private static long getSuspiciousThreshold(AccountRatingCategory category) {
		return -getLevelThreshold(category, 1);
	}

	private static long getLevelThreshold(AccountRatingCategory category, int level) {
		switch (category) {
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

	private static AccountTrustStatus mapLevelToStatus(int level) {
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

	private static long saturatedAdd(long left, long right) {
		long result = left + right;
		if (((left ^ result) & (right ^ result)) < 0)
			return right < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;

		return result;
	}

	private static class CategoryScore {
		private long score;
		private long levelScore;
		private long levelScoreCap;
		private int level;
		private final List<AccountTrustPreviewData.CategoryImpact> impacts = new ArrayList<>();

		private void apply(LevelDecision decision) {
			this.level = decision.level;
			this.levelScore = decision.levelScore;
			this.levelScoreCap = decision.levelScoreCap;
		}
	}

	private static class LevelDecision {
		private final int level;
		private final long levelScore;
		private final long levelScoreCap;

		private LevelDecision(int level, long levelScore, long levelScoreCap) {
			this.level = level;
			this.levelScore = levelScore;
			this.levelScoreCap = levelScoreCap;
		}
	}

	private static class EvaluatorScore {
		private final long score;
		private final int level;

		private EvaluatorScore(long score, int level) {
			this.score = score;
			this.level = level;
		}
	}

	private static class DerivedGraph {
		private final Set<String> seedAddresses;
		private final Set<String> accountAddresses;
		private final Map<String, byte[]> publicKeysByAddress;
		private final Map<AccountRatingCategory, Map<String, AccountTrustPreviewData.RatingCounts>> inboundCountsByCategory;
		private final Map<AccountRatingCategory, Map<String, CategoryScore>> scoresByCategory;

		private DerivedGraph(Set<String> seedAddresses, Set<String> accountAddresses, Map<String, byte[]> publicKeysByAddress,
				Map<AccountRatingCategory, Map<String, AccountTrustPreviewData.RatingCounts>> inboundCountsByCategory,
				Map<AccountRatingCategory, Map<String, CategoryScore>> scoresByCategory) {
			this.seedAddresses = seedAddresses;
			this.accountAddresses = accountAddresses;
			this.publicKeysByAddress = publicKeysByAddress;
			this.inboundCountsByCategory = inboundCountsByCategory;
			this.scoresByCategory = scoresByCategory;
		}

		private Result buildResult(String accountAddress, Integer maxImpactsPerCategory) {
			List<AccountTrustPreviewData.CategoryTrust> categories = new ArrayList<>();
			categories.add(buildCategoryTrust(AccountRatingCategory.SUBJECT, accountAddress,
					this.scoresByCategory.get(AccountRatingCategory.SUBJECT),
					this.inboundCountsByCategory.get(AccountRatingCategory.SUBJECT), maxImpactsPerCategory));
			categories.add(buildCategoryTrust(AccountRatingCategory.PLAYER, accountAddress,
					this.scoresByCategory.get(AccountRatingCategory.PLAYER),
					this.inboundCountsByCategory.get(AccountRatingCategory.PLAYER), maxImpactsPerCategory));
			categories.add(buildCategoryTrust(AccountRatingCategory.TRAINER, accountAddress,
					this.scoresByCategory.get(AccountRatingCategory.TRAINER),
					this.inboundCountsByCategory.get(AccountRatingCategory.TRAINER), maxImpactsPerCategory));
			categories.add(buildCategoryTrust(AccountRatingCategory.MANAGER, accountAddress,
					this.scoresByCategory.get(AccountRatingCategory.MANAGER),
					this.inboundCountsByCategory.get(AccountRatingCategory.MANAGER), maxImpactsPerCategory));

			AccountTrustStatus derivedTrustStatus = categories.get(0).getMappedTrustStatus();
			return new Result(derivedTrustStatus, this.seedAddresses.contains(accountAddress), categories);
		}
	}

	public static class Result {
		private final AccountTrustStatus derivedTrustStatus;
		private final boolean mintingSeedMember;
		private final List<AccountTrustPreviewData.CategoryTrust> categories;

		private Result(AccountTrustStatus derivedTrustStatus, boolean mintingSeedMember,
				List<AccountTrustPreviewData.CategoryTrust> categories) {
			this.derivedTrustStatus = derivedTrustStatus;
			this.mintingSeedMember = mintingSeedMember;
			this.categories = categories;
		}

		public AccountTrustStatus getDerivedTrustStatus() {
			return this.derivedTrustStatus;
		}

		public boolean isMintingSeedMember() {
			return this.mintingSeedMember;
		}

		public List<AccountTrustPreviewData.CategoryTrust> getCategories() {
			return this.categories;
		}
	}
}
