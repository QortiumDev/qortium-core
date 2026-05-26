package org.qortium.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.AccountTrustPolicy;
import org.qortium.account.AccountTrustWeight;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.resource.AccountRatingsResource;
import org.qortium.data.account.AccountRating;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountRatingCooldownData;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.account.AccountTrustStatusChangeData;
import org.qortium.data.transaction.RateAccountTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountTrustTestUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.utils.Base58;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccountTrustAdversarialScenarioTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testSignedIsolatedFarmRingStaysUnverifiedWithZeroWeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount farmA = Common.getTestAccount(repository, "bob");
			TestAccount farmB = Common.getTestAccount(repository, "chloe");
			TestAccount farmC = Common.getTestAccount(repository, "dilbert");
			List<TestAccount> farmAccounts = Arrays.asList(farmA, farmB, farmC);

			ensureKnownAccounts(repository, farmA, farmB, farmC);
			repository.saveChanges();

			TransactionUtils.signAndMint(repository, ratingData(farmA, farmB, AccountRatingCategory.MANAGER, 4), farmA);
			TransactionUtils.signAndMint(repository, ratingData(farmB, farmC, AccountRatingCategory.TRAINER, 4), farmB);
			TransactionUtils.signAndMint(repository, ratingData(farmC, farmA, AccountRatingCategory.PLAYER, 4), farmC);
			TransactionUtils.signAndMint(repository, ratingData(farmA, farmC, AccountRatingCategory.SUBJECT, 4), farmA);

			for (TestAccount farmAccount : farmAccounts) {
				for (AccountRatingCategory category : AccountRatingCategory.values()) {
					AccountTrustSnapshotData snapshot = findSnapshot(repository, farmAccount.getAddress(), category);

					assertEquals("Signed farm-ring ratings should not create " + category + " score",
							0L, snapshot.getScore());
					assertEquals("Signed farm-ring ratings should not create " + category + " level",
							0, snapshot.getLevel());
					assertEquals("Signed farm-ring ratings should remain Unverified",
							AccountTrustStatus.UNVERIFIED, snapshot.getMappedTrustStatus());
					assertEquals("Signed farm-ring ratings should not create governance weight",
							0, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, snapshot));
				}
			}
		}
	}

	@Test
	public void testSignedSameBranchPositiveSupportCannotLiftTarget() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount sameBranchPlayerA = Common.getTestAccount(repository, "bob");
			TestAccount sameBranchPlayerB = Common.getTestAccount(repository, "dilbert");
			TestAccount target = Common.getTestAccount(repository, "chloe");

			ensureKnownAccounts(repository, seedAccount, sameBranchPlayerA, sameBranchPlayerB, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatingsFromSharedManagerBranch(repository, seedAccount,
					Arrays.asList(sameBranchPlayerA, sameBranchPlayerB));
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository,
					ratingData(sameBranchPlayerA, target, AccountRatingCategory.SUBJECT, 4), sameBranchPlayerA);
			TransactionUtils.signAndMint(repository,
					ratingData(sameBranchPlayerB, target, AccountRatingCategory.SUBJECT, 4), sameBranchPlayerB);

			AccountTrustSnapshotData targetSubject = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertTrue("Same-branch positive support should remain auditable",
					targetSubject.getScore() >= AccountTrustPolicy.getLevelThreshold(AccountRatingCategory.SUBJECT, 4));
			assertEquals("Same-branch positive support should not satisfy branch independence",
					0, targetSubject.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, targetSubject.getMappedTrustStatus());
			assertEquals(0, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, targetSubject));
		}
	}

	@Test
	public void testSignedSameBranchNegativeSupportCannotMakeTargetSuspicious() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount sameBranchPlayerA = Common.getTestAccount(repository, "bob");
			TestAccount sameBranchPlayerB = Common.getTestAccount(repository, "dilbert");
			TestAccount target = Common.getTestAccount(repository, "chloe");

			TestChainBootstrapUtils.ensureMintingGroupMember(repository, "chloe");
			ensureKnownAccounts(repository, seedAccount, sameBranchPlayerA, sameBranchPlayerB, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatingsFromSharedManagerBranch(repository, seedAccount,
					Arrays.asList(sameBranchPlayerA, sameBranchPlayerB));
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository,
					ratingData(sameBranchPlayerA, target, AccountRatingCategory.SUBJECT, -2), sameBranchPlayerA);
			TransactionUtils.signAndMint(repository,
					ratingData(sameBranchPlayerB, target, AccountRatingCategory.SUBJECT, -2), sameBranchPlayerB);

			AccountTrustSnapshotData targetSubject = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertTrue("Same-branch negative support should remain auditable", targetSubject.getScore() < 0);
			assertEquals("Same-branch negative support should not satisfy Suspicious branch independence",
					0, targetSubject.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, targetSubject.getMappedTrustStatus());
			assertTrue("Same-branch negative support should not block minting",
					new Account(repository, target.getAddress()).canMint(false));
		}
	}

	@Test
	public void testSignedIndependentNegativeSupportBlocksMinting() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount raterA = Common.getTestAccount(repository, "bob");
			TestAccount raterB = Common.getTestAccount(repository, "dilbert");
			TestAccount target = Common.getTestAccount(repository, "chloe");

			TestChainBootstrapUtils.ensureMintingGroupMember(repository, "chloe");
			ensureKnownAccounts(repository, seedAccount, raterA, raterB, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, raterA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, raterB);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository, ratingData(raterA, target, AccountRatingCategory.SUBJECT, -2),
					raterA);
			TransactionUtils.signAndMint(repository, ratingData(raterB, target, AccountRatingCategory.SUBJECT, -2),
					raterB);

			AccountTrustSnapshotData targetSubject = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.SUSPICIOUS, targetSubject.getMappedTrustStatus());
			assertFalse("Independent negative support should block minting",
					new Account(repository, target.getAddress()).canMint(false));

			List<AccountTrustStatusChangeData> suspiciousChanges = repository.getAccountRatingRepository()
					.getTrustStatusChanges(target.getAddress(), AccountRatingCategory.SUBJECT,
							AccountTrustStatus.UNVERIFIED, AccountTrustStatus.SUSPICIOUS, null, null, null);
			assertEquals(1, suspiciousChanges.size());
			assertEquals(targetSubject.getSnapshotHeight(), suspiciousChanges.get(0).getSnapshotHeight());
		}
	}

	@Test
	public void testDefaultCooldownBlocksRapidAttackFlipAndApiExplainsIt() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount rater = Common.getTestAccount(repository, "bob");
			TestAccount target = Common.getTestAccount(repository, "chloe");

			ensureKnownAccounts(repository, seedAccount, rater, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, rater);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository, ratingData(rater, target, AccountRatingCategory.SUBJECT, -2),
					rater);

			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_CHANGE_TOO_SOON,
					Transaction.fromData(repository,
							ratingData(rater, target, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING))
							.isValid());
			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_CHANGE_TOO_SOON,
					Transaction.fromData(repository, ratingData(rater, target, AccountRatingCategory.SUBJECT, 4))
							.isValid());

			AccountRatingCooldownData cooldown = getCooldown(target, rater, AccountRatingCategory.SUBJECT);
			assertFalse(cooldown.isCanChangeNow());
			assertEquals(Integer.valueOf(-2), cooldown.getActiveRating());
			assertEquals(1_440, cooldown.getCooldownBlocks());
			assertTrue(cooldown.getBlocksRemaining() > 0);
			assertEquals(cooldown.getLatestRatingChangeHeight().intValue() + cooldown.getCooldownBlocks(),
					cooldown.getEarliestAllowedHeight());
		}
	}

	@Test
	public void testShortCooldownAllowsRecoveryAfterWindow() throws Exception {
		AccountTrustTestUtils.useAccountRatingCooldown(3);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount raterA = Common.getTestAccount(repository, "bob");
			TestAccount raterB = Common.getTestAccount(repository, "dilbert");
			TestAccount target = Common.getTestAccount(repository, "chloe");

			TestChainBootstrapUtils.ensureMintingGroupMember(repository, "chloe");
			ensureKnownAccounts(repository, seedAccount, raterA, raterB, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, raterA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, raterB);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository, ratingData(raterA, target, AccountRatingCategory.SUBJECT, -2),
					raterA);
			TransactionUtils.signAndMint(repository, ratingData(raterB, target, AccountRatingCategory.SUBJECT, -2),
					raterB);

			AccountTrustSnapshotData targetSuspicious = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.SUSPICIOUS, targetSuspicious.getMappedTrustStatus());
			assertFalse(new Account(repository, target.getAddress()).canMint(false));

			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_CHANGE_TOO_SOON,
					Transaction.fromData(repository,
							ratingData(raterA, target, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING))
							.isValid());

			AccountRatingCooldownData blockedCooldown = getCooldown(target, raterA, AccountRatingCategory.SUBJECT);
			assertFalse(blockedCooldown.isCanChangeNow());
			assertEquals(1, blockedCooldown.getBlocksRemaining());

			BlockUtils.mintBlock(repository);

			AccountRatingCooldownData allowedCooldown = getCooldown(target, raterA, AccountRatingCategory.SUBJECT);
			assertTrue(allowedCooldown.isCanChangeNow());
			assertEquals(0, allowedCooldown.getBlocksRemaining());

			TransactionUtils.signAndMint(repository,
					ratingData(raterA, target, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING), raterA);

			AccountTrustSnapshotData targetRecovered = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.UNVERIFIED, targetRecovered.getMappedTrustStatus());
			assertTrue("Removing one independent negative rating after cooldown should restore minting",
					new Account(repository, target.getAddress()).canMint(false));
		}
	}

	private RateAccountTransactionData ratingData(PrivateKeyAccount rater, PrivateKeyAccount target,
			AccountRatingCategory category, int rating) throws DataException {
		return new RateAccountTransactionData(TestTransaction.generateBase(rater), target.getPublicKey(), category, rating);
	}

	private void ensureKnownAccounts(Repository repository, PrivateKeyAccount... accounts) throws DataException {
		for (PrivateKeyAccount account : accounts)
			AccountTrustTestUtils.ensureKnownAccount(repository, account);
	}

	private AccountTrustSnapshotData findSnapshot(Repository repository, String accountAddress,
			AccountRatingCategory category) throws DataException {
		return repository.getAccountRatingRepository().getTrustDerivationSnapshots(accountAddress).stream()
				.filter(snapshot -> snapshot.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing snapshot for " + accountAddress + " in category " + category));
	}

	private AccountRatingCooldownData getCooldown(PrivateKeyAccount target, PrivateKeyAccount rater,
			AccountRatingCategory category) {
		return new AccountRatingsResource().getAccountRatingCooldown(Base58.encode(target.getPublicKey()),
				Base58.encode(rater.getPublicKey()), category.name());
	}
}
