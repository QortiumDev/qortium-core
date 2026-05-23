package org.qortal.account;

import org.qortal.block.BlockChain;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountRating;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountTrustDerivationData;
import org.qortal.data.account.AccountTrustCategoryData;
import org.qortal.data.account.AccountTrustCategoryImpactData;
import org.qortal.data.account.AccountTrustRatingCountsData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Groups;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class AccountTrustDerivation {

	private static final int LIST_IMPACT_LIMIT = 5;

	public static Result derive(Repository repository, String targetAddress) throws DataException {
		DerivedGraph graph = deriveGraph(repository, getLiveMintingSeedHeight(repository));
		return graph.buildResult(targetAddress, null);
	}

	public static Result deriveWithRatingOverlay(Repository repository, String targetAddress,
			AccountRatingData ratingOverlay) throws DataException {
		List<AccountRatingData> allRatings = repository.getAccountRatingRepository()
				.getRatings(null, null, null, null, null, null);
		DerivedGraph graph = deriveGraph(repository, getLiveMintingSeedHeight(repository),
				applyRatingOverlay(allRatings, ratingOverlay));
		return graph.buildResult(targetAddress, null);
	}

	public static List<AccountTrustDerivationData> deriveAll(Repository repository) throws DataException {
		return deriveAll(repository, getLiveMintingSeedHeight(repository));
	}

	public static List<AccountTrustDerivationData> deriveAll(Repository repository, int mintingSeedHeight) throws DataException {
		DerivedGraph graph = deriveGraph(repository, mintingSeedHeight);
		int[] voteWeightPercents = AccountTrustPolicy.getVoteWeightPercents(repository, mintingSeedHeight);
		List<AccountTrustDerivationData> derivedAccounts = new ArrayList<>();

		for (String accountAddress : graph.accountAddresses) {
			Result result = graph.buildResult(accountAddress, LIST_IMPACT_LIMIT);
			derivedAccounts.add(new AccountTrustDerivationData(graph.publicKeysByAddress.get(accountAddress), accountAddress,
					result.getDerivedTrustStatus(),
					AccountTrustPolicy.getVoteWeightPercent(voteWeightPercents, result.getDerivedTrustStatus()),
					result.isMintingSeedMember(), null, null, true, result.getCategories()));
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
		return deriveGraph(repository, mintingSeedHeight, allRatings);
	}

	private static DerivedGraph deriveGraph(Repository repository, int mintingSeedHeight,
			List<AccountRatingData> allRatings) throws DataException {
		Map<AccountRatingCategory, List<AccountRatingData>> ratingsByCategory = groupRatingsByCategory(allRatings);
		Set<String> seedAddresses = getMintingSeedAddresses(repository, mintingSeedHeight);
		long startingEnergy = AccountTrustPolicy.getStartingEnergy(repository, mintingSeedHeight);
		int managerEnergyHops = AccountTrustPolicy.getManagerEnergyHops(repository, mintingSeedHeight);
		AccountTrustPolicy.DecisionSettings decisionSettings = AccountTrustPolicy.getDecisionSettings(repository,
				mintingSeedHeight);
		AccountTrustPolicy.CategoryPolicySettings categoryPolicySettings =
				AccountTrustPolicy.getCategoryPolicySettings(repository, mintingSeedHeight);
		Map<String, EnergyScore> seedEnergy = buildSeedEnergy(seedAddresses, startingEnergy);
		Map<String, EnergyScore> managerEnergy = flowManagerEnergy(ratingsByCategory.get(AccountRatingCategory.MANAGER),
				seedEnergy, seedAddresses, managerEnergyHops);

		Map<String, CategoryScore> managerScores = deriveCategory(ratingsByCategory.get(AccountRatingCategory.MANAGER),
				managerEnergy, AccountRatingCategory.MANAGER, decisionSettings, categoryPolicySettings);
		Map<String, CategoryScore> trainerScores = deriveCategory(ratingsByCategory.get(AccountRatingCategory.TRAINER),
				managerScores, AccountRatingCategory.TRAINER, decisionSettings, categoryPolicySettings);
		Map<String, CategoryScore> playerScores = deriveCategory(ratingsByCategory.get(AccountRatingCategory.PLAYER),
				trainerScores, AccountRatingCategory.PLAYER, decisionSettings, categoryPolicySettings);
		Map<String, CategoryScore> subjectScores = deriveCategory(ratingsByCategory.get(AccountRatingCategory.SUBJECT),
				playerScores, AccountRatingCategory.SUBJECT, decisionSettings, categoryPolicySettings);

		Map<AccountRatingCategory, Map<String, CategoryScore>> scoresByCategory = new EnumMap<>(AccountRatingCategory.class);
		scoresByCategory.put(AccountRatingCategory.SUBJECT, subjectScores);
		scoresByCategory.put(AccountRatingCategory.PLAYER, playerScores);
		scoresByCategory.put(AccountRatingCategory.TRAINER, trainerScores);
		scoresByCategory.put(AccountRatingCategory.MANAGER, managerScores);

		return new DerivedGraph(seedAddresses, collectAccountAddresses(seedAddresses, allRatings, scoresByCategory),
				buildKnownPublicKeysByAddress(repository, seedAddresses, allRatings),
				buildInboundCountsByCategory(ratingsByCategory), scoresByCategory, decisionSettings,
				categoryPolicySettings);
	}

	private static List<AccountRatingData> applyRatingOverlay(List<AccountRatingData> ratings,
			AccountRatingData ratingOverlay) {
		if (ratingOverlay == null)
			return ratings;

		List<AccountRatingData> updatedRatings = new ArrayList<>();
		boolean replacedExistingEdge = false;

		for (AccountRatingData rating : ratings) {
			if (isSameRatingEdge(rating, ratingOverlay)) {
				replacedExistingEdge = true;
				if (AccountRating.isActive(ratingOverlay.getRating()))
					updatedRatings.add(ratingOverlay);

				continue;
			}

			updatedRatings.add(rating);
		}

		if (!replacedExistingEdge && AccountRating.isActive(ratingOverlay.getRating()))
			updatedRatings.add(ratingOverlay);

		return updatedRatings;
	}

	private static boolean isSameRatingEdge(AccountRatingData left, AccountRatingData right) {
		return Arrays.equals(left.getTargetPublicKey(), right.getTargetPublicKey())
				&& Arrays.equals(left.getRaterPublicKey(), right.getRaterPublicKey())
				&& left.getCategory() == right.getCategory();
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

	private static Map<AccountRatingCategory, Map<String, AccountTrustRatingCountsData>> buildInboundCountsByCategory(
			Map<AccountRatingCategory, List<AccountRatingData>> ratingsByCategory) {
		Map<AccountRatingCategory, Map<String, AccountTrustRatingCountsData>> inboundCountsByCategory = new EnumMap<>(
				AccountRatingCategory.class);

		for (AccountRatingCategory category : AccountRatingCategory.values()) {
			Map<String, AccountTrustRatingCountsData> inboundCounts = new HashMap<>();
			for (AccountRatingData rating : ratingsByCategory.get(category)) {
				AccountTrustRatingCountsData ratingCounts = inboundCounts.computeIfAbsent(rating.getTargetAddress(),
						ignored -> new AccountTrustRatingCountsData());
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

	private static Map<String, EnergyScore> buildSeedEnergy(Set<String> seedAddresses, long startingEnergy) {
		Map<String, EnergyScore> seedEnergy = new HashMap<>();
		if (seedAddresses.isEmpty())
			return seedEnergy;

		long scorePerSeed = startingEnergy / seedAddresses.size();
		for (String seedAddress : seedAddresses)
			seedEnergy.put(seedAddress, new EnergyScore(scorePerSeed));

		return seedEnergy;
	}

	private static Map<String, EnergyScore> flowManagerEnergy(List<AccountRatingData> managerRatings,
			Map<String, EnergyScore> seedEnergy, Set<String> seedAddresses, int managerEnergyHops) {
		Map<String, EnergyScore> energy = new HashMap<>(seedEnergy);
		Map<String, Integer> positiveRatingScales = calculatePositiveRatingScales(managerRatings);

		for (int hop = 0; hop < managerEnergyHops; ++hop) {
			Map<String, EnergyScore> nextEnergy = new HashMap<>();

			for (AccountRatingData rating : managerRatings) {
				if (!AccountRating.isPositive(rating.getRating()))
					continue;

				EnergyScore raterEnergy = energy.get(rating.getRaterAddress());
				if (raterEnergy == null || raterEnergy.score <= 0L)
					continue;

				Integer positiveRatingScale = positiveRatingScales.get(rating.getRaterAddress());
				if (positiveRatingScale == null || positiveRatingScale <= 0)
					continue;

				long targetEnergy = AccountRating.saturatedMultiply(raterEnergy.score,
						AccountRating.getConfidence(rating.getRating())) / positiveRatingScale;
				if (targetEnergy != 0L)
					mergeEnergy(nextEnergy, rating.getTargetAddress(), targetEnergy,
							branchKeysForManagerTransfer(rating, raterEnergy, seedAddresses));
			}

			energy = nextEnergy;
		}

		return energy;
	}

	private static void mergeEnergy(Map<String, EnergyScore> energyByAddress, String address, long energy,
			Set<String> trustBranchKeys) {
		EnergyScore score = energyByAddress.computeIfAbsent(address, ignored -> new EnergyScore());
		score.score = saturatedAdd(score.score, energy);
		score.trustBranchKeys.addAll(trustBranchKeys);
	}

	private static Set<String> branchKeysForManagerTransfer(AccountRatingData rating, EnergyScore raterEnergy,
			Set<String> seedAddresses) {
		if (raterEnergy.trustBranchKeys.isEmpty() && seedAddresses.contains(rating.getRaterAddress()))
			return Collections.singleton(managerBranchKey(rating.getRaterAddress(), rating.getTargetAddress()));

		return raterEnergy.trustBranchKeys;
	}

	private static String managerBranchKey(String seedAddress, String firstHopAddress) {
		return seedAddress + ">" + firstHopAddress;
	}

	private static Map<String, CategoryScore> deriveCategory(List<AccountRatingData> ratings, Map<String, ?> evaluatorScores,
			AccountRatingCategory targetCategory, AccountTrustPolicy.DecisionSettings decisionSettings,
			AccountTrustPolicy.CategoryPolicySettings categoryPolicySettings) {
		Map<String, CategoryScore> scores = new HashMap<>();

		for (AccountRatingData rating : ratings) {
			String raterAddress = rating.getRaterAddress();
			EvaluatorScore evaluatorScore = getEvaluatorScore(evaluatorScores, raterAddress);
			long evaluatorWeight = Math.max(evaluatorScore.score, 0L);
			long impact = AccountRating.calculateImpactLong(rating.getRating(), evaluatorWeight);

			CategoryScore targetScore = scores.computeIfAbsent(rating.getTargetAddress(), ignored -> new CategoryScore());
			if (impact != 0L) {
				targetScore.score = saturatedAdd(targetScore.score, impact);
				targetScore.impacts.add(new AccountTrustCategoryImpactData(rating.getRaterPublicKey(),
						raterAddress, evaluatorScore.level, evaluatorScore.score, rating.getRating(), impact,
						new ArrayList<>(evaluatorScore.trustBranchKeys)));
				if (impact > 0L)
					targetScore.trustBranchKeys.addAll(evaluatorScore.trustBranchKeys);
			}
		}

		for (CategoryScore score : scores.values())
			score.apply(AccountTrustPolicy.decideLevel(targetCategory, score.score, score.impacts, decisionSettings,
					categoryPolicySettings));

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
			return new EvaluatorScore(categoryScore.score, categoryScore.level, categoryScore.trustBranchKeys);
		}

		if (score instanceof EnergyScore) {
			EnergyScore energyScore = (EnergyScore) score;
			return new EvaluatorScore(energyScore.score, 0, energyScore.trustBranchKeys);
		}

		if (score instanceof Long)
			return new EvaluatorScore((Long) score, 0, Collections.emptySet());

		return new EvaluatorScore(0L, 0, Collections.emptySet());
	}

	private static AccountTrustCategoryData buildCategoryTrust(AccountRatingCategory category, String targetAddress,
			Map<String, CategoryScore> scores, Map<String, AccountTrustRatingCountsData> inboundCountsByAddress,
			Integer maxImpacts, AccountTrustPolicy.DecisionSettings decisionSettings,
			AccountTrustPolicy.CategoryPolicySettings categoryPolicySettings) {
		CategoryScore score = scores.get(targetAddress);
		if (score == null) {
			score = new CategoryScore();
			score.apply(AccountTrustPolicy.decideLevel(category, score.score, score.impacts, decisionSettings,
					categoryPolicySettings));
		}

		AccountTrustRatingCountsData inboundCounts = inboundCountsByAddress.get(targetAddress);
		if (inboundCounts == null)
			inboundCounts = new AccountTrustRatingCountsData();

		score.impacts.sort(Comparator
				.comparingLong((AccountTrustCategoryImpactData impact) -> Math.abs(impact.getImpact()))
				.reversed()
				.thenComparing(AccountTrustCategoryImpactData::getRaterAddress));

		List<AccountTrustCategoryImpactData> impacts = new ArrayList<>(score.impacts);
		if (maxImpacts != null && maxImpacts >= 0 && impacts.size() > maxImpacts)
			impacts = new ArrayList<>(impacts.subList(0, maxImpacts));

		return new AccountTrustCategoryData(category, score.score, score.levelScore, score.levelScoreCap,
				score.level, AccountTrustPolicy.mapLevelToStatus(score.level), inboundCounts, impacts);
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
		private final Set<String> trustBranchKeys = new TreeSet<>();
		private final List<AccountTrustCategoryImpactData> impacts = new ArrayList<>();

		private void apply(AccountTrustPolicy.LevelDecision decision) {
			this.level = decision.getLevel();
			this.levelScore = decision.getLevelScore();
			this.levelScoreCap = decision.getLevelScoreCap();
		}
	}

	private static class EvaluatorScore {
		private final long score;
		private final int level;
		private final Set<String> trustBranchKeys;

		private EvaluatorScore(long score, int level, Set<String> trustBranchKeys) {
			this.score = score;
			this.level = level;
			this.trustBranchKeys = trustBranchKeys == null ? Collections.emptySet() : trustBranchKeys;
		}
	}

	private static class EnergyScore {
		private long score;
		private final Set<String> trustBranchKeys = new TreeSet<>();

		private EnergyScore() {
		}

		private EnergyScore(long score) {
			this.score = score;
		}
	}

	private static class DerivedGraph {
		private final Set<String> seedAddresses;
		private final Set<String> accountAddresses;
		private final Map<String, byte[]> publicKeysByAddress;
		private final Map<AccountRatingCategory, Map<String, AccountTrustRatingCountsData>> inboundCountsByCategory;
		private final Map<AccountRatingCategory, Map<String, CategoryScore>> scoresByCategory;
		private final AccountTrustPolicy.DecisionSettings decisionSettings;
		private final AccountTrustPolicy.CategoryPolicySettings categoryPolicySettings;

		private DerivedGraph(Set<String> seedAddresses, Set<String> accountAddresses, Map<String, byte[]> publicKeysByAddress,
				Map<AccountRatingCategory, Map<String, AccountTrustRatingCountsData>> inboundCountsByCategory,
				Map<AccountRatingCategory, Map<String, CategoryScore>> scoresByCategory,
				AccountTrustPolicy.DecisionSettings decisionSettings,
				AccountTrustPolicy.CategoryPolicySettings categoryPolicySettings) {
			this.seedAddresses = seedAddresses;
			this.accountAddresses = accountAddresses;
			this.publicKeysByAddress = publicKeysByAddress;
			this.inboundCountsByCategory = inboundCountsByCategory;
			this.scoresByCategory = scoresByCategory;
			this.decisionSettings = decisionSettings;
			this.categoryPolicySettings = categoryPolicySettings;
		}

		private Result buildResult(String accountAddress, Integer maxImpactsPerCategory) {
			List<AccountTrustCategoryData> categories = new ArrayList<>();
			categories.add(buildCategoryTrust(AccountRatingCategory.SUBJECT, accountAddress,
					this.scoresByCategory.get(AccountRatingCategory.SUBJECT),
					this.inboundCountsByCategory.get(AccountRatingCategory.SUBJECT), maxImpactsPerCategory,
					this.decisionSettings, this.categoryPolicySettings));
			categories.add(buildCategoryTrust(AccountRatingCategory.PLAYER, accountAddress,
					this.scoresByCategory.get(AccountRatingCategory.PLAYER),
					this.inboundCountsByCategory.get(AccountRatingCategory.PLAYER), maxImpactsPerCategory,
					this.decisionSettings, this.categoryPolicySettings));
			categories.add(buildCategoryTrust(AccountRatingCategory.TRAINER, accountAddress,
					this.scoresByCategory.get(AccountRatingCategory.TRAINER),
					this.inboundCountsByCategory.get(AccountRatingCategory.TRAINER), maxImpactsPerCategory,
					this.decisionSettings, this.categoryPolicySettings));
			categories.add(buildCategoryTrust(AccountRatingCategory.MANAGER, accountAddress,
					this.scoresByCategory.get(AccountRatingCategory.MANAGER),
					this.inboundCountsByCategory.get(AccountRatingCategory.MANAGER), maxImpactsPerCategory,
					this.decisionSettings, this.categoryPolicySettings));

			AccountTrustStatus derivedTrustStatus = categories.get(0).getMappedTrustStatus();
			return new Result(derivedTrustStatus, this.seedAddresses.contains(accountAddress), categories,
					this.decisionSettings, this.categoryPolicySettings);
		}
	}

	public static class Result {
		private final AccountTrustStatus derivedTrustStatus;
		private final boolean mintingSeedMember;
		private final List<AccountTrustCategoryData> categories;
		private final AccountTrustPolicy.DecisionSettings decisionSettings;
		private final AccountTrustPolicy.CategoryPolicySettings categoryPolicySettings;

		private Result(AccountTrustStatus derivedTrustStatus, boolean mintingSeedMember,
				List<AccountTrustCategoryData> categories, AccountTrustPolicy.DecisionSettings decisionSettings,
				AccountTrustPolicy.CategoryPolicySettings categoryPolicySettings) {
			this.derivedTrustStatus = derivedTrustStatus;
			this.mintingSeedMember = mintingSeedMember;
			this.categories = categories;
			this.decisionSettings = decisionSettings;
			this.categoryPolicySettings = categoryPolicySettings;
		}

		public AccountTrustStatus getDerivedTrustStatus() {
			return this.derivedTrustStatus;
		}

		public boolean isMintingSeedMember() {
			return this.mintingSeedMember;
		}

		public List<AccountTrustCategoryData> getCategories() {
			return this.categories;
		}

		public AccountTrustPolicy.DecisionSettings getDecisionSettings() {
			return this.decisionSettings;
		}

		public AccountTrustPolicy.CategoryPolicySettings getCategoryPolicySettings() {
			return this.categoryPolicySettings;
		}
	}
}
