package org.qortium.test.rating;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.block.BlockChain;
import org.qortium.data.account.AccountRatingData;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountRatingSummaryData;
import org.qortium.data.account.AccountRating;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.transaction.RateAccountTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.AccountTrustTestUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.Transaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AccountRatingTests extends Common {

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		AccountTrustTestUtils.useAccountRatingCooldown(0);
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
			assertEquals(Transaction.ValidationResult.OK,
					Transaction.fromData(repository, ratingData(alice, bob, AccountRatingCategory.PLAYER, 4)).isValid());
		}
	}

	@Test
	public void testRatingDelayedDuringOnlineAccountBlocks() throws DataException, IllegalAccessException {
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 100, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 10, true);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			Transaction ratingTransaction = Transaction.fromData(repository, ratingData(alice, bob, 4));

			assertEquals(true, ratingTransaction.isConfirmableAtHeight(89));
			assertEquals(false, ratingTransaction.isConfirmableAtHeight(90));
			assertEquals(false, ratingTransaction.isConfirmableAtHeight(99));
			assertEquals(false, ratingTransaction.isConfirmableAtHeight(100));
			assertEquals(true, ratingTransaction.isConfirmableAtHeight(101));
		}
	}

	@Test
	public void testImportedRatingWaitsUntilOnlineAccountWindowEnds() throws DataException, IllegalAccessException {
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchStartHeight", 0, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchSize", 100, true);
		FieldUtils.writeField(BlockChain.getInstance(), "blockRewardBatchAccountsBlockCount", 10, true);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "chloe");
			TestAccount target = Common.getTestAccount(repository, "dilbert");

			AccountTrustTestUtils.ensureKnownAccount(repository, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporterA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporterB);
			AccountTrustTestUtils.saveAccountRating(repository, supporterA, target,
					AccountRatingCategory.SUBJECT, 4);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);
			assertSubjectStatus(repository, target, AccountTrustStatus.UNVERIFIED);

			BlockUtils.mintBlocks(repository, 88);
			assertEquals(89, repository.getBlockRepository().getBlockchainHeight());

			RateAccountTransactionData delayedRatingData = ratingData(supporterB, target,
					AccountRatingCategory.SUBJECT, 4);
			TransactionUtils.signAndImportValid(repository, delayedRatingData, supporterB);
			assertFalse(repository.getTransactionRepository().isConfirmed(delayedRatingData.getSignature()));
			assertUnconfirmedContains(repository, delayedRatingData);

			for (int height = 90; height <= 100; ++height) {
				BlockUtils.mintBlock(repository);
				assertEquals(height, repository.getBlockRepository().getBlockchainHeight());
				assertFalse(repository.getTransactionRepository().isConfirmed(delayedRatingData.getSignature()));
				assertUnconfirmedContains(repository, delayedRatingData);
				assertNull(repository.getAccountRatingRepository().getRating(target.getPublicKey(),
						supporterB.getPublicKey(), AccountRatingCategory.SUBJECT));
				assertSubjectStatus(repository, target, AccountTrustStatus.UNVERIFIED);
			}

			BlockUtils.mintBlock(repository);
			assertEquals(101, repository.getBlockRepository().getBlockchainHeight());
			assertTrue(repository.getTransactionRepository().isConfirmed(delayedRatingData.getSignature()));
			assertUnconfirmedDoesNotContain(repository, delayedRatingData);
			assertActiveRating(repository, target, supporterB, AccountRatingCategory.SUBJECT, 4);
			assertSubjectStatus(repository, target, AccountTrustStatus.GOLD);
			assertEquals(Integer.valueOf(101), ((RateAccountTransactionData) repository.getTransactionRepository()
					.fromSignature(delayedRatingData.getSignature())).getRatingChangeHeight());

			BlockUtils.orphanLastBlock(repository);
			assertEquals(100, repository.getBlockRepository().getBlockchainHeight());
			assertFalse(repository.getTransactionRepository().isConfirmed(delayedRatingData.getSignature()));
			assertNull(repository.getAccountRatingRepository().getRating(target.getPublicKey(),
					supporterB.getPublicKey(), AccountRatingCategory.SUBJECT));
			assertSubjectStatus(repository, target, AccountTrustStatus.UNVERIFIED);
			assertNull(((RateAccountTransactionData) repository.getTransactionRepository()
					.fromSignature(delayedRatingData.getSignature())).getRatingChangeHeight());
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

	@Test
	public void testCategoryRatingsAreIndependent() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.SUBJECT, 4), alice);
			TransactionUtils.signAndMint(repository, ratingData(alice, bob, AccountRatingCategory.PLAYER, -2), alice);

			assertActiveRating(repository, bob, alice, AccountRatingCategory.SUBJECT, 4);
			assertActiveRating(repository, bob, alice, AccountRatingCategory.PLAYER, -2);
			assertEquals(2, repository.getAccountRatingRepository()
					.getRatings(bob.getPublicKey(), alice.getPublicKey(), null, null, null, null).size());
			assertEquals(1, repository.getAccountRatingRepository()
					.getRatings(bob.getPublicKey(), alice.getPublicKey(), AccountRatingCategory.PLAYER, null, null, null)
					.size());

			AccountRatingSummaryData subjectSummary = repository.getAccountRatingRepository()
					.getRatingSummary(bob.getPublicKey(), bob.getAddress(), AccountRatingCategory.SUBJECT);
			AccountRatingSummaryData playerSummary = repository.getAccountRatingRepository()
					.getRatingSummary(bob.getPublicKey(), bob.getAddress(), AccountRatingCategory.PLAYER);
			assertEquals(1, subjectSummary.getPositiveRatingCount());
			assertEquals(0, subjectSummary.getNegativeRatingCount());
			assertEquals(0, playerSummary.getPositiveRatingCount());
			assertEquals(1, playerSummary.getNegativeRatingCount());

			TransactionUtils.signAndMint(repository,
					ratingData(alice, bob, AccountRatingCategory.PLAYER, AccountRating.NO_RATING), alice);
			assertActiveRating(repository, bob, alice, AccountRatingCategory.SUBJECT, 4);
			assertNull(repository.getAccountRatingRepository().getRating(bob.getPublicKey(), alice.getPublicKey(),
					AccountRatingCategory.PLAYER));
			assertEquals(1, repository.getAccountRatingRepository()
					.getRatings(bob.getPublicKey(), alice.getPublicKey(), null, null, null, null).size());
			assertEquals(1, repository.getAccountRatingRepository()
					.getRatings(bob.getPublicKey(), alice.getPublicKey(), AccountRatingCategory.SUBJECT, null, null, null)
					.size());
			assertEquals(0, repository.getAccountRatingRepository()
					.getRatings(bob.getPublicKey(), alice.getPublicKey(), AccountRatingCategory.PLAYER, null, null, null)
					.size());

			subjectSummary = repository.getAccountRatingRepository()
					.getRatingSummary(bob.getPublicKey(), bob.getAddress(), AccountRatingCategory.SUBJECT);
			playerSummary = repository.getAccountRatingRepository()
					.getRatingSummary(bob.getPublicKey(), bob.getAddress(), AccountRatingCategory.PLAYER);
			assertEquals(1, subjectSummary.getPositiveRatingCount());
			assertEquals(0, subjectSummary.getNegativeRatingCount());
			assertEquals(0, playerSummary.getTotalRatingCount());

			BlockUtils.orphanLastBlock(repository);
			assertActiveRating(repository, bob, alice, AccountRatingCategory.SUBJECT, 4);
			assertActiveRating(repository, bob, alice, AccountRatingCategory.PLAYER, -2);
			assertEquals(2, repository.getAccountRatingRepository()
					.getRatings(bob.getPublicKey(), alice.getPublicKey(), null, null, null, null).size());
			TransactionUtils.deleteUnconfirmedTransactions(repository);
		}
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target, int rating)
			throws DataException {
		return ratingData(rater, target.getPublicKey(), rating);
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), target.getPublicKey(), category, rating);
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, byte[] targetPublicKey, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), targetPublicKey, rating);
	}

	private void assertActiveRating(Repository repository, PrivateKeyAccount target, PrivateKeyAccount rater,
			int expectedRating) throws DataException {
		assertActiveRating(repository, target, rater, AccountRatingCategory.SUBJECT, expectedRating);
	}

	private void assertActiveRating(Repository repository, PrivateKeyAccount target, PrivateKeyAccount rater,
			AccountRatingCategory category, int expectedRating) throws DataException {
		AccountRatingData activeRating = repository.getAccountRatingRepository().getRating(target.getPublicKey(), rater.getPublicKey(),
				category);

		assertEquals(expectedRating, activeRating.getRating());
		assertEquals(category, activeRating.getCategory());
		assertEquals(AccountRating.getDirection(expectedRating), activeRating.getRatingDirection());
		assertEquals(AccountRating.getConfidence(expectedRating), activeRating.getRatingConfidence());
	}

	private void assertSubjectStatus(Repository repository, PrivateKeyAccount target,
			AccountTrustStatus expectedStatus) throws DataException {
		AccountTrustSnapshotData snapshot = findSnapshot(repository, target.getAddress(), AccountRatingCategory.SUBJECT);
		assertEquals(expectedStatus, snapshot.getMappedTrustStatus());
	}

	private AccountTrustSnapshotData findSnapshot(Repository repository, String accountAddress,
			AccountRatingCategory category) throws DataException {
		return repository.getAccountRatingRepository().getTrustDerivationSnapshots(accountAddress).stream()
				.filter(snapshot -> snapshot.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing snapshot for " + accountAddress
						+ " in category " + category));
	}

	private void assertUnconfirmedContains(Repository repository, TransactionData expectedTransactionData)
			throws DataException {
		assertTrue(repository.getTransactionRepository().getUnconfirmedTransactions().stream()
				.anyMatch(transactionData -> java.util.Arrays.equals(transactionData.getSignature(),
						expectedTransactionData.getSignature())));
	}

	private void assertUnconfirmedDoesNotContain(Repository repository, TransactionData expectedTransactionData)
			throws DataException {
		assertFalse(repository.getTransactionRepository().getUnconfirmedTransactions().stream()
				.anyMatch(transactionData -> java.util.Arrays.equals(transactionData.getSignature(),
						expectedTransactionData.getSignature())));
	}
}
