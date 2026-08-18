package org.qortium.chat.crypto;

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.qortium.arbitrary.misc.EncryptedDataEnvelope;
import org.qortium.crypto.Crypto;
import org.qortium.crypto.Ed25519Extras;
import org.qortium.transform.TransformationException;
import org.qortium.transform.Transformer;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Reference cryptography and codec for QENC v2 private chat attachments. */
public final class PrivateChatAttachmentCrypto {

	public static final int MAX_ENVELOPE_BYTES = 1024 * 1024;

	private static final String AES = "AES";
	private static final String AES_GCM = "AES/GCM/NoPadding";
	private static final String HMAC_SHA256 = "HmacSHA256";
	private static final int GCM_TAG_LENGTH_BITS = EncryptedDataEnvelope.AUTH_TAG_LENGTH * Byte.SIZE;
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private static final byte[] CONTENT_AAD_DOMAIN = ascii("QENC attachment content v2");
	private static final byte[] RECIPIENT_WRAP_AAD_DOMAIN = ascii("QENC attachment recipient wrap v2");
	private static final byte[] RECIPIENT_WRAP_HKDF_SALT = ascii("QENC attachment recipient hkdf salt v2");
	private static final byte[] GROUP_CONTENT_INFO_DOMAIN = ascii("QENC attachment group content v2");
	private static final byte[] GROUP_CONTENT_HKDF_SALT = ascii("QENC attachment group hkdf salt v2");

	private PrivateChatAttachmentCrypto() {
	}

	/** Encrypt a direct attachment to both distinct CHAT participants, including the sender for reopen. */
	public static byte[] encryptDirect(byte[] senderPublicKey, byte[] recipientPublicKey,
			PrivateChatAttachmentPayload payload) throws GeneralSecurityException {
		validateEd25519PublicKey(senderPublicKey, "sender public key");
		validateEd25519PublicKey(recipientPublicKey, "recipient public key");
		if (Arrays.equals(senderPublicKey, recipientPublicKey))
			throw new IllegalArgumentException("Direct attachment participants must be distinct");
		return encryptForRecipients(List.of(senderPublicKey, recipientPublicKey), payload);
	}

	/** Encrypt to one or more account public keys. Chat direct-message callers use {@link #encryptDirect}. */
	public static byte[] encryptForRecipients(List<byte[]> recipientPublicKeys,
			PrivateChatAttachmentPayload payload) throws GeneralSecurityException {
		if (recipientPublicKeys == null)
			throw new IllegalArgumentException("Recipient public keys are missing");

		byte[] ephemeralPrivateKey = new X25519PrivateKeyParameters(SECURE_RANDOM).getEncoded();
		byte[] contentKey = randomBytes(Transformer.AES256_LENGTH);
		byte[] contentNonce = randomBytes(EncryptedDataEnvelope.CONTENT_NONCE_LENGTH);
		List<byte[]> wrapNonces = new ArrayList<>(recipientPublicKeys.size());
		for (int index = 0; index < recipientPublicKeys.size(); ++index)
			wrapNonces.add(randomBytes(EncryptedDataEnvelope.WRAP_NONCE_LENGTH));

		return encryptForRecipients(recipientPublicKeys, payload, ephemeralPrivateKey, contentKey,
				contentNonce, wrapNonces);
	}

