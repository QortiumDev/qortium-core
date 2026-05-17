package org.qortal.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.account.AccountRating;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.transaction.RateAccountTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountTrustTestUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AccountRatingCooldownTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testDefaultCooldownRejectsImmediateEdgeChanges() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4)).isValid());

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4), alice);

			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_UNCHANGED,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4)).isValid());
			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_CHANGE_TOO_SOON,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, -2)).isValid());
			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_CHANGE_TOO_SOON,
					Transaction.fromData(repository,
							ratingData(alice, bob, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING)).isValid());
		}
	}

	@Test
	public void testDefaultCooldownIsPerTargetAndCategory() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.generateRandomSeedAccount(repository);

			AccountTrustTestUtils.ensureKnownAccount(repository, chloe);
			repository.saveChanges();
			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4), alice);

			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingCategory.PLAYER, -2)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, chloe, AccountRatingCategory.SUBJECT, 4)).isValid());
		}
	}

	@Test
	public void testConfiguredCooldownAllowsChangeAfterWindow() throws Exception {
		AccountTrustTestUtils.useAccountRatingCooldown(2);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4), alice);

			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_CHANGE_TOO_SOON,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, -2)).isValid());

			BlockUtils.mintBlock(repository);

			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, -2)).isValid());
			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, -2), alice);
			assertActiveRating(repository, bob, alice, AccountRatingCategory.SUBJECT, -2);
		}
	}

	@Test
	public void testZeroCooldownDisablesRatingChangeLimit() throws Exception {
		AccountTrustTestUtils.useAccountRatingCooldown(0);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4), alice);

			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, -2)).isValid());
			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, -2), alice);
			assertActiveRating(repository, bob, alice, AccountRatingCategory.SUBJECT, -2);

			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository,
							ratingData(alice, bob, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING)).isValid());
		}
	}

	@Test
	public void testOrphanClearsRatingChangeCooldown() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4), alice);
			assertEquals(Integer.valueOf(repository.getBlockRepository().getBlockchainHeight()),
					repository.getAccountRatingRepository().getLatestRatingChangeHeight(bob.getPublicKey(),
							alice.getPublicKey(), AccountRatingCategory.SUBJECT));

			BlockUtils.orphanLastBlock(repository);
			TransactionUtils.deleteUnconfirmedTransactions(repository);

			assertNull(repository.getAccountRatingRepository().getLatestRatingChangeHeight(bob.getPublicKey(),
					alice.getPublicKey(), AccountRatingCategory.SUBJECT));
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4)).isValid());
		}
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), target.getPublicKey(), category, rating);
	}

	private void assertActiveRating(Repository repository, PrivateKeyAccount target, PrivateKeyAccount rater,
			AccountRatingCategory category, int expectedRating) throws DataException {
		AccountRatingData activeRating = repository.getAccountRatingRepository()
				.getRating(target.getPublicKey(), rater.getPublicKey(), category);

		assertEquals(expectedRating, activeRating.getRating());
	}
}
