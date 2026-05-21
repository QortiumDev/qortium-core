package org.qortal.test;

import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.account.PublicKeyAccount;
import org.qortal.crypto.Crypto;
import org.qortal.utils.Base58;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class PublicKeyAccountTests {

	@Test
	public void testPublicKeyAccountAcceptsValidPublicKey() {
		byte[] publicKey = validPublicKey();
		PublicKeyAccount account = new PublicKeyAccount(null, publicKey);

		assertArrayEquals(publicKey, account.getPublicKey());
		assertEquals(Crypto.toAddress(publicKey), account.getAddress());
	}

	@Test
	public void testPublicKeyAccountAcceptsAllZeroPublicKey() {
		PublicKeyAccount account = new PublicKeyAccount(null, PublicKeyAccount.ALL_ZEROS);

		assertArrayEquals(PublicKeyAccount.ALL_ZEROS, account.getPublicKey());
		assertEquals(Crypto.toAddress(PublicKeyAccount.ALL_ZEROS), account.getAddress());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testPublicKeyAccountRejectsNullPublicKey() {
		new PublicKeyAccount(null, null);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testPublicKeyAccountRejectsShortPublicKey() {
		new PublicKeyAccount(null, Arrays.copyOf(validPublicKey(), 31));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testPublicKeyAccountRejectsLongPublicKey() {
		new PublicKeyAccount(null, Arrays.copyOf(validPublicKey(), 33));
	}

	private static byte[] validPublicKey() {
		final String privateKey58 = "A9MNsATgQgruBUjxy2rjWY36Yf19uRioKZbiLFT2P7c6";
		return new PrivateKeyAccount(null, Base58.decode(privateKey58)).getPublicKey();
	}

}
