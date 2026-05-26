package org.qortium.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.AccountTrustDerivation;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.block.BlockChain;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountRatingData;
import org.qortium.data.account.AccountTrustCategoryData;
import org.qortium.data.account.AccountTrustDerivationData;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.group.GroupData;
import org.qortium.data.group.GroupMemberData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountTrustTestUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.utils.Groups;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class AccountTrustScaleTests extends Common {

	private static final String RUN_LONG_TRUST_NETWORK_TESTS_PROPERTY = "qortium.runLongTrustNetworkTests";

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testSyntheticTrustGraphDerivationAndSnapshotsScaleSanity() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			SyntheticTrustGraph graph = createSyntheticTrustGraph(repository,
					new SyntheticTrustGraphProfile("default", 2, 3, 4, 6, 8, 10));
			repository.saveChanges();

			List<AccountTrustDerivationData> derivedAccounts = AccountTrustDerivation.deriveAll(repository);
			assertDerivedGraphShape(repository, graph, derivedAccounts);

			AccountTrustDerivation.refreshSnapshots(repository, repository.getBlockRepository().getBlockchainHeight() + 1,
					repository.getBlockRepository().getLastBlock().getTimestamp());
			repository.saveChanges();

			List<AccountTrustSnapshotData> snapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(null, null, null);
			assertEquals(graph.expectedAccountAddresses.size() * AccountRatingCategory.values().length, snapshots.size());
			assertSnapshotRowsAreComplete(graph.expectedAccountAddresses, snapshots);
		}
	}

	@Test
	public void testLongSyntheticTrustNetworkBenchmarks() throws DataException {
		assumeTrue(Boolean.getBoolean(RUN_LONG_TRUST_NETWORK_TESTS_PROPERTY));

		runSyntheticBenchmark(new SyntheticTrustGraphProfile("medium", 3, 6, 12, 18, 24, 32));
		runSyntheticBenchmark(new SyntheticTrustGraphProfile("large", 4, 10, 30, 45, 60, 75));
		runSyntheticChurnBenchmark(new SyntheticTrustGraphProfile("medium", 3, 6, 12, 18, 24, 32), 4, 48, 24);
		runSyntheticChurnBenchmark(new SyntheticTrustGraphProfile("large", 4, 10, 30, 45, 60, 75), 4, 120, 60);
	}

	@Test
	public void testSyntheticTrustGraphChurnRefreshScaleSanity() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			SyntheticTrustGraph graph = createSyntheticTrustGraph(repository,
					new SyntheticTrustGraphProfile("default-churn", 2, 3, 4, 6, 8, 10));
			repository.saveChanges();

			AccountTrustDerivation.refreshSnapshots(repository, repository.getBlockRepository().getBlockchainHeight() + 1,
					repository.getBlockRepository().getLastBlock().getTimestamp());
			repository.saveChanges();

			int baselineRatingCount = graph.getActiveRatingCount();
			int expectedSnapshotCount = graph.expectedAccountAddresses.size() * AccountRatingCategory.values().length;
			assertEquals(expectedSnapshotCount, repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(null, null, null)
					.size());

			ChurnResult churnResult = applyDeterministicChurn(repository, graph, 0, 12, 6);
			repository.saveChanges();

			assertTrue("Synthetic churn should change ratings", churnResult.changedRatings > 0);
			assertTrue("Synthetic churn should remove ratings", churnResult.removedRatings > 0);
			assertEquals(baselineRatingCount - churnResult.removedRatings, graph.getActiveRatingCount());

			AccountTrustDerivation.refreshSnapshots(repository, repository.getBlockRepository().getBlockchainHeight() + 1,
					repository.getBlockRepository().getLastBlock().getTimestamp());
			repository.saveChanges();

			List<AccountTrustDerivationData> derivedAccounts = AccountTrustDerivation.deriveAll(repository);
			assertDerivedGraphShape(repository, graph, derivedAccounts);

			List<AccountTrustSnapshotData> snapshots = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(null, null, null);
			assertEquals(expectedSnapshotCount, snapshots.size());
			assertSnapshotRowsAreComplete(graph.expectedAccountAddresses, snapshots);
		}
	}

	private void runSyntheticBenchmark(SyntheticTrustGraphProfile profile) throws DataException {
		Common.useDefaultSettings();

		try (final Repository repository = RepositoryManager.getRepository()) {
			SyntheticTrustGraph graph = createSyntheticTrustGraph(repository, profile);
			repository.saveChanges();

			long deriveStart = System.currentTimeMillis();
			List<AccountTrustDerivationData> derivedAccounts = AccountTrustDerivation.deriveAll(repository);
			long deriveTime = System.currentTimeMillis() - deriveStart;
			assertDerivedGraphShape(repository, graph, derivedAccounts);

			long refreshStart = System.currentTimeMillis();
			AccountTrustDerivation.refreshSnapshots(repository, repository.getBlockRepository().getBlockchainHeight() + 1,
					repository.getBlockRepository().getLastBlock().getTimestamp());
			repository.saveChanges();
			long refreshTime = System.currentTimeMillis() - refreshStart;

			int snapshotCount = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(null, null, null)
					.size();
			long totalTime = deriveTime + refreshTime;

			System.out.printf("Trust graph benchmark %s: accounts=%d, ratings=%d, snapshots=%d, derive=%dms, refresh=%dms, total=%dms%n",
					profile.name, graph.expectedAccountAddresses.size(), graph.getActiveRatingCount(), snapshotCount, deriveTime,
					refreshTime, totalTime);
		}
	}

	private void runSyntheticChurnBenchmark(SyntheticTrustGraphProfile profile, int churnRounds, int changesPerRound,
			int removalsPerRound) throws DataException {
		Common.useDefaultSettings();

		try (final Repository repository = RepositoryManager.getRepository()) {
			SyntheticTrustGraph graph = createSyntheticTrustGraph(repository, profile);
			repository.saveChanges();

			AccountTrustDerivation.refreshSnapshots(repository, repository.getBlockRepository().getBlockchainHeight() + 1,
					repository.getBlockRepository().getLastBlock().getTimestamp());
			repository.saveChanges();

			int startingRatingCount = graph.getActiveRatingCount();
			int changedRatings = 0;
			int removedRatings = 0;
			long totalRefreshTime = 0L;
			long maxRefreshTime = 0L;

			for (int round = 0; round < churnRounds; ++round) {
				ChurnResult churnResult = applyDeterministicChurn(repository, graph, round, changesPerRound, removalsPerRound);
				changedRatings += churnResult.changedRatings;
				removedRatings += churnResult.removedRatings;
				repository.saveChanges();

				long refreshStart = System.currentTimeMillis();
				AccountTrustDerivation.refreshSnapshots(repository, repository.getBlockRepository().getBlockchainHeight() + 1,
						repository.getBlockRepository().getLastBlock().getTimestamp());
				repository.saveChanges();
				long refreshTime = System.currentTimeMillis() - refreshStart;
				totalRefreshTime += refreshTime;
				maxRefreshTime = Math.max(maxRefreshTime, refreshTime);

				assertDerivedGraphShape(repository, graph, AccountTrustDerivation.deriveAll(repository));
			}

			int snapshotCount = repository.getAccountRatingRepository()
					.getTrustDerivationSnapshots(null, null, null)
					.size();
			long averageRefreshTime = churnRounds == 0 ? 0L : totalRefreshTime / churnRounds;

			System.out.printf("Trust graph churn benchmark %s: accounts=%d, startingRatings=%d, endingRatings=%d, rounds=%d, changedRatings=%d, removedRatings=%d, snapshots=%d, totalRefresh=%dms, averageRefresh=%dms, maxRefresh=%dms%n",
					profile.name, graph.expectedAccountAddresses.size(), startingRatingCount, graph.getActiveRatingCount(),
					churnRounds, changedRatings, removedRatings, snapshotCount, totalRefreshTime, averageRefreshTime,
					maxRefreshTime);
		}
	}

	private ChurnResult applyDeterministicChurn(Repository repository, SyntheticTrustGraph graph, int round,
			int maxRatingChanges, int maxRatingRemovals) throws DataException {
		int removedRatings = removeSubjectRatings(repository, graph, round, maxRatingRemovals);
		int changedRatings = changeActiveRatings(repository, graph, round, maxRatingChanges);

		return new ChurnResult(changedRatings, removedRatings);
	}

	private int removeSubjectRatings(Repository repository, SyntheticTrustGraph graph, int round, int maxRatingRemovals)
			throws DataException {
		List<SyntheticRating> activeRatings = graph.getActiveRatings();
		if (activeRatings.isEmpty() || maxRatingRemovals <= 0)
			return 0;

		int removedRatings = 0;
		int start = Math.floorMod(round * 11, activeRatings.size());
		for (int i = 0; i < activeRatings.size() && removedRatings < maxRatingRemovals; ++i) {
			SyntheticRating rating = activeRatings.get((start + i * 13) % activeRatings.size());
			if (rating.category != AccountRatingCategory.SUBJECT || !rating.active)
				continue;

			rating.remove(repository);
			++removedRatings;
		}

		return removedRatings;
	}

	private int changeActiveRatings(Repository repository, SyntheticTrustGraph graph, int round, int maxRatingChanges)
			throws DataException {
		List<SyntheticRating> activeRatings = graph.getActiveRatings();
		if (activeRatings.isEmpty() || maxRatingChanges <= 0)
			return 0;

		int changedRatings = 0;
		int start = Math.floorMod(round * 7, activeRatings.size());
		for (int i = 0; i < activeRatings.size() && changedRatings < maxRatingChanges; ++i) {
			SyntheticRating rating = activeRatings.get((start + i * 5) % activeRatings.size());
			if (!rating.active)
				continue;

			int updatedRating = rating.rating == 4 ? 3 : 4;
			rating.change(repository, updatedRating);
			++changedRatings;
		}

		return changedRatings;
	}

	private void assertDerivedGraphShape(Repository repository, SyntheticTrustGraph graph,
			List<AccountTrustDerivationData> derivedAccounts) throws DataException {
		assertEquals(graph.getActiveRatingCount(), repository.getAccountRatingRepository()
				.getRatings(null, null, null, null, null, null)
				.size());
		assertEquals(graph.expectedAccountAddresses.size(), derivedAccounts.size());

		Set<String> derivedAddresses = new TreeSet<>();
		Map<AccountRatingCategory, Integer> nonZeroScoreCounts = new HashMap<>();
		for (AccountRatingCategory category : AccountRatingCategory.values())
			nonZeroScoreCounts.put(category, 0);

		for (AccountTrustDerivationData derivedAccount : derivedAccounts) {
			derivedAddresses.add(derivedAccount.getAccountAddress());
			assertEquals(AccountRatingCategory.values().length, derivedAccount.getCategories().size());

			Set<AccountRatingCategory> categories = EnumSet.noneOf(AccountRatingCategory.class);
			for (AccountTrustCategoryData categoryData : derivedAccount.getCategories()) {
				categories.add(categoryData.getCategory());
				if (categoryData.getScore() != 0L)
					nonZeroScoreCounts.merge(categoryData.getCategory(), 1, Integer::sum);
			}
			assertEquals(EnumSet.allOf(AccountRatingCategory.class), categories);
		}

		assertEquals(graph.expectedAccountAddresses, derivedAddresses);
		for (AccountRatingCategory category : AccountRatingCategory.values())
			assertTrue("Synthetic graph should produce non-zero " + category + " scores",
					nonZeroScoreCounts.get(category) > 0);
	}

	private void assertSnapshotRowsAreComplete(Set<String> expectedAccountAddresses,
			List<AccountTrustSnapshotData> snapshots) {
		Map<String, Set<AccountRatingCategory>> categoriesByAccount = new HashMap<>();

		for (AccountTrustSnapshotData snapshot : snapshots)
			categoriesByAccount.computeIfAbsent(snapshot.getAccountAddress(), ignored -> EnumSet.noneOf(AccountRatingCategory.class))
					.add(snapshot.getCategory());

		assertEquals(expectedAccountAddresses, categoriesByAccount.keySet());
		for (String accountAddress : expectedAccountAddresses)
			assertEquals(EnumSet.allOf(AccountRatingCategory.class), categoriesByAccount.get(accountAddress));
	}

	private SyntheticTrustGraph createSyntheticTrustGraph(Repository repository, SyntheticTrustGraphProfile profile)
			throws DataException {
		Set<String> expectedAccountAddresses = new TreeSet<>(getActiveMintingSeedAddresses(repository));
		List<SyntheticRating> ratings = new ArrayList<>();
		List<PrivateKeyAccount> seeds = generateKnownAccounts(repository, profile.seedCount, expectedAccountAddresses);
		List<PrivateKeyAccount> managerEvaluators = new ArrayList<>();

		for (PrivateKeyAccount seed : seeds) {
			ensureMintingGroupMember(repository, seed);
			for (int i = 0; i < profile.managerPathsPerSeed; ++i) {
				PrivateKeyAccount firstHop = generateKnownAccount(repository, expectedAccountAddresses);
				PrivateKeyAccount secondHop = generateKnownAccount(repository, expectedAccountAddresses);
				PrivateKeyAccount thirdHop = generateKnownAccount(repository, expectedAccountAddresses);
				PrivateKeyAccount evaluator = generateKnownAccount(repository, expectedAccountAddresses);
				managerEvaluators.add(evaluator);

				saveRating(repository, ratings, seed, firstHop, AccountRatingCategory.MANAGER);
				saveRating(repository, ratings, firstHop, secondHop, AccountRatingCategory.MANAGER);
				saveRating(repository, ratings, secondHop, thirdHop, AccountRatingCategory.MANAGER);
				saveRating(repository, ratings, thirdHop, evaluator, AccountRatingCategory.MANAGER);
			}
		}

		expectedAccountAddresses.addAll(getActiveMintingSeedAddresses(repository));

		List<PrivateKeyAccount> managerTargets = generateKnownAccounts(repository, profile.managerTargetCount,
				expectedAccountAddresses);
		List<PrivateKeyAccount> trainerTargets = generateKnownAccounts(repository, profile.trainerTargetCount,
				expectedAccountAddresses);
		List<PrivateKeyAccount> playerTargets = generateKnownAccounts(repository, profile.playerTargetCount,
				expectedAccountAddresses);
		List<PrivateKeyAccount> subjectTargets = generateKnownAccounts(repository, profile.subjectTargetCount,
				expectedAccountAddresses);

		for (PrivateKeyAccount evaluator : managerEvaluators) {
			for (PrivateKeyAccount managerTarget : managerTargets)
				saveRating(repository, ratings, evaluator, managerTarget, AccountRatingCategory.MANAGER);
		}

		for (PrivateKeyAccount managerTarget : managerTargets) {
			for (PrivateKeyAccount trainerTarget : trainerTargets)
				saveRating(repository, ratings, managerTarget, trainerTarget, AccountRatingCategory.TRAINER);
		}

		for (PrivateKeyAccount trainerTarget : trainerTargets) {
			for (PrivateKeyAccount playerTarget : playerTargets)
				saveRating(repository, ratings, trainerTarget, playerTarget, AccountRatingCategory.PLAYER);
		}

		for (PrivateKeyAccount playerTarget : playerTargets) {
			for (PrivateKeyAccount subjectTarget : subjectTargets)
				saveRating(repository, ratings, playerTarget, subjectTarget, AccountRatingCategory.SUBJECT);
		}

		return new SyntheticTrustGraph(expectedAccountAddresses, ratings);
	}

	private List<PrivateKeyAccount> generateKnownAccounts(Repository repository, int count,
			Set<String> expectedAccountAddresses) throws DataException {
		List<PrivateKeyAccount> accounts = new ArrayList<>();
		for (int i = 0; i < count; ++i)
			accounts.add(generateKnownAccount(repository, expectedAccountAddresses));

		return accounts;
	}

	private PrivateKeyAccount generateKnownAccount(Repository repository, Set<String> expectedAccountAddresses)
			throws DataException {
		PrivateKeyAccount account = Common.generateRandomSeedAccount(repository);
		AccountTrustTestUtils.ensureKnownAccount(repository, account);
		expectedAccountAddresses.add(account.getAddress());
		return account;
	}

	private void ensureMintingGroupMember(Repository repository, PrivateKeyAccount account) throws DataException {
		AccountTrustTestUtils.ensureKnownAccount(repository, account);
		if (repository.getGroupRepository().memberExists(TestChainBootstrapUtils.MINTING_GROUP_ID, account.getAddress()))
			return;

		GroupData groupData = repository.getGroupRepository().fromGroupId(TestChainBootstrapUtils.MINTING_GROUP_ID);
		repository.getGroupRepository().save(new GroupMemberData(TestChainBootstrapUtils.MINTING_GROUP_ID,
				account.getAddress(), groupData.getCreated(), groupData.getReference()));
	}

	private Set<String> getActiveMintingSeedAddresses(Repository repository) throws DataException {
		List<Integer> mintingGroupIds = Groups.getGroupIdsToMint(BlockChain.getInstance(),
				repository.getBlockRepository().getBlockchainHeight() + 1);
		Set<String> seedAddresses = new HashSet<>(Groups.getAllMembers(repository.getGroupRepository(), mintingGroupIds));
		seedAddresses.remove(Group.NULL_OWNER_ADDRESS);
		return seedAddresses;
	}

	private void saveRating(Repository repository, List<SyntheticRating> ratings, PrivateKeyAccount rater,
			PrivateKeyAccount target, AccountRatingCategory category) throws DataException {
		AccountTrustTestUtils.saveAccountRating(repository, rater, target, category, 4);
		ratings.add(new SyntheticRating(rater, target, category, 4));
	}

	private static class ChurnResult {
		private final int changedRatings;
		private final int removedRatings;

		private ChurnResult(int changedRatings, int removedRatings) {
			this.changedRatings = changedRatings;
			this.removedRatings = removedRatings;
		}
	}

	private static class SyntheticTrustGraph {
		private final Set<String> expectedAccountAddresses;
		private final List<SyntheticRating> ratings;

		private SyntheticTrustGraph(Set<String> expectedAccountAddresses, List<SyntheticRating> ratings) {
			this.expectedAccountAddresses = Collections.unmodifiableSet(new TreeSet<>(expectedAccountAddresses));
			this.ratings = ratings;
		}

		private List<SyntheticRating> getActiveRatings() {
			List<SyntheticRating> activeRatings = new ArrayList<>();
			for (SyntheticRating rating : this.ratings) {
				if (rating.active)
					activeRatings.add(rating);
			}

			return activeRatings;
		}

		private int getActiveRatingCount() {
			int ratingCount = 0;
			for (SyntheticRating rating : this.ratings) {
				if (rating.active)
					++ratingCount;
			}

			return ratingCount;
		}
	}

	private static class SyntheticRating {
		private final PrivateKeyAccount rater;
		private final PrivateKeyAccount target;
		private final AccountRatingCategory category;
		private int rating;
		private boolean active = true;

		private SyntheticRating(PrivateKeyAccount rater, PrivateKeyAccount target, AccountRatingCategory category,
				int rating) {
			this.rater = rater;
			this.target = target;
			this.category = category;
			this.rating = rating;
		}

		private void change(Repository repository, int updatedRating) throws DataException {
			repository.getAccountRatingRepository()
					.save(new AccountRatingData(this.target.getPublicKey(), this.rater.getPublicKey(), this.category,
							updatedRating));
			this.rating = updatedRating;
			this.active = true;
		}

		private void remove(Repository repository) throws DataException {
			repository.getAccountRatingRepository()
					.delete(this.target.getPublicKey(), this.rater.getPublicKey(), this.category);
			this.active = false;
		}
	}

	private static class SyntheticTrustGraphProfile {
		private final String name;
		private final int seedCount;
		private final int managerPathsPerSeed;
		private final int managerTargetCount;
		private final int trainerTargetCount;
		private final int playerTargetCount;
		private final int subjectTargetCount;

		private SyntheticTrustGraphProfile(String name, int seedCount, int managerPathsPerSeed, int managerTargetCount,
				int trainerTargetCount, int playerTargetCount, int subjectTargetCount) {
			this.name = name;
			this.seedCount = seedCount;
			this.managerPathsPerSeed = managerPathsPerSeed;
			this.managerTargetCount = managerTargetCount;
			this.trainerTargetCount = trainerTargetCount;
			this.playerTargetCount = playerTargetCount;
			this.subjectTargetCount = subjectTargetCount;
		}
	}
}
