package org.qortium.network;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.data.network.PeerData;
import org.qortium.network.helper.PeerCapabilities;
import org.qortium.network.i2p.I2PStreamProvider;
import org.qortium.network.message.PeersMessage;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.utils.NTP;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NetworkI2PTests extends Common {

	private static final String B32 = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";
	private static final String NODE_ID = "node-id-for-network-i2p-test";

	@Before
	public void before() throws Exception {
		Common.useDefaultSettings();
		FieldUtils.writeField(Settings.getInstance(), "i2pEnabled", true, true);
		clearNetworkPeerState();
	}

	@After
	public void after() throws Exception {
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", null, true);
		clearNetworkPeerState();
	}

	@Test
	public void testLearnsI2PChainAddressFromHandshakeCapability() throws Exception {
		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put(Handshake.I2P_CAPABILITY, B32);

		invokeAddI2PChainPeer(networkPeerWithCapabilities(capabilities));

		List<PeerData> knownPeers = Network.getInstance().getAllKnownPeers();
		assertTrue(containsAddress(knownPeers, B32 + ":0"));
	}

	@Test
	public void testSkipsI2PChainCapabilityWhenI2PDisabled() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "i2pEnabled", false, true);
		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put(Handshake.I2P_CAPABILITY, B32);

		invokeAddI2PChainPeer(networkPeerWithCapabilities(capabilities));

		assertTrue(Network.getInstance().getAllKnownPeers().isEmpty());
	}

	@Test
	public void testDirectChainPeerStaysPrimaryWhenI2PNotPreferred() throws Exception {
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", new FakeI2PStreamProvider(B32, true), true);
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString("198.51.100.10:24892"), 100L, "test"));
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString(B32), 100L, "test"));

		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertEquals("198.51.100.10:24892", selectedPeer.getPeerData().getAddress().toString());
		assertFalse(selectedPeer.getPeerData().getAddress().isI2P());
	}

	@Test
	public void testPreferredI2PSelectsI2PChainPeerWhenSessionIsUp() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "i2pPreferred", true, true);
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", new FakeI2PStreamProvider(B32, true), true);
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString("198.51.100.10:24892"), 100L, "test"));
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString(B32), 100L, "test"));

		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertEquals(B32 + ":0", selectedPeer.getPeerData().getAddress().toString());
		assertTrue(selectedPeer.getPeerData().getAddress().isI2P());
	}

	@Test
	public void testPeersMessageIncludesI2PChainPeersWithoutDnsResolution() throws Exception {
		PeerData i2pPeerData = new PeerData(PeerAddress.fromString(B32), 100L, "test");
		Long now = NTP.getTime();
		i2pPeerData.setLastAttempted(now);
		i2pPeerData.setLastConnected(now);
		getMutableKnownPeers().add(i2pPeerData);

		Peer peer = new Peer(new PeerData(PeerAddress.fromString("198.51.100.10:24892")), Peer.NETWORK);
		PeersMessage message = (PeersMessage) Network.getInstance().buildPeersMessage(peer);
		byte[] dataBytes = (byte[]) FieldUtils.readField(message, "dataBytes", true);
		PeersMessage decoded = (PeersMessage) PeersMessage.fromByteBuffer(1, ByteBuffer.wrap(dataBytes));

		assertTrue(decoded.getPeerAddresses().stream()
				.anyMatch(peerAddress -> peerAddress.isI2P() && peerAddress.toString().equals(B32 + ":0")));
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
	private void clearNetworkPeerState() throws Exception {
		getMutableKnownPeers().clear();
		((List<PeerAddress>) FieldUtils.readField(Network.getInstance(), "selfPeers", true)).clear();
		((Map<String, ?>) FieldUtils.readField(Network.getInstance(), "addressToNodeIdCache", true)).clear();
	}

	@SuppressWarnings("unchecked")
	private List<PeerData> getMutableKnownPeers() throws Exception {
		return (List<PeerData>) FieldUtils.readField(Network.getInstance(), "allKnownPeers", true);
	}

	private Peer invokeGetConnectablePeer(long now) throws Exception {
		java.lang.reflect.Method method = Network.class.getDeclaredMethod("getConnectablePeer", Long.class);
		method.setAccessible(true);
		return (Peer) method.invoke(Network.getInstance(), now);
	}

	private void invokeAddI2PChainPeer(Peer peer) throws Exception {
		java.lang.reflect.Method method = Network.class.getDeclaredMethod("addI2PChainPeer", Peer.class);
		method.setAccessible(true);
		method.invoke(Network.getInstance(), peer);
	}

	private static class FakeI2PStreamProvider implements I2PStreamProvider {
		private final String localB32;
		private final boolean sessionUp;

		private FakeI2PStreamProvider(String localB32, boolean sessionUp) {
			this.localB32 = localB32;
			this.sessionUp = sessionUp;
		}

		@Override
		public void start() throws IOException {
		}

		@Override
		public String getLocalB32() {
			return this.localB32;
		}

		@Override
		public boolean isSessionUp() {
			return this.sessionUp;
		}

		@Override
		public SocketChannel connect(String remoteB32) throws IOException {
			return null;
		}

		@Override
		public void startForward(int localPort) throws IOException {
		}

		@Override
		public String readForwardedDestination(SocketChannel inbound) throws IOException {
			return null;
		}

		@Override
		public void close() {
		}
	}
}
