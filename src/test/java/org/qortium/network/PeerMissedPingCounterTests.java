package org.qortium.network;

import org.junit.Before;
import org.junit.Test;
import org.qortium.data.network.PeerData;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertEquals;

/**
 * Covers the consecutive-missed-ping counter backing the "three strikes" ping policy
 * (see PingTask / Settings.peerPingFailureThreshold): reset on success, incremented on miss,
 * thread-safe via AtomicInteger.
 */
public class PeerMissedPingCounterTests extends Common {

	private static final String B32 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";

	@Before
	public void beforeTest() throws DataException {
		// Constructing a Peer initialises Handshake, which needs settings loaded
		Common.useDefaultSettings();
	}

	private static Peer newPeer() {
		return new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORK);
	}

	@Test
	public void testStartsAtZero() {
		Peer peer = newPeer();
		assertEquals(0, peer.getConsecutiveMissedPings());
	}

	@Test
	public void testRecordMissedPingIncrementsAndReturnsNewCount() {
		Peer peer = newPeer();

		assertEquals(1, peer.recordMissedPing());
		assertEquals(2, peer.recordMissedPing());
		assertEquals(3, peer.recordMissedPing());
		assertEquals(3, peer.getConsecutiveMissedPings());
	}

	@Test
	public void testResetMissedPingsReturnsCounterToZero() {
		Peer peer = newPeer();

		peer.recordMissedPing();
		peer.recordMissedPing();
		assertEquals(2, peer.getConsecutiveMissedPings());

		peer.resetMissedPings();
		assertEquals(0, peer.getConsecutiveMissedPings());
	}

	@Test
	public void testMissesResumeCountingAfterReset() {
		Peer peer = newPeer();

		peer.recordMissedPing();
		peer.recordMissedPing();
		peer.resetMissedPings();

		assertEquals(1, peer.recordMissedPing());
	}
}
