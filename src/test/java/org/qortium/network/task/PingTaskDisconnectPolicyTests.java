package org.qortium.network.task;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the pure "three strikes" decision helper backing PingTask's consecutive-missed-ping
 * disconnect policy (Settings.peerPingFailureThreshold, default 3). A value of 1 restores the
 * previous instant-disconnect-on-first-miss behavior.
 */
public class PingTaskDisconnectPolicyTests {

	@Test
	public void testDoesNotDisconnectBelowThreshold() {
		assertFalse(PingTask.shouldDisconnectAfterMiss(1, 3));
		assertFalse(PingTask.shouldDisconnectAfterMiss(2, 3));
	}

	@Test
	public void testDisconnectsAtThreshold() {
		assertTrue(PingTask.shouldDisconnectAfterMiss(3, 3));
	}

	@Test
	public void testDisconnectsBeyondThreshold() {
		// Defensive: counter should never exceed threshold in practice (reset happens first), but the
		// decision must still hold if it somehow does.
		assertTrue(PingTask.shouldDisconnectAfterMiss(4, 3));
	}

	@Test
	public void testThresholdOfOneRestoresInstantDisconnectBehavior() {
		assertTrue(PingTask.shouldDisconnectAfterMiss(1, 1));
	}

	@Test
	public void testHigherThresholdTakesMoreMissesToTrip() {
		for (int misses = 1; misses <= 9; misses++) {
			assertFalse("misses=" + misses, PingTask.shouldDisconnectAfterMiss(misses, 10));
		}
		assertTrue(PingTask.shouldDisconnectAfterMiss(10, 10));
	}
}
