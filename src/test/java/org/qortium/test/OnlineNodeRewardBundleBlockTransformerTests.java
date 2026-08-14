package org.qortium.test;

import org.junit.Before;
import org.junit.Test;
import org.qortium.block.Block;
import org.qortium.data.block.BlockData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.transform.Transformer;
import org.qortium.transform.TransformationException;
import org.qortium.transform.block.BlockTransformation;
import org.qortium.transform.block.BlockTransformer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class OnlineNodeRewardBundleBlockTransformerTests extends Common {

	private static final long BUNDLE_EPOCH = 123456789L;
	private static final byte[] EMPTY_COHORT = ByteBuffer.allocate(Integer.BYTES).putInt(0).array();

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testBlockDataBundleAliasIsVersionAware() {
		byte[] legacySignatures = new byte[Transformer.SIGNATURE_LENGTH];
		BlockData legacy = blockData(Block.CURRENT_VERSION, 1L, legacySignatures);
		assertNull(legacy.getOnlineAccountBundles());
		assertEquals(1, legacy.getOnlineAccountsSignaturesCount());

		BlockData bundleAware = blockData(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION, BUNDLE_EPOCH, EMPTY_COHORT);
		assertArrayEquals(EMPTY_COHORT, bundleAware.getOnlineAccountBundles());
		assertEquals(0, bundleAware.getOnlineAccountsSignaturesCount());
	}

	@Test
	public void testBundleAwareMinterPreimagePinsEveryField() {
		byte[] encodedOnlineAccounts = new byte[] { 1, 2, 3, 4 };
		byte[] cohort = new byte[] { 5, 6, 7, 8 };
		BlockData blockData = blockData(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION, BUNDLE_EPOCH, cohort,
				encodedOnlineAccounts);

		byte[] expected = ByteBuffer.allocate(BlockTransformer.BLOCK_SIGNATURE_LENGTH + Transformer.PUBLIC_KEY_LENGTH
				+ Integer.BYTES + Integer.BYTES + encodedOnlineAccounts.length + 1
				+ Long.BYTES + Integer.BYTES + cohort.length)
				.put(blockData.getReference())
				.put(blockData.getMinterPublicKey())
				.putInt(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION)
				.putInt(encodedOnlineAccounts.length)
				.put(encodedOnlineAccounts)
				.put((byte) 1)
				.putLong(BUNDLE_EPOCH)
				.putInt(cohort.length)
				.put(cohort)
				.array();

		assertArrayEquals(expected, BlockTransformer.getBytesForMinterSignature(blockData));

		cohort[cohort.length - 1] ^= 1;
		assertFalse(Arrays.equals(expected, BlockTransformer.getBytesForMinterSignature(blockData)));
	}

	@Test
	public void testBundleAwareMinterPreimageRepresentsAbsentPayload() {
		BlockData blockData = blockData(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION, null, null);
		byte[] preimage = BlockTransformer.getBytesForMinterSignature(blockData);

		int presenceOffset = BlockTransformer.BLOCK_SIGNATURE_LENGTH + Transformer.PUBLIC_KEY_LENGTH
				+ Integer.BYTES + Integer.BYTES;
		assertEquals(presenceOffset + 1, preimage.length);
		assertEquals(0, preimage[presenceOffset]);
	}

	@Test
	public void testBundleAwareBlockRoundTrip() throws DataException, TransformationException {
		try (Repository repository = RepositoryManager.getRepository()) {
			BlockData blockData = blockData(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION, BUNDLE_EPOCH, EMPTY_COHORT);
			Block block = new Block(repository, blockData, Collections.emptyList(), Collections.emptyList());

			byte[] serialized = BlockTransformer.toBytes(block);
			assertEquals(BlockTransformer.getDataLength(block), serialized.length);

			BlockTransformation decoded = BlockTransformer.fromBytes(serialized);
			assertEquals(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION, decoded.getBlockData().getVersion());
			assertEquals(Long.valueOf(BUNDLE_EPOCH), decoded.getBlockData().getOnlineAccountsTimestamp());
			assertArrayEquals(EMPTY_COHORT, decoded.getBlockData().getOnlineAccountBundles());
		}
	}

	@Test
	public void testBundleAwareBlockRejectsTruncatedPayload() throws DataException, TransformationException {
		try (Repository repository = RepositoryManager.getRepository()) {
			BlockData blockData = blockData(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION, BUNDLE_EPOCH, EMPTY_COHORT);
			Block block = new Block(repository, blockData, Collections.emptyList(), Collections.emptyList());
			byte[] serialized = BlockTransformer.toBytes(block);

			byte[] truncated = Arrays.copyOf(serialized, serialized.length - 1);
			assertThrows(TransformationException.class, () -> BlockTransformer.fromBytes(truncated));
		}
	}

	@Test
	public void testBundleAwareBlockRejectsTimestampWithoutPayload() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			BlockData blockData = blockData(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION, BUNDLE_EPOCH, null);
			Block block = new Block(repository, blockData, Collections.emptyList(), Collections.emptyList());

			assertThrows(IllegalStateException.class, () -> BlockTransformer.toBytes(block));
			assertThrows(IllegalStateException.class, () -> BlockTransformer.getBytesForMinterSignature(blockData));
		}
	}

	@Test
	public void testSignatureTrimmingPreservesBundlePayload() throws DataException {
		try (Repository repository = RepositoryManager.getRepository()) {
			BlockData legacy = repository.getBlockRepository().fromHeight(1);
			legacy.setOnlineAccountsSignatures(new byte[Transformer.SIGNATURE_LENGTH]);
			repository.getBlockRepository().save(legacy);

			Block minted = BlockUtils.mintBlock(repository);
			BlockData mintedData = minted.getBlockData();
			BlockData bundleAware = new BlockData(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION,
					mintedData.getReference(), mintedData.getTransactionCount(), mintedData.getTotalFees(),
					mintedData.getTransactionsSignature(), mintedData.getHeight(), mintedData.getTimestamp(),
					mintedData.getMinterPublicKey(), mintedData.getMinterSignature(), mintedData.getATCount(),
					mintedData.getATFees(), mintedData.getEncodedOnlineAccounts(), mintedData.getOnlineAccountsCount(),
					BUNDLE_EPOCH, EMPTY_COHORT);
			repository.getBlockRepository().save(bundleAware);

			repository.getBlockRepository().trimOldOnlineAccountsSignatures(1, bundleAware.getHeight());

			assertNull(repository.getBlockRepository().fromHeight(1).getOnlineAccountsSignatures());
			assertArrayEquals(EMPTY_COHORT,
					repository.getBlockRepository().fromHeight(bundleAware.getHeight()).getOnlineAccountBundles());
			repository.discardChanges();
		}
	}

	private static BlockData blockData(int version, Long epoch, byte[] onlineData) {
		return blockData(version, epoch, onlineData, new byte[0]);
	}

	private static BlockData blockData(int version, Long epoch, byte[] onlineData, byte[] encodedOnlineAccounts) {
		return new BlockData(version, new byte[BlockTransformer.BLOCK_SIGNATURE_LENGTH], 0, 0L,
				new byte[Transformer.SIGNATURE_LENGTH], 1, 2L,
				new byte[Transformer.PUBLIC_KEY_LENGTH], new byte[Transformer.SIGNATURE_LENGTH], 0, 0L,
				encodedOnlineAccounts, 0, epoch, onlineData);
	}
}