	static byte[] encryptForRecipients(List<byte[]> recipientPublicKeys, PrivateChatAttachmentPayload payload,
			byte[] ephemeralPrivateKey, byte[] contentKey, byte[] contentNonce, List<byte[]> wrapNonces)
			throws GeneralSecurityException {
		validatePayload(payload);
		validateLength(ephemeralPrivateKey, Transformer.PRIVATE_KEY_LENGTH, "ephemeral private key");
		validateLength(contentKey, Transformer.AES256_LENGTH, "content key");
		validateLength(contentNonce, EncryptedDataEnvelope.CONTENT_NONCE_LENGTH, "content nonce");
		if (recipientPublicKeys == null || recipientPublicKeys.isEmpty()
				|| recipientPublicKeys.size() > EncryptedDataEnvelope.MAX_RECIPIENTS)
			throw new IllegalArgumentException("Recipient count is invalid");
		if (wrapNonces == null || wrapNonces.size() != recipientPublicKeys.size())
			throw new IllegalArgumentException("Recipient wrap nonce count does not match");

		X25519PrivateKeyParameters ephemeralPrivate = new X25519PrivateKeyParameters(ephemeralPrivateKey, 0);
		byte[] ephemeralPublicKey = ephemeralPrivate.generatePublicKey().getEncoded();
		List<RecipientMaterial> recipients = new ArrayList<>(recipientPublicKeys.size());
		for (int index = 0; index < recipientPublicKeys.size(); ++index) {
			byte[] publicKey = recipientPublicKeys.get(index);
			byte[] wrapNonce = wrapNonces.get(index);
			validateEd25519PublicKey(publicKey, "recipient public key");
			validateLength(wrapNonce, EncryptedDataEnvelope.WRAP_NONCE_LENGTH, "wrap nonce");
			recipients.add(new RecipientMaterial(publicKey, recipientKeyId(publicKey), wrapNonce));
		}
		recipients.sort(Comparator.comparing(RecipientMaterial::keyId,
				PrivateChatAttachmentCrypto::compareUnsigned));
		for (int index = 1; index < recipients.size(); ++index)
			if (compareUnsigned(recipients.get(index - 1).keyId(), recipients.get(index).keyId()) == 0)
				throw new IllegalArgumentException("Recipient key ids must be unique");

		List<RecipientEntry> entries = new ArrayList<>(recipients.size());
		for (RecipientMaterial recipient : recipients) {
			byte[] sharedSecret = x25519(ephemeralPrivate, recipient.publicKey());
			byte[] wrapAssociatedData = buildRecipientWrapAssociatedData(ephemeralPublicKey,
					recipient.keyId(), contentNonce);
			byte[] wrappingKey = deriveRecipientWrappingKey(sharedSecret, wrapAssociatedData);
			byte[] wrappedKey = doAesGcm(Cipher.ENCRYPT_MODE, wrappingKey, recipient.wrapNonce(),
					wrapAssociatedData, contentKey);
			entries.add(new RecipientEntry(recipient.keyId(), recipient.wrapNonce(), wrappedKey));
		}

		byte[] variableHeader = buildRecipientsHeader(contentNonce, ephemeralPublicKey, entries);
		byte[] fixedHeader = buildFixedHeader(EncryptedDataEnvelope.MODE_RECIPIENTS, variableHeader.length);
		byte[] contentAssociatedData = buildContentAssociatedData(fixedHeader, variableHeader);
		byte[] ciphertext = doAesGcm(Cipher.ENCRYPT_MODE, contentKey, contentNonce,
				contentAssociatedData, payload.toBytes());
		return buildEnvelope(fixedHeader, variableHeader, ciphertext);
	}

	/** Decrypt a recipient-mode attachment for the selected account. */
	public static PrivateChatAttachmentPayload decryptForRecipient(byte[] localPrivateKey, byte[] envelope)
			throws GeneralSecurityException, TransformationException {
		validateLength(localPrivateKey, Transformer.PRIVATE_KEY_LENGTH, "local private key");
		ParsedEnvelope parsed = parse(envelope, EncryptedDataEnvelope.MODE_RECIPIENTS);
		byte[] localPublicKey = Crypto.toPublicKey(localPrivateKey);
		byte[] localKeyId = recipientKeyId(localPublicKey);
		RecipientEntry matchingEntry = parsed.recipientEntries().stream()
				.filter(entry -> Arrays.equals(entry.keyId(), localKeyId))
				.findFirst()
				.orElseThrow(() -> new GeneralSecurityException("Selected account is not an attachment recipient"));

		X25519PrivateKeyParameters localX25519 = new X25519PrivateKeyParameters(
				Ed25519Extras.toX25519PrivateKey(localPrivateKey), 0);
		byte[] sharedSecret = new byte[Crypto.SHARED_SECRET_LENGTH];
		localX25519.generateSecret(new X25519PublicKeyParameters(parsed.ephemeralPublicKey(), 0),
				sharedSecret, 0);
		byte[] wrapAssociatedData = buildRecipientWrapAssociatedData(parsed.ephemeralPublicKey(),
				matchingEntry.keyId(), parsed.contentNonce());
		byte[] wrappingKey = deriveRecipientWrappingKey(sharedSecret, wrapAssociatedData);
		byte[] contentKey = doAesGcm(Cipher.DECRYPT_MODE, wrappingKey, matchingEntry.wrapNonce(),
				wrapAssociatedData, matchingEntry.wrappedKey());
		byte[] plaintext = doAesGcm(Cipher.DECRYPT_MODE, contentKey, parsed.contentNonce(),
				buildContentAssociatedData(parsed.fixedHeader(), parsed.variableHeader()), parsed.ciphertext());
		return PrivateChatAttachmentPayload.fromBytes(plaintext);
	}

