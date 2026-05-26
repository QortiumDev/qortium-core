package org.qortium.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.resource.AccountRatingsResource;
import org.qortium.data.account.AccountData;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustCategoryImpactData;
import org.qortium.data.account.AccountTrustExplanationData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.transaction.RateAccountTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountTrustTestUtils;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.utils.Base58;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AccountTrustExplanationApiTests extends ApiCommon {

	private AccountRatingsResource accountRatingsResource;

	@Before
	public void buildResource() {
		this.accountRatingsResource = (AccountRatingsResource) ApiCommon.buildResource(AccountRatingsResource.class);
	}

	@Test
	public void testEmptyKnownAccountExplainsUnverifiedCategories() throws DataException {
		TestAccount bob;

		try (final Repository repository = RepositoryManager.getRepository()) {
			bob = Common.getTestAccount(repository, "bob");
			ensureKnownAccount(repository, bob);
			repository.saveChanges();
		}

		AccountTrustExplanationData explanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(bob.getPublicKey()), null);

		assertFalse(explanation.isLive());
		assertEquals(bob.getAddress(), explanation.getTargetAddress());
		assertEquals(AccountTrustStatus.UNVERIFIED, explanation.getTrustStatus());
		assertEquals(0, explanation.getTrustWeightPercent());
		assertNull(explanation.getSnapshotHeight());
		assertNull(explanation.getSnapshotTimestamp());
		assertEquals(AccountRatingCategory.values().length, explanation.getCategories().size());

		for (AccountTrustExplanationData.CategoryExplanation category : explanation.getCategories()) {
			assertEquals(0L, category.getScore());
			assertEquals(0L, category.getLevelScore());
			assertEquals(0, category.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, category.getMappedTrustStatus());
			assertFalse(category.getConfiguredLevels().isEmpty());
			assertFalse(category.getRequirements().isEmpty());
			assertTrue(category.getTopPositiveImpacts().isEmpty());
			assertTrue(category.getTopNegativeImpacts().isEmpty());
		}
	}

	@Test
	public void testStoredAndLiveExplanationsRemainDistinguishable() throws DataException {
		TestAccount alice;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			AccountTrustTestUtils.createDerivedSilverSubjectSnapshot(repository, alice, bob, chloe, dilbert);
		}

		String alicePublicKey58 = Base58.encode(alice.getPublicKey());
		AccountTrustExplanationData storedExplanation = this.accountRatingsResource
				.getAccountTrustExplanation(alicePublicKey58, null);
		AccountTrustExplanationData liveExplanation = this.accountRatingsResource
				.getAccountTrustExplanation(alicePublicKey58, true);
		AccountTrustExplanationData.CategoryExplanation storedSubject = findCategory(storedExplanation,
				AccountRatingCategory.SUBJECT);
		AccountTrustExplanationData.CategoryExplanation liveSubject = findCategory(liveExplanation,
				AccountRatingCategory.SUBJECT);

		assertFalse(storedExplanation.isLive());
		assertNotNull(storedExplanation.getSnapshotHeight());
		assertNotNull(storedExplanation.getSnapshotTimestamp());
		assertEquals(AccountTrustStatus.SILVER, storedExplanation.getTrustStatus());
		assertEquals(96_000_000L, storedSubject.getScore());
		assertEquals(50_000_000L, storedSubject.getLevelScore());
		assertEquals(2, storedSubject.getLevel());

		assertTrue(liveExplanation.isLive());
		assertNull(liveExplanation.getSnapshotHeight());
		assertNull(liveExplanation.getSnapshotTimestamp());
		assertEquals(AccountTrustStatus.SILVER, liveExplanation.getTrustStatus());
		assertEquals(storedSubject.getScore(), liveSubject.getScore());
		assertEquals(storedSubject.getLevel(), liveSubject.getLevel());
	}

	@Test
	public void testPositiveSubjectExplanationShowsPassedThresholdBranchAndSupportChecks() throws DataException {
		TestAccount subject;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount playerA = Common.getTestAccount(repository, "bob");
			TestAccount playerB = Common.getTestAccount(repository, "dilbert");
			subject = Common.getTestAccount(repository, "chloe");

			ensureKnownAccounts(repository, seedAccount, playerA, playerB, subject);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, playerA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, playerB);
			AccountTrustTestUtils.saveAccountRating(repository, playerA, subject, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.saveAccountRating(repository, playerB, subject, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);
		}

		AccountTrustExplanationData explanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(subject.getPublicKey()), null);
		AccountTrustExplanationData.CategoryExplanation subjectExplanation = findCategory(explanation,
				AccountRatingCategory.SUBJECT);

		assertEquals(AccountTrustStatus.GOLD, explanation.getTrustStatus());
		assertEquals(128_000_000L, subjectExplanation.getScore());
		assertEquals(100_000_000L, subjectExplanation.getLevelScore());
		assertEquals(50_000_000L, subjectExplanation.getLevelScoreCap());
		assertEquals(3, subjectExplanation.getLevel());
		assertEquals(AccountTrustStatus.GOLD, subjectExplanation.getMappedTrustStatus());

		assertTrue(findRequirement(subjectExplanation, "level.3.threshold").isPassed());
		assertEquals("100000000", findRequirement(subjectExplanation, "level.3.threshold").getActual());
		assertEquals("100000000", findRequirement(subjectExplanation, "level.3.threshold").getRequired());

		AccountTrustExplanationData.Requirement branchRequirement = findRequirement(subjectExplanation,
				"level.3.independent-branches");
		assertTrue(branchRequirement.isPassed());
		assertEquals("4", branchRequirement.getActual());
		assertEquals("2", branchRequirement.getRequired());

		AccountTrustExplanationData.Requirement supportRequirement = findRequirement(subjectExplanation,
				"level.3.positive-support");
		assertTrue(supportRequirement.isPassed());
		assertEquals("2", supportRequirement.getActual());
		assertEquals(">= 1 high-confidence or >= 2 medium-confidence", supportRequirement.getRequired());

		assertEquals(2, subjectExplanation.getTopPositiveImpacts().size());
		for (AccountTrustCategoryImpactData impact : subjectExplanation.getTopPositiveImpacts()) {
			assertEquals(4, impact.getRating());
			assertEquals(4, impact.getRatingConfidence());
			assertEquals(64_000_000L, impact.getImpact());
		}
		assertTrue(subjectExplanation.getTopNegativeImpacts().isEmpty());
	}

	@Test
	public void testSameBranchPositiveExplanationShowsAuditableEvidenceAndFailedBranchCheck()
			throws DataException {
		TestAccount subject;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount playerA = Common.getTestAccount(repository, "bob");
			TestAccount playerB = Common.getTestAccount(repository, "dilbert");
			subject = Common.getTestAccount(repository, "chloe");

			ensureKnownAccounts(repository, seedAccount, playerA, playerB, subject);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatingsFromSharedManagerBranch(repository, seedAccount,
					Arrays.asList(playerA, playerB));
			AccountTrustTestUtils.saveAccountRating(repository, playerA, subject, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.saveAccountRating(repository, playerB, subject, AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);
		}

		AccountTrustExplanationData explanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(subject.getPublicKey()), null);
		AccountTrustExplanationData.CategoryExplanation subjectExplanation = findCategory(explanation,
				AccountRatingCategory.SUBJECT);

		assertEquals(AccountTrustStatus.UNVERIFIED, explanation.getTrustStatus());
		assertTrue(subjectExplanation.getScore() >= 150_000_000L);
		assertEquals(0, subjectExplanation.getLevel());
		assertEquals(AccountTrustStatus.UNVERIFIED, subjectExplanation.getMappedTrustStatus());
		assertFalse(subjectExplanation.getTopPositiveImpacts().isEmpty());

		AccountTrustExplanationData.Requirement thresholdRequirement = findRequirement(subjectExplanation,
				"level.4.threshold");
		assertTrue(thresholdRequirement.isPassed());

		AccountTrustExplanationData.Requirement branchRequirement = findRequirement(subjectExplanation,
				"level.4.independent-branches");
		assertFalse(branchRequirement.isPassed());
		assertEquals("1", branchRequirement.getActual());
		assertEquals("2", branchRequirement.getRequired());
	}

	@Test
	public void testLowConfidenceNegativeExplanationShowsThresholdHitButFailedConfidenceRequirements()
			throws DataException {
		TestAccount target;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount playerA = Common.getTestAccount(repository, "bob");
			TestAccount playerB = Common.getTestAccount(repository, "dilbert");
			target = Common.getTestAccount(repository, "chloe");

			ensureKnownAccounts(repository, seedAccount, playerA, playerB, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, playerA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, playerB);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository, ratingData(playerA, target, AccountRatingCategory.SUBJECT, -1),
					playerA);
			TransactionUtils.signAndMint(repository, ratingData(playerB, target, AccountRatingCategory.SUBJECT, -1),
					playerB);
		}

		AccountTrustExplanationData explanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(target.getPublicKey()), null);
		AccountTrustExplanationData.CategoryExplanation subjectExplanation = findCategory(explanation,
				AccountRatingCategory.SUBJECT);

		assertEquals(AccountTrustStatus.UNVERIFIED, explanation.getTrustStatus());
		assertEquals(-128_000_000L, subjectExplanation.getScore());
		assertEquals(-10_000_000L, subjectExplanation.getLevelScore());
		assertEquals(0, subjectExplanation.getLevel());
		assertEquals(2, subjectExplanation.getTopNegativeImpacts().size());
		for (AccountTrustCategoryImpactData impact : subjectExplanation.getTopNegativeImpacts())
			assertEquals(1, impact.getRatingConfidence());

		AccountTrustExplanationData.Requirement suspiciousThreshold = findRequirement(subjectExplanation,
				"suspicious.threshold");
		assertTrue(suspiciousThreshold.isPassed());
		assertEquals("-10000000", suspiciousThreshold.getActual());
		assertEquals("-10000000", suspiciousThreshold.getRequired());

		AccountTrustExplanationData.Requirement suspiciousRaters = findRequirement(subjectExplanation,
				"suspicious.independent-raters");
		assertFalse(suspiciousRaters.isPassed());
		assertEquals("0", suspiciousRaters.getActual());
		assertEquals("2", suspiciousRaters.getRequired());

		AccountTrustExplanationData.Requirement suspiciousBranches = findRequirement(subjectExplanation,
				"suspicious.independent-branches");
		assertFalse(suspiciousBranches.isPassed());
		assertEquals("0", suspiciousBranches.getActual());
		assertEquals("2", suspiciousBranches.getRequired());
	}

	@Test
	public void testIndependentNegativeExplanationShowsSuspiciousRequirementsPassing() throws DataException {
		TestAccount target;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount playerA = Common.getTestAccount(repository, "bob");
			TestAccount playerB = Common.getTestAccount(repository, "dilbert");
			target = Common.getTestAccount(repository, "chloe");

			ensureKnownAccounts(repository, seedAccount, playerA, playerB, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, playerA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, playerB);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository, ratingData(playerA, target, AccountRatingCategory.SUBJECT, -2),
					playerA);
			TransactionUtils.signAndMint(repository, ratingData(playerB, target, AccountRatingCategory.SUBJECT, -2),
					playerB);
		}

		AccountTrustExplanationData explanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(target.getPublicKey()), null);
		AccountTrustExplanationData.CategoryExplanation subjectExplanation = findCategory(explanation,
				AccountRatingCategory.SUBJECT);

		assertEquals(AccountTrustStatus.SUSPICIOUS, explanation.getTrustStatus());
		assertEquals(-256_000_000L, subjectExplanation.getScore());
		assertEquals(-10_000_000L, subjectExplanation.getLevelScore());
		assertEquals(5_000_000L, subjectExplanation.getLevelScoreCap());
		assertEquals(-1, subjectExplanation.getLevel());
		assertEquals(AccountTrustStatus.SUSPICIOUS, subjectExplanation.getMappedTrustStatus());
		assertEquals(2, subjectExplanation.getTopNegativeImpacts().size());

		AccountTrustExplanationData.Requirement suspiciousThreshold = findRequirement(subjectExplanation,
				"suspicious.threshold");
		assertTrue(suspiciousThreshold.isPassed());
		assertEquals("-10000000", suspiciousThreshold.getActual());
		assertEquals("-10000000", suspiciousThreshold.getRequired());

		AccountTrustExplanationData.Requirement suspiciousRaters = findRequirement(subjectExplanation,
				"suspicious.independent-raters");
		assertTrue(suspiciousRaters.isPassed());
		assertEquals("2", suspiciousRaters.getActual());
		assertEquals("2", suspiciousRaters.getRequired());

		AccountTrustExplanationData.Requirement suspiciousBranches = findRequirement(subjectExplanation,
				"suspicious.independent-branches");
		assertTrue(suspiciousBranches.isPassed());
		assertEquals("4", suspiciousBranches.getActual());
		assertEquals("2", suspiciousBranches.getRequired());
	}

	@Test
	public void testTopImpactsAreLimitedAndSortedByStrongestImpactFirst() throws DataException {
		TestAccount target;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			target = Common.getTestAccount(repository, "bob");
			List<PrivateKeyAccount> evaluators = AccountTrustTestUtils.saveManagerEnergyPaths(repository,
					seedAccount, 12);
			int[] ratings = new int[] { 4, 3, 2, 1, 1, 1, -4, -3, -2, -1, -1, -1 };

			ensureKnownAccount(repository, target);
			for (int i = 0; i < evaluators.size(); ++i)
				AccountTrustTestUtils.saveAccountRating(repository, evaluators.get(i), target,
						AccountRatingCategory.MANAGER, ratings[i]);

			AccountTrustTestUtils.refreshTrustSnapshots(repository);
		}

		AccountTrustExplanationData explanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(target.getPublicKey()), true);
		AccountTrustExplanationData.CategoryExplanation managerExplanation = findCategory(explanation,
				AccountRatingCategory.MANAGER);

		assertEquals(5, managerExplanation.getTopPositiveImpacts().size());
		assertEquals(5, managerExplanation.getTopNegativeImpacts().size());
		assertImpactsSortedByAbsoluteValue(managerExplanation.getTopPositiveImpacts());
		assertImpactsSortedByAbsoluteValue(managerExplanation.getTopNegativeImpacts());
		assertEquals(4, managerExplanation.getTopPositiveImpacts().get(0).getRating());
		assertEquals(-4, managerExplanation.getTopNegativeImpacts().get(0).getRating());
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), target.getPublicKey(), category, rating);
	}

	private void ensureKnownAccounts(Repository repository, PrivateKeyAccount... accounts) throws DataException {
		for (PrivateKeyAccount account : accounts)
			ensureKnownAccount(repository, account);
	}

	private void ensureKnownAccount(Repository repository, PrivateKeyAccount account) throws DataException {
		repository.getAccountRepository()
				.ensureAccount(new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, 0));
	}

	private AccountTrustExplanationData.CategoryExplanation findCategory(AccountTrustExplanationData explanation,
			AccountRatingCategory category) {
		return explanation.getCategories().stream()
				.filter(categoryExplanation -> categoryExplanation.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing category " + category));
	}

	private AccountTrustExplanationData.Requirement findRequirement(
			AccountTrustExplanationData.CategoryExplanation category, String name) {
		return category.getRequirements().stream()
				.filter(requirement -> requirement.getName().equals(name))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing requirement " + name));
	}

	private void assertImpactsSortedByAbsoluteValue(List<AccountTrustCategoryImpactData> impacts) {
		long previousImpact = Long.MAX_VALUE;
		for (AccountTrustCategoryImpactData impact : impacts) {
			long currentImpact = Math.abs(impact.getImpact());
			assertTrue("Impacts should be sorted strongest first", currentImpact <= previousImpact);
			previousImpact = currentImpact;
		}
	}
}
