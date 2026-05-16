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
import org.qortal.data.account.AccountTrustPolicyData;
import org.qortal.data.account.AccountTrustCategoryData;
import org.qortal.data.account.AccountTrustCategoryImpactData;
import org.qortal.data.account.AccountTrustRatingCountsData;
import org.qortal.data.account.AccountTrustProfileData;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.account.AccountTrustSummaryData;
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
	public void testTrustSummaryEndpointReturnsEmptyBuckets() {
		AccountTrustSummaryData summary = this.accountRatingsResource.getAccountTrustSummary();

		assertEquals(AccountRatingCategory.SUBJECT, summary.getActiveWeightCategory());
		assertNull(summary.getSnapshotHeight());
		assertNull(summary.getSnapshotTimestamp());
		assertEquals(0L, summary.getActiveSnapshotAccountCount());
		assertEquals(0L, summary.getActiveSeedMemberCount());
		assertEquals(0L, summary.getActiveMintingAllowedCount());
		assertEquals(0L, summary.getSuspiciousCount());
		assertEquals(0L, summary.getRawVoteWeight());
		assertEquals(0L, summary.getEffectiveVoteWeight());
		assertEquals(AccountTrustStatus.values().length, summary.getStatusSummaries().size());
		assertEquals(AccountRatingCategory.values().length, summary.getCategorySummaries().size());

		for (AccountTrustSummaryData.StatusSummary statusSummary : summary.getStatusSummaries()) {
			assertEquals(0L, statusSummary.getAccountCount());
			assertEquals(0L, statusSummary.getSeedMemberCount());
			assertEquals(0L, statusSummary.getRawVoteWeight());
			assertEquals(0L, statusSummary.getEffectiveVoteWeight());
			assertEquals(statusSummary.getStatus().getValue(), statusSummary.getStatusValue());
			assertEquals(statusSummary.getStatus().getVoteWeightPercent(), statusSummary.getVoteWeightPercent());
			assertEquals(statusSummary.getStatus().canMint(), statusSummary.isTrustAllowsMinting());
		}

		for (AccountTrustSummaryData.CategorySummary categorySummary : summary.getCategorySummaries()) {
			assertEquals(AccountTrustStatus.values().length, categorySummary.getStatusCounts().size());
			for (AccountTrustSummaryData.StatusCount statusCount : categorySummary.getStatusCounts()) {
				assertEquals(statusCount.getStatus().getValue(), statusCount.getStatusValue());
				assertEquals(0L, statusCount.getAccountCount());
			}
		}
	}

	@Test
	public void testTrustSummaryEndpointAggregatesStoredSnapshots() throws DataException {
		TestAccount alice;
		TestAccount bob;
		TestAccount chloe;
		TestAccount dilbert;
		PrivateKeyAccount erin;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			chloe = Common.getTestAccount(repository, "chloe");
			dilbert = Common.getTestAccount(repository, "dilbert");
			erin = Common.generateRandomSeedAccount(repository);

			setVoteAccount(repository, alice, 101);
			setVoteAccount(repository, bob, 101);
			setVoteAccount(repository, chloe, 101);
			setVoteAccount(repository, dilbert, 101);
			setVoteAccount(repository, erin, 101);

			repository.getAccountRatingRepository().replaceTrustDerivationSnapshots(Arrays.asList(
					trustDerivation(alice, true,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.GOLD),
							categoryTrust(AccountRatingCategory.MANAGER, AccountTrustStatus.GOLD)),
					trustDerivation(bob, true,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.SILVER),
							categoryTrust(AccountRatingCategory.PLAYER, AccountTrustStatus.SILVER)),
					trustDerivation(chloe, false,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.BRONZE),
							categoryTrust(AccountRatingCategory.TRAINER, AccountTrustStatus.BRONZE)),
					trustDerivation(dilbert, false,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.SUSPICIOUS)),
					trustDerivation(erin, false,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.UNVERIFIED),
							categoryTrust(AccountRatingCategory.MANAGER, AccountTrustStatus.SUSPICIOUS))),
					repository.getBlockRepository().getBlockchainHeight(),
					repository.getBlockRepository().getLastBlock().getTimestamp());
			repository.saveChanges();
		}

		AccountTrustSummaryData summary = this.accountRatingsResource.getAccountTrustSummary();

		assertEquals(AccountRatingCategory.SUBJECT, summary.getActiveWeightCategory());
		assertNotNull(summary.getSnapshotHeight());
		assertNotNull(summary.getSnapshotTimestamp());
		assertEquals(5L, summary.getActiveSnapshotAccountCount());
		assertEquals(2L, summary.getActiveSeedMemberCount());
		assertEquals(2L, summary.getActiveMintingAllowedCount());
		assertEquals(1L, summary.getSuspiciousCount());
		assertEquals(505L, summary.getRawVoteWeight());
		assertEquals(176L, summary.getEffectiveVoteWeight());

		AccountTrustSummaryData.StatusSummary gold = findStatusSummary(summary, AccountTrustStatus.GOLD);
		assertEquals(1L, gold.getAccountCount());
		assertEquals(1L, gold.getSeedMemberCount());
		assertEquals(101L, gold.getRawVoteWeight());
		assertEquals(101L, gold.getEffectiveVoteWeight());

		AccountTrustSummaryData.StatusSummary silver = findStatusSummary(summary, AccountTrustStatus.SILVER);
		assertEquals(1L, silver.getAccountCount());
		assertEquals(1L, silver.getSeedMemberCount());
		assertEquals(101L, silver.getRawVoteWeight());
		assertEquals(50L, silver.getEffectiveVoteWeight());

		AccountTrustSummaryData.StatusSummary bronze = findStatusSummary(summary, AccountTrustStatus.BRONZE);
		assertEquals(1L, bronze.getAccountCount());
		assertEquals(0L, bronze.getSeedMemberCount());
		assertEquals(25L, bronze.getEffectiveVoteWeight());

		AccountTrustSummaryData.StatusSummary suspicious = findStatusSummary(summary, AccountTrustStatus.SUSPICIOUS);
		assertEquals(1L, suspicious.getAccountCount());
		assertEquals(0L, suspicious.getSeedMemberCount());
		assertEquals(0L, suspicious.getEffectiveVoteWeight());
		assertFalse(suspicious.isTrustAllowsMinting());

		AccountTrustSummaryData.StatusSummary unverified = findStatusSummary(summary, AccountTrustStatus.UNVERIFIED);
		assertEquals(1L, unverified.getAccountCount());
		assertEquals(101L, unverified.getRawVoteWeight());
		assertEquals(0L, unverified.getEffectiveVoteWeight());
		assertTrue(unverified.isTrustAllowsMinting());

		AccountTrustSummaryData.CategorySummary subjectSummary = findCategorySummary(summary, AccountRatingCategory.SUBJECT);
		assertEquals(1L, findStatusCount(subjectSummary, AccountTrustStatus.GOLD).getAccountCount());
		assertEquals(1L, findStatusCount(subjectSummary, AccountTrustStatus.SILVER).getAccountCount());
		assertEquals(1L, findStatusCount(subjectSummary, AccountTrustStatus.BRONZE).getAccountCount());
		assertEquals(1L, findStatusCount(subjectSummary, AccountTrustStatus.UNVERIFIED).getAccountCount());
		assertEquals(1L, findStatusCount(subjectSummary, AccountTrustStatus.SUSPICIOUS).getAccountCount());

		AccountTrustSummaryData.CategorySummary managerSummary = findCategorySummary(summary, AccountRatingCategory.MANAGER);
		assertEquals(1L, findStatusCount(managerSummary, AccountTrustStatus.GOLD).getAccountCount());
		assertEquals(1L, findStatusCount(managerSummary, AccountTrustStatus.SUSPICIOUS).getAccountCount());
		assertEquals(0L, findStatusCount(managerSummary, AccountTrustStatus.SILVER).getAccountCount());

		AccountTrustSummaryData.CategorySummary playerSummary = findCategorySummary(summary, AccountRatingCategory.PLAYER);
		assertEquals(1L, findStatusCount(playerSummary, AccountTrustStatus.SILVER).getAccountCount());

		AccountTrustSummaryData.CategorySummary trainerSummary = findCategorySummary(summary, AccountRatingCategory.TRAINER);
		assertEquals(1L, findStatusCount(trainerSummary, AccountTrustStatus.BRONZE).getAccountCount());
	}

	@Test
	public void testTrustPolicyEndpointReturnsConfiguredPolicy() {
		AccountTrustPolicyData policy = this.accountRatingsResource.getAccountTrustPolicy();

		assertEquals(AccountRatingCategory.SUBJECT, policy.getActiveWeightCategory());
		assertEquals(1_000_000L, policy.getStartingEnergy());
		assertEquals(4, policy.getManagerEnergyHops());
		assertEquals(2, policy.getSuspiciousMinRaterCount());
		assertEquals(2, policy.getSuspiciousMinRatingConfidence());
		assertEquals(AccountTrustStatus.values().length, policy.getStatusVoteWeights().size());
		assertEquals(AccountRatingCategory.values().length, policy.getCategoryPolicies().size());

		AccountTrustPolicyData.StatusVoteWeight gold = findStatusVoteWeight(policy, AccountTrustStatus.GOLD);
		assertEquals(AccountTrustStatus.GOLD.getValue(), gold.getStatusValue());
		assertEquals(100, gold.getVoteWeightPercent());
		assertTrue(gold.isTrustAllowsMinting());

		AccountTrustPolicyData.StatusVoteWeight suspicious = findStatusVoteWeight(policy, AccountTrustStatus.SUSPICIOUS);
		assertEquals(AccountTrustStatus.SUSPICIOUS.getValue(), suspicious.getStatusValue());
		assertEquals(0, suspicious.getVoteWeightPercent());
		assertFalse(suspicious.isTrustAllowsMinting());

		AccountTrustPolicyData.CategoryPolicy subject = findCategoryPolicy(policy, AccountRatingCategory.SUBJECT);
		assertEquals(-10_000_000L, subject.getSuspiciousThreshold());
		assertEquals(5_000_000L, subject.getSuspiciousLevelScoreCap());
		assertEquals(4, subject.getLevels().size());

		AccountTrustPolicyData.LevelPolicy subjectLevelTwo = findLevelPolicy(subject, 2);
		assertEquals(AccountTrustStatus.SILVER, subjectLevelTwo.getMappedTrustStatus());
		assertEquals(AccountTrustStatus.SILVER.getValue(), subjectLevelTwo.getMappedTrustStatusValue());
		assertEquals(50, subjectLevelTwo.getMappedTrustWeightPercent());
		assertEquals(50_000_000L, subjectLevelTwo.getThreshold());
		assertEquals(25_000_000L, subjectLevelTwo.getLevelScoreCap());

		AccountTrustPolicyData.CategoryPolicy manager = findCategoryPolicy(policy, AccountRatingCategory.MANAGER);
		assertEquals(-1_000L, manager.getSuspiciousThreshold());
		assertEquals(500L, manager.getSuspiciousLevelScoreCap());
		assertEquals(2, manager.getLevels().size());
		assertEquals(200_000L, findLevelPolicy(manager, 2).getThreshold());
		assertEquals(100_000L, findLevelPolicy(manager, 2).getLevelScoreCap());
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
	public void testTrustProfileReturnsEmptyKnownAccountProfile() throws DataException {
		TestAccount bob;

		try (final Repository repository = RepositoryManager.getRepository()) {
			bob = Common.getTestAccount(repository, "bob");
			ensureKnownAccount(repository, bob);
			repository.saveChanges();
		}

		AccountTrustProfileData profile = this.accountRatingsResource.getAccountTrustProfile(Base58.encode(bob.getPublicKey()));

		assertEquals(bob.getAddress(), profile.getTargetAddress());
		assertEquals(AccountTrustStatus.UNVERIFIED, profile.getTrustStatus());
		assertEquals(AccountTrustStatus.UNVERIFIED.getValue(), profile.getTrustStatusValue());
		assertEquals(0, profile.getTrustWeightPercent());
		assertTrue(profile.isTrustAllowsMinting());
		assertEquals(AccountRatingCategory.SUBJECT, profile.getActiveWeightCategory());
		assertFalse(profile.isMintingSeedMember());
		assertNull(profile.getSnapshotHeight());
		assertNull(profile.getSnapshotTimestamp());
		assertEquals(AccountRatingCategory.values().length, profile.getCategories().size());

		for (AccountTrustProfileData.CategoryProfile category : profile.getCategories()) {
			assertEquals(0L, category.getScore());
			assertEquals(0L, category.getLevelScore());
			assertEquals(0L, category.getLevelScoreCap());
			assertEquals(0, category.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, category.getMappedTrustStatus());
			assertEquals(AccountTrustStatus.UNVERIFIED.getValue(), category.getMappedTrustStatusValue());
			assertEquals(0, category.getMappedTrustWeightPercent());
			assertEquals(0, category.getInboundRatings().getTotalRatingCount());
			assertEquals(0, category.getOutboundRatings().getTotalRatingCount());
			assertNull(category.getSnapshotHeight());
			assertNull(category.getSnapshotTimestamp());
		}
	}

	@Test
	public void testTrustProfileReturnsStoredSnapshotsAndCategoryCounts() throws DataException {
		TestAccount bob;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			AccountTrustRatingCountsData subjectInboundCounts = new AccountTrustRatingCountsData();
			subjectInboundCounts.addRating(4);
			subjectInboundCounts.addRating(-2);

			saveAccountRating(repository, alice, bob, AccountRatingCategory.SUBJECT, 4);
			saveAccountRating(repository, chloe, bob, AccountRatingCategory.SUBJECT, -2);
			saveAccountRating(repository, bob, alice, AccountRatingCategory.PLAYER, 3);
			saveAccountRating(repository, bob, chloe, AccountRatingCategory.MANAGER, -1);
			saveAccountRating(repository, bob, dilbert, AccountRatingCategory.SUBJECT, 1);
			saveSubjectSnapshots(repository, subjectDerivation(bob, AccountTrustStatus.BRONZE, subjectInboundCounts));
		}

		AccountTrustProfileData profile = this.accountRatingsResource.getAccountTrustProfile(Base58.encode(bob.getPublicKey()));

		assertEquals(bob.getAddress(), profile.getTargetAddress());
		assertEquals(AccountTrustStatus.BRONZE, profile.getTrustStatus());
		assertEquals(AccountTrustStatus.BRONZE.getValue(), profile.getTrustStatusValue());
		assertEquals(25, profile.getTrustWeightPercent());
		assertTrue(profile.isTrustAllowsMinting());
		assertEquals(AccountRatingCategory.SUBJECT, profile.getActiveWeightCategory());
		assertTrue(profile.isMintingSeedMember());
		assertNotNull(profile.getSnapshotHeight());
		assertNotNull(profile.getSnapshotTimestamp());
		assertEquals(AccountRatingCategory.values().length, profile.getCategories().size());

		AccountTrustProfileData.CategoryProfile subject = findCategory(profile, AccountRatingCategory.SUBJECT);
		assertEquals(10_000_000L, subject.getScore());
		assertEquals(10_000_000L, subject.getLevelScore());
		assertEquals(0L, subject.getLevelScoreCap());
		assertEquals(1, subject.getLevel());
		assertEquals(AccountTrustStatus.BRONZE, subject.getMappedTrustStatus());
		assertEquals(25, subject.getMappedTrustWeightPercent());
		assertEquals(1, subject.getInboundRatings().getPositiveVeryHighCount());
		assertEquals(1, subject.getInboundRatings().getNegativeMediumCount());
		assertEquals(1, subject.getOutboundRatings().getPositiveLowCount());
		assertNotNull(subject.getSnapshotHeight());
		assertNotNull(subject.getSnapshotTimestamp());

		AccountTrustProfileData.CategoryProfile player = findCategory(profile, AccountRatingCategory.PLAYER);
		assertEquals(0L, player.getScore());
		assertEquals(AccountTrustStatus.UNVERIFIED, player.getMappedTrustStatus());
		assertEquals(0, player.getInboundRatings().getTotalRatingCount());
		assertEquals(1, player.getOutboundRatings().getPositiveHighCount());
		assertNull(player.getSnapshotHeight());
		assertNull(player.getSnapshotTimestamp());

		AccountTrustProfileData.CategoryProfile manager = findCategory(profile, AccountRatingCategory.MANAGER);
		assertEquals(0L, manager.getScore());
		assertEquals(AccountTrustStatus.UNVERIFIED, manager.getMappedTrustStatus());
		assertEquals(0, manager.getInboundRatings().getTotalRatingCount());
		assertEquals(1, manager.getOutboundRatings().getNegativeLowCount());
		assertNull(manager.getSnapshotHeight());
		assertNull(manager.getSnapshotTimestamp());
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
	public void testTrustExplanationUsesMintingGroupSeed() throws DataException {
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

		AccountTrustExplanationData bobExplanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(bob.getPublicKey()), true);
		AccountTrustExplanationData chloeExplanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(chloe.getPublicKey()), true);
		AccountTrustExplanationData dilbertExplanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(dilbert.getPublicKey()), true);
		AccountTrustExplanationData aliceExplanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(alice.getPublicKey()), true);

		AccountTrustExplanationData.CategoryExplanation bobManager = findCategory(bobExplanation, AccountRatingCategory.MANAGER);
		assertEquals(1_000_000L, bobManager.getScore());
		assertEquals(200_000L, bobManager.getLevelScore());
		assertEquals(100_000L, bobManager.getLevelScoreCap());
		assertEquals(2, bobManager.getLevel());

		AccountTrustExplanationData.CategoryExplanation chloeTrainer = findCategory(chloeExplanation,
				AccountRatingCategory.TRAINER);
		assertEquals(8_000_000L, chloeTrainer.getScore());
		assertEquals(1_000_000L, chloeTrainer.getLevelScore());
		assertEquals(500_000L, chloeTrainer.getLevelScoreCap());
		assertEquals(2, chloeTrainer.getLevel());

		AccountTrustExplanationData.CategoryExplanation dilbertPlayer = findCategory(dilbertExplanation,
				AccountRatingCategory.PLAYER);
		assertEquals(32_000_000L, dilbertPlayer.getScore());
		assertEquals(3_000_000L, dilbertPlayer.getLevelScore());
		assertEquals(1_500_000L, dilbertPlayer.getLevelScoreCap());
		assertEquals(3, dilbertPlayer.getLevel());

		AccountTrustExplanationData.CategoryExplanation aliceSubject = findCategory(aliceExplanation,
				AccountRatingCategory.SUBJECT);
		assertTrue(aliceExplanation.isMintingSeedMember());
		assertEquals(96_000_000L, aliceSubject.getScore());
		assertEquals(50_000_000L, aliceSubject.getLevelScore());
		assertEquals(25_000_000L, aliceSubject.getLevelScoreCap());
		assertEquals(2, aliceSubject.getLevel());
		assertEquals(AccountTrustStatus.SILVER, aliceExplanation.getTrustStatus());

		AccountTrustCategoryImpactData subjectImpact = aliceSubject.getTopPositiveImpacts().get(0);
		assertEquals(dilbert.getAddress(), subjectImpact.getRaterAddress());
		assertEquals(3, subjectImpact.getEvaluatorLevel());
		assertEquals(32_000_000L, subjectImpact.getEvaluatorScore());
		assertEquals(2, subjectImpact.getRating());
		assertEquals(64_000_000L, subjectImpact.getImpact());

		try (final Repository repository = RepositoryManager.getRepository()) {
			saveAccountRating(repository, dilbert, alice, AccountRatingCategory.SUBJECT, -1);
			refreshTrustSnapshots(repository);
		}

		AccountTrustExplanationData negativeExplanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(alice.getPublicKey()), true);
		AccountTrustExplanationData.CategoryExplanation negativeSubject = findCategory(negativeExplanation,
				AccountRatingCategory.SUBJECT);
		assertEquals(-96_000_000L, negativeSubject.getScore());
		assertEquals(0L, negativeSubject.getLevelScore());
		assertEquals(5_000_000L, negativeSubject.getLevelScoreCap());
		assertEquals(0, negativeSubject.getLevel());
		assertEquals(AccountTrustStatus.UNVERIFIED, negativeExplanation.getTrustStatus());
		assertEquals(-128_000_000L, negativeSubject.getTopNegativeImpacts().get(0).getImpact());
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

		AccountTrustExplanationData bobExplanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(bob.getPublicKey()), true);
		AccountTrustExplanationData chloeExplanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(chloe.getPublicKey()), true);

		AccountTrustExplanationData.CategoryExplanation bobManager = findCategory(bobExplanation,
				AccountRatingCategory.MANAGER);
		AccountTrustExplanationData.CategoryExplanation chloeManager = findCategory(chloeExplanation,
				AccountRatingCategory.MANAGER);

		assertEquals(250_000L, bobManager.getScore());
		assertEquals(250_000L, bobManager.getTopPositiveImpacts().get(0).getImpact());
		assertEquals(750_000L, chloeManager.getScore());
		assertEquals(750_000L, chloeManager.getTopPositiveImpacts().get(0).getImpact());
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
		AccountTrustCategoryData storedSubject = findCategory(aliceDerivation, AccountRatingCategory.SUBJECT);
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
		assertEquals(4, pagedList.get(0).getCategories().size());
		findCategory(pagedList.get(0), AccountRatingCategory.SUBJECT);
		findCategory(pagedList.get(0), AccountRatingCategory.PLAYER);
		findCategory(pagedList.get(0), AccountRatingCategory.TRAINER);
		findCategory(pagedList.get(0), AccountRatingCategory.MANAGER);

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
				() -> this.accountRatingsResource.getAccountTrustExplanation(Base58.encode(unknown.getPublicKey()), null));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountTrustProfile(Base58.encode(unknown.getPublicKey())));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountTrustProfile(null));
	}

	@Test
	public void testInvalidPublicKeyFailsList() {
		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountRatings("not-a-public-key", null, null, null, null));
		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountTrustExplanation("not-a-public-key", null));
		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountTrustProfile("not-a-public-key"));
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

	private AccountTrustCategoryData findCategory(AccountTrustDerivationData derivation,
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

	private AccountTrustProfileData.CategoryProfile findCategory(AccountTrustProfileData profile, AccountRatingCategory category) {
		return profile.getCategories().stream()
				.filter(categoryProfile -> categoryProfile.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing category " + category));
	}

	private AccountTrustPolicyData.StatusVoteWeight findStatusVoteWeight(AccountTrustPolicyData policy,
			AccountTrustStatus status) {
		return policy.getStatusVoteWeights().stream()
				.filter(statusVoteWeight -> statusVoteWeight.getStatus() == status)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing status vote weight " + status));
	}

	private AccountTrustSummaryData.StatusSummary findStatusSummary(AccountTrustSummaryData summary,
			AccountTrustStatus status) {
		return summary.getStatusSummaries().stream()
				.filter(statusSummary -> statusSummary.getStatus() == status)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing trust summary for status " + status));
	}

	private AccountTrustSummaryData.CategorySummary findCategorySummary(AccountTrustSummaryData summary,
			AccountRatingCategory category) {
		return summary.getCategorySummaries().stream()
				.filter(categorySummary -> categorySummary.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing trust summary for category " + category));
	}

	private AccountTrustSummaryData.StatusCount findStatusCount(AccountTrustSummaryData.CategorySummary categorySummary,
			AccountTrustStatus status) {
		return categorySummary.getStatusCounts().stream()
				.filter(statusCount -> statusCount.getStatus() == status)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing trust summary count for status " + status));
	}

	private AccountTrustPolicyData.CategoryPolicy findCategoryPolicy(AccountTrustPolicyData policy,
			AccountRatingCategory category) {
		return policy.getCategoryPolicies().stream()
				.filter(categoryPolicy -> categoryPolicy.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing category policy " + category));
	}

	private AccountTrustPolicyData.LevelPolicy findLevelPolicy(AccountTrustPolicyData.CategoryPolicy categoryPolicy,
			int level) {
		return categoryPolicy.getLevels().stream()
				.filter(levelPolicy -> levelPolicy.getLevel() == level)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing level policy " + level));
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
		return subjectDerivation(account, trustStatus, new AccountTrustRatingCountsData());
	}

	private AccountTrustDerivationData subjectDerivation(TestAccount account, AccountTrustStatus trustStatus,
			AccountTrustRatingCountsData inboundRatings) throws DataException {
		AccountTrustCategoryData subjectTrust = new AccountTrustCategoryData(
				AccountRatingCategory.SUBJECT, scoreForStatus(trustStatus), levelForStatus(trustStatus), trustStatus,
				inboundRatings, Collections.emptyList());
		return new AccountTrustDerivationData(account.getPublicKey(), account.getAddress(), trustStatus, true,
				Collections.singletonList(subjectTrust));
	}

	private AccountTrustDerivationData trustDerivation(PrivateKeyAccount account, boolean seedMember,
			AccountTrustCategoryData... categories) {
		AccountTrustStatus subjectStatus = AccountTrustStatus.UNVERIFIED;
		for (AccountTrustCategoryData category : categories) {
			if (category.getCategory() == AccountRatingCategory.SUBJECT) {
				subjectStatus = category.getMappedTrustStatus();
				break;
			}
		}

		return new AccountTrustDerivationData(account.getPublicKey(), account.getAddress(), subjectStatus, seedMember,
				Arrays.asList(categories));
	}

	private AccountTrustCategoryData categoryTrust(AccountRatingCategory category, AccountTrustStatus trustStatus) {
		return new AccountTrustCategoryData(category, scoreForStatus(trustStatus), levelForStatus(trustStatus),
				trustStatus, new AccountTrustRatingCountsData(), Collections.emptyList());
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

	private void setVoteAccount(Repository repository, PrivateKeyAccount account, int blocksMinted) throws DataException {
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