	public static byte[] encryptForGroup(byte[] groupKey, int groupId, byte[] epochId, byte[] keyId,
			PrivateChatAttachmentPayload payload) throws GeneralSecurityException {
		return encryptForGroup(groupKey, groupId, epochId, keyId,
				randomBytes(EncryptedDataEnvelope.CONTENT_NONCE_LENGTH), payload);
	}

	static byte[] encryptForGroup(byte[] groupKey, int groupId, byte[] epochId, byte[] keyId,
			byte[] contentNonce, PrivateChatAttachmentPayload payload) throws GeneralSecurityException {
		validatePayload(payload);
		validateGroupContext(groupKey, groupId, epochId, keyId);
		validateLength(contentNonce, EncryptedDataEnvelope.CONTENT_NONCE_LENGTH, "content nonce");

		byte[] variableHeader = buildGroupHeader(groupId, epochId, keyId, contentNonce);
		byte[] fixedHeader = buildFixedHeader(EncryptedDataEnvelope.MODE_GROUP, variableHeader.length);
		byte[] groupInfo = buildGroupContentInfo(groupId, epochId, keyId);
		byte[] contentKey = deriveGroupContentKey(groupKey, groupInfo);
		byte[] ciphertext = doAesGcm(Cipher.ENCRYPT_MODE, contentKey, contentNonce,
				buildContentAssociatedData(fixedHeader, variableHeader), payload.toBytes());
		return buildEnvelope(fixedHeader, variableHeader, ciphertext);
	}

	public static PrivateChatAttachmentPayload decryptForGroup(byte[] groupKey, int expectedGroupId,
			byte[] expectedEpochId, byte[] expectedKeyId, byte[] envelope)
			throws GeneralSecurityException, TransformationException {
		validateGroupContext(groupKey, expectedGroupId, expectedEpochId, expectedKeyId);
		ParsedEnvelope parsed = parse(envelope, EncryptedDataEnvelope.MODE_GROUP);
		if (parsed.groupId() != expectedGroupId || !Arrays.equals(parsed.epochId(), expectedEpochId)
				|| !Arrays.equals(parsed.groupKeyId(), expectedKeyId))
			throw new GeneralSecurityException("Private attachment group context does not match");

		byte[] groupInfo = buildGroupContentInfo(parsed.groupId(), parsed.epochId(), parsed.groupKeyId());
		byte[] contentKey = deriveGroupContentKey(groupKey, groupInfo);
		byte[] plaintext = doAesGcm(Cipher.DECRYPT_MODE, contentKey, parsed.contentNonce(),
				buildContentAssociatedData(parsed.fixedHeader(), parsed.variableHeader()), parsed.ciphertext());
		return PrivateChatAttachmentPayload.fromBytes(plaintext);
	}

	static byte[] recipientKeyId(byte[] publicKey) {
		validateEd25519PublicKey(publicKey, "recipient public key");
		return Arrays.copyOf(Crypto.digest(publicKey), EncryptedDataEnvelope.RECIPIENT_KEY_ID_LENGTH);
	}

	static byte[] buildRecipientWrapAssociatedData(byte[] ephemeralPublicKey, byte[] recipientKeyId,
			byte[] contentNonce) {
		validateLength(ephemeralPublicKey, EncryptedDataEnvelope.EPHEMERAL_PUBLIC_KEY_LENGTH,
				"ephemeral public key");
		validateLength(recipientKeyId, EncryptedDataEnvelope.RECIPIENT_KEY_ID_LENGTH, "recipient key id");
		validateLength(contentNonce, EncryptedDataEnvelope.CONTENT_NONCE_LENGTH, "content nonce");
		return concat(RECIPIENT_WRAP_AAD_DOMAIN, ephemeralPublicKey, recipientKeyId, contentNonce);
	}

	static byte[] deriveRecipientWrappingKey(byte[] sharedSecret, byte[] info)
			throws GeneralSecurityException {
		validateLength(sharedSecret, Crypto.SHARED_SECRET_LENGTH, "shared secret");
		return hkdfSha256(sharedSecret, RECIPIENT_WRAP_HKDF_SALT, info);
	}

