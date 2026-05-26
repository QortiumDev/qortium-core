package org.qortium.test.rating;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.AccountTrustWeight;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.resource.AccountRatingsResource;
import org.qortium.data.account.AccountData;
import org.qortium.data.account.AccountRating;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountRatingCooldownData;
import org.qortium.data.account.AccountTrustCategoryImpactData;
import org.qortium.data.account.AccountTrustExplanationData;
import org.qortium.data.account.AccountTrustProfileData;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.transaction.RateAccountTransactionData;
import org.qortium.group.Group;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AccountTrustOnboardingScenarioTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testNewMintingMemberWithoutEvidenceCanMintButHasZeroWeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount newMinter = Common.getTestAccount(repository, "bob");

			AccountTrustTestUtils.ensureKnownAccount(repository, newMinter);
			TestChainBootstrapUtils.ensureMintingGroupMember(repository, "bob");
			setBlocksMinted(repository, newMinter, 1_000);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			AccountTrustSnapshotData subjectSnapshot = findSnapshot(repository, newMinter.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.UNVERIFIED, subjectSnapshot.getMappedTrustStatus());
			assertTrue("Unverified Minting group members should still be allowed to mint",
					new Account(repository, newMinter.getAddress()).canMint(false));
			assertEquals("Unverified accounts should have zero effective governance weight",
					0, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, subjectSnapshot));

			AccountTrustProfileData profile = getProfile(newMinter);
			assertEquals(AccountTrustStatus.UNVERIFIED, profile.getTrustStatus());
			assertEquals(0, profile.getTrustWeightPercent());
			assertTrue(profile.isTrustAllowsMinting());
			assertTrue(profile.isMintingSeedMember());
			assertEquals(AccountTrustStatus.UNVERIFIED,
					findProfileCategory(profile, AccountRatingCategory.SUBJECT).getMappedTrustStatus());

			AccountTrustExplanationData explanation = getExplanation(newMinter);
			AccountTrustExplanationData.CategoryExplanation subjectExplanation = findExplanationCategory(explanation,
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.UNVERIFIED, explanation.getTrustStatus());
			assertEquals(0, explanation.getTrustWeightPercent());
			assertTrue(explanation.isMintingSeedMember());
			assertTrue(subjectExplanation.getTopPositiveImpacts().isEmpty());
			assertTrue(subjectExplanation.getTopNegativeImpacts().isEmpty());
			assertFalse(findRequirement(subjectExplanation, "level.1.threshold").isPassed());
		}
	}

	@Test
	public void testSignedOneTrustedSupporterLeavesAuditEvidenceWithoutTier() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporter = Common.getTestAccount(repository, "bob");
			TestAccount target = Common.getTestAccount(repository, "chloe");

			ensureKnownAccounts(repository, seedAccount, supporter, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporter);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository,
					ratingData(supporter, target, AccountRatingCategory.SUBJECT, 4), supporter);

			AccountTrustSnapshotData targetSubject = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(128_000_000L, targetSubject.getScore());
			assertEquals(5_000_000L, targetSubject.getLevelScore());
			assertEquals(0, targetSubject.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, targetSubject.getMappedTrustStatus());
			assertEquals(0, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, targetSubject));

			AccountTrustExplanationData explanation = getExplanation(target);
			AccountTrustExplanationData.CategoryExplanation subjectExplanation = findExplanationCategory(explanation,
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.UNVERIFIED, explanation.getTrustStatus());
			assertEquals(1, subjectExplanation.getTopPositiveImpacts().size());
			assertTrue(subjectExplanation.getTopNegativeImpacts().isEmpty());

			AccountTrustCategoryImpactData impact = subjectExplanation.getTopPositiveImpacts().get(0);
			assertEquals(4, impact.getRating());
			assertEquals(128_000_000L, impact.getImpact());
			assertFalse(findRequirement(subjectExplanation, "level.1.threshold").isPassed());
			assertEquals("5000000", findRequirement(subjectExplanation, "level.1.threshold").getActual());
		}
	}

	@Test
	public void testSignedIndependentSupportersPromoteTargetToGold() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "dilbert");
			TestAccount target = Common.getTestAccount(repository, "chloe");

			ensureKnownAccounts(repository, seedAccount, supporterA, supporterB, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporterA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporterB);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			assertStatus(repository, supporterA.getAddress(), AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);
			assertStatus(repository, supporterB.getAddress(), AccountRatingCategory.PLAYER, AccountTrustStatus.GOLD);

			TransactionUtils.signAndMint(repository,
					ratingData(supporterA, target, AccountRatingCategory.SUBJECT, 4), supporterA);
			TransactionUtils.signAndMint(repository,
					ratingData(supporterB, target, AccountRatingCategory.SUBJECT, 4), supporterB);

			AccountTrustSnapshotData targetSubject = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(128_000_000L, targetSubject.getScore());
			assertEquals(100_000_000L, targetSubject.getLevelScore());
			assertEquals(50_000_000L, targetSubject.getLevelScoreCap());
			assertEquals(3, targetSubject.getLevel());
			assertEquals(AccountTrustStatus.GOLD, targetSubject.getMappedTrustStatus());
			assertEquals(1_000, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, targetSubject));

			AccountTrustProfileData profile = getProfile(target);
			assertEquals(AccountTrustStatus.GOLD, profile.getTrustStatus());
			assertEquals(100, profile.getTrustWeightPercent());
			assertEquals(AccountTrustStatus.GOLD,
					findProfileCategory(profile, AccountRatingCategory.SUBJECT).getMappedTrustStatus());

			AccountTrustExplanationData explanation = getExplanation(target);
			AccountTrustExplanationData.CategoryExplanation subjectExplanation = findExplanationCategory(explanation,
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.GOLD, explanation.getTrustStatus());
			assertEquals(2, subjectExplanation.getTopPositiveImpacts().size());
			assertTrue(findRequirement(subjectExplanation, "level.3.threshold").isPassed());
			assertTrue(findRequirement(subjectExplanation, "level.3.independent-branches").isPassed());
			assertTrue(findRequirement(subjectExplanation, "level.3.positive-support").isPassed());
		}
	}

	@Test
	public void testSignedLowerConfidenceSupportCanReachBronzeAndSilver() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "dilbert");
			TestAccount bronzeTarget = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount silverTarget = Common.generateRandomSeedAccount(repository);

			ensureKnownAccounts(repository, seedAccount, supporterA, supporterB, bronzeTarget, silverTarget);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporterA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporterB);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository,
					ratingData(supporterA, bronzeTarget, AccountRatingCategory.SUBJECT, 1), supporterA);
			TransactionUtils.signAndMint(repository,
					ratingData(supporterB, bronzeTarget, AccountRatingCategory.SUBJECT, 1), supporterB);
			TransactionUtils.signAndMint(repository,
					ratingData(supporterA, silverTarget, AccountRatingCategory.SUBJECT, 2), supporterA);
			TransactionUtils.signAndMint(repository,
					ratingData(supporterB, silverTarget, AccountRatingCategory.SUBJECT, 2), supporterB);

			AccountTrustSnapshotData bronzeSubject = findSnapshot(repository, bronzeTarget.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(32_000_000L, bronzeSubject.getScore());
			assertEquals(10_000_000L, bronzeSubject.getLevelScore());
			assertEquals(5_000_000L, bronzeSubject.getLevelScoreCap());
			assertEquals(1, bronzeSubject.getLevel());
			assertEquals(AccountTrustStatus.BRONZE, bronzeSubject.getMappedTrustStatus());
			assertEquals(400, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, bronzeSubject));

			AccountTrustSnapshotData silverSubject = findSnapshot(repository, silverTarget.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(64_000_000L, silverSubject.getScore());
			assertEquals(50_000_000L, silverSubject.getLevelScore());
			assertEquals(25_000_000L, silverSubject.getLevelScoreCap());
			assertEquals(2, silverSubject.getLevel());
			assertEquals(AccountTrustStatus.SILVER, silverSubject.getMappedTrustStatus());
			assertEquals(700, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, silverSubject));

			assertEquals(AccountTrustStatus.BRONZE, getProfile(bronzeTarget).getTrustStatus());
			assertEquals(AccountTrustStatus.SILVER, getProfile(silverTarget).getTrustStatus());
		}
	}

	@Test
	public void testSignedSupportRemovalDropsTierAfterCooldownAndApisExplainWindow() throws Exception {
		AccountTrustTestUtils.useAccountRatingCooldown(3);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount seedAccount = Common.getTestAccount(repository, "alice");
			TestAccount supporterA = Common.getTestAccount(repository, "bob");
			TestAccount supporterB = Common.getTestAccount(repository, "dilbert");
			TestAccount target = Common.getTestAccount(repository, "chloe");

			ensureKnownAccounts(repository, seedAccount, supporterA, supporterB, target);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporterA);
			AccountTrustTestUtils.saveDerivedPlayerLevelThreeRatings(repository, seedAccount, supporterB);
			AccountTrustTestUtils.refreshTrustSnapshots(repository);

			TransactionUtils.signAndMint(repository,
					ratingData(supporterA, target, AccountRatingCategory.SUBJECT, 4), supporterA);
			TransactionUtils.signAndMint(repository,
					ratingData(supporterB, target, AccountRatingCategory.SUBJECT, 4), supporterB);

			AccountTrustSnapshotData targetGold = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(AccountTrustStatus.GOLD, targetGold.getMappedTrustStatus());

			assertEquals(Transaction.ValidationResult.ACCOUNT_RATING_CHANGE_TOO_SOON,
					Transaction.fromData(repository,
							ratingData(supporterA, target, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING))
							.isValid());

			AccountRatingCooldownData blockedCooldown = getCooldown(target, supporterA, AccountRatingCategory.SUBJECT);
			assertFalse(blockedCooldown.isCanChangeNow());
			assertEquals(Integer.valueOf(4), blockedCooldown.getActiveRating());
			assertEquals(3, blockedCooldown.getCooldownBlocks());
			assertEquals(1, blockedCooldown.getBlocksRemaining());

			BlockUtils.mintBlock(repository);

			AccountRatingCooldownData allowedCooldown = getCooldown(target, supporterA, AccountRatingCategory.SUBJECT);
			assertTrue(allowedCooldown.isCanChangeNow());
			assertEquals(0, allowedCooldown.getBlocksRemaining());

			TransactionUtils.signAndMint(repository,
					ratingData(supporterA, target, AccountRatingCategory.SUBJECT, AccountRating.NO_RATING), supporterA);

			assertNull(repository.getAccountRatingRepository().getRating(target.getPublicKey(),
					supporterA.getPublicKey(), AccountRatingCategory.SUBJECT));

			AccountTrustSnapshotData targetAfterRemoval = findSnapshot(repository, target.getAddress(),
					AccountRatingCategory.SUBJECT);
			assertEquals(64_000_000L, targetAfterRemoval.getScore());
			assertEquals(5_000_000L, targetAfterRemoval.getLevelScore());
			assertEquals(0, targetAfterRemoval.getLevel());
			assertEquals(AccountTrustStatus.UNVERIFIED, targetAfterRemoval.getMappedTrustStatus());
			assertEquals(0, AccountTrustWeight.calculateEffectiveVoteWeight(1_000, targetAfterRemoval));

			AccountTrustProfileData profile = getProfile(target);
			assertEquals(AccountTrustStatus.UNVERIFIED, profile.getTrustStatus());
			assertEquals(0, profile.getTrustWeightPercent());

			AccountTrustExplanationData.CategoryExplanation subjectExplanation = findExplanationCategory(
					getExplanation(target), AccountRatingCategory.SUBJECT);
			assertEquals(1, subjectExplanation.getTopPositiveImpacts().size());
			assertFalse(findRequirement(subjectExplanation, "level.1.threshold").isPassed());
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

	private void assertStatus(Repository repository, String accountAddress, AccountRatingCategory category,
			AccountTrustStatus expectedStatus) throws DataException {
		assertEquals(expectedStatus, findSnapshot(repository, accountAddress, category).getMappedTrustStatus());
	}

	private AccountTrustSnapshotData findSnapshot(Repository repository, String accountAddress,
			AccountRatingCategory category) throws DataException {
		return repository.getAccountRatingRepository().getTrustDerivationSnapshots(accountAddress).stream()
				.filter(snapshot -> snapshot.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing snapshot for " + accountAddress + " in category " + category));
	}

	private AccountTrustProfileData getProfile(PrivateKeyAccount target) {
		return new AccountRatingsResource().getAccountTrustProfile(Base58.encode(target.getPublicKey()));
	}

	private AccountTrustProfileData.CategoryProfile findProfileCategory(AccountTrustProfileData profile,
			AccountRatingCategory category) {
		return profile.getCategories().stream()
				.filter(categoryProfile -> categoryProfile.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing profile category " + category));
	}

	private AccountTrustExplanationData getExplanation(PrivateKeyAccount target) {
		return new AccountRatingsResource().getAccountTrustExplanation(Base58.encode(target.getPublicKey()), null);
	}

	private AccountTrustExplanationData.CategoryExplanation findExplanationCategory(
			AccountTrustExplanationData explanation, AccountRatingCategory category) {
		return explanation.getCategories().stream()
				.filter(categoryExplanation -> categoryExplanation.getCategory() == category)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing explanation category " + category));
	}

	private AccountTrustExplanationData.Requirement findRequirement(
			AccountTrustExplanationData.CategoryExplanation category, String name) {
		return category.getRequirements().stream()
				.filter(requirement -> requirement.getName().equals(name))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing requirement " + name));
	}

	private AccountRatingCooldownData getCooldown(PrivateKeyAccount target, PrivateKeyAccount rater,
			AccountRatingCategory category) {
		return new AccountRatingsResource().getAccountRatingCooldown(Base58.encode(target.getPublicKey()),
				Base58.encode(rater.getPublicKey()), category.name());
	}

	private void setBlocksMinted(Repository repository, PrivateKeyAccount account, int blocksMinted) throws DataException {
		AccountData accountData = repository.getAccountRepository().getAccount(account.getAddress());
		if (accountData == null)
			accountData = new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, blocksMinted);
		else {
			accountData.setPublicKey(account.getPublicKey());
			accountData.setBlocksMinted(blocksMinted);
		}

		repository.getAccountRepository().setMintedBlockCount(accountData);
		repository.saveChanges();
	}
}
