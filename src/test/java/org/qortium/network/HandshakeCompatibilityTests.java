package org.qortium.network;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortium.block.BlockChain;
import org.qortium.data.network.PeerData;
import org.qortium.network.helper.PeerCapabilities;
import org.qortium.test.common.Common;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HandshakeCompatibilityTests extends Common {

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testHelloAdvertisesChainIdentity() {
		BlockChain blockChain = BlockChain.getInstance();
		for (int peerType : new int[] { Peer.NETWORK, Peer.NETWORKDATA }) {
			for (boolean i2p : new boolean[] { false, true }) {
				Map<String, Object> capabilities = Handshake.buildHelloCapabilities(peerType, i2p);
				assertEquals(blockChain.getNetworkId(), capabilities.get(Handshake.CHAIN_NETWORK_ID_CAPABILITY));
				assertEquals(blockChain.getGenesisSignature(), capabilities.get(Handshake.CHAIN_GENESIS_SIGNATURE_CAPABILITY));
				assertEquals(blockChain.getChainConfigHash(), capabilities.get(Handshake.CHAIN_CONFIG_HASH_CAPABILITY));
				assertEquals(blockChain.getFeatureTriggerScheduleVersion(),
						capabilities.get(Handshake.FEATURE_TRIGGER_SCHEDULE_VERSION_CAPABILITY));
				assertEquals(blockChain.getFeatureTriggerScheduleHash(),
						capabilities.get(Handshake.FEATURE_TRIGGER_SCHEDULE_HASH_CAPABILITY));
			}
		}
		assertNotNull(blockChain.getChainConfigHash());
		assertNotNull(blockChain.getGenesisSignature());
	}

	@Test
	public void testMatchingChainCapabilitiesAreCompatible() {
		PeerCapabilities capabilities = new PeerCapabilities(new HashMap<>(Handshake.buildHelloCapabilities()));

		assertTrue(Handshake.areChainCapabilitiesCompatible(capabilities));
	}

	@Test
	public void testMismatchedNetworkIdIsIncompatible() {
		Map<String, Object> capabilities = new HashMap<>(Handshake.buildHelloCapabilities());
		capabilities.put(Handshake.CHAIN_NETWORK_ID_CAPABILITY, "other-network");

		assertFalse(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities)));
	}

	@Test
	public void testMismatchedGenesisSignatureIsIncompatible() {
		Map<String, Object> capabilities = new HashMap<>(Handshake.buildHelloCapabilities());
		capabilities.put(Handshake.CHAIN_GENESIS_SIGNATURE_CAPABILITY, "other-genesis");

		assertFalse(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities)));
	}

	@Test
	public void testMismatchedConfigHashIsIncompatible() {
		Map<String, Object> capabilities = new HashMap<>(Handshake.buildHelloCapabilities());
		capabilities.put(Handshake.CHAIN_CONFIG_HASH_CAPABILITY, "other-config");

		assertFalse(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities)));
	}

	@Test
	public void testMissingChainCapabilityIsIncompatible() {
		Map<String, Object> capabilities = new HashMap<>(Handshake.buildHelloCapabilities());
		capabilities.remove(Handshake.CHAIN_CONFIG_HASH_CAPABILITY);

		assertFalse(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities)));
	}

	@Test
	public void testScheduleMismatchIsAllowedOnlyBeforeLocalEnforcementHeight() throws Exception {
		BlockChain blockChain = BlockChain.getInstance();
		FieldUtils.writeField(blockChain, "featureTriggerScheduleEnforcementHeight", 100L, true);
		Map<String, Object> capabilities = new HashMap<>(Handshake.buildHelloCapabilities());

		assertTrue(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities), 100L));

		capabilities.remove(Handshake.FEATURE_TRIGGER_SCHEDULE_HASH_CAPABILITY);
		assertTrue(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities), 99L));
		assertFalse(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities), 100L));

		capabilities.put(Handshake.FEATURE_TRIGGER_SCHEDULE_HASH_CAPABILITY, "other-schedule");
		assertTrue(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities), 99L));
		assertFalse(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities), 100L));

		capabilities.put(Handshake.FEATURE_TRIGGER_SCHEDULE_HASH_CAPABILITY,
				blockChain.getFeatureTriggerScheduleHash());
		capabilities.put(Handshake.FEATURE_TRIGGER_SCHEDULE_VERSION_CAPABILITY, 2);
		assertTrue(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities), 99L));
		assertFalse(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities), 100L));
	}

	@Test
	public void testBaseIdentityMismatchIsRejectedBeforeScheduleEnforcement() throws Exception {
		FieldUtils.writeField(BlockChain.getInstance(), "featureTriggerScheduleEnforcementHeight", 100L, true);
		Map<String, Object> capabilities = new HashMap<>(Handshake.buildHelloCapabilities());
		capabilities.put(Handshake.CHAIN_CONFIG_HASH_CAPABILITY, "other-config");

		assertFalse(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities), 99L));
	}

	@Test
	public void testExistingChainAndDataSessionsAreDisconnectedAtCutover() throws Exception {
		FieldUtils.writeField(BlockChain.getInstance(), "featureTriggerScheduleEnforcementHeight", 100L, true);
		TrackingPeer chainPeer = peer(Peer.NETWORK, true);
		TrackingPeer dataPeer = peer(Peer.NETWORKDATA, false);

		assertEquals(0, Handshake.disconnectPeersWithIncompatibleFeatureSchedule(
				java.util.List.of(chainPeer, dataPeer), 99L));
		assertFalse(chainPeer.disconnected);
		assertFalse(dataPeer.disconnected);

		assertEquals(1, Handshake.disconnectPeersWithIncompatibleFeatureSchedule(
				java.util.List.of(chainPeer, dataPeer), 100L));
		assertFalse(chainPeer.disconnected);
		assertTrue(dataPeer.disconnected);
	}

	private static TrackingPeer peer(int peerType, boolean matchingSchedule) throws Exception {
		String address = peerType == Peer.NETWORK ? "198.51.100.10:24892" : "198.51.100.10:24894";
		TrackingPeer peer = new TrackingPeer(new PeerData(PeerAddress.fromString(address)), peerType);
		Map<String, Object> capabilities = new HashMap<>(Handshake.buildHelloCapabilities(peerType, false));
		if (!matchingSchedule)
			capabilities.remove(Handshake.FEATURE_TRIGGER_SCHEDULE_HASH_CAPABILITY);
		peer.setPeersCapabilities(new PeerCapabilities(capabilities));
		return peer;
	}

	private static final class TrackingPeer extends Peer {
		private boolean disconnected;

		private TrackingPeer(PeerData peerData, int peerType) {
			super(peerData, peerType);
		}

		@Override
		public void disconnect(String reason) {
			this.disconnected = true;
		}
	}
}
