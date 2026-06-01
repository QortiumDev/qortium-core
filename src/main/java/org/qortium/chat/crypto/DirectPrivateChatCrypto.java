package org.qortium.chat.crypto;

import org.qortium.crypto.Crypto;
import org.qortium.transform.Transformer;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

public class DirectPrivateChatCrypto {

	private static final String AES = "AES";
	private static final String AES_GCM = "AES/GCM/NoPadding";
	private static final String HMAC_SHA256 = "HmacSHA256";
	private static final int GCM_TAG_LENGTH_BITS = 128;

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private static final byte[] MESSAGE_AAD_DOMAIN = "QDM1 message v1".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] SHARED_KEY_HKDF_SALT = "QDM1 shared key hkdf salt v1".getBytes(StandardCharsets.US_ASCII);

	private DirectPrivateChatCrypto() {
	}

	public static byte[] generateNonce() {
		byte[] nonce = new byte[DirectPrivateChatEnvelope.NONCE_LENGTH];
		SECURE_RANDOM.nextBytes(nonce);
		return nonce;
	}

	public static byte[] encryptMessage(byte[] senderPrivateKey, byte[] recipientPublicKey, byte[] plaintext)
			throws GeneralSecurityException {
		validateLength(senderPrivateKey, Transformer.PRIVATE_KEY_LENGTH, "sender private key");
		validateLength(recipientPublicKey, Transformer.PUBLIC_KEY_LENGTH, "recipient public key");
		validatePayload(plaintext, "plaintext");

		byte[] senderPublicKey = Crypto.toPublicKey(senderPrivateKey);
		byte[] nonce = generateNonce();
		byte[] associatedData = buildMessageAssociatedData(senderPublicKey, recipientPublicKey);
		byte[] sharedSecret = Crypto.getSharedSecret(senderPrivateKey, recipientPublicKey);
		byte[] sharedKey = deriveSharedKey(sharedSecret, associatedData);
		byte[] ciphertext = doAesGcm(Cipher.ENCRYPT_MODE, sharedKey, nonce, associatedData, plaintext);

		return DirectPrivateChatEnvelope.message(senderPublicKey, recipientPublicKey, nonce, ciphertext).toBytes();
	}

	public static byte[] decryptMessage(byte[] localPrivateKey, DirectPrivateChatEnvelope envelope)
			throws GeneralSecurityException {
		validateLength(localPrivateKey, Transformer.PRIVATE_KEY_LENGTH, "local private key");
		if (envelope == null)
			throw new IllegalArgumentException("direct private chat envelope is missing");

		byte[] localPublicKey = Crypto.toPublicKey(localPrivateKey);
		byte[] senderPublicKey = envelope.getSenderPublicKey();
		byte[] recipientPublicKey = envelope.getRecipientPublicKey();
		byte[] otherPublicKey;

		if (Arrays.equals(localPublicKey, senderPublicKey)) {
			otherPublicKey = recipientPublicKey;
		} else if (Arrays.equals(localPublicKey, recipientPublicKey)) {
			otherPublicKey = senderPublicKey;
		} else {
			throw new GeneralSecurityException("Local account is not a participant in this direct private chat");
		}

		byte[] associatedData = buildMessageAssociatedData(senderPublicKey, recipientPublicKey);
		byte[] sharedSecret = Crypto.getSharedSecret(localPrivateKey, otherPublicKey);
		byte[] sharedKey = deriveSharedKey(sharedSecret, associatedData);
		return doAesGcm(Cipher.DECRYPT_MODE, sharedKey, envelope.getNonce(), associatedData,
				envelope.getCiphertext());
	}

	private static byte[] buildMessageAssociatedData(byte[] senderPublicKey, byte[] recipientPublicKey) {
		validateLength(senderPublicKey, Transformer.PUBLIC_KEY_LENGTH, "sender public key");
		validateLength(recipientPublicKey, Transformer.PUBLIC_KEY_LENGTH, "recipient public key");

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		writeBytes(bytes, MESSAGE_AAD_DOMAIN);
		writeInt(bytes, DirectPrivateChatEnvelope.MAGIC);
		writeBytes(bytes, senderPublicKey);
		writeBytes(bytes, recipientPublicKey);
		return bytes.toByteArray();
	}

	private static byte[] doAesGcm(int mode, byte[] key, byte[] nonce, byte[] associatedData, byte[] input)
			throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance(AES_GCM);
		cipher.init(mode, new SecretKeySpec(key, AES), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
		cipher.updateAAD(associatedData);
		return cipher.doFinal(input);
	}

	private static byte[] deriveSharedKey(byte[] sharedSecret, byte[] info) throws GeneralSecurityException {
		validateLength(sharedSecret, Crypto.SHARED_SECRET_LENGTH, "shared secret");

		byte[] pseudorandomKey = hmac(SHARED_KEY_HKDF_SALT, sharedSecret);
		byte[] output = hmac(pseudorandomKey, concat(info, new byte[] { 1 }));
		return Arrays.copyOf(output, Transformer.AES256_LENGTH);
	}

	private static byte[] hmac(byte[] key, byte[] input) throws GeneralSecurityException {
		Mac mac = Mac.getInstance(HMAC_SHA256);
		mac.init(new SecretKeySpec(key, HMAC_SHA256));
		return mac.doFinal(input);
	}

	private static byte[] concat(byte[] first, byte[] second) {
		byte[] output = new byte[first.length + second.length];
		System.arraycopy(first, 0, output, 0, first.length);
		System.arraycopy(second, 0, output, first.length, second.length);
		return output;
	}

	private static void validatePayload(byte[] bytes, String fieldName) {
		if (bytes == null)
			throw new IllegalArgumentException(fieldName + " is missing");

		if (bytes.length == 0)
			throw new IllegalArgumentException(fieldName + " is empty");
	}

	private static void validateLength(byte[] bytes, int expectedLength, String fieldName) {
		if (bytes == null)
			throw new IllegalArgumentException(fieldName + " is missing");

		if (bytes.length != expectedLength)
			throw new IllegalArgumentException(fieldName + " has invalid length");
	}

	private static void writeInt(ByteArrayOutputStream bytes, int value) {
		bytes.write((value >>> 24) & 0xff);
		bytes.write((value >>> 16) & 0xff);
		bytes.write((value >>> 8) & 0xff);
		bytes.write(value & 0xff);
	}

	private static void writeBytes(ByteArrayOutputStream bytes, byte[] value) {
		bytes.write(value, 0, value.length);
	}

}
