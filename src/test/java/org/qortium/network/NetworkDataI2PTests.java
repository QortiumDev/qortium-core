package org.qortium.network;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.data.network.PeerData;
import org.qortium.network.helper.PeerCapabilities;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NetworkDataI2PTests extends Common {

	private static final String B32 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";
	private static final String NODE_ID = "node-id-for-networkdata-i2p-test";

	@Before
	public void before() throws Exception {
		Common.useDefaultSettings();
		FieldUtils.writeField(Settings.getInstance(), "i2pEnabled", true, true);
		clearNetworkDataPeerState();
	}

	@After
	public void after() throws Exception {
		clearNetworkDataPeerState();
	}

	@Test
	public void testAddPeerLearnsDirectAndI2PQdnAddresses() throws Exception {
		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put("QDN", 24894);
		capabilities.put(Handshake.I2P_QDN_CAPABILITY, B32);

		NetworkData.getInstance().addPeer(networkPeerWithCapabilities(capabilities));

		List<PeerData> knownPeers = NetworkData.getInstance().getAllKnownPeers();
		assertTrue(containsAddress(knownPeers, "198.51.100.10:24894"));
		assertTrue(containsAddress(knownPeers, B32 + ":0"));
	}

	@Test
	public void testAddPeerSkipsDisabledQdnPort() throws Exception {
		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put("QDN", 0);

		NetworkData.getInstance().addPeer(networkPeerWithCapabilities(capabilities));

		assertFalse(containsAddress(NetworkData.getInstance().getAllKnownPeers(), "198.51.100.10:0"));
		assertTrue(NetworkData.getInstance().getAllKnownPeers().isEmpty());
	}

	@Test
	public void testAddPeerSkipsI2PQdnWhenI2PDisabled() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "i2pEnabled", false, true);
		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put(Handshake.I2P_QDN_CAPABILITY, B32);

		NetworkData.getInstance().addPeer(networkPeerWithCapabilities(capabilities));

		assertTrue(NetworkData.getInstance().getAllKnownPeers().isEmpty());
	}

	@Test
	public void testPreferredI2PSkipsI2PWhenLocalSessionIsDown() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "i2pPreferred", true, true);
		List<PeerData> knownPeers = getMutableKnownPeers();
		knownPeers.add(new PeerData(PeerAddress.fromString("198.51.100.10:24894"), 100L, "test"));
		knownPeers.add(new PeerData(PeerAddress.fromString(B32), 100L, "test"));

		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertEquals("198.51.100.10:24894", selectedPeer.getPeerData().getAddress().toString());
		assertFalse(selectedPeer.getPeerData().getAddress().isI2P());
	}

	@Test
	public void testI2PStartupRetriesUseFreshSamSessionIds() throws Exception {
		String firstSessionId = invokeNextI2PDataSessionId();
		String secondSessionId = invokeNextI2PDataSessionId();

		assertTrue(firstSessionId.startsWith("qortium-data-"));
		assertTrue(secondSessionId.startsWith("qortium-data-"));
		assertFalse(firstSessionId.equals(secondSessionId));
	}

	private Peer networkPeerWithCapabilities(Map<String, Object> capabilities) {
		Peer peer = new Peer(new PeerData(PeerAddress.fromString("198.51.100.10:24892")), Peer.NETWORK);
		peer.setPeersCapabilities(new PeerCapabilities(capabilities));
		peer.setPeersNodeId(NODE_ID);
		return peer;
	}

	private boolean containsAddress(List<PeerData> peers, String address) {
		return peers.stream().anyMatch(peerData -> peerData.getAddress().toString().equals(address));
	}

	@SuppressWarnings("unchecked")
	private void clearNetworkDataPeerState() throws Exception {
		getMutableKnownPeers().clear();
		((Map<String, ?>) FieldUtils.readField(NetworkData.getInstance(), "addressToNodeIdCache", true)).clear();
	}

	@SuppressWarnings("unchecked")
	private List<PeerData> getMutableKnownPeers() throws Exception {
		return (List<PeerData>) FieldUtils.readField(NetworkData.getInstance(), "allKnownPeers", true);
	}

	private Peer invokeGetConnectablePeer(long now) throws Exception {
		java.lang.reflect.Method method = NetworkData.class.getDeclaredMethod("getConnectablePeer", Long.class);
		method.setAccessible(true);
		return (Peer) method.invoke(NetworkData.getInstance(), now);
	}

	private String invokeNextI2PDataSessionId() throws Exception {
		java.lang.reflect.Method method = NetworkData.class.getDeclaredMethod("nextI2PDataSessionId");
		method.setAccessible(true);
		return (String) method.invoke(NetworkData.getInstance());
	}
}
