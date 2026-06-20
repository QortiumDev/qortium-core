package org.qortium.api.model;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.data.network.PeerData;
import org.qortium.network.Peer;
import org.qortium.network.PeerAddress;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertEquals;

/**
 * Confirms the {@code transport} field on the connected-peer API models reflects whether the peer
 * address is an I2P destination or a direct IP/DNS address.
 */
public class ConnectedPeerTransportTests extends Common {

	private static final String B32 = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";

	@Before
	public void before() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testTransportIsI2PForI2PAddress() throws Exception {
		Peer chainPeer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORK);
		Peer dataPeer = dataPeer(PeerAddress.fromString(B32));

		assertEquals("I2P", new ConnectedPeer(chainPeer).transport);
		assertEquals("I2P", new ConnectedDataPeer(dataPeer).transport);
	}

	@Test
	public void testTransportIsIpForDirectAddress() throws Exception {
		Peer chainPeer = new Peer(new PeerData(PeerAddress.fromString("198.51.100.10:24892")), Peer.NETWORK);
		Peer dataPeer = dataPeer(PeerAddress.fromString("198.51.100.10:24894"));

		assertEquals("IP", new ConnectedPeer(chainPeer).transport);
		assertEquals("IP", new ConnectedDataPeer(dataPeer).transport);
	}

	/** A data peer with lastValidUse populated, so ConnectedDataPeer's getLastQDNUse() unbox is safe. */
	private static Peer dataPeer(PeerAddress address) throws Exception {
		Peer peer = new Peer(new PeerData(address), Peer.NETWORKDATA);
		FieldUtils.writeField(peer, "lastValidUse", 1_000L, true);
		return peer;
	}
}
