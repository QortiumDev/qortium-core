package org.qortium.controller;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers the pure arithmetic behind GET_BLOCKS auto-degrade (Settings.blocksBatchAutoDegrade): on a
 * response timeout, {@link Synchronizer#nextBlocksCountAfterTimeout(int)} halves the requested block
 * count (floor 1) for an immediate retry within the same call; on success at a possibly-degraded count,
 * {@link Synchronizer#nextBlocksCountAfterSuccess(int, int)} lets the NEXT (separate) request try double
 * the count again, capped at maxBlocksPerRequest.
 */
public class SynchronizerBlocksAutoDegradeTests {

	@Test
	public void testHalvingSequenceFrom100ReachesOne() {
		List<Integer> sequence = new ArrayList<>();
		int count = 100;
		sequence.add(count);
		while (count > 1) {
			count = Synchronizer.nextBlocksCountAfterTimeout(count);
			sequence.add(count);
		}

		assertEquals(java.util.Arrays.asList(100, 50, 25, 12, 6, 3, 1), sequence);
		// A genuinely dead peer costs at most this many timeouts (~log2(100)+1), never unbounded.
		assertTrue("halving sequence must be bounded (~log2(100)+1 attempts)", sequence.size() <= 8);
	}

	@Test
	public void testHalvingNeverGoesBelowOne() {
		assertEquals(1, Synchronizer.nextBlocksCountAfterTimeout(1));
		assertEquals(1, Synchronizer.nextBlocksCountAfterTimeout(0));
	}

	@Test
	public void testHalvingSmallCounts() {
		assertEquals(1, Synchronizer.nextBlocksCountAfterTimeout(2));
		assertEquals(1, Synchronizer.nextBlocksCountAfterTimeout(3));
		assertEquals(2, Synchronizer.nextBlocksCountAfterTimeout(4));
	}

	@Test
	public void testRecoveryDoublesAfterSuccess() {
		assertEquals(2, Synchronizer.nextBlocksCountAfterSuccess(1, 100));
		assertEquals(4, Synchronizer.nextBlocksCountAfterSuccess(2, 100));
		assertEquals(100, Synchronizer.nextBlocksCountAfterSuccess(64, 100));
	}

	@Test
	public void testRecoveryCappedAtMaxBlocksPerRequest() {
		// Recovery doubling never exceeds the configured maxBlocksPerRequest.
		assertEquals(100, Synchronizer.nextBlocksCountAfterSuccess(80, 100));
		assertEquals(1, Synchronizer.nextBlocksCountAfterSuccess(1, 1));
	}

	@Test
	public void testFullDegradeThenRecoverRoundTrip() {
		// Simulate: 100 requested, times out down to 1, succeeds at 1, then recovers back toward 100
		// across successive (separate) requests, capping at maxBlocksPerRequest.
		int max = 100;
		int count = 100;
		while (count > 1) {
			count = Synchronizer.nextBlocksCountAfterTimeout(count);
		}
		assertEquals(1, count);

		// Successive successful requests should recover: 1 -> 2 -> 4 -> 8 -> 16 -> 32 -> 64 -> 100 (capped)
		int[] expectedRecovery = {2, 4, 8, 16, 32, 64, 100, 100};
		for (int expected : expectedRecovery) {
			count = Synchronizer.nextBlocksCountAfterSuccess(count, max);
			assertEquals(expected, count);
		}
	}
}
