package org.qortium.chat.crypto;

import org.qortium.crypto.Crypto;
import org.qortium.transform.Transformer;
import org.qortium.utils.ByteArray;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PrivateGroupChatKeyAnnouncement {

	private static final byte[] SIGNING_DOMAIN = "QPGC key announcement v1".getBytes(StandardCharsets.US_ASCII);

	private PrivateGroupChatKeyAnnouncement() {
	}

	public static PrivateGroupChatEnvelope create(PrivateGroupChatMembership.MembershipEpoch epoch, byte[] groupKey,
			byte[] announcerPrivateKey) throws GeneralSecurityException {
		validateEpoch(epoch);
		validateLength(groupKey, Transformer.AES256_LENGTH, "group key");
		validateLength(announcerPrivateKey, Transformer.PRIVATE_KEY_LENGTH, "announcer private key");

		int groupId = epoch.getGroupId();
		byte[] epochId = epoch.getEpochId();
		byte[] announcerPublicKey = Crypto.toPublicKey(announcerPrivateKey);
		List<byte[]> memberPublicKeys = epoch.getMemberPublicKeys();

		if (!containsPublicKey(memberPublicKeys, announcerPublicKey))
			throw new GeneralSecurityException("Key announcer is not a current group member");

		byte[] keyId = PrivateGroupChatCrypto.computeKeyId(groupId, epochId, groupKey);
		List<PrivateGroupChatEnvelope.KeyWrapper> keyWrappers = new ArrayList<>(memberPublicKeys.size());
		for (byte[] recipientPublicKey : memberPublicKeys) {
			byte[] wrappedKey = PrivateGroupChatCrypto.wrapGroupKey(groupId, epochId, keyId, groupKey,
					announcerPrivateKey, recipientPublicKey);
			keyWrappers.add(new PrivateGroupChatEnvelope.KeyWrapper(recipientPublicKey, wrappedKey));
		}

		keyWrappers = sortedWrappers(keyWrappers);
		byte[] signingBytes = buildSigningBytes(groupId, epochId, keyId, announcerPublicKey, keyWrappers);
		byte[] signature = Crypto.sign(announcerPrivateKey, signingBytes);

		return PrivateGroupChatEnvelope.keyAnnouncement(groupId, epochId, keyId, announcerPublicKey, keyWrappers, signature);
	}

	public static boolean isValid(PrivateGroupChatMembership.MembershipEpoch epoch, PrivateGroupChatEnvelope envelope) {
		try {
			validate(epoch, envelope);
			return true;
		} catch (GeneralSecurityException | IllegalArgumentException | IllegalStateException e) {
			return false;
		}
	}

	public static boolean isHistoricallyValid(PrivateGroupChatEnvelope envelope) {
		try {
			validateHistorical(envelope);
			return true;
		} catch (GeneralSecurityException | IllegalArgumentException | IllegalStateException e) {
			return false;
		}
	}

	public static byte[] unwrapForRecipient(PrivateGroupChatMembership.MembershipEpoch epoch,
			PrivateGroupChatEnvelope envelope, byte[] recipientPrivateKey) throws GeneralSecurityException {
		validateLength(recipientPrivateKey, Transformer.PRIVATE_KEY_LENGTH, "recipient private key");
		validate(epoch, envelope);

		byte[] recipientPublicKey = Crypto.toPublicKey(recipientPrivateKey);
		PrivateGroupChatEnvelope.KeyWrapper keyWrapper = findWrapper(envelope.getKeyWrappers(), recipientPublicKey);
		if (keyWrapper == null)
			throw new GeneralSecurityException("Key announcement does not include recipient wrapper");

		return PrivateGroupChatCrypto.unwrapGroupKey(envelope.getGroupId(), envelope.getEpochId(), envelope.getKeyId(),
				keyWrapper.getWrappedKey(), recipientPrivateKey, envelope.getCreatorPublicKey());
	}

	public static byte[] unwrapHistoricalForRecipient(PrivateGroupChatEnvelope envelope, byte[] recipientPrivateKey)
			throws GeneralSecurityException {
		validateLength(recipientPrivateKey, Transformer.PRIVATE_KEY_LENGTH, "recipient private key");
		validateHistorical(envelope);

		byte[] recipientPublicKey = Crypto.toPublicKey(recipientPrivateKey);
		PrivateGroupChatEnvelope.KeyWrapper keyWrapper = findWrapper(envelope.getKeyWrappers(), recipientPublicKey);
		if (keyWrapper == null)
			throw new GeneralSecurityException("Key announcement does not include recipient wrapper");

		return PrivateGroupChatCrypto.unwrapGroupKey(envelope.getGroupId(), envelope.getEpochId(), envelope.getKeyId(),
				keyWrapper.getWrappedKey(), recipientPrivateKey, envelope.getCreatorPublicKey());
	}

	private static void validate(PrivateGroupChatMembership.MembershipEpoch epoch, PrivateGroupChatEnvelope envelope)
			throws GeneralSecurityException {
		validateEpoch(epoch);
		if (envelope == null)
			throw new GeneralSecurityException("Key announcement envelope is missing");

		if (envelope.getType() != PrivateGroupChatEnvelope.Type.KEY_ANNOUNCEMENT)
			throw new GeneralSecurityException("Envelope is not a key announcement");

		if (envelope.getGroupId() != epoch.getGroupId())
			throw new GeneralSecurityException("Key announcement group id does not match current epoch");

		if (!Arrays.equals(envelope.getEpochId(), epoch.getEpochId()))
			throw new GeneralSecurityException("Key announcement epoch id does not match current epoch");

		byte[] keyId = envelope.getKeyId();
		byte[] creatorPublicKey = envelope.getCreatorPublicKey();
		byte[] signature = envelope.getSignature();
		validateLength(keyId, PrivateGroupChatEnvelope.KEY_ID_LENGTH, "key id");
		validateLength(creatorPublicKey, PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, "creator public key");
		validateLength(signature, PrivateGroupChatEnvelope.SIGNATURE_LENGTH, "signature");

		List<byte[]> memberPublicKeys = epoch.getMemberPublicKeys();
		if (!containsPublicKey(memberPublicKeys, creatorPublicKey))
			throw new GeneralSecurityException("Key announcement creator is not a current group member");

		List<PrivateGroupChatEnvelope.KeyWrapper> sortedWrappers = validateAndSortWrappers(memberPublicKeys,
				envelope.getKeyWrappers());
		byte[] signingBytes = buildSigningBytes(envelope.getGroupId(), envelope.getEpochId(), keyId,
				creatorPublicKey, sortedWrappers);

		if (!Crypto.verify(creatorPublicKey, signature, signingBytes))
			throw new GeneralSecurityException("Key announcement signature is invalid");
	}

	private static void validateHistorical(PrivateGroupChatEnvelope envelope) throws GeneralSecurityException {
		if (envelope == null)
			throw new GeneralSecurityException("Key announcement envelope is missing");

		if (envelope.getType() != PrivateGroupChatEnvelope.Type.KEY_ANNOUNCEMENT)
			throw new GeneralSecurityException("Envelope is not a key announcement");

		byte[] keyId = envelope.getKeyId();
		byte[] epochId = envelope.getEpochId();
		byte[] creatorPublicKey = envelope.getCreatorPublicKey();
		byte[] signature = envelope.getSignature();
		validateLength(keyId, PrivateGroupChatEnvelope.KEY_ID_LENGTH, "key id");
		validateLength(epochId, PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, "epoch id");
		validateLength(creatorPublicKey, PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, "creator public key");
		validateLength(signature, PrivateGroupChatEnvelope.SIGNATURE_LENGTH, "signature");

		List<PrivateGroupChatEnvelope.KeyWrapper> sortedWrappers = validateAndSortWrappers(envelope.getKeyWrappers());
		byte[] signingBytes = buildSigningBytes(envelope.getGroupId(), epochId, keyId,
				creatorPublicKey, sortedWrappers);

		if (!Crypto.verify(creatorPublicKey, signature, signingBytes))
			throw new GeneralSecurityException("Key announcement signature is invalid");
	}

	private static List<PrivateGroupChatEnvelope.KeyWrapper> validateAndSortWrappers(List<byte[]> memberPublicKeys,
			List<PrivateGroupChatEnvelope.KeyWrapper> keyWrappers) throws GeneralSecurityException {
		if (keyWrappers == null || keyWrappers.isEmpty())
			throw new GeneralSecurityException("Key announcement has no wrappers");

		Set<ByteArray> memberSet = new HashSet<>(memberPublicKeys.size());
		for (byte[] memberPublicKey : memberPublicKeys)
			memberSet.add(ByteArray.copyOf(memberPublicKey));

		Set<ByteArray> recipientSet = new HashSet<>(keyWrappers.size());
		for (PrivateGroupChatEnvelope.KeyWrapper keyWrapper : keyWrappers) {
			if (keyWrapper == null)
				throw new GeneralSecurityException("Key announcement wrapper is missing");

			byte[] recipientPublicKey = keyWrapper.getRecipientPublicKey();
			validateLength(recipientPublicKey, PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, "recipient public key");
			if (!memberSet.contains(ByteArray.wrap(recipientPublicKey)))
				throw new GeneralSecurityException("Key announcement includes a non-member recipient");

			if (!recipientSet.add(ByteArray.copyOf(recipientPublicKey)))
				throw new GeneralSecurityException("Key announcement includes duplicate recipient wrapper");

			byte[] wrappedKey = keyWrapper.getWrappedKey();
			if (wrappedKey == null || wrappedKey.length <= PrivateGroupChatEnvelope.NONCE_LENGTH)
				throw new GeneralSecurityException("Key announcement wrapper is too short");
		}

		if (recipientSet.size() != memberSet.size())
			throw new GeneralSecurityException("Key announcement does not cover every current group member");

		return sortedWrappers(keyWrappers);
	}

	private static List<PrivateGroupChatEnvelope.KeyWrapper> validateAndSortWrappers(
			List<PrivateGroupChatEnvelope.KeyWrapper> keyWrappers) throws GeneralSecurityException {
		if (keyWrappers == null || keyWrappers.isEmpty())
			throw new GeneralSecurityException("Key announcement has no wrappers");

		Set<ByteArray> recipientSet = new HashSet<>(keyWrappers.size());
		for (PrivateGroupChatEnvelope.KeyWrapper keyWrapper : keyWrappers) {
			if (keyWrapper == null)
				throw new GeneralSecurityException("Key announcement wrapper is missing");

			byte[] recipientPublicKey = keyWrapper.getRecipientPublicKey();
			validateLength(recipientPublicKey, PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, "recipient public key");

			if (!recipientSet.add(ByteArray.copyOf(recipientPublicKey)))
				throw new GeneralSecurityException("Key announcement includes duplicate recipient wrapper");

			byte[] wrappedKey = keyWrapper.getWrappedKey();
			if (wrappedKey == null || wrappedKey.length <= PrivateGroupChatEnvelope.NONCE_LENGTH)
				throw new GeneralSecurityException("Key announcement wrapper is too short");
		}

		return sortedWrappers(keyWrappers);
	}

	private static byte[] buildSigningBytes(int groupId, byte[] epochId, byte[] keyId, byte[] creatorPublicKey,
			List<PrivateGroupChatEnvelope.KeyWrapper> keyWrappers) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		writeBytes(bytes, SIGNING_DOMAIN);
		writeInt(bytes, groupId);
		writeBytes(bytes, epochId);
		writeBytes(bytes, keyId);
		writeBytes(bytes, creatorPublicKey);
		writeInt(bytes, keyWrappers.size());
		for (PrivateGroupChatEnvelope.KeyWrapper keyWrapper : keyWrappers) {
			writeBytes(bytes, keyWrapper.getRecipientPublicKey());
			byte[] wrappedKey = keyWrapper.getWrappedKey();
			writeInt(bytes, wrappedKey.length);
			writeBytes(bytes, wrappedKey);
		}

		return bytes.toByteArray();
	}

	private static List<PrivateGroupChatEnvelope.KeyWrapper> sortedWrappers(List<PrivateGroupChatEnvelope.KeyWrapper> keyWrappers) {
		List<PrivateGroupChatEnvelope.KeyWrapper> sortedWrappers = new ArrayList<>(keyWrappers);
		sortedWrappers.sort((left, right) -> compareUnsigned(left.getRecipientPublicKey(), right.getRecipientPublicKey()));
		return sortedWrappers;
	}

	private static PrivateGroupChatEnvelope.KeyWrapper findWrapper(List<PrivateGroupChatEnvelope.KeyWrapper> keyWrappers,
			byte[] recipientPublicKey) {
		for (PrivateGroupChatEnvelope.KeyWrapper keyWrapper : keyWrappers)
			if (Arrays.equals(keyWrapper.getRecipientPublicKey(), recipientPublicKey))
				return keyWrapper;

		return null;
	}

	private static boolean containsPublicKey(List<byte[]> publicKeys, byte[] publicKey) {
		for (byte[] candidate : publicKeys)
			if (Arrays.equals(candidate, publicKey))
				return true;

		return false;
	}

	private static void validateEpoch(PrivateGroupChatMembership.MembershipEpoch epoch) {
		if (epoch == null)
			throw new IllegalArgumentException("membership epoch is missing");

		if (epoch.getMemberPublicKeys().isEmpty())
			throw new IllegalArgumentException("membership epoch has no members");
	}

	private static void validateLength(byte[] bytes, int expectedLength, String fieldName) {
		if (bytes == null)
			throw new IllegalArgumentException(fieldName + " is missing");

		if (bytes.length != expectedLength)
			throw new IllegalArgumentException(fieldName + " has invalid length");
	}

	private static int compareUnsigned(byte[] left, byte[] right) {
		for (int i = 0; i < Math.min(left.length, right.length); ++i) {
			int comparison = Integer.compare(left[i] & 0xff, right[i] & 0xff);
			if (comparison != 0)
				return comparison;
		}

		return Integer.compare(left.length, right.length);
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
