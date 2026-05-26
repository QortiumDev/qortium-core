package org.qortium.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.AccountTrustDerivation;
import org.qortium.account.AccountTrustPolicy;
import org.qortium.account.AccountTrustWeight;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.account.AccountData;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustCategoryData;
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

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AccountTrustBootstrapWalkthroughTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testTrustBootstrapsFromMintingSeedThroughSubjectStatus() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount untrustedRater = Common.getTestAccount(repository, "bob");
			TestAccount managerA = Common.getTestAccount(repository, "chloe");
			TestAccount managerB = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount trainerA = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount trainerB = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount playerA = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount playerB = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount subject = Common.generateRandomSeedAccount(repository);

			ensureKnownAccounts(repository, seedAccount, untrustedRater, managerA, managerB, trainerA, trainerB,
					playerA, playerB, subject);
			setBlocksMinted(repository, subject, 1_000);

			AccountTrustTestUtils.saveAccountRating(repository, untrustedRater, subject,
					AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			AccountTrustSnapshotData seedManager = findSnapshot(repository, seedAccount.getAddress(),
					AccountRatingCategory.MANAGER);
			assertTrue("Minting group membership should seed Manager energy",
					seedManager.isMintingSeedMember());
			assertEquals("Minting group membership should not directly assign a Manager score",
					AccountTrustStatus.UNVERIFIED, seedManager.getMappedTrustStatus());

			AccountTrustSnapshotData subjectBeforeQualifiedSupport = findSnapshot(repository, subject.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals("Stored ratings from unqualified raters should remain auditable",
					1, subjectBeforeQualifiedSupport.getInboundRatings().getPositiveVeryHighCount());
			assertEquals("Unqualified Subject ratings should not create active score",
					0L, subjectBeforeQualifiedSupport.getScore());
			assertEquals(AccountTrustStatus.UNVERIFIED,
					subjectBeforeQualifiedSupport.getMappedTrustStatus());
			assertEquals(0, AccountTrustWeight.calculateEffectiveVoteWeight(1_000,
					subjectBeforeQualifiedSupport));

			AccountTrustTestUtils.saveDerivedManagerLevelTwoRatings(repository, seedAccount,
					Arrays.asList(managerA, managerB));
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertStatus(repository, managerA, AccountRatingCategory.MANAGER, AccountTrustStatus.SILVER);
			assertStatus(repository, managerB, AccountRatingCategory.MANAGER, AccountTrustStatus.SILVER);
			assertStatus(repository, managerA, AccountRatingCategory.TRAINER, AccountTrustStatus.UNVERIFIED);
			assertStatus(repository, subject, AccountRatingCategory.SUBJECT, AccountTrustStatus.UNVERIFIED);

			AccountTrustTestUtils.saveAccountRating(repository, managerA, trainerA,
					AccountRatingCategory.TRAINER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, managerB, trainerA,
					AccountRatingCategory.TRAINER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, managerA, trainerB,
					AccountRatingCategory.TRAINER, 4);
			AccountTrustTestUtils.saveAccountRating(repository, managerB, trainerB,
					AccountRatingCategory.TRAINER, 4);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertStatus(repository, trainerA, AccountRatingCategory.TRAINER, AccountTrustStatus.SILVER);
			assertStatus(repository, trainerB, AccountRatingCategory.TRAINER, AccountTrustStatus.SILVER);
			assertStatus(repository, trainerA, AccountRatingCategory.PLAYER, AccountTrustStatus.UNVERIFIED);
			assertStatus(repository, subject, AccountRatingCategory.SUBJECT, AccountTrustStatus.UNVERIFIED);

			AccountTrustTestUtils.saveAccountRating(repository, trainerA, playerA,
					AccountRatingCategory.PLAYER, 2);
			AccountTrustTestUtils.saveAccountRating(repository, trainerB, playerA,
					AccountRatingCategory.PLAYER, 2);
			AccountTrustTestUtils.saveAccountRating(repository, trainerA, playerB,
					AccountRatingCategory.PLAYER, 2);
			AccountTrustTestUtils.saveAccountRating(repository, trainerB, playerB,
					AccountRatingCategory.PLAYER, 2);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertStatus(repository, playerA, AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);
			assertStatus(repository, playerB, AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);
			assertStatus(repository, playerA, AccountRatingCategory.SUBJECT, AccountTrustStatus.UNVERIFIED);
			assertStatus(repository, subject, AccountRatingCategory.SUBJECT, AccountTrustStatus.UNVERIFIED);

			AccountTrustTestUtils.saveAccountRating(repository, playerA, subject,
					AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.saveAccountRating(repository, playerB, subject,
					AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			AccountTrustSnapshotData subjectAfterQualifiedSupport = findSnapshot(repository, subject.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals("All stored Subject ratings should stay visible in inbound counts",
					3, subjectAfterQualifiedSupport.getInboundRatings().getPositiveVeryHighCount());
			AccountTrustCategoryData liveSubjectCategory = findLiveCategory(repository, subject,
					AccountRatingCategory.SUBJECT);
			assertEquals("Only qualified Player support should contribute active Subject impacts",
					2, liveSubjectCategory.getImpacts().size());
			assertTrue(liveSubjectCategory.getImpacts().stream()
					.allMatch(impact -> impact.getRaterAddress().equals(playerA.getAddress())
							|| impact.getRaterAddress().equals(playerB.getAddress())));
			assertEquals(liveSubjectCategory.getScore(), subjectAfterQualifiedSupport.getScore());
			assertTrue("Qualified Player support should reach a Gold Subject level",
					subjectAfterQualifiedSupport.getLevel() >= 3);
			assertEquals(AccountTrustStatus.GOLD, subjectAfterQualifiedSupport.getMappedTrustStatus());
			assertEquals(1_000, AccountTrustWeight.calculateEffectiveVoteWeight(1_000,
					subjectAfterQualifiedSupport));

			AccountTrustSummaryData summary = repository.getAccountRatingRepository()
					.getTrustSummary(AccountTrustPolicy.getActiveWeightCategory());
			assertTrue(summary.isSnapshotsComplete());
			assertTrue("Bootstrap walkthrough should include active account ratings",
					summary.getActiveRatingCount() > 0);
			assertTrue("Bootstrap walkthrough should include a Gold Subject status summary",
					findStatusSummary(summary, AccountTrustStatus.GOLD).getAccountCount() > 0);
			assertEquals(1, findStatusChanges(repository, subject, AccountTrustStatus.UNVERIFIED,
					AccountTrustStatus.GOLD).size());
		}
	}

	private void ensureKnownAccounts(Repository repository, PrivateKeyAccount... accounts) throws DataException {
		for (PrivateKeyAccount account : accounts)
			AccountTrustTestUtils.ensureKnownAccount(repository, account);
	}

	private void assertStatus(Repository repository, PrivateKeyAccount account, AccountRatingCategory category,
			AccountTrustStatus expectedStatus) throws DataException {
		assertEquals(expectedStatus, findSnapshot(repository, account.getAddress(), category).getMappedTrustStatus());
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
				.orElseThrow(() -> new AssertionError("Missing snapshot for " + accountAddress
						+ " in category " + category));
	}

	private AccountTrustCategoryData findLiveCategory(Repository repository, PrivateKeyAccount account,
			AccountRatingCategory category) throws DataException {
		return AccountTrustDerivation.derive(repository, account.getAddress()).getCategories().stream()
				.filter(categoryData -> categoryData.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing live category " + category
						+ " for " + account.getAddress()));
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
