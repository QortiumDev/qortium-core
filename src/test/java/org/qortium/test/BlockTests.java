package org.qortium.test;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.block.Block;
import org.qortium.block.GenesisBlock;
import org.qortium.data.block.BlockData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.PaymentTestTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transform.Transformer;
import org.qortium.transform.TransformationException;
import org.qortium.transform.block.BlockTransformation;
import org.qortium.transform.block.BlockTransformer;
import org.qortium.transform.transaction.TransactionTransformer;

import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class BlockTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testGenesisBlockTransactions() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			GenesisBlock block = GenesisBlock.getInstance(repository);

			assertNotNull(block);
			assertEquals(Block.CURRENT_VERSION, block.getBlockData().getVersion());
			assertTrue(block.isSignatureValid());
			// only true if blockchain is empty
			// assertTrue(block.isValid());

			List<Transaction> transactions = block.getTransactions();
			assertNotNull(transactions);

			byte[] lastGenesisSignature = null;
			for (Transaction transaction : transactions) {
				assertNotNull(transaction);

				TransactionData transactionData = transaction.getTransactionData();

				if (transactionData.getType() != Transaction.TransactionType.GENESIS)
					continue;

				assertEquals(0L, (long) transactionData.getFee());
				assertTrue(transaction.isSignatureValid());
				assertEquals(Transaction.ValidationResult.OK, transaction.isValid());

				lastGenesisSignature = transactionData.getSignature();
			}

			// Attempt to load last GENESIS transaction directly from database
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(lastGenesisSignature);
			assertNotNull(transactionData);

			assertEquals(Transaction.TransactionType.GENESIS, transactionData.getType());
			assertEquals(0L, (long) transactionData.getFee());
			// assertNull(transactionData.getReference());

			Transaction transaction = Transaction.fromData(repository, transactionData);
			assertNotNull(transaction);

			assertTrue(transaction.isSignatureValid());
			assertEquals(Transaction.ValidationResult.OK, transaction.isValid());
		}
	}

	@Test
	public void testMinterSignatureUsesFullParentSignature() {
		byte[] parentSignature = new byte[BlockTransformer.BLOCK_SIGNATURE_LENGTH];
		byte[] nextMinterPublicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
		byte[] encodedOnlineAccounts = new byte[] { 1, 2, 3, 4 };

		for (int i = 0; i < parentSignature.length; ++i)
			parentSignature[i] = (byte) (i + 1);

		for (int i = 0; i < nextMinterPublicKey.length; ++i)
			nextMinterPublicKey[i] = (byte) (i + 11);

		byte[] expected = new byte[parentSignature.length + nextMinterPublicKey.length + encodedOnlineAccounts.length];
		System.arraycopy(parentSignature, 0, expected, 0, parentSignature.length);
		System.arraycopy(nextMinterPublicKey, 0, expected, parentSignature.length, nextMinterPublicKey.length);
		System.arraycopy(encodedOnlineAccounts, 0, expected, parentSignature.length + nextMinterPublicKey.length, encodedOnlineAccounts.length);

		byte[] parentMinterSignature = Arrays.copyOfRange(parentSignature, 0, Transformer.SIGNATURE_LENGTH);
		byte[] parentTransactionsSignature = Arrays.copyOfRange(parentSignature, Transformer.SIGNATURE_LENGTH, BlockTransformer.BLOCK_SIGNATURE_LENGTH);

		BlockData parentBlockData = new BlockData(Block.CURRENT_VERSION, new byte[BlockTransformer.BLOCK_SIGNATURE_LENGTH], 0, 0L, parentTransactionsSignature, 123, 456L,
				new byte[Transformer.PUBLIC_KEY_LENGTH], parentMinterSignature, 0, 0L);
		BlockData childBlockData = new BlockData(Block.CURRENT_VERSION, parentSignature, 0, 0L, null, 124, 457L,
				nextMinterPublicKey, null, 0, 0L, encodedOnlineAccounts, 0, null, null);

		assertArrayEquals(expected, BlockTransformer.getBytesForMinterSignature(parentBlockData, nextMinterPublicKey, encodedOnlineAccounts));
		assertArrayEquals(expected, BlockTransformer.getBytesForMinterSignature(childBlockData));
	}

	@Test
	public void testBlockSerializationWithoutTransactions() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Block block = BlockUtils.mintBlock(repository);
			assertEquals(Block.CURRENT_VERSION, block.getBlockData().getVersion());
			BlockTransformation blockInfo = assertBlockSerializationRoundTrip(block);

			assertEquals("Transaction count differs", 0, blockInfo.getTransactions().size());
			assertTrue("Unexpected transactions in serialized block", blockInfo.getTransactions().isEmpty());
		}
	}

	@Test
	public void testIncorrectBlockVersionRejected() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			BlockData parentBlockData = repository.getBlockRepository().getLastBlock();
			PrivateKeyAccount mintingAccount = Common.getTestAccount(repository, "alice-reward-share");
			Block validBlock = Block.mint(repository, parentBlockData, mintingAccount);
			assertNotNull(validBlock);

			BlockData validBlockData = validBlock.getBlockData();
			BlockData wrongVersionBlockData = new BlockData(Block.CURRENT_VERSION + 1, validBlockData.getReference(),
					validBlockData.getTransactionCount(), validBlockData.getTotalFees(), validBlockData.getTransactionsSignature(),
					validBlockData.getHeight(), validBlockData.getTimestamp(), validBlockData.getMinterPublicKey(),
					validBlockData.getMinterSignature(), validBlockData.getATCount(), validBlockData.getATFees(),
					validBlockData.getEncodedOnlineAccounts(), validBlockData.getOnlineAccountsCount(),
					validBlockData.getOnlineAccountsTimestamp(), validBlockData.getOnlineAccountsSignatures());

			assertEquals(Block.ValidationResult.VERSION_INCORRECT, new Block(repository, wrongVersionBlockData).isValid());
		}
	}

	@Test
	public void testBlockSerializationWithPaymentTransaction() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount signingAccount = Common.getTestAccount(repository, "alice");
			TransactionData paymentTransactionData = PaymentTestTransaction.randomTransaction(repository, signingAccount, true);

			TransactionUtils.signAndImportValid(repository, paymentTransactionData, signingAccount);

			Block block = BlockUtils.mintBlock(repository);
			BlockTransformation blockInfo = assertBlockSerializationRoundTrip(block);

			assertEquals("Transaction count differs", 1, blockInfo.getTransactions().size());
			assertSerializedTransactionEquals(block.getTransactions().get(0).getTransactionData(), blockInfo.getTransactions().get(0));
		}
	}

	private BlockTransformation assertBlockSerializationRoundTrip(Block block) throws DataException, TransformationException {
		assertTrue(block.isSignatureValid());

		byte[] bytes = BlockTransformer.toBytes(block);
		assertEquals(BlockTransformer.getDataLength(block), bytes.length);

		BlockTransformation blockInfo = BlockTransformer.fromBytes(bytes);
		BlockUtils.assertEqual(block.getBlockData(), blockInfo.getBlockData());
		assertEquals("Transaction count differs", block.getBlockData().getTransactionCount(), blockInfo.getTransactions().size());

		return blockInfo;
	}

	private void assertSerializedTransactionEquals(TransactionData expected, TransactionData actual) throws TransformationException {
		assertEquals("Transaction type differs", expected.getType(), actual.getType());
		assertArrayEquals("Transaction signature differs", expected.getSignature(), actual.getSignature());
		assertEquals("Transaction declared length differs", TransactionTransformer.getDataLength(expected), TransactionTransformer.getDataLength(actual));
		assertArrayEquals("Transaction serialized bytes differ", TransactionTransformer.toBytes(expected), TransactionTransformer.toBytes(actual));
	}

	@Test
	public void testLatestBlockCacheWithLatestBlock() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Deque<BlockData> latestBlockCache = buildLatestBlockCache(repository, 20);

			BlockData latestBlock = repository.getBlockRepository().getLastBlock();
			byte[] parentSignature = latestBlock.getSignature();

			List<BlockData> childBlocks = findCachedChildBlocks(latestBlockCache, parentSignature);

			assertEquals(true, childBlocks.isEmpty());
		}
	}

	@Test
	public void testLatestBlockCacheWithPenultimateBlock() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Deque<BlockData> latestBlockCache = buildLatestBlockCache(repository, 20);

			BlockData latestBlock = repository.getBlockRepository().getLastBlock();
			BlockData penultimateBlock = repository.getBlockRepository().fromHeight(latestBlock.getHeight() - 1);
			byte[] parentSignature = penultimateBlock.getSignature();

			List<BlockData> childBlocks = findCachedChildBlocks(latestBlockCache, parentSignature);

			assertEquals(false, childBlocks.isEmpty());
			assertEquals(1, childBlocks.size());

			BlockData expectedBlock = latestBlock;
			BlockData actualBlock = childBlocks.get(0);
			assertArrayEquals(expectedBlock.getSignature(), actualBlock.getSignature());
		}
	}

	@Test
	public void testLatestBlockCacheWithMiddleBlock() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Deque<BlockData> latestBlockCache = buildLatestBlockCache(repository, 20);

			int tipOffset = 5;

			BlockData latestBlock = repository.getBlockRepository().getLastBlock();
			BlockData parentBlock = repository.getBlockRepository().fromHeight(latestBlock.getHeight() - tipOffset);
			byte[] parentSignature = parentBlock.getSignature();

			List<BlockData> childBlocks = findCachedChildBlocks(latestBlockCache, parentSignature);

			assertEquals(false, childBlocks.isEmpty());
			assertEquals(tipOffset, childBlocks.size());

			BlockData expectedFirstBlock = repository.getBlockRepository().fromHeight(parentBlock.getHeight() + 1);
			BlockData actualFirstBlock = childBlocks.get(0);
			assertArrayEquals(expectedFirstBlock.getSignature(), actualFirstBlock.getSignature());

			BlockData expectedLastBlock = latestBlock;
			BlockData actualLastBlock = childBlocks.get(childBlocks.size() - 1);
			assertArrayEquals(expectedLastBlock.getSignature(), actualLastBlock.getSignature());
		}
	}

	@Test
	public void testLatestBlockCacheWithFirstBlock() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Deque<BlockData> latestBlockCache = buildLatestBlockCache(repository, 20);

			int tipOffset = latestBlockCache.size();

			BlockData latestBlock = repository.getBlockRepository().getLastBlock();
			BlockData parentBlock = repository.getBlockRepository().fromHeight(latestBlock.getHeight() - tipOffset);
			byte[] parentSignature = parentBlock.getSignature();

			List<BlockData> childBlocks = findCachedChildBlocks(latestBlockCache, parentSignature);

			assertEquals(false, childBlocks.isEmpty());
			assertEquals(tipOffset, childBlocks.size());

			BlockData expectedFirstBlock = repository.getBlockRepository().fromHeight(parentBlock.getHeight() + 1);
			BlockData actualFirstBlock = childBlocks.get(0);
			assertArrayEquals(expectedFirstBlock.getSignature(), actualFirstBlock.getSignature());

			BlockData expectedLastBlock = latestBlock;
			BlockData actualLastBlock = childBlocks.get(childBlocks.size() - 1);
			assertArrayEquals(expectedLastBlock.getSignature(), actualLastBlock.getSignature());
		}
	}

	@Test
	public void testLatestBlockCacheWithNoncachedBlock() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Deque<BlockData> latestBlockCache = buildLatestBlockCache(repository, 20);

			int tipOffset = latestBlockCache.size() + 1; // outside of cache

			BlockData latestBlock = repository.getBlockRepository().getLastBlock();
			BlockData parentBlock = repository.getBlockRepository().fromHeight(latestBlock.getHeight() - tipOffset);
			byte[] parentSignature = parentBlock.getSignature();

			List<BlockData> childBlocks = findCachedChildBlocks(latestBlockCache, parentSignature);

			assertEquals(true, childBlocks.isEmpty());
		}
	}

	private Deque<BlockData> buildLatestBlockCache(Repository repository, int count) throws DataException {
		Deque<BlockData> latestBlockCache = new LinkedList<>();

		// Mint some blocks
		for (int h = 0; h < count; ++h)
			latestBlockCache.addLast(BlockUtils.mintBlock(repository).getBlockData());

		// Reduce cache down to latest 10 blocks
		while (latestBlockCache.size() > 10)
			latestBlockCache.removeFirst();

		return latestBlockCache;
	}

	private List<BlockData> findCachedChildBlocks(Deque<BlockData> latestBlockCache, byte[] parentSignature) {
		return latestBlockCache.stream()
				.dropWhile(cachedBlockData -> !Arrays.equals(cachedBlockData.getReference(), parentSignature))
				.collect(Collectors.toList());
	}

	@Test
	public void testCommonBlockSearch() {
		// Given a list of block summaries, trim all trailing summaries after common block

		// We'll represent known block summaries as a list of booleans,
		// where the boolean value indicates whether peer's block is also in our repository.

		// Trivial case, single element array
		assertCommonBlock(0, new boolean[] { true });

		// Test odd and even array lengths
		for (int arrayLength = 5; arrayLength <= 6; ++arrayLength) {
			boolean[] testBlocks = new boolean[arrayLength];

			// Test increasing amount of common blocks
			for (int c = 1; c <= testBlocks.length; ++c) {
				testBlocks[c - 1] = true;

				assertCommonBlock(c - 1, testBlocks);
			}
		}
	}

	private void assertCommonBlock(int expectedIndex, boolean[] testBlocks) {
		int commonBlockIndex = findCommonBlockIndex(testBlocks);
		assertEquals(expectedIndex, commonBlockIndex);
	}

	private int findCommonBlockIndex(boolean[] testBlocks) {
		int low = 1;
		int high = testBlocks.length - 1;

		while (low <= high) {
			int mid = (low + high) >>> 1;

			if (testBlocks[mid])
				low = mid + 1;
			else
				high = mid - 1;
		}

		return low - 1;
	}

}
