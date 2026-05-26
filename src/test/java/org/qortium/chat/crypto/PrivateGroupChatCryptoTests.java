package org.qortium.chat.crypto;

import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.transform.Transformer;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PrivateGroupChatCryptoTests {

	@Test
	public void testGeneratedKeyAndNonceLengths() {
		assertEquals(Transformer.AES256_LENGTH, PrivateGroupChatCrypto.generateGroupKey().length);
		assertEquals(PrivateGroupChatEnvelope.NONCE_LENGTH, PrivateGroupChatCrypto.generateNonce().length);
	}

	@Test
	public void testKeyIdIsStableAndContextBound() {
		byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 1);
		byte[] groupKey = bytes(Transformer.AES256_LENGTH, 2);

		byte[] firstKeyId = PrivateGroupChatCrypto.computeKeyId(7, epochId, groupKey);
		byte[] secondKeyId = PrivateGroupChatCrypto.computeKeyId(7, epochId, groupKey);
		assertArrayEquals(firstKeyId, secondKeyId);

		assertFalse(Arrays.equals(firstKeyId, PrivateGroupChatCrypto.computeKeyId(8, epochId, groupKey)));

		byte[] differentEpochId = epochId.clone();
		differentEpochId[0] ^= 1;
		assertFalse(Arrays.equals(firstKeyId, PrivateGroupChatCrypto.computeKeyId(7, differentEpochId, groupKey)));

		byte[] differentGroupKey = groupKey.clone();
		differentGroupKey[0] ^= 1;
		assertFalse(Arrays.equals(firstKeyId, PrivateGroupChatCrypto.computeKeyId(7, epochId, differentGroupKey)));
	}

	@Test
	public void testMessageEncryptDecrypt() throws GeneralSecurityException {
		int groupId = 10;
		byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 10);
		byte[] groupKey = bytes(Transformer.AES256_LENGTH, 11);
		byte[] keyId = PrivateGroupChatCrypto.computeKeyId(groupId, epochId, groupKey);
		byte[] nonce = bytes(PrivateGroupChatEnvelope.NONCE_LENGTH, 12);
		byte[] plaintext = "private group chat message".getBytes(StandardCharsets.UTF_8);

		byte[] ciphertext = PrivateGroupChatCrypto.encryptMessage(groupKey, groupId, epochId, keyId, nonce, plaintext);
		assertFalse(Arrays.equals(plaintext, ciphertext));

		byte[] decrypted = PrivateGroupChatCrypto.decryptMessage(groupKey, groupId, epochId, keyId, nonce, ciphertext);
		assertArrayEquals(plaintext, decrypted);
	}

	@Test
	public void testMessageDecryptFailsForWrongContextOrTampering() throws GeneralSecurityException {
		int groupId = 11;
		byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 20);
		byte[] groupKey = bytes(Transformer.AES256_LENGTH, 21);
		byte[] keyId = PrivateGroupChatCrypto.computeKeyId(groupId, epochId, groupKey);
		byte[] nonce = bytes(PrivateGroupChatEnvelope.NONCE_LENGTH, 22);
		byte[] plaintext = "tamper test".getBytes(StandardCharsets.UTF_8);
		byte[] ciphertext = PrivateGroupChatCrypto.encryptMessage(groupKey, groupId, epochId, keyId, nonce, plaintext);

		byte[] wrongGroupKey = groupKey.clone();
		wrongGroupKey[0] ^= 1;
		assertDecryptFails(wrongGroupKey, groupId, epochId, keyId, nonce, ciphertext);

		assertDecryptFails(groupKey, groupId + 1, epochId, keyId, nonce, ciphertext);

		byte[] wrongEpochId = epochId.clone();
		wrongEpochId[0] ^= 1;
		assertDecryptFails(groupKey, groupId, wrongEpochId, keyId, nonce, ciphertext);

		byte[] wrongKeyId = keyId.clone();
		wrongKeyId[0] ^= 1;
		assertDecryptFails(groupKey, groupId, epochId, wrongKeyId, nonce, ciphertext);

		byte[] wrongNonce = nonce.clone();
		wrongNonce[0] ^= 1;
		assertDecryptFails(groupKey, groupId, epochId, keyId, wrongNonce, ciphertext);

		byte[] tamperedCiphertext = ciphertext.clone();
		tamperedCiphertext[tamperedCiphertext.length - 1] ^= 1;
		assertDecryptFails(groupKey, groupId, epochId, keyId, nonce, tamperedCiphertext);
	}

	@Test
	public void testGroupKeyWrapUnwrap() throws GeneralSecurityException {
		PrivateKeyAccount announcer = randomAccount();
		PrivateKeyAccount recipient = randomAccount();
		int groupId = 12;
		byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 30);
		byte[] groupKey = bytes(Transformer.AES256_LENGTH, 31);
		byte[] keyId = PrivateGroupChatCrypto.computeKeyId(groupId, epochId, groupKey);

		byte[] wrappedKey = PrivateGroupChatCrypto.wrapGroupKey(groupId, epochId, keyId, groupKey,
				announcer.getPrivateKey(), recipient.getPublicKey());
		assertTrue(wrappedKey.length > PrivateGroupChatEnvelope.NONCE_LENGTH);

		byte[] unwrappedKey = PrivateGroupChatCrypto.unwrapGroupKey(groupId, epochId, keyId, wrappedKey,
				recipient.getPrivateKey(), announcer.getPublicKey());
		assertArrayEquals(groupKey, unwrappedKey);
	}

	@Test
	public void testGroupKeyUnwrapFailsForWrongContextOrTampering() throws GeneralSecurityException {
		PrivateKeyAccount announcer = randomAccount();
		PrivateKeyAccount recipient = randomAccount();
		PrivateKeyAccount wrongRecipient = randomAccount();
		PrivateKeyAccount wrongAnnouncer = randomAccount();
		int groupId = 13;
		byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 40);
		byte[] groupKey = bytes(Transformer.AES256_LENGTH, 41);
		byte[] keyId = PrivateGroupChatCrypto.computeKeyId(groupId, epochId, groupKey);
		byte[] wrappedKey = PrivateGroupChatCrypto.wrapGroupKey(groupId, epochId, keyId, groupKey,
				announcer.getPrivateKey(), recipient.getPublicKey());

		assertUnwrapFails(groupId, epochId, keyId, wrappedKey, wrongRecipient.getPrivateKey(), announcer.getPublicKey());
		assertUnwrapFails(groupId, epochId, keyId, wrappedKey, recipient.getPrivateKey(), wrongAnnouncer.getPublicKey());
		assertUnwrapFails(groupId + 1, epochId, keyId, wrappedKey, recipient.getPrivateKey(), announcer.getPublicKey());

		byte[] wrongEpochId = epochId.clone();
		wrongEpochId[0] ^= 1;
		assertUnwrapFails(groupId, wrongEpochId, keyId, wrappedKey, recipient.getPrivateKey(), announcer.getPublicKey());

		byte[] wrongKeyId = keyId.clone();
		wrongKeyId[0] ^= 1;
		assertUnwrapFails(groupId, epochId, wrongKeyId, wrappedKey, recipient.getPrivateKey(), announcer.getPublicKey());

		byte[] tamperedWrappedKey = wrappedKey.clone();
		tamperedWrappedKey[tamperedWrappedKey.length - 1] ^= 1;
		assertUnwrapFails(groupId, epochId, keyId, tamperedWrappedKey, recipient.getPrivateKey(), announcer.getPublicKey());
	}

	@Test
	public void testUnwrappedGroupKeyMustMatchKeyId() throws GeneralSecurityException {
		PrivateKeyAccount announcer = randomAccount();
		PrivateKeyAccount recipient = randomAccount();
		int groupId = 14;
		byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 50);
		byte[] groupKey = bytes(Transformer.AES256_LENGTH, 51);
		byte[] mismatchedKeyId = bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 52);

		byte[] wrappedKey = PrivateGroupChatCrypto.wrapGroupKey(groupId, epochId, mismatchedKeyId, groupKey,
				announcer.getPrivateKey(), recipient.getPublicKey());

		assertUnwrapFails(groupId, epochId, mismatchedKeyId, wrappedKey, recipient.getPrivateKey(), announcer.getPublicKey());
	}

	@Test
	public void testInvalidInputsAreRejected() {
		byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 60);
		byte[] groupKey = bytes(Transformer.AES256_LENGTH, 61);
		byte[] keyId = PrivateGroupChatCrypto.computeKeyId(15, epochId, groupKey);
		byte[] nonce = bytes(PrivateGroupChatEnvelope.NONCE_LENGTH, 62);
		byte[] plaintext = bytes(4, 63);

		assertThrows(IllegalArgumentException.class, () -> PrivateGroupChatCrypto.computeKeyId(15, new byte[31], groupKey));
		assertThrows(IllegalArgumentException.class, () -> PrivateGroupChatCrypto.computeKeyId(15, epochId, new byte[31]));
		assertThrows(IllegalArgumentException.class, () -> PrivateGroupChatCrypto.encryptMessage(new byte[31], 15, epochId, keyId, nonce, plaintext));
		assertThrows(IllegalArgumentException.class, () -> PrivateGroupChatCrypto.encryptMessage(groupKey, 15, epochId, keyId, new byte[11], plaintext));
		assertThrows(IllegalArgumentException.class, () -> PrivateGroupChatCrypto.encryptMessage(groupKey, 15, epochId, keyId, nonce, new byte[0]));
		assertThrows(IllegalArgumentException.class, () -> PrivateGroupChatCrypto.unwrapGroupKey(15, epochId, keyId, new byte[PrivateGroupChatEnvelope.NONCE_LENGTH],
				randomAccount().getPrivateKey(), randomAccount().getPublicKey()));
	}

	private static void assertDecryptFails(byte[] groupKey, int groupId, byte[] epochId, byte[] keyId, byte[] nonce,
			byte[] ciphertext) {
		assertThrows(GeneralSecurityException.class,
				() -> PrivateGroupChatCrypto.decryptMessage(groupKey, groupId, epochId, keyId, nonce, ciphertext));
	}

	private static void assertUnwrapFails(int groupId, byte[] epochId, byte[] keyId, byte[] wrappedKey,
			byte[] recipientPrivateKey, byte[] announcerPublicKey) {
		assertThrows(GeneralSecurityException.class,
				() -> PrivateGroupChatCrypto.unwrapGroupKey(groupId, epochId, keyId, wrappedKey,
						recipientPrivateKey, announcerPublicKey));
	}

	private static PrivateKeyAccount randomAccount() {
		byte[] privateKey = new byte[Transformer.PRIVATE_KEY_LENGTH];
		new SecureRandom().nextBytes(privateKey);
		return new PrivateKeyAccount(null, privateKey);
	}

	private static byte[] bytes(int length, int seed) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; ++i)
			bytes[i] = (byte) (seed + i);

		return bytes;
	}

}
