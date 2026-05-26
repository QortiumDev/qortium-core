package org.qortium.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.AccountTrustPolicy;
import org.qortium.account.AccountTrustWeight;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.account.AccountRating;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.account.AccountTrustSummaryData;
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

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AccountTrustLaunchCommunityScenarioTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testLaunchAcceptanceScenarioCombinesHonestUsersFarmResistanceAndRecovery() throws Exception {
		AccountTrustTestUtils.useAccountRatingCooldown(3);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "dilbert");
			TestAccount noEvidenceMinter = Common.getTestAccount(repository, "chloe");
			List<PrivateKeyAccount> independentSupporters = Arrays.asList(supporterA, supporterB);
			PrivateKeyAccount bronzeTarget = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount silverTarget = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount goldTarget = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount farmA = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount farmB = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount farmC = Common.generateRandomSeedAccount(repository);
			List<PrivateKeyAccount> farmAccounts = Arrays.asList(farmA, farmB, farmC);
			PrivateKeyAccount sameBranchSeed = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount sameBranchSupporterA = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount sameBranchSupporterB = Common.generateRandomSeedAccount(repository);
			List<PrivateKeyAccount> sameBranchSupporters = Arrays.asList(sameBranchSupporterA, sameBranchSupporterB);
			PrivateKeyAccount sameBranchPositiveTarget = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount sameBranchNegativeTarget = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount mistakenSuspiciousTarget = Common.generateRandomSeedAccount(repository);

			ensureKnownAccounts(repository, seedAccount, supporterA, supporterB, noEvidenceMinter, bronzeTarget,
					silverTarget, goldTarget, farmA, farmB, farmC, mistakenSuspiciousTarget);
			AccountTrustTestUtils.setBlocksMinted(repository, noEvidenceMinter, 1_000);
			AccountTrustTestUtils.setBlocksMinted(repository, bronzeTarget, 1_000);
			AccountTrustTestUtils.setBlocksMinted(repository, silverTarget, 1_000);
			AccountTrustTestUtils.setBlocksMinted(repository, goldTarget, 1_000);
			AccountTrustTestUtils.setBlocksMinted(repository, mistakenSuspiciousTarget, 1_000);
			for (PrivateKeyAccount farmAccount : farmAccounts)
				AccountTrustTestUtils.setBlocksMinted(repository, farmAccount, 1_000);

			prepareTrustedSupporters(repository, seedAccount, independentSupporters);

			rateByAll(repository, independentSupporters, bronzeTarget, 1);
			rateByAll(repository, independentSupporters, silverTarget, 2);
			rateByAll(repository, independentSupporters, goldTarget, 4);

			AccountTrustTestUtils.saveAccountRating(repository, farmA, farmB, AccountRatingCategory.MANAGER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, farmB, farmC, AccountRatingCategory.TRAINER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, farmC, farmA, AccountRatingCategory.PLAYER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, farmA, farmC, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.saveAccountRating(repository, farmB, farmA, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			rateByAllSigned(repository, independentSupporters, mistakenSuspiciousTarget, -2);

			Account noEvidenceAccount = new Account(repository, noEvidenceMinter.getAddress());
			assertEquals(AccountTrustStatus.UNVERIFIED, noEvidenceAccount.getTrustStatus());
			assertEquals(0, noEvidenceAccount.getEffectiveVoteWeight());
			assertSubjectStatus(repository, bronzeTarget, AccountTrustStatus.BRONZE, 400);
			assertSubjectStatus(repository, silverTarget, AccountTrustStatus.SILVER, 700);
			assertSubjectStatus(repository, goldTarget, AccountTrustStatus.GOLD, 1_000);

			for (PrivateKeyAccount farmAccount : farmAccounts)
				assertFarmAccountRemainsUnverified(repository, farmAccount);

			assertSubjectStatus(repository, mistakenSuspiciousTarget, AccountTrustStatus.SUSPICIOUS, 0);
			assertFalse("Independent negative evidence should block trust-level mint eligibility",
					AccountTrustWeight.canMint(findSnapshot(repository, mistakenSuspiciousTarget.getAddress(),
							AccountRatingCategory.SUBJECT)));
			ensureMintingGroupMember(repository, mistakenSuspiciousTarget);
			assertFalse("Independent negative evidence should block Minting group member minting",
					new Account(repository, mistakenSuspiciousTarget.getAddress()).canMint(false));
			repository.getGroupRepository().deleteMember(TestChainBootstrapUtils.MINTING_GROUP_ID,
					mistakenSuspiciousTarget.getAddress());
			repository.saveChanges();

			AccountTrustSummaryData summary = repository.getAccountRatingRepository()
					.getTrustSummary(AccountTrustPolicy.getActiveWeightCategory());
			assertTrue(summary.isSnapshotsComplete());
			assertTrue(findStatusSummary(summary, AccountTrustStatus.UNVERIFIED).getAccountCount() > 0);
			assertTrue(findStatusSummary(summary, AccountTrustStatus.BRONZE).getAccountCount() > 0);
			assertTrue(findStatusSummary(summary, AccountTrustStatus.SILVER).getAccountCount() > 0);
			assertTrue(findStatusSummary(summary, AccountTrustStatus.GOLD).getAccountCount() > 0);
			assertTrue(findStatusSummary(summary, AccountTrustStatus.SUSPICIOUS).getAccountCount() > 0);

			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_CHANGE_TOO_SOON,
					Transaction.fromData(repository, ratingData(supporterA, mistakenSuspiciousTarget,
							AccountRatingCategory.SUBJECT, AccountRating.NO_RATING)).isValid());

			BlockUtils.mintBlock(repository);
			TransactionUtils.signAndMint(repository,
					ratingData(supporterA, mistakenSuspiciousTarget, AccountRatingCategory.SUBJECT,
							AccountRating.NO_RATING),
					supporterA);

			assertNull(repository.getAccountRatingRepository().getRating(mistakenSuspiciousTarget.getPublicKey(),
					supporterA.getPublicKey(), AccountRatingCategory.SUBJECT));
			assertSubjectStatus(repository, mistakenSuspiciousTarget, AccountTrustStatus.UNVERIFIED, 0);
			ensureMintingGroupMember(repository, mistakenSuspiciousTarget);
			assertTrue("Removing one mistaken negative rating should restore minting",
					new Account(repository, mistakenSuspiciousTarget.getAddress()).canMint(false));
			repository.getGroupRepository().deleteMember(TestChainBootstrapUtils.MINTING_GROUP_ID,
					mistakenSuspiciousTarget.getAddress());
			repository.saveChanges();
			assertSubjectStatus(repository, bronzeTarget, AccountTrustStatus.BRONZE, 400);
			assertSubjectStatus(repository, silverTarget, AccountTrustStatus.SILVER, 700);
			assertSubjectStatus(repository, goldTarget, AccountTrustStatus.GOLD, 1_000);

			ensureKnownAccounts(repository, sameBranchSeed, sameBranchSupporterA, sameBranchSupporterB,
					sameBranchPositiveTarget, sameBranchNegativeTarget);
			ensureMintingGroupMember(repository, sameBranchSeed);
			AccountTrustTestUtils.setBlocksMinted(repository, sameBranchPositiveTarget, 1_000);
			AccountTrustTestUtils.setBlocksMinted(repository, sameBranchNegativeTarget, 1_000);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatingsFromSharedManagerBranch(repository, sameBranchSeed,
					sameBranchSupporters);
			rateByAll(repository, sameBranchSupporters, sameBranchPositiveTarget, 4);
			rateByAll(repository, sameBranchSupporters, sameBranchNegativeTarget, -2);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			AccountTrustSnapshotData sameBranchPositiveSubject = findSnapshot(repository,
					sameBranchPositiveTarget.getAddress(), AccountRatingCategory.SUBJECT);
			assertTrue("Same-branch positive support should remain auditable",
					sameBranchPositiveSubject.getScore() > 0);
			assertEquals("Same-branch positive support should not satisfy branch independence",
					0, sameBranchPositiveSubject.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, sameBranchPositiveSubject.getMappedTrustStatus());
			assertEquals(0, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, sameBranchPositiveSubject));

			AccountTrustSnapshotData sameBranchNegativeSubject = findSnapshot(repository,
					sameBranchNegativeTarget.getAddress(), AccountRatingCategory.SUBJECT);
			assertTrue("Same-branch negative support should remain auditable",
					sameBranchNegativeSubject.getScore() < 0);
			assertEquals("Same-branch negative support should not satisfy Suspicious branch independence",
					0, sameBranchNegativeSubject.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, sameBranchNegativeSubject.getMappedTrustStatus());
			ensureMintingGroupMember(repository, sameBranchNegativeTarget);
			assertTrue("Same-branch negative support should not block minting",
					new Account(repository, sameBranchNegativeTarget.getAddress()).canMint(false));

			ensureMintingGroupMember(repository, noEvidenceMinter);
			assertEquals(AccountTrustStatus.UNVERIFIED, noEvidenceAccount.getTrustStatus());
			assertEquals(0, noEvidenceAccount.getEffectiveVoteWeight());
			assertTrue("No-evidence Minting group members should still be able to mint",
					noEvidenceAccount.canMint(false));
		}
	}

	@Test
	public void testMixedLaunchCommunityGraphShowsExpectedTrustSpread() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "dilbert");
			List<PrivateKeyAccount> allSupporters = Arrays.asList(supporterA, supporterB);
			PrivateKeyAccount auditOnlyTarget = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount bronzeTarget = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount silverTarget = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount goldTarget = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount suspiciousTarget = Common.generateRandomSeedAccount(repository);

			ensureKnownAccounts(repository, seedAccount, supporterA, supporterB, auditOnlyTarget, bronzeTarget,
					silverTarget, goldTarget, suspiciousTarget);
			prepareTrustedSupporters(repository, seedAccount, allSupporters);

			AccountTrustTestUtils.saveAccountRating(repository, supporterA, auditOnlyTarget,
					AccountRatingCategory.SUBJECT, 4);
			rateByAll(repository, allSupporters, bronzeTarget, 1);
			rateByAll(repository, allSupporters, silverTarget, 2);
			rateByAll(repository, allSupporters, goldTarget, 4);
			rateByAll(repository, Arrays.asList(supporterA, supporterB), suspiciousTarget, -2);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertSubjectStatus(repository, seedAccount, AccountTrustStatus.UNVERIFIED, 0);
			assertTrue("No-evidence Minting group seeds should still be able to mint",
					new Account(repository, seedAccount.getAddress()).canMint(false));
			assertSubjectStatus(repository, auditOnlyTarget, AccountTrustStatus.UNVERIFIED, 0);
			assertSubjectStatus(repository, bronzeTarget, AccountTrustStatus.BRONZE, 400);
			assertSubjectStatus(repository, silverTarget, AccountTrustStatus.SILVER, 700);
			assertSubjectStatus(repository, goldTarget, AccountTrustStatus.GOLD, 1_000);
			assertSubjectStatus(repository, suspiciousTarget, AccountTrustStatus.SUSPICIOUS, 0);
			assertFalse("Suspicious trust status should not allow minting",
					AccountTrustWeight.canMint(findSnapshot(repository, suspiciousTarget.getAddress(),
							AccountRatingCategory.SUBJECT)));

			AccountTrustSummaryData summary = repository.getAccountRatingRepository()
					.getTrustSummary(AccountTrustPolicy.getActiveWeightCategory());
			assertTrue(summary.isSnapshotsComplete());
			assertTrue(summary.getActiveRatingCount() > 0);
			assertTrue(summary.getActiveSeedMemberCount() >= 1);
			assertTrue(findStatusSummary(summary, AccountTrustStatus.UNVERIFIED).getAccountCount() > 0);
			assertTrue(findStatusSummary(summary, AccountTrustStatus.BRONZE).getAccountCount() > 0);
			assertTrue(findStatusSummary(summary, AccountTrustStatus.SILVER).getAccountCount() > 0);
			assertTrue(findStatusSummary(summary, AccountTrustStatus.GOLD).getAccountCount() > 0);
			assertTrue(findStatusSummary(summary, AccountTrustStatus.SUSPICIOUS).getAccountCount() > 0);
		}
	}

	@Test
	public void testIsolatedFarmClusterDoesNotDistortHonestLaunchGraph() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "dilbert");
			List<PrivateKeyAccount> allSupporters = Arrays.asList(supporterA, supporterB);
			TestAccount honestTarget = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount farmA = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount farmB = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount farmC = Common.generateRandomSeedAccount(repository);
			List<PrivateKeyAccount> farmAccounts = Arrays.asList(farmA, farmB, farmC);

			ensureKnownAccounts(repository, seedAccount, supporterA, supporterB, honestTarget, farmA, farmB, farmC);
			prepareTrustedSupporters(repository, seedAccount, allSupporters);
			rateByAll(repository, Arrays.asList(supporterA, supporterB), honestTarget, 4);

			AccountTrustTestUtils.saveAccountRating(repository, farmA, farmB, AccountRatingCategory.MANAGER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, farmB, farmC, AccountRatingCategory.TRAINER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, farmC, farmA, AccountRatingCategory.PLAYER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, farmA, farmC, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.saveAccountRating(repository, farmB, farmA, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertSubjectStatus(repository, honestTarget, AccountTrustStatus.GOLD, 1_000);
			for (PrivateKeyAccount farmAccount : farmAccounts) {
				for (AccountRatingCategory category : AccountRatingCategory.values()) {
					AccountTrustSnapshotData snapshot = findSnapshot(repository, farmAccount.getAddress(), category);

					assertEquals("Isolated farm ratings should not create " + category + " score",
							0L, snapshot.getScore());
					assertEquals("Isolated farm ratings should not create " + category + " level",
							0, snapshot.getLevel());
					assertEquals("Isolated farm accounts should remain Unverified",
							AccountTrustStatus.UNVERIFIED, snapshot.getMappedTrustStatus());
					assertEquals("Isolated farm accounts should have zero effective weight",
							0, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, snapshot));
				}
			}
		}
	}

	@Test
	public void testMistakenSuspiciousRatingRecoveryLeavesOtherLaunchStatusesStable() throws Exception {
		AccountTrustTestUtils.useAccountRatingCooldown(3);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "dilbert");
			List<PrivateKeyAccount> allSupporters = Arrays.asList(supporterA, supporterB);
			PrivateKeyAccount goldTarget = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount silverTarget = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount suspiciousTarget = Common.generateRandomSeedAccount(repository);

			ensureKnownAccounts(repository, seedAccount, supporterA, supporterB, goldTarget, silverTarget,
					suspiciousTarget);
			prepareTrustedSupporters(repository, seedAccount, allSupporters);

			rateByAll(repository, allSupporters, goldTarget, 4);
			rateByAll(repository, allSupporters, silverTarget, 2);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);
			rateByAllSigned(repository, Arrays.asList(supporterA, supporterB), suspiciousTarget, -2);

			assertSubjectStatus(repository, goldTarget, AccountTrustStatus.GOLD, 1_000);
			assertSubjectStatus(repository, silverTarget, AccountTrustStatus.SILVER, 700);
			assertSubjectStatus(repository, suspiciousTarget, AccountTrustStatus.SUSPICIOUS, 0);
			assertFalse(AccountTrustWeight.canMint(findSnapshot(repository, suspiciousTarget.getAddress(),
					AccountRatingCategory.SUBJECT)));

			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_CHANGE_TOO_SOON,
					Transaction.fromData(repository, ratingData(supporterA, suspiciousTarget,
							AccountRatingCategory.SUBJECT, AccountRating.NO_RATING)).isValid());

			BlockUtils.mintBlock(repository);

			TransactionUtils.signAndMint(repository,
					ratingData(supporterA, suspiciousTarget, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING),
					supporterA);

			assertNull(repository.getAccountRatingRepository().getRating(suspiciousTarget.getPublicKey(),
					supporterA.getPublicKey(), AccountRatingCategory.SUBJECT));
			assertSubjectStatus(repository, suspiciousTarget, AccountTrustStatus.UNVERIFIED, 0);
			assertTrue(AccountTrustWeight.canMint(findSnapshot(repository, suspiciousTarget.getAddress(),
					AccountRatingCategory.SUBJECT)));
			assertSubjectStatus(repository, goldTarget, AccountTrustStatus.GOLD, 1_000);
			assertSubjectStatus(repository, silverTarget, AccountTrustStatus.SILVER, 700);
		}
	}

	private void prepareTrustedSupporters(Repository repository, PrivateKeyAccount seedAccount,
			List<? extends PrivateKeyAccount> supporters) throws DataException {
		for (PrivateKeyAccount supporter : supporters)
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporter);
		AccountTrustTestUtils.refreshTrustSnapshots(repository);
	}

	private void rateByAll(Repository repository, List<? extends PrivateKeyAccount> raters,
			PrivateKeyAccount target, int rating) throws DataException {
		for (PrivateKeyAccount rater : raters)
			AccountTrustTestUtils.saveAccountRating(repository, rater, target, AccountRatingCategory.SUBJECT, rating);
	}

	private void rateByAllSigned(Repository repository, List<? extends PrivateKeyAccount> raters,
			PrivateKeyAccount target, int rating) throws DataException {
		for (PrivateKeyAccount rater : raters)
			TransactionUtils.signAndMint(repository, ratingData(rater, target, AccountRatingCategory.SUBJECT, rating),
					rater);
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
		repository.saveChanges();
	}

	private void assertSubjectStatus(Repository repository, PrivateKeyAccount target,
			AccountTrustStatus expectedStatus, int expectedVoteWeight) throws DataException {
		AccountTrustSnapshotData subject = findSnapshot(repository, target.getAddress(), AccountRatingCategory.SUBJECT);

		assertEquals(expectedStatus, subject.getMappedTrustStatus());
		assertEquals(expectedVoteWeight, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, subject));
	}

	private void assertFarmAccountRemainsUnverified(Repository repository, PrivateKeyAccount farmAccount)
			throws DataException {
		for (AccountRatingCategory category : AccountRatingCategory.values()) {
			AccountTrustSnapshotData snapshot = findSnapshot(repository, farmAccount.getAddress(), category);

			assertEquals("Isolated farm ratings should not create " + category + " score", 0L, snapshot.getScore());
			assertEquals("Isolated farm ratings should not create " + category + " level", 0, snapshot.getLevel());
			assertEquals("Isolated farm accounts should remain Unverified", AccountTrustStatus.UNVERIFIED,
					snapshot.getMappedTrustStatus());
			assertEquals("Isolated farm accounts should have zero effective weight", 0,
					AccountTrustWeight.calculateEffectiveVoteWeight(1_000, snapshot));
		}
	}

	private AccountTrustSnapshotData findSnapshot(Repository repository, String accountAddress,
			AccountRatingCategory category) throws DataException {
		return repository.getAccountRatingRepository().getTrustDerivationSnapshots(accountAddress).stream()
				.filter(snapshot -> snapshot.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing snapshot for " + accountAddress + " in category " + category));
	}

	private AccountTrustSummaryData.StatusSummary findStatusSummary(AccountTrustSummaryData summary,
			AccountTrustStatus status) {
		return summary.getStatusSummaries().stream()
				.filter(statusSummary -> statusSummary.getStatus() == status)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing summary for status " + status));
	}
}
