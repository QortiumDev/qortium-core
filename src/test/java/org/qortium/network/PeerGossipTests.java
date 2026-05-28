package org.qortium.network;

import org.junit.Test;
import org.qortium.data.network.PeerData;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PeerGossipTests {

	@Test
	public void testInboundListenAddressIsMarkedRecentlyConnected() {
		PeerAddress listenAddress = PeerAddress.fromString("80.241.221.139:24892");
		PeerData peerData = new PeerData(listenAddress, 100L, "test");

		assertTrue(Network.markRecentlyConnectedPeerData(peerData, 200L, Collections.singletonList(listenAddress)));
		assertEquals(Long.valueOf(200L), peerData.getLastAttempted());
		assertEquals(Long.valueOf(200L), peerData.getLastConnected());
	}

	@Test
	public void testUnmatchedPeerAddressIsNotMarkedRecentlyConnected() {
		PeerData peerData = new PeerData(PeerAddress.fromString("80.241.221.139:24892"), 100L, "test");

		assertFalse(Network.markRecentlyConnectedPeerData(peerData, 200L,
				Collections.singletonList(PeerAddress.fromString("146.103.42.59:24892"))));
		assertEquals(null, peerData.getLastAttempted());
		assertEquals(null, peerData.getLastConnected());
	}

}
