package org.qortium.test.minting;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.block.Block;
import org.qortium.controller.BlockMinter;
import org.qortium.data.account.AccountData;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountTrustDerivationData;
import org.qortium.data.account.AccountTrustCategoryData;
import org.qortium.data.account.AccountTrustRatingCountsData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.block.BlockData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TestAccount;

import java.util.Collections;

import static org.junit.Assert.*;

public class LevelZeroMintingTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestChainBootstrapUtils.ensureMintingGroupMember(repository, "bob");
			repository.saveChanges();
		}
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testLevelZeroGroupMemberCanCreateRewardShareAndMint() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount bob = Common.getTestAccount(repository, "bob");
			assertEquals("Bob should start at level 0", 0, (int) bob.getLevel());
			assertTrue("Bob should be eligible through minting-group membership", bob.canMint(false));

			byte[] bobRewardSharePrivateKey = AccountUtils.rewardShare(repository, "bob", "bob", 0);
			PrivateKeyAccount bobRewardShareAccount = new PrivateKeyAccount(repository, bobRewardSharePrivateKey);

			RewardShareData bobRewardShareData = repository.getAccountRepository().getRewardShare(bobRewardShareAccount.getPublicKey());
			assertNotNull("Bob's reward-share should exist", bobRewardShareData);

			assertEquals("Bob's actual minting level should remain 0", 0, Account.getRewardShareEffectiveMintingLevel(repository, bobRewardShareAccount.getPublicKey()));
			assertEquals("Bob's reward-share should be accepted as a level-0 minter", Integer.valueOf(0), Account.getRewardShareEffectiveMintingLevelIfMinting(repository, bobRewardShareAccount.getPublicKey()));

			BlockData parentBlockData = repository.getBlockRepository().getLastBlock();
			long expectedTimestamp = Block.calcTimestamp(parentBlockData, bobRewardShareAccount.getPublicKey(), Account.getMintingWeightLevel(0));
			int bobBlocksMinted = bob.getBlocksMinted();
			Block mintedBlock = BlockMinter.mintTestingBlock(repository, bobRewardShareAccount);

			assertEquals("Level-zero minter timestamp should use the minimum minting weight", expectedTimestamp, mintedBlock.getBlockData().getTimestamp());
			assertEquals("Bob should receive minted-block credit", bobBlocksMinted + 1, (int) bob.getBlocksMinted());
			assertEquals("Bob should remain level 0 after one minted block", 0, (int) bob.getLevel());
		}
	}

	@Test
	public void testDerivedTrustStatusControlsMintingEligibility() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			clearTrustSnapshots(repository);

			assertTrue("Missing Subject snapshot should not block minting-group members", bob.canMint(false));
			assertTrue("Missing Subject snapshot should not block prevalidated minting-group members", bob.canMint(true));

			for (AccountTrustStatus trustStatus : new AccountTrustStatus[] {
					AccountTrustStatus.UNVERIFIED,
					AccountTrustStatus.BRONZE,
					AccountTrustStatus.SILVER,
					AccountTrustStatus.GOLD
			}) {
				saveSubjectSnapshot(repository, bob, trustStatus);

				assertTrue("Minting-group member should mint with derived trust status " + trustStatus, bob.canMint(false));
				assertTrue("Prevalidated minting-group member should mint with derived trust status " + trustStatus, bob.canMint(true));
			}

			saveSubjectSnapshot(repository, chloe, AccountTrustStatus.GOLD);
			assertFalse("Non-member should not mint even with derived Gold trust status", chloe.canMint(false));

			byte[] bobRewardSharePrivateKey = AccountUtils.rewardShare(repository, "bob", "bob", 0);
			PrivateKeyAccount bobRewardShareAccount = new PrivateKeyAccount(repository, bobRewardSharePrivateKey);

			saveSubjectSnapshot(repository, bob, AccountTrustStatus.SUSPICIOUS);

			assertFalse("Derived Suspicious minting-group member should not mint", bob.canMint(false));
			assertFalse("Derived Suspicious prevalidated minting-group member should not mint", bob.canMint(true));
			assertNull("Derived Suspicious account's reward-share should not be treated as minting",
					Account.getRewardShareEffectiveMintingLevelIfMinting(repository, bobRewardShareAccount.getPublicKey()));
		}
	}

	@Test
	public void testLevelZeroUsesLevelOneMintingWeight() {
		byte[] parentSignature = new byte[64];
		byte[] minterPublicKey = new byte[32];

		assertEquals(
				"Level-zero minting should use the same minimum key-distance divisor as level one",
				Block.calcKeyDistance(1, parentSignature, minterPublicKey, 1),
				Block.calcKeyDistance(1, parentSignature, minterPublicKey, 0)
		);
	}

	private void clearTrustSnapshots(Repository repository) throws DataException {
		repository.getAccountRatingRepository().replaceTrustDerivationSnapshots(Collections.emptyList(),
				repository.getBlockRepository().getBlockchainHeight(), repository.getBlockRepository().getLastBlock().getTimestamp());
		repository.saveChanges();
	}

	private void saveSubjectSnapshot(Repository repository, TestAccount account, AccountTrustStatus trustStatus)
			throws DataException {
		repository.getAccountRepository().ensureAccount(new AccountData(account.getAddress(), account.getPublicKey(),
				Group.NO_GROUP, 0, 0));

		AccountTrustCategoryData subjectTrust = new AccountTrustCategoryData(
				AccountRatingCategory.SUBJECT, scoreForStatus(trustStatus), levelForStatus(trustStatus), trustStatus,
				new AccountTrustRatingCountsData(), Collections.emptyList());
		AccountTrustDerivationData derivationData = new AccountTrustDerivationData(account.getPublicKey(),
				account.getAddress(), trustStatus, true, Collections.singletonList(subjectTrust));

		repository.getAccountRatingRepository().replaceTrustDerivationSnapshots(Collections.singletonList(derivationData),
				repository.getBlockRepository().getBlockchainHeight(), repository.getBlockRepository().getLastBlock().getTimestamp());
		repository.saveChanges();
	}

	private long scoreForStatus(AccountTrustStatus trustStatus) {
		switch (trustStatus) {
			case GOLD:
				return 100_000_000L;
			case SILVER:
				return 50_000_000L;
			case BRONZE:
				return 10_000_000L;
			case SUSPICIOUS:
				return -1L;
			case UNVERIFIED:
			default:
				return 0L;
		}
	}

	private int levelForStatus(AccountTrustStatus trustStatus) {
		switch (trustStatus) {
			case GOLD:
				return 3;
			case SILVER:
				return 2;
			case BRONZE:
				return 1;
			case SUSPICIOUS:
				return -1;
			case UNVERIFIED:
			default:
				return 0;
		}
	}
}
