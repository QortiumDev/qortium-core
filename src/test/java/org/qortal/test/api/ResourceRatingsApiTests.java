package org.qortal.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.ApiError;
import org.qortal.api.resource.ResourceRatingsResource;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.rating.ResourceRatingSummaryData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.RateResourceTransactionData;
import org.qortal.data.transaction.RegisterNameTransactionData;
import org.qortal.group.Group;
import org.qortal.rating.ResourceRating;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.ArbitraryUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.utils.Base58;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class ResourceRatingsApiTests extends ApiCommon {

	private static final String RESOURCE_NAME = "resource-ratings-api-test";
	private static final String IDENTIFIER = "default";

	private ResourceRatingsResource resourceRatingsResource;

	@Before
	public void buildResource() {
		this.resourceRatingsResource = (ResourceRatingsResource) ApiCommon.buildResource(ResourceRatingsResource.class);
	}

	@Test
	public void testResource() {
		assertNotNull(this.resourceRatingsResource);
	}

	@Test
	public void testSummaryAndListEndpoints() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			publishResource(repository, RESOURCE_NAME, Service.APP, null);

			ResourceRatingSummaryData emptySummary = this.resourceRatingsResource.getResourceRatingSummary("APP", RESOURCE_NAME, null);
			assertEquals(0, emptySummary.getRatingCount());

			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			setVoteAccount(repository, alice, 100, AccountTrustStatus.GOLD);
			setVoteAccount(repository, bob, 101, AccountTrustStatus.SILVER);

			TransactionUtils.signAndMint(repository, ratingData(alice, 10), alice);
			TransactionUtils.signAndMint(repository, ratingData(bob, 6), bob);
		}

		ResourceRatingSummaryData summary = this.resourceRatingsResource.getResourceRatingSummary("app", RESOURCE_NAME, null);
		assertEquals(Service.APP, summary.getService());
		assertEquals(RESOURCE_NAME, summary.getName());
		assertEquals(IDENTIFIER, summary.getIdentifier());
		assertEquals(2, summary.getRatingCount());
		assertEquals(Long.valueOf(201L), summary.getRawTotalWeight());
		assertEquals(Long.valueOf(150L), summary.getTotalWeight());
		assertEquals(1606.0d / 201.0d, summary.getRawWeightedAverageRating(), 0.0000001d);
		assertEquals(1300.0d / 150.0d, summary.getWeightedAverageRating(), 0.0000001d);

		List<ResourceRatingSummaryData> summaries = this.resourceRatingsResource.getResourceRatings("APP", RESOURCE_NAME, null, null, null, null);
		assertEquals(1, summaries.size());
		assertEquals(RESOURCE_NAME, summaries.get(0).getName());
	}

	@Test
	public void testRateResourceEndpointBuildsUnsignedTransaction() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			publishResource(repository, RESOURCE_NAME, Service.APP, null);

			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			String rawTransaction = this.resourceRatingsResource.rateResource(ratingData(chloe, 7));

			assertNotNull(rawTransaction);
			assertFalse(rawTransaction.isEmpty());

			TransactionUtils.signAndMint(repository, ratingData(chloe, 7), chloe);
			String removalTransaction = this.resourceRatingsResource.rateResource(ratingData(chloe, ResourceRating.NO_RATING));

			assertNotNull(removalTransaction);
			assertFalse(removalTransaction.isEmpty());
		}
	}

	@Test
	public void testMissingResourceFailsSummary() {
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.resourceRatingsResource.getResourceRatingSummary("APP", "missing-resource", null));
	}

	private RateResourceTransactionData ratingData(PrivateKeyAccount rater, int rating) throws DataException {
		return new RateResourceTransactionData(TestTransaction.generateBase(rater), Service.APP.value, RESOURCE_NAME, null, rating);
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
		RegisterNameTransactionData registerNameTransactionData =
				new RegisterNameTransactionData(TestTransaction.generateBase(publisher), name, "");
		TransactionUtils.signAndMint(repository, registerNameTransactionData, publisher);

		Path path = Paths.get("src/test/resources/arbitrary/demo1");
		ArbitraryUtils.createAndMintTxn(repository, Base58.encode(publisher.getPublicKey()), path, name, identifier,
				ArbitraryTransactionData.Method.PUT, service, publisher);
	}

}
