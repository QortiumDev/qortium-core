package org.qortium.network.message;

import org.junit.Test;
import org.qortium.controller.LiteNode;
import org.qortium.network.Peer;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class HelloMessageTests {

	@Test
	public void testLiteDataCapabilityRoundTrip() throws MessageException {
		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put(LiteNode.LITE_DATA_CAPABILITY, LiteNode.LITE_DATA_CAPABILITY_VERSION);
		capabilities.put("CHAIN_CONFIG_HASH", "abc123");

		HelloMessage message = new HelloMessage(123L, "1.0.0", "127.0.0.1:14892", capabilities, Peer.NETWORK);

		HelloMessage decodedMessage = (HelloMessage) HelloMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));
		Object liteDataCapability = decodedMessage.getCapabilities().getCapability(LiteNode.LITE_DATA_CAPABILITY);
		Object chainConfigHash = decodedMessage.getCapabilities().getCapability("CHAIN_CONFIG_HASH");

		assertEquals(LiteNode.LITE_DATA_CAPABILITY_VERSION, ((Number) liteDataCapability).intValue());
		assertEquals("abc123", chainConfigHash);
	}

	@Test
	public void testI2PQdnCapabilityRoundTrip() throws MessageException {
		String b32 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";
		Map<String, Object> capabilities = new HashMap<>();
		capabilities.put("QDN", 24894);
		capabilities.put("I2P_QDN", b32);

		HelloMessage message = new HelloMessage(123L, "1.0.0", "127.0.0.1:14892", capabilities, Peer.NETWORK);

		HelloMessage decodedMessage = (HelloMessage) HelloMessage.fromByteBuffer(123, ByteBuffer.wrap(message.dataBytes));
		Object qdnCapability = decodedMessage.getCapabilities().getCapability("QDN");
		Object i2pQdnCapability = decodedMessage.getCapabilities().getCapability("I2P_QDN");

		assertEquals(24894, ((Number) qdnCapability).intValue());
		assertEquals(b32, i2pQdnCapability);
	}

}
