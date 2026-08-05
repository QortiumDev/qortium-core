package org.qortium.network;

import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.qortium.data.block.BlockSummaryData;
import org.qortium.data.network.PeerData;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PeerTests extends Common {

	private static final String B32 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";
	private static final String OTHER_B32 = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";

	@Before
	public void beforeTest() throws DataException {
		// Constructing a Peer initialises Handshake, which needs settings loaded
		Common.useDefaultSettings();
	}

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

	private static Peer newPeer() {
		return new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORK);
	}

	private static BlockSummaryData blockSummary(int height) {
		return new BlockSummaryData(height, new byte[] { (byte) height }, new byte[] { 1 }, 1_000L * height);
	}

	@Test
	public void testChainTipDataIsNullUntilFirstUpdate() {
		assertNull(newPeer().getChainTipData());
	}

	@Test
	public void testChainTipDataReturnsLatestSummary() {
		Peer peer = newPeer();

		peer.setChainTipSummaries(List.of(blockSummary(80889), blockSummary(80890)));

		assertNotNull(peer.getChainTipData());
		assertEquals(80890, peer.getChainTipData().getHeight());
	}

	/**
	 * An empty BLOCK_SUMMARIES update must not erase a chain tip we already know. Callers filter
	 * peers on a non-null chain tip and then dereference it, so allowing an empty broadcast to
	 * clear it re-introduces the null after those filters have run - which used to kill the
	 * Synchronizer thread outright and silently stop the node from ever advancing its chain.
	 */
	@Test
	public void testEmptyChainTipUpdateDoesNotClearKnownChainTip() {
		Peer peer = newPeer();
		peer.setChainTipSummaries(List.of(blockSummary(80890)));

		peer.setChainTipSummaries(Collections.emptyList());

		assertNotNull(peer.getChainTipData());
		assertEquals(80890, peer.getChainTipData().getHeight());
	}

	@Test
	public void testNullChainTipUpdateDoesNotClearKnownChainTip() {
		Peer peer = newPeer();
		peer.setChainTipSummaries(List.of(blockSummary(80890)));

		peer.setChainTipSummaries(null);

		assertNotNull(peer.getChainTipData());
		assertEquals(80890, peer.getChainTipData().getHeight());
	}

	@Test
	public void testNonEmptyChainTipUpdateStillReplacesKnownChainTip() {
		Peer peer = newPeer();
		peer.setChainTipSummaries(List.of(blockSummary(80890)));

		peer.setChainTipSummaries(List.of(blockSummary(81121)));

		assertEquals(81121, peer.getChainTipData().getHeight());
	}
}
