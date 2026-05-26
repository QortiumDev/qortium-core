package org.qortium.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.arbitrary.misc.Service;
import org.qortium.block.BlockChain;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.rating.ResourceRatingData;
import org.qortium.data.rating.ResourceRatingDistributionData;
import org.qortium.data.rating.ResourceRatingSummaryData;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.RateResourceTransactionData;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.group.Group;
import org.qortium.rating.ResourceRating;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ArbitraryUtils;
import org.qortium.test.common.AccountTrustTestUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.utils.Base58;

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
			AccountTrustTestUtils.setBlocksMinted(repository, alice, 100);

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
			PrivateKeyAccount suspicious = Common.generateRandomSeedAccount(repository);

			AccountTrustTestUtils.setBlocksMinted(repository, alice, 100);
			AccountTrustTestUtils.setBlocksMinted(repository, bob, 100);
			AccountTrustTestUtils.setBlocksMinted(repository, chloe, 100);
			AccountTrustTestUtils.setBlocksMinted(repository, dilbert, 100);
			AccountTrustTestUtils.setBlocksMinted(repository, suspicious, 100);

			AccountTrustTestUtils.replaceSubjectTrustSnapshots(repository,
					AccountTrustTestUtils.subjectTrustSnapshot(alice, AccountTrustStatus.GOLD),
					AccountTrustTestUtils.subjectTrustSnapshot(bob, AccountTrustStatus.SILVER),
					AccountTrustTestUtils.subjectTrustSnapshot(chloe, AccountTrustStatus.BRONZE),
					AccountTrustTestUtils.subjectTrustSnapshot(dilbert, AccountTrustStatus.UNVERIFIED),
					AccountTrustTestUtils.subjectTrustSnapshot(suspicious, AccountTrustStatus.SUSPICIOUS));

			saveResourceRating(repository, alice, 10);
			saveResourceRating(repository, bob, 8);
			saveResourceRating(repository, chloe, 6);
			saveResourceRating(repository, dilbert, 4);
			saveResourceRating(repository, suspicious, 2);

			ResourceRatingSummaryData summary = repository.getResourceRatingRepository()
					.getRatingSummary(Service.APP, ResourceRating.toNameKey(RESOURCE_NAME), RESOURCE_NAME, ResourceRating.toIdentifierKey(IDENTIFIER));

			assertEquals(5, summary.getRatingCount());
			assertEquals(30L, summary.getRatingTotal());
			assertEquals(Long.valueOf(500L), summary.getRawTotalWeight());
			assertEquals(Long.valueOf(210L), summary.getTotalWeight());
			assertEquals(6.0d, summary.getAverageRating(), 0.0000001d);
			assertEquals(6.0d, summary.getRawWeightedAverageRating(), 0.0000001d);
			assertEquals(1800.0d / 210.0d, summary.getWeightedAverageRating(), 0.0000001d);
			assertEquals(1, distributionFor(summary, 2).getRatingCount());
			assertEquals(100L, distributionFor(summary, 2).getRawRatingWeight());
			assertEquals(0L, distributionFor(summary, 2).getRatingWeight());
			assertEquals(1, distributionFor(summary, 4).getRatingCount());
			assertEquals(100L, distributionFor(summary, 4).getRawRatingWeight());
			assertEquals(0L, distributionFor(summary, 4).getRatingWeight());
			assertEquals(1, distributionFor(summary, 6).getRatingCount());
			assertEquals(100L, distributionFor(summary, 6).getRawRatingWeight());
			assertEquals(40L, distributionFor(summary, 6).getRatingWeight());
			assertEquals(1, distributionFor(summary, 8).getRatingCount());
			assertEquals(100L, distributionFor(summary, 8).getRawRatingWeight());
			assertEquals(70L, distributionFor(summary, 8).getRatingWeight());
			assertEquals(1, distributionFor(summary, 10).getRatingCount());
			assertEquals(100L, distributionFor(summary, 10).getRawRatingWeight());
			assertEquals(100L, distributionFor(summary, 10).getRatingWeight());
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
		assertNull(summary.getAverageRating());
		assertNull(summary.getRawWeightedAverageRating());
		assertNull(summary.getWeightedAverageRating());
	}

	private ResourceRatingDistributionData distributionFor(ResourceRatingSummaryData summary, int rating) {
		return summary.getRatingDistribution().stream()
				.filter(distribution -> distribution.getRating() == rating)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing distribution for rating " + rating));
	}

	private void saveResourceRating(Repository repository, PrivateKeyAccount rater, int rating) throws DataException {
		repository.getResourceRatingRepository().save(new ResourceRatingData(Service.APP,
				ResourceRating.toNameKey(RESOURCE_NAME), RESOURCE_NAME, ResourceRating.toIdentifierKey(IDENTIFIER),
				rater.getPublicKey(), rating));
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
