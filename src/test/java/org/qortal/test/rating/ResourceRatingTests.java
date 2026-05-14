package org.qortal.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.AccountTrustDerivation;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.arbitrary.misc.Service;
import org.qortal.block.BlockChain;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.rating.ResourceRatingData;
import org.qortal.data.rating.ResourceRatingDistributionData;
import org.qortal.data.rating.ResourceRatingSummaryData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.RateResourceTransactionData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.group.Group;
import org.qortal.rating.ResourceRating;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.ArbitraryUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Base58;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ResourceRatingTests extends Common {

	private static final String RESOURCE_NAME = "rating-test-resource";
	private static final String IDENTIFIER = "main";

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testRatingValidation() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			publishResource(repository, RESOURCE_NAME, Service.APP, IDENTIFIER);

			TestAccount alice = Common.getTestAccount(repository, "alice");
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, 1)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, 10)).isValid());
			assertEquals(Transaction.ValidationResult.INVALID_RATING,
					Transaction.fromData(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, -1)).isValid());
			assertEquals(Transaction.ValidationResult.INVALID_RATING,
					Transaction.fromData(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, 11)).isValid());
			assertEquals(Transaction.ValidationResult.ALREADY_RATED_RESOURCE,
					Transaction.fromData(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, ResourceRating.NO_RATING)).isValid());
			assertEquals(Transaction.ValidationResult.INVALID_RESOURCE,
					Transaction.fromData(repository, ratingData(alice, Service.ARBITRARY_DATA, RESOURCE_NAME, IDENTIFIER, 5)).isValid());
			assertEquals(Transaction.ValidationResult.RESOURCE_DOES_NOT_EXIST,
					Transaction.fromData(repository, ratingData(alice, Service.APP, "missing-resource", IDENTIFIER, 5)).isValid());

			TransactionUtils.signAndMint(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, 7), alice);
			assertEquals(Transaction.ValidationResult.ALREADY_RATED_RESOURCE,
					Transaction.fromData(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, 7)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, 8)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, ResourceRating.NO_RATING)).isValid());
		}
	}

	@Test
	public void testProcessReplaceAndOrphan() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			publishResource(repository, RESOURCE_NAME, Service.APP, IDENTIFIER);

			TestAccount alice = Common.getTestAccount(repository, "alice");
			TransactionUtils.signAndMint(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, 4), alice);
			assertActiveRating(repository, alice, 4);

			TransactionUtils.signAndMint(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, 8), alice);
			assertActiveRating(repository, alice, 8);

			TransactionUtils.signAndMint(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, ResourceRating.NO_RATING), alice);
			assertNull(repository.getResourceRatingRepository().getRating(Service.APP, ResourceRating.toNameKey(RESOURCE_NAME),
					ResourceRating.toIdentifierKey(IDENTIFIER), alice.getPublicKey()));
			assertEmptySummary(repository);

			BlockUtils.orphanLastBlock(repository);
			assertActiveRating(repository, alice, 8);

			BlockUtils.orphanLastBlock(repository);
			assertActiveRating(repository, alice, 4);

			BlockUtils.orphanLastBlock(repository);
			assertNull(repository.getResourceRatingRepository().getRating(Service.APP, ResourceRating.toNameKey(RESOURCE_NAME),
					ResourceRating.toIdentifierKey(IDENTIFIER), alice.getPublicKey()));
		}
	}

	@Test
	public void testRemoveRatingUpdatesSummaryAndCanBeRepeatedAfterNewRating() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			publishResource(repository, RESOURCE_NAME, Service.APP, IDENTIFIER);

			TestAccount alice = Common.getTestAccount(repository, "alice");
			setVoteAccount(repository, alice, 100, AccountTrustStatus.GOLD);

			TransactionUtils.signAndMint(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, 9), alice);
			ResourceRatingSummaryData summary = repository.getResourceRatingRepository()
					.getRatingSummary(Service.APP, ResourceRating.toNameKey(RESOURCE_NAME), RESOURCE_NAME, ResourceRating.toIdentifierKey(IDENTIFIER));
			assertEquals(1, summary.getRatingCount());
			assertEquals(9L, summary.getRatingTotal());
			assertEquals(1, distributionFor(summary, 9).getRatingCount());

			TransactionUtils.signAndMint(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, ResourceRating.NO_RATING), alice);
			assertEmptySummary(repository);
			assertEquals(Transaction.ValidationResult.ALREADY_RATED_RESOURCE,
					Transaction.fromData(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, ResourceRating.NO_RATING)).isValid());

			TransactionUtils.signAndMint(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, 6), alice);
			assertActiveRating(repository, alice, 6);
		}
	}

	@Test
	public void testSummaryUsesTrustWeightedBlocksMinted() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			publishResource(repository, RESOURCE_NAME, Service.APP, IDENTIFIER);

			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");
			createDerivedSilverSubjectSnapshot(repository, alice, bob, chloe, dilbert);

			setVoteAccount(repository, alice, 100, AccountTrustStatus.GOLD);
			setVoteAccount(repository, bob, 101, AccountTrustStatus.SILVER);
			setVoteAccount(repository, chloe, 101, AccountTrustStatus.BRONZE);

			TransactionUtils.signAndMint(repository, ratingData(alice, Service.APP, RESOURCE_NAME, IDENTIFIER, 10), alice);
			TransactionUtils.signAndMint(repository, ratingData(bob, Service.APP, RESOURCE_NAME, IDENTIFIER, 6), bob);
			TransactionUtils.signAndMint(repository, ratingData(chloe, Service.APP, RESOURCE_NAME, IDENTIFIER, 1), chloe);

			ResourceRatingSummaryData summary = repository.getResourceRatingRepository()
					.getRatingSummary(Service.APP, ResourceRating.toNameKey(RESOURCE_NAME), RESOURCE_NAME, ResourceRating.toIdentifierKey(IDENTIFIER));

			assertEquals(3, summary.getRatingCount());
			assertEquals(17L, summary.getRatingTotal());
			assertEquals(Long.valueOf(305L), summary.getRawTotalWeight());
			assertEquals(Long.valueOf(51L), summary.getTotalWeight());
			assertEquals(Long.valueOf(51L), summary.getDerivedTotalWeight());
			assertEquals(Long.valueOf(178L), summary.getStoredTotalWeight());
			assertEquals(17.0d / 3.0d, summary.getAverageRating(), 0.0000001d);
			assertEquals(1737.0d / 305.0d, summary.getRawWeightedAverageRating(), 0.0000001d);
			assertEquals(10.0d, summary.getWeightedAverageRating(), 0.0000001d);
			assertEquals(10.0d, summary.getDerivedWeightedAverageRating(), 0.0000001d);
			assertEquals(1355.0d / 178.0d, summary.getStoredWeightedAverageRating(), 0.0000001d);
			assertEquals(1, distributionFor(summary, 1).getRatingCount());
			assertEquals(101L, distributionFor(summary, 1).getRawRatingWeight());
			assertEquals(0L, distributionFor(summary, 1).getRatingWeight());
			assertEquals(0L, distributionFor(summary, 1).getDerivedRatingWeight());
			assertEquals(25L, distributionFor(summary, 1).getStoredRatingWeight());
			assertEquals(1, distributionFor(summary, 6).getRatingCount());
			assertEquals(101L, distributionFor(summary, 6).getRawRatingWeight());
			assertEquals(0L, distributionFor(summary, 6).getRatingWeight());
			assertEquals(0L, distributionFor(summary, 6).getDerivedRatingWeight());
			assertEquals(50L, distributionFor(summary, 6).getStoredRatingWeight());
			assertEquals(1, distributionFor(summary, 10).getRatingCount());
			assertEquals(103L, distributionFor(summary, 10).getRawRatingWeight());
			assertEquals(51L, distributionFor(summary, 10).getRatingWeight());
			assertEquals(51L, distributionFor(summary, 10).getDerivedRatingWeight());
			assertEquals(103L, distributionFor(summary, 10).getStoredRatingWeight());
		}
	}

	private RateResourceTransactionData ratingData(PrivateKeyAccount rater, Service service, String name, String identifier,
			int rating) throws DataException {
		return new RateResourceTransactionData(TestTransaction.generateBase(rater), service.value, name, identifier, rating);
	}

	private void assertActiveRating(Repository repository, PrivateKeyAccount rater, int expectedRating) throws DataException {
		ResourceRatingData activeRating = repository.getResourceRatingRepository().getRating(Service.APP,
				ResourceRating.toNameKey(RESOURCE_NAME), ResourceRating.toIdentifierKey(IDENTIFIER), rater.getPublicKey());

		assertEquals(expectedRating, activeRating.getRating());
	}

	private void assertEmptySummary(Repository repository) throws DataException {
		ResourceRatingSummaryData summary = repository.getResourceRatingRepository()
				.getRatingSummary(Service.APP, ResourceRating.toNameKey(RESOURCE_NAME), RESOURCE_NAME, ResourceRating.toIdentifierKey(IDENTIFIER));

		assertEquals(0, summary.getRatingCount());
		assertEquals(0L, summary.getRatingTotal());
		assertEquals(Long.valueOf(0L), summary.getRawTotalWeight());
		assertEquals(Long.valueOf(0L), summary.getTotalWeight());
		assertEquals(Long.valueOf(0L), summary.getDerivedTotalWeight());
		assertEquals(Long.valueOf(0L), summary.getStoredTotalWeight());
		assertNull(summary.getAverageRating());
		assertNull(summary.getRawWeightedAverageRating());
		assertNull(summary.getWeightedAverageRating());
		assertNull(summary.getDerivedWeightedAverageRating());
		assertNull(summary.getStoredWeightedAverageRating());
	}

	private ResourceRatingDistributionData distributionFor(ResourceRatingSummaryData summary, int rating) {
		return summary.getRatingDistribution().stream()
				.filter(distribution -> distribution.getRating() == rating)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing distribution for rating " + rating));
	}

	private void createDerivedSilverSubjectSnapshot(Repository repository, TestAccount alice, TestAccount bob,
			TestAccount chloe, TestAccount dilbert) throws DataException {
		saveAccountRating(repository, alice, bob, AccountRatingCategory.MANAGER, 4);
		saveAccountRating(repository, bob, chloe, AccountRatingCategory.TRAINER, 4);
		saveAccountRating(repository, chloe, dilbert, AccountRatingCategory.PLAYER, 4);
		saveAccountRating(repository, dilbert, alice, AccountRatingCategory.SUBJECT, 4);
		AccountTrustDerivation.refreshSnapshots(repository, repository.getBlockRepository().getBlockchainHeight() + 1,
				repository.getBlockRepository().getLastBlock().getTimestamp());
		repository.saveChanges();
	}

	private void saveAccountRating(Repository repository, PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		repository.getAccountRatingRepository()
				.save(new AccountRatingData(target.getPublicKey(), rater.getPublicKey(), category, rating));
	}

	private void setVoteAccount(Repository repository, TestAccount account, int blocksMinted, AccountTrustStatus trustStatus) throws DataException {
		AccountData accountData = repository.getAccountRepository().getAccount(account.getAddress());
		if (accountData == null)
			accountData = new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, blocksMinted);

		accountData.setPublicKey(account.getPublicKey());
		accountData.setBlocksMinted(blocksMinted);

		repository.getAccountRepository().setMintedBlockCount(accountData);
		repository.getAccountRepository().setTrustStatus(account.getAddress(), trustStatus);
		repository.saveChanges();
	}

	private void publishResource(Repository repository, String name, Service service, String identifier) throws DataException {
		TestAccount publisher = Common.getTestAccount(repository, "alice");
		long timestamp = System.currentTimeMillis();
		long fee = BlockChain.getInstance().getNameRegistrationUnitFeeAtTimestamp(timestamp);
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, publisher.getPublicKey(), fee, null);
		RegisterNameTransactionData registerNameTransactionData = new RegisterNameTransactionData(baseTransactionData, name, "");
		TransactionUtils.signAndMint(repository, registerNameTransactionData, publisher);

		Path path = Paths.get("src/test/resources/arbitrary/demo1");
		ArbitraryUtils.createAndMintTxn(repository, Base58.encode(publisher.getPublicKey()), path, name, identifier,
				ArbitraryTransactionData.Method.PUT, service, publisher);
	}

}
