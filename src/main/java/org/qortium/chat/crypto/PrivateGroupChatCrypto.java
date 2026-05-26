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

public class PrivateGroupChatCrypto {

	private static final String AES = "AES";
	private static final String AES_GCM = "AES/GCM/NoPadding";
	private static final String HMAC_SHA256 = "HmacSHA256";
	private static final int GCM_TAG_LENGTH_BITS = 128;

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private static final byte[] KEY_ID_DOMAIN = "QPGC key id v1".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] MESSAGE_AAD_DOMAIN = "QPGC message v1".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] KEY_WRAP_AAD_DOMAIN = "QPGC key wrap v1".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] KEY_WRAP_HKDF_SALT = "QPGC key wrap hkdf salt v1".getBytes(StandardCharsets.US_ASCII);

	private PrivateGroupChatCrypto() {
	}

	public static byte[] generateGroupKey() {
		byte[] groupKey = new byte[Transformer.AES256_LENGTH];
		SECURE_RANDOM.nextBytes(groupKey);
		return groupKey;
	}

	public static byte[] generateNonce() {
		byte[] nonce = new byte[PrivateGroupChatEnvelope.NONCE_LENGTH];
		SECURE_RANDOM.nextBytes(nonce);
		return nonce;
	}

	public static byte[] computeKeyId(int groupId, byte[] epochId, byte[] groupKey) {
		validateLength(epochId, PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, "epoch id");
		validateLength(groupKey, Transformer.AES256_LENGTH, "group key");

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		writeBytes(bytes, KEY_ID_DOMAIN);
		writeInt(bytes, groupId);
		writeBytes(bytes, epochId);
		writeBytes(bytes, groupKey);

		return Crypto.digest(bytes.toByteArray());
	}

	public static byte[] encryptMessage(byte[] groupKey, int groupId, byte[] epochId, byte[] keyId, byte[] nonce,
			byte[] plaintext) throws GeneralSecurityException {
		validateLength(groupKey, Transformer.AES256_LENGTH, "group key");
		validateLength(nonce, PrivateGroupChatEnvelope.NONCE_LENGTH, "nonce");
		validatePayload(plaintext, "plaintext");

		byte[] associatedData = buildMessageAssociatedData(groupId, epochId, keyId);
		return doAesGcm(Cipher.ENCRYPT_MODE, groupKey, nonce, associatedData, plaintext);
	}

	public static byte[] decryptMessage(byte[] groupKey, int groupId, byte[] epochId, byte[] keyId, byte[] nonce,
			byte[] ciphertext) throws GeneralSecurityException {
		validateLength(groupKey, Transformer.AES256_LENGTH, "group key");
		validateLength(nonce, PrivateGroupChatEnvelope.NONCE_LENGTH, "nonce");
		validatePayload(ciphertext, "ciphertext");

		byte[] associatedData = buildMessageAssociatedData(groupId, epochId, keyId);
		return doAesGcm(Cipher.DECRYPT_MODE, groupKey, nonce, associatedData, ciphertext);
	}

	public static byte[] wrapGroupKey(int groupId, byte[] epochId, byte[] keyId, byte[] groupKey,
			byte[] announcerPrivateKey, byte[] recipientPublicKey) throws GeneralSecurityException {
		validateLength(groupKey, Transformer.AES256_LENGTH, "group key");
		validateLength(announcerPrivateKey, Transformer.PRIVATE_KEY_LENGTH, "announcer private key");
		validateLength(recipientPublicKey, Transformer.PUBLIC_KEY_LENGTH, "recipient public key");

		byte[] announcerPublicKey = Crypto.toPublicKey(announcerPrivateKey);
		byte[] sharedSecret = Crypto.getSharedSecret(announcerPrivateKey, recipientPublicKey);
		byte[] associatedData = buildKeyWrapAssociatedData(groupId, epochId, keyId, announcerPublicKey, recipientPublicKey);
		byte[] wrappingKey = deriveWrappingKey(sharedSecret, associatedData);
		byte[] nonce = generateNonce();
		byte[] encryptedKey = doAesGcm(Cipher.ENCRYPT_MODE, wrappingKey, nonce, associatedData, groupKey);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		writeBytes(bytes, nonce);
		writeBytes(bytes, encryptedKey);
		return bytes.toByteArray();
	}

	public static byte[] unwrapGroupKey(int groupId, byte[] epochId, byte[] keyId, byte[] wrappedKey,
			byte[] recipientPrivateKey, byte[] announcerPublicKey) throws GeneralSecurityException {
		validateLength(recipientPrivateKey, Transformer.PRIVATE_KEY_LENGTH, "recipient private key");
		validateLength(announcerPublicKey, Transformer.PUBLIC_KEY_LENGTH, "announcer public key");
		validateWrappedKey(wrappedKey);

		byte[] recipientPublicKey = Crypto.toPublicKey(recipientPrivateKey);
		byte[] sharedSecret = Crypto.getSharedSecret(recipientPrivateKey, announcerPublicKey);
		byte[] associatedData = buildKeyWrapAssociatedData(groupId, epochId, keyId, announcerPublicKey, recipientPublicKey);
		byte[] wrappingKey = deriveWrappingKey(sharedSecret, associatedData);

		byte[] nonce = Arrays.copyOfRange(wrappedKey, 0, PrivateGroupChatEnvelope.NONCE_LENGTH);
		byte[] ciphertext = Arrays.copyOfRange(wrappedKey, PrivateGroupChatEnvelope.NONCE_LENGTH, wrappedKey.length);
		byte[] groupKey = doAesGcm(Cipher.DECRYPT_MODE, wrappingKey, nonce, associatedData, ciphertext);

		if (!Arrays.equals(computeKeyId(groupId, epochId, groupKey), keyId))
			throw new GeneralSecurityException("Unwrapped group key does not match key id");

		return groupKey;
	}

	private static byte[] buildMessageAssociatedData(int groupId, byte[] epochId, byte[] keyId) {
		validateLength(epochId, PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, "epoch id");
		validateLength(keyId, PrivateGroupChatEnvelope.KEY_ID_LENGTH, "key id");

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		writeBytes(bytes, MESSAGE_AAD_DOMAIN);
		writeInt(bytes, groupId);
		writeBytes(bytes, epochId);
		writeBytes(bytes, keyId);
		return bytes.toByteArray();
	}

	private static byte[] buildKeyWrapAssociatedData(int groupId, byte[] epochId, byte[] keyId,
			byte[] announcerPublicKey, byte[] recipientPublicKey) {
		validateLength(epochId, PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, "epoch id");
		validateLength(keyId, PrivateGroupChatEnvelope.KEY_ID_LENGTH, "key id");
		validateLength(announcerPublicKey, Transformer.PUBLIC_KEY_LENGTH, "announcer public key");
		validateLength(recipientPublicKey, Transformer.PUBLIC_KEY_LENGTH, "recipient public key");

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		writeBytes(bytes, KEY_WRAP_AAD_DOMAIN);
		writeInt(bytes, groupId);
		writeBytes(bytes, epochId);
		writeBytes(bytes, keyId);
		writeBytes(bytes, announcerPublicKey);
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

	private static byte[] deriveWrappingKey(byte[] sharedSecret, byte[] info) throws GeneralSecurityException {
		validateLength(sharedSecret, Crypto.SHARED_SECRET_LENGTH, "shared secret");

		byte[] pseudorandomKey = hmac(KEY_WRAP_HKDF_SALT, sharedSecret);
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

	private static void validateWrappedKey(byte[] wrappedKey) {
		if (wrappedKey == null)
			throw new IllegalArgumentException("wrapped key is missing");

		if (wrappedKey.length <= PrivateGroupChatEnvelope.NONCE_LENGTH)
			throw new IllegalArgumentException("wrapped key is too short");
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
