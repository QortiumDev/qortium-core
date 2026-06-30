package org.qortium.network;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.data.network.PeerData;
import org.qortium.network.helper.PeerCapabilities;
import org.qortium.network.i2p.I2PStreamProvider;
import org.qortium.network.message.Message;
import org.qortium.network.message.PeersMessage;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.utils.NTP;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class NetworkI2PTests extends Common {

	private static final String B32 = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";
	private static final String LOCAL_B32 = "cdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuv.b32.i2p";
	private static final String NODE_ID = "node-id-for-network-i2p-test";
	/** Must match Network.I2P_FALLBACK_DROP_COOLDOWN. */
	private static final long I2P_FALLBACK_DROP_COOLDOWN = 15 * 60 * 1000L;

	@Before
	public void before() throws Exception {
		Common.useDefaultSettings();
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", java.util.List.of("IP", "I2P"), true);
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
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", java.util.List.of("IP"), true);
		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put(Handshake.I2P_CAPABILITY, B32);

		invokeAddI2PChainPeer(networkPeerWithCapabilities(capabilities));

		assertTrue(Network.getInstance().getAllKnownPeers().isEmpty());
	}

	@Test
	public void testSkipsOwnI2PChainCapability() throws Exception {
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", new FakeI2PStreamProvider(B32, true), true);
		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put(Handshake.I2P_CAPABILITY, B32);

		invokeAddI2PChainPeer(networkPeerWithCapabilities(capabilities));

		assertTrue(Network.getInstance().getAllKnownPeers().isEmpty());
	}

	@Test
	public void testDirectChainPeerStaysPrimaryWhenI2PNotPreferred() throws Exception {
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", new FakeI2PStreamProvider(LOCAL_B32, true), true);
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString("198.51.100.10:24892"), 100L, "test"));
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString(B32), 100L, "test"));

		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertEquals("198.51.100.10:24892", selectedPeer.getPeerData().getAddress().toString());
		assertFalse(selectedPeer.getPeerData().getAddress().isI2P());
	}

	@Test
	public void testI2PChainAlternativeSkippedWhenDirectPeerAlreadyConnected() throws Exception {
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", new FakeI2PStreamProvider(LOCAL_B32, true), true);
		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put(Handshake.I2P_CAPABILITY, B32);
		Network.getInstance().addConnectedPeer(networkPeerWithCapabilities(capabilities));
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString(B32), 100L, "test"));

		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertNull(selectedPeer);
	}

	@Test
	public void testI2PChainHandshakeCachesNodeIdForDuplicateSuppression() throws Exception {
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", new FakeI2PStreamProvider(LOCAL_B32, true), true);
		Peer directPeer = new Peer(new PeerData(PeerAddress.fromString("198.51.100.10:24892")), Peer.NETWORK);
		directPeer.setPeersNodeId(NODE_ID);
		Network.getInstance().addConnectedPeer(directPeer);
		Peer i2pPeer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORK);
		i2pPeer.setPeersNodeId(NODE_ID);
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString(B32), 100L, "test"));

		invokeUpdateConnectedPeerAddressCache(i2pPeer);
		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertNull(selectedPeer);
	}

	@Test
	public void testRejectedDuplicateI2PChainHandshakeCachesNodeIdForSuppression() throws Exception {
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", new FakeI2PStreamProvider(LOCAL_B32, true), true);
		Peer directPeer = new Peer(new PeerData(PeerAddress.fromString("198.51.100.10:24892")), Peer.NETWORK);
		directPeer.setPeersNodeId(NODE_ID);
		Network.getInstance().addConnectedPeer(directPeer);
		Peer i2pPeer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORK);
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString(B32), 100L, "test"));

		Network.getInstance().noteHandshakePeerAddress(i2pPeer, NODE_ID);
		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertNull(selectedPeer);
	}

	@Test
	public void testKnownI2PChainCapabilityRefreshesNodeIdCache() throws Exception {
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", new FakeI2PStreamProvider(LOCAL_B32, true), true);
		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put(Handshake.I2P_CAPABILITY, B32);
		// The direct peer that advertises B32 must actually be connected, otherwise there is no
		// connected alternative to suppress and dialing the bare I2P address is correct.
		Peer directPeer = networkPeerWithCapabilities(capabilities);
		Network.getInstance().addConnectedPeer(directPeer);
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString(B32), 100L, "test"));

		invokeAddI2PChainPeer(directPeer);
		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertNull(selectedPeer);
	}

	@Test
	public void testDirectChainPeerCanReplaceExistingI2PFallback() throws Exception {
		Peer i2pPeer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORK);
		i2pPeer.setPeersNodeId(NODE_ID);
		Network.getInstance().addConnectedPeer(i2pPeer);
		PeerData directPeerData = new PeerData(PeerAddress.fromString("198.51.100.10:24892"), 100L, "test");
		getMutableKnownPeers().add(directPeerData);
		invokeUpdateAddressToNodeIdCache(directPeerData.getAddress().toString(), NODE_ID);

		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertEquals("198.51.100.10:24892", selectedPeer.getPeerData().getAddress().toString());
		assertFalse(selectedPeer.getPeerData().getAddress().isI2P());
	}

	@Test
	public void testFullChainSlotsSelectI2PFallbackForDirectReplacement() throws Exception {
		Peer i2pPeer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORK);
		i2pPeer.setPeersNodeId(NODE_ID);
		Network.getInstance().addConnectedPeer(i2pPeer);
		Network.getInstance().addHandshakedPeer(i2pPeer);
		PeerData directPeerData = new PeerData(PeerAddress.fromString("198.51.100.10:24892"), 100L, "test");
		getMutableKnownPeers().add(directPeerData);
		invokeUpdateAddressToNodeIdCache(directPeerData.getAddress().toString(), NODE_ID);

		Peer fallbackPeer = invokeFindI2PFallbackPeerWithDirectReplacement(System.currentTimeMillis());

		assertSame(i2pPeer, fallbackPeer);
	}

	@Test
	public void testDroppedI2PChainFallbackIsNotImmediatelyRedropped() throws Exception {
		long now = System.currentTimeMillis();

		// An outbound, handshaked I2P fallback peer with a known nodeId...
		Peer i2pPeer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORK);
		i2pPeer.setPeersNodeId(NODE_ID);
		Network.getInstance().addConnectedPeer(i2pPeer);
		Network.getInstance().addHandshakedPeer(i2pPeer);

		// ...and a cached (but, in the wild, unreachable) direct TCP address for that same node.
		PeerData directPeerData = new PeerData(PeerAddress.fromString("198.51.100.10:24892"), 100L, "test");
		getMutableKnownPeers().add(directPeerData);
		invokeUpdateAddressToNodeIdCache(directPeerData.getAddress().toString(), NODE_ID);

		// First pass: the I2P fallback is eligible and is dropped so direct TCP can be retried.
		assertTrue(invokeDisconnectI2PFallbackPeerWithDirectReplacement(now));

		// The direct dial would fail in the wild; the peer reconnects over I2P (same node) while
		// the cached direct address stays unreachable.
		Peer reconnectedI2pPeer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORK);
		reconnectedI2pPeer.setPeersNodeId(NODE_ID);
		Network.getInstance().addConnectedPeer(reconnectedI2pPeer);
		Network.getInstance().addHandshakedPeer(reconnectedI2pPeer);

		// Within the cooldown window the same node must NOT be dropped again (no thrash).
		assertNull(invokeFindI2PFallbackPeerWithDirectReplacement(now + 60_000L));
		assertFalse(invokeDisconnectI2PFallbackPeerWithDirectReplacement(now + 60_000L));

		// Once the cooldown expires it becomes eligible to drop again.
		assertSame(reconnectedI2pPeer,
				invokeFindI2PFallbackPeerWithDirectReplacement(now + I2P_FALLBACK_DROP_COOLDOWN + 1));
	}

	@Test
	public void testI2PChainForwardedPeerRejectedWhenServerFull() throws Exception {
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", new FakeI2PStreamProvider(LOCAL_B32, true), true);

		// Fill the chain peer slots to maxPeers.
		int maxPeers = Network.getInstance().getMaxPeers();
		for (int i = 0; i < maxPeers; i++) {
			int v = i + 1;
			String ip = "10." + ((v >> 16) & 255) + "." + ((v >> 8) & 255) + "." + (v & 255);
			Network.getInstance().addConnectedPeer(new Peer(new PeerData(PeerAddress.fromString(ip + ":24892")), Peer.NETWORK));
		}
		int beforeCount = Network.getInstance().getImmutableConnectedPeers().size();

		// A forwarded I2P connection arriving while full must be closed and not added as a peer.
		try (java.nio.channels.ServerSocketChannel server = java.nio.channels.ServerSocketChannel.open()) {
			server.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));
			SocketChannel client = SocketChannel.open(server.getLocalAddress());
			SocketChannel accepted = server.accept();
			try {
				invokeProcessI2PForwardedPeer(client);

				assertEquals(beforeCount, Network.getInstance().getImmutableConnectedPeers().size());
				assertFalse(client.isOpen());
			} finally {
				if (client.isOpen()) client.close();
				if (accepted != null) accepted.close();
			}
		}
	}

	@Test
	public void testStaleOutboundI2PChainHandshakeIsDisconnectedAndBackedOff() throws Exception {
		long now = System.currentTimeMillis();
		Peer peer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORK);
		peer.setHandshakeStatus(Handshake.HELLO);
		FieldUtils.writeField(peer, "connectionTimestamp", now - 61_000L, true);
		Network.getInstance().addConnectedPeer(peer);

		invokeCleanupStaleHandshakingPeers(now);

		assertFalse(Network.getInstance().getImmutableConnectedPeers().contains(peer));
		assertTrue(getOutboundFailures().containsKey(PeerAddress.fromString(B32).getHost()));
	}

	@Test
	public void testPreferredI2PSelectsI2PChainPeerWhenSessionIsUp() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", java.util.List.of("I2P", "IP"), true);
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", new FakeI2PStreamProvider(LOCAL_B32, true), true);
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString("198.51.100.10:24892"), 100L, "test"));
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString(B32), 100L, "test"));

		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertEquals(B32 + ":0", selectedPeer.getPeerData().getAddress().toString());
		assertTrue(selectedPeer.getPeerData().getAddress().isI2P());
	}

	@Test
	public void testPreferredI2PSkipsChainPeerAlreadyConnecting() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", java.util.List.of("I2P", "IP"), true);
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", new FakeI2PStreamProvider(LOCAL_B32, true), true);
		PeerAddress i2pAddress = PeerAddress.fromString(B32);
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString("198.51.100.10:24892"), 100L, "test"));
		getMutableKnownPeers().add(new PeerData(i2pAddress, 100L, "test"));
		getConnectingI2PPeers().add(i2pAddress);

		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertEquals("198.51.100.10:24892", selectedPeer.getPeerData().getAddress().toString());
		assertFalse(selectedPeer.getPeerData().getAddress().isI2P());
	}

	@Test
	public void testI2PChainPeerUsesLongerConnectFailureBackoff() throws Exception {
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", new FakeI2PStreamProvider(LOCAL_B32, true), true);
		Peer connectedPeer = new Peer(new PeerData(PeerAddress.fromString("198.51.100.10:24892")), Peer.NETWORK);
		connectedPeer.setPeersNodeId(NODE_ID);
		Network.getInstance().addConnectedPeer(connectedPeer);
		Network.getInstance().addHandshakedPeer(connectedPeer);
		PeerData recentlyAttemptedI2PPeer = new PeerData(PeerAddress.fromString(B32), 100L, "test");
		// lastAttempted (not addedWhen) drives the backoff: 3 min ago is past the 2 min TCP backoff
		// but within the 15 min I2P backoff, so an I2P peer must still be skipped.
		recentlyAttemptedI2PPeer.setLastAttempted(System.currentTimeMillis() - 3 * 60 * 1000L);
		getMutableKnownPeers().add(recentlyAttemptedI2PPeer);

		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertNull(selectedPeer);
	}

	@Test
	public void testPeersMessageIncludesI2PChainPeersWithoutDnsResolution() throws Exception {
		PeerData i2pPeerData = new PeerData(PeerAddress.fromString(B32), 100L, "test");
		Long now = NTP.getTime();
		i2pPeerData.setLastAttempted(now);
		i2pPeerData.setLastConnected(now);
		getMutableKnownPeers().add(i2pPeerData);

		// Requester is an I2P peer, so it must receive the I2P chain peer address (no DNS resolution).
		Peer peer = new Peer(new PeerData(PeerAddress.fromString(LOCAL_B32)), Peer.NETWORK);
		PeersMessage message = (PeersMessage) Network.getInstance().buildPeersMessage(peer);
		byte[] dataBytes = (byte[]) FieldUtils.readField(message, "dataBytes", true);
		PeersMessage decoded = (PeersMessage) PeersMessage.fromByteBuffer(1, ByteBuffer.wrap(dataBytes));

		assertTrue(decoded.getPeerAddresses().stream()
				.anyMatch(peerAddress -> peerAddress.isI2P() && peerAddress.toString().equals(B32 + ":0")));
	}

	@Test
	public void testPeersMessageFiltersByRequesterTransport() throws Exception {
		Long now = NTP.getTime();

		PeerData i2pPeerData = new PeerData(PeerAddress.fromString(B32), 100L, "test");
		i2pPeerData.setLastAttempted(now);
		i2pPeerData.setLastConnected(now);
		getMutableKnownPeers().add(i2pPeerData);

		PeerData clearnetPeerData = new PeerData(PeerAddress.fromString("198.51.100.20:24892"), 100L, "test");
		clearnetPeerData.setLastAttempted(now);
		clearnetPeerData.setLastConnected(now);
		getMutableKnownPeers().add(clearnetPeerData);

		// Clearnet requester: receives only the clearnet address, never the I2P one.
		Peer clearnetRequester = new Peer(new PeerData(PeerAddress.fromString("198.51.100.10:24892")), Peer.NETWORK);
		List<PeerAddress> clearnetAddresses = decodedPeerAddresses(Network.getInstance().buildPeersMessage(clearnetRequester));
		assertTrue(clearnetAddresses.stream()
				.anyMatch(peerAddress -> peerAddress.toString().equals("198.51.100.20:24892")));
		assertFalse(clearnetAddresses.stream().anyMatch(PeerAddress::isI2P));

		// I2P requester: receives only the I2P address, never the clearnet one.
		Peer i2pRequester = new Peer(new PeerData(PeerAddress.fromString(LOCAL_B32)), Peer.NETWORK);
		List<PeerAddress> i2pAddresses = decodedPeerAddresses(Network.getInstance().buildPeersMessage(i2pRequester));
		assertTrue(i2pAddresses.stream()
				.anyMatch(peerAddress -> peerAddress.isI2P() && peerAddress.toString().equals(B32 + ":0")));
		assertFalse(i2pAddresses.stream().anyMatch(peerAddress -> !peerAddress.isI2P()));
	}

	// buildPeersMessage returns an outbound message that only holds serialized dataBytes; getPeerAddresses()
	// is null until a serialize -> fromByteBuffer round-trip, matching how a remote peer would decode it. The
	// first decoded entry is the sender's own listen-port sentinel (empty address); drop it like the real
	// receiver (onPeersMessage) does before inspecting the advertised peer addresses.
	private static List<PeerAddress> decodedPeerAddresses(Message message) throws Exception {
		byte[] dataBytes = (byte[]) FieldUtils.readField(message, "dataBytes", true);
		PeersMessage decoded = (PeersMessage) PeersMessage.fromByteBuffer(1, ByteBuffer.wrap(dataBytes));
		List<PeerAddress> addresses = new ArrayList<>(decoded.getPeerAddresses());
		if (!addresses.isEmpty())
			addresses.remove(0);
		return addresses;
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
		clearPeerDirectionState();
		((List<Peer>) FieldUtils.readField(Network.getInstance(), "connectedPeers", true)).clear();
		((List<Peer>) FieldUtils.readField(Network.getInstance(), "handshakedPeers", true)).clear();
		((List<Peer>) FieldUtils.readField(Network.getInstance(), "outboundHandshakedPeers", true)).clear();
		FieldUtils.writeField(Network.getInstance(), "immutableConnectedPeers", List.of(), true);
		FieldUtils.writeField(Network.getInstance(), "immutableHandshakedPeers", List.of(), true);
		FieldUtils.writeField(Network.getInstance(), "immutableOutboundHandshakedPeers", List.of(), true);
		FieldUtils.writeField(Network.getInstance(), "nextHandshakeCleanup", 0L, true);
		getConnectingI2PPeers().clear();
	}

	@SuppressWarnings("unchecked")
	private void clearPeerDirectionState() throws Exception {
		PeerDirectionState peerDirectionState = (PeerDirectionState) FieldUtils.readField(Network.getInstance(),
				"peerDirectionState", true);
		((Map<String, ?>) FieldUtils.readField(peerDirectionState, "outboundFailures", true)).clear();
		((Map<String, ?>) FieldUtils.readField(peerDirectionState, "outboundFailuresByNodeId", true)).clear();
		((Map<String, ?>) FieldUtils.readField(peerDirectionState, "directionMismatchByNodeId", true)).clear();
	}

	@SuppressWarnings("unchecked")
	private List<PeerData> getMutableKnownPeers() throws Exception {
		return (List<PeerData>) FieldUtils.readField(Network.getInstance(), "allKnownPeers", true);
	}

	@SuppressWarnings("unchecked")
	private java.util.Set<PeerAddress> getConnectingI2PPeers() throws Exception {
		return (java.util.Set<PeerAddress>) FieldUtils.readField(Network.getInstance(), "connectingI2PPeers", true);
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

	private void invokeUpdateAddressToNodeIdCache(String address, String nodeId) throws Exception {
		java.lang.reflect.Method method = Network.class.getDeclaredMethod("updateAddressToNodeIdCache", String.class, String.class);
		method.setAccessible(true);
		method.invoke(Network.getInstance(), address, nodeId);
	}

	private void invokeUpdateConnectedPeerAddressCache(Peer peer) throws Exception {
		java.lang.reflect.Method method = Network.class.getDeclaredMethod("updateConnectedPeerAddressCache", Peer.class);
		method.setAccessible(true);
		method.invoke(Network.getInstance(), peer);
	}

	private Peer invokeFindI2PFallbackPeerWithDirectReplacement(long now) throws Exception {
		java.lang.reflect.Method method = Network.class.getDeclaredMethod("findI2PFallbackPeerWithDirectReplacement", Long.class);
		method.setAccessible(true);
		return (Peer) method.invoke(Network.getInstance(), now);
	}

	private boolean invokeDisconnectI2PFallbackPeerWithDirectReplacement(long now) throws Exception {
		java.lang.reflect.Method method = Network.class.getDeclaredMethod("disconnectI2PFallbackPeerWithDirectReplacement", Long.class);
		method.setAccessible(true);
		return (boolean) method.invoke(Network.getInstance(), now);
	}

	private void invokeProcessI2PForwardedPeer(SocketChannel channel) throws Exception {
		java.lang.reflect.Method method = Network.class.getDeclaredMethod("processI2PForwardedPeer", SocketChannel.class);
		method.setAccessible(true);
		method.invoke(Network.getInstance(), channel);
	}

	@SuppressWarnings("unchecked")
	private Map<String, ?> getOutboundFailures() throws Exception {
		PeerDirectionState peerDirectionState = (PeerDirectionState) FieldUtils.readField(Network.getInstance(),
				"peerDirectionState", true);
		return (Map<String, ?>) FieldUtils.readField(peerDirectionState, "outboundFailures", true);
	}

	private void invokeCleanupStaleHandshakingPeers(long now) throws Exception {
		java.lang.reflect.Method method = Network.class.getDeclaredMethod("cleanupStaleHandshakingPeers", Long.class);
		method.setAccessible(true);
		method.invoke(Network.getInstance(), now);
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
