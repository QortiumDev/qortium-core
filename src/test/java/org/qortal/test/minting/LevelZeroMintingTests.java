package org.qortal.test.minting;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.Block;
import org.qortal.controller.BlockMinter;
import org.qortal.data.account.RewardShareData;
import org.qortal.data.block.BlockData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AccountUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;

import static org.junit.Assert.*;

public class LevelZeroMintingTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
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
	public void testLevelZeroUsesLevelOneMintingWeight() {
		byte[] parentSignature = new byte[64];
		byte[] minterPublicKey = new byte[32];

		assertEquals(
				"Level-zero minting should use the same minimum key-distance divisor as level one",
				Block.calcKeyDistance(1, parentSignature, minterPublicKey, 1),
				Block.calcKeyDistance(1, parentSignature, minterPublicKey, 0)
		);
	}
}
