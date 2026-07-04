package org.qortium.network.message;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BlocksMessageTests {

	@Test
	public void testBoundedBuilderIncludesBlocksUnderBudget() throws Exception {
		BlocksMessage.BoundedBuilder builder = builderWithPayloadBudget(64);

		assertTrue(builder.tryAdd(serializedBlock(1, 10)));
		assertTrue(builder.tryAdd(serializedBlock(2, 20)));

		BlocksMessage.BoundedBuildResult result = builder.build();

		assertFalse(result.isTruncated());
		assertEquals(2, result.getBlockCount());
		assertEquals(4 + 4 + 10 + 4 + 20, result.getPayloadLength());
		assertEquals(2, serializedCount(result.getMessage()));
	}

	@Test
	public void testBoundedBuilderTruncatesBeforeExceedingBudget() throws Exception {
		BlocksMessage.BoundedBuilder builder = builderWithPayloadBudget(30);

		assertTrue(builder.tryAdd(serializedBlock(1, 10)));
		assertFalse(builder.tryAdd(serializedBlock(2, 20)));

		BlocksMessage.BoundedBuildResult result = builder.build();

		assertTrue(result.isTruncated());
		assertFalse(result.isFirstBlockOversized());
		assertEquals(1, result.getBlockCount());
		assertEquals(18, result.getPayloadLength());
		assertEquals(Integer.valueOf(2), result.getFirstExcludedHeight());
		assertEquals(24, result.getFirstExcludedPayloadLength());
		assertEquals(1, serializedCount(result.getMessage()));
	}

	@Test
	public void testBoundedBuilderAllowsExactBudget() throws Exception {
		BlocksMessage.BoundedBuilder builder = builderWithPayloadBudget(18);

		assertTrue(builder.tryAdd(serializedBlock(7, 10)));

		BlocksMessage.BoundedBuildResult result = builder.build();

		assertFalse(result.isTruncated());
		assertEquals(1, result.getBlockCount());
		assertEquals(18, result.getPayloadLength());
		assertEquals(1, serializedCount(result.getMessage()));
	}

	@Test
	public void testBoundedBuilderDetectsFirstOversizedBlock() throws Exception {
		BlocksMessage.BoundedBuilder builder = builderWithPayloadBudget(17);

		assertFalse(builder.tryAdd(serializedBlock(7, 10)));

		BlocksMessage.BoundedBuildResult result = builder.build();

		assertTrue(result.isTruncated());
		assertTrue(result.isFirstBlockOversized());
		assertEquals(0, result.getBlockCount());
		assertEquals(4, result.getPayloadLength());
		assertEquals(Integer.valueOf(7), result.getFirstExcludedHeight());
		assertEquals(14, result.getFirstExcludedPayloadLength());
		assertEquals(0, serializedCount(result.getMessage()));
	}

	@Test
	public void testBoundedBuilderBuildsEmptyBlocksMessage() throws Exception {
		BlocksMessage.BoundedBuildResult result = builderWithPayloadBudget(64).build();

		assertFalse(result.isTruncated());
		assertEquals(0, result.getBlockCount());
		assertEquals(4, result.getPayloadLength());
		assertEquals(0, serializedCount(result.getMessage()));
	}

	private static BlocksMessage.BoundedBuilder builderWithPayloadBudget(int payloadBudget) {
		return BlocksMessage.newBoundedBuilder(payloadBudget + 1024);
	}

	private static BlocksMessage.SerializedBlock serializedBlock(int height, int blockByteCount) {
		return new BlocksMessage.SerializedBlock(null, height, new byte[blockByteCount]);
	}

	private static int serializedCount(BlocksMessage message) {
		return ByteBuffer.wrap(message.dataBytes).getInt();
	}
}
