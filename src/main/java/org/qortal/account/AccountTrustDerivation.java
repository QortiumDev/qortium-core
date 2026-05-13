package org.qortal.account;

import org.qortal.block.BlockChain;
import org.qortal.data.account.AccountRating;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountRatingData;
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

public class AccountTrustDerivation {

	private static final long STARTING_ENERGY = 1_000_000L;

	public static Result derive(Repository repository, String targetAddress) throws DataException {
		List<AccountRatingData> allRatings = repository.getAccountRatingRepository()
				.getRatings(null, null, null, null, null, null);
		Map<AccountRatingCategory, List<AccountRatingData>> ratingsByCategory = groupRatingsByCategory(allRatings);
		Set<String> seedAddresses = getMintingSeedAddresses(repository);
		Map<String, Long> seedScores = buildSeedScores(seedAddresses);

		Map<String, CategoryScore> managerScores = deriveCategory(ratingsByCategory.get(AccountRatingCategory.MANAGER),
				seedScores, AccountRatingCategory.MANAGER);
		Map<String, CategoryScore> trainerScores = deriveCategory(ratingsByCategory.get(AccountRatingCategory.TRAINER),
				managerScores, AccountRatingCategory.TRAINER);
		Map<String, CategoryScore> playerScores = deriveCategory(ratingsByCategory.get(AccountRatingCategory.PLAYER),
				trainerScores, AccountRatingCategory.PLAYER);
		Map<String, CategoryScore> subjectScores = deriveCategory(ratingsByCategory.get(AccountRatingCategory.SUBJECT),
				playerScores, AccountRatingCategory.SUBJECT);

		List<AccountTrustPreviewData.CategoryTrust> categories = new ArrayList<>();
		categories.add(buildCategoryTrust(AccountRatingCategory.SUBJECT, targetAddress, subjectScores,
				ratingsByCategory.get(AccountRatingCategory.SUBJECT)));
		categories.add(buildCategoryTrust(AccountRatingCategory.PLAYER, targetAddress, playerScores,
				ratingsByCategory.get(AccountRatingCategory.PLAYER)));
		categories.add(buildCategoryTrust(AccountRatingCategory.TRAINER, targetAddress, trainerScores,
				ratingsByCategory.get(AccountRatingCategory.TRAINER)));
		categories.add(buildCategoryTrust(AccountRatingCategory.MANAGER, targetAddress, managerScores,
				ratingsByCategory.get(AccountRatingCategory.MANAGER)));

		AccountTrustStatus derivedTrustStatus = categories.get(0).getMappedTrustStatus();
		return new Result(derivedTrustStatus, seedAddresses.contains(targetAddress), categories);
	}

	private static Map<AccountRatingCategory, List<AccountRatingData>> groupRatingsByCategory(List<AccountRatingData> ratings) {
		Map<AccountRatingCategory, List<AccountRatingData>> ratingsByCategory = new EnumMap<>(AccountRatingCategory.class);
		for (AccountRatingCategory category : AccountRatingCategory.values())
			ratingsByCategory.put(category, new ArrayList<>());

		for (AccountRatingData rating : ratings)
			ratingsByCategory.get(rating.getCategory()).add(rating);

		return ratingsByCategory;
	}

