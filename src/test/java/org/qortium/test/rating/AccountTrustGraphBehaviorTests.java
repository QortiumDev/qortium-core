package org.qortium.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.AccountTrustDerivation;
import org.qortium.account.AccountTrustWeight;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.account.AccountData;
import org.qortium.data.account.AccountRating;
import org.qortium.data.account.AccountRatingData;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.account.AccountTrustStatusChangeData;
import org.qortium.data.transaction.RateAccountTransactionData;
import org.qortium.group.Group;
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

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AccountTrustGraphBehaviorTests extends Common {

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		AccountTrustTestUtils.useAccountRatingCooldown(0);
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
	public void testUntrustedPositiveSubjectRatingDoesNotAddScore() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount untrustedRater = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount subject = Common.generateRandomSeedAccount(repository);

			ensureKnownAccount(repository, untrustedRater);
			ensureKnownAccount(repository, subject);

			saveAccountRating(repository, untrustedRater, subject, AccountRatingCategory.SUBJECT, 4);
			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData subjectSnapshot = findSnapshot(repository, subject.getAddress(),
					AccountRatingCategory.SUBJECT);

			assertEquals("Untrusted positive Subject ratings should remain visible as inbound evidence",
					1, subjectSnapshot.getInboundRatings().getPositiveVeryHighCount());
			assertEquals("Untrusted positive Subject ratings should not add active score",
					0L, subjectSnapshot.getScore());
			assertEquals(0, subjectSnapshot.getLevelScore());
			assertEquals(0, subjectSnapshot.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, subjectSnapshot.getMappedTrustStatus());
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
	public void testLowConfidenceTrustedNegativeSubjectRatingsDoNotMakeTargetSuspicious() throws DataException {
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
			AccountTrustSnapshotData dilbertPlayer = findSnapshot(repository, dilbert.getAddress(),
					AccountRatingCategory.PLAYER);
			assertEquals(AccountTrustStatus.GOLD, bobPlayer.getMappedTrustStatus());
			assertEquals(AccountTrustStatus.GOLD, dilbertPlayer.getMappedTrustStatus());

			TransactionUtils.signAndMint(repository, ratingData(bob, alice, AccountRatingCategory.SUBJECT, -1), bob);
			TransactionUtils.signAndMint(repository, ratingData(dilbert, alice, AccountRatingCategory.SUBJECT, -1),
					dilbert);

			AccountTrustSnapshotData aliceSubject = findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(-128_000_000L, aliceSubject.getScore());
			assertEquals(-10_000_000L, aliceSubject.getLevelScore());
			assertEquals(5_000_000L, aliceSubject.getLevelScoreCap());
			assertEquals(0, aliceSubject.getLevel());
			assertEquals("Low-confidence negative ratings should not meet the Suspicious rater requirement",
					AccountTrustStatus.UNVERIFIED, aliceSubject.getMappedTrustStatus());
			assertTrue("Low-confidence negative ratings should not block mint eligibility",
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

			List<AccountTrustStatusChangeData> suspiciousChanges = repository.getAccountRatingRepository()
					.getTrustStatusChanges(alice.getAddress(), AccountRatingCategory.SUBJECT,
							AccountTrustStatus.UNVERIFIED, AccountTrustStatus.SUSPICIOUS, null, null, null);
			assertEquals(1, suspiciousChanges.size());
			assertEquals(aliceSubjectAfterSecondRating.getSnapshotHeight(), suspiciousChanges.get(0).getSnapshotHeight());

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
			assertTrue(repository.getAccountRatingRepository()
					.getTrustStatusChanges(alice.getAddress(), AccountRatingCategory.SUBJECT,
							AccountTrustStatus.UNVERIFIED, AccountTrustStatus.SUSPICIOUS, null, null, null)
					.isEmpty());
		}
	}

	@Test
	public void testTwoPositiveScoreNegativeSubjectRatingsFromSameBranchDoNotMakeTargetSuspicious() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, dilbert);

			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatingsFromSharedManagerBranch(repository, alice,
					Arrays.asList(bob, dilbert));
			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData bobPlayer = findSnapshot(repository, bob.getAddress(), AccountRatingCategory.PLAYER);
			AccountTrustSnapshotData dilbertPlayer = findSnapshot(repository, dilbert.getAddress(),
					AccountRatingCategory.PLAYER);
			assertEquals("Same-branch positive score should not satisfy Player branch independence",
					AccountTrustStatus.UNVERIFIED, bobPlayer.getMappedTrustStatus());
			assertEquals("Same-branch positive score should not satisfy Player branch independence",
					AccountTrustStatus.UNVERIFIED, dilbertPlayer.getMappedTrustStatus());

			TransactionUtils.signAndMint(repository, ratingData(bob, alice, AccountRatingCategory.SUBJECT, -2), bob);
			TransactionUtils.signAndMint(repository, ratingData(dilbert, alice, AccountRatingCategory.SUBJECT, -2),
					dilbert);

			AccountTrustSnapshotData aliceSubject = findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(-512_000_000L, aliceSubject.getScore());
			assertEquals(-10_000_000L, aliceSubject.getLevelScore());
			assertEquals(5_000_000L, aliceSubject.getLevelScoreCap());
			assertEquals(0, aliceSubject.getLevel());
			assertEquals("Two same-branch negative raters should not satisfy Suspicious branch independence",
					AccountTrustStatus.UNVERIFIED, aliceSubject.getMappedTrustStatus());
			assertTrue("Same-branch negative ratings should not block mint eligibility",
					new Account(repository, alice.getAddress()).canMint(false));
		}
	}

	@Test
	public void testPositiveSubjectRatingRemovalRefreshesAndOrphanRestoresSnapshot() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount subject = Common.getTestAccount(repository, "chloe");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			ensureKnownAccount(repository, seedAccount);
			ensureKnownAccount(repository, subject);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, dilbert);

			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, bob);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, dilbert);
			refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository, ratingData(bob, subject, AccountRatingCategory.SUBJECT, 4), bob);
			TransactionUtils.signAndMint(repository, ratingData(dilbert, subject, AccountRatingCategory.SUBJECT, 4),
					dilbert);

			AccountTrustSnapshotData subjectAfterRatings = findSnapshot(repository, subject.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(128_000_000L, subjectAfterRatings.getScore());
			assertEquals(100_000_000L, subjectAfterRatings.getLevelScore());
			assertEquals(50_000_000L, subjectAfterRatings.getLevelScoreCap());
			assertEquals(3, subjectAfterRatings.getLevel());
			assertEquals(AccountTrustStatus.GOLD, subjectAfterRatings.getMappedTrustStatus());

			TransactionUtils.signAndMint(repository,
					ratingData(bob, subject, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING), bob);

			assertNull(repository.getAccountRatingRepository().getRating(subject.getPublicKey(), bob.getPublicKey(),
					AccountRatingCategory.SUBJECT));
			assertActiveRating(repository, subject, dilbert, AccountRatingCategory.SUBJECT, 4);

			AccountTrustSnapshotData subjectAfterRemoval = findSnapshot(repository, subject.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(64_000_000L, subjectAfterRemoval.getScore());
			assertEquals(5_000_000L, subjectAfterRemoval.getLevelScore());
			assertEquals(5_000_000L, subjectAfterRemoval.getLevelScoreCap());
			assertEquals(0, subjectAfterRemoval.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, subjectAfterRemoval.getMappedTrustStatus());

			BlockUtils.orphanLastBlock(repository);

			assertActiveRating(repository, subject, bob, AccountRatingCategory.SUBJECT, 4);
			assertActiveRating(repository, subject, dilbert, AccountRatingCategory.SUBJECT, 4);

			AccountTrustSnapshotData subjectAfterOrphan = findSnapshot(repository, subject.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(128_000_000L, subjectAfterOrphan.getScore());
			assertEquals(100_000_000L, subjectAfterOrphan.getLevelScore());
			assertEquals(50_000_000L, subjectAfterOrphan.getLevelScoreCap());
			assertEquals(3, subjectAfterOrphan.getLevel());
			assertEquals(AccountTrustStatus.GOLD, subjectAfterOrphan.getMappedTrustStatus());
		}
	}

	@Test
	public void testNegativeSubjectRatingRemovalClearsSuspiciousAndOrphanRestoresMintBlock() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount subject = Common.getTestAccount(repository, "chloe");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			ensureKnownAccount(repository, seedAccount);
			ensureKnownAccount(repository, subject);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, dilbert);

			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, bob);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, dilbert);
			refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository, ratingData(bob, subject, AccountRatingCategory.SUBJECT, -2), bob);
			TransactionUtils.signAndMint(repository, ratingData(dilbert, subject, AccountRatingCategory.SUBJECT, -2),
					dilbert);

			AccountTrustSnapshotData subjectAfterRatings = findSnapshot(repository, subject.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(-256_000_000L, subjectAfterRatings.getScore());
			assertEquals(-10_000_000L, subjectAfterRatings.getLevelScore());
			assertEquals(5_000_000L, subjectAfterRatings.getLevelScoreCap());
			assertEquals(-1, subjectAfterRatings.getLevel());
			assertEquals(AccountTrustStatus.SUSPICIOUS, subjectAfterRatings.getMappedTrustStatus());
			assertFalse("Two trusted negative ratings should block mint eligibility",
					AccountTrustWeight.canMint(subjectAfterRatings));

			TransactionUtils.signAndMint(repository,
					ratingData(bob, subject, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING), bob);

			assertNull(repository.getAccountRatingRepository().getRating(subject.getPublicKey(), bob.getPublicKey(),
					AccountRatingCategory.SUBJECT));
			assertActiveRating(repository, subject, dilbert, AccountRatingCategory.SUBJECT, -2);

			AccountTrustSnapshotData subjectAfterRemoval = findSnapshot(repository, subject.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(-128_000_000L, subjectAfterRemoval.getScore());
			assertEquals(-5_000_000L, subjectAfterRemoval.getLevelScore());
			assertEquals(5_000_000L, subjectAfterRemoval.getLevelScoreCap());
			assertEquals(0, subjectAfterRemoval.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, subjectAfterRemoval.getMappedTrustStatus());
			assertTrue("Removing one trusted negative rating should restore mint eligibility",
					AccountTrustWeight.canMint(subjectAfterRemoval));

			BlockUtils.orphanLastBlock(repository);

			assertActiveRating(repository, subject, bob, AccountRatingCategory.SUBJECT, -2);
			assertActiveRating(repository, subject, dilbert, AccountRatingCategory.SUBJECT, -2);

			AccountTrustSnapshotData subjectAfterOrphan = findSnapshot(repository, subject.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(-256_000_000L, subjectAfterOrphan.getScore());
			assertEquals(-10_000_000L, subjectAfterOrphan.getLevelScore());
			assertEquals(5_000_000L, subjectAfterOrphan.getLevelScoreCap());
			assertEquals(-1, subjectAfterOrphan.getLevel());
			assertEquals(AccountTrustStatus.SUSPICIOUS, subjectAfterOrphan.getMappedTrustStatus());
			assertFalse("Orphaning the removal should restore Suspicious mint blocking",
					AccountTrustWeight.canMint(subjectAfterOrphan));
		}
	}

	@Test
	public void testSinglePositiveTrainerImpactDoesNotQualifyThroughCap() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount trainerTarget = Common.generateRandomSeedAccount(repository);

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, trainerTarget);
			AccountTrustTestUtils.saveDerivedManagerLevelTwoRatings(repository, alice, Arrays.asList(bob));
			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData bobManager = findSnapshot(repository, bob.getAddress(), AccountRatingCategory.MANAGER);
			assertEquals(AccountTrustStatus.SILVER, bobManager.getMappedTrustStatus());

			saveAccountRating(repository, bob, trainerTarget, AccountRatingCategory.TRAINER, 4);
			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData trainerSnapshot = findSnapshot(repository, trainerTarget.getAddress(),
					AccountRatingCategory.TRAINER);
			assertEquals(4_000_000L, trainerSnapshot.getScore());
			assertEquals(250_000L, trainerSnapshot.getLevelScore());
			assertEquals(250_000L, trainerSnapshot.getLevelScoreCap());
			assertEquals(0, trainerSnapshot.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, trainerSnapshot.getMappedTrustStatus());
		}
	}

	@Test
	public void testTwoPositiveTrainerImpactsQualifyThroughCap() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount trainerTarget = Common.generateRandomSeedAccount(repository);

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, dilbert);
			ensureKnownAccount(repository, trainerTarget);
			AccountTrustTestUtils.saveDerivedManagerLevelTwoRatings(repository, alice, Arrays.asList(bob, dilbert));

			saveAccountRating(repository, bob, trainerTarget, AccountRatingCategory.TRAINER, 1);
			saveAccountRating(repository, dilbert, trainerTarget, AccountRatingCategory.TRAINER, 1);
			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData bobManager = findSnapshot(repository, bob.getAddress(), AccountRatingCategory.MANAGER);
			AccountTrustSnapshotData dilbertManager = findSnapshot(repository, dilbert.getAddress(),
					AccountRatingCategory.MANAGER);
			assertEquals(AccountTrustStatus.SILVER, bobManager.getMappedTrustStatus());
			assertEquals(AccountTrustStatus.SILVER, dilbertManager.getMappedTrustStatus());

			AccountTrustSnapshotData trainerSnapshot = findSnapshot(repository, trainerTarget.getAddress(),
					AccountRatingCategory.TRAINER);
			assertEquals(2_000_000L, trainerSnapshot.getScore());
			assertEquals(1_000_000L, trainerSnapshot.getLevelScore());
			assertEquals(500_000L, trainerSnapshot.getLevelScoreCap());
			assertEquals(2, trainerSnapshot.getLevel());
			assertEquals(AccountTrustStatus.SILVER, trainerSnapshot.getMappedTrustStatus());
		}
	}

	@Test
	public void testTwoPositiveTrainerImpactsFromSameBranchDoNotQualify() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount trainerTarget = Common.generateRandomSeedAccount(repository);

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, dilbert);
			ensureKnownAccount(repository, trainerTarget);
			AccountTrustTestUtils.saveDerivedManagerLevelTwoRatingsFromSharedManagerBranch(repository, alice,
					Arrays.asList(bob, dilbert));

			saveAccountRating(repository, bob, trainerTarget, AccountRatingCategory.TRAINER, 1);
			saveAccountRating(repository, dilbert, trainerTarget, AccountRatingCategory.TRAINER, 1);
			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData trainerSnapshot = findSnapshot(repository, trainerTarget.getAddress(),
					AccountRatingCategory.TRAINER);
			assertEquals(2_000_000L, trainerSnapshot.getScore());
			assertEquals(500_000L, trainerSnapshot.getLevelScore());
			assertEquals(250_000L, trainerSnapshot.getLevelScoreCap());
			assertEquals(0, trainerSnapshot.getLevel());
			assertEquals("Same-branch positive raters should not satisfy positive branch independence",
					AccountTrustStatus.UNVERIFIED, trainerSnapshot.getMappedTrustStatus());
		}
	}

	@Test
	public void testSinglePositivePlayerImpactDoesNotQualifyThroughCap() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount trainerRater = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount playerTarget = Common.generateRandomSeedAccount(repository);

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, dilbert);
			ensureKnownAccount(repository, trainerRater);
			ensureKnownAccount(repository, playerTarget);
			AccountTrustTestUtils.saveDerivedManagerLevelTwoRatings(repository, alice, Arrays.asList(bob, dilbert));
			saveAccountRating(repository, bob, trainerRater, AccountRatingCategory.TRAINER, 1);
			saveAccountRating(repository, dilbert, trainerRater, AccountRatingCategory.TRAINER, 1);
			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData trainerRaterSnapshot = findSnapshot(repository, trainerRater.getAddress(),
					AccountRatingCategory.TRAINER);
			assertEquals(AccountTrustStatus.SILVER, trainerRaterSnapshot.getMappedTrustStatus());

			saveAccountRating(repository, trainerRater, playerTarget, AccountRatingCategory.PLAYER, 4);
			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData playerSnapshot = findSnapshot(repository, playerTarget.getAddress(),
					AccountRatingCategory.PLAYER);
			assertEquals(8_000_000L, playerSnapshot.getScore());
			assertEquals(500_000L, playerSnapshot.getLevelScore());
			assertEquals(500_000L, playerSnapshot.getLevelScoreCap());
			assertEquals(0, playerSnapshot.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, playerSnapshot.getMappedTrustStatus());
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
	public void testManagerCategoryScoringDoesNotSplitFinalEvaluatorEnergyAcrossTargets() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount evaluator = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount targetA = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount targetB = Common.generateRandomSeedAccount(repository);

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, evaluator);
			ensureKnownAccount(repository, targetA);
			ensureKnownAccount(repository, targetB);
			saveManagerEnergyPath(repository, alice, evaluator);
			saveAccountRating(repository, evaluator, targetA, AccountRatingCategory.MANAGER, 1);
			saveAccountRating(repository, evaluator, targetB, AccountRatingCategory.MANAGER, 1);

			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData targetASnapshot = findSnapshot(repository, targetA.getAddress(),
					AccountRatingCategory.MANAGER);
			AccountTrustSnapshotData targetBSnapshot = findSnapshot(repository, targetB.getAddress(),
					AccountRatingCategory.MANAGER);

			assertEquals("Aura scores each Manager rating from the final evaluator energy without a second outbound split",
					1_000_000L, targetASnapshot.getScore());
			assertEquals("Aura scores each Manager rating from the final evaluator energy without a second outbound split",
					1_000_000L, targetBSnapshot.getScore());
			assertEquals("The raw Manager score should show direct Aura scoring, not a conserved post-flow budget",
					2_000_000L, targetASnapshot.getScore() + targetBSnapshot.getScore());
		}
	}

	@Test
	public void testSubjectCategoryScoringDoesNotSplitPlayerEvaluatorScoreAcrossTargets() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, chloe);
			ensureKnownAccount(repository, dilbert);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, alice, bob);
			saveAccountRating(repository, bob, chloe, AccountRatingCategory.SUBJECT, 2);
			saveAccountRating(repository, bob, dilbert, AccountRatingCategory.SUBJECT, 2);

			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData bobPlayer = findSnapshot(repository, bob.getAddress(), AccountRatingCategory.PLAYER);
			AccountTrustSnapshotData chloeSubject = findSnapshot(repository, chloe.getAddress(), AccountRatingCategory.SUBJECT);
			AccountTrustSnapshotData dilbertSubject = findSnapshot(repository, dilbert.getAddress(),
					AccountRatingCategory.SUBJECT);

			assertEquals(32_000_000L, bobPlayer.getScore());
			assertEquals("Aura scores each Subject rating as confidence times evaluator score without an outbound split",
					64_000_000L, chloeSubject.getScore());
			assertEquals("Aura scores each Subject rating as confidence times evaluator score without an outbound split",
					64_000_000L, dilbertSubject.getScore());
			assertEquals("The raw Subject score should show direct Aura scoring, not a conserved evaluator budget",
					128_000_000L, chloeSubject.getScore() + dilbertSubject.getScore());
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

	private void assertActiveRating(Repository repository, PrivateKeyAccount target, PrivateKeyAccount rater,
			AccountRatingCategory category, int expectedRating) throws DataException {
		AccountRatingData activeRating = repository.getAccountRatingRepository().getRating(target.getPublicKey(),
				rater.getPublicKey(), category);

		assertNotNull(activeRating);
		assertEquals(expectedRating, activeRating.getRating());
		assertEquals(category, activeRating.getCategory());
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
