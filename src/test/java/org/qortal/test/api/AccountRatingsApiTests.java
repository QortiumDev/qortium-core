package org.qortal.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.AccountTrustDerivation;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.ApiError;
import org.qortal.api.resource.AccountRatingsResource;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountRating;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingSummaryData;
import org.qortal.data.account.AccountTrustDerivationData;
import org.qortal.data.account.AccountTrustExplanationData;
import org.qortal.data.account.AccountTrustPreviewData;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.transaction.RateAccountTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountTrustTestUtils;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.utils.Base58;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AccountRatingsApiTests extends ApiCommon {

	private AccountRatingsResource accountRatingsResource;

	@Before
	public void buildResource() {
		this.accountRatingsResource = (AccountRatingsResource) ApiCommon.buildResource(AccountRatingsResource.class);
	}

	@Test
	public void testResource() {
		assertNotNull(this.accountRatingsResource);
	}

	@Test
	public void testSummaryAndListEndpoints() throws DataException {
		TestAccount bob;
		TestAccount alice;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			AccountRatingSummaryData emptySummary = this.accountRatingsResource.getAccountRatingSummary(Base58.encode(bob.getPublicKey()));
			assertEquals(0, emptySummary.getTotalRatingCount());

			AccountTrustPreviewData emptyPreview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(bob.getPublicKey()));
			assertEquals(AccountTrustStatus.UNVERIFIED, emptyPreview.getTrustStatus());
			assertEquals(0, emptyPreview.getTrustWeightPercent());
			assertEquals(0, emptyPreview.getInboundTotalRatingCount());
			assertEquals(0, emptyPreview.getOutboundTotalRatingCount());
			assertEquals(0, emptyPreview.getNetScore());

			AccountTrustExplanationData emptyExplanation = this.accountRatingsResource
					.getAccountTrustExplanation(Base58.encode(bob.getPublicKey()), null);
			assertFalse(emptyExplanation.isLive());
			assertEquals(AccountTrustStatus.UNVERIFIED, emptyExplanation.getTrustStatus());
			assertEquals(0, emptyExplanation.getTrustWeightPercent());
			assertEquals(AccountRatingCategory.values().length, emptyExplanation.getCategories().size());

			for (AccountTrustExplanationData.CategoryExplanation category : emptyExplanation.getCategories()) {
				assertEquals(0L, category.getScore());
				assertEquals(0, category.getLevel());
				assertEquals(AccountTrustStatus.UNVERIFIED, category.getMappedTrustStatus());
				assertFalse(category.getConfiguredLevels().isEmpty());
			}

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, 4), alice);
			TransactionUtils.signAndMint(repository, ratingData(chloe, bob, 2), chloe);
		}

		String targetPublicKey58 = Base58.encode(bob.getPublicKey());
		String raterPublicKey58 = Base58.encode(alice.getPublicKey());

		AccountRatingSummaryData summary = this.accountRatingsResource.getAccountRatingSummary(targetPublicKey58);
		assertEquals(1, summary.getPositiveVeryHighCount());
		assertEquals(1, summary.getPositiveMediumCount());
		assertEquals(0, summary.getNegativeRatingCount());
		assertEquals(2, summary.getTotalRatingCount());

		List<AccountRatingData> ratings = this.accountRatingsResource.getAccountRatings(targetPublicKey58, null, null, null, null);
		assertEquals(2, ratings.size());

		List<AccountRatingData> filteredRatings = this.accountRatingsResource.getAccountRatings(targetPublicKey58, raterPublicKey58,
				null, null, null);
		assertEquals(1, filteredRatings.size());
		assertEquals(4, filteredRatings.get(0).getRating());
		assertEquals("POSITIVE", filteredRatings.get(0).getRatingDirection());
		assertEquals(4, filteredRatings.get(0).getRatingConfidence());
	}

	@Test
	public void testTrustPreviewCountsScoresAndMutualPositiveRatings() throws DataException {
		TestAccount bob;
		String aliceAddress;
		String chloeAddress;
		String dilbertAddress;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			aliceAddress = alice.getAddress();
			bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			chloeAddress = chloe.getAddress();
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");
			dilbertAddress = dilbert.getAddress();

			setVoteAccount(repository, alice, 100);
			setVoteAccount(repository, bob, 100);
			setVoteAccount(repository, chloe, 101);
			setVoteAccount(repository, dilbert, 101);

			saveAccountRating(repository, alice, bob, AccountRatingCategory.SUBJECT, 4);
			saveAccountRating(repository, chloe, bob, AccountRatingCategory.SUBJECT, 2);
			saveAccountRating(repository, dilbert, bob, AccountRatingCategory.SUBJECT, -3);
			saveAccountRating(repository, bob, alice, AccountRatingCategory.SUBJECT, 1);
			saveAccountRating(repository, bob, chloe, AccountRatingCategory.SUBJECT, 4);
			saveAccountRating(repository, bob, dilbert, AccountRatingCategory.SUBJECT, -2);
			saveSubjectSnapshots(repository,
					subjectDerivation(bob, AccountTrustStatus.BRONZE),
					subjectDerivation(alice, AccountTrustStatus.SILVER),
					subjectDerivation(chloe, AccountTrustStatus.UNVERIFIED),
					subjectDerivation(dilbert, AccountTrustStatus.GOLD));
		}

		AccountTrustPreviewData preview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(bob.getPublicKey()));

		assertEquals(AccountTrustStatus.BRONZE, preview.getTrustStatus());
		assertEquals(AccountTrustStatus.BRONZE.getValue(), preview.getTrustStatusValue());
		assertEquals(25, preview.getTrustWeightPercent());
		assertEquals(1, preview.getInboundRatings().getPositiveVeryHighCount());
		assertEquals(1, preview.getInboundRatings().getPositiveMediumCount());
		assertEquals(1, preview.getInboundRatings().getNegativeHighCount());
		assertEquals(3, preview.getInboundTotalRatingCount());
		assertEquals(1, preview.getOutboundRatings().getPositiveLowCount());
		assertEquals(1, preview.getOutboundRatings().getPositiveVeryHighCount());
		assertEquals(1, preview.getOutboundRatings().getNegativeMediumCount());
		assertEquals(3, preview.getOutboundTotalRatingCount());
		assertEquals(2, preview.getMutualPositiveCount());
		assertEquals(200, preview.getPositiveScore());
		assertEquals(1212, preview.getNegativeScore());
		assertEquals(-1012, preview.getNetScore());

		assertEquals(3, preview.getEvaluatorImpacts().size());
		AccountTrustPreviewData.EvaluatorImpact aliceImpact = findEvaluatorImpact(preview, aliceAddress);
		assertEquals(4, aliceImpact.getRating());
		assertEquals(4, aliceImpact.getRatingConfidence());
		assertEquals(AccountTrustStatus.SILVER, aliceImpact.getTrustStatus());
		assertEquals(100, aliceImpact.getRawVoteWeight());
		assertEquals(50, aliceImpact.getEffectiveVoteWeight());
		assertEquals(200, aliceImpact.getImpact());

		AccountTrustPreviewData.EvaluatorImpact chloeImpact = findEvaluatorImpact(preview, chloeAddress);
		assertEquals(AccountTrustStatus.UNVERIFIED, chloeImpact.getTrustStatus());
		assertEquals(0, chloeImpact.getEffectiveVoteWeight());
		assertEquals(0, chloeImpact.getImpact());

		AccountTrustPreviewData.EvaluatorImpact dilbertImpact = findEvaluatorImpact(preview, dilbertAddress);
		assertEquals(-3, dilbertImpact.getRating());
		assertEquals("NEGATIVE", dilbertImpact.getRatingDirection());
		assertEquals(AccountTrustStatus.GOLD, dilbertImpact.getTrustStatus());
		assertEquals(101, dilbertImpact.getEffectiveVoteWeight());
		assertEquals(-1212, dilbertImpact.getImpact());
	}

	@Test
	public void testTrustPreviewDoesNotChangeTrustStatusOrVoteWeight() throws DataException {
		TestAccount bob;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			setVoteAccount(repository, alice, 100);
			setVoteAccount(repository, chloe, 100);
			TransactionUtils.signAndMint(repository, ratingData(alice, bob, 4), alice);
			TransactionUtils.signAndMint(repository, ratingData(bob, alice, 4), bob);
			TransactionUtils.signAndMint(repository, ratingData(chloe, bob, 4), chloe);
			TransactionUtils.signAndMint(repository, ratingData(bob, chloe, 4), bob);

			setVoteAccount(repository, bob, 100);
		}

		AccountTrustPreviewData preview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(bob.getPublicKey()));
		assertEquals(0, preview.getPositiveScore());
		assertEquals(2, preview.getMutualPositiveCount());
		assertEquals(AccountTrustStatus.UNVERIFIED, preview.getTrustStatus());
		assertEquals(0, preview.getTrustWeightPercent());

		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountData bobAccountData = repository.getAccountRepository().getAccount(bob.getAddress());

			assertEquals(AccountTrustStatus.UNVERIFIED, bobAccountData.getTrustStatus());
			assertEquals(0, bobAccountData.getEffectiveVoteWeight());
		}
	}

	@Test
	public void testCategoryAwareListAndSummaryEndpoints() throws DataException {
		TestAccount bob;
		TestAccount alice;
		TestAccount chloe;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			chloe = Common.getTestAccount(repository, "chloe");

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4), alice);
			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.PLAYER, -2), alice);
			TransactionUtils.signAndMint(repository, ratingData(chloe, bob, AccountRatingCategory.SUBJECT, 2), chloe);
			TransactionUtils.signAndMint(repository, ratingData(alice, chloe, AccountRatingCategory.SUBJECT, 1), alice);
			TransactionUtils.signAndMint(repository, ratingData(bob, alice, AccountRatingCategory.SUBJECT, 3), bob);
		}

		String targetPublicKey58 = Base58.encode(bob.getPublicKey());
		String raterPublicKey58 = Base58.encode(alice.getPublicKey());

		List<AccountRatingData> allRatings = this.accountRatingsResource.getAccountRatings(targetPublicKey58, raterPublicKey58,
				null, null, null, null);
		assertEquals(2, allRatings.size());

		List<AccountRatingData> playerRatings = this.accountRatingsResource.getAccountRatings(targetPublicKey58, raterPublicKey58,
				AccountRatingCategory.PLAYER.name(), null, null, null);
		assertEquals(1, playerRatings.size());
		assertEquals(AccountRatingCategory.PLAYER, playerRatings.get(0).getCategory());
		assertEquals(-2, playerRatings.get(0).getRating());

		List<AccountRatingData> inboundSubjectRatings = this.accountRatingsResource.getAccountRatings(targetPublicKey58,
				null, AccountRatingCategory.SUBJECT.name(), null, null, null);
		assertEquals(2, inboundSubjectRatings.size());
		assertEquals(4, findRatingByRater(inboundSubjectRatings, alice.getAddress()).getRating());
		assertEquals(2, findRatingByRater(inboundSubjectRatings, chloe.getAddress()).getRating());

		List<AccountRatingData> outboundSubjectRatings = this.accountRatingsResource.getAccountRatings(null,
				raterPublicKey58, AccountRatingCategory.SUBJECT.name(), null, null, null);
		assertEquals(2, outboundSubjectRatings.size());
		assertEquals(4, findRatingByTarget(outboundSubjectRatings, bob.getAddress()).getRating());
		assertEquals(1, findRatingByTarget(outboundSubjectRatings, chloe.getAddress()).getRating());

		List<AccountRatingData> allSubjectRatings = this.accountRatingsResource.getAccountRatings(null, null,
				AccountRatingCategory.SUBJECT.name(), null, null, null);
		List<AccountRatingData> pagedSubjectRatings = this.accountRatingsResource.getAccountRatings(null, null,
				AccountRatingCategory.SUBJECT.name(), 2, 1, null);
		assertEquals(4, allSubjectRatings.size());
		assertEquals(2, pagedSubjectRatings.size());
		assertSameRating(allSubjectRatings.get(1), pagedSubjectRatings.get(0));
		assertSameRating(allSubjectRatings.get(2), pagedSubjectRatings.get(1));

		AccountRatingSummaryData subjectSummary = this.accountRatingsResource.getAccountRatingSummary(targetPublicKey58,
				AccountRatingCategory.SUBJECT.name());
		AccountRatingSummaryData playerSummary = this.accountRatingsResource.getAccountRatingSummary(targetPublicKey58,
				AccountRatingCategory.PLAYER.name());
		assertEquals(2, subjectSummary.getPositiveRatingCount());
		assertEquals(0, subjectSummary.getNegativeRatingCount());
		assertEquals(0, playerSummary.getPositiveRatingCount());
		assertEquals(1, playerSummary.getNegativeRatingCount());
	}

	@Test
	public void testAuraStyleTrustPreviewUsesMintingGroupSeed() throws DataException {
		TestAccount alice;
		TestAccount bob;
		TestAccount chloe;
		TestAccount dilbert;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			chloe = Common.getTestAccount(repository, "chloe");
			dilbert = Common.getTestAccount(repository, "dilbert");

			createAuraTrustGraph(repository, alice, bob, chloe, dilbert);
		}

		AccountTrustPreviewData bobPreview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(bob.getPublicKey()));
		AccountTrustPreviewData chloePreview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(chloe.getPublicKey()));
		AccountTrustPreviewData dilbertPreview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(dilbert.getPublicKey()));
		AccountTrustPreviewData alicePreview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(alice.getPublicKey()));

		AccountTrustPreviewData.CategoryTrust bobManager = findCategory(bobPreview, AccountRatingCategory.MANAGER);
		assertEquals(1_000_000L, bobManager.getScore());
		assertEquals(200_000L, bobManager.getLevelScore());
		assertEquals(100_000L, bobManager.getLevelScoreCap());
		assertEquals(2, bobManager.getLevel());

		AccountTrustPreviewData.CategoryTrust chloeTrainer = findCategory(chloePreview, AccountRatingCategory.TRAINER);
		assertEquals(8_000_000L, chloeTrainer.getScore());
		assertEquals(1_000_000L, chloeTrainer.getLevelScore());
		assertEquals(500_000L, chloeTrainer.getLevelScoreCap());
		assertEquals(2, chloeTrainer.getLevel());

		AccountTrustPreviewData.CategoryTrust dilbertPlayer = findCategory(dilbertPreview, AccountRatingCategory.PLAYER);
		assertEquals(32_000_000L, dilbertPlayer.getScore());
		assertEquals(3_000_000L, dilbertPlayer.getLevelScore());
		assertEquals(1_500_000L, dilbertPlayer.getLevelScoreCap());
		assertEquals(3, dilbertPlayer.getLevel());

		AccountTrustPreviewData.CategoryTrust aliceSubject = findCategory(alicePreview, AccountRatingCategory.SUBJECT);
		assertTrue(alicePreview.isMintingSeedMember());
		assertEquals(96_000_000L, aliceSubject.getScore());
		assertEquals(50_000_000L, aliceSubject.getLevelScore());
		assertEquals(25_000_000L, aliceSubject.getLevelScoreCap());
		assertEquals(2, aliceSubject.getLevel());
		assertEquals(AccountTrustStatus.SILVER, alicePreview.getTrustStatus());

		AccountTrustPreviewData.CategoryImpact subjectImpact = aliceSubject.getImpacts().get(0);
		assertEquals(dilbert.getAddress(), subjectImpact.getRaterAddress());
		assertEquals(3, subjectImpact.getEvaluatorLevel());
		assertEquals(32_000_000L, subjectImpact.getEvaluatorScore());
		assertEquals(2, subjectImpact.getRating());
		assertEquals(64_000_000L, subjectImpact.getImpact());

		try (final Repository repository = RepositoryManager.getRepository()) {
			saveAccountRating(repository, dilbert, alice, AccountRatingCategory.SUBJECT, -1);
			refreshTrustSnapshots(repository);
		}

		AccountTrustPreviewData negativePreview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(alice.getPublicKey()));
		AccountTrustPreviewData.CategoryTrust negativeSubject = findCategory(negativePreview, AccountRatingCategory.SUBJECT);
		assertEquals(-96_000_000L, negativeSubject.getScore());
		assertEquals(0L, negativeSubject.getLevelScore());
		assertEquals(5_000_000L, negativeSubject.getLevelScoreCap());
		assertEquals(0, negativeSubject.getLevel());
		assertEquals(AccountTrustStatus.UNVERIFIED, negativePreview.getTrustStatus());
		assertEquals(-128_000_000L, negativeSubject.getImpacts().get(0).getImpact());
	}

	@Test
	public void testTrustExplanationEndpointExplainsStoredAndLiveDerivation() throws DataException {
		TestAccount alice;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			createAuraTrustGraph(repository, alice, bob, chloe, dilbert);
		}

		String alicePublicKey58 = Base58.encode(alice.getPublicKey());
		AccountTrustExplanationData storedExplanation = this.accountRatingsResource
				.getAccountTrustExplanation(alicePublicKey58, null);

		assertFalse(storedExplanation.isLive());
		assertEquals(alice.getAddress(), storedExplanation.getTargetAddress());
		assertEquals(AccountTrustStatus.SILVER, storedExplanation.getTrustStatus());
		assertEquals(50, storedExplanation.getTrustWeightPercent());
		assertEquals(AccountRatingCategory.SUBJECT, storedExplanation.getActiveWeightCategory());
		assertTrue(storedExplanation.isMintingSeedMember());
		assertNotNull(storedExplanation.getSnapshotHeight());
		assertNotNull(storedExplanation.getSnapshotTimestamp());

		AccountTrustExplanationData.CategoryExplanation storedSubject = findCategory(storedExplanation,
				AccountRatingCategory.SUBJECT);
		assertEquals(96_000_000L, storedSubject.getScore());
		assertEquals(50_000_000L, storedSubject.getLevelScore());
		assertEquals(25_000_000L, storedSubject.getLevelScoreCap());
		assertEquals(2, storedSubject.getLevel());
		assertEquals(AccountTrustStatus.SILVER, storedSubject.getMappedTrustStatus());
		assertEquals(4, storedSubject.getConfiguredLevels().size());
		assertEquals(50_000_000L, findConfiguredLevel(storedSubject, 2).getThreshold());
		assertEquals(25_000_000L, findConfiguredLevel(storedSubject, 2).getLevelScoreCap());
		assertTrue(findRequirement(storedSubject, "level.2.threshold").isPassed());
		assertTrue(findRequirement(storedSubject, "level.2.positive-support").isPassed());
		assertFalse(findRequirement(storedSubject, "level.3.threshold").isPassed());
		assertFalse(storedSubject.getTopPositiveImpacts().isEmpty());
		assertTrue(storedSubject.getTopNegativeImpacts().isEmpty());

		AccountTrustExplanationData liveExplanation = this.accountRatingsResource
				.getAccountTrustExplanation(alicePublicKey58, true);
		AccountTrustExplanationData.CategoryExplanation liveSubject = findCategory(liveExplanation,
				AccountRatingCategory.SUBJECT);

		assertTrue(liveExplanation.isLive());
		assertNull(liveExplanation.getSnapshotHeight());
		assertNull(liveExplanation.getSnapshotTimestamp());
		assertEquals(AccountTrustStatus.SILVER, liveExplanation.getTrustStatus());
		assertEquals(96_000_000L, liveSubject.getScore());
		assertFalse(liveSubject.getTopPositiveImpacts().isEmpty());
	}

	@Test
	public void testTrustExplanationShowsSingleNegativeButNotSuspicious() throws DataException {
		TestAccount alice;
		TestAccount bob;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, alice, bob);
			refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository, ratingData(bob, alice, AccountRatingCategory.SUBJECT, -4), bob);
		}

		AccountTrustExplanationData explanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(alice.getPublicKey()), null);
		AccountTrustExplanationData.CategoryExplanation subject = findCategory(explanation, AccountRatingCategory.SUBJECT);

		assertEquals(AccountTrustStatus.UNVERIFIED, explanation.getTrustStatus());
		assertEquals(-512_000_000L, subject.getScore());
		assertEquals(-5_000_000L, subject.getLevelScore());
		assertEquals(5_000_000L, subject.getLevelScoreCap());
		assertEquals(0, subject.getLevel());
		assertEquals(AccountTrustStatus.UNVERIFIED, subject.getMappedTrustStatus());
		assertEquals(1, subject.getTopNegativeImpacts().size());
		assertEquals(bob.getAddress(), subject.getTopNegativeImpacts().get(0).getRaterAddress());
		assertEquals(-512_000_000L, subject.getTopNegativeImpacts().get(0).getImpact());

		AccountTrustExplanationData.Requirement suspiciousThreshold = findRequirement(subject, "suspicious.threshold");
		assertFalse(suspiciousThreshold.isPassed());
		assertEquals("-5000000", suspiciousThreshold.getActual());
		assertEquals("-10000000", suspiciousThreshold.getRequired());

		AccountTrustExplanationData.Requirement suspiciousRaters = findRequirement(subject, "suspicious.independent-raters");
		assertFalse(suspiciousRaters.isPassed());
		assertEquals("1", suspiciousRaters.getActual());
		assertEquals("2", suspiciousRaters.getRequired());
	}

	@Test
	public void testTrustExplanationShowsIndependentNegativeRatingsMakeSuspicious() throws DataException {
		TestAccount alice;
		TestAccount bob;
		TestAccount dilbert;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			dilbert = Common.getTestAccount(repository, "dilbert");

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, dilbert);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, alice, bob);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, alice, dilbert);
			refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository, ratingData(bob, alice, AccountRatingCategory.SUBJECT, -2), bob);
			TransactionUtils.signAndMint(repository, ratingData(dilbert, alice, AccountRatingCategory.SUBJECT, -2),
					dilbert);
		}

		AccountTrustExplanationData explanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(alice.getPublicKey()), null);
		AccountTrustExplanationData.CategoryExplanation subject = findCategory(explanation, AccountRatingCategory.SUBJECT);

		assertEquals(AccountTrustStatus.SUSPICIOUS, explanation.getTrustStatus());
		assertEquals(-256_000_000L, subject.getScore());
		assertEquals(-10_000_000L, subject.getLevelScore());
		assertEquals(5_000_000L, subject.getLevelScoreCap());
		assertEquals(-1, subject.getLevel());
		assertEquals(AccountTrustStatus.SUSPICIOUS, subject.getMappedTrustStatus());
		assertEquals(2, subject.getTopNegativeImpacts().size());

		AccountTrustExplanationData.Requirement suspiciousThreshold = findRequirement(subject, "suspicious.threshold");
		assertTrue(suspiciousThreshold.isPassed());
		assertEquals("-10000000", suspiciousThreshold.getActual());
		assertEquals("-10000000", suspiciousThreshold.getRequired());

		AccountTrustExplanationData.Requirement suspiciousRaters = findRequirement(subject, "suspicious.independent-raters");
		assertTrue(suspiciousRaters.isPassed());
		assertEquals("2", suspiciousRaters.getActual());
		assertEquals("2", suspiciousRaters.getRequired());
	}

	@Test
	public void testManagerEnergySplitsAcrossPositiveManagerPaths() throws DataException {
		TestAccount bob;
		TestAccount chloe;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			chloe = Common.getTestAccount(repository, "chloe");

			PrivateKeyAccount branchA1 = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount branchA2 = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount branchA3 = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount evaluatorA = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount branchB1 = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount branchB2 = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount branchB3 = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount evaluatorB = Common.generateRandomSeedAccount(repository);
			List<PrivateKeyAccount> generatedAccounts = Arrays.asList(branchA1, branchA2, branchA3, evaluatorA,
					branchB1, branchB2, branchB3, evaluatorB);

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, chloe);
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
			saveAccountRating(repository, evaluatorA, bob, AccountRatingCategory.MANAGER, 1);
			saveAccountRating(repository, evaluatorB, chloe, AccountRatingCategory.MANAGER, 1);
			refreshTrustSnapshots(repository);
		}

		AccountTrustPreviewData bobPreview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(bob.getPublicKey()));
		AccountTrustPreviewData chloePreview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(chloe.getPublicKey()));

		AccountTrustPreviewData.CategoryTrust bobManager = findCategory(bobPreview, AccountRatingCategory.MANAGER);
		AccountTrustPreviewData.CategoryTrust chloeManager = findCategory(chloePreview, AccountRatingCategory.MANAGER);

		assertEquals(250_000L, bobManager.getScore());
		assertEquals(250_000L, bobManager.getImpacts().get(0).getImpact());
		assertEquals(750_000L, chloeManager.getScore());
		assertEquals(750_000L, chloeManager.getImpacts().get(0).getImpact());
	}

	@Test
	public void testTrustDerivationListingReturnsGraphAndSeedMembers() throws DataException {
		TestAccount alice;
		TestAccount bob;
		TestAccount chloe;
		TestAccount dilbert;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			chloe = Common.getTestAccount(repository, "chloe");
			dilbert = Common.getTestAccount(repository, "dilbert");

			createAuraTrustGraph(repository, alice, bob, chloe, dilbert);
		}

		List<AccountTrustDerivationData> derivedAccounts = this.accountRatingsResource.getAccountTrustDerivation(
				null, null, null, null, null, null, null);
		AccountTrustDerivationData aliceDerivation = findDerivation(derivedAccounts, alice.getAddress());
		AccountTrustDerivationData bobDerivation = findDerivation(derivedAccounts, bob.getAddress());
		AccountTrustDerivationData chloeDerivation = findDerivation(derivedAccounts, chloe.getAddress());
		AccountTrustDerivationData dilbertDerivation = findDerivation(derivedAccounts, dilbert.getAddress());

		assertTrue(aliceDerivation.isMintingSeedMember());
		assertFalse(aliceDerivation.isLive());
		assertNotNull(aliceDerivation.getSnapshotHeight());
		assertNotNull(aliceDerivation.getSnapshotTimestamp());
		assertEquals(AccountTrustStatus.SILVER, aliceDerivation.getDerivedTrustStatus());
		assertEquals(50, aliceDerivation.getDerivedTrustWeightPercent());
		AccountTrustPreviewData.CategoryTrust storedSubject = findCategory(aliceDerivation, AccountRatingCategory.SUBJECT);
		assertEquals(96_000_000L, storedSubject.getScore());
		assertEquals(50_000_000L, storedSubject.getLevelScore());
		assertEquals(25_000_000L, storedSubject.getLevelScoreCap());
		assertTrue(storedSubject.getImpacts().isEmpty());
		assertEquals(1_000_000L, findCategory(bobDerivation, AccountRatingCategory.MANAGER).getScore());
		assertEquals(8_000_000L, findCategory(chloeDerivation, AccountRatingCategory.TRAINER).getScore());
		assertEquals(32_000_000L, findCategory(dilbertDerivation, AccountRatingCategory.PLAYER).getScore());

		List<AccountTrustDerivationData> liveAccounts = this.accountRatingsResource.getAccountTrustDerivation(
				null, null, null, null, null, null, null, true);
		AccountTrustDerivationData liveAliceDerivation = findDerivation(liveAccounts, alice.getAddress());
		assertTrue(liveAliceDerivation.isLive());
		assertNull(liveAliceDerivation.getSnapshotHeight());
		assertNull(liveAliceDerivation.getSnapshotTimestamp());
		assertFalse(findCategory(liveAliceDerivation, AccountRatingCategory.SUBJECT).getImpacts().isEmpty());
	}

	@Test
	public void testTrustDerivationListingFiltersAndPages() throws DataException {
		TestAccount alice;
		TestAccount bob;
		TestAccount chloe;
		TestAccount dilbert;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			chloe = Common.getTestAccount(repository, "chloe");
			dilbert = Common.getTestAccount(repository, "dilbert");

			createAuraTrustGraph(repository, alice, bob, chloe, dilbert);
		}

		List<AccountTrustDerivationData> silverAccounts = this.accountRatingsResource.getAccountTrustDerivation(
				AccountTrustStatus.SILVER.name(), null, null, null, null, null, null);
		assertEquals(1, silverAccounts.size());
		assertEquals(alice.getAddress(), silverAccounts.get(0).getAccountAddress());

		List<AccountTrustDerivationData> seedAccounts = this.accountRatingsResource.getAccountTrustDerivation(
				null, null, true, null, null, null, null);
		assertEquals(1, seedAccounts.size());
		assertEquals(alice.getAddress(), seedAccounts.get(0).getAccountAddress());

		List<AccountTrustDerivationData> managerAccounts = this.accountRatingsResource.getAccountTrustDerivation(
				null, AccountRatingCategory.MANAGER.name(), null, 2, null, null, null);
		assertEquals(2, managerAccounts.size());
		findDerivation(managerAccounts, bob.getAddress());

		List<AccountTrustDerivationData> trainerAccounts = this.accountRatingsResource.getAccountTrustDerivation(
				null, AccountRatingCategory.TRAINER.name(), null, 2, null, null, null);
		assertEquals(2, trainerAccounts.size());
		findDerivation(trainerAccounts, chloe.getAddress());

		List<AccountTrustDerivationData> playerAccounts = this.accountRatingsResource.getAccountTrustDerivation(
				null, AccountRatingCategory.PLAYER.name(), null, 3, null, null, null);
		assertEquals(1, playerAccounts.size());
		assertEquals(dilbert.getAddress(), playerAccounts.get(0).getAccountAddress());

		List<AccountTrustDerivationData> fullList = this.accountRatingsResource.getAccountTrustDerivation(
				null, null, null, null, null, null, null);
		List<AccountTrustDerivationData> pagedList = this.accountRatingsResource.getAccountTrustDerivation(
				null, null, null, null, 2, 1, null);
		assertEquals(2, pagedList.size());
		assertEquals(fullList.get(1).getAccountAddress(), pagedList.get(0).getAccountAddress());

		List<AccountTrustDerivationData> reversedList = this.accountRatingsResource.getAccountTrustDerivation(
				null, null, null, null, 1, null, true);
		assertEquals(1, reversedList.size());
		assertEquals(fullList.get(fullList.size() - 1).getAccountAddress(), reversedList.get(0).getAccountAddress());
	}

	@Test
	public void testTrustSnapshotEndpointReturnsAndFiltersStoredRows() throws DataException {
		TestAccount alice;
		TestAccount bob;
		TestAccount chloe;
		TestAccount dilbert;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			chloe = Common.getTestAccount(repository, "chloe");
			dilbert = Common.getTestAccount(repository, "dilbert");

			processAuraTrustGraph(repository, alice, bob, chloe, dilbert);
		}

		List<AccountTrustSnapshotData> allSnapshots = this.accountRatingsResource.getAccountTrustSnapshots(
				null, null, null, null, null, null, null, null);
		assertEquals(60, allSnapshots.size());

		List<AccountTrustSnapshotData> aliceSnapshots = this.accountRatingsResource.getAccountTrustSnapshots(
				alice.getAddress(), null, null, null, null, null, null, null);
		assertEquals(4, aliceSnapshots.size());

		List<AccountTrustSnapshotData> silverSubjectSnapshots = this.accountRatingsResource.getAccountTrustSnapshots(
				null, AccountRatingCategory.SUBJECT.name(), AccountTrustStatus.SILVER.name(), null, null, null, null, null);
		assertEquals(1, silverSubjectSnapshots.size());
		AccountTrustSnapshotData aliceSubject = silverSubjectSnapshots.get(0);
		assertEquals(alice.getAddress(), aliceSubject.getAccountAddress());
		assertEquals(AccountRatingCategory.SUBJECT, aliceSubject.getCategory());
		assertEquals(96_000_000L, aliceSubject.getScore());
		assertEquals(50_000_000L, aliceSubject.getLevelScore());
		assertEquals(25_000_000L, aliceSubject.getLevelScoreCap());
		assertEquals(2, aliceSubject.getLevel());
		assertEquals(2, aliceSubject.getInboundRatings().getPositiveMediumCount());
		assertTrue(aliceSubject.isMintingSeedMember());
		assertNotNull(aliceSubject.getSnapshotHeight());

		List<AccountTrustSnapshotData> managerSnapshots = this.accountRatingsResource.getAccountTrustSnapshots(
				null, AccountRatingCategory.MANAGER.name(), null, null, 2, null, null, null);
		assertEquals(2, managerSnapshots.size());

		List<AccountTrustSnapshotData> seedSnapshots = this.accountRatingsResource.getAccountTrustSnapshots(
				null, null, null, true, null, null, null, null);
		assertEquals(4, seedSnapshots.size());

		List<AccountTrustSnapshotData> pagedSnapshots = this.accountRatingsResource.getAccountTrustSnapshots(
				null, null, null, null, null, 2, 1, null);
		assertEquals(2, pagedSnapshots.size());
		assertEquals(allSnapshots.get(1).getAccountAddress(), pagedSnapshots.get(0).getAccountAddress());

		List<AccountTrustSnapshotData> reversedSnapshots = this.accountRatingsResource.getAccountTrustSnapshots(
				null, null, null, null, null, 1, null, true);
		assertEquals(1, reversedSnapshots.size());
		assertEquals(allSnapshots.get(allSnapshots.size() - 1).getAccountAddress(), reversedSnapshots.get(0).getAccountAddress());
	}

	@Test
	public void testRateAccountEndpointBuildsUnsignedTransaction() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			String rawTransaction = this.accountRatingsResource.rateAccount(ratingData(alice, bob, -1));

			assertNotNull(rawTransaction);
			assertFalse(rawTransaction.isEmpty());

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, 4), alice);
			String removalTransaction = this.accountRatingsResource.rateAccount(ratingData(alice, bob, AccountRating.NO_RATING));

			assertNotNull(removalTransaction);
			assertFalse(removalTransaction.isEmpty());
		}
	}

	@Test
	public void testMissingTargetFailsSummary() {
		PrivateKeyAccount unknown = Common.generateRandomSeedAccount(null);

		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountRatingSummary(Base58.encode(unknown.getPublicKey())));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountTrustPreview(Base58.encode(unknown.getPublicKey())));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountTrustExplanation(Base58.encode(unknown.getPublicKey()), null));
	}

	@Test
	public void testInvalidPublicKeyFailsList() {
		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountRatings("not-a-public-key", null, null, null, null));
		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountTrustPreview("not-a-public-key"));
		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountTrustExplanation("not-a-public-key", null));
	}

	@Test
	public void testInvalidCategoryFailsList() {
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountRatings(null, null, "not-a-category", null, null, null));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountTrustDerivation(null, "not-a-category", null, null, null, null, null));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountTrustSnapshots(null, "not-a-category", null, null, null, null, null, null));
	}

	@Test
	public void testInvalidTrustStatusFailsDerivationList() {
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountTrustDerivation("not-a-status", null, null, null, null, null, null));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountTrustSnapshots(null, null, "not-a-status", null, null, null, null, null));
	}

	@Test
	public void testInvalidTrustSnapshotAddressFailsList() {
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountTrustSnapshots("not-an-address", null, null, null, null, null, null, null));
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target, int rating)
			throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), target.getPublicKey(), rating);
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), target.getPublicKey(), category, rating);
	}

	private AccountTrustPreviewData.EvaluatorImpact findEvaluatorImpact(AccountTrustPreviewData preview, String raterAddress) {
		return preview.getEvaluatorImpacts().stream()
				.filter(impact -> impact.getRaterAddress().equals(raterAddress))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing evaluator impact for " + raterAddress));
	}

	private AccountRatingData findRatingByRater(List<AccountRatingData> ratings, String raterAddress) {
		return ratings.stream()
				.filter(rating -> rating.getRaterAddress().equals(raterAddress))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing rating by " + raterAddress));
	}

	private AccountRatingData findRatingByTarget(List<AccountRatingData> ratings, String targetAddress) {
		return ratings.stream()
				.filter(rating -> rating.getTargetAddress().equals(targetAddress))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing rating for " + targetAddress));
	}

	private void assertSameRating(AccountRatingData expected, AccountRatingData actual) {
		assertEquals(expected.getTargetAddress(), actual.getTargetAddress());
		assertEquals(expected.getRaterAddress(), actual.getRaterAddress());
		assertEquals(expected.getCategory(), actual.getCategory());
		assertEquals(expected.getRating(), actual.getRating());
	}

	private AccountTrustPreviewData.CategoryTrust findCategory(AccountTrustPreviewData preview, AccountRatingCategory category) {
		return preview.getCategories().stream()
				.filter(categoryTrust -> categoryTrust.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing category " + category));
	}

	private AccountTrustPreviewData.CategoryTrust findCategory(AccountTrustDerivationData derivation,
			AccountRatingCategory category) {
		return derivation.getCategories().stream()
				.filter(categoryTrust -> categoryTrust.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing category " + category));
	}

	private AccountTrustExplanationData.CategoryExplanation findCategory(AccountTrustExplanationData explanation,
			AccountRatingCategory category) {
		return explanation.getCategories().stream()
				.filter(categoryExplanation -> categoryExplanation.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing category " + category));
	}

	private AccountTrustExplanationData.ConfiguredLevel findConfiguredLevel(
			AccountTrustExplanationData.CategoryExplanation category, int level) {
		return category.getConfiguredLevels().stream()
				.filter(configuredLevel -> configuredLevel.getLevel() == level)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing configured level " + level));
	}

	private AccountTrustExplanationData.Requirement findRequirement(
			AccountTrustExplanationData.CategoryExplanation category, String name) {
		return category.getRequirements().stream()
				.filter(requirement -> requirement.getName().equals(name))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing requirement " + name));
	}

	private AccountTrustDerivationData findDerivation(List<AccountTrustDerivationData> derivations, String accountAddress) {
		return derivations.stream()
				.filter(derivation -> derivation.getAccountAddress().equals(accountAddress))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing derivation for " + accountAddress));
	}

	private void createAuraTrustGraph(Repository repository, TestAccount alice, TestAccount bob, TestAccount chloe,
			TestAccount dilbert) throws DataException {
		AccountTrustTestUtils.createDerivedSilverSubjectSnapshot(repository, alice, bob, chloe, dilbert);
	}

	private void processAuraTrustGraph(Repository repository, TestAccount alice, TestAccount bob, TestAccount chloe,
			TestAccount dilbert) throws DataException {
		ensureKnownAccount(repository, alice);
		ensureKnownAccount(repository, bob);
		ensureKnownAccount(repository, chloe);
		ensureKnownAccount(repository, dilbert);
		repository.saveChanges();

		AccountTrustTestUtils.createDerivedSilverSubjectSnapshot(repository, alice, bob, chloe, dilbert);
	}

	private void saveAccountRating(Repository repository, PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		ensureKnownAccount(repository, rater);
		ensureKnownAccount(repository, target);
		repository.getAccountRatingRepository()
				.save(new AccountRatingData(target.getPublicKey(), rater.getPublicKey(), category, rating));
	}

	private void ensureKnownAccount(Repository repository, PrivateKeyAccount account) throws DataException {
		repository.getAccountRepository()
				.ensureAccount(new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, 0));
	}

	private void refreshTrustSnapshots(Repository repository) throws DataException {
		AccountTrustDerivation.refreshSnapshots(repository, repository.getBlockRepository().getBlockchainHeight() + 1,
				repository.getBlockRepository().getLastBlock().getTimestamp());
		repository.saveChanges();
	}

	private void saveSubjectSnapshots(Repository repository, AccountTrustDerivationData... derivationData)
			throws DataException {
		repository.getAccountRatingRepository().replaceTrustDerivationSnapshots(Arrays.asList(derivationData),
				repository.getBlockRepository().getBlockchainHeight(), repository.getBlockRepository().getLastBlock().getTimestamp());
		repository.saveChanges();
	}

	private AccountTrustDerivationData subjectDerivation(TestAccount account, AccountTrustStatus trustStatus)
			throws DataException {
		AccountTrustPreviewData.CategoryTrust subjectTrust = new AccountTrustPreviewData.CategoryTrust(
				AccountRatingCategory.SUBJECT, scoreForStatus(trustStatus), levelForStatus(trustStatus), trustStatus,
				new AccountTrustPreviewData.RatingCounts(), Collections.emptyList());
		return new AccountTrustDerivationData(account.getPublicKey(), account.getAddress(), trustStatus, true,
				Collections.singletonList(subjectTrust));
	}

	private long scoreForStatus(AccountTrustStatus trustStatus) {
		switch (trustStatus) {
			case GOLD:
				return 100_000_000L;
			case SILVER:
				return 50_000_000L;
			case BRONZE:
				return 10_000_000L;
			case SUSPICIOUS:
				return -1L;
			case UNVERIFIED:
			default:
				return 0L;
		}
	}

	private int levelForStatus(AccountTrustStatus trustStatus) {
		switch (trustStatus) {
			case GOLD:
				return 3;
			case SILVER:
				return 2;
			case BRONZE:
				return 1;
			case SUSPICIOUS:
				return -1;
			case UNVERIFIED:
			default:
				return 0;
		}
	}

	private void setVoteAccount(Repository repository, TestAccount account, int blocksMinted) throws DataException {
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
