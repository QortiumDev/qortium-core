package org.qortium.network;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class PeerResponseDeadlineTests {

	@Test
	public void testExpiredDeadlineHasNoTimeoutRemaining() {
		assertEquals(0, Peer.remainingTimeoutMillis(100L, 100L));
		assertEquals(0, Peer.remainingTimeoutMillis(99L, 100L));
	}

	@Test
	public void testSubMillisecondRemainderRoundsUpForOneFinalWait() {
		assertEquals(1, Peer.remainingTimeoutMillis(500L, 100L));
	}

	@Test
	public void testRemainingDeadlineUsesOneSharedClock() {
		long deadline = TimeUnit.MILLISECONDS.toNanos(20_000);
		assertEquals(20_000, Peer.remainingTimeoutMillis(deadline, 0L));
		assertEquals(12_500, Peer.remainingTimeoutMillis(deadline,
				TimeUnit.MILLISECONDS.toNanos(7_500)));
	}
}
