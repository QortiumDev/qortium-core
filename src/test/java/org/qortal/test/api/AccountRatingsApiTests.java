package org.qortal.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.ApiError;
import org.qortal.api.resource.AccountRatingsResource;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountRating;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingSummaryData;
import org.qortal.data.account.AccountTrustPreviewData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.transaction.RateAccountTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.utils.Base58;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
		String dilbertAddress;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			aliceAddress = alice.getAddress();
			bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");
			dilbertAddress = dilbert.getAddress();

			setVoteAccount(repository, alice, 100, AccountTrustStatus.GOLD);
			setVoteAccount(repository, chloe, 101, AccountTrustStatus.SILVER);
			setVoteAccount(repository, dilbert, 101, AccountTrustStatus.BRONZE);

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, 4), alice);
			TransactionUtils.signAndMint(repository, ratingData(chloe, bob, 2), chloe);
			TransactionUtils.signAndMint(repository, ratingData(dilbert, bob, -3), dilbert);
			TransactionUtils.signAndMint(repository, ratingData(bob, alice, 1), bob);
			TransactionUtils.signAndMint(repository, ratingData(bob, chloe, 4), bob);
			TransactionUtils.signAndMint(repository, ratingData(bob, dilbert, -2), bob);
		}

		AccountTrustPreviewData preview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(bob.getPublicKey()));

		assertEquals(1, preview.getInboundRatings().getPositiveVeryHighCount());
		assertEquals(1, preview.getInboundRatings().getPositiveMediumCount());
		assertEquals(1, preview.getInboundRatings().getNegativeHighCount());
		assertEquals(3, preview.getInboundTotalRatingCount());
		assertEquals(1, preview.getOutboundRatings().getPositiveLowCount());
		assertEquals(1, preview.getOutboundRatings().getPositiveVeryHighCount());
		assertEquals(1, preview.getOutboundRatings().getNegativeMediumCount());
		assertEquals(3, preview.getOutboundTotalRatingCount());
		assertEquals(2, preview.getMutualPositiveCount());
		assertEquals(500, preview.getPositiveScore());
		assertEquals(300, preview.getNegativeScore());
		assertEquals(200, preview.getNetScore());

		assertEquals(3, preview.getEvaluatorImpacts().size());
		AccountTrustPreviewData.EvaluatorImpact aliceImpact = findEvaluatorImpact(preview, aliceAddress);
		assertEquals(4, aliceImpact.getRating());
		assertEquals(4, aliceImpact.getRatingConfidence());
		assertEquals(AccountTrustStatus.GOLD, aliceImpact.getTrustStatus());
		assertEquals(100, aliceImpact.getRawVoteWeight());
		assertEquals(100, aliceImpact.getEffectiveVoteWeight());
		assertEquals(400, aliceImpact.getImpact());

		AccountTrustPreviewData.EvaluatorImpact dilbertImpact = findEvaluatorImpact(preview, dilbertAddress);
		assertEquals(-3, dilbertImpact.getRating());
		assertEquals("NEGATIVE", dilbertImpact.getRatingDirection());
		assertEquals(25, dilbertImpact.getEffectiveVoteWeight());
		assertEquals(-300, dilbertImpact.getImpact());
	}

	@Test
	public void testTrustPreviewDoesNotChangeTrustStatusOrVoteWeight() throws DataException {
		TestAccount bob;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			setVoteAccount(repository, alice, 100, AccountTrustStatus.GOLD);
			setVoteAccount(repository, chloe, 100, AccountTrustStatus.GOLD);
			TransactionUtils.signAndMint(repository, ratingData(alice, bob, 4), alice);
			TransactionUtils.signAndMint(repository, ratingData(bob, alice, 4), bob);
			TransactionUtils.signAndMint(repository, ratingData(chloe, bob, 4), chloe);
			TransactionUtils.signAndMint(repository, ratingData(bob, chloe, 4), bob);

			setVoteAccount(repository, bob, 100, AccountTrustStatus.UNVERIFIED);
		}

		AccountTrustPreviewData preview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(bob.getPublicKey()));
		assertTrue(preview.getPositiveScore() > 0);
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

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4), alice);
			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.PLAYER, -2), alice);
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

		AccountRatingSummaryData subjectSummary = this.accountRatingsResource.getAccountRatingSummary(targetPublicKey58,
				AccountRatingCategory.SUBJECT.name());
		AccountRatingSummaryData playerSummary = this.accountRatingsResource.getAccountRatingSummary(targetPublicKey58,
				AccountRatingCategory.PLAYER.name());
		assertEquals(1, subjectSummary.getPositiveRatingCount());
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

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.MANAGER, 4), alice);
			TransactionUtils.signAndMint(repository, ratingData(bob, chloe, AccountRatingCategory.TRAINER, 4), bob);
			TransactionUtils.signAndMint(repository, ratingData(chloe, dilbert, AccountRatingCategory.PLAYER, 4), chloe);
			TransactionUtils.signAndMint(repository, ratingData(dilbert, alice, AccountRatingCategory.SUBJECT, 4), dilbert);
		}

		AccountTrustPreviewData bobPreview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(bob.getPublicKey()));
		AccountTrustPreviewData chloePreview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(chloe.getPublicKey()));
		AccountTrustPreviewData dilbertPreview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(dilbert.getPublicKey()));
		AccountTrustPreviewData alicePreview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(alice.getPublicKey()));

		AccountTrustPreviewData.CategoryTrust bobManager = findCategory(bobPreview, AccountRatingCategory.MANAGER);
		assertEquals(1_000_000L, bobManager.getScore());
		assertEquals(2, bobManager.getLevel());

		AccountTrustPreviewData.CategoryTrust chloeTrainer = findCategory(chloePreview, AccountRatingCategory.TRAINER);
		assertEquals(4_000_000L, chloeTrainer.getScore());
		assertEquals(2, chloeTrainer.getLevel());

		AccountTrustPreviewData.CategoryTrust dilbertPlayer = findCategory(dilbertPreview, AccountRatingCategory.PLAYER);
		assertEquals(16_000_000L, dilbertPlayer.getScore());
		assertEquals(3, dilbertPlayer.getLevel());

		AccountTrustPreviewData.CategoryTrust aliceSubject = findCategory(alicePreview, AccountRatingCategory.SUBJECT);
		assertTrue(alicePreview.isMintingSeedMember());
		assertEquals(64_000_000L, aliceSubject.getScore());
		assertEquals(2, aliceSubject.getLevel());
		assertEquals(AccountTrustStatus.SILVER, alicePreview.getDerivedTrustStatus());
		assertEquals(AccountTrustStatus.UNVERIFIED, alicePreview.getTrustStatus());

		AccountTrustPreviewData.CategoryImpact subjectImpact = aliceSubject.getImpacts().get(0);
		assertEquals(dilbert.getAddress(), subjectImpact.getRaterAddress());
		assertEquals(3, subjectImpact.getEvaluatorLevel());
		assertEquals(16_000_000L, subjectImpact.getEvaluatorScore());
		assertEquals(64_000_000L, subjectImpact.getImpact());

		try (final Repository repository = RepositoryManager.getRepository()) {
			TransactionUtils.signAndMint(repository, ratingData(dilbert, alice, AccountRatingCategory.SUBJECT, -1), dilbert);
		}

		AccountTrustPreviewData negativePreview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(alice.getPublicKey()));
		AccountTrustPreviewData.CategoryTrust negativeSubject = findCategory(negativePreview, AccountRatingCategory.SUBJECT);
		assertEquals(-64_000_000L, negativeSubject.getScore());
		assertEquals(-1, negativeSubject.getLevel());
		assertEquals(AccountTrustStatus.SUSPICIOUS, negativePreview.getDerivedTrustStatus());
		assertEquals(-64_000_000L, negativeSubject.getImpacts().get(0).getImpact());
	}

	@Test
	public void testManagerSeedEnergySplitsAcrossPositiveManagerRatings() throws DataException {
		TestAccount bob;
		TestAccount chloe;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			bob = Common.getTestAccount(repository, "bob");
			chloe = Common.getTestAccount(repository, "chloe");

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.MANAGER, 4), alice);
			TransactionUtils.signAndMint(repository, ratingData(alice, chloe, AccountRatingCategory.MANAGER, 4), alice);
		}

		AccountTrustPreviewData bobPreview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(bob.getPublicKey()));
		AccountTrustPreviewData chloePreview = this.accountRatingsResource.getAccountTrustPreview(Base58.encode(chloe.getPublicKey()));

		AccountTrustPreviewData.CategoryTrust bobManager = findCategory(bobPreview, AccountRatingCategory.MANAGER);
		AccountTrustPreviewData.CategoryTrust chloeManager = findCategory(chloePreview, AccountRatingCategory.MANAGER);

		assertEquals(500_000L, bobManager.getScore());
		assertEquals(500_000L, bobManager.getImpacts().get(0).getImpact());
		assertEquals(500_000L, chloeManager.getScore());
		assertEquals(500_000L, chloeManager.getImpacts().get(0).getImpact());
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
	}

	@Test
	public void testInvalidPublicKeyFailsList() {
		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountRatings("not-a-public-key", null, null, null, null));
		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountTrustPreview("not-a-public-key"));
	}

	@Test
	public void testInvalidCategoryFailsList() {
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountRatings(null, null, "not-a-category", null, null, null));
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

	private AccountTrustPreviewData.CategoryTrust findCategory(AccountTrustPreviewData preview, AccountRatingCategory category) {
		return preview.getCategories().stream()
				.filter(categoryTrust -> categoryTrust.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing category " + category));
	}

	private void setVoteAccount(Repository repository, TestAccount account, int blocksMinted, AccountTrustStatus trustStatus) throws DataException {
		AccountData accountData = repository.getAccountRepository().getAccount(account.getAddress());
		if (accountData == null)
			accountData = new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, blocksMinted);
		else
			accountData.setBlocksMinted(blocksMinted);

		repository.getAccountRepository().setMintedBlockCount(accountData);
		repository.getAccountRepository().setTrustStatus(account.getAddress(), trustStatus);
		repository.saveChanges();
	}
}
