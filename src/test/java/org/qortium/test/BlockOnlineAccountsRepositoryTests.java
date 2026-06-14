package org.qortium.test;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.block.Block;
import org.qortium.controller.BlockMinter;
import org.qortium.data.block.DecodedOnlineAccountData;
import org.qortium.data.transaction.RegisterNameTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.RegisterNameTransaction;
import org.qortium.transform.Transformer;
import org.qortium.utils.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the local (non-consensus) per-block online-accounts index on BlockRepository.
 * These exercise the BlockOnlineAccounts table directly (save / fetch / replace / delete)
 * without needing to mint or process real blocks.
 */
public class BlockOnlineAccountsRepositoryTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	private static byte[] publicKey(int seed) {
		byte[] publicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
		for (int i = 0; i < publicKey.length; ++i)
			publicKey[i] = (byte) (seed + i);
		return publicKey;
	}

	@Test
	public void testUnindexedHeightReturnsNull() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// A height the node has never indexed must be distinguishable from "indexed but empty".
			assertNull(repository.getBlockRepository().getOnlineRewardSharePublicKeys(12345));
		}
	}

	@Test
	public void testSaveAndFetchPreservesPublicKeys() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<byte[]> publicKeys = new ArrayList<>();
			publicKeys.add(publicKey(1));
			publicKeys.add(publicKey(40));
			publicKeys.add(publicKey(200));

			repository.getBlockRepository().saveOnlineRewardSharePublicKeys(500, publicKeys);
			repository.saveChanges();

			List<byte[]> fetched = repository.getBlockRepository().getOnlineRewardSharePublicKeys(500);

			assertNotNull(fetched);
			assertEquals(publicKeys.size(), fetched.size());
			for (int i = 0; i < publicKeys.size(); ++i) {
				assertEquals(Transformer.PUBLIC_KEY_LENGTH, fetched.get(i).length);
				assertArrayEquals(publicKeys.get(i), fetched.get(i));
			}
		}
	}

	@Test
	public void testEmptyListIsIndexedNotNull() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// A block with no online accounts is still "indexed": empty list, not null.
			repository.getBlockRepository().saveOnlineRewardSharePublicKeys(501, new ArrayList<>());
			repository.saveChanges();

			List<byte[]> fetched = repository.getBlockRepository().getOnlineRewardSharePublicKeys(501);

			assertNotNull(fetched);
			assertTrue(fetched.isEmpty());
		}
	}

	@Test
	public void testSaveReplacesExistingEntry() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<byte[]> first = new ArrayList<>(Arrays.asList(publicKey(1), publicKey(2)));
			repository.getBlockRepository().saveOnlineRewardSharePublicKeys(502, first);
			repository.saveChanges();

			List<byte[]> second = new ArrayList<>(Arrays.asList(publicKey(9)));
			repository.getBlockRepository().saveOnlineRewardSharePublicKeys(502, second);
			repository.saveChanges();

			List<byte[]> fetched = repository.getBlockRepository().getOnlineRewardSharePublicKeys(502);

			assertNotNull(fetched);
			assertEquals(1, fetched.size());
			assertArrayEquals(publicKey(9), fetched.get(0));
		}
	}

	@Test
	public void testProcessPopulatesIndexAndSurvivesSelfShareChange() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloe = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, "dilbert");

			// alice (a founder) already has a self-share and is an eligible online account.
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");

			// Mint a block with alice online.
			Block block = BlockMinter.mintTestingBlock(repository, aliceSelfShare);
			int height = block.getBlockData().getHeight();
			assertEquals(1, block.getBlockData().getOnlineAccountsCount());

			// process() must have populated the local index with alice's reward-share public key.
			List<byte[]> indexed = repository.getBlockRepository().getOnlineRewardSharePublicKeys(height);
			assertNotNull(indexed);
			assertEquals(1, indexed.size());
			assertArrayEquals(aliceSelfShare.getPublicKey(), indexed.get(0));

			// The decoded online accounts resolve to alice.
			Set<String> mintersBefore = decodedMinters(repository, height);
			assertTrue(mintersBefore.contains(alice.getAddress()));

			// Change the self-share set (adds rows, shifting positional indices). The legacy
			// positional decode would drift; the local index must keep returning the same account.
			AccountUtils.generateSelfShares(repository, List.of(bob, chloe, dilbert));

			Set<String> mintersAfter = decodedMinters(repository, height);
			assertEquals(mintersBefore, mintersAfter);
			assertTrue(mintersAfter.contains(alice.getAddress()));

			// Orphaning back past our block removes its local index entry (orphan hook).
			// generateSelfShares() mints its own block(s), so orphan from the current tip down to height.
			int orphanCount = repository.getBlockRepository().getBlockchainHeight() - height + 1;
			BlockUtils.orphanBlocks(repository, orphanCount);
			assertNull(repository.getBlockRepository().getOnlineRewardSharePublicKeys(height));
		}
	}

	@Test
	public void testDecodedOnlineAccountsToleratesAccountWithMultipleNames() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount aliceSelfShare = Common.getTestAccount(repository, "alice-reward-share");

			// One account owning more than one name used to make the decoded online-accounts
			// lookup throw on a duplicate map key (owner address), silently returning empty.
			registerName(repository, alice, "alice-name-one");
			registerName(repository, alice, "alice-name-two");

			Block block = BlockMinter.mintTestingBlock(repository, aliceSelfShare);
			int height = block.getBlockData().getHeight();

			Set<String> minters = decodedMinters(repository, height);
			assertTrue(minters.contains(alice.getAddress()));
		}
	}

	private static void registerName(Repository repository, PrivateKeyAccount account, String name) throws DataException {
		RegisterNameTransactionData data = new RegisterNameTransactionData(TestTransaction.generateBase(account), name, "");
		data.setFee(new RegisterNameTransaction(null, null).getUnitFee(data.getTimestamp()));
		TransactionUtils.signAndMint(repository, data, account);
	}

	private static Set<String> decodedMinters(Repository repository, int height) throws DataException {
		Set<DecodedOnlineAccountData> decoded = Blocks.getDecodedOnlineAccountsForBlock(
				repository, repository.getBlockRepository().fromHeight(height));

		return decoded.stream().map(DecodedOnlineAccountData::getMinter).collect(Collectors.toSet());
	}

	@Test
	public void testDeleteRemovesEntry() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			repository.getBlockRepository().saveOnlineRewardSharePublicKeys(503, new ArrayList<>(Arrays.asList(publicKey(5))));
			repository.saveChanges();
			assertNotNull(repository.getBlockRepository().getOnlineRewardSharePublicKeys(503));

			repository.getBlockRepository().deleteOnlineRewardSharePublicKeys(503);
			repository.saveChanges();

			// Back to "not indexed".
			assertNull(repository.getBlockRepository().getOnlineRewardSharePublicKeys(503));
		}
	}
}
