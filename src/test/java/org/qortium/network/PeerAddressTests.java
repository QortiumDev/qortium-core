package org.qortium.network;

import org.junit.Test;

import java.net.UnknownHostException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PeerAddressTests {

	private static final String B32 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst.b32.i2p";
	private static final String B32_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567ABCDEFGHIJKLMNOPQRST.b32.i2p";

	@Test
	public void testIpAddressParsingKeepsExistingBehavior() throws Exception {
		PeerAddress address = PeerAddress.fromString("192.0.2.1:24892");

		assertEquals(PeerAddress.Kind.IP, address.getKind());
		assertFalse(address.isI2P());
		assertEquals("192.0.2.1", address.getHost());
		assertEquals(24892, address.getPort());
		assertEquals("192.0.2.1:24892", address.toString());
	}

	@Test
	public void testHostnameParsingKeepsExistingBehavior() {
		PeerAddress address = PeerAddress.fromString("peer.example.org:24892");

		assertEquals(PeerAddress.Kind.IP, address.getKind());
		assertFalse(address.isI2P());
		assertEquals("peer.example.org", address.getHost());
		assertEquals(24892, address.getPort());
	}

	@Test
	public void testI2PAddressParsing() {
		PeerAddress address = PeerAddress.fromString(B32);

		assertEquals(PeerAddress.Kind.I2P, address.getKind());
		assertTrue(address.isI2P());
		assertEquals(B32, address.getHost());
		assertEquals(0, address.getPort());
		assertEquals(B32 + ":0", address.toString());
	}

	@Test
	public void testI2PAddressParsingNormalizesHostAndIgnoresPort() {
		PeerAddress address = PeerAddress.fromString(B32_UPPER + ":24894");

		assertEquals(PeerAddress.Kind.I2P, address.getKind());
		assertEquals(B32, address.getHost());
		assertEquals(0, address.getPort());
		assertEquals(B32 + ":0", address.toString());
	}

	@Test
	public void testI2PAddressCannotUseDnsResolutionPath() {
		PeerAddress address = PeerAddress.fromString(B32);

		try {
			address.toSocketAddress();
			fail("Expected I2P peer address to reject DNS/socket conversion");
		} catch (UnknownHostException expected) {
			// expected
		}
	}

	@Test
	public void testInvalidI2PB32AddressIsRejected() {
		assertInvalid("foo.b32.i2p");
		assertInvalid("0000000000000000000000000000000000000000000000000000.b32.i2p");
		assertInvalid(B32 + " extra");
	}

	@Test
	public void testValueBasedEqualityAndHashCode() {
		PeerAddress a = PeerAddress.fromString("192.0.2.1:24892");
		PeerAddress b = PeerAddress.fromString("192.0.2.1:24892");
		PeerAddress different = PeerAddress.fromString("192.0.2.2:24892");

		// equals(Object)/hashCode are value-based and consistent across distinct instances.
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertFalse(a.equals(different));

		// I2P addresses compare case-insensitively.
		PeerAddress i2pLower = PeerAddress.fromString(B32);
		PeerAddress i2pUpper = PeerAddress.fromString(B32_UPPER);
		assertEquals(i2pLower, i2pUpper);
		assertEquals(i2pLower.hashCode(), i2pUpper.hashCode());

		// Value-equal instances therefore dedupe in a hash-based Set.
		java.util.HashSet<PeerAddress> set = new java.util.HashSet<>();
		set.add(a);
		assertTrue(set.contains(b));
		assertFalse(set.add(b));
	}

	private void assertInvalid(String address) {
		try {
			PeerAddress.fromString(address);
			fail("Expected invalid I2P address to be rejected: " + address);
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}
}
