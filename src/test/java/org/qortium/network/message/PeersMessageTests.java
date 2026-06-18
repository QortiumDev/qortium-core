package org.qortium.network.message;

import org.junit.Test;
import org.qortium.network.PeerAddress;
import org.qortium.test.common.Common;

import java.nio.ByteBuffer;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PeersMessageTests extends Common {

	private static final String B32 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";

	@Test
	public void testI2PAddressRoundTrip() throws MessageException {
		PeerAddress original = PeerAddress.fromString(B32);
		PeersMessage message = new PeersMessage(Collections.singletonList(original));

		PeersMessage decoded = (PeersMessage) PeersMessage.fromByteBuffer(1, ByteBuffer.wrap(message.dataBytes));

		assertEquals(2, decoded.getPeerAddresses().size());
		PeerAddress decodedAddress = decoded.getPeerAddresses().get(1);
		assertEquals(PeerAddress.Kind.I2P, decodedAddress.getKind());
		assertTrue(decodedAddress.equals(original));
		assertEquals(B32 + ":0", decodedAddress.toString());
	}
}