	static byte[] buildGroupContentInfo(int groupId, byte[] epochId, byte[] keyId) {
		if (groupId <= 0)
			throw new IllegalArgumentException("group id must be positive");
		validateLength(epochId, EncryptedDataEnvelope.EPOCH_ID_LENGTH, "epoch id");
		validateLength(keyId, EncryptedDataEnvelope.GROUP_KEY_ID_LENGTH, "group key id");
		return concat(GROUP_CONTENT_INFO_DOMAIN, intBytes(groupId), epochId, keyId);
	}

	static byte[] deriveGroupContentKey(byte[] groupKey, byte[] info) throws GeneralSecurityException {
		validateLength(groupKey, Transformer.AES256_LENGTH, "group key");
		return hkdfSha256(groupKey, GROUP_CONTENT_HKDF_SALT, info);
	}

	static byte[] buildContentAssociatedData(byte[] fixedHeader, byte[] variableHeader) {
		return concat(CONTENT_AAD_DOMAIN, fixedHeader, variableHeader);
	}

	private static ParsedEnvelope parse(byte[] envelope, byte expectedMode) throws TransformationException {
		if (envelope == null || envelope.length > MAX_ENVELOPE_BYTES
				|| !EncryptedDataEnvelope.isEnvelope(envelope)
				|| envelope[4] != EncryptedDataEnvelope.VERSION_2
				|| envelope[5] != expectedMode)
			throw new TransformationException("Private chat attachment has an invalid QENC v2 envelope");

		int headerLength = ((envelope[8] & 0xff) << 8) | (envelope[9] & 0xff);
		byte[] fixedHeader = Arrays.copyOfRange(envelope, 0, EncryptedDataEnvelope.FIXED_HEADER_LENGTH);
		byte[] variableHeader = Arrays.copyOfRange(envelope, EncryptedDataEnvelope.FIXED_HEADER_LENGTH,
				EncryptedDataEnvelope.FIXED_HEADER_LENGTH + headerLength);
		byte[] ciphertext = Arrays.copyOfRange(envelope,
				EncryptedDataEnvelope.FIXED_HEADER_LENGTH + headerLength, envelope.length);

		if (expectedMode == EncryptedDataEnvelope.MODE_RECIPIENTS) {
			int recipientCount = ((variableHeader[0] & 0xff) << 8) | (variableHeader[1] & 0xff);
			byte[] contentNonce = Arrays.copyOfRange(variableHeader, 2,
					2 + EncryptedDataEnvelope.CONTENT_NONCE_LENGTH);
			int ephemeralOffset = 2 + EncryptedDataEnvelope.CONTENT_NONCE_LENGTH;
			byte[] ephemeralPublicKey = Arrays.copyOfRange(variableHeader, ephemeralOffset,
					ephemeralOffset + EncryptedDataEnvelope.EPHEMERAL_PUBLIC_KEY_LENGTH);
			List<RecipientEntry> entries = new ArrayList<>(recipientCount);
			int entryOffset = EncryptedDataEnvelope.RECIPIENTS_HEADER_PREFIX_LENGTH;
			for (int index = 0; index < recipientCount; ++index) {
				byte[] keyId = Arrays.copyOfRange(variableHeader, entryOffset,
						entryOffset + EncryptedDataEnvelope.RECIPIENT_KEY_ID_LENGTH);
				int wrapNonceOffset = entryOffset + EncryptedDataEnvelope.RECIPIENT_KEY_ID_LENGTH;
				byte[] wrapNonce = Arrays.copyOfRange(variableHeader, wrapNonceOffset,
						wrapNonceOffset + EncryptedDataEnvelope.WRAP_NONCE_LENGTH);
				int wrappedKeyOffset = wrapNonceOffset + EncryptedDataEnvelope.WRAP_NONCE_LENGTH;
				byte[] wrappedKey = Arrays.copyOfRange(variableHeader, wrappedKeyOffset,
						wrappedKeyOffset + EncryptedDataEnvelope.WRAPPED_CONTENT_KEY_LENGTH);
				entries.add(new RecipientEntry(keyId, wrapNonce, wrappedKey));
				entryOffset += EncryptedDataEnvelope.RECIPIENT_ENTRY_LENGTH;
			}
			return ParsedEnvelope.recipients(fixedHeader, variableHeader, ciphertext, contentNonce,
					ephemeralPublicKey, entries);
		}

		int groupId = readInt(variableHeader, 0);
		byte[] epochId = Arrays.copyOfRange(variableHeader, EncryptedDataEnvelope.GROUP_ID_LENGTH,
				EncryptedDataEnvelope.GROUP_ID_LENGTH + EncryptedDataEnvelope.EPOCH_ID_LENGTH);
		int keyIdOffset = EncryptedDataEnvelope.GROUP_ID_LENGTH + EncryptedDataEnvelope.EPOCH_ID_LENGTH;
		byte[] keyId = Arrays.copyOfRange(variableHeader, keyIdOffset,
				keyIdOffset + EncryptedDataEnvelope.GROUP_KEY_ID_LENGTH);
		int nonceOffset = keyIdOffset + EncryptedDataEnvelope.GROUP_KEY_ID_LENGTH;
		byte[] contentNonce = Arrays.copyOfRange(variableHeader, nonceOffset,
				nonceOffset + EncryptedDataEnvelope.CONTENT_NONCE_LENGTH);
		return ParsedEnvelope.group(fixedHeader, variableHeader, ciphertext, contentNonce,
				groupId, epochId, keyId);
	}

