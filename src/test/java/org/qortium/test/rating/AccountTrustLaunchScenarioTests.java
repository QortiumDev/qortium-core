package org.qortium.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.AccountTrustPolicy;
import org.qortium.account.AccountTrustWeight;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.account.AccountData;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.account.AccountTrustStatusChangeData;
import org.qortium.data.account.AccountTrustSummaryData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountTrustTestUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TestChainBootstrapUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AccountTrustLaunchScenarioTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testNoEvidenceMintingMemberStaysUnverifiedWithZeroVoteWeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount newMinter = Common.getTestAccount(repository, "bob");

			AccountTrustTestUtils.ensureKnownAccount(repository, newMinter);
			TestChainBootstrapUtils.ensureMintingGroupMember(repository, "bob");
			setBlocksMinted(repository, newMinter, 1_000);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			AccountTrustSnapshotData subjectSnapshot = findSnapshot(repository, newMinter.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.UNVERIFIED, subjectSnapshot.getMappedTrustStatus());
			assertTrue("Unverified minting-group members should still be allowed to mint",
					new Account(repository, newMinter.getAddress()).canMint(false));
			assertEquals("Unverified accounts should have zero effective governance weight",
					0, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, subjectSnapshot));

			AccountTrustSummaryData summary = repository.getAccountRatingRepository()
					.getTrustSummary(AccountTrustPolicy.getActiveWeightCategory());
			assertTrue(summary.isSnapshotsComplete());
			assertEquals(2L, summary.getActiveSeedMemberCount());
			assertEquals(2L, summary.getActiveMintingAllowedCount());
			assertEquals(0L, summary.getEffectiveVoteWeight());
			assertEquals(0L, summary.getActiveRatingCount());
		}
	}

	@Test
	public void testOneTrustedSubjectSupporterCreatesAuditScoreWithoutPositiveTier() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount trustedPlayer = Common.getTestAccount(repository, "bob");
			TestAccount target = Common.getTestAccount(repository, "chloe");

			AccountTrustTestUtils.ensureKnownAccount(repository, seedAccount);
			AccountTrustTestUtils.ensureKnownAccount(repository, trustedPlayer);
			AccountTrustTestUtils.ensureKnownAccount(repository, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, trustedPlayer);
			AccountTrustTestUtils.saveAccountRating(repository, trustedPlayer, target, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertStatus(repository, trustedPlayer.getAddress(), AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);

			AccountTrustSnapshotData targetSubject = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertTrue("One trusted supporter should leave positive audit score", targetSubject.getScore() > 0);
			assertEquals("One trusted supporter should not satisfy launch branch requirements",
					0, targetSubject.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, targetSubject.getMappedTrustStatus());
			assertEquals(0, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, targetSubject));
		}
	}

	@Test
	public void testTwoIndependentTrustedSubjectSupportersCanLiftTargetToGold() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "dilbert");
			TestAccount target = Common.getTestAccount(repository, "chloe");

			AccountTrustTestUtils.ensureKnownAccount(repository, seedAccount);
			AccountTrustTestUtils.ensureKnownAccount(repository, supporterA);
			AccountTrustTestUtils.ensureKnownAccount(repository, supporterB);
			AccountTrustTestUtils.ensureKnownAccount(repository, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporterA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporterB);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			AccountTrustTestUtils.saveAccountRating(repository, supporterA, target, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.saveAccountRating(repository, supporterB, target, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			AccountTrustSnapshotData targetSubject = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(128_000_000L, targetSubject.getScore());
			assertEquals(100_000_000L, targetSubject.getLevelScore());
			assertEquals(AccountTrustStatus.GOLD, targetSubject.getMappedTrustStatus());
			assertEquals(1_000, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, targetSubject));

			AccountTrustSummaryData summary = repository.getAccountRatingRepository()
					.getTrustSummary(AccountTrustPolicy.getActiveWeightCategory());
			assertTrue(summary.isSnapshotsComplete());
			assertTrue("Launch scenario should include active account ratings", summary.getActiveRatingCount() > 0);
			assertTrue("Launch scenario should include a Gold Subject status summary",
					findStatusSummary(summary, AccountTrustStatus.GOLD).getAccountCount() > 0);
		}
	}

	@Test
	public void testSameBranchEvidenceCannotLiftOrBlockTargetByItself() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount sameBranchPlayerA = Common.getTestAccount(repository, "bob");
			TestAccount sameBranchPlayerB = Common.getTestAccount(repository, "dilbert");
			TestAccount positiveTarget = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount negativeTarget = Common.generateRandomSeedAccount(repository);
			List<TestAccount> sameBranchPlayers = Arrays.asList(sameBranchPlayerA, sameBranchPlayerB);

			AccountTrustTestUtils.ensureKnownAccount(repository, seedAccount);
			for (TestAccount sameBranchPlayer : sameBranchPlayers)
				AccountTrustTestUtils.ensureKnownAccount(repository, sameBranchPlayer);
			AccountTrustTestUtils.ensureKnownAccount(repository, positiveTarget);
			AccountTrustTestUtils.ensureKnownAccount(repository, negativeTarget);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatingsFromSharedManagerBranch(repository, seedAccount,
					sameBranchPlayers);
			AccountTrustTestUtils.saveAccountRating(repository, sameBranchPlayerA, positiveTarget,
					AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.saveAccountRating(repository, sameBranchPlayerB, positiveTarget,
					AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.saveAccountRating(repository, sameBranchPlayerA, negativeTarget,
					AccountRatingCategory.SUBJECT, -2);
			AccountTrustTestUtils.saveAccountRating(repository, sameBranchPlayerB, negativeTarget,
					AccountRatingCategory.SUBJECT, -2);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			AccountTrustSnapshotData positiveSubject = findSnapshot(repository, positiveTarget.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertTrue("Same-branch positive support should remain auditable",
					positiveSubject.getScore() >= AccountTrustPolicy.getLevelThreshold(AccountRatingCategory.SUBJECT, 4));
			assertEquals(AccountTrustStatus.UNVERIFIED, positiveSubject.getMappedTrustStatus());

			AccountTrustSnapshotData negativeSubject = findSnapshot(repository, negativeTarget.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertTrue("Same-branch negative evidence should remain auditable", negativeSubject.getScore() < 0);
			assertEquals(AccountTrustStatus.UNVERIFIED, negativeSubject.getMappedTrustStatus());
			assertTrue("Same-branch negative evidence should not block minting by itself",
					AccountTrustWeight.canMint(negativeSubject));
		}
	}

	@Test
	public void testIndependentNegativeRatingsCanBlockAndRemovalRestoresLaunchMinting() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount raterA = Common.getTestAccount(repository, "bob");
			TestAccount raterB = Common.getTestAccount(repository, "dilbert");
			TestAccount target = Common.getTestAccount(repository, "chloe");

			TestChainBootstrapUtils.ensureMintingGroupMember(repository, "chloe");
			AccountTrustTestUtils.ensureKnownAccount(repository, seedAccount);
			AccountTrustTestUtils.ensureKnownAccount(repository, raterA);
			AccountTrustTestUtils.ensureKnownAccount(repository, raterB);
			AccountTrustTestUtils.ensureKnownAccount(repository, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, raterA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, raterB);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			AccountTrustTestUtils.saveAccountRating(repository, raterA, target, AccountRatingCategory.SUBJECT, -2);
			AccountTrustTestUtils.saveAccountRating(repository, raterB, target, AccountRatingCategory.SUBJECT, -2);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			AccountTrustSnapshotData targetSuspicious = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.SUSPICIOUS, targetSuspicious.getMappedTrustStatus());
			assertFalse("Independent negative launch evidence should block minting",
					new Account(repository, target.getAddress()).canMint(false));
			assertEquals(1, findStatusChanges(repository, target, AccountTrustStatus.UNVERIFIED,
					AccountTrustStatus.SUSPICIOUS).size());

			repository.getAccountRatingRepository().delete(target.getPublicKey(), raterA.getPublicKey(),
					AccountRatingCategory.SUBJECT);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertNull(repository.getAccountRatingRepository().getRating(target.getPublicKey(), raterA.getPublicKey(),
					AccountRatingCategory.SUBJECT));

			AccountTrustSnapshotData targetRestored = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.UNVERIFIED, targetRestored.getMappedTrustStatus());
			assertTrue("Removing one independent negative rating should restore launch minting",
					new Account(repository, target.getAddress()).canMint(false));

			AccountTrustSummaryData summary = repository.getAccountRatingRepository()
					.getTrustSummary(AccountTrustPolicy.getActiveWeightCategory());
			assertTrue(summary.isSnapshotsComplete());
			assertEquals(1L, summary.getTrustStatusChangeCount());
			assertEquals(targetRestored.getSnapshotHeight(), summary.getLatestTrustChangeHeight().intValue());
			assertEquals(1, findStatusChanges(repository, target, AccountTrustStatus.SUSPICIOUS,
					AccountTrustStatus.UNVERIFIED).size());
		}
	}

	private void assertStatus(Repository repository, String accountAddress, AccountRatingCategory category,
			AccountTrustStatus expectedStatus) throws DataException {
		assertEquals(expectedStatus, findSnapshot(repository, accountAddress, category).getMappedTrustStatus());
	}

	private List<AccountTrustStatusChangeData> findStatusChanges(Repository repository, PrivateKeyAccount account,
			AccountTrustStatus previousStatus, AccountTrustStatus newStatus) throws DataException {
		return repository.getAccountRatingRepository().getTrustStatusChanges(account.getAddress(),
				AccountRatingCategory.SUBJECT, previousStatus, newStatus, null, null, null);
	}

	private AccountTrustSummaryData.StatusSummary findStatusSummary(AccountTrustSummaryData summary,
			AccountTrustStatus status) {
		return summary.getStatusSummaries().stream()
				.filter(statusSummary -> statusSummary.getStatus() == status)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing summary for status " + status));
	}

	private AccountTrustSnapshotData findSnapshot(Repository repository, String accountAddress,
			AccountRatingCategory category) throws DataException {
		return repository.getAccountRatingRepository().getTrustDerivationSnapshots(accountAddress).stream()
				.filter(snapshot -> snapshot.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing snapshot for " + accountAddress + " in category " + category));
	}

	private void setBlocksMinted(Repository repository, PrivateKeyAccount account, int blocksMinted) throws DataException {
		AccountData accountData = repository.getAccountRepository().getAccount(account.getAddress());
		if (accountData == null)
			accountData = new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, blocksMinted);
		else {
			accountData.setPublicKey(account.getPublicKey());
			accountData.setBlocksMinted(blocksMinted);
		}

		repository.getAccountRepository().setMintedBlockCount(accountData);
		repository.saveChanges();
	}
}
