package org.qortium.test;

import org.junit.Before;
import org.junit.Test;
import org.qortium.chat.crypto.DirectPrivateChatCrypto;
import org.qortium.chat.crypto.DirectPrivateChatEnvelope;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.transform.TransformationException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DirectPrivateChatCryptoTests extends Common {

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testRoundTripForSenderAndRecipient() throws Exception {
		TestAccount alice = Common.getTestAccount(null, "alice");
		TestAccount bob = Common.getTestAccount(null, "bob");
		byte[] payload = "direct private roundtrip".getBytes(StandardCharsets.UTF_8);

		byte[] encrypted = DirectPrivateChatCrypto.encryptMessage(alice.getPrivateKey(), bob.getPublicKey(), payload);
		DirectPrivateChatEnvelope envelope = DirectPrivateChatEnvelope.fromBytes(encrypted);

		assertArrayEquals(alice.getPublicKey(), envelope.getSenderPublicKey());
		assertArrayEquals(bob.getPublicKey(), envelope.getRecipientPublicKey());
		assertArrayEquals(payload, DirectPrivateChatCrypto.decryptMessage(bob.getPrivateKey(), envelope));
		assertArrayEquals(payload, DirectPrivateChatCrypto.decryptMessage(alice.getPrivateKey(), envelope));
	}

	@Test
	public void testWrongAccountCannotDecrypt() throws Exception {
		TestAccount alice = Common.getTestAccount(null, "alice");
		TestAccount bob = Common.getTestAccount(null, "bob");
		TestAccount chloe = Common.getTestAccount(null, "chloe");
		byte[] payload = "direct private wrong account".getBytes(StandardCharsets.UTF_8);

		byte[] encrypted = DirectPrivateChatCrypto.encryptMessage(alice.getPrivateKey(), bob.getPublicKey(), payload);
		DirectPrivateChatEnvelope envelope = DirectPrivateChatEnvelope.fromBytes(encrypted);

		try {
			DirectPrivateChatCrypto.decryptMessage(chloe.getPrivateKey(), envelope);
			fail("Expected wrong account decryption to fail");
		} catch (GeneralSecurityException expected) {
			// Expected
		}
	}

	@Test
	public void testTamperedCiphertextCannotDecrypt() throws Exception {
		TestAccount alice = Common.getTestAccount(null, "alice");
		TestAccount bob = Common.getTestAccount(null, "bob");
		byte[] payload = "direct private tamper".getBytes(StandardCharsets.UTF_8);

		byte[] encrypted = DirectPrivateChatCrypto.encryptMessage(alice.getPrivateKey(), bob.getPublicKey(), payload);
		DirectPrivateChatEnvelope envelope = DirectPrivateChatEnvelope.fromBytes(encrypted);
		byte[] ciphertext = envelope.getCiphertext();
		ciphertext[ciphertext.length - 1] ^= 1;
		DirectPrivateChatEnvelope tampered = DirectPrivateChatEnvelope.message(envelope.getSenderPublicKey(),
				envelope.getRecipientPublicKey(), envelope.getNonce(), ciphertext);

		try {
			DirectPrivateChatCrypto.decryptMessage(bob.getPrivateKey(), tampered);
			fail("Expected tampered ciphertext to fail");
		} catch (GeneralSecurityException expected) {
			// Expected
		}
	}

	@Test
	public void testLegacyPayloadIsUnsupportedEnvelope() {
		try {
			DirectPrivateChatEnvelope.fromBytes("legacy encrypted bytes".getBytes(StandardCharsets.UTF_8));
			fail("Expected legacy payload to be rejected");
		} catch (TransformationException expected) {
			assertTrue(expected.getMessage().contains("too short")
					|| expected.getMessage().contains("magic"));
		}
	}

}
