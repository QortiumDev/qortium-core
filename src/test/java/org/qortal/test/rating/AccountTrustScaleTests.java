package org.qortal.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.AccountTrustDerivation;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.BlockChain;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustCategoryData;
import org.qortal.data.account.AccountTrustDerivationData;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.group.GroupData;
import org.qortal.data.group.GroupMemberData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountTrustTestUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestChainBootstrapUtils;
import org.qortal.utils.Groups;

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
					profile.name, graph.expectedAccountAddresses.size(), graph.ratingCount, snapshotCount, deriveTime,
					refreshTime, totalTime);
		}
	}

	private void assertDerivedGraphShape(Repository repository, SyntheticTrustGraph graph,
			List<AccountTrustDerivationData> derivedAccounts) throws DataException {
		assertEquals(graph.ratingCount, repository.getAccountRatingRepository()
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
		List<PrivateKeyAccount> seeds = generateKnownAccounts(repository, profile.seedCount, expectedAccountAddresses);
		List<PrivateKeyAccount> managerEvaluators = new ArrayList<>();
		int ratingCount = 0;

		for (PrivateKeyAccount seed : seeds) {
			ensureMintingGroupMember(repository, seed);
			for (int i = 0; i < profile.managerPathsPerSeed; ++i) {
				PrivateKeyAccount firstHop = generateKnownAccount(repository, expectedAccountAddresses);
				PrivateKeyAccount secondHop = generateKnownAccount(repository, expectedAccountAddresses);
				PrivateKeyAccount thirdHop = generateKnownAccount(repository, expectedAccountAddresses);
				PrivateKeyAccount evaluator = generateKnownAccount(repository, expectedAccountAddresses);
				managerEvaluators.add(evaluator);

				ratingCount += saveRating(repository, seed, firstHop, AccountRatingCategory.MANAGER);
				ratingCount += saveRating(repository, firstHop, secondHop, AccountRatingCategory.MANAGER);
				ratingCount += saveRating(repository, secondHop, thirdHop, AccountRatingCategory.MANAGER);
				ratingCount += saveRating(repository, thirdHop, evaluator, AccountRatingCategory.MANAGER);
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
				ratingCount += saveRating(repository, evaluator, managerTarget, AccountRatingCategory.MANAGER);
		}

		for (PrivateKeyAccount managerTarget : managerTargets) {
			for (PrivateKeyAccount trainerTarget : trainerTargets)
				ratingCount += saveRating(repository, managerTarget, trainerTarget, AccountRatingCategory.TRAINER);
		}

		for (PrivateKeyAccount trainerTarget : trainerTargets) {
			for (PrivateKeyAccount playerTarget : playerTargets)
				ratingCount += saveRating(repository, trainerTarget, playerTarget, AccountRatingCategory.PLAYER);
		}

		for (PrivateKeyAccount playerTarget : playerTargets) {
			for (PrivateKeyAccount subjectTarget : subjectTargets)
				ratingCount += saveRating(repository, playerTarget, subjectTarget, AccountRatingCategory.SUBJECT);
		}

		return new SyntheticTrustGraph(expectedAccountAddresses, ratingCount);
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

	private int saveRating(Repository repository, PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category) throws DataException {
		AccountTrustTestUtils.saveAccountRating(repository, rater, target, category, 4);
		return 1;
	}

	private static class SyntheticTrustGraph {
		private final Set<String> expectedAccountAddresses;
		private final int ratingCount;

		private SyntheticTrustGraph(Set<String> expectedAccountAddresses, int ratingCount) {
			this.expectedAccountAddresses = Collections.unmodifiableSet(new TreeSet<>(expectedAccountAddresses));
			this.ratingCount = ratingCount;
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
