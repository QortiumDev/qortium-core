package org.qortium.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.AccountTrustWeight;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.resource.AccountRatingsResource;
import org.qortium.data.account.AccountRating;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountRatingCooldownData;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
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
import org.qortium.transaction.Transaction;
import org.qortium.utils.Base58;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AccountTrustLaunchStressScenarioTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testOneTrustedSupporterCannotPromoteManyTargetsAlone() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporter = Common.getTestAccount(repository, "bob");
			List<PrivateKeyAccount> targets = generateKnownAccounts(repository, 4);

			ensureKnownAccounts(repository, seedAccount, supporter);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporter);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			for (PrivateKeyAccount target : targets)
				TransactionUtils.signAndMint(repository,
						ratingData(supporter, target, AccountRatingCategory.SUBJECT, 4), supporter);

			for (PrivateKeyAccount target : targets) {
				AccountTrustSnapshotData targetSubject = findSnapshot(repository, target.getAddress(),
						AccountRatingCategory.SUBJECT);

				assertEquals(128_000_000L, targetSubject.getScore());
				assertEquals(5_000_000L, targetSubject.getLevelScore());
				assertEquals(0, targetSubject.getLevel());
				assertEquals(AccountTrustStatus.UNVERIFIED, targetSubject.getMappedTrustStatus());
				assertEquals(0, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, targetSubject));
			}
		}
	}

	@Test
	public void testTwoIndependentSupportersCanPromoteManyTargets() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "dilbert");
			List<PrivateKeyAccount> targets = generateKnownAccounts(repository, 4);

			prepareTrustedSupporters(repository, seedAccount, supporterA, supporterB);

			for (PrivateKeyAccount target : targets) {
				TransactionUtils.signAndMint(repository,
						ratingData(supporterA, target, AccountRatingCategory.SUBJECT, 4), supporterA);
				TransactionUtils.signAndMint(repository,
						ratingData(supporterB, target, AccountRatingCategory.SUBJECT, 4), supporterB);
			}

			for (PrivateKeyAccount target : targets) {
				AccountTrustSnapshotData targetSubject = findSnapshot(repository, target.getAddress(),
						AccountRatingCategory.SUBJECT);

				assertEquals(128_000_000L, targetSubject.getScore());
				assertEquals(100_000_000L, targetSubject.getLevelScore());
				assertEquals(3, targetSubject.getLevel());
				assertEquals(AccountTrustStatus.GOLD, targetSubject.getMappedTrustStatus());
				assertEquals(1_000, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, targetSubject));
			}
		}
	}

	@Test
	public void testMixedOnboardingBatchProducesExpectedTierSpread() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount auditOnlyTarget = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount bronzeTarget = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount silverTarget = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount goldTarget = Common.generateRandomSeedAccount(repository);

			ensureKnownAccounts(repository, auditOnlyTarget, bronzeTarget, silverTarget, goldTarget);
			prepareTrustedSupporters(repository, seedAccount, supporterA, supporterB);

			TransactionUtils.signAndMint(repository,
					ratingData(supporterA, auditOnlyTarget, AccountRatingCategory.SUBJECT, 4), supporterA);
			rateByBothSupporters(repository, supporterA, supporterB, bronzeTarget, 1);
			rateByBothSupporters(repository, supporterA, supporterB, silverTarget, 2);
			rateByBothSupporters(repository, supporterA, supporterB, goldTarget, 4);

			assertSubjectStatus(repository, auditOnlyTarget, AccountTrustStatus.UNVERIFIED, 0);
			assertSubjectStatus(repository, bronzeTarget, AccountTrustStatus.BRONZE, 400);
			assertSubjectStatus(repository, silverTarget, AccountTrustStatus.SILVER, 700);
			assertSubjectStatus(repository, goldTarget, AccountTrustStatus.GOLD, 1_000);
		}
	}

	@Test
	public void testSupportRemovalAfterCooldownDropsPromotedTarget() throws Exception {
		AccountTrustTestUtils.useAccountRatingCooldown(3);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount target = Common.generateRandomSeedAccount(repository);

			ensureKnownAccounts(repository, target);
			prepareTrustedSupporters(repository, seedAccount, supporterA, supporterB);
			rateByBothSupporters(repository, supporterA, supporterB, target, 4);

			assertSubjectStatus(repository, target, AccountTrustStatus.GOLD, 1_000);

			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_CHANGE_TOO_SOON,
					Transaction.fromData(repository,
							ratingData(supporterA, target, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING))
							.isValid());

			AccountRatingCooldownData blockedCooldown = getCooldown(target, supporterA, AccountRatingCategory.SUBJECT);
			assertFalse(blockedCooldown.isCanChangeNow());
			assertEquals(1, blockedCooldown.getBlocksRemaining());

			BlockUtils.mintBlock(repository);

			AccountRatingCooldownData allowedCooldown = getCooldown(target, supporterA, AccountRatingCategory.SUBJECT);
			assertTrue(allowedCooldown.isCanChangeNow());
			assertEquals(0, allowedCooldown.getBlocksRemaining());

			TransactionUtils.signAndMint(repository,
					ratingData(supporterA, target, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING), supporterA);

			assertNull(repository.getAccountRatingRepository().getRating(target.getPublicKey(),
					supporterA.getPublicKey(), AccountRatingCategory.SUBJECT));
			assertSubjectStatus(repository, target, AccountTrustStatus.UNVERIFIED, 0);
		}
	}

	@Test
	public void testSuspiciousTargetRecoversAfterOneNegativeRatingRemoval() throws Exception {
		AccountTrustTestUtils.useAccountRatingCooldown(3);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount raterA = Common.getTestAccount(repository, "bob");
			TestAccount raterB = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount target = Common.generateRandomSeedAccount(repository);

			ensureMintingGroupMember(repository, target);
			prepareTrustedSupporters(repository, seedAccount, raterA, raterB);
			rateByBothSupporters(repository, raterA, raterB, target, -2);

			AccountTrustSnapshotData suspiciousTarget = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.SUSPICIOUS, suspiciousTarget.getMappedTrustStatus());
			assertFalse(new Account(repository, target.getAddress()).canMint(false));

			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_CHANGE_TOO_SOON,
					Transaction.fromData(repository,
							ratingData(raterA, target, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING))
							.isValid());

			BlockUtils.mintBlock(repository);

			TransactionUtils.signAndMint(repository,
					ratingData(raterA, target, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING), raterA);

			AccountTrustSnapshotData recoveredTarget = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.UNVERIFIED, recoveredTarget.getMappedTrustStatus());
			assertTrue(new Account(repository, target.getAddress()).canMint(false));
		}
	}

	@Test
	public void testExtraMintingSeedsDiluteLaunchSupportOutcome() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount target = Common.generateRandomSeedAccount(repository);
			List<PrivateKeyAccount> extraSeeds = generateKnownAccounts(repository, 5);

			ensureKnownAccounts(repository, target);
			for (PrivateKeyAccount extraSeed : extraSeeds)
				ensureMintingGroupMember(repository, extraSeed);

			prepareTrustedSupporters(repository, seedAccount, supporterA, supporterB);
			rateByBothSupporters(repository, supporterA, supporterB, target, 4);

			AccountTrustSnapshotData targetSubject = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.BRONZE, targetSubject.getMappedTrustStatus());
			assertEquals(1, targetSubject.getLevel());
			assertEquals(10_000_000L, targetSubject.getLevelScore());
			assertEquals(400, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, targetSubject));
		}
	}

	@Test
	public void testMintingSeedFarmSubjectOnlyRatingsStayUnverifiedWithZeroWeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<PrivateKeyAccount> farmAccounts = generateKnownAccounts(repository, 4);

			for (PrivateKeyAccount farmAccount : farmAccounts) {
				ensureMintingGroupMember(repository, farmAccount);
				AccountTrustTestUtils.setBlocksMinted(repository, farmAccount, 1_000);
			}

			for (int i = 0; i < farmAccounts.size(); ++i) {
				PrivateKeyAccount rater = farmAccounts.get(i);
				PrivateKeyAccount target = farmAccounts.get((i + 1) % farmAccounts.size());
				AccountTrustTestUtils.saveAccountRating(repository, rater, target, AccountRatingCategory.SUBJECT, 4);
			}
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			for (PrivateKeyAccount farmAccount : farmAccounts) {
				assertSubjectStatus(repository, farmAccount, AccountTrustStatus.UNVERIFIED, 0);
				assertTrue("Unverified Minting group farm accounts should still be able to mint",
						new Account(repository, farmAccount.getAddress()).canMint(false));
			}
		}
	}

	@Test
	public void testMintingSeedFarmSharedBranchRatingsStayUnverifiedWithZeroWeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount farmSeed = Common.generateRandomSeedAccount(repository);
			List<PrivateKeyAccount> sharedBranchPlayers = generateKnownAccounts(repository, 2);
			PrivateKeyAccount farmTarget = Common.generateRandomSeedAccount(repository);

			ensureKnownAccounts(repository, farmSeed, farmTarget);
			ensureMintingGroupMember(repository, farmSeed);
			ensureMintingGroupMember(repository, farmTarget);
			AccountTrustTestUtils.setBlocksMinted(repository, farmTarget, 1_000);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatingsFromSharedManagerBranch(repository, farmSeed,
					sharedBranchPlayers);
			for (PrivateKeyAccount player : sharedBranchPlayers)
				AccountTrustTestUtils.saveAccountRating(repository, player, farmTarget, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			AccountTrustSnapshotData farmTargetSubject = findSnapshot(repository, farmTarget.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertTrue("Same-branch farm support should remain auditable", farmTargetSubject.getScore() > 0);
			assertEquals("Same-branch farm support should not satisfy branch independence",
					0, farmTargetSubject.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, farmTargetSubject.getMappedTrustStatus());
			assertEquals(0, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, farmTargetSubject));
			assertTrue("Same-branch farm support should not block minting",
					new Account(repository, farmTarget.getAddress()).canMint(false));
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
		ensureKnownAccounts(repository, seedAccount, supporterA, supporterB);
		AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporterA);
		AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporterB);
		AccountTrustTestUtils.refreshTrustSnapshots(repository);
	}

	private void rateByBothSupporters(Repository repository, PrivateKeyAccount supporterA,
			PrivateKeyAccount supporterB, PrivateKeyAccount target, int rating) throws DataException {
		TransactionUtils.signAndMint(repository,
				ratingData(supporterA, target, AccountRatingCategory.SUBJECT, rating), supporterA);
		TransactionUtils.signAndMint(repository,
				ratingData(supporterB, target, AccountRatingCategory.SUBJECT, rating), supporterB);
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), target.getPublicKey(), category, rating);
	}

	private void ensureKnownAccounts(Repository repository, PrivateKeyAccount... accounts) throws DataException {
		for (PrivateKeyAccount account : accounts)
			AccountTrustTestUtils.ensureKnownAccount(repository, account);
	}

	private void ensureMintingGroupMember(Repository repository, PrivateKeyAccount account) throws DataException {
		AccountTrustTestUtils.ensureKnownAccount(repository, account);
		if (repository.getGroupRepository().memberExists(TestChainBootstrapUtils.MINTING_GROUP_ID, account.getAddress()))
			return;

		GroupData groupData = repository.getGroupRepository().fromGroupId(TestChainBootstrapUtils.MINTING_GROUP_ID);
		repository.getGroupRepository().save(new GroupMemberData(TestChainBootstrapUtils.MINTING_GROUP_ID,
				account.getAddress(), groupData.getCreated(), groupData.getReference()));
	}

	private void assertSubjectStatus(Repository repository, PrivateKeyAccount target,
			AccountTrustStatus expectedStatus, int expectedVoteWeight) throws DataException {
		AccountTrustSnapshotData targetSubject = findSnapshot(repository, target.getAddress(),
				AccountRatingCategory.SUBJECT);

		assertEquals(expectedStatus, targetSubject.getMappedTrustStatus());
		assertEquals(expectedVoteWeight, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, targetSubject));
	}

	private AccountTrustSnapshotData findSnapshot(Repository repository, String accountAddress,
			AccountRatingCategory category) throws DataException {
		return repository.getAccountRatingRepository().getTrustDerivationSnapshots(accountAddress).stream()
				.filter(snapshot -> snapshot.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing snapshot for " + accountAddress + " in category " + category));
	}

	private AccountRatingCooldownData getCooldown(PrivateKeyAccount target, PrivateKeyAccount rater,
			AccountRatingCategory category) {
		return new AccountRatingsResource().getAccountRatingCooldown(Base58.encode(target.getPublicKey()),
				Base58.encode(rater.getPublicKey()), category.name());
	}
}
