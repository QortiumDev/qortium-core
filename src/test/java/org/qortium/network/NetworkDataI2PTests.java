package org.qortium.network;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.data.network.KnownPeerDiagnostic;
import org.qortium.data.network.KnownPeerDiagnostics;
import org.qortium.data.network.PeerData;
import org.qortium.network.helper.PeerCapabilities;
import org.qortium.network.i2p.I2PStreamProvider;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class NetworkDataI2PTests extends Common {

	private static final String B32 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";
	private static final String LOCAL_B32 = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";
	private static final String NODE_ID = "node-id-for-networkdata-i2p-test";

	@Before
	public void before() throws Exception {
		Common.useDefaultSettings();
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", java.util.List.of("IP", "I2P"), true);
		clearNetworkDataPeerState();
	}

	@After
	public void after() throws Exception {
		FieldUtils.writeField(NetworkData.getInstance(), "dataI2PStreamProvider", null, true);
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
	public void testAddPeerSkipsOwnI2PQdnCapability() throws Exception {
		FieldUtils.writeField(NetworkData.getInstance(), "dataI2PStreamProvider", new FakeI2PStreamProvider(B32, true), true);
		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put("QDN", 24894);
		capabilities.put(Handshake.I2P_QDN_CAPABILITY, B32);

		NetworkData.getInstance().addPeer(networkPeerWithCapabilities(capabilities));

		List<PeerData> knownPeers = NetworkData.getInstance().getAllKnownPeers();
		assertTrue(containsAddress(knownPeers, "198.51.100.10:24894"));
		assertFalse(containsAddress(knownPeers, B32 + ":0"));
	}

	@Test
	public void testAddPeerSkipsI2PQdnWhenI2PDisabled() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", java.util.List.of("IP"), true);
		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put(Handshake.I2P_QDN_CAPABILITY, B32);

		NetworkData.getInstance().addPeer(networkPeerWithCapabilities(capabilities));

		assertTrue(NetworkData.getInstance().getAllKnownPeers().isEmpty());
	}

	@Test
	public void testPreferredI2PSkipsI2PWhenLocalSessionIsDown() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", java.util.List.of("I2P", "IP"), true);
		List<PeerData> knownPeers = getMutableKnownPeers();
		knownPeers.add(new PeerData(PeerAddress.fromString("198.51.100.10:24894"), 100L, "test"));
		knownPeers.add(new PeerData(PeerAddress.fromString(B32), 100L, "test"));

		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertEquals("198.51.100.10:24894", selectedPeer.getPeerData().getAddress().toString());
		assertFalse(selectedPeer.getPeerData().getAddress().isI2P());
	}

	@Test
	public void testI2PQdnAlternativeSkippedWhenDirectDataPeerAlreadyConnected() throws Exception {
		FieldUtils.writeField(NetworkData.getInstance(), "dataI2PStreamProvider", new FakeI2PStreamProvider(LOCAL_B32, true), true);
		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put(Handshake.I2P_QDN_CAPABILITY, B32);
		NetworkData.getInstance().addConnectedPeer(dataPeerWithCapabilities(capabilities));
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString(B32), 100L, "test"));

		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertNull(selectedPeer);
	}

	@Test
	public void testDirectDataPeerCanReplaceExistingI2PFallback() throws Exception {
		Peer i2pPeer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORKDATA);
		i2pPeer.setPeersNodeId(NODE_ID);
		i2pPeer.setIsDataPeer(true);
		NetworkData.getInstance().addConnectedPeer(i2pPeer);
		PeerData directPeerData = new PeerData(PeerAddress.fromString("198.51.100.10:24894"), 100L, "test");
		getMutableKnownPeers().add(directPeerData);
		invokeUpdateAddressToNodeIdCache(directPeerData.getAddress().toString(), NODE_ID);

		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertEquals("198.51.100.10:24894", selectedPeer.getPeerData().getAddress().toString());
		assertFalse(selectedPeer.getPeerData().getAddress().isI2P());
	}

	@Test
	public void testRejectedDuplicateI2PDataHandshakeCachesNodeIdForSuppression() throws Exception {
		FieldUtils.writeField(NetworkData.getInstance(), "dataI2PStreamProvider", new FakeI2PStreamProvider(LOCAL_B32, true), true);
		Peer directPeer = new Peer(new PeerData(PeerAddress.fromString("198.51.100.10:24894")), Peer.NETWORKDATA);
		directPeer.setPeersNodeId(NODE_ID);
		directPeer.setIsDataPeer(true);
		NetworkData.getInstance().addConnectedPeer(directPeer);
		Peer i2pPeer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORKDATA);
		i2pPeer.setIsDataPeer(true);
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString(B32), 100L, "test"));

		NetworkData.getInstance().noteHandshakePeerAddress(i2pPeer, NODE_ID);
		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertNull(selectedPeer);
	}

	@Test
	public void testFullDataSlotsSelectI2PFallbackForDirectReplacement() throws Exception {
		Peer i2pPeer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORKDATA);
		i2pPeer.setPeersNodeId(NODE_ID);
		i2pPeer.setIsDataPeer(true);
		NetworkData.getInstance().addConnectedPeer(i2pPeer);
		NetworkData.getInstance().addHandshakedPeer(i2pPeer);
		PeerData directPeerData = new PeerData(PeerAddress.fromString("198.51.100.10:24894"), 100L, "test");
		getMutableKnownPeers().add(directPeerData);
		invokeUpdateAddressToNodeIdCache(directPeerData.getAddress().toString(), NODE_ID);

		Peer fallbackPeer = invokeFindI2PFallbackPeerWithDirectReplacement(System.currentTimeMillis());

		assertSame(i2pPeer, fallbackPeer);
	}

	@Test
	public void testStaleOutboundI2PDataHandshakeIsDisconnectedAndBackedOff() throws Exception {
		long now = System.currentTimeMillis();
		Peer peer = new Peer(new PeerData(PeerAddress.fromString(B32)), Peer.NETWORKDATA);
		peer.setIsDataPeer(true);
		peer.setHandshakeStatus(Handshake.HELLO);
		FieldUtils.writeField(peer, "connectionTimestamp", now - 61_000L, true);
		NetworkData.getInstance().addConnectedPeer(peer);

		invokeCleanupStaleHandshakingPeers(now);

		assertFalse(NetworkData.getInstance().getImmutableConnectedPeers().stream().anyMatch(connectedPeer -> connectedPeer == peer));
		assertTrue(getOutboundFailures().containsKey(PeerAddress.fromString(B32).getHost()));
	}

	@Test
	public void testPreferredI2PSkipsDataPeerAlreadyConnecting() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", java.util.List.of("I2P", "IP"), true);
		FieldUtils.writeField(NetworkData.getInstance(), "dataI2PStreamProvider", new FakeI2PStreamProvider(LOCAL_B32, true), true);
		PeerAddress i2pAddress = PeerAddress.fromString(B32);
		List<PeerData> knownPeers = getMutableKnownPeers();
		knownPeers.add(new PeerData(PeerAddress.fromString("198.51.100.10:24894"), 100L, "test"));
		knownPeers.add(new PeerData(i2pAddress, 100L, "test"));
		getConnectingI2PPeers().add(i2pAddress);

		Peer selectedPeer = invokeGetConnectablePeer(System.currentTimeMillis());

		assertEquals("198.51.100.10:24894", selectedPeer.getPeerData().getAddress().toString());
		assertFalse(selectedPeer.getPeerData().getAddress().isI2P());
	}

	@Test
	public void testDataPeerRetriesAfterConnectFailureBackoff() throws Exception {
		long now = System.currentTimeMillis();
		PeerData failedPeerData = new PeerData(PeerAddress.fromString("198.51.100.10:24894"), 100L, "test");
		failedPeerData.setLastAttempted(now - 3 * 60 * 1000L);
		getMutableKnownPeers().add(failedPeerData);

		Peer selectedPeer = invokeGetConnectablePeer(now);

		assertEquals("198.51.100.10:24894", selectedPeer.getPeerData().getAddress().toString());
		assertFalse(selectedPeer.getPeerData().getAddress().isI2P());
	}

	@Test
	public void testRecentDataPeerConnectFailureSkippedWhenNotIsolated() throws Exception {
		long now = System.currentTimeMillis();
		Peer connectedPeer = new Peer(new PeerData(PeerAddress.fromString("198.51.100.20:24894")), Peer.NETWORKDATA);
		connectedPeer.setIsDataPeer(true);
		NetworkData.getInstance().addConnectedPeer(connectedPeer);
		NetworkData.getInstance().addHandshakedPeer(connectedPeer);
		PeerData recentlyAttemptedPeerData = new PeerData(PeerAddress.fromString("198.51.100.10:24894"), 100L, "test");
		recentlyAttemptedPeerData.setLastAttempted(now - 30 * 1000L);
		getMutableKnownPeers().add(recentlyAttemptedPeerData);

		Peer selectedPeer = invokeGetConnectablePeer(now);

		assertNull(selectedPeer);
	}

	@Test
	public void testI2PDataPeerUsesLongerConnectFailureBackoff() throws Exception {
		long now = System.currentTimeMillis();
		FieldUtils.writeField(NetworkData.getInstance(), "dataI2PStreamProvider", new FakeI2PStreamProvider(LOCAL_B32, true), true);
		Peer connectedPeer = new Peer(new PeerData(PeerAddress.fromString("198.51.100.20:24894")), Peer.NETWORKDATA);
		connectedPeer.setIsDataPeer(true);
		NetworkData.getInstance().addConnectedPeer(connectedPeer);
		NetworkData.getInstance().addHandshakedPeer(connectedPeer);
		PeerData recentlyAttemptedI2PPeer = new PeerData(PeerAddress.fromString(B32), 100L, "test");
		// 3 minutes is outside the normal 2 minute TCP backoff, but still inside the 15 minute I2P backoff.
		recentlyAttemptedI2PPeer.setLastAttempted(now - 3 * 60 * 1000L);
		getMutableKnownPeers().add(recentlyAttemptedI2PPeer);

		Peer selectedPeer = invokeGetConnectablePeer(now);

		assertNull(selectedPeer);
	}

	@Test
	public void testI2POnlyIsolatedDataNetworkRetriesBackoffPeerWhenKnownIPIsDisallowed() throws Exception {
		long now = System.currentTimeMillis();
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", java.util.List.of("I2P"), true);
		FieldUtils.writeField(NetworkData.getInstance(), "dataI2PStreamProvider", new FakeI2PStreamProvider(LOCAL_B32, true), true);

		PeerData recentlyAttemptedI2PPeer = new PeerData(PeerAddress.fromString(B32), 100L, "test");
		recentlyAttemptedI2PPeer.setLastAttempted(now - 30 * 1000L);
		getMutableKnownPeers().add(recentlyAttemptedI2PPeer);
		getMutableKnownPeers().add(new PeerData(PeerAddress.fromString("198.51.100.10:24894"), 100L, "test"));

		Peer selectedPeer = invokeGetConnectablePeer(now);

		assertEquals(B32 + ":0", selectedPeer.getPeerData().getAddress().toString());
		assertTrue(selectedPeer.getPeerData().getAddress().isI2P());
	}

	@Test
	public void testI2PStartupRetriesUseFreshSamSessionIds() throws Exception {
		String firstSessionId = invokeNextI2PDataSessionId();
		String secondSessionId = invokeNextI2PDataSessionId();

		assertTrue(firstSessionId.startsWith("qortium-data-"));
		assertTrue(secondSessionId.startsWith("qortium-data-"));
		assertFalse(firstSessionId.equals(secondSessionId));
	}

	@Test
	public void testNoConnectableDataPeersWarningIncludesDiagnosticSummary() {
		KnownPeerDiagnostics diagnostics = new KnownPeerDiagnostics(KnownPeerDiagnostics.Layer.DATA);
		diagnostics.knownCount = 3;
		diagnostics.connectedCount = 0;
		diagnostics.handshakedCount = 0;
		diagnostics.connectableCount = 0;
		diagnostics.backoffCount = 2;
		diagnostics.i2pSessionUp = false;
		diagnostics.qdnFallbackCandidateCount = 1;
		diagnostics.reasonCounts.put(KnownPeerDiagnostic.Reason.RECENT_CONNECT_FAILURE, 2);
		diagnostics.reasonCounts.put(KnownPeerDiagnostic.Reason.I2P_SESSION_DOWN, 1);

		String warning = NetworkData.formatNoConnectableDataPeersWarning(diagnostics);

		assertEquals("Isolated node: No connectable data peers found "
				+ "(known=3, connected=0, handshaked=0, connectable=0, backoff=2, i2pSessionUp=false, "
				+ "qdnFallbackCandidates=1, recentConnectFailure=2, i2pSessionDown=1)", warning);
	}

	@Test
	public void testSeedInitialDataPeersSeedsClearnetWhenIPAllowed() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", java.util.List.of("IP", "I2P"), true);
		FieldUtils.writeField(Settings.getInstance(), "initialDataPeers",
				new String[] { "185.207.104.78:24894", "146.103.42.59:24894" }, true);

		NetworkData.getInstance().seedInitialDataPeers();

		List<PeerData> knownPeers = NetworkData.getInstance().getAllKnownPeers();
		assertTrue(containsAddress(knownPeers, "185.207.104.78:24894"));
		assertTrue(containsAddress(knownPeers, "146.103.42.59:24894"));
	}

	@Test
	public void testSeedInitialDataPeersSkipsClearnetWhenIPDisabled() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", java.util.List.of("I2P"), true);
		FieldUtils.writeField(Settings.getInstance(), "initialDataPeers",
				new String[] { "185.207.104.78:24894", B32 }, true);

		NetworkData.getInstance().seedInitialDataPeers();

		List<PeerData> knownPeers = NetworkData.getInstance().getAllKnownPeers();
		// Clearnet seed skipped (IP not allowed), I2P seed kept (I2P enabled)
		assertFalse(containsAddress(knownPeers, "185.207.104.78:24894"));
		assertTrue(containsAddress(knownPeers, B32 + ":0"));
	}

	@Test
	public void testSeedInitialDataPeersSkipsI2PWhenI2PDisabled() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", java.util.List.of("IP"), true);
		FieldUtils.writeField(Settings.getInstance(), "initialDataPeers",
				new String[] { "185.207.104.78:24894", B32 }, true);

		NetworkData.getInstance().seedInitialDataPeers();

		List<PeerData> knownPeers = NetworkData.getInstance().getAllKnownPeers();
		// I2P seed skipped (I2P disabled), clearnet seed kept (IP allowed)
		assertTrue(containsAddress(knownPeers, "185.207.104.78:24894"));
		assertFalse(containsAddress(knownPeers, B32 + ":0"));
	}

	@Test
	public void testSeedInitialDataPeersIgnoresInvalidEntries() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "allowedTransports", java.util.List.of("IP", "I2P"), true);
		// Unbracketed IPv6 literal is rejected by PeerAddress.fromString (requireBracketsForIPv6)
		FieldUtils.writeField(Settings.getInstance(), "initialDataPeers",
				new String[] { "2001:db8::1:9084", "185.207.104.78:24894" }, true);

		NetworkData.getInstance().seedInitialDataPeers();

		List<PeerData> knownPeers = NetworkData.getInstance().getAllKnownPeers();
		assertTrue(containsAddress(knownPeers, "185.207.104.78:24894"));
		assertEquals(1, knownPeers.size());
	}

	private Peer networkPeerWithCapabilities(Map<String, Object> capabilities) {
		Peer peer = new Peer(new PeerData(PeerAddress.fromString("198.51.100.10:24892")), Peer.NETWORK);
		peer.setPeersCapabilities(new PeerCapabilities(capabilities));
		peer.setPeersNodeId(NODE_ID);
		return peer;
	}

	private Peer dataPeerWithCapabilities(Map<String, Object> capabilities) {
		Peer peer = new Peer(new PeerData(PeerAddress.fromString("198.51.100.10:24894")), Peer.NETWORKDATA);
		peer.setPeersCapabilities(new PeerCapabilities(capabilities));
		peer.setPeersNodeId(NODE_ID);
		peer.setIsDataPeer(true);
		return peer;
	}

	private boolean containsAddress(List<PeerData> peers, String address) {
		return peers.stream().anyMatch(peerData -> peerData.getAddress().toString().equals(address));
	}

	@SuppressWarnings("unchecked")
	private void clearNetworkDataPeerState() throws Exception {
		getMutableKnownPeers().clear();
		((List<PeerAddress>) FieldUtils.readField(NetworkData.getInstance(), "selfPeers", true)).clear();
		((Map<String, ?>) FieldUtils.readField(NetworkData.getInstance(), "addressToNodeIdCache", true)).clear();
		clearPeerDirectionState();
		((List<Peer>) FieldUtils.readField(NetworkData.getInstance(), "connectedPeers", true)).clear();
		((List<Peer>) FieldUtils.readField(NetworkData.getInstance(), "handshakedPeers", true)).clear();
		((List<Peer>) FieldUtils.readField(NetworkData.getInstance(), "outboundHandshakedPeers", true)).clear();
		FieldUtils.writeField(NetworkData.getInstance(), "nextHandshakeCleanup", 0L, true);
		getConnectingI2PPeers().clear();
	}

	@SuppressWarnings("unchecked")
	private void clearPeerDirectionState() throws Exception {
		PeerDirectionState peerDirectionState = (PeerDirectionState) FieldUtils.readField(NetworkData.getInstance(),
				"peerDirectionState", true);
		((Map<String, ?>) FieldUtils.readField(peerDirectionState, "outboundFailures", true)).clear();
		((Map<String, ?>) FieldUtils.readField(peerDirectionState, "outboundFailuresByNodeId", true)).clear();
		((Map<String, ?>) FieldUtils.readField(peerDirectionState, "directionMismatchByNodeId", true)).clear();
	}

	@SuppressWarnings("unchecked")
	private List<PeerData> getMutableKnownPeers() throws Exception {
		return (List<PeerData>) FieldUtils.readField(NetworkData.getInstance(), "allKnownPeers", true);
	}

	@SuppressWarnings("unchecked")
	private java.util.Set<PeerAddress> getConnectingI2PPeers() throws Exception {
		return (java.util.Set<PeerAddress>) FieldUtils.readField(NetworkData.getInstance(), "connectingI2PPeers", true);
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

	private void invokeUpdateAddressToNodeIdCache(String address, String nodeId) throws Exception {
		java.lang.reflect.Method method = NetworkData.class.getDeclaredMethod("updateAddressToNodeIdCache", String.class, String.class);
		method.setAccessible(true);
		method.invoke(NetworkData.getInstance(), address, nodeId);
	}

	private Peer invokeFindI2PFallbackPeerWithDirectReplacement(long now) throws Exception {
		java.lang.reflect.Method method = NetworkData.class.getDeclaredMethod("findI2PFallbackPeerWithDirectReplacement", Long.class);
		method.setAccessible(true);
		return (Peer) method.invoke(NetworkData.getInstance(), now);
	}

	@SuppressWarnings("unchecked")
	private Map<String, ?> getOutboundFailures() throws Exception {
		PeerDirectionState peerDirectionState = (PeerDirectionState) FieldUtils.readField(NetworkData.getInstance(),
				"peerDirectionState", true);
		return (Map<String, ?>) FieldUtils.readField(peerDirectionState, "outboundFailures", true);
	}

	private void invokeCleanupStaleHandshakingPeers(long now) throws Exception {
		java.lang.reflect.Method method = NetworkData.class.getDeclaredMethod("cleanupStaleHandshakingPeers", Long.class);
		method.setAccessible(true);
		method.invoke(NetworkData.getInstance(), now);
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
