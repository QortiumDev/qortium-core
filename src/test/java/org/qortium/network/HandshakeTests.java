package org.qortium.network;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.controller.LiteNode;
import org.qortium.data.network.PeerData;
import org.qortium.network.helper.PeerCapabilities;
import org.qortium.network.i2p.I2PStreamProvider;
import org.qortium.network.message.HelloMessage;
import org.qortium.network.message.Message;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.utils.NTP;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class HandshakeTests {

	@Before
	public void before() throws Exception {
		Common.useDefaultSettings();
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", null, true);
		FieldUtils.writeField(NetworkData.getInstance(), "dataI2PStreamProvider", null, true);
	}

	@After
	public void after() throws Exception {
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", null, true);
		FieldUtils.writeField(NetworkData.getInstance(), "dataI2PStreamProvider", null, true);
	}

	@Test
	public void testNonLiteNodeAdvertisesLiteDataCapability() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "lite", false, true);

		Map<String, Object> capabilities = Handshake.buildHelloCapabilities();

		assertEquals(LiteNode.LITE_DATA_CAPABILITY_VERSION, capabilities.get(LiteNode.LITE_DATA_CAPABILITY));
	}

	@Test
	public void testLiteNodeDoesNotAdvertiseLiteDataCapability() throws Exception {
		FieldUtils.writeField(Settings.getInstance(), "lite", true, true);

		Map<String, Object> capabilities = Handshake.buildHelloCapabilities();

		assertFalse(capabilities.containsKey(LiteNode.LITE_DATA_CAPABILITY));
	}

	@Test
	public void testAdvertisesI2PChainToI2PPeerWhenChainSessionIsUp() throws Exception {
		String b32 = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", new FakeI2PStreamProvider(b32, true), true);

		Map<String, Object> capabilities = Handshake.buildHelloCapabilities(true);

		assertEquals(b32, capabilities.get(Handshake.I2P_CAPABILITY));
	}

	@Test
	public void testDoesNotAdvertiseI2PChainToI2PPeerWhenChainSessionIsDown() throws Exception {
		String b32 = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", new FakeI2PStreamProvider(b32, false), true);

		Map<String, Object> capabilities = Handshake.buildHelloCapabilities(true);

		assertFalse(capabilities.containsKey(Handshake.I2P_CAPABILITY));
	}

	@Test
	public void testDoesNotAdvertiseI2PChainToClearnetPeerEvenWhenChainSessionIsUp() throws Exception {
		String b32 = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider", new FakeI2PStreamProvider(b32, true), true);

		Map<String, Object> capabilities = Handshake.buildHelloCapabilities(false);

		assertFalse(capabilities.containsKey(Handshake.I2P_CAPABILITY));
	}

	@Test
	public void testAdvertisesQdnPortToClearnetPeerButNotToI2PPeer() throws Exception {
		Map<String, Object> clearnet = Handshake.buildHelloCapabilities(false);
		assertTrue("clearnet peer must learn our QDN port", clearnet.containsKey("QDN"));

		Map<String, Object> i2p = Handshake.buildHelloCapabilities(true);
		assertFalse("I2P peer must not learn our clearnet QDN port", i2p.containsKey("QDN"));
	}

	@Test
	public void testNeverAdvertisesI2PQdnToClearnetPeer() throws Exception {
		String b32 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";
		FieldUtils.writeField(NetworkData.getInstance(), "dataI2PStreamProvider", new FakeI2PStreamProvider(b32, true), true);

		Map<String, Object> capabilities = Handshake.buildHelloCapabilities(false);

		assertFalse(capabilities.containsKey(Handshake.I2P_QDN_CAPABILITY));
	}

	@Test
	public void testHelloAddressCapabilitiesAreLayerAndTransportScoped() throws Exception {
		String chainB32 = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";
		String dataB32 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider",
				new FakeI2PStreamProvider(chainB32, true), true);
		FieldUtils.writeField(NetworkData.getInstance(), "dataI2PStreamProvider",
				new FakeI2PStreamProvider(dataB32, true), true);

		Map<String, Object> chainIp = Handshake.buildHelloCapabilities(Peer.NETWORK, false);
		assertTrue(chainIp.containsKey("QDN"));
		assertFalse(chainIp.containsKey(Handshake.I2P_CAPABILITY));
		assertFalse(chainIp.containsKey(Handshake.I2P_QDN_CAPABILITY));

		Map<String, Object> chainI2p = Handshake.buildHelloCapabilities(Peer.NETWORK, true);
		assertEquals(chainB32, chainI2p.get(Handshake.I2P_CAPABILITY));
		assertFalse(chainI2p.containsKey("QDN"));
		assertFalse(chainI2p.containsKey(Handshake.I2P_QDN_CAPABILITY));

		Map<String, Object> dataIp = Handshake.buildHelloCapabilities(Peer.NETWORKDATA, false);
		assertFalse(dataIp.containsKey("QDN"));
		assertFalse(dataIp.containsKey(Handshake.I2P_CAPABILITY));
		assertFalse(dataIp.containsKey(Handshake.I2P_QDN_CAPABILITY));

		Map<String, Object> dataI2p = Handshake.buildHelloCapabilities(Peer.NETWORKDATA, true);
		assertEquals(dataB32, dataI2p.get(Handshake.I2P_QDN_CAPABILITY));
		assertFalse(dataI2p.containsKey("QDN"));
		assertFalse(dataI2p.containsKey(Handshake.I2P_CAPABILITY));
	}

	@Test
	public void testPostHandshakeHelloStripsWrongLayerAndTransportCapabilities() {
		String chainB32 = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";
		String dataB32 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";
		for (int peerType : new int[] { Peer.NETWORK, Peer.NETWORKDATA }) {
			for (String address : new String[] { "198.51.100.10:24892", dataB32 }) {
				Peer peer = new Peer(new PeerData(PeerAddress.fromString(address)), peerType);
				Map<String, Object> capabilities = new HashMap<>(
						Handshake.buildHelloCapabilities(peerType, address.endsWith(".i2p")));
				capabilities.put("QDN", 24894);
				capabilities.put(Handshake.I2P_CAPABILITY, chainB32);
				capabilities.put(Handshake.I2P_QDN_CAPABILITY, dataB32);
				assertTrue(Handshake.applyPostHandshakeHello(peer,
						new org.qortium.network.message.HelloMessage(1L, "qortium-1.0.0",
								address, capabilities, peerType)));

				boolean i2p = address.endsWith(".i2p");
				assertEquals(peerType == Peer.NETWORK && !i2p,
						peer.getPeerCapability("QDN") != null);
				assertEquals(peerType == Peer.NETWORK && i2p,
						peer.getPeerCapability(Handshake.I2P_CAPABILITY) != null);
				assertEquals(peerType == Peer.NETWORKDATA && i2p,
						peer.getPeerCapability(Handshake.I2P_QDN_CAPABILITY) != null);
			}
		}
	}

	@Test
	public void testHelloActionUsesPeerLayerAndTransport() throws Exception {
		String chainB32 = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";
		String dataB32 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";
		FieldUtils.writeField(Network.getInstance(), "chainI2PStreamProvider",
				new FakeI2PStreamProvider(chainB32, true), true);
		FieldUtils.writeField(NetworkData.getInstance(), "dataI2PStreamProvider",
				new FakeI2PStreamProvider(dataB32, true), true);

		for (int peerType : new int[] { Peer.NETWORK, Peer.NETWORKDATA }) {
			for (String address : new String[] { "198.51.100.10:24892", dataB32 }) {
				CapturingPeer peer = new CapturingPeer(address, peerType);
				Handshake.HELLO.action(peer);
				Message parsed = Message.fromByteBuffer(ByteBuffer.wrap(peer.sentMessage.toBytes()));
				Map<String, Object> capabilities = ((HelloMessage) parsed).getCapabilities()
						.getPeerCapabilities();
				boolean i2p = address.endsWith(".i2p");
				assertEquals(peerType == Peer.NETWORK && !i2p, capabilities.containsKey("QDN"));
				assertEquals(peerType == Peer.NETWORK && i2p, capabilities.containsKey(Handshake.I2P_CAPABILITY));
				assertEquals(peerType == Peer.NETWORKDATA && i2p,
						capabilities.containsKey(Handshake.I2P_QDN_CAPABILITY));
			}
		}
	}

	@Test
	public void testInitialHelloStripsWrongLayerAndTransportCapabilities() {
		String chainB32 = "bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";
		String dataB32 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";
		for (int peerType : new int[] { Peer.NETWORK, Peer.NETWORKDATA }) {
			for (String address : new String[] { "198.51.100.10:24892", dataB32 }) {
				Peer peer = new Peer(new PeerData(PeerAddress.fromString(address)), peerType);
				Map<String, Object> capabilities = new HashMap<>(
						Handshake.buildHelloCapabilities(peerType, address.endsWith(".i2p")));
				capabilities.put("QDN", 24894);
				capabilities.put(Handshake.I2P_CAPABILITY, chainB32);
				capabilities.put(Handshake.I2P_QDN_CAPABILITY, dataB32);

				Handshake next = Handshake.HELLO.onMessage(peer, new HelloMessage(NTP.getTime(),
						"qortium-1.0.0", "127.0.0.1:24892", capabilities, peerType));
				assertSame(Handshake.CHALLENGE, next);

				boolean i2p = address.endsWith(".i2p");
				assertEquals(peerType == Peer.NETWORK && !i2p,
						peer.getPeerCapability("QDN") != null);
				assertEquals(peerType == Peer.NETWORK && i2p,
						peer.getPeerCapability(Handshake.I2P_CAPABILITY) != null);
				assertEquals(peerType == Peer.NETWORKDATA && i2p,
						peer.getPeerCapability(Handshake.I2P_QDN_CAPABILITY) != null);
			}
		}
	}

	@Test
	public void testAdvertisesHandshakePowV2Capability() {
		Map<String, Object> capabilities = Handshake.buildHelloCapabilities();

		assertEquals(Boolean.TRUE, capabilities.get(Handshake.HANDSHAKE_POW_V2_CAPABILITY));
	}

	@Test
	public void testHandshakePowV2CapabilityOverridesResetQortiumVersion() {
		Peer peer = new Peer(new PeerData(PeerAddress.fromString("198.51.100.10:24892")), Peer.NETWORK);
		peer.setPeersVersion("qortium-1.0.0", 0x0100000000L);
		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put(Handshake.HANDSHAKE_POW_V2_CAPABILITY, true);
		peer.setPeersCapabilities(new PeerCapabilities(capabilities));

		assertSame(Handshake.HandshakePowParameters.V2, Handshake.getHandshakePowParameters(peer));
	}

	@Test
	public void testMissingHandshakePowV2CapabilityKeepsLegacyParametersForResetQortiumVersion() {
		Peer peer = new Peer(new PeerData(PeerAddress.fromString("198.51.100.10:24892")), Peer.NETWORK);
		peer.setPeersVersion("qortium-1.0.0", 0x0100000000L);
		peer.setPeersCapabilities(new PeerCapabilities(new HashMap<>()));

		assertSame(Handshake.HandshakePowParameters.LEGACY, Handshake.getHandshakePowParameters(peer));
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

	private static class CapturingPeer extends Peer {
		private Message sentMessage;

		private CapturingPeer(String address, int peerType) {
			super(new PeerData(PeerAddress.fromString(address)), peerType);
		}

		@Override
		public boolean sendMessage(Message message) {
			this.sentMessage = message;
			return true;
		}
	}
}
