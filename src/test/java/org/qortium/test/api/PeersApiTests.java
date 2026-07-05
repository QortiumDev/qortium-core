package org.qortium.test.api;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.resource.PeersResource;
import org.qortium.data.network.KnownPeerDiagnostic;
import org.qortium.data.network.KnownPeerDiagnostics;
import org.qortium.data.network.PeerData;
import org.qortium.network.Network;
import org.qortium.network.NetworkData;
import org.qortium.network.Peer;
import org.qortium.network.PeerAddress;
import org.qortium.settings.Settings;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PeersApiTests extends ApiCommon {

	private PeersResource peersResource;

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		clearPeerState();
	}

	@Before
	public void buildResource() {
		this.peersResource = (PeersResource) ApiCommon.buildResource(PeersResource.class);
	}

	@After
	public void afterTest() throws Exception {
		clearPeerState();
		Common.useDefaultSettings();
	}

	@Test
	public void testGetKnownDataPeers() throws Exception {
		PeerData firstPeer = new PeerData(PeerAddress.fromString("198.51.100.10:24894"), 100L, "test");
		PeerData secondPeer = new PeerData(PeerAddress.fromString("abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p"), 200L, "test");
		getMutableKnownDataPeers().add(firstPeer);
		getMutableKnownDataPeers().add(secondPeer);

		List<PeerData> knownDataPeers = this.peersResource.getKnownDataPeers();

		assertEquals(2, knownDataPeers.size());
		assertTrue(knownDataPeers.stream()
				.anyMatch(peerData -> peerData.getAddress().toString().equals("198.51.100.10:24894")));
		assertTrue(knownDataPeers.stream()
				.anyMatch(peerData -> peerData.getAddress().toString().equals(
						"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p:0")));
	}

	@Test
	public void testGetKnownDataPeerDiagnostics() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", java.util.List.of("I2P"), true);
		long now = System.currentTimeMillis();
		PeerData directPeer = new PeerData(PeerAddress.fromString("198.51.100.10:24894"), 100L, "test");
		directPeer.setLastAttempted(now - 30_000L);
		PeerData i2pPeer = new PeerData(PeerAddress.fromString("abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p"), 200L, "test");
		getMutableKnownDataPeers().add(directPeer);
		getMutableKnownDataPeers().add(i2pPeer);

		KnownPeerDiagnostics diagnostics = this.peersResource.getKnownDataPeerDiagnostics();

		assertEquals(KnownPeerDiagnostics.Layer.DATA, diagnostics.layer);
		assertEquals(2, diagnostics.knownCount);
		assertEquals(0, diagnostics.connectedCount);
		assertEquals(0, diagnostics.connectableCount);
		assertEquals(Integer.valueOf(1), diagnostics.reasonCounts.get(KnownPeerDiagnostic.Reason.RECENT_CONNECT_FAILURE));
		assertEquals(Integer.valueOf(1), diagnostics.reasonCounts.get(KnownPeerDiagnostic.Reason.TRANSPORT_NOT_ALLOWED));
		assertEquals(Integer.valueOf(1), diagnostics.reasonCounts.get(KnownPeerDiagnostic.Reason.I2P_SESSION_DOWN));
		KnownPeerDiagnostic directDiagnostic = findDiagnostic(diagnostics, "198.51.100.10:24894");
		assertFalse(directDiagnostic.connectable);
		assertTrue(directDiagnostic.inBackoff);
		assertTrue(directDiagnostic.reasons.contains(KnownPeerDiagnostic.Reason.RECENT_CONNECT_FAILURE));
		assertTrue(directDiagnostic.reasons.contains(KnownPeerDiagnostic.Reason.TRANSPORT_NOT_ALLOWED));
	}

	@Test
	public void testGetKnownPeerDiagnostics() throws Exception {
		PeerData directPeer = new PeerData(PeerAddress.fromString("198.51.100.10:24892"), 100L, "test");
		PeerData i2pPeer = new PeerData(PeerAddress.fromString("abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p"), 200L, "test");
		getMutableKnownChainPeers().add(directPeer);
		getMutableKnownChainPeers().add(i2pPeer);

		KnownPeerDiagnostics diagnostics = this.peersResource.getKnownPeerDiagnostics();

		assertEquals(KnownPeerDiagnostics.Layer.CHAIN, diagnostics.layer);
		assertEquals(2, diagnostics.knownCount);
		assertEquals(0, diagnostics.connectedCount);
		assertEquals(1, diagnostics.connectableCount);
		assertEquals(Integer.valueOf(1), diagnostics.reasonCounts.get(KnownPeerDiagnostic.Reason.I2P_SESSION_DOWN));
		KnownPeerDiagnostic directDiagnostic = findDiagnostic(diagnostics, "198.51.100.10:24892");
		assertTrue(directDiagnostic.connectable);
		assertTrue(directDiagnostic.reasons.isEmpty());
	}

	private KnownPeerDiagnostic findDiagnostic(KnownPeerDiagnostics diagnostics, String address) {
		return diagnostics.peers.stream()
				.filter(peer -> peer.address.equals(address))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing diagnostic for " + address));
	}

	private void clearPeerState() throws Exception {
		clearKnownChainPeers();
		getMutableKnownDataPeers().clear();
		((List<Peer>) FieldUtils.readField(Network.getInstance(), "connectedPeers", true)).clear();
		((List<Peer>) FieldUtils.readField(Network.getInstance(), "handshakedPeers", true)).clear();
		((List<Peer>) FieldUtils.readField(Network.getInstance(), "outboundHandshakedPeers", true)).clear();
		FieldUtils.writeField(Network.getInstance(), "immutableConnectedPeers", List.of(), true);
		FieldUtils.writeField(Network.getInstance(), "immutableHandshakedPeers", List.of(), true);
		FieldUtils.writeField(Network.getInstance(), "immutableOutboundHandshakedPeers", List.of(), true);
		((List<Peer>) FieldUtils.readField(NetworkData.getInstance(), "connectedPeers", true)).clear();
		((List<Peer>) FieldUtils.readField(NetworkData.getInstance(), "handshakedPeers", true)).clear();
		((List<Peer>) FieldUtils.readField(NetworkData.getInstance(), "outboundHandshakedPeers", true)).clear();
	}

	private void clearKnownChainPeers() throws Exception {
		getMutableKnownChainPeers().clear();
	}

	@SuppressWarnings("unchecked")
	private List<PeerData> getMutableKnownDataPeers() throws Exception {
		return (List<PeerData>) FieldUtils.readField(NetworkData.getInstance(), "allKnownPeers", true);
	}

	@SuppressWarnings("unchecked")
	private List<PeerData> getMutableKnownChainPeers() throws Exception {
		return (List<PeerData>) FieldUtils.readField(Network.getInstance(), "allKnownPeers", true);
	}

}
