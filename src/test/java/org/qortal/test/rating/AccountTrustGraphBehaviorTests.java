package org.qortal.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.AccountTrustDerivation;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.transaction.RateAccountTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TestChainBootstrapUtils;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccountTrustGraphBehaviorTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testIsolatedPositiveFarmRingStaysUnverified() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount farmA = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount farmB = Common.generateRandomSeedAccount(repository);
			PrivateKeyAccount farmC = Common.generateRandomSeedAccount(repository);
			List<PrivateKeyAccount> farmAccounts = Arrays.asList(farmA, farmB, farmC);

			for (PrivateKeyAccount account : farmAccounts)
				ensureKnownAccount(repository, account);

			saveAccountRating(repository, farmA, farmB, AccountRatingCategory.MANAGER, 4);
			saveAccountRating(repository, farmB, farmC, AccountRatingCategory.TRAINER, 4);
			saveAccountRating(repository, farmC, farmA, AccountRatingCategory.PLAYER, 4);
			saveAccountRating(repository, farmA, farmC, AccountRatingCategory.SUBJECT, 4);
			refreshTrustSnapshots(repository);

			for (PrivateKeyAccount account : farmAccounts) {
				for (AccountRatingCategory category : AccountRatingCategory.values()) {
					AccountTrustSnapshotData snapshot = findSnapshot(repository, account.getAddress(), category);

					assertEquals("Farm ring should not gain " + category + " score without a seed path",
							0L, snapshot.getScore());
					assertEquals("Farm ring should not gain " + category + " level without a seed path",
							0, snapshot.getLevel());
					assertEquals("Farm ring should remain Unverified without a seed path",
							AccountTrustStatus.UNVERIFIED, snapshot.getMappedTrustStatus());
				}
			}
		}
	}

	@Test
	public void testUntrustedNegativeSubjectRatingDoesNotMakeTargetSuspicious() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			TestChainBootstrapUtils.ensureMintingGroupMember(repository, "bob");
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, chloe);

			saveAccountRating(repository, chloe, bob, AccountRatingCategory.SUBJECT, -4);
			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData bobSubject = findSnapshot(repository, bob.getAddress(), AccountRatingCategory.SUBJECT);
			assertEquals(0L, bobSubject.getScore());
			assertEquals(0, bobSubject.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, bobSubject.getMappedTrustStatus());
			assertTrue("Unverified target in the minting group should still be able to mint",
					new Account(repository, bob.getAddress()).canMint(false));
		}
	}

	@Test
	public void testTrustedNegativeSubjectRatingMakesTargetSuspiciousAndOrphanRestoresMinting() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			ensureKnownAccount(repository, alice);
			ensureKnownAccount(repository, bob);
			ensureKnownAccount(repository, chloe);
			ensureKnownAccount(repository, dilbert);

			saveAccountRating(repository, alice, chloe, AccountRatingCategory.MANAGER, 4);
			saveAccountRating(repository, chloe, dilbert, AccountRatingCategory.TRAINER, 4);
			saveAccountRating(repository, dilbert, bob, AccountRatingCategory.PLAYER, 4);
			refreshTrustSnapshots(repository);

			AccountTrustSnapshotData bobPlayer = findSnapshot(repository, bob.getAddress(), AccountRatingCategory.PLAYER);
			assertEquals(16_000_000L, bobPlayer.getScore());
			assertEquals(3, bobPlayer.getLevel());
			assertEquals(AccountTrustStatus.GOLD, bobPlayer.getMappedTrustStatus());

			AccountTrustSnapshotData aliceSubjectBefore = findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.UNVERIFIED, aliceSubjectBefore.getMappedTrustStatus());
			assertTrue("Alice should be able to mint before the trusted negative rating",
					new Account(repository, alice.getAddress()).canMint(false));

			TransactionUtils.signAndMint(repository, ratingData(bob, alice, AccountRatingCategory.SUBJECT, -4), bob);

			AccountTrustSnapshotData aliceSubjectAfter = findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(-256_000_000L, aliceSubjectAfter.getScore());
			assertEquals(-1, aliceSubjectAfter.getLevel());
			assertEquals(AccountTrustStatus.SUSPICIOUS, aliceSubjectAfter.getMappedTrustStatus());
			assertFalse("Derived Suspicious should block mint eligibility",
					new Account(repository, alice.getAddress()).canMint(false));

			BlockUtils.orphanLastBlock(repository);

			AccountTrustSnapshotData aliceSubjectRestored = findSnapshot(repository, alice.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(0L, aliceSubjectRestored.getScore());
			assertEquals(0, aliceSubjectRestored.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, aliceSubjectRestored.getMappedTrustStatus());
			assertTrue("Orphaning the trusted negative rating should restore mint eligibility",
					new Account(repository, alice.getAddress()).canMint(false));
		}
	}

	@Test
	public void testManagerSeedEnergySplitsAcrossPositiveRatings() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			List<PrivateKeyAccount> managerTargets = Arrays.asList(
					Common.generateRandomSeedAccount(repository),
					Common.generateRandomSeedAccount(repository),
					Common.generateRandomSeedAccount(repository),
					Common.generateRandomSeedAccount(repository));

			ensureKnownAccount(repository, alice);
			for (PrivateKeyAccount managerTarget : managerTargets) {
				ensureKnownAccount(repository, managerTarget);
				saveAccountRating(repository, alice, managerTarget, AccountRatingCategory.MANAGER, 4);
			}

			refreshTrustSnapshots(repository);

			long totalManagerScore = 0L;
			for (PrivateKeyAccount managerTarget : managerTargets) {
				AccountTrustSnapshotData managerSnapshot = findSnapshot(repository, managerTarget.getAddress(),
						AccountRatingCategory.MANAGER);

				assertEquals(250_000L, managerSnapshot.getScore());
				assertEquals(2, managerSnapshot.getLevel());
				assertEquals(AccountTrustStatus.SILVER, managerSnapshot.getMappedTrustStatus());
				totalManagerScore += managerSnapshot.getScore();
			}

			assertEquals("Alice's one seed budget should be split, not multiplied", 1_000_000L, totalManagerScore);
		}
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), target.getPublicKey(), category, rating);
	}

	private void saveAccountRating(Repository repository, PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		repository.getAccountRatingRepository().save(new AccountRatingData(target.getPublicKey(), rater.getPublicKey(),
				category, rating));
	}

	private void refreshTrustSnapshots(Repository repository) throws DataException {
		AccountTrustDerivation.refreshSnapshots(repository, repository.getBlockRepository().getBlockchainHeight() + 1,
				repository.getBlockRepository().getLastBlock().getTimestamp());
		repository.saveChanges();
	}

	private void ensureKnownAccount(Repository repository, PrivateKeyAccount account) throws DataException {
		repository.getAccountRepository()
				.ensureAccount(new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, 0));
	}

	private AccountTrustSnapshotData findSnapshot(Repository repository, String accountAddress, AccountRatingCategory category)
			throws DataException {
		return repository.getAccountRatingRepository().getTrustDerivationSnapshots(accountAddress).stream()
				.filter(snapshot -> snapshot.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing snapshot for " + accountAddress + " in category " + category));
	}
}
