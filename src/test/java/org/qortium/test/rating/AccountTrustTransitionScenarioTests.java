package org.qortium.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.AccountTrustWeight;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.account.AccountRating;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountRatingData;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.account.AccountTrustStatusChangeData;
import org.qortium.data.group.GroupData;
import org.qortium.data.group.GroupMemberData;
import org.qortium.data.transaction.RateAccountTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountTrustTestUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AccountTrustTransitionScenarioTests extends Common {

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		AccountTrustTestUtils.useAccountRatingCooldown(0);
	}

	@Test
	public void testMintingSeedSetChangesDiluteAndRestorePromotedSubject() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "dilbert");
			TestAccount target = Common.getTestAccount(repository, "chloe");
			List<PrivateKeyAccount> extraSeeds = generateKnownAccounts(repository, 5);

			ensureKnownAccounts(repository, seedAccount, supporterA, supporterB, target);
			prepareTrustedSupporters(repository, seedAccount, supporterA, supporterB);
			rateByBothSupporters(repository, supporterA, supporterB, target, 4);
			assertSubjectStatus(repository, target, AccountTrustStatus.GOLD, 1_000);

			for (PrivateKeyAccount extraSeed : extraSeeds)
				addMintingSeed(repository, extraSeed);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertSubjectStatus(repository, target, AccountTrustStatus.BRONZE, 400);

			for (PrivateKeyAccount extraSeed : extraSeeds)
				repository.getGroupRepository().deleteMember(TestChainBootstrapUtils.MINTING_GROUP_ID,
						extraSeed.getAddress());
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertSubjectStatus(repository, target, AccountTrustStatus.GOLD, 1_000);
		}
	}

	@Test
	public void testCategoryChainBreakAndRecoveryPropagatesThroughTrustLayers() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount managerA = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount managerB = Common.generateRandomSeedAccount(repository);
			TestAccount trainerA = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount trainerB = Common.generateRandomSeedAccount(repository);
			TestAccount playerA = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount playerB = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount subject = Common.generateRandomSeedAccount(repository);

			ensureKnownAccounts(repository, seedAccount, managerA, managerB, trainerA, trainerB, playerA, playerB,
					subject);
			AccountTrustTestUtils.saveDerivedManagerLevelTwoRatings(repository, seedAccount,
					Arrays.asList(managerA, managerB));
			AccountTrustTestUtils.saveAccountRating(repository, managerA, trainerA, AccountRatingCategory.TRAINER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, managerB, trainerA, AccountRatingCategory.TRAINER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, managerA, trainerB, AccountRatingCategory.TRAINER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, managerB, trainerB, AccountRatingCategory.TRAINER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, trainerA, playerA, AccountRatingCategory.PLAYER, 2);
			AccountTrustTestUtils.saveAccountRating(repository, trainerB, playerA, AccountRatingCategory.PLAYER, 2);
			AccountTrustTestUtils.saveAccountRating(repository, trainerA, playerB, AccountRatingCategory.PLAYER, 2);
			AccountTrustTestUtils.saveAccountRating(repository, trainerB, playerB, AccountRatingCategory.PLAYER, 2);
			AccountTrustTestUtils.saveAccountRating(repository, playerA, subject, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.saveAccountRating(repository, playerB, subject, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertStatus(repository, managerA, AccountRatingCategory.MANAGER, AccountTrustStatus.SILVER);
			assertStatus(repository, managerB, AccountRatingCategory.MANAGER, AccountTrustStatus.SILVER);
			assertStatus(repository, trainerA, AccountRatingCategory.TRAINER, AccountTrustStatus.SILVER);
			assertStatus(repository, trainerB, AccountRatingCategory.TRAINER, AccountTrustStatus.SILVER);
			assertStatus(repository, playerA, AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);
			assertStatus(repository, playerB, AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);
			assertStatus(repository, subject, AccountRatingCategory.SUBJECT, AccountTrustStatus.GOLD);

			List<AccountRatingData> removedManagerRatings = new ArrayList<>();
			removedManagerRatings.addAll(removeInboundRatings(repository, managerA, AccountRatingCategory.MANAGER));
			removedManagerRatings.addAll(removeInboundRatings(repository, managerB, AccountRatingCategory.MANAGER));
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertStatus(repository, managerA, AccountRatingCategory.MANAGER, AccountTrustStatus.UNVERIFIED);
			assertStatus(repository, managerB, AccountRatingCategory.MANAGER, AccountTrustStatus.UNVERIFIED);
			assertStatus(repository, trainerA, AccountRatingCategory.TRAINER, AccountTrustStatus.UNVERIFIED);
			assertStatus(repository, trainerB, AccountRatingCategory.TRAINER, AccountTrustStatus.UNVERIFIED);
			assertStatus(repository, playerA, AccountRatingCategory.PLAYER, AccountTrustStatus.UNVERIFIED);
			assertStatus(repository, playerB, AccountRatingCategory.PLAYER, AccountTrustStatus.UNVERIFIED);
			assertStatus(repository, subject, AccountRatingCategory.SUBJECT, AccountTrustStatus.UNVERIFIED);

			restoreRatings(repository, removedManagerRatings);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertStatus(repository, managerA, AccountRatingCategory.MANAGER, AccountTrustStatus.SILVER);
			assertStatus(repository, managerB, AccountRatingCategory.MANAGER, AccountTrustStatus.SILVER);
			assertStatus(repository, trainerA, AccountRatingCategory.TRAINER, AccountTrustStatus.SILVER);
			assertStatus(repository, trainerB, AccountRatingCategory.TRAINER, AccountTrustStatus.SILVER);
			assertStatus(repository, playerA, AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);
			assertStatus(repository, playerB, AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);
			assertStatus(repository, subject, AccountRatingCategory.SUBJECT, AccountTrustStatus.GOLD);
		}
	}

	@Test
	public void testEvaluatorCategoryTrustLossDropsDownstreamSubjectSupport() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "dilbert");
			TestAccount target = Common.getTestAccount(repository, "chloe");

			ensureKnownAccounts(repository, seedAccount, supporterA, supporterB, target);
			prepareTrustedSupporters(repository, seedAccount, supporterA, supporterB);
			rateByBothSupporters(repository, supporterA, supporterB, target, 4);

			assertStatus(repository, supporterA, AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);
			assertStatus(repository, supporterB, AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);
			assertSubjectStatus(repository, target, AccountTrustStatus.GOLD, 1_000);

			List<AccountRatingData> removedPlayerRatings = removeInboundRatings(repository, supporterA,
					AccountRatingCategory.PLAYER);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertStatus(repository, supporterA, AccountRatingCategory.PLAYER, AccountTrustStatus.UNVERIFIED);
			assertStatus(repository, supporterB, AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);
			assertSubjectStatus(repository, target, AccountTrustStatus.UNVERIFIED, 0);

			restoreRatings(repository, removedPlayerRatings);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertStatus(repository, supporterA, AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);
			assertSubjectStatus(repository, target, AccountTrustStatus.GOLD, 1_000);
		}
	}

	@Test
	public void testSignedSupportRemovalAndOrphanRestoresPromotedSubject() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "dilbert");
			TestAccount target = Common.getTestAccount(repository, "chloe");

			ensureKnownAccounts(repository, seedAccount, supporterA, supporterB, target);
			prepareTrustedSupporters(repository, seedAccount, supporterA, supporterB);

			TransactionUtils.signAndMint(repository,
					ratingData(supporterA, target, AccountRatingCategory.SUBJECT, 4), supporterA);
			TransactionUtils.signAndMint(repository,
					ratingData(supporterB, target, AccountRatingCategory.SUBJECT, 4), supporterB);

			assertSubjectStatus(repository, target, AccountTrustStatus.GOLD, 1_000);
			assertEquals(1, findStatusChanges(repository, target, AccountTrustStatus.UNVERIFIED,
					AccountTrustStatus.GOLD).size());

			TransactionUtils.signAndMint(repository,
					ratingData(supporterA, target, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING),
					supporterA);

			assertNull(repository.getAccountRatingRepository().getRating(target.getPublicKey(),
					supporterA.getPublicKey(), AccountRatingCategory.SUBJECT));
			assertSubjectStatus(repository, target, AccountTrustStatus.UNVERIFIED, 0);
			assertEquals(1, findStatusChanges(repository, target, AccountTrustStatus.GOLD,
					AccountTrustStatus.UNVERIFIED).size());

			BlockUtils.orphanLastBlock(repository);

			assertEquals(4, repository.getAccountRatingRepository()
					.getRating(target.getPublicKey(), supporterA.getPublicKey(), AccountRatingCategory.SUBJECT)
					.getRating());
			assertSubjectStatus(repository, target, AccountTrustStatus.GOLD, 1_000);
			assertTrue(findStatusChanges(repository, target, AccountTrustStatus.GOLD,
					AccountTrustStatus.UNVERIFIED).isEmpty());
			assertEquals(1, findStatusChanges(repository, target, AccountTrustStatus.UNVERIFIED,
					AccountTrustStatus.GOLD).size());
		}
	}

	private List<PrivateKeyAccount> generateKnownAccounts(Repository repository, int count) throws DataException {
		List<PrivateKeyAccount> accounts = new ArrayList<>();
		for (int i = 0; i < count; ++i) {
			PrivateKeyAccount account = Common.generateRandomSeedAccount(repository);
			AccountTrustTestUtils.ensureKnownAccount(repository, account);
			accounts.add(account);
		}

		return accounts;
	}

	private void prepareTrustedSupporters(Repository repository, PrivateKeyAccount seedAccount,
			PrivateKeyAccount supporterA, PrivateKeyAccount supporterB) throws DataException {
		AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporterA);
		AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporterB);
		AccountTrustTestUtils.refreshTrustSnapshots(repository);
	}

	private void rateByBothSupporters(Repository repository, PrivateKeyAccount supporterA,
			PrivateKeyAccount supporterB, PrivateKeyAccount target, int rating) throws DataException {
		AccountTrustTestUtils.saveAccountRating(repository, supporterA, target, AccountRatingCategory.SUBJECT, rating);
		AccountTrustTestUtils.saveAccountRating(repository, supporterB, target, AccountRatingCategory.SUBJECT, rating);
		AccountTrustTestUtils.refreshTrustSnapshots(repository);
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), target.getPublicKey(), category, rating);
	}

	private void ensureKnownAccounts(Repository repository, PrivateKeyAccount... accounts) throws DataException {
		for (PrivateKeyAccount account : accounts)
			AccountTrustTestUtils.ensureKnownAccount(repository, account);
	}

	private void addMintingSeed(Repository repository, PrivateKeyAccount account) throws DataException {
		AccountTrustTestUtils.ensureKnownAccount(repository, account);
		if (repository.getGroupRepository().memberExists(TestChainBootstrapUtils.MINTING_GROUP_ID, account.getAddress()))
			return;

		GroupData groupData = repository.getGroupRepository().fromGroupId(TestChainBootstrapUtils.MINTING_GROUP_ID);
		repository.getGroupRepository().save(new GroupMemberData(TestChainBootstrapUtils.MINTING_GROUP_ID,
				account.getAddress(), groupData.getCreated(), groupData.getReference()));
	}

	private List<AccountRatingData> removeInboundRatings(Repository repository, PrivateKeyAccount target,
			AccountRatingCategory category) throws DataException {
		List<AccountRatingData> ratings = repository.getAccountRatingRepository()
				.getRatings(target.getPublicKey(), null, category, null, null, null);
		for (AccountRatingData rating : ratings)
			repository.getAccountRatingRepository().delete(rating.getTargetPublicKey(), rating.getRaterPublicKey(),
					rating.getCategory());

		return ratings;
	}

	private void restoreRatings(Repository repository, List<AccountRatingData> ratings) throws DataException {
		for (AccountRatingData rating : ratings)
			repository.getAccountRatingRepository().save(rating);
	}

	private void assertStatus(Repository repository, PrivateKeyAccount account, AccountRatingCategory category,
			AccountTrustStatus expectedStatus) throws DataException {
		assertEquals(expectedStatus, findSnapshot(repository, account.getAddress(), category).getMappedTrustStatus());
	}

	private void assertSubjectStatus(Repository repository, PrivateKeyAccount account,
			AccountTrustStatus expectedStatus, int expectedVoteWeight) throws DataException {
		AccountTrustSnapshotData subjectSnapshot = findSnapshot(repository, account.getAddress(),
				AccountRatingCategory.SUBJECT);

		assertEquals(expectedStatus, subjectSnapshot.getMappedTrustStatus());
		assertEquals(expectedVoteWeight, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, subjectSnapshot));
	}

	private List<AccountTrustStatusChangeData> findStatusChanges(Repository repository, PrivateKeyAccount account,
			AccountTrustStatus previousStatus, AccountTrustStatus newStatus) throws DataException {
		return repository.getAccountRatingRepository().getTrustStatusChanges(account.getAddress(),
				AccountRatingCategory.SUBJECT, previousStatus, newStatus, null, null, null);
	}

	private AccountTrustSnapshotData findSnapshot(Repository repository, String accountAddress,
			AccountRatingCategory category) throws DataException {
		return repository.getAccountRatingRepository().getTrustDerivationSnapshots(accountAddress).stream()
				.filter(snapshot -> snapshot.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing snapshot for " + accountAddress + " in category " + category));
	}
}
