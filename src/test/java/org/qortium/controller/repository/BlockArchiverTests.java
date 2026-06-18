package org.qortium.controller.repository;

import org.junit.Test;
import org.qortium.block.BlockChain;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class BlockArchiverTests {

	@Test
	public void testSelectsLowestCheckpointInsideArchiveRange() {
		List<BlockChain.Checkpoint> checkpoints = List.of(
				checkpoint(250),
				checkpoint(100),
				checkpoint(400));

		assertEquals(100, BlockArchiver.selectCheckpointArchiveHeight(checkpoints, 0, 300));
		assertEquals(250, BlockArchiver.selectCheckpointArchiveHeight(checkpoints, 101, 300));
	}

	@Test
	public void testIgnoresCheckpointsOutsideArchiveRange() {
		List<BlockChain.Checkpoint> checkpoints = List.of(
				checkpoint(50),
				checkpoint(500));

		assertEquals(0, BlockArchiver.selectCheckpointArchiveHeight(checkpoints, 100, 300));
	}

	@Test
	public void testClampsArchiveStartToFirstArchivedBlock() {
		List<BlockChain.Checkpoint> checkpoints = List.of(checkpoint(2));

		assertEquals(2, BlockArchiver.selectCheckpointArchiveHeight(checkpoints, 0, 10));
		assertEquals(0, BlockArchiver.selectCheckpointArchiveHeight(checkpoints, 3, 10));
	}

	@Test
	public void testNoCheckpointsMeansNoCheckpointTarget() {
		assertEquals(0, BlockArchiver.selectCheckpointArchiveHeight(List.of(), 0, 300));
		assertEquals(0, BlockArchiver.selectCheckpointArchiveHeight(null, 0, 300));
	}

	private static BlockChain.Checkpoint checkpoint(int height) {
		BlockChain.Checkpoint checkpoint = new BlockChain.Checkpoint();
		checkpoint.height = height;
		checkpoint.signature = "test";
		return checkpoint;
	}
}
