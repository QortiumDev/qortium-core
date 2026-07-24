package org.qortium.network;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.data.network.KnownPeerDiagnostics;
import org.qortium.data.network.PeerData;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NetworkDataCapacityTests extends Common {

	private NetworkData networkData;

	@Before
	public void before() throws Exception {
		Common.useDefaultSettings();
		this.networkData = NetworkData.getInstance();
		clearCapacityState();
	}

	@After
	public void after() throws Exception {
		clearCapacityState();
	}

	@Test
	public void testDataAdmissionUsesMaxDataPeersNotChainMaxPeers() {
		assertEquals(32, Network.getInstance().getMaxPeers());
		assertEquals(64, this.networkData.getMaxDataPeers());
		assertNotEquals(Network.getInstance().getMaxPeers(), this.networkData.getMaxDataPeers());

		List<NetworkData.PeerAdmission> admissions = reserveToLimit();
		assertEquals(this.networkData.getMaxDataPeers() + 1, admissions.size());
		assertNull(this.networkData.tryReservePeerAdmission());
		releaseAll(admissions);
	}

	@Test
	public void testConcurrentAdmissionsNeverExceedDataCapacityPlusOne() throws Exception {
		int attempts = (this.networkData.getMaxDataPeers() + 1) * 3;
		ExecutorService executor = Executors.newFixedThreadPool(8);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger accepted = new AtomicInteger();
		List<NetworkData.PeerAdmission> admissions = java.util.Collections.synchronizedList(new ArrayList<>());

		for (int i = 0; i < attempts; ++i) {
			executor.submit(() -> {
				try {
					start.await();
					NetworkData.PeerAdmission admission = this.networkData.tryReservePeerAdmission();
					if (admission != null) {
						admissions.add(admission);
						accepted.incrementAndGet();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
		}

		start.countDown();
		executor.shutdown();
		assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

		assertEquals(this.networkData.getMaxDataPeers() + 1, accepted.get());
		assertEquals(accepted.get(), this.networkData.getPendingPeerAdmissions());
		releaseAll(admissions);
	}

	@Test
	public void testCompletedDataCapacityAllowsExactlyOneProvisionalAdmission() {
		fillCompletedCapacity(null);

		NetworkData.PeerAdmission provisional = this.networkData.tryReservePeerAdmission();
		assertNotNull(provisional);
		assertNull(this.networkData.tryReservePeerAdmission());
		assertEquals(1, this.networkData.getPendingPeerAdmissions());

		this.networkData.releasePeerAdmission(provisional);
	}

	@Test
	public void testAdmissionReleaseIsExactlyOnceAndForceConnectCannotBypassCapacity() {
		NetworkData.PeerAdmission releasedTwice = this.networkData.tryReservePeerAdmission();
		assertNotNull(releasedTwice);
		this.networkData.releasePeerAdmission(releasedTwice);
		this.networkData.releasePeerAdmission(releasedTwice);

		List<NetworkData.PeerAdmission> admissions = reserveToLimit();
		assertNull(this.networkData.tryReservePeerAdmission());

		FailingPeer peer = new FailingPeer();
		assertFalse(this.networkData.forceConnectPeer(peer));
		assertEquals("forceConnectPeer must not open a socket when all data slots are reserved", 0, peer.connectAttempts);

		releaseAll(admissions);
	}

	@Test
	public void testFailedOutboundConnectReleasesItsReservation() throws Exception {
		FailingPeer peer = new FailingPeer();

		assertFalse(this.networkData.connectPeer(peer));
		assertEquals(1, peer.connectAttempts);
		assertEquals(0, this.networkData.getPendingPeerAdmissions());
	}

	@Test
	public void testDiagnosticsExposeEffectiveDataCapacityAndPendingAdmissions() {
		NetworkData.PeerAdmission admission = this.networkData.tryReservePeerAdmission();
		KnownPeerDiagnostics diagnostics = this.networkData.getKnownPeerDiagnostics(System.currentTimeMillis());

		assertEquals(Integer.valueOf(this.networkData.getMaxDataPeers()), diagnostics.dataPeerCapacity);
		assertEquals(Integer.valueOf(1), diagnostics.pendingDataPeerAdmissions);

		this.networkData.releasePeerAdmission(admission);
	}

	@Test
	public void testRestartRequiredSettingsReplacementDoesNotChangeLiveDataCapacity() throws Exception {
		int effectiveCapacity = this.networkData.getMaxDataPeers();
		FieldUtils.writeField(Settings.getInstance(), "maxDataPeers", effectiveCapacity - 1, true);

		assertEquals(effectiveCapacity, this.networkData.getMaxDataPeers());
	}

	@Test
	public void testFullCapacityRejectsNonDuplicateNewcomerWithoutEvictingIncumbent() {
		List<Peer> incumbents = fillCompletedCapacity(null);
		Peer newcomer = peer("198.51.100.250:24894", new byte[] { 1 });

		this.networkData.onHandshakeCompleted(newcomer);

		assertEquals(this.networkData.getMaxDataPeers(), this.networkData.getImmutableHandshakedPeers().size());
		assertFalse(containsPeer(newcomer));
		assertTrue(incumbents.stream().allMatch(this::containsPeer));
	}

	@Test
	public void testFullCapacityAllowsAProvenDuplicateToReplaceCorrectDirectionIncumbent() throws Exception {
		byte[] duplicateKey = new byte[] { 7 };
		List<Peer> incumbents = fillCompletedCapacity(duplicateKey);
		Peer existingIncumbent = incumbents.get(0);
		SocketChannel incumbentSocket = SocketChannel.open();
		FieldUtils.writeField(existingIncumbent, "socketChannel", incumbentSocket, true);
		Peer replacement = peer("198.51.100.251:24894", duplicateKey);
		FieldUtils.writeField(replacement, "isOutbound", false, true);
		replacement.setPeersNodeId("");

		try {
			this.networkData.onHandshakeCompleted(replacement);
		} finally {
			incumbentSocket.close();
		}

		assertEquals(this.networkData.getMaxDataPeers(), this.networkData.getImmutableHandshakedPeers().size());
		assertFalse(containsPeer(existingIncumbent));
		assertTrue(containsPeer(replacement));
	}

	private List<NetworkData.PeerAdmission> reserveToLimit() {
		List<NetworkData.PeerAdmission> admissions = new ArrayList<>();
		for (int i = 0; i < this.networkData.getMaxDataPeers() + 1; ++i) {
			NetworkData.PeerAdmission admission = this.networkData.tryReservePeerAdmission();
			assertNotNull(admission);
			admissions.add(admission);
		}
		return admissions;
	}

	private void releaseAll(List<NetworkData.PeerAdmission> admissions) {
		for (NetworkData.PeerAdmission admission : admissions)
			this.networkData.releasePeerAdmission(admission);
	}

	private List<Peer> fillCompletedCapacity(byte[] firstPeerKey) {
		List<Peer> peers = new ArrayList<>();
		for (int i = 0; i < this.networkData.getMaxDataPeers(); ++i) {
			byte[] key = i == 0 ? firstPeerKey : new byte[] { (byte) (i + 10) };
			Peer peer = peer("198.51.100." + (i + 1) + ":24894", key);
			this.networkData.addConnectedPeer(peer);
			this.networkData.addHandshakedPeer(peer);
			peers.add(peer);
		}
		return peers;
	}

	private Peer peer(String address, byte[] publicKey) {
		Peer peer = new CapacityTestPeer(address);
		peer.setPeersPublicKey(publicKey);
		peer.setPeersNodeId("node-" + address);
		return peer;
	}

	private boolean containsPeer(Peer target) {
		return this.networkData.getImmutableHandshakedPeers().stream().anyMatch(peer -> peer == target);
	}

	@SuppressWarnings("unchecked")
	private void clearCapacityState() throws Exception {
		((List<Peer>) FieldUtils.readField(this.networkData, "connectedPeers", true)).clear();
		((List<Peer>) FieldUtils.readField(this.networkData, "handshakedPeers", true)).clear();
		((List<Peer>) FieldUtils.readField(this.networkData, "outboundHandshakedPeers", true)).clear();
		FieldUtils.writeField(this.networkData, "pendingPeerAdmissions", 0, true);
	}

	private static class FailingPeer extends Peer {
		private int connectAttempts;

		private FailingPeer() {
			super(new PeerData(PeerAddress.fromString("198.51.100.10:24894")), Peer.NETWORKDATA);
		}

		@Override
		public SocketChannel connect(int network) {
			this.connectAttempts++;
			return null;
		}
	}

	/**
	 * Test peer whose disconnect performs the NetworkData list cleanup without
	 * attempting socket shutdown. Capacity decisions are under test here, not
	 * SocketChannel lifecycle, and most fixtures intentionally have no socket.
	 */
	private static class CapacityTestPeer extends Peer {
		private CapacityTestPeer(String address) {
			super(new PeerData(PeerAddress.fromString(address)), Peer.NETWORKDATA);
		}

		@Override
		public void disconnect(String reason) {
			NetworkData.getInstance().removeConnectedPeer(this);
		}
	}
}