	private static Set<String> getMintingSeedAddresses(Repository repository) throws DataException {
		int blockchainHeight = repository.getBlockRepository().getBlockchainHeight();
		List<Integer> mintingGroupIds = Groups.getGroupIdsToMint(BlockChain.getInstance(), blockchainHeight + 1);
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

	private static Map<String, CategoryScore> deriveCategory(List<AccountRatingData> ratings, Map<String, ?> evaluatorScores,
			AccountRatingCategory targetCategory) {
		Map<String, CategoryScore> scores = new HashMap<>();
		Map<String, Integer> positiveRatingScales = targetCategory == AccountRatingCategory.MANAGER
				? calculatePositiveRatingScales(ratings)
				: new HashMap<>();

		for (AccountRatingData rating : ratings) {
			String raterAddress = rating.getRaterAddress();
			EvaluatorScore evaluatorScore = getEvaluatorScore(evaluatorScores, raterAddress);
			long evaluatorWeight = Math.max(evaluatorScore.score, 0L);
			long impact = calculateImpact(targetCategory, rating, evaluatorWeight, positiveRatingScales.get(raterAddress));

			CategoryScore targetScore = scores.computeIfAbsent(rating.getTargetAddress(), ignored -> new CategoryScore());
			if (impact != 0L) {
				targetScore.score = saturatedAdd(targetScore.score, impact);
				targetScore.impacts.add(new AccountTrustPreviewData.CategoryImpact(rating.getRaterPublicKey(),
						raterAddress, evaluatorScore.level, evaluatorScore.score, rating.getRating(), impact));
			}
		}

		for (CategoryScore score : scores.values())
			score.level = calculateLevel(targetCategory, score.score, score.impacts);

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

	private static long calculateImpact(AccountRatingCategory targetCategory, AccountRatingData rating, long evaluatorWeight,
			Integer positiveRatingScale) {
		if (targetCategory == AccountRatingCategory.MANAGER && AccountRating.isPositive(rating.getRating())) {
			if (positiveRatingScale == null || positiveRatingScale <= 0)
				return 0L;

			return AccountRating.saturatedMultiply(evaluatorWeight, AccountRating.getConfidence(rating.getRating())) / positiveRatingScale;
		}

		return AccountRating.calculateImpactLong(rating.getRating(), evaluatorWeight);
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
			Map<String, CategoryScore> scores, List<AccountRatingData> ratings) {
		CategoryScore score = scores.get(targetAddress);
		if (score == null)
			score = new CategoryScore();

		AccountTrustPreviewData.RatingCounts inboundCounts = new AccountTrustPreviewData.RatingCounts();
		for (AccountRatingData rating : ratings) {
			if (targetAddress.equals(rating.getTargetAddress()))
				inboundCounts.addRating(rating.getRating());
		}

		score.impacts.sort(Comparator
				.comparingLong((AccountTrustPreviewData.CategoryImpact impact) -> Math.abs(impact.getImpact()))
				.reversed()
				.thenComparing(AccountTrustPreviewData.CategoryImpact::getRaterAddress));

		return new AccountTrustPreviewData.CategoryTrust(category, score.score, score.level, mapLevelToStatus(score.score,
				score.level), inboundCounts, score.impacts);
	}

	private static int calculateLevel(AccountRatingCategory category, long score,
			List<AccountTrustPreviewData.CategoryImpact> impacts) {
		if (score < 0)
			return -1;

		switch (category) {
			case MANAGER:
				if (score >= 200_000L)
					return 2;
				if (score >= 1_000L)
					return 1;
				return 0;

			case TRAINER:
				if (score >= 1_000_000L)
					return 2;
				if (score >= 500_000L)
					return 1;
				return 0;

			case PLAYER:
				if (score >= 3_000_000L && (hasImpact(impacts, 2, 3) || countImpacts(impacts, 2, 2) >= 2))
					return 3;
				if (score >= 2_000_000L && hasImpact(impacts, 1, 2))
					return 2;
				if (score >= 1_000_000L)
					return 1;
				return 0;

			case SUBJECT:
			default:
				if (score >= 150_000_000L && (hasImpact(impacts, 3, 3) || countImpacts(impacts, 3, 2) >= 2))
					return 4;
				if (score >= 100_000_000L && (hasImpact(impacts, 2, 3) || countImpacts(impacts, 2, 2) >= 2))
					return 3;
				if (score >= 50_000_000L && hasImpact(impacts, 1, 2))
					return 2;
				if (score >= 10_000_000L && hasImpact(impacts, 1, 1))
					return 1;
				return 0;
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

	private static AccountTrustStatus mapLevelToStatus(long score, int level) {
		if (score < 0 || level < 0)
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
		private int level;
		private final List<AccountTrustPreviewData.CategoryImpact> impacts = new ArrayList<>();
	}

	private static class EvaluatorScore {
		private final long score;
		private final int level;

		private EvaluatorScore(long score, int level) {
			this.score = score;
			this.level = level;
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
