package org.qortium.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.AccountTrustPolicy;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.account.AccountRating;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.account.AccountTrustStatusChangeData;
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

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AccountTrustTransactionCalibrationScenarioTests extends Common {

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		AccountTrustTestUtils.useAccountRatingCooldown(0);
	}

	@Test
	public void testSignedPositiveSubjectRatingsPromoteTargetAndRemovalOrphanRestoresGold()
			throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount playerA = Common.getTestAccount(repository, "bob");
			TestAccount playerB = Common.getTestAccount(repository, "dilbert");
			TestAccount subject = Common.getTestAccount(repository, "chloe");

			ensureKnownAccounts(repository, seedAccount, playerA, playerB, subject);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, playerA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, playerB);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertStatus(repository, playerA.getAddress(), AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);
			assertStatus(repository, playerB.getAddress(), AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);

			TransactionUtils.signAndMint(repository, ratingData(playerA, subject, AccountRatingCategory.SUBJECT, 4),
					playerA);
			TransactionUtils.signAndMint(repository, ratingData(playerB, subject, AccountRatingCategory.SUBJECT, 4),
					playerB);

			AccountTrustSnapshotData subjectAfterRatings = findSnapshot(repository, subject.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(128_000_000L, subjectAfterRatings.getScore());
			assertEquals(100_000_000L, subjectAfterRatings.getLevelScore());
			assertEquals(50_000_000L, subjectAfterRatings.getLevelScoreCap());
			assertEquals(3, subjectAfterRatings.getLevel());
			assertEquals(AccountTrustStatus.GOLD, subjectAfterRatings.getMappedTrustStatus());

			TransactionUtils.signAndMint(repository,
					ratingData(playerA, subject, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING), playerA);

			assertNull(repository.getAccountRatingRepository().getRating(subject.getPublicKey(), playerA.getPublicKey(),
					AccountRatingCategory.SUBJECT));
			AccountTrustSnapshotData subjectAfterRemoval = findSnapshot(repository, subject.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(64_000_000L, subjectAfterRemoval.getScore());
			assertEquals(5_000_000L, subjectAfterRemoval.getLevelScore());
			assertEquals(5_000_000L, subjectAfterRemoval.getLevelScoreCap());
			assertEquals(0, subjectAfterRemoval.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, subjectAfterRemoval.getMappedTrustStatus());

			BlockUtils.orphanLastBlock(repository);

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
	public void testSignedLowConfidenceNegativeSubjectRatingsStayUnverified() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount playerA = Common.getTestAccount(repository, "bob");
			TestAccount playerB = Common.getTestAccount(repository, "dilbert");
			TestAccount target = Common.getTestAccount(repository, "chloe");

			TestChainBootstrapUtils.ensureMintingGroupMember(repository, "chloe");
			ensureKnownAccounts(repository, seedAccount, playerA, playerB, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, playerA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, playerB);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository, ratingData(playerA, target, AccountRatingCategory.SUBJECT, -1),
					playerA);
			TransactionUtils.signAndMint(repository, ratingData(playerB, target, AccountRatingCategory.SUBJECT, -1),
					playerB);

			AccountTrustSnapshotData targetSubject = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(-64_000_000L, targetSubject.getScore());
			assertEquals(-10_000_000L, targetSubject.getLevelScore());
			assertEquals(5_000_000L, targetSubject.getLevelScoreCap());
			assertEquals(0, targetSubject.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, targetSubject.getMappedTrustStatus());
			assertTrue("Low-confidence negative ratings should not block a minting seed",
					new Account(repository, target.getAddress()).canMint(false));
		}
	}

	@Test
	public void testSignedMediumNegativeSubjectRatingsBlockMintingAndRemovalOrphanRestoresBlock()
			throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount playerA = Common.getTestAccount(repository, "bob");
			TestAccount playerB = Common.getTestAccount(repository, "dilbert");
			TestAccount target = Common.getTestAccount(repository, "chloe");

			TestChainBootstrapUtils.ensureMintingGroupMember(repository, "chloe");
			ensureKnownAccounts(repository, seedAccount, playerA, playerB, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, playerA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, playerB);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository, ratingData(playerA, target, AccountRatingCategory.SUBJECT, -2),
					playerA);

			AccountTrustSnapshotData targetAfterFirstRating = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(0, targetAfterFirstRating.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, targetAfterFirstRating.getMappedTrustStatus());
			assertTrue("One trusted negative rating should not block minting",
					new Account(repository, target.getAddress()).canMint(false));

			TransactionUtils.signAndMint(repository, ratingData(playerB, target, AccountRatingCategory.SUBJECT, -2),
					playerB);

			AccountTrustSnapshotData targetAfterSecondRating = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(-128_000_000L, targetAfterSecondRating.getScore());
			assertEquals(-10_000_000L, targetAfterSecondRating.getLevelScore());
			assertEquals(5_000_000L, targetAfterSecondRating.getLevelScoreCap());
			assertEquals(-1, targetAfterSecondRating.getLevel());
			assertEquals(AccountTrustStatus.SUSPICIOUS, targetAfterSecondRating.getMappedTrustStatus());
			assertFalse("Independent medium-confidence negative ratings should block minting",
					new Account(repository, target.getAddress()).canMint(false));

			List<AccountTrustStatusChangeData> suspiciousChanges = repository.getAccountRatingRepository()
					.getTrustStatusChanges(target.getAddress(), AccountRatingCategory.SUBJECT,
							AccountTrustStatus.UNVERIFIED, AccountTrustStatus.SUSPICIOUS, null, null, null);
			assertEquals(1, suspiciousChanges.size());
			assertEquals(targetAfterSecondRating.getSnapshotHeight(), suspiciousChanges.get(0).getSnapshotHeight());

			TransactionUtils.signAndMint(repository,
					ratingData(playerA, target, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING), playerA);

			AccountTrustSnapshotData targetAfterRemoval = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(-64_000_000L, targetAfterRemoval.getScore());
			assertEquals(-5_000_000L, targetAfterRemoval.getLevelScore());
			assertEquals(5_000_000L, targetAfterRemoval.getLevelScoreCap());
			assertEquals(0, targetAfterRemoval.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, targetAfterRemoval.getMappedTrustStatus());
			assertTrue("Removing one trusted negative rating should restore minting",
					new Account(repository, target.getAddress()).canMint(false));

			BlockUtils.orphanLastBlock(repository);

			AccountTrustSnapshotData targetAfterOrphan = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(-128_000_000L, targetAfterOrphan.getScore());
			assertEquals(-10_000_000L, targetAfterOrphan.getLevelScore());
			assertEquals(5_000_000L, targetAfterOrphan.getLevelScoreCap());
			assertEquals(-1, targetAfterOrphan.getLevel());
			assertEquals(AccountTrustStatus.SUSPICIOUS, targetAfterOrphan.getMappedTrustStatus());
			assertFalse("Orphaning the removal should restore Suspicious mint blocking",
					new Account(repository, target.getAddress()).canMint(false));
		}
	}

	@Test
	public void testSignedSameBranchSubjectRatingsDoNotSatisfyBranchIndependence() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount sameBranchPlayerA = Common.getTestAccount(repository, "bob");
			TestAccount sameBranchPlayerB = Common.getTestAccount(repository, "dilbert");
			TestAccount subject = Common.getTestAccount(repository, "chloe");

			ensureKnownAccounts(repository, seedAccount, sameBranchPlayerA, sameBranchPlayerB, subject);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatingsFromSharedManagerBranch(repository, seedAccount,
					Arrays.asList(sameBranchPlayerA, sameBranchPlayerB));
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertStatus(repository, sameBranchPlayerA.getAddress(), AccountRatingCategory.PLAYER,
					AccountTrustStatus.UNVERIFIED);
			assertStatus(repository, sameBranchPlayerB.getAddress(), AccountRatingCategory.PLAYER,
					AccountTrustStatus.UNVERIFIED);

			TransactionUtils.signAndMint(repository,
					ratingData(sameBranchPlayerA, subject, AccountRatingCategory.SUBJECT, 4), sameBranchPlayerA);
			TransactionUtils.signAndMint(repository,
					ratingData(sameBranchPlayerB, subject, AccountRatingCategory.SUBJECT, 4), sameBranchPlayerB);

			AccountTrustSnapshotData subjectSnapshot = findSnapshot(repository, subject.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertTrue("Same-branch positive evidence should remain visible for audit",
					subjectSnapshot.getScore() >= AccountTrustPolicy.getLevelThreshold(AccountRatingCategory.SUBJECT, 4));
			assertEquals("Same-branch positive evidence should not satisfy branch independence",
					0, subjectSnapshot.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, subjectSnapshot.getMappedTrustStatus());
		}
	}

	@Test
	public void testSignedIsolatedFarmRingStaysUnverified() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount farmA = Common.getTestAccount(repository, "bob");
			TestAccount farmB = Common.getTestAccount(repository, "chloe");
			TestAccount farmC = Common.getTestAccount(repository, "dilbert");
			List<TestAccount> farmAccounts = Arrays.asList(farmA, farmB, farmC);

			ensureKnownAccounts(repository, farmA, farmB, farmC);
			repository.saveChanges();

			TransactionUtils.signAndMint(repository, ratingData(farmA, farmB, AccountRatingCategory.MANAGER, 4), farmA);
			TransactionUtils.signAndMint(repository, ratingData(farmB, farmC, AccountRatingCategory.TRAINER, 4), farmB);
			TransactionUtils.signAndMint(repository, ratingData(farmC, farmA, AccountRatingCategory.PLAYER, 4), farmC);
			TransactionUtils.signAndMint(repository, ratingData(farmA, farmC, AccountRatingCategory.SUBJECT, 4), farmA);

			for (TestAccount farmAccount : farmAccounts) {
				for (AccountRatingCategory category : AccountRatingCategory.values()) {
					AccountTrustSnapshotData snapshot = findSnapshot(repository, farmAccount.getAddress(), category);
					assertEquals("Signed farm-ring ratings should not create " + category + " score without a seed path",
							0L, snapshot.getScore());
					assertEquals("Signed farm-ring ratings should not create " + category + " level without a seed path",
							0, snapshot.getLevel());
					assertEquals("Signed farm-ring ratings should remain Unverified",
							AccountTrustStatus.UNVERIFIED, snapshot.getMappedTrustStatus());
				}
			}
		}
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), target.getPublicKey(), category, rating);
	}

	private void ensureKnownAccounts(Repository repository, PrivateKeyAccount... accounts) throws DataException {
		for (PrivateKeyAccount account : accounts)
			AccountTrustTestUtils.ensureKnownAccount(repository, account);
	}

	private void assertStatus(Repository repository, String accountAddress, AccountRatingCategory category,
			AccountTrustStatus expectedStatus) throws DataException {
		assertEquals(expectedStatus, findSnapshot(repository, accountAddress, category).getMappedTrustStatus());
	}

	private AccountTrustSnapshotData findSnapshot(Repository repository, String accountAddress,
			AccountRatingCategory category) throws DataException {
		return repository.getAccountRatingRepository().getTrustDerivationSnapshots(accountAddress).stream()
				.filter(snapshot -> snapshot.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing snapshot for " + accountAddress + " in category " + category));
	}
}
