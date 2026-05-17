package org.qortal.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.AccountTrustPolicy;
import org.qortal.account.AccountTrustWeight;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.account.AccountRating;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.account.AccountTrustSummaryData;
import org.qortal.data.transaction.RateAccountTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountTrustTestUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction;

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

	private void assertSubjectStatus(Repository repository, PrivateKeyAccount target,
			AccountTrustStatus expectedStatus, int expectedVoteWeight) throws DataException {
		AccountTrustSnapshotData subject = findSnapshot(repository, target.getAddress(), AccountRatingCategory.SUBJECT);

		assertEquals(expectedStatus, subject.getMappedTrustStatus());
		assertEquals(expectedVoteWeight, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, subject));
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
