package org.qortal.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.ApiError;
import org.qortal.api.resource.AccountRatingsResource;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingLevel;
import org.qortal.data.account.AccountRatingSummaryData;
import org.qortal.data.transaction.RateAccountTransactionData;
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

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingLevel.TRUSTED), alice);
			TransactionUtils.signAndMint(repository, ratingData(chloe, bob, AccountRatingLevel.KNOWN), chloe);
		}

		String targetPublicKey58 = Base58.encode(bob.getPublicKey());
		String raterPublicKey58 = Base58.encode(alice.getPublicKey());

		AccountRatingSummaryData summary = this.accountRatingsResource.getAccountRatingSummary(targetPublicKey58);
		assertEquals(1, summary.getTrustedCount());
		assertEquals(1, summary.getKnownCount());
		assertEquals(0, summary.getUntrustedCount());
		assertEquals(2, summary.getTotalRatingCount());

		List<AccountRatingData> ratings = this.accountRatingsResource.getAccountRatings(targetPublicKey58, null, null, null, null);
		assertEquals(2, ratings.size());

		List<AccountRatingData> filteredRatings = this.accountRatingsResource.getAccountRatings(targetPublicKey58, raterPublicKey58,
				null, null, null);
		assertEquals(1, filteredRatings.size());
		assertEquals(AccountRatingLevel.TRUSTED, filteredRatings.get(0).getRatingLevel());
	}

	@Test
	public void testRateAccountEndpointBuildsUnsignedTransaction() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			String rawTransaction = this.accountRatingsResource.rateAccount(ratingData(alice, bob, AccountRatingLevel.UNTRUSTED));

			assertNotNull(rawTransaction);
			assertFalse(rawTransaction.isEmpty());
		}
	}

	@Test
	public void testMissingTargetFailsSummary() {
		PrivateKeyAccount unknown = Common.generateRandomSeedAccount(null);

		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.accountRatingsResource.getAccountRatingSummary(Base58.encode(unknown.getPublicKey())));
	}

	@Test
	public void testInvalidPublicKeyFailsList() {
		assertApiError(ApiError.INVALID_PUBLIC_KEY,
				() -> this.accountRatingsResource.getAccountRatings("not-a-public-key", null, null, null, null));
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target, AccountRatingLevel ratingLevel)
			throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), target.getPublicKey(), ratingLevel.getValue());
	}
}
