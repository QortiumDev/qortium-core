package org.qortium.controller;

import org.junit.Test;
import org.qortium.data.block.ArchiveChunkData;
import org.qortium.data.block.ArchiveManifest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the archive fast-sync manifest-coverage gate. This is the check that refuses to fast-replay
 * when a peer's (untrusted) manifest does not contiguously cover height 2 up to the pinned checkpoint: without
 * such coverage the checkpoint cross-bind can't be reached, so the chain prefix is never anchored and must not
 * be trusted. The selection also stops exactly at the chunk spanning the checkpoint (no over-fetching).
 */
public class ArchiveFastSyncManagerTests {

	private static ArchiveChunkData chunk(int start, int end) {
		// sha256/size are irrelevant to the coverage gate; the integrity checks happen later, per chunk.
		return new ArchiveChunkData(start, end, "00", end - start + 1);
	}

	private static ArchiveManifest manifest(ArchiveChunkData... chunks) {
		return new ArchiveManifest(1, Arrays.asList(chunks));
	}

	@Test
	public void testContiguousManifestCoveringCheckpointIsAccepted() {
		// 2..250, 251..500, 501..750 ; checkpoint 600 sits in the third chunk.
		ArchiveManifest manifest = manifest(chunk(2, 250), chunk(251, 500), chunk(501, 750));

		List<ArchiveChunkData> selected = ArchiveFastSyncManager.selectContiguousChunksCoveringCheckpoint(manifest, 600);

		// All three chunks are needed to reach the one spanning 600, and selection stops there.
		assertEquals(3, selected.size());
		assertEquals(2, selected.get(0).getStartHeight());
		assertEquals(750, selected.get(2).getEndHeight());
	}

	@Test
	public void testSelectionStopsAtCheckpointChunkAndDoesNotOverFetch() {
		// Checkpoint 300 lives in the second chunk; the third must NOT be included.
		ArchiveManifest manifest = manifest(chunk(2, 250), chunk(251, 500), chunk(501, 750));

		List<ArchiveChunkData> selected = ArchiveFastSyncManager.selectContiguousChunksCoveringCheckpoint(manifest, 300);

		assertEquals(2, selected.size());
		assertEquals(500, selected.get(selected.size() - 1).getEndHeight());
	}

	@Test
	public void testCheckpointOnChunkBoundaryIsAccepted() {
		// Checkpoint exactly at a chunk's end height is still "covered" by that chunk.
		ArchiveManifest manifest = manifest(chunk(2, 250), chunk(251, 500));

		List<ArchiveChunkData> selected = ArchiveFastSyncManager.selectContiguousChunksCoveringCheckpoint(manifest, 250);
		assertEquals(1, selected.size());

		// ...and at a chunk's start height.
		selected = ArchiveFastSyncManager.selectContiguousChunksCoveringCheckpoint(manifest, 251);
		assertEquals(2, selected.size());
	}

	@Test
	public void testUnsortedManifestIsSortedBeforeCoverageCheck() {
		// A peer may list chunks out of order; the gate must sort first, not reject.
		ArchiveManifest manifest = manifest(chunk(501, 750), chunk(2, 250), chunk(251, 500));

		List<ArchiveChunkData> selected = ArchiveFastSyncManager.selectContiguousChunksCoveringCheckpoint(manifest, 600);

		assertEquals(3, selected.size());
		assertEquals(2, selected.get(0).getStartHeight());
	}

	@Test
	public void testGapBeforeCheckpointIsRefused() {
		// 2..250 then 300..500 — a hole at 251..299 means the prefix isn't contiguous, so the checkpoint
		// at 400 can't be proven. Must refuse (null), never silently fast-sync a non-anchored chain.
		ArchiveManifest manifest = manifest(chunk(2, 250), chunk(300, 500));

		assertNull(ArchiveFastSyncManager.selectContiguousChunksCoveringCheckpoint(manifest, 400));
	}

	@Test
	public void testManifestNotReachingCheckpointIsRefused() {
		// Contiguous, but stops short of the checkpoint height.
		ArchiveManifest manifest = manifest(chunk(2, 250), chunk(251, 500));

		assertNull(ArchiveFastSyncManager.selectContiguousChunksCoveringCheckpoint(manifest, 600));
	}

