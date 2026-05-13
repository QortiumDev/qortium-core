package org.qortal.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingLevel;
import org.qortal.data.account.AccountRatingSummaryData;
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
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingLevel.TRUSTED)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingLevel.KNOWN)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingLevel.UNTRUSTED)).isValid());
			assertEquals(Transaction.ValidationResult.INVALID_ACCOUNT_RATING,
					Transaction.fromData(repository, ratingData(alice, bob.getPublicKey(), 3)).isValid());
			assertEquals(Transaction.ValidationResult.CANNOT_RATE_SELF,
					Transaction.fromData(repository, ratingData(alice, alice, AccountRatingLevel.TRUSTED)).isValid());
			assertEquals(Transaction.ValidationResult.PUBLIC_KEY_UNKNOWN,
					Transaction.fromData(repository, ratingData(alice, unknown.getPublicKey(), AccountRatingLevel.TRUSTED.getValue())).isValid());
			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_UNCHANGED,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingLevel.UNKNOWN)).isValid());

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingLevel.TRUSTED), alice);
			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_UNCHANGED,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingLevel.TRUSTED)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingLevel.KNOWN)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingLevel.UNKNOWN)).isValid());
		}
	}

	@Test
	public void testProcessReplaceAndOrphan() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingLevel.TRUSTED), alice);
			assertActiveRating(repository, bob, alice, AccountRatingLevel.TRUSTED);

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingLevel.KNOWN), alice);
			assertActiveRating(repository, bob, alice, AccountRatingLevel.KNOWN);

			BlockUtils.orphanLastBlock(repository);
			assertActiveRating(repository, bob, alice, AccountRatingLevel.TRUSTED);
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

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingLevel.UNTRUSTED), alice);
			assertActiveRating(repository, bob, alice, AccountRatingLevel.UNTRUSTED);

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingLevel.UNKNOWN), alice);
			assertNull(repository.getAccountRatingRepository().getRating(bob.getPublicKey(), alice.getPublicKey()));

			BlockUtils.orphanLastBlock(repository);
			assertActiveRating(repository, bob, alice, AccountRatingLevel.UNTRUSTED);
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

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingLevel.TRUSTED), alice);
			TransactionUtils.signAndMint(repository, ratingData(chloe, bob, AccountRatingLevel.KNOWN), chloe);
			TransactionUtils.signAndMint(repository, ratingData(dilbert, bob, AccountRatingLevel.UNTRUSTED), dilbert);

			AccountRatingSummaryData summary = repository.getAccountRatingRepository()
					.getRatingSummary(bob.getPublicKey(), bob.getAddress());

			assertEquals(1, summary.getTrustedCount());
			assertEquals(1, summary.getKnownCount());
			assertEquals(1, summary.getUntrustedCount());
			assertEquals(3, summary.getTotalRatingCount());
			assertEquals(3, repository.getAccountRatingRepository().getRatings(bob.getPublicKey(), null, null, null, null).size());
			assertEquals(1, repository.getAccountRatingRepository().getRatings(bob.getPublicKey(), alice.getPublicKey(), null, null, null).size());
		}
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target, AccountRatingLevel ratingLevel)
			throws DataException {
		return ratingData(rater, target.getPublicKey(), ratingLevel.getValue());
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, byte[] targetPublicKey, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), targetPublicKey, rating);
	}

	private void assertActiveRating(Repository repository, PrivateKeyAccount target, PrivateKeyAccount rater,
			AccountRatingLevel expectedRatingLevel) throws DataException {
		AccountRatingData activeRating = repository.getAccountRatingRepository().getRating(target.getPublicKey(), rater.getPublicKey());

		assertEquals(expectedRatingLevel, activeRating.getRatingLevel());
	}
}