	private static byte[] buildRecipientsHeader(byte[] contentNonce, byte[] ephemeralPublicKey,
			List<RecipientEntry> entries) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(
				EncryptedDataEnvelope.RECIPIENTS_HEADER_PREFIX_LENGTH
						+ entries.size() * EncryptedDataEnvelope.RECIPIENT_ENTRY_LENGTH);
		writeUnsignedShort(bytes, entries.size());
		writeBytes(bytes, contentNonce);
		writeBytes(bytes, ephemeralPublicKey);
		for (RecipientEntry entry : entries) {
			writeBytes(bytes, entry.keyId());
			writeBytes(bytes, entry.wrapNonce());
			writeBytes(bytes, entry.wrappedKey());
		}
		return bytes.toByteArray();
	}

	private static byte[] buildGroupHeader(int groupId, byte[] epochId, byte[] keyId, byte[] contentNonce) {
		return concat(intBytes(groupId), epochId, keyId, contentNonce);
	}

	private static byte[] buildFixedHeader(byte mode, int headerLength) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(EncryptedDataEnvelope.FIXED_HEADER_LENGTH);
		writeBytes(bytes, EncryptedDataEnvelope.MAGIC);
		bytes.write(EncryptedDataEnvelope.VERSION_2);
		bytes.write(mode);
		bytes.write(EncryptedDataEnvelope.CIPHER_AES_256_GCM);
		bytes.write(0);
		writeUnsignedShort(bytes, headerLength);
		return bytes.toByteArray();
	}

	private static byte[] buildEnvelope(byte[] fixedHeader, byte[] variableHeader, byte[] ciphertext) {
		byte[] envelope = concat(fixedHeader, variableHeader, ciphertext);
		if (envelope.length > MAX_ENVELOPE_BYTES)
			throw new IllegalArgumentException("Private chat attachment exceeds the 1 MiB envelope limit");
		return envelope;
	}

	private static byte[] x25519(X25519PrivateKeyParameters privateKey, byte[] ed25519PublicKey) {
		byte[] x25519PublicKey = Ed25519Extras.toX25519PublicKey(ed25519PublicKey);
		if (x25519PublicKey == null)
			throw new IllegalArgumentException("Recipient public key cannot be converted to X25519");
		byte[] sharedSecret = new byte[Crypto.SHARED_SECRET_LENGTH];
		privateKey.generateSecret(new X25519PublicKeyParameters(x25519PublicKey, 0), sharedSecret, 0);
		return sharedSecret;
	}

	private static byte[] hkdfSha256(byte[] inputKeyMaterial, byte[] salt, byte[] info)
			throws GeneralSecurityException {
		byte[] pseudorandomKey = hmac(salt, inputKeyMaterial);
		return Arrays.copyOf(hmac(pseudorandomKey, concat(info, new byte[] { 1 })),
				Transformer.AES256_LENGTH);
	}

	private static byte[] doAesGcm(int mode, byte[] key, byte[] nonce, byte[] associatedData, byte[] input)
			throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance(AES_GCM);
		cipher.init(mode, new SecretKeySpec(key, AES), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
		cipher.updateAAD(associatedData);
		return cipher.doFinal(input);
	}

	private static byte[] hmac(byte[] key, byte[] input) throws GeneralSecurityException {
		Mac mac = Mac.getInstance(HMAC_SHA256);
		mac.init(new SecretKeySpec(key, HMAC_SHA256));
		return mac.doFinal(input);
	}

	private static void validateGroupContext(byte[] groupKey, int groupId, byte[] epochId, byte[] keyId) {
		validateLength(groupKey, Transformer.AES256_LENGTH, "group key");
		if (groupId <= 0)
			throw new IllegalArgumentException("group id must be positive");
		validateLength(epochId, EncryptedDataEnvelope.EPOCH_ID_LENGTH, "epoch id");
		validateLength(keyId, EncryptedDataEnvelope.GROUP_KEY_ID_LENGTH, "group key id");
		if (!Arrays.equals(PrivateGroupChatCrypto.computeKeyId(groupId, epochId, groupKey), keyId))
			throw new IllegalArgumentException("group key does not match key id");
	}

	private static void validatePayload(PrivateChatAttachmentPayload payload) {
		if (payload == null)
			throw new IllegalArgumentException("Private chat attachment payload is missing");
	}

	private static void validateEd25519PublicKey(byte[] publicKey, String fieldName) {
		validateLength(publicKey, Transformer.PUBLIC_KEY_LENGTH, fieldName);
		if (Ed25519Extras.toX25519PublicKey(publicKey) == null)
			throw new IllegalArgumentException(fieldName + " is invalid");
	}

	private static void validateLength(byte[] bytes, int expectedLength, String fieldName) {
		if (bytes == null || bytes.length != expectedLength)
			throw new IllegalArgumentException(fieldName + " has invalid length");
	}

	private static byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		SECURE_RANDOM.nextBytes(bytes);
		return bytes;
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

	private static byte[] intBytes(int value) {
		return new byte[] {
				(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
		};
	}

	private static int readInt(byte[] bytes, int offset) {
		return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
				| ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
	}

	private static byte[] concat(byte[]... values) {
		int length = 0;
		for (byte[] value : values)
			length += value.length;
		byte[] output = new byte[length];
		int offset = 0;
		for (byte[] value : values) {
			System.arraycopy(value, 0, output, offset, value.length);
			offset += value.length;
		}
		return output;
	}

	private static int compareUnsigned(byte[] left, byte[] right) {
		for (int index = 0; index < left.length; ++index) {
			int comparison = Integer.compare(left[index] & 0xff, right[index] & 0xff);
			if (comparison != 0)
				return comparison;
		}
		return 0;
	}

	private static void writeUnsignedShort(ByteArrayOutputStream bytes, int value) {
		bytes.write((value >>> 8) & 0xff);
		bytes.write(value & 0xff);
	}

	private static void writeBytes(ByteArrayOutputStream bytes, byte[] value) {
		bytes.write(value, 0, value.length);
	}

	private record RecipientMaterial(byte[] publicKey, byte[] keyId, byte[] wrapNonce) {
	}

	private record RecipientEntry(byte[] keyId, byte[] wrapNonce, byte[] wrappedKey) {
	}

	private record ParsedEnvelope(byte[] fixedHeader, byte[] variableHeader, byte[] ciphertext,
			byte[] contentNonce, byte[] ephemeralPublicKey, List<RecipientEntry> recipientEntries,
			int groupId, byte[] epochId, byte[] groupKeyId) {

		private static ParsedEnvelope recipients(byte[] fixedHeader, byte[] variableHeader,
				byte[] ciphertext, byte[] contentNonce, byte[] ephemeralPublicKey,
				List<RecipientEntry> entries) {
			return new ParsedEnvelope(fixedHeader, variableHeader, ciphertext, contentNonce,
					ephemeralPublicKey, List.copyOf(entries), 0, null, null);
		}

		private static ParsedEnvelope group(byte[] fixedHeader, byte[] variableHeader, byte[] ciphertext,
				byte[] contentNonce, int groupId, byte[] epochId, byte[] groupKeyId) {
			return new ParsedEnvelope(fixedHeader, variableHeader, ciphertext, contentNonce,
					null, List.of(), groupId, epochId, groupKeyId);
		}
	}
}
