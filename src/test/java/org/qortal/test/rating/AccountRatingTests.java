package org.qortal.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingSummaryData;
import org.qortal.data.account.AccountRating;
import org.qortal.data.transaction.RateAccountTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AccountRatingTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testRatingValidation() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount unknown = Common.generateRandomSeedAccount(repository);

			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, 4)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, 1)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, -1)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, -4)).isValid());
			assertEquals(Transaction.ValidationResult.INVALID_ACCOUNT_RATING,
					Transaction.fromData(repository, ratingData(alice, bob.getPublicKey(), 5)).isValid());
			assertEquals(Transaction.ValidationResult.INVALID_ACCOUNT_RATING,
					Transaction.fromData(repository, ratingData(alice, bob.getPublicKey(), -5)).isValid());
			assertEquals(Transaction.ValidationResult.CANNOT_RATE_SELF,
					Transaction.fromData(repository, ratingData(alice, alice, 4)).isValid());
			assertEquals(Transaction.ValidationResult.PUBLIC_KEY_UNKNOWN,
					Transaction.fromData(repository, ratingData(alice, unknown.getPublicKey(), 4)).isValid());
			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_UNCHANGED,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRating.NO_RATING)).isValid());

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, 4), alice);
			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_UNCHANGED,
					Transaction.fromData(repository, ratingData(alice, bob, 4)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, -2)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRating.NO_RATING)).isValid());
		}
	}

	@Test
	public void testProcessReplaceAndOrphan() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, 4), alice);
			assertActiveRating(repository, bob, alice, 4);

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, -2), alice);
			assertActiveRating(repository, bob, alice, -2);

			BlockUtils.orphanLastBlock(repository);
			assertActiveRating(repository, bob, alice, 4);
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			BlockUtils.orphanLastBlock(repository);
			assertNull(repository.getAccountRatingRepository().getRating(bob.getPublicKey(), alice.getPublicKey()));
		}
	}

	@Test
	public void testUnknownClearsAndOrphans() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, -4), alice);
			assertActiveRating(repository, bob, alice, -4);

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRating.NO_RATING), alice);
			assertNull(repository.getAccountRatingRepository().getRating(bob.getPublicKey(), alice.getPublicKey()));

			BlockUtils.orphanLastBlock(repository);
			assertActiveRating(repository, bob, alice, -4);
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			BlockUtils.orphanLastBlock(repository);
			assertNull(repository.getAccountRatingRepository().getRating(bob.getPublicKey(), alice.getPublicKey()));
		}
	}

	@Test
	public void testSummaryAndList() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, 4), alice);
			TransactionUtils.signAndMint(repository, ratingData(chloe, bob, 2), chloe);
			TransactionUtils.signAndMint(repository, ratingData(dilbert, bob, -3), dilbert);

			AccountRatingSummaryData summary = repository.getAccountRatingRepository()
					.getRatingSummary(bob.getPublicKey(), bob.getAddress());

			assertEquals(0, summary.getPositiveLowCount());
			assertEquals(1, summary.getPositiveMediumCount());
			assertEquals(0, summary.getPositiveHighCount());
			assertEquals(1, summary.getPositiveVeryHighCount());
			assertEquals(0, summary.getNegativeLowCount());
			assertEquals(0, summary.getNegativeMediumCount());
			assertEquals(1, summary.getNegativeHighCount());
			assertEquals(0, summary.getNegativeVeryHighCount());
			assertEquals(2, summary.getPositiveRatingCount());
			assertEquals(1, summary.getNegativeRatingCount());
			assertEquals(3, summary.getTotalRatingCount());
			assertEquals(3, repository.getAccountRatingRepository().getRatings(bob.getPublicKey(), null, null, null, null).size());
			assertEquals(1, repository.getAccountRatingRepository().getRatings(bob.getPublicKey(), alice.getPublicKey(), null, null, null).size());
		}
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target, int rating)
			throws DataException {
		return ratingData(rater, target.getPublicKey(), rating);
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, byte[] targetPublicKey, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), targetPublicKey, rating);
	}

	private void assertActiveRating(Repository repository, PrivateKeyAccount target, PrivateKeyAccount rater,
			int expectedRating) throws DataException {
		AccountRatingData activeRating = repository.getAccountRatingRepository().getRating(target.getPublicKey(), rater.getPublicKey());

		assertEquals(expectedRating, activeRating.getRating());
		assertEquals(AccountRating.getDirection(expectedRating), activeRating.getRatingDirection());
		assertEquals(AccountRating.getConfidence(expectedRating), activeRating.getRatingConfidence());
	}
}
