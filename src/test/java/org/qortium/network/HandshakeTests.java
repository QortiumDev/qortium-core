package org.qortium.network;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.controller.LiteNode;
import org.qortium.network.i2p.I2PStreamProvider;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
	public void testNeverAdvertisesI2PQdnToI2PPeer() throws Exception {
		String b32 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";
		FieldUtils.writeField(NetworkData.getInstance(), "dataI2PStreamProvider", new FakeI2PStreamProvider(b32, true), true);

		Map<String, Object> capabilities = Handshake.buildHelloCapabilities(true);

		assertFalse(capabilities.containsKey(Handshake.I2P_QDN_CAPABILITY));
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