	@Test
	public void testManifestNotStartingAtHeightTwoIsRefused() {
		// Archive must begin at height 2 (genesis block 1 is not archived); a manifest starting elsewhere
		// can't bootstrap a fresh node.
		ArchiveManifest manifest = manifest(chunk(10, 250), chunk(251, 500));

		assertNull(ArchiveFastSyncManager.selectContiguousChunksCoveringCheckpoint(manifest, 300));
	}

	@Test
	public void testMalformedRangeIsRefused() {
		// A malformed chunk (endHeight < startHeight) encountered while walking toward the checkpoint must be
		// refused. The checkpoint (300) lies beyond the first chunk, so the walk reaches the malformed one.
		ArchiveManifest manifest = manifest(chunk(2, 250), new ArchiveChunkData(251, 200, "00", 1));

		assertNull(ArchiveFastSyncManager.selectContiguousChunksCoveringCheckpoint(manifest, 300));
	}

	@Test
	public void testEmptyManifestIsRefused() {
		assertNull(ArchiveFastSyncManager.selectContiguousChunksCoveringCheckpoint(manifest(), 100));
		assertNull(ArchiveFastSyncManager.selectContiguousChunksCoveringCheckpoint(new ArchiveManifest(1, Collections.emptyList()), 100));
	}

	private static ArchiveChunkData sized(long size) {
		return new ArchiveChunkData(2, 2, "00", size);
	}

	@Test
	public void testReplayBudgetAcceptsSaneManifest() {
		// checkpoint 1000 -> budget = 1000 * 256KiB = 250 MiB; a single 200 MiB chunk fits.
		assertTrue(ArchiveFastSyncManager.isWithinReplayBudget(Arrays.asList(sized(200L * 1024 * 1024)), 1000));
	}

	@Test
	public void testReplayBudgetRejectsOversizedTotal() {
		// Two 200 MiB chunks (each under the per-chunk cap) total 400 MiB > the 250 MiB budget for height 1000.
		assertFalse(ArchiveFastSyncManager.isWithinReplayBudget(
				Arrays.asList(sized(200L * 1024 * 1024), sized(200L * 1024 * 1024)), 1000));
	}

	@Test
	public void testReplayBudgetRejectsSingleOversizedChunk() {
		// A single chunk above the 256 MiB per-chunk cap is rejected regardless of the aggregate budget.
		assertFalse(ArchiveFastSyncManager.isWithinReplayBudget(Arrays.asList(sized(300L * 1024 * 1024)), 1_000_000));
	}

	@Test
	public void testReplayBudgetRejectsNonPositiveSize() {
		assertFalse(ArchiveFastSyncManager.isWithinReplayBudget(Arrays.asList(sized(0)), 1000));
		assertFalse(ArchiveFastSyncManager.isWithinReplayBudget(Arrays.asList(sized(-1)), 1000));
	}

	@Test
	public void testReplayBudgetRejectsMoreChunksThanBlocks() {
		// Can't legitimately have more chunks than blocks below the checkpoint.
		assertFalse(ArchiveFastSyncManager.isWithinReplayBudget(
				Arrays.asList(sized(1), sized(1), sized(1), sized(1), sized(1)), 3));
	}

	@Test
	public void testArchiveFastSyncPercentTracksReplayRange() {
		assertEquals(0, ArchiveFastSyncManager.calculateArchiveFastSyncPercent(2, 1, 501));
		assertEquals(20, ArchiveFastSyncManager.calculateArchiveFastSyncPercent(2, 101, 501));
		assertEquals(100, ArchiveFastSyncManager.calculateArchiveFastSyncPercent(2, 501, 501));
		assertEquals(100, ArchiveFastSyncManager.calculateArchiveFastSyncPercent(2, 600, 501));
	}

	@Test
	public void testArchiveFastSyncPercentHandlesEmptyRange() {
		assertEquals(100, ArchiveFastSyncManager.calculateArchiveFastSyncPercent(2, 1, 1));
	}
}
