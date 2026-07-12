package org.qortium.test.api;

import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.MarshallerProperties;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.ApiError;
import org.qortium.api.resource.ResourceRatingsResource;
import org.qortium.arbitrary.misc.Service;
import org.qortium.block.BlockChain;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.rating.ResourceRatingData;
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
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.AccountTrustTestUtils;
import org.qortium.test.common.ArbitraryUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.utils.Base58;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

			AccountTrustTestUtils.setBlocksMinted(repository, alice, 100);
			AccountTrustTestUtils.setBlocksMinted(repository, bob, 101);
			AccountTrustTestUtils.replaceSubjectTrustSnapshots(repository,
					AccountTrustTestUtils.subjectTrustSnapshot(alice, AccountTrustStatus.SILVER),
					AccountTrustTestUtils.subjectTrustSnapshot(bob, AccountTrustStatus.UNVERIFIED));

			TransactionUtils.signAndMint(repository, ratingData(alice, 10), alice);
			TransactionUtils.signAndMint(repository, ratingData(bob, 6), bob);
		}

		ResourceRatingSummaryData summary = this.resourceRatingsResource.getResourceRatingSummary("app", RESOURCE_NAME, null);
		assertEquals(Service.APP, summary.getService());
		assertEquals(RESOURCE_NAME, summary.getName());
		assertEquals(IDENTIFIER, summary.getIdentifier());
		assertEquals(2, summary.getRatingCount());
		assertEquals(Long.valueOf(203L), summary.getRawTotalWeight());
		assertEquals(Long.valueOf(71L), summary.getTotalWeight());
		assertEquals(1626.0d / 203.0d, summary.getRawWeightedAverageRating(), 0.0000001d);
		assertEquals(10.0d, summary.getWeightedAverageRating(), 0.0000001d);

		List<ResourceRatingSummaryData> summaries = this.resourceRatingsResource.getResourceRatings("APP", RESOURCE_NAME, null, null, null, null);
		assertEquals(1, summaries.size());
		assertEquals(RESOURCE_NAME, summaries.get(0).getName());
		assertEquals(Long.valueOf(71L), summaries.get(0).getTotalWeight());
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
	public void testRatingEndpointSerializesExistingRating() throws DataException, JAXBException {
		TestAccount chloe;

		try (final Repository repository = RepositoryManager.getRepository()) {
			publishResource(repository, RESOURCE_NAME, Service.APP, null);
			chloe = Common.getTestAccount(repository, "chloe");
			TransactionUtils.signAndMint(repository, ratingData(chloe, 7), chloe);
		}

		ResourceRatingData rating = this.resourceRatingsResource.getResourceRating(
				"APP", RESOURCE_NAME, null, chloe.getAddress());
		assertEquals(Service.APP, rating.getService());
		assertEquals(RESOURCE_NAME, rating.getName());
		assertEquals(IDENTIFIER, rating.getIdentifier());
		assertEquals(chloe.getAddress(), rating.getRaterAddress());
		assertEquals(7, rating.getRating());

		String json = marshalRating(rating);
		assertTrue(json.contains("\"raterAddress\":\"" + chloe.getAddress() + "\""));
		assertTrue(json.contains("\"rating\":7"));
	}

	@Test
	public void testMissingResourceFailsSummary() {
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.resourceRatingsResource.getResourceRatingSummary("APP", "missing-resource", null));
	}

	private RateResourceTransactionData ratingData(PrivateKeyAccount rater, int rating) throws DataException {
		return new RateResourceTransactionData(TestTransaction.generateBase(rater), Service.APP.value, RESOURCE_NAME, null, rating);
	}

	private static String marshalRating(ResourceRatingData rating) throws JAXBException {
		JAXBContext context = JAXBContextFactory.createContext(new Class[] {ResourceRatingData.class}, null);
		Marshaller marshaller = context.createMarshaller();
		marshaller.setProperty(MarshallerProperties.MEDIA_TYPE, "application/json");
		marshaller.setProperty(MarshallerProperties.JSON_INCLUDE_ROOT, false);

		StringWriter writer = new StringWriter();
		marshaller.marshal(rating, writer);
		return writer.toString();
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
