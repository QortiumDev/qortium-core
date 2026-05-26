package org.qortium.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.AccountTrustPolicy;
import org.qortium.account.AccountTrustWeight;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountTrustTestUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccountTrustScenarioTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testIndependentBranchesPromoteSubjectWhileIsolatedFarmRingStaysUnverified()
			throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount playerA = Common.getTestAccount(repository, "bob");
			TestAccount playerB = Common.getTestAccount(repository, "dilbert");
			TestAccount subject = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount farmA = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount farmB = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount farmC = Common.generateRandomSeedAccount(repository);
			List<PrivateKeyAccount> farmAccounts = Arrays.asList(farmA, farmB, farmC);

			AccountTrustTestUtils.ensureKnownAccount(repository, seedAccount);
			AccountTrustTestUtils.ensureKnownAccount(repository, playerA);
			AccountTrustTestUtils.ensureKnownAccount(repository, playerB);
			AccountTrustTestUtils.ensureKnownAccount(repository, subject);
			for (PrivateKeyAccount farmAccount : farmAccounts)
				AccountTrustTestUtils.ensureKnownAccount(repository, farmAccount);

			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, playerA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, playerB);
			AccountTrustTestUtils.saveAccountRating(repository, playerA, subject, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.saveAccountRating(repository, playerB, subject, AccountRatingCategory.SUBJECT, 4);

			AccountTrustTestUtils.saveAccountRating(repository, farmA, farmB, AccountRatingCategory.MANAGER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, farmB, farmC, AccountRatingCategory.TRAINER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, farmC, farmA, AccountRatingCategory.PLAYER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, farmA, farmC, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertStatus(repository, playerA.getAddress(), AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);
			assertStatus(repository, playerB.getAddress(), AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);

			AccountTrustSnapshotData subjectSnapshot = findSnapshot(repository, subject.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(128_000_000L, subjectSnapshot.getScore());
			assertEquals(100_000_000L, subjectSnapshot.getLevelScore());
			assertEquals(50_000_000L, subjectSnapshot.getLevelScoreCap());
			assertEquals(3, subjectSnapshot.getLevel());
			assertEquals(AccountTrustStatus.GOLD, subjectSnapshot.getMappedTrustStatus());

			for (PrivateKeyAccount farmAccount : farmAccounts) {
				for (AccountRatingCategory category : AccountRatingCategory.values()) {
					AccountTrustSnapshotData farmSnapshot = findSnapshot(repository, farmAccount.getAddress(), category);

					assertEquals("Isolated farm ratings should not create " + category + " score",
							0L, farmSnapshot.getScore());
					assertEquals("Isolated farm ratings should not create " + category + " level",
							0, farmSnapshot.getLevel());
					assertEquals("Isolated farm ratings should remain Unverified",
							AccountTrustStatus.UNVERIFIED, farmSnapshot.getMappedTrustStatus());
				}
			}
		}
	}

	@Test
	public void testSameBranchPositiveLiftAttemptLeavesSubjectUnverifiedWithAuditableScore()
			throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount playerA = Common.getTestAccount(repository, "bob");
			TestAccount playerB = Common.getTestAccount(repository, "dilbert");
			TestAccount subject = Common.getTestAccount(repository, "chloe");

			AccountTrustTestUtils.ensureKnownAccount(repository, seedAccount);
			AccountTrustTestUtils.ensureKnownAccount(repository, playerA);
			AccountTrustTestUtils.ensureKnownAccount(repository, playerB);
			AccountTrustTestUtils.ensureKnownAccount(repository, subject);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatingsFromSharedManagerBranch(repository, seedAccount,
					Arrays.asList(playerA, playerB));
			AccountTrustTestUtils.saveAccountRating(repository, playerA, subject, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.saveAccountRating(repository, playerB, subject, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertStatus(repository, playerA.getAddress(), AccountRatingCategory.PLAYER, AccountTrustStatus.UNVERIFIED);
			assertStatus(repository, playerB.getAddress(), AccountRatingCategory.PLAYER, AccountTrustStatus.UNVERIFIED);

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
	public void testNegativeScenarioRequiresIndependentBranchesBeforeSuspicious() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount sameBranchRaterA = Common.getTestAccount(repository, "bob");
			TestAccount sameBranchRaterB = Common.getTestAccount(repository, "dilbert");
			TestAccount independentTarget = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount independentRaterA = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount independentRaterB = Common.generateRandomSeedAccount(repository);

			AccountTrustTestUtils.ensureKnownAccount(repository, seedAccount);
			AccountTrustTestUtils.ensureKnownAccount(repository, sameBranchRaterA);
			AccountTrustTestUtils.ensureKnownAccount(repository, sameBranchRaterB);
			AccountTrustTestUtils.ensureKnownAccount(repository, independentTarget);
			AccountTrustTestUtils.ensureKnownAccount(repository, independentRaterA);
			AccountTrustTestUtils.ensureKnownAccount(repository, independentRaterB);

			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatingsFromSharedManagerBranch(repository, seedAccount,
					Arrays.asList(sameBranchRaterA, sameBranchRaterB));
			AccountTrustTestUtils.saveAccountRating(repository, sameBranchRaterA, seedAccount,
					AccountRatingCategory.SUBJECT, -2);
			AccountTrustTestUtils.saveAccountRating(repository, sameBranchRaterB, seedAccount,
					AccountRatingCategory.SUBJECT, -2);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			AccountTrustSnapshotData sameBranchTarget = findSnapshot(repository, seedAccount.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals("Same-branch negative evidence should remain visible for audit",
					-512_000_000L, sameBranchTarget.getScore());
			assertEquals(0, sameBranchTarget.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, sameBranchTarget.getMappedTrustStatus());
			assertTrue("Same-branch negative evidence should not block minting by itself",
					AccountTrustWeight.canMint(sameBranchTarget));

			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, independentRaterA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, independentRaterB);
			AccountTrustTestUtils.saveAccountRating(repository, independentRaterA, independentTarget,
					AccountRatingCategory.SUBJECT, -2);
			AccountTrustTestUtils.saveAccountRating(repository, independentRaterB, independentTarget,
					AccountRatingCategory.SUBJECT, -2);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			AccountTrustSnapshotData independentBranchTarget = findSnapshot(repository, independentTarget.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertTrue("Independent negative evidence should produce a negative raw score",
					independentBranchTarget.getScore() < 0);
			assertEquals(-1, independentBranchTarget.getLevel());
			assertEquals(AccountTrustStatus.SUSPICIOUS, independentBranchTarget.getMappedTrustStatus());
			assertFalse("Independent negative branches should be able to derive Suspicious status",
					AccountTrustWeight.canMint(independentBranchTarget));
		}
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
