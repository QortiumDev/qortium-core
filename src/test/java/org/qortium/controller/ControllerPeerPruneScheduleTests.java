package org.qortium.controller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ControllerPeerPruneScheduleTests {

	private static final long START_TIMESTAMP = 1_000_000L;
	private static final long START_DELAY = 120_000L;
	private static final long PRUNE_INTERVAL = 90_000L;

	@Test
	public void testFirstPruneIsDueAfterStartupDelay() {
		long firstPruneTimestamp = Controller.initialPeerPruneTimestamp(START_TIMESTAMP);

		assertEquals(START_TIMESTAMP + START_DELAY, firstPruneTimestamp);
		assertFalse(Controller.isPeerPruneDue(firstPruneTimestamp - 1, firstPruneTimestamp));
		assertTrue(Controller.isPeerPruneDue(firstPruneTimestamp, firstPruneTimestamp));
	}

	@Test
	public void testNextPruneIsDueAfterRecurringInterval() {
		long firstPruneTimestamp = Controller.initialPeerPruneTimestamp(START_TIMESTAMP);
		long secondPruneTimestamp = Controller.calculateNextPeerPruneTimestamp(firstPruneTimestamp);

		assertEquals(firstPruneTimestamp + PRUNE_INTERVAL, secondPruneTimestamp);
		assertFalse(Controller.isPeerPruneDue(secondPruneTimestamp - 1, secondPruneTimestamp));
		assertTrue(Controller.isPeerPruneDue(secondPruneTimestamp, secondPruneTimestamp));
	}

	@Test
	public void testLatePruneSchedulesFromActualRunTime() {
		long lateRunTimestamp = START_TIMESTAMP + START_DELAY + 15_000L;

		assertEquals(lateRunTimestamp + PRUNE_INTERVAL,
				Controller.calculateNextPeerPruneTimestamp(lateRunTimestamp));
	}
}
