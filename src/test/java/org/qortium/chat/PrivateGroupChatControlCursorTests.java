package org.qortium.chat;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class PrivateGroupChatControlCursorTests {

	@Test
	public void testCursorRoundTripIsStable() {
		byte[] signature = signature(7);
		PrivateGroupChatControlCursor cursor = new PrivateGroupChatControlCursor(1_700_000_000_000L, signature);
		PrivateGroupChatControlCursor decoded = PrivateGroupChatControlCursor.decode(cursor.encode());

		assertEquals(cursor.getTimestamp(), decoded.getTimestamp());
		assertArrayEquals(signature, decoded.getSignature());
		assertEquals(cursor.encode(), decoded.encode());
		assertNull(PrivateGroupChatControlCursor.decode(null));
	}

	@Test
	public void testCursorRejectsMalformedAndUnsupportedValues() {
		assertRejected("not base58!");
		assertRejected("1");

		PrivateGroupChatControlCursor valid = new PrivateGroupChatControlCursor(1_700_000_000_000L, signature(8));
		byte[] bytes = org.qortium.utils.Base58.decode(valid.encode());
		bytes[0] = 2;
		assertRejected(org.qortium.utils.Base58.encode(bytes));
	}

	private static void assertRejected(String value) {
		try {
			PrivateGroupChatControlCursor.decode(value);
			fail("Cursor should have been rejected: " + value);
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	private static byte[] signature(int marker) {
		byte[] signature = new byte[64];
		signature[63] = (byte) marker;
		return signature;
	}
}
