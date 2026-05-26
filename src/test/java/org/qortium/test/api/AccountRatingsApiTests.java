package org.qortium.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.AccountTrustDerivation;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.ApiError;
import org.qortium.api.model.SimpleTransactionSignRequest;
import org.qortium.api.resource.AccountRatingsResource;
import org.qortium.api.resource.TransactionsResource;
import org.qortium.block.BlockChain;
import org.qortium.block.ChainParameter;
import org.qortium.data.account.AccountData;
import org.qortium.data.account.AccountRating;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountRatingCooldownData;
import org.qortium.data.account.AccountRatingData;
import org.qortium.data.account.AccountRatingImpactPreviewData;
import org.qortium.data.account.AccountRatingSummaryData;
import org.qortium.data.account.AccountTrustDerivationData;
import org.qortium.data.account.AccountTrustExplanationData;
import org.qortium.data.account.AccountTrustPolicyData;
import org.qortium.data.account.AccountTrustCategoryData;
import org.qortium.data.account.AccountTrustCategoryImpactData;
import org.qortium.data.account.AccountTrustRatingCountsData;
import org.qortium.data.account.AccountTrustProfileData;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.account.AccountTrustStatusChangeData;
import org.qortium.data.account.AccountTrustSummaryData;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.ChainParameterUpdateTransactionData;
import org.qortium.data.transaction.RateAccountTransactionData;
import org.qortium.data.transaction.TransactionConfirmationTimingData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountTrustTestUtils;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.Base58;

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
	private TransactionsResource transactionsResource;

	@Before
	public void buildResource() {
		this.accountRatingsResource = (AccountRatingsResource) ApiCommon.buildResource(AccountRatingsResource.class);
		this.transactionsResource = (TransactionsResource) ApiCommon.buildResource(TransactionsResource.class);
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
		assertEquals(0L, summary.getSnapshotAccountCount());
		assertEquals(0L, summary.getSnapshotRowCount());
		assertEquals(0L, summary.getExpectedSnapshotRowCount());
		assertTrue(summary.isSnapshotsComplete());
		assertEquals(0L, summary.getActiveRatingCount());
		assertEquals(0L, summary.getTrustStatusChangeCount());
		assertNull(summary.getLatestTrustChangeHeight());
		assertNull(summary.getLatestTrustChangeTimestamp());
		assertEquals(0L, summary.getActiveSnapshotAccountCount());
		assertEquals(0L, summary.getActiveSeedMemberCount());
		assertEquals(0L, summary.getActiveMintingAllowedCount());
		assertEquals(0L, summary.getSuspiciousCount());
		assertEquals(0L, summary.getRawVoteWeight());
		assertEquals(0L, summary.getEffectiveVoteWeight());
		assertEquals(AccountTrustStatus.values().length, summary.getStatusSummaries().size());
		assertEquals(AccountRatingCategory.values().length, summary.getCategorySummaries().size());
		assertEquals(AccountRatingCategory.values().length, summary.getRatingCategorySummaries().size());

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

		for (AccountTrustSummaryData.RatingCategorySummary ratingCategorySummary : summary.getRatingCategorySummaries()) {
			assertEquals(0L, ratingCategorySummary.getRatingCount());
			assertEquals(0L, ratingCategorySummary.getPositiveRatingCount());
			assertEquals(0L, ratingCategorySummary.getNegativeRatingCount());
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

			saveAccountRating(repository, alice, bob, AccountRatingCategory.SUBJECT, 4);
			saveAccountRating(repository, bob, alice, AccountRatingCategory.SUBJECT, -2);
			saveAccountRating(repository, chloe, dilbert, AccountRatingCategory.MANAGER, 3);
			saveAccountRating(repository, dilbert, chloe, AccountRatingCategory.PLAYER, -1);

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
		assertEquals(5L, summary.getSnapshotAccountCount());
		assertEquals(9L, summary.getSnapshotRowCount());
		assertEquals(20L, summary.getExpectedSnapshotRowCount());
		assertFalse(summary.isSnapshotsComplete());
		assertEquals(4L, summary.getActiveRatingCount());
		assertEquals(0L, summary.getTrustStatusChangeCount());
		assertNull(summary.getLatestTrustChangeHeight());
		assertNull(summary.getLatestTrustChangeTimestamp());
		assertEquals(5L, summary.getActiveSnapshotAccountCount());
		assertEquals(2L, summary.getActiveSeedMemberCount());
		assertEquals(2L, summary.getActiveMintingAllowedCount());
		assertEquals(1L, summary.getSuspiciousCount());
		assertEquals(505L, summary.getRawVoteWeight());
		assertEquals(211L, summary.getEffectiveVoteWeight());

		AccountTrustSummaryData.StatusSummary gold = findStatusSummary(summary, AccountTrustStatus.GOLD);
		assertEquals(1L, gold.getAccountCount());
		assertEquals(1L, gold.getSeedMemberCount());
		assertEquals(101L, gold.getRawVoteWeight());
		assertEquals(100, gold.getVoteWeightPercent());
		assertEquals(101L, gold.getEffectiveVoteWeight());

		AccountTrustSummaryData.StatusSummary silver = findStatusSummary(summary, AccountTrustStatus.SILVER);
		assertEquals(1L, silver.getAccountCount());
		assertEquals(1L, silver.getSeedMemberCount());
		assertEquals(101L, silver.getRawVoteWeight());
		assertEquals(70, silver.getVoteWeightPercent());
		assertEquals(70L, silver.getEffectiveVoteWeight());

		AccountTrustSummaryData.StatusSummary bronze = findStatusSummary(summary, AccountTrustStatus.BRONZE);
		assertEquals(1L, bronze.getAccountCount());
		assertEquals(0L, bronze.getSeedMemberCount());
		assertEquals(101L, bronze.getRawVoteWeight());
		assertEquals(40, bronze.getVoteWeightPercent());
		assertEquals(40L, bronze.getEffectiveVoteWeight());

		AccountTrustSummaryData.StatusSummary suspicious = findStatusSummary(summary, AccountTrustStatus.SUSPICIOUS);
		assertEquals(1L, suspicious.getAccountCount());
		assertEquals(0L, suspicious.getSeedMemberCount());
		assertEquals(101L, suspicious.getRawVoteWeight());
		assertEquals(0, suspicious.getVoteWeightPercent());
		assertEquals(0L, suspicious.getEffectiveVoteWeight());
		assertFalse(suspicious.isTrustAllowsMinting());

		AccountTrustSummaryData.StatusSummary unverified = findStatusSummary(summary, AccountTrustStatus.UNVERIFIED);
		assertEquals(1L, unverified.getAccountCount());
		assertEquals(101L, unverified.getRawVoteWeight());
		assertEquals(0, unverified.getVoteWeightPercent());
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

		AccountTrustSummaryData.RatingCategorySummary subjectRatings =
				findRatingCategorySummary(summary, AccountRatingCategory.SUBJECT);
		assertEquals(2L, subjectRatings.getRatingCount());
		assertEquals(1L, subjectRatings.getPositiveRatingCount());
		assertEquals(1L, subjectRatings.getNegativeRatingCount());

		AccountTrustSummaryData.RatingCategorySummary managerRatings =
				findRatingCategorySummary(summary, AccountRatingCategory.MANAGER);
		assertEquals(1L, managerRatings.getRatingCount());
		assertEquals(1L, managerRatings.getPositiveRatingCount());
		assertEquals(0L, managerRatings.getNegativeRatingCount());

		AccountTrustSummaryData.RatingCategorySummary playerRatings =
				findRatingCategorySummary(summary, AccountRatingCategory.PLAYER);
		assertEquals(1L, playerRatings.getRatingCount());
		assertEquals(0L, playerRatings.getPositiveRatingCount());
		assertEquals(1L, playerRatings.getNegativeRatingCount());

		AccountTrustSummaryData.RatingCategorySummary trainerRatings =
				findRatingCategorySummary(summary, AccountRatingCategory.TRAINER);
		assertEquals(0L, trainerRatings.getRatingCount());
	}

	@Test
	public void testTrustSummaryEndpointUsesOnChainVoteWeights() throws DataException {
		TestAccount alice;
		TestAccount bob;
		int[] voteWeights = new int[] { 0, 20, 50, 80, 90 };

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");

			approveTrustStatusVoteWeightsOverlay(repository, voteWeights);
			setVoteAccount(repository, alice, 100);
			setVoteAccount(repository, bob, 100);
			replaceTrustSnapshots(repository, repository.getBlockRepository().getBlockchainHeight(),
					repository.getBlockRepository().getLastBlock().getTimestamp(),
					trustDerivation(alice, true, categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.GOLD)),
					trustDerivation(bob, true, categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.BRONZE)));

			assertEquals(90, new Account(repository, alice.getAddress()).getEffectiveVoteWeight());
			assertEquals(50, new Account(repository, bob.getAddress()).getEffectiveVoteWeight());
		}

		AccountTrustSummaryData summary = this.accountRatingsResource.getAccountTrustSummary();

		assertEquals(200L, summary.getRawVoteWeight());
		assertEquals(140L, summary.getEffectiveVoteWeight());

		AccountTrustSummaryData.StatusSummary gold = findStatusSummary(summary, AccountTrustStatus.GOLD);
		assertEquals(90, gold.getVoteWeightPercent());
		assertEquals(90L, gold.getEffectiveVoteWeight());

		AccountTrustSummaryData.StatusSummary bronze = findStatusSummary(summary, AccountTrustStatus.BRONZE);
		assertEquals(50, bronze.getVoteWeightPercent());
		assertEquals(50L, bronze.getEffectiveVoteWeight());
	}

	@Test
	public void testTrustSummaryEndpointReportsCompleteSnapshotsAndLatestChange() throws DataException {
		TestAccount alice;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");

			replaceTrustSnapshots(repository, 10, 1000L,
					completeTrustDerivation(alice, true, AccountTrustStatus.UNVERIFIED));
			replaceTrustSnapshots(repository, 11, 2000L,
					completeTrustDerivation(alice, true, AccountTrustStatus.GOLD));
		}

		AccountTrustSummaryData summary = this.accountRatingsResource.getAccountTrustSummary();

		assertEquals(1L, summary.getSnapshotAccountCount());
		assertEquals(AccountRatingCategory.values().length, summary.getSnapshotRowCount());
		assertEquals(AccountRatingCategory.values().length, summary.getExpectedSnapshotRowCount());
		assertTrue(summary.isSnapshotsComplete());
		assertEquals(1L, summary.getTrustStatusChangeCount());
		assertEquals(Integer.valueOf(11), summary.getLatestTrustChangeHeight());
		assertEquals(Long.valueOf(2000L), summary.getLatestTrustChangeTimestamp());

		AccountTrustSummaryData.StatusSummary gold = findStatusSummary(summary, AccountTrustStatus.GOLD);
		assertEquals(1L, gold.getAccountCount());
	}

	@Test
	public void testTrustPolicyEndpointReturnsConfiguredPolicy() {
		AccountTrustPolicyData policy = this.accountRatingsResource.getAccountTrustPolicy();

		assertEquals(AccountRatingCategory.SUBJECT, policy.getActiveWeightCategory());
		assertEquals(1_000_000L, policy.getStartingEnergy());
		assertEquals(4, policy.getManagerEnergyHops());
		assertEquals(2, policy.getPositiveMinBranchCount());
		assertEquals(2, policy.getSuspiciousMinRaterCount());
		assertEquals(2, policy.getSuspiciousMinBranchCount());
		assertEquals(2, policy.getSuspiciousMinRatingConfidence());
		assertEquals(1440, policy.getAccountRatingChangeCooldownBlocks());
		assertEquals(AccountTrustStatus.values().length, policy.getStatusVoteWeights().size());
		assertEquals(AccountRatingCategory.values().length, policy.getCategoryPolicies().size());

		AccountTrustPolicyData.StatusVoteWeight gold = findStatusVoteWeight(policy, AccountTrustStatus.GOLD);
		assertEquals(AccountTrustStatus.GOLD.getValue(), gold.getStatusValue());
		assertEquals(100, gold.getVoteWeightPercent());
		assertTrue(gold.isTrustAllowsMinting());

		AccountTrustPolicyData.StatusVoteWeight silver = findStatusVoteWeight(policy, AccountTrustStatus.SILVER);
		assertEquals(AccountTrustStatus.SILVER.getValue(), silver.getStatusValue());
		assertEquals(70, silver.getVoteWeightPercent());
		assertTrue(silver.isTrustAllowsMinting());

		AccountTrustPolicyData.StatusVoteWeight bronze = findStatusVoteWeight(policy, AccountTrustStatus.BRONZE);
		assertEquals(AccountTrustStatus.BRONZE.getValue(), bronze.getStatusValue());
		assertEquals(40, bronze.getVoteWeightPercent());
		assertTrue(bronze.isTrustAllowsMinting());

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
		assertEquals(70, subjectLevelTwo.getMappedTrustWeightPercent());
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
	public void testTrustPolicyEndpointUsesOnChainVoteWeights() throws DataException {
		int[] voteWeights = new int[] { 0, 20, 50, 80, 90 };

		try (final Repository repository = RepositoryManager.getRepository()) {
			approveTrustStatusVoteWeightsOverlay(repository, voteWeights);
		}

		AccountTrustPolicyData policy = this.accountRatingsResource.getAccountTrustPolicy();

		assertEquals(90, findStatusVoteWeight(policy, AccountTrustStatus.GOLD).getVoteWeightPercent());
		assertEquals(80, findStatusVoteWeight(policy, AccountTrustStatus.SILVER).getVoteWeightPercent());
		assertEquals(50, findStatusVoteWeight(policy, AccountTrustStatus.BRONZE).getVoteWeightPercent());
		assertEquals(20, findStatusVoteWeight(policy, AccountTrustStatus.UNVERIFIED).getVoteWeightPercent());
		assertEquals(0, findStatusVoteWeight(policy, AccountTrustStatus.SUSPICIOUS).getVoteWeightPercent());

		AccountTrustPolicyData.CategoryPolicy subject = findCategoryPolicy(policy, AccountRatingCategory.SUBJECT);
		assertEquals(80, findLevelPolicy(subject, 2).getMappedTrustWeightPercent());
		assertEquals(90, findLevelPolicy(subject, 3).getMappedTrustWeightPercent());
	}

	@Test
	public void testTrustPolicyEndpointUsesOnChainStartingEnergy() throws DataException {
		long startingEnergy = 1_234_567L;

		try (final Repository repository = RepositoryManager.getRepository()) {
			approveAccountTrustStartingEnergyOverlay(repository, startingEnergy);
		}

		AccountTrustPolicyData policy = this.accountRatingsResource.getAccountTrustPolicy();

		assertEquals(startingEnergy, policy.getStartingEnergy());
	}

	@Test
	public void testTrustPolicyEndpointUsesOnChainManagerEnergyHops() throws DataException {
		int managerEnergyHops = 5;

		try (final Repository repository = RepositoryManager.getRepository()) {
			approveAccountTrustManagerEnergyHopsOverlay(repository, managerEnergyHops);
		}

		AccountTrustPolicyData policy = this.accountRatingsResource.getAccountTrustPolicy();

		assertEquals(managerEnergyHops, policy.getManagerEnergyHops());
	}

	@Test
	public void testTrustPolicyEndpointUsesOnChainPositiveMinBranchCount() throws DataException {
		int positiveMinBranchCount = 3;

		try (final Repository repository = RepositoryManager.getRepository()) {
			approveAccountTrustPositiveMinBranchCountOverlay(repository, positiveMinBranchCount);
		}

		AccountTrustPolicyData policy = this.accountRatingsResource.getAccountTrustPolicy();

		assertEquals(positiveMinBranchCount, policy.getPositiveMinBranchCount());
	}

	@Test
	public void testTrustPolicyEndpointUsesOnChainSuspiciousDecisionSettings() throws DataException {
		int suspiciousMinRaterCount = 3;
		int suspiciousMinRatingConfidence = 3;

		try (final Repository repository = RepositoryManager.getRepository()) {
			approveAccountTrustSuspiciousMinRaterCountOverlay(repository, suspiciousMinRaterCount);
			approveAccountTrustSuspiciousMinBranchCountOverlay(repository, 0);
			approveAccountTrustSuspiciousMinRatingConfidenceOverlay(repository, suspiciousMinRatingConfidence);
		}

		AccountTrustPolicyData policy = this.accountRatingsResource.getAccountTrustPolicy();

		assertEquals(suspiciousMinRaterCount, policy.getSuspiciousMinRaterCount());
		assertEquals(suspiciousMinRaterCount, policy.getSuspiciousMinBranchCount());
		assertEquals(suspiciousMinRatingConfidence, policy.getSuspiciousMinRatingConfidence());
	}

	@Test
	public void testTrustExplanationUsesOnChainSuspiciousDecisionSettings() throws DataException {
		TestAccount alice;
		TestAccount bob;
		TestAccount dilbert;

		try (final Repository repository = RepositoryManager.getRepository()) {
			approveAccountTrustSuspiciousMinRaterCountOverlay(repository, 2);
			approveAccountTrustSuspiciousMinBranchCountOverlay(repository, 2);
			approveAccountTrustSuspiciousMinRatingConfidenceOverlay(repository, 4);

			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			dilbert = Common.getTestAccount(repository, "dilbert");

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, dilbert);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, alice, bob);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, alice, dilbert);
			refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository, ratingData(bob, alice, AccountRatingCategory.SUBJECT, -4), bob);
			TransactionUtils.signAndMint(repository,
					ratingData(dilbert, alice, AccountRatingCategory.SUBJECT, -4), dilbert);
		}

		AccountTrustExplanationData explanation = this.accountRatingsResource
				.getAccountTrustExplanation(Base58.encode(alice.getPublicKey()), null);
		AccountTrustExplanationData.CategoryExplanation subject = findCategory(explanation, AccountRatingCategory.SUBJECT);

		assertEquals(AccountTrustStatus.SUSPICIOUS, explanation.getTrustStatus());
		assertEquals(2, subject.getSuspiciousMinBranchCount());
		assertEquals("-10000000", findRequirement(subject, "suspicious.threshold").getRequired());
		assertEquals("2", findRequirement(subject, "suspicious.independent-raters").getRequired());
		assertEquals("2", findRequirement(subject, "suspicious.independent-branches").getRequired());
	}

	@Test
	public void testRatingCooldownEndpointReturnsOpenEdgeStatus() throws DataException {
		TestAccount alice;
		TestAccount bob;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
		}

		AccountRatingCooldownData cooldown = this.accountRatingsResource.getAccountRatingCooldown(
				Base58.encode(bob.getPublicKey()), Base58.encode(alice.getPublicKey()), null);

		assertEquals(bob.getAddress(), cooldown.getTargetAddress());
		assertEquals(alice.getAddress(), cooldown.getRaterAddress());
		assertEquals(AccountRatingCategory.SUBJECT, cooldown.getCategory());
		assertNull(cooldown.getActiveRating());
		assertEquals(1440, cooldown.getCooldownBlocks());
		assertNull(cooldown.getLatestRatingChangeHeight());
		assertEquals(cooldown.getCurrentHeight() + 1, cooldown.getCandidateChangeHeight());
		assertEquals(cooldown.getCandidateChangeHeight(), cooldown.getEarliestAllowedHeight());
		assertEquals(0, cooldown.getBlocksRemaining());
		assertTrue(cooldown.isCanChangeNow());
	}

	@Test
	public void testRatingCooldownEndpointReportsActiveCooldown() throws DataException {
		TestAccount alice;
		TestAccount bob;
		int latestChangeHeight;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4), alice);
			latestChangeHeight = repository.getBlockRepository().getBlockchainHeight();
		}

		AccountRatingCooldownData cooldown = this.accountRatingsResource.getAccountRatingCooldown(
				Base58.encode(bob.getPublicKey()), Base58.encode(alice.getPublicKey()),
				AccountRatingCategory.SUBJECT.name());

		assertEquals(Integer.valueOf(4), cooldown.getActiveRating());
		assertEquals(1440, cooldown.getCooldownBlocks());
		assertEquals(Integer.valueOf(latestChangeHeight), cooldown.getLatestRatingChangeHeight());
		assertEquals(latestChangeHeight, cooldown.getCurrentHeight());
		assertEquals(latestChangeHeight + 1, cooldown.getCandidateChangeHeight());
		assertEquals(latestChangeHeight + 1440, cooldown.getEarliestAllowedHeight());
		assertEquals(1439, cooldown.getBlocksRemaining());
		assertFalse(cooldown.isCanChangeNow());
	}

	@Test
	public void testRatingCooldownEndpointReportsAllowedAfterWindow() throws Exception {
		AccountTrustTestUtils.useAccountRatingCooldown(2);
		TestAccount alice;
		TestAccount bob;
		int latestChangeHeight;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4), alice);
			latestChangeHeight = repository.getBlockRepository().getBlockchainHeight();
		}

		AccountRatingCooldownData activeCooldown = this.accountRatingsResource.getAccountRatingCooldown(
				Base58.encode(bob.getPublicKey()), Base58.encode(alice.getPublicKey()),
				AccountRatingCategory.SUBJECT.name());
		assertEquals(2, activeCooldown.getCooldownBlocks());
		assertEquals(latestChangeHeight + 2, activeCooldown.getEarliestAllowedHeight());
		assertEquals(1, activeCooldown.getBlocksRemaining());
		assertFalse(activeCooldown.isCanChangeNow());

		try (final Repository repository = RepositoryManager.getRepository()) {
			BlockUtils.mintBlock(repository);
		}

		AccountRatingCooldownData expiredCooldown = this.accountRatingsResource.getAccountRatingCooldown(
				Base58.encode(bob.getPublicKey()), Base58.encode(alice.getPublicKey()),
				AccountRatingCategory.SUBJECT.name());
		assertEquals(latestChangeHeight + 2, expiredCooldown.getCandidateChangeHeight());
		assertEquals(latestChangeHeight + 2, expiredCooldown.getEarliestAllowedHeight());
		assertEquals(0, expiredCooldown.getBlocksRemaining());
		assertTrue(expiredCooldown.isCanChangeNow());
	}

	@Test
	public void testRatingCooldownEndpointReportsDisabledCooldown() throws Exception {
		AccountTrustTestUtils.useAccountRatingCooldown(0);
		TestAccount alice;
		TestAccount bob;
		int latestChangeHeight;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4), alice);
			latestChangeHeight = repository.getBlockRepository().getBlockchainHeight();
		}

		AccountRatingCooldownData cooldown = this.accountRatingsResource.getAccountRatingCooldown(
				Base58.encode(bob.getPublicKey()), Base58.encode(alice.getPublicKey()),
				AccountRatingCategory.SUBJECT.name());

		assertEquals(0, cooldown.getCooldownBlocks());
		assertEquals(Integer.valueOf(latestChangeHeight), cooldown.getLatestRatingChangeHeight());
		assertEquals(cooldown.getCandidateChangeHeight(), cooldown.getEarliestAllowedHeight());
		assertEquals(0, cooldown.getBlocksRemaining());
		assertTrue(cooldown.isCanChangeNow());
	}

	@Test
	public void testAccountRatingImpactPreviewShowsLiveStatusChangeWithoutSavingRating() throws DataException {
		TestAccount alice;
		TestAccount bob;
		TestAccount chloe;
		TestAccount dilbert;
		PrivateKeyAccount target;
		PrivateKeyAccount secondPlayer;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			chloe = Common.getTestAccount(repository, "chloe");
			dilbert = Common.getTestAccount(repository, "dilbert");
			target = Common.generateRandomSeedAccount(repository);
			secondPlayer = Common.generateRandomSeedAccount(repository);

			createAuraTrustGraph(repository, alice, bob, chloe, dilbert);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, alice, secondPlayer);
			ensureKnownAccount(repository, target);
			saveAccountRating(repository, secondPlayer, target, AccountRatingCategory.SUBJECT, 4);
			repository.saveChanges();
		}

		AccountRatingImpactPreviewData preview = this.accountRatingsResource.getAccountRatingImpactPreview(
				Base58.encode(target.getPublicKey()), Base58.encode(dilbert.getPublicKey()),
				AccountRatingCategory.SUBJECT.name(), 4);

		assertEquals(Transaction.ValidationResult.OK.name(), preview.getValidationResult());
		assertEquals(Transaction.ValidationResult.OK.value, preview.getValidationResultValue());
		assertTrue(preview.isCanSubmit());
		assertEquals(target.getAddress(), preview.getTargetAddress());
		assertEquals(dilbert.getAddress(), preview.getRaterAddress());
		assertEquals(AccountRatingCategory.SUBJECT, preview.getCategory());
		assertEquals(4, preview.getCandidateRating());
		assertEquals("POSITIVE", preview.getCandidateRatingDirection());
		assertEquals(4, preview.getCandidateRatingConfidence());
		assertNull(preview.getActiveRating());
		assertEquals(Integer.valueOf(4), preview.getPreviewActiveRating());
		assertEquals(AccountTrustStatus.UNVERIFIED, preview.getCurrentTrust().getDerivedTrustStatus());
		assertTrue(preview.getPreviewTrust().getDerivedTrustStatus() != AccountTrustStatus.UNVERIFIED);
		assertTrue(preview.isTrustStatusChanged());
		assertTrue(preview.isTrustWeightChanged());
		assertTrue(preview.isSelectedCategoryLevelChanged());
		assertTrue(preview.isSelectedCategoryScoreChanged());
		assertEquals(0, preview.getCooldown().getBlocksRemaining());

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertNull(repository.getAccountRatingRepository().getRating(target.getPublicKey(), dilbert.getPublicKey(),
					AccountRatingCategory.SUBJECT));
			AccountTrustDerivation.Result liveResult = AccountTrustDerivation.derive(repository, target.getAddress());
			assertEquals(AccountTrustStatus.UNVERIFIED, liveResult.getDerivedTrustStatus());
		}
	}

	@Test
	public void testAccountRatingImpactPreviewShowsRemovalWithoutDeletingRating() throws Exception {
		AccountTrustTestUtils.useAccountRatingCooldown(0);
		TestAccount alice;
		TestAccount bob;
		TestAccount chloe;
		TestAccount dilbert;
		PrivateKeyAccount target;
		PrivateKeyAccount secondPlayer;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			chloe = Common.getTestAccount(repository, "chloe");
			dilbert = Common.getTestAccount(repository, "dilbert");
			target = Common.generateRandomSeedAccount(repository);
			secondPlayer = Common.generateRandomSeedAccount(repository);

			createAuraTrustGraph(repository, alice, bob, chloe, dilbert);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, alice, secondPlayer);
			saveAccountRating(repository, secondPlayer, target, AccountRatingCategory.SUBJECT, 4);
			saveAccountRating(repository, dilbert, target, AccountRatingCategory.SUBJECT, 4);
			repository.saveChanges();
		}

		AccountRatingImpactPreviewData preview = this.accountRatingsResource.getAccountRatingImpactPreview(
				Base58.encode(target.getPublicKey()), Base58.encode(dilbert.getPublicKey()),
				AccountRatingCategory.SUBJECT.name(), AccountRating.NO_RATING);

		assertEquals(Transaction.ValidationResult.OK.name(), preview.getValidationResult());
		assertTrue(preview.isCanSubmit());
		assertEquals(Integer.valueOf(4), preview.getActiveRating());
		assertNull(preview.getPreviewActiveRating());
		assertTrue(preview.getCurrentTrust().getDerivedTrustStatus() != AccountTrustStatus.UNVERIFIED);
		assertEquals(AccountTrustStatus.UNVERIFIED, preview.getPreviewTrust().getDerivedTrustStatus());
		assertTrue(preview.isTrustStatusChanged());
		assertTrue(preview.isSelectedCategoryScoreChanged());

		try (final Repository repository = RepositoryManager.getRepository()) {
			AccountRatingData activeRating = repository.getAccountRatingRepository()
					.getRating(target.getPublicKey(), dilbert.getPublicKey(), AccountRatingCategory.SUBJECT);
			assertNotNull(activeRating);
			assertEquals(4, activeRating.getRating());
		}
	}

	@Test
	public void testAccountRatingImpactPreviewReportsCooldownBlockedChange() throws DataException {
		TestAccount alice;
		TestAccount bob;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4), alice);
		}

		AccountRatingImpactPreviewData preview = this.accountRatingsResource.getAccountRatingImpactPreview(
				Base58.encode(bob.getPublicKey()), Base58.encode(alice.getPublicKey()),
				AccountRatingCategory.SUBJECT.name(), 3);

		assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_CHANGE_TOO_SOON.name(), preview.getValidationResult());
		assertFalse(preview.isCanSubmit());
		assertEquals(Integer.valueOf(4), preview.getActiveRating());
		assertEquals(Integer.valueOf(4), preview.getPreviewActiveRating());
		assertFalse(preview.isTrustStatusChanged());
		assertFalse(preview.isTrustWeightChanged());
		assertFalse(preview.isSelectedCategoryLevelChanged());
		assertFalse(preview.isSelectedCategoryScoreChanged());
		assertFalse(preview.getCooldown().isCanChangeNow());
	}

	@Test
	public void testAccountRatingImpactPreviewReportsNoOpAndUnknownTarget() throws DataException {
		TestAccount alice;
		TestAccount bob;
		PrivateKeyAccount unknown;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			unknown = Common.generateRandomSeedAccount(repository);

			saveAccountRating(repository, alice, bob, AccountRatingCategory.SUBJECT, 4);
			repository.saveChanges();
		}

		AccountRatingImpactPreviewData noOpPreview = this.accountRatingsResource.getAccountRatingImpactPreview(
				Base58.encode(bob.getPublicKey()), Base58.encode(alice.getPublicKey()),
				AccountRatingCategory.SUBJECT.name(), 4);
		assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_UNCHANGED.name(), noOpPreview.getValidationResult());
		assertFalse(noOpPreview.isCanSubmit());
		assertEquals(Integer.valueOf(4), noOpPreview.getActiveRating());
		assertEquals(Integer.valueOf(4), noOpPreview.getPreviewActiveRating());

		AccountRatingImpactPreviewData unknownPreview = this.accountRatingsResource.getAccountRatingImpactPreview(
				Base58.encode(unknown.getPublicKey()), Base58.encode(alice.getPublicKey()),
				AccountRatingCategory.SUBJECT.name(), 4);
		assertEquals(Transaction.ValidationResult.PUBLIC_KEY_UNKNOWN.name(), unknownPreview.getValidationResult());
		assertFalse(unknownPreview.isCanSubmit());
		assertEquals(AccountTrustStatus.UNVERIFIED, unknownPreview.getCurrentTrust().getDerivedTrustStatus());
		assertEquals(AccountTrustStatus.UNVERIFIED, unknownPreview.getPreviewTrust().getDerivedTrustStatus());
	}

	@Test
	public void testAccountRatingImpactPreviewReportsSelectedNonSubjectCategory() throws DataException {
		TestAccount alice;
		TestAccount bob;
		TestAccount chloe;
		TestAccount dilbert;
		PrivateKeyAccount playerTarget;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			chloe = Common.getTestAccount(repository, "chloe");
			dilbert = Common.getTestAccount(repository, "dilbert");
			playerTarget = Common.generateRandomSeedAccount(repository);

			createAuraTrustGraph(repository, alice, bob, chloe, dilbert);
			ensureKnownAccount(repository, playerTarget);
			repository.saveChanges();
		}

		AccountRatingImpactPreviewData preview = this.accountRatingsResource.getAccountRatingImpactPreview(
				Base58.encode(playerTarget.getPublicKey()), Base58.encode(chloe.getPublicKey()),
				AccountRatingCategory.PLAYER.name(), 2);

		assertEquals(Transaction.ValidationResult.OK.name(), preview.getValidationResult());
		assertEquals(AccountRatingCategory.PLAYER, preview.getCategory());
		assertEquals(AccountTrustStatus.UNVERIFIED, preview.getCurrentTrust().getDerivedTrustStatus());
		assertEquals(AccountTrustStatus.UNVERIFIED, preview.getPreviewTrust().getDerivedTrustStatus());
		assertFalse(preview.isTrustStatusChanged());
		assertTrue(preview.isSelectedCategoryScoreChanged());
		assertEquals(AccountRatingCategory.PLAYER, preview.getPreviewSelectedCategory().getCategory());
		assertTrue(preview.getPreviewSelectedCategory().getScore() > 0L);
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
			setVoteAccount(repository, bob, 250);
		}

		AccountTrustProfileData profile = this.accountRatingsResource.getAccountTrustProfile(Base58.encode(bob.getPublicKey()));

		assertEquals(bob.getAddress(), profile.getTargetAddress());
		assertEquals(AccountTrustStatus.UNVERIFIED, profile.getTrustStatus());
		assertEquals(AccountTrustStatus.UNVERIFIED.getValue(), profile.getTrustStatusValue());
		assertEquals(0, profile.getTrustWeightPercent());
		assertTrue(profile.isTrustAllowsMinting());
		assertEquals(250, profile.getBlocksMinted());
		assertEquals(0, profile.getEffectiveVoteWeight());
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
			setVoteAccount(repository, bob, 250);
			saveSubjectSnapshots(repository, subjectDerivation(bob, AccountTrustStatus.BRONZE, subjectInboundCounts));
		}

		AccountTrustProfileData profile = this.accountRatingsResource.getAccountTrustProfile(Base58.encode(bob.getPublicKey()));

		assertEquals(bob.getAddress(), profile.getTargetAddress());
		assertEquals(AccountTrustStatus.BRONZE, profile.getTrustStatus());
		assertEquals(AccountTrustStatus.BRONZE.getValue(), profile.getTrustStatusValue());
		assertEquals(40, profile.getTrustWeightPercent());
		assertTrue(profile.isTrustAllowsMinting());
		assertEquals(250, profile.getBlocksMinted());
		assertEquals(100, profile.getEffectiveVoteWeight());
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
		assertEquals(40, subject.getMappedTrustWeightPercent());
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
		assertEquals(70, storedExplanation.getTrustWeightPercent());
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
		assertEquals(2, storedSubject.getPositiveMinBranchCount());
		assertEquals(50_000_000L, findConfiguredLevel(storedSubject, 2).getThreshold());
		assertEquals(25_000_000L, findConfiguredLevel(storedSubject, 2).getLevelScoreCap());
		assertTrue(findRequirement(storedSubject, "level.2.threshold").isPassed());
		assertTrue(findRequirement(storedSubject, "level.2.independent-branches").isPassed());
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
		assertEquals(2, subject.getSuspiciousMinBranchCount());
		assertEquals(2, subject.getTopNegativeImpacts().get(0).getTrustBranchCount());

		AccountTrustExplanationData.Requirement suspiciousThreshold = findRequirement(subject, "suspicious.threshold");
		assertFalse(suspiciousThreshold.isPassed());
		assertEquals("-5000000", suspiciousThreshold.getActual());
		assertEquals("-10000000", suspiciousThreshold.getRequired());

		AccountTrustExplanationData.Requirement suspiciousRaters = findRequirement(subject, "suspicious.independent-raters");
		assertFalse(suspiciousRaters.isPassed());
		assertEquals("1", suspiciousRaters.getActual());
		assertEquals("2", suspiciousRaters.getRequired());

		AccountTrustExplanationData.Requirement suspiciousBranches = findRequirement(subject,
				"suspicious.independent-branches");
		assertTrue(suspiciousBranches.isPassed());
		assertEquals("2", suspiciousBranches.getActual());
		assertEquals("2", suspiciousBranches.getRequired());
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
		assertEquals(2, subject.getSuspiciousMinBranchCount());

		AccountTrustExplanationData.Requirement suspiciousThreshold = findRequirement(subject, "suspicious.threshold");
		assertTrue(suspiciousThreshold.isPassed());
		assertEquals("-10000000", suspiciousThreshold.getActual());
		assertEquals("-10000000", suspiciousThreshold.getRequired());

		AccountTrustExplanationData.Requirement suspiciousRaters = findRequirement(subject, "suspicious.independent-raters");
		assertTrue(suspiciousRaters.isPassed());
		assertEquals("2", suspiciousRaters.getActual());
		assertEquals("2", suspiciousRaters.getRequired());

		AccountTrustExplanationData.Requirement suspiciousBranches = findRequirement(subject,
				"suspicious.independent-branches");
		assertTrue(suspiciousBranches.isPassed());
		assertEquals("4", suspiciousBranches.getActual());
		assertEquals("2", suspiciousBranches.getRequired());
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
		assertEquals(70, aliceDerivation.getDerivedTrustWeightPercent());
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
	public void testTrustChangesEndpointRecordsFiltersAndPagesTransitions() throws DataException {
		TestAccount alice;
		TestAccount bob;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");

			replaceTrustSnapshots(repository, 10, 1000L,
					trustDerivation(alice, true,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.UNVERIFIED),
							categoryTrust(AccountRatingCategory.MANAGER, AccountTrustStatus.SILVER)),
					trustDerivation(bob, true,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.UNVERIFIED),
							categoryTrust(AccountRatingCategory.MANAGER, AccountTrustStatus.UNVERIFIED)));
		}

		assertTrue(this.accountRatingsResource.getAccountTrustStatusChanges(
				null, null, null, null, null, null, null).isEmpty());

		try (final Repository repository = RepositoryManager.getRepository()) {
			replaceTrustSnapshots(repository, 11, 2000L,
					trustDerivation(alice, true,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.GOLD),
							categoryTrust(AccountRatingCategory.MANAGER, AccountTrustStatus.SILVER)),
					trustDerivation(bob, true,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.BRONZE),
							categoryTrust(AccountRatingCategory.MANAGER, AccountTrustStatus.SUSPICIOUS)));

			replaceTrustSnapshots(repository, 12, 3000L,
					trustDerivation(alice, true,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.SILVER),
							categoryTrust(AccountRatingCategory.MANAGER, AccountTrustStatus.SILVER)),
					trustDerivation(bob, true,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.BRONZE),
							categoryTrust(AccountRatingCategory.MANAGER, AccountTrustStatus.SUSPICIOUS)));
		}

		List<AccountTrustStatusChangeData> changes = this.accountRatingsResource.getAccountTrustStatusChanges(
				null, null, null, null, null, null, null);
		assertEquals(4, changes.size());

		AccountTrustStatusChangeData newest = changes.get(0);
		assertEquals(alice.getAddress(), newest.getAccountAddress());
		assertEquals(AccountRatingCategory.SUBJECT, newest.getCategory());
		assertEquals(3, newest.getPreviousLevel());
		assertEquals(2, newest.getNewLevel());
		assertEquals(AccountTrustStatus.GOLD, newest.getPreviousTrustStatus());
		assertEquals(AccountTrustStatus.SILVER, newest.getNewTrustStatus());
		assertEquals(AccountTrustStatus.GOLD.getValue(), newest.getPreviousTrustStatusValue());
		assertEquals(AccountTrustStatus.SILVER.getValue(), newest.getNewTrustStatusValue());
		assertEquals(100_000_000L, newest.getPreviousScore());
		assertEquals(50_000_000L, newest.getNewScore());
		assertEquals(11, newest.getPreviousSnapshotHeight());
		assertEquals(2000L, newest.getPreviousSnapshotTimestamp());
		assertEquals(12, newest.getSnapshotHeight());
		assertEquals(3000L, newest.getSnapshotTimestamp());
		assertTrue(newest.isPreviousMintingSeedMember());
		assertTrue(newest.isNewMintingSeedMember());

		List<AccountTrustStatusChangeData> filtered = this.accountRatingsResource.getAccountTrustStatusChanges(
				bob.getAddress(), AccountRatingCategory.MANAGER.name(), AccountTrustStatus.UNVERIFIED.name(),
				AccountTrustStatus.SUSPICIOUS.name(), null, null, null);
		assertEquals(1, filtered.size());
		assertEquals(bob.getAddress(), filtered.get(0).getAccountAddress());
		assertEquals(AccountRatingCategory.MANAGER, filtered.get(0).getCategory());
		assertEquals(AccountTrustStatus.UNVERIFIED, filtered.get(0).getPreviousTrustStatus());
		assertEquals(AccountTrustStatus.SUSPICIOUS, filtered.get(0).getNewTrustStatus());

		List<AccountTrustStatusChangeData> paged = this.accountRatingsResource.getAccountTrustStatusChanges(
				null, null, null, null, 1, 1, null);
		assertEquals(1, paged.size());
		assertEquals(changes.get(1).getAccountAddress(), paged.get(0).getAccountAddress());
		assertEquals(changes.get(1).getCategory(), paged.get(0).getCategory());

		List<AccountTrustStatusChangeData> oldestFirst = this.accountRatingsResource.getAccountTrustStatusChanges(
				null, null, null, null, 1, null, true);
		assertEquals(1, oldestFirst.size());
		assertEquals(11, oldestFirst.get(0).getSnapshotHeight());
	}

	@Test
	public void testTrustChangeHistorySkipsScoreOnlyChanges() throws DataException {
		TestAccount alice;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");

			replaceTrustSnapshots(repository, 10, 1000L,
					trustDerivation(alice, true,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.BRONZE, 10_000_000L)));
			replaceTrustSnapshots(repository, 11, 2000L,
					trustDerivation(alice, true,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.BRONZE, 11_000_000L)));
		}

		assertTrue(this.accountRatingsResource.getAccountTrustStatusChanges(
				null, null, null, null, null, null, null).isEmpty());
	}

	@Test
	public void testTrustChangeHistoryRollbackRemovesOrphanedRows() throws DataException {
		TestAccount alice;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");

			replaceTrustSnapshots(repository, 10, 1000L,
					trustDerivation(alice, true,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.UNVERIFIED)));
			replaceTrustSnapshots(repository, 11, 2000L,
					trustDerivation(alice, true,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.GOLD)));
		}

		List<AccountTrustStatusChangeData> changes = this.accountRatingsResource.getAccountTrustStatusChanges(
				null, null, null, null, null, null, null);
		assertEquals(1, changes.size());
		assertEquals(11, changes.get(0).getSnapshotHeight());

		try (final Repository repository = RepositoryManager.getRepository()) {
			replaceTrustSnapshots(repository, 10, 1000L,
					trustDerivation(alice, true,
							categoryTrust(AccountRatingCategory.SUBJECT, AccountTrustStatus.UNVERIFIED)));
		}

		assertTrue(this.accountRatingsResource.getAccountTrustStatusChanges(
				null, null, null, null, null, null, null).isEmpty());

		List<AccountTrustSnapshotData> snapshots = this.accountRatingsResource.getAccountTrustSnapshots(
				alice.getAddress(), AccountRatingCategory.SUBJECT.name(), null, null, null, null, null, null);
		assertEquals(1, snapshots.size());
		assertEquals(AccountTrustStatus.UNVERIFIED, snapshots.get(0).getMappedTrustStatus());
		assertEquals(10, snapshots.get(0).getSnapshotHeight());
	}

	@Test
	public void testRateAccountEndpointBuildsUnsignedTransaction() throws Exception {
		AccountTrustTestUtils.useAccountRatingCooldown(0);

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
	public void testAccountRatingClientWorkflowConfirmsPreviewedTrustChange() throws Exception {
		TestAccount alice;
		TestAccount bob;
		TestAccount chloe;
		TestAccount dilbert;
		PrivateKeyAccount target;
		PrivateKeyAccount firstPlayer;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			chloe = Common.getTestAccount(repository, "chloe");
			dilbert = Common.getTestAccount(repository, "dilbert");
			target = Common.generateRandomSeedAccount(repository);
			firstPlayer = Common.generateRandomSeedAccount(repository);

			createAuraTrustGraph(repository, alice, bob, chloe, dilbert);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, alice, firstPlayer);
			ensureKnownAccount(repository, target);
			saveAccountRating(repository, firstPlayer, target, AccountRatingCategory.SUBJECT, 4);
			repository.saveChanges();
		}

		String targetPublicKey58 = Base58.encode(target.getPublicKey());
		String raterPublicKey58 = Base58.encode(dilbert.getPublicKey());

		AccountTrustProfileData currentProfile = this.accountRatingsResource.getAccountTrustProfile(targetPublicKey58);
		assertEquals(AccountTrustStatus.UNVERIFIED, currentProfile.getTrustStatus());

		AccountTrustExplanationData currentExplanation = this.accountRatingsResource
				.getAccountTrustExplanation(targetPublicKey58, true);
		assertTrue(currentExplanation.isLive());
		assertEquals(AccountTrustStatus.UNVERIFIED, currentExplanation.getTrustStatus());

		AccountTrustPolicyData policy = this.accountRatingsResource.getAccountTrustPolicy();
		assertEquals(AccountRatingCategory.SUBJECT, policy.getActiveWeightCategory());

		AccountRatingImpactPreviewData preview = this.accountRatingsResource.getAccountRatingImpactPreview(
				targetPublicKey58, raterPublicKey58, AccountRatingCategory.SUBJECT.name(), 4);
		assertEquals(Transaction.ValidationResult.OK.name(), preview.getValidationResult());
		assertTrue(preview.isCanSubmit());
		assertNull(preview.getActiveRating());
		assertEquals(Integer.valueOf(4), preview.getPreviewActiveRating());
		assertEquals(AccountTrustStatus.UNVERIFIED, preview.getCurrentTrust().getDerivedTrustStatus());
		assertTrue(preview.getPreviewTrust().getDerivedTrustStatus() != AccountTrustStatus.UNVERIFIED);
		assertTrue(preview.isTrustStatusChanged());
		assertTrue(preview.isTrustWeightChanged());
		assertEquals(0, preview.getCooldown().getBlocksRemaining());

		AccountRatingCooldownData cooldown = this.accountRatingsResource.getAccountRatingCooldown(
				targetPublicKey58, raterPublicKey58, AccountRatingCategory.SUBJECT.name());
		assertTrue(cooldown.isCanChangeNow());
		assertNull(cooldown.getActiveRating());

		RateAccountTransactionData ratingTransactionData = ratingData(dilbert, target, AccountRatingCategory.SUBJECT, 4);
		String rawUnsignedTransaction = this.accountRatingsResource.rateAccount(ratingTransactionData);
		assertNotNull(rawUnsignedTransaction);
		assertFalse(rawUnsignedTransaction.isEmpty());

		TransactionConfirmationTimingData timing = this.transactionsResource
				.getTransactionConfirmationTiming(rawUnsignedTransaction);
		assertEquals(Transaction.TransactionType.RATE_ACCOUNT, timing.getTransactionType());
		assertTrue(timing.isTransactionConfirmable());
		assertTrue(timing.isConfirmableAtCandidateHeight());
		assertNull(timing.getConfirmationDelayBlocks());
		assertNull(timing.getDelayReason());

		String signedTransaction = signTransaction(dilbert, rawUnsignedTransaction);
		importSignedTransactionAndMint(signedTransaction);

		AccountTrustProfileData confirmedProfile = this.accountRatingsResource.getAccountTrustProfile(targetPublicKey58);
		assertEquals(preview.getPreviewTrust().getDerivedTrustStatus(), confirmedProfile.getTrustStatus());
		assertEquals(preview.getPreviewTrust().getDerivedTrustWeightPercent(), confirmedProfile.getTrustWeightPercent());
		assertNotNull(confirmedProfile.getSnapshotHeight());
		assertNotNull(confirmedProfile.getSnapshotTimestamp());

		AccountTrustExplanationData confirmedExplanation = this.accountRatingsResource
				.getAccountTrustExplanation(targetPublicKey58, null);
		assertFalse(confirmedExplanation.isLive());
		assertEquals(preview.getPreviewTrust().getDerivedTrustStatus(), confirmedExplanation.getTrustStatus());

		List<AccountRatingData> activeRatings = this.accountRatingsResource.getAccountRatings(targetPublicKey58,
				raterPublicKey58, AccountRatingCategory.SUBJECT.name(), null, null, null);
		assertEquals(1, activeRatings.size());
		assertEquals(4, activeRatings.get(0).getRating());
	}

	@Test
	public void testAccountRatingClientWorkflowRemovesRating() throws Exception {
		AccountTrustTestUtils.useAccountRatingCooldown(0);

		TestAccount alice;
		TestAccount bob;
		TestAccount chloe;
		TestAccount dilbert;
		PrivateKeyAccount target;
		PrivateKeyAccount firstPlayer;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			chloe = Common.getTestAccount(repository, "chloe");
			dilbert = Common.getTestAccount(repository, "dilbert");
			target = Common.generateRandomSeedAccount(repository);
			firstPlayer = Common.generateRandomSeedAccount(repository);

			createAuraTrustGraph(repository, alice, bob, chloe, dilbert);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, alice, firstPlayer);
			saveAccountRating(repository, firstPlayer, target, AccountRatingCategory.SUBJECT, 4);
			saveAccountRating(repository, dilbert, target, AccountRatingCategory.SUBJECT, 4);
			refreshTrustSnapshots(repository);
		}

		String targetPublicKey58 = Base58.encode(target.getPublicKey());
		String raterPublicKey58 = Base58.encode(dilbert.getPublicKey());

		AccountTrustProfileData currentProfile = this.accountRatingsResource.getAccountTrustProfile(targetPublicKey58);
		assertTrue(currentProfile.getTrustStatus() != AccountTrustStatus.UNVERIFIED);

		AccountRatingImpactPreviewData preview = this.accountRatingsResource.getAccountRatingImpactPreview(
				targetPublicKey58, raterPublicKey58, AccountRatingCategory.SUBJECT.name(), AccountRating.NO_RATING);
		assertEquals(Transaction.ValidationResult.OK.name(), preview.getValidationResult());
		assertTrue(preview.isCanSubmit());
		assertEquals(Integer.valueOf(4), preview.getActiveRating());
		assertNull(preview.getPreviewActiveRating());
		assertEquals(currentProfile.getTrustStatus(), preview.getCurrentTrust().getDerivedTrustStatus());
		assertEquals(AccountTrustStatus.UNVERIFIED, preview.getPreviewTrust().getDerivedTrustStatus());
		assertTrue(preview.isTrustStatusChanged());

		RateAccountTransactionData removalTransactionData = ratingData(dilbert, target,
				AccountRatingCategory.SUBJECT, AccountRating.NO_RATING);
		String rawUnsignedTransaction = this.accountRatingsResource.rateAccount(removalTransactionData);
		TransactionConfirmationTimingData timing = this.transactionsResource
				.getTransactionConfirmationTiming(rawUnsignedTransaction);
		assertEquals(Transaction.TransactionType.RATE_ACCOUNT, timing.getTransactionType());
		assertTrue(timing.isTransactionConfirmable());
		assertTrue(timing.isConfirmableAtCandidateHeight());

		String signedTransaction = signTransaction(dilbert, rawUnsignedTransaction);
		importSignedTransactionAndMint(signedTransaction);

		AccountTrustProfileData confirmedProfile = this.accountRatingsResource.getAccountTrustProfile(targetPublicKey58);
		assertEquals(AccountTrustStatus.UNVERIFIED, confirmedProfile.getTrustStatus());
		assertNotNull(confirmedProfile.getSnapshotHeight());

		List<AccountRatingData> removedRatings = this.accountRatingsResource.getAccountRatings(targetPublicKey58,
				raterPublicKey58, AccountRatingCategory.SUBJECT.name(), null, null, null);
		assertTrue(removedRatings.isEmpty());

		List<AccountRatingData> remainingRatings = this.accountRatingsResource.getAccountRatings(targetPublicKey58,
				Base58.encode(firstPlayer.getPublicKey()), AccountRatingCategory.SUBJECT.name(), null, null, null);
		assertEquals(1, remainingRatings.size());
		assertEquals(4, remainingRatings.get(0).getRating());
	}

	@Test
	public void testMissingTargetFailsSummary() {
		PrivateKeyAccount unknown = Common.generateRandomSeedAccount(null);
		TestAccount alice = Common.getTestAccount(null, "alice");

		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountRatingSummary(Base58.encode(unknown.getPublicKey())));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountTrustExplanation(Base58.encode(unknown.getPublicKey()), null));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountTrustProfile(Base58.encode(unknown.getPublicKey())));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountTrustProfile(null));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountRatingCooldown(Base58.encode(unknown.getPublicKey()),
						Base58.encode(alice.getPublicKey()), null));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountRatingCooldown(null, Base58.encode(alice.getPublicKey()), null));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountRatingImpactPreview(null,
						Base58.encode(alice.getPublicKey()), null, 4));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountRatingImpactPreview(Base58.encode(unknown.getPublicKey()),
						null, null, 4));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountRatingImpactPreview(Base58.encode(unknown.getPublicKey()),
						Base58.encode(alice.getPublicKey()), null, null));
	}

	@Test
	public void testInvalidPublicKeyFailsList() {
		TestAccount alice = Common.getTestAccount(null, "alice");
		TestAccount bob = Common.getTestAccount(null, "bob");

		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountRatings("not-a-public-key", null, null, null, null));
		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountTrustExplanation("not-a-public-key", null));
		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountTrustProfile("not-a-public-key"));
		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountRatingCooldown("not-a-public-key",
						Base58.encode(alice.getPublicKey()), null));
		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountRatingCooldown(Base58.encode(bob.getPublicKey()),
						"not-a-public-key", null));
		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountRatingImpactPreview("not-a-public-key",
						Base58.encode(alice.getPublicKey()), null, 4));
		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountRatingImpactPreview(Base58.encode(bob.getPublicKey()),
						"not-a-public-key", null, 4));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountRatingCooldown(Base58.encode(bob.getPublicKey()), null, null));
	}

	@Test
	public void testInvalidCategoryFailsList() {
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountRatings(null, null, "not-a-category", null, null, null));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountTrustDerivation(null, "not-a-category", null, null, null, null, null));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountTrustSnapshots(null, "not-a-category", null, null, null, null, null, null));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountRatingCooldown(Base58.encode(Common.getTestAccount(null, "bob").getPublicKey()),
						Base58.encode(Common.getTestAccount(null, "alice").getPublicKey()), "not-a-category"));
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountRatingImpactPreview(
						Base58.encode(Common.getTestAccount(null, "bob").getPublicKey()),
						Base58.encode(Common.getTestAccount(null, "alice").getPublicKey()), "not-a-category", 4));
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

	private String signTransaction(PrivateKeyAccount signer, String rawUnsignedTransaction) {
		SimpleTransactionSignRequest signRequest = new SimpleTransactionSignRequest();
		signRequest.privateKey = signer.getPrivateKey();
		signRequest.transactionBytes = Base58.decode(rawUnsignedTransaction);

		return this.transactionsResource.signTransaction(signRequest);
	}

	private void importSignedTransactionAndMint(String signedTransaction)
			throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TransactionData transactionData = TransactionTransformer.fromBytes(Base58.decode(signedTransaction));
			assertNotNull(transactionData);

			Transaction transaction = Transaction.fromData(repository, transactionData);
			assertTrue("Transaction's signature should be valid", transaction.isSignatureValid());

			try {
				Thread.sleep(1L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			assertEquals(Transaction.ValidationResult.OK, transaction.importAsUnconfirmed());
			BlockUtils.mintBlock(repository);
		}
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

	private AccountTrustSummaryData.RatingCategorySummary findRatingCategorySummary(AccountTrustSummaryData summary,
			AccountRatingCategory category) {
		return summary.getRatingCategorySummaries().stream()
				.filter(categorySummary -> categorySummary.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing rating summary for category " + category));
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

	private void replaceTrustSnapshots(Repository repository, int snapshotHeight, long snapshotTimestamp,
			AccountTrustDerivationData... derivationData) throws DataException {
		repository.getAccountRatingRepository().replaceTrustDerivationSnapshots(Arrays.asList(derivationData),
				snapshotHeight, snapshotTimestamp);
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

	private AccountTrustDerivationData completeTrustDerivation(PrivateKeyAccount account, boolean seedMember,
			AccountTrustStatus subjectStatus) {
		return trustDerivation(account, seedMember,
				categoryTrust(AccountRatingCategory.SUBJECT, subjectStatus),
				categoryTrust(AccountRatingCategory.PLAYER, AccountTrustStatus.UNVERIFIED),
				categoryTrust(AccountRatingCategory.TRAINER, AccountTrustStatus.UNVERIFIED),
				categoryTrust(AccountRatingCategory.MANAGER, AccountTrustStatus.UNVERIFIED));
	}

	private AccountTrustCategoryData categoryTrust(AccountRatingCategory category, AccountTrustStatus trustStatus) {
		return new AccountTrustCategoryData(category, scoreForStatus(trustStatus), levelForStatus(trustStatus),
				trustStatus, new AccountTrustRatingCountsData(), Collections.emptyList());
	}

	private AccountTrustCategoryData categoryTrust(AccountRatingCategory category, AccountTrustStatus trustStatus,
			long score) {
		return new AccountTrustCategoryData(category, score, levelForStatus(trustStatus), trustStatus,
				new AccountTrustRatingCountsData(), Collections.emptyList());
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

	private void approveTrustStatusVoteWeightsOverlay(Repository repository, int[] voteWeights) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		int activationHeight = getActivationHeightSafelyAfterApproval(repository, 1);
			ChainParameterUpdateTransactionData transactionData = new ChainParameterUpdateTransactionData(
					TestTransaction.generateBase(alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
					ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.id, activationHeight,
					ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.encodeIntArrayValue(voteWeights));

		TransactionUtils.signAndMint(repository, transactionData, alice);
		GroupUtils.approveTransaction(repository, "alice", transactionData.getSignature(), true);
		BlockUtils.mintBlocks(repository, getApprovalSettlementBlockCount(repository));
		BlockUtils.mintBlocks(repository, activationHeight - repository.getBlockRepository().getBlockchainHeight());
	}

	private void approveAccountTrustStartingEnergyOverlay(Repository repository, long startingEnergy) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		int activationHeight = getActivationHeightSafelyAfterApproval(repository, 1);
		ChainParameterUpdateTransactionData transactionData = new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.encodeLongValue(startingEnergy));

		TransactionUtils.signAndMint(repository, transactionData, alice);
		GroupUtils.approveTransaction(repository, "alice", transactionData.getSignature(), true);
		BlockUtils.mintBlocks(repository, getApprovalSettlementBlockCount(repository));
		BlockUtils.mintBlocks(repository, activationHeight - repository.getBlockRepository().getBlockchainHeight());
	}

	private void approveAccountTrustManagerEnergyHopsOverlay(Repository repository, int managerEnergyHops)
			throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		int activationHeight = getActivationHeightSafelyAfterApproval(repository, 1);
		ChainParameterUpdateTransactionData transactionData = new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.encodeIntValue(managerEnergyHops));

		TransactionUtils.signAndMint(repository, transactionData, alice);
		GroupUtils.approveTransaction(repository, "alice", transactionData.getSignature(), true);
		BlockUtils.mintBlocks(repository, getApprovalSettlementBlockCount(repository));
		BlockUtils.mintBlocks(repository, activationHeight - repository.getBlockRepository().getBlockchainHeight());
	}

	private void approveAccountTrustPositiveMinBranchCountOverlay(Repository repository, int positiveMinBranchCount)
			throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		int activationHeight = getActivationHeightSafelyAfterApproval(repository, 1);
		ChainParameterUpdateTransactionData transactionData = new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT.encodeIntValue(positiveMinBranchCount));

		TransactionUtils.signAndMint(repository, transactionData, alice);
		GroupUtils.approveTransaction(repository, "alice", transactionData.getSignature(), true);
		BlockUtils.mintBlocks(repository, getApprovalSettlementBlockCount(repository));
		BlockUtils.mintBlocks(repository, activationHeight - repository.getBlockRepository().getBlockchainHeight());
	}

	private void approveAccountTrustSuspiciousMinRaterCountOverlay(Repository repository, int suspiciousMinRaterCount)
			throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		int activationHeight = getActivationHeightSafelyAfterApproval(repository, 1);
		ChainParameterUpdateTransactionData transactionData = new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT.encodeIntValue(suspiciousMinRaterCount));

		TransactionUtils.signAndMint(repository, transactionData, alice);
		GroupUtils.approveTransaction(repository, "alice", transactionData.getSignature(), true);
		BlockUtils.mintBlocks(repository, getApprovalSettlementBlockCount(repository));
		BlockUtils.mintBlocks(repository, activationHeight - repository.getBlockRepository().getBlockchainHeight());
	}

	private void approveAccountTrustSuspiciousMinBranchCountOverlay(Repository repository, int suspiciousMinBranchCount)
			throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		int activationHeight = getActivationHeightSafelyAfterApproval(repository, 1);
		ChainParameterUpdateTransactionData transactionData = new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT.encodeIntValue(suspiciousMinBranchCount));

		TransactionUtils.signAndMint(repository, transactionData, alice);
		GroupUtils.approveTransaction(repository, "alice", transactionData.getSignature(), true);
		BlockUtils.mintBlocks(repository, getApprovalSettlementBlockCount(repository));
		BlockUtils.mintBlocks(repository, activationHeight - repository.getBlockRepository().getBlockchainHeight());
	}

	private void approveAccountTrustSuspiciousMinRatingConfidenceOverlay(Repository repository,
			int suspiciousMinRatingConfidence) throws DataException {
		PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
		int activationHeight = getActivationHeightSafelyAfterApproval(repository, 1);
		ChainParameterUpdateTransactionData transactionData = new ChainParameterUpdateTransactionData(
				TestTransaction.generateBase(alice, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE.id, activationHeight,
				ChainParameter.ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE.encodeIntValue(
						suspiciousMinRatingConfidence));

		TransactionUtils.signAndMint(repository, transactionData, alice);
		GroupUtils.approveTransaction(repository, "alice", transactionData.getSignature(), true);
		BlockUtils.mintBlocks(repository, getApprovalSettlementBlockCount(repository));
		BlockUtils.mintBlocks(repository, activationHeight - repository.getBlockRepository().getBlockchainHeight());
	}

	private int getApprovalSettlementBlockCount(Repository repository) throws DataException {
		GroupData groupData = repository.getGroupRepository().fromGroupId(TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID);
		return Math.max(2, groupData.getMinimumBlockDelay() + 1);
	}

	private int getActivationHeightSafelyAfterApproval(Repository repository, int extraBlocks) throws DataException {
		return repository.getBlockRepository().getBlockchainHeight()
				+ getApprovalSettlementBlockCount(repository)
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay()
				+ extraBlocks;
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
