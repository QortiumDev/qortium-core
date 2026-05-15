package org.qortal.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.AccountTrustDerivation;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.transaction.RateAccountTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountTrustTestUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TestChainBootstrapUtils;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccountTrustGraphBehaviorTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testIsolatedPositiveFarmRingStaysUnverified() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount farmA = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount farmB = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount farmC = Common.generateRandomSeedAccount(repository);
			List<PrivateKeyAccount> farmAccounts = Arrays.asList(farmA, farmB, farmC);

			for (PrivateKeyAccount account : farmAccounts)
				ensureKnownAccount(repository, account);

			saveAccountRating(repository, farmA, farmB, AccountRatingCategory.MANAGER, 4);
			saveAccountRating(repository, farmB, farmC, AccountRatingCategory.TRAINER, 4);
			saveAccountRating(repository, farmC, farmA, AccountRatingCategory.PLAYER, 4);
			saveAccountRating(repository, farmA, farmC, AccountRatingCategory.SUBJECT, 4);
			refreshTrustSnapshots(repository);

			for (PrivateKeyAccount account : farmAccounts) {
				for (AccountRatingCategory category : AccountRatingCategory.values()) {
					AccountTrustSnapshotData snapshot = findSnapshot(repository, account.getAddress(), category);

					assertEquals("Farm ring should not gain " + category + " score without a seed path",
							0L, snapshot.getScore());
					assertEquals("Farm ring should not gain " + category + " level without a seed path",
							0, snapshot.getLevel());
					assertEquals("Farm ring should remain Unverified without a seed path",
							AccountTrustStatus.UNVERIFIED, snapshot.getMappedTrustStatus());
				}
			}
		}
	}

	@Test
	public void testUntrustedNegativeSubjectRatingDoesNotMakeTargetSuspicious() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			TestChainBootstrapUtils.ensureMintingGroupMember(repository, "bob");
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, chloe);

			saveAccountRating(repository, chloe, bob, AccountRatingCategory.SUBJECT, -4);
			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData bobSubject = findSnapshot(repository, bob.getAddress(), AccountRatingCategory.SUBJECT);
			assertEquals(0L, bobSubject.getScore());
			assertEquals(0, bobSubject.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, bobSubject.getMappedTrustStatus());
			assertTrue("Unverified target in the minting group should still be able to mint",
					new Account(repository, bob.getAddress()).canMint(false));
		}
	}

	@Test
	public void testSingleTrustedNegativeSubjectRatingDoesNotMakeTargetSuspicious() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);

			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, alice, bob);
			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData bobPlayer = findSnapshot(repository, bob.getAddress(), AccountRatingCategory.PLAYER);
			assertEquals(32_000_000L, bobPlayer.getScore());
			assertEquals(3_000_000L, bobPlayer.getLevelScore());
			assertEquals(1_500_000L, bobPlayer.getLevelScoreCap());
			assertEquals(3, bobPlayer.getLevel());
			assertEquals(AccountTrustStatus.GOLD, bobPlayer.getMappedTrustStatus());

			AccountTrustSnapshotData aliceSubjectBefore = findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.UNVERIFIED, aliceSubjectBefore.getMappedTrustStatus());
			assertTrue("Alice should be able to mint before the trusted negative rating",
					new Account(repository, alice.getAddress()).canMint(false));

			TransactionUtils.signAndMint(repository, ratingData(bob, alice, AccountRatingCategory.SUBJECT, -4), bob);

			AccountTrustSnapshotData aliceSubjectAfter = findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(-512_000_000L, aliceSubjectAfter.getScore());
			assertEquals(-5_000_000L, aliceSubjectAfter.getLevelScore());
			assertEquals(5_000_000L, aliceSubjectAfter.getLevelScoreCap());
			assertEquals(0, aliceSubjectAfter.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, aliceSubjectAfter.getMappedTrustStatus());
			assertTrue("One trusted negative rating should not block mint eligibility",
					new Account(repository, alice.getAddress()).canMint(false));
		}
	}

	@Test
	public void testTwoTrustedNegativeSubjectRatingsMakeTargetSuspiciousAndOrphanRestoresMinting() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, dilbert);

			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, alice, bob);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, alice, dilbert);
			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData bobPlayer = findSnapshot(repository, bob.getAddress(), AccountRatingCategory.PLAYER);
			assertEquals(3, bobPlayer.getLevel());
			assertEquals(AccountTrustStatus.GOLD, bobPlayer.getMappedTrustStatus());

			AccountTrustSnapshotData dilbertPlayer = findSnapshot(repository, dilbert.getAddress(),
					AccountRatingCategory.PLAYER);
			assertEquals(3, dilbertPlayer.getLevel());
			assertEquals(AccountTrustStatus.GOLD, dilbertPlayer.getMappedTrustStatus());

			AccountTrustSnapshotData aliceSubjectBefore = findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.UNVERIFIED, aliceSubjectBefore.getMappedTrustStatus());
			assertTrue("Alice should be able to mint before the trusted negative ratings",
					new Account(repository, alice.getAddress()).canMint(false));

			TransactionUtils.signAndMint(repository, ratingData(bob, alice, AccountRatingCategory.SUBJECT, -2), bob);

			AccountTrustSnapshotData aliceSubjectAfterFirstRating = findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(-128_000_000L, aliceSubjectAfterFirstRating.getScore());
			assertEquals(-5_000_000L, aliceSubjectAfterFirstRating.getLevelScore());
			assertEquals(5_000_000L, aliceSubjectAfterFirstRating.getLevelScoreCap());
			assertEquals(0, aliceSubjectAfterFirstRating.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, aliceSubjectAfterFirstRating.getMappedTrustStatus());
			assertTrue("One trusted negative rating should not block mint eligibility",
					new Account(repository, alice.getAddress()).canMint(false));

			TransactionUtils.signAndMint(repository, ratingData(dilbert, alice, AccountRatingCategory.SUBJECT, -2),
					dilbert);

			AccountTrustSnapshotData aliceSubjectAfterSecondRating = findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(-256_000_000L, aliceSubjectAfterSecondRating.getScore());
			assertEquals(-10_000_000L, aliceSubjectAfterSecondRating.getLevelScore());
			assertEquals(5_000_000L, aliceSubjectAfterSecondRating.getLevelScoreCap());
			assertEquals(-1, aliceSubjectAfterSecondRating.getLevel());
			assertEquals(AccountTrustStatus.SUSPICIOUS, aliceSubjectAfterSecondRating.getMappedTrustStatus());
			assertFalse("Two trusted negative ratings should block mint eligibility",
					new Account(repository, alice.getAddress()).canMint(false));

			BlockUtils.orphanLastBlock(repository);

			AccountTrustSnapshotData aliceSubjectRestored = findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(-128_000_000L, aliceSubjectRestored.getScore());
			assertEquals(-5_000_000L, aliceSubjectRestored.getLevelScore());
			assertEquals(5_000_000L, aliceSubjectRestored.getLevelScoreCap());
			assertEquals(0, aliceSubjectRestored.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, aliceSubjectRestored.getMappedTrustStatus());
			assertTrue("Orphaning the second trusted negative rating should restore mint eligibility",
					new Account(repository, alice.getAddress()).canMint(false));
		}
	}

	@Test
	public void testSinglePositiveManagerImpactDoesNotQualifyThroughCap() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			saveManagerTrust(repository, alice, bob, 4);
			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData bobManager = findSnapshot(repository, bob.getAddress(), AccountRatingCategory.MANAGER);

			assertEquals(4_000_000L, bobManager.getScore());
			assertEquals(500L, bobManager.getLevelScore());
			assertEquals(500L, bobManager.getLevelScoreCap());
			assertEquals(0, bobManager.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, bobManager.getMappedTrustStatus());
		}
	}

	@Test
	public void testTwoPositiveManagerImpactsQualifyThroughCap() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			List<PrivateKeyAccount> evaluators;

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			evaluators = AccountTrustTestUtils.saveManagerEnergyPaths(repository, alice, 2);
			for (PrivateKeyAccount evaluator : evaluators)
				saveAccountRating(repository, evaluator, bob, AccountRatingCategory.MANAGER, 1);
			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData bobManager = findSnapshot(repository, bob.getAddress(), AccountRatingCategory.MANAGER);

			assertEquals(1_000_000L, bobManager.getScore());
			assertEquals(200_000L, bobManager.getLevelScore());
			assertEquals(100_000L, bobManager.getLevelScoreCap());
			assertEquals(2, bobManager.getLevel());
			assertEquals(AccountTrustStatus.SILVER, bobManager.getMappedTrustStatus());
		}
	}

	@Test
	public void testDirectSeedManagerRatingDoesNotCreateManagerScore() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount managerTarget = Common.generateRandomSeedAccount(repository);

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, managerTarget);
			saveAccountRating(repository, alice, managerTarget, AccountRatingCategory.MANAGER, 4);

			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData managerSnapshot = findSnapshot(repository, managerTarget.getAddress(),
					AccountRatingCategory.MANAGER);
			assertEquals("Aura manager scoring should use final energy after four manager hops",
					0L, managerSnapshot.getScore());
			assertEquals(0, managerSnapshot.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, managerSnapshot.getMappedTrustStatus());
		}
	}

	@Test
	public void testManagerEnergySplitsDuringFourHopFlow() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount branchA1 = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount branchA2 = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount branchA3 = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount evaluatorA = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount branchB1 = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount branchB2 = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount branchB3 = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount evaluatorB = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount targetA = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount targetB = Common.generateRandomSeedAccount(repository);
			List<PrivateKeyAccount> generatedAccounts = Arrays.asList(branchA1, branchA2, branchA3, evaluatorA,
					branchB1, branchB2, branchB3, evaluatorB, targetA, targetB);

			ensureKnownAccount(repository, alice);
			for (PrivateKeyAccount account : generatedAccounts)
				ensureKnownAccount(repository, account);

			saveAccountRating(repository, alice, branchA1, AccountRatingCategory.MANAGER, 1);
			saveAccountRating(repository, alice, branchB1, AccountRatingCategory.MANAGER, 3);
			saveAccountRating(repository, branchA1, branchA2, AccountRatingCategory.MANAGER, 4);
			saveAccountRating(repository, branchA2, branchA3, AccountRatingCategory.MANAGER, 4);
			saveAccountRating(repository, branchA3, evaluatorA, AccountRatingCategory.MANAGER, 4);
			saveAccountRating(repository, branchB1, branchB2, AccountRatingCategory.MANAGER, 4);
			saveAccountRating(repository, branchB2, branchB3, AccountRatingCategory.MANAGER, 4);
			saveAccountRating(repository, branchB3, evaluatorB, AccountRatingCategory.MANAGER, 4);
			saveAccountRating(repository, evaluatorA, targetA, AccountRatingCategory.MANAGER, 1);
			saveAccountRating(repository, evaluatorB, targetB, AccountRatingCategory.MANAGER, 1);

			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData targetASnapshot = findSnapshot(repository, targetA.getAddress(),
					AccountRatingCategory.MANAGER);
			AccountTrustSnapshotData targetBSnapshot = findSnapshot(repository, targetB.getAddress(),
					AccountRatingCategory.MANAGER);

			assertEquals(250_000L, targetASnapshot.getScore());
			assertEquals(750_000L, targetBSnapshot.getScore());
			assertEquals("Alice's one seed budget should be split by outgoing confidence during energy flow",
					1_000_000L, targetASnapshot.getScore() + targetBSnapshot.getScore());
		}
	}

	@Test
	public void testSingleNegativeManagerRatingUsesFinalEnergyAndFlaggingMultiplierWithoutSuspicious() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount evaluator = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount managerTarget = Common.generateRandomSeedAccount(repository);

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, evaluator);
			ensureKnownAccount(repository, managerTarget);
			saveManagerEnergyPath(repository, alice, evaluator);
			saveAccountRating(repository, evaluator, managerTarget, AccountRatingCategory.MANAGER, -1);

			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData managerSnapshot = findSnapshot(repository, managerTarget.getAddress(),
					AccountRatingCategory.MANAGER);

			assertEquals(-4_000_000L, managerSnapshot.getScore());
			assertEquals(-500L, managerSnapshot.getLevelScore());
			assertEquals(500L, managerSnapshot.getLevelScoreCap());
			assertEquals(0, managerSnapshot.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, managerSnapshot.getMappedTrustStatus());
		}
	}

	@Test
	public void testTwoNegativeManagerRatingsMakeTargetSuspiciousThroughCap() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount managerTarget = Common.generateRandomSeedAccount(repository);
			List<PrivateKeyAccount> evaluators;

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, managerTarget);
			evaluators = AccountTrustTestUtils.saveManagerEnergyPaths(repository, alice, 2);
			for (PrivateKeyAccount evaluator : evaluators)
				saveAccountRating(repository, evaluator, managerTarget, AccountRatingCategory.MANAGER, -2);

			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData managerSnapshot = findSnapshot(repository, managerTarget.getAddress(),
					AccountRatingCategory.MANAGER);

			assertEquals(-8_000_000L, managerSnapshot.getScore());
			assertEquals(-1_000L, managerSnapshot.getLevelScore());
			assertEquals(500L, managerSnapshot.getLevelScoreCap());
			assertEquals(-1, managerSnapshot.getLevel());
			assertEquals(AccountTrustStatus.SUSPICIOUS, managerSnapshot.getMappedTrustStatus());
		}
	}

	private void saveManagerTrust(Repository repository, PrivateKeyAccount seedAccount, PrivateKeyAccount managerTarget,
			int rating) throws DataException {
		PrivateKeyAccount evaluator = Common.generateRandomSeedAccount(repository);

		ensureKnownAccount(repository, evaluator);
		saveManagerEnergyPath(repository, seedAccount, evaluator);
		saveAccountRating(repository, evaluator, managerTarget, AccountRatingCategory.MANAGER, rating);
	}

	private void saveManagerEnergyPath(Repository repository, PrivateKeyAccount seedAccount, PrivateKeyAccount evaluator)
			throws DataException {
		List<PrivateKeyAccount> pathAccounts = Arrays.asList(
				Common.generateRandomSeedAccount(repository),
				Common.generateRandomSeedAccount(repository),
				Common.generateRandomSeedAccount(repository));

		ensureKnownAccount(repository, seedAccount);
		ensureKnownAccount(repository, evaluator);
		for (PrivateKeyAccount account : pathAccounts)
			ensureKnownAccount(repository, account);

		saveAccountRating(repository, seedAccount, pathAccounts.get(0), AccountRatingCategory.MANAGER, 4);
		saveAccountRating(repository, pathAccounts.get(0), pathAccounts.get(1), AccountRatingCategory.MANAGER, 4);
		saveAccountRating(repository, pathAccounts.get(1), pathAccounts.get(2), AccountRatingCategory.MANAGER, 4);
		saveAccountRating(repository, pathAccounts.get(2), evaluator, AccountRatingCategory.MANAGER, 4);
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), target.getPublicKey(), category, rating);
	}

	private void saveAccountRating(Repository repository, PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		repository.getAccountRatingRepository().save(new AccountRatingData(target.getPublicKey(), rater.getPublicKey(),
				category, rating));
	}

	private void refreshTrustSnapshots(Repository repository) throws DataException {
		AccountTrustDerivation.refreshSnapshots(repository, repository.getBlockRepository().getBlockchainHeight() + 1,
				repository.getBlockRepository().getLastBlock().getTimestamp());
		repository.saveChanges();
	}

	private void ensureKnownAccount(Repository repository, PrivateKeyAccount account) throws DataException {
		repository.getAccountRepository()
				.ensureAccount(new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, 0));
	}

	private AccountTrustSnapshotData findSnapshot(Repository repository, String accountAddress, AccountRatingCategory category)
			throws DataException {
		return repository.getAccountRatingRepository().getTrustDerivationSnapshots(accountAddress).stream()
				.filter(snapshot -> snapshot.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing snapshot for " + accountAddress + " in category " + category));
	}
}
