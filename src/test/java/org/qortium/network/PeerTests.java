package org.qortium.network;

import org.junit.Test;
import org.qortium.data.network.PeerData;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PeerTests {

	private static final String B32 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";
	private static final String OTHER_B32 = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";

	@Test
	public void testEqualsHandlesSameI2PAddressWithoutDnsResolution() {
		Peer firstPeer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORK);
		Peer secondPeer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORK);

		assertTrue(firstPeer.equals(secondPeer));
	}

	@Test
	public void testEqualsRejectsDifferentI2PAddressWithoutDnsResolution() {
		Peer firstPeer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORK);
		Peer secondPeer = new Peer(new PeerData(PeerAddress.fromString(OTHER_B32)), Peer.NETWORK);

		assertFalse(firstPeer.equals(secondPeer));
	}

	@Test
	public void testEqualsRejectsMixedI2PAndIpAddressWithoutDnsResolution() {
		Peer i2pPeer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORK);
		Peer ipPeer = new Peer(new PeerData(PeerAddress.fromString("192.0.2.1:24892")), Peer.NETWORK);

		assertFalse(i2pPeer.equals(ipPeer));
		assertFalse(ipPeer.equals(i2pPeer));
	}
}
