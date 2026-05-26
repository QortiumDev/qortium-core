package org.qortium.network;

import org.junit.Before;
import org.junit.Test;
import org.qortium.block.BlockChain;
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
	public void testHelloV2AdvertisesChainIdentity() {
		Map<String, Object> capabilities = Handshake.buildHelloV2Capabilities();
		BlockChain blockChain = BlockChain.getInstance();

		assertEquals(blockChain.getNetworkId(), capabilities.get(Handshake.CHAIN_NETWORK_ID_CAPABILITY));
		assertEquals(blockChain.getGenesisSignature(), capabilities.get(Handshake.CHAIN_GENESIS_SIGNATURE_CAPABILITY));
		assertEquals(blockChain.getChainConfigHash(), capabilities.get(Handshake.CHAIN_CONFIG_HASH_CAPABILITY));
		assertNotNull(blockChain.getChainConfigHash());
		assertNotNull(blockChain.getGenesisSignature());
	}

	@Test
	public void testMatchingChainCapabilitiesAreCompatible() {
		PeerCapabilities capabilities = new PeerCapabilities(new HashMap<>(Handshake.buildHelloV2Capabilities()));

		assertTrue(Handshake.areChainCapabilitiesCompatible(capabilities));
	}

	@Test
	public void testMismatchedNetworkIdIsIncompatible() {
		Map<String, Object> capabilities = new HashMap<>(Handshake.buildHelloV2Capabilities());
		capabilities.put(Handshake.CHAIN_NETWORK_ID_CAPABILITY, "other-network");

		assertFalse(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities)));
	}

	@Test
	public void testMismatchedGenesisSignatureIsIncompatible() {
		Map<String, Object> capabilities = new HashMap<>(Handshake.buildHelloV2Capabilities());
		capabilities.put(Handshake.CHAIN_GENESIS_SIGNATURE_CAPABILITY, "other-genesis");

		assertFalse(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities)));
	}

	@Test
	public void testMismatchedConfigHashIsIncompatible() {
		Map<String, Object> capabilities = new HashMap<>(Handshake.buildHelloV2Capabilities());
		capabilities.put(Handshake.CHAIN_CONFIG_HASH_CAPABILITY, "other-config");

		assertFalse(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities)));
	}

	@Test
	public void testMissingChainCapabilityIsIncompatible() {
		Map<String, Object> capabilities = new HashMap<>(Handshake.buildHelloV2Capabilities());
		capabilities.remove(Handshake.CHAIN_CONFIG_HASH_CAPABILITY);

		assertFalse(Handshake.areChainCapabilitiesCompatible(new PeerCapabilities(capabilities)));
	}
}
