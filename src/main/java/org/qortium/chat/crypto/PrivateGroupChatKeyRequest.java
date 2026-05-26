package org.qortium.chat.crypto;

import org.qortium.crypto.Crypto;
import org.qortium.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

public class PrivateGroupChatKeyRequest {

	private static final byte[] SIGNING_DOMAIN = "QPGC key request v1".getBytes(StandardCharsets.US_ASCII);

	private PrivateGroupChatKeyRequest() {
	}

	public static PrivateGroupChatEnvelope create(PrivateGroupChatMembership.MembershipEpoch epoch,
			byte[] requesterPrivateKey, byte[] keyId) throws GeneralSecurityException {
		validateEpoch(epoch);
		validateLength(requesterPrivateKey, Transformer.PRIVATE_KEY_LENGTH, "requester private key");

		byte[] requesterPublicKey = Crypto.toPublicKey(requesterPrivateKey);
		if (!containsPublicKey(epoch.getMemberPublicKeys(), requesterPublicKey))
			throw new GeneralSecurityException("Key requester is not a current group member");

		return create(epoch.getGroupId(), epoch.getEpochId(), requesterPrivateKey, keyId);
	}

	public static PrivateGroupChatEnvelope create(int groupId, byte[] epochId, byte[] requesterPrivateKey,
			byte[] keyId) throws GeneralSecurityException {
		validateLength(epochId, PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, "epoch id");
		validateLength(requesterPrivateKey, Transformer.PRIVATE_KEY_LENGTH, "requester private key");
		if (keyId != null)
			validateLength(keyId, PrivateGroupChatEnvelope.KEY_ID_LENGTH, "key id");

		byte[] requesterPublicKey = Crypto.toPublicKey(requesterPrivateKey);
		byte[] signature = Crypto.sign(requesterPrivateKey, buildSigningBytes(groupId, epochId,
				requesterPublicKey, keyId));

		return PrivateGroupChatEnvelope.keyRequest(groupId, epochId, requesterPublicKey, keyId, signature);
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

	private static void validate(PrivateGroupChatMembership.MembershipEpoch epoch, PrivateGroupChatEnvelope envelope)
			throws GeneralSecurityException {
		validateEpoch(epoch);
		if (envelope == null)
			throw new GeneralSecurityException("Key request envelope is missing");

		if (envelope.getType() != PrivateGroupChatEnvelope.Type.KEY_REQUEST)
			throw new GeneralSecurityException("Envelope is not a key request");

		if (envelope.getGroupId() != epoch.getGroupId())
			throw new GeneralSecurityException("Key request group id does not match current epoch");

		if (!Arrays.equals(envelope.getEpochId(), epoch.getEpochId()))
			throw new GeneralSecurityException("Key request epoch id does not match current epoch");

		byte[] requesterPublicKey = envelope.getRequesterPublicKey();
		byte[] signature = envelope.getSignature();
		validateLength(requesterPublicKey, PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, "requester public key");
		validateLength(signature, PrivateGroupChatEnvelope.SIGNATURE_LENGTH, "signature");

		byte[] keyId = envelope.hasRequestedKeyId() ? envelope.getKeyId() : null;
		if (keyId != null)
			validateLength(keyId, PrivateGroupChatEnvelope.KEY_ID_LENGTH, "key id");

		if (!containsPublicKey(epoch.getMemberPublicKeys(), requesterPublicKey))
			throw new GeneralSecurityException("Key request creator is not a current group member");

		byte[] signingBytes = buildSigningBytes(envelope.getGroupId(), envelope.getEpochId(),
				requesterPublicKey, keyId);
		if (!Crypto.verify(requesterPublicKey, signature, signingBytes))
			throw new GeneralSecurityException("Key request signature is invalid");
	}

	private static void validateHistorical(PrivateGroupChatEnvelope envelope) throws GeneralSecurityException {
		if (envelope == null)
			throw new GeneralSecurityException("Key request envelope is missing");

		if (envelope.getType() != PrivateGroupChatEnvelope.Type.KEY_REQUEST)
			throw new GeneralSecurityException("Envelope is not a key request");

		byte[] epochId = envelope.getEpochId();
		byte[] requesterPublicKey = envelope.getRequesterPublicKey();
		byte[] signature = envelope.getSignature();
		validateLength(epochId, PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, "epoch id");
		validateLength(requesterPublicKey, PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, "requester public key");
		validateLength(signature, PrivateGroupChatEnvelope.SIGNATURE_LENGTH, "signature");

		byte[] keyId = envelope.hasRequestedKeyId() ? envelope.getKeyId() : null;
		if (keyId != null)
			validateLength(keyId, PrivateGroupChatEnvelope.KEY_ID_LENGTH, "key id");

		byte[] signingBytes = buildSigningBytes(envelope.getGroupId(), epochId, requesterPublicKey, keyId);
		if (!Crypto.verify(requesterPublicKey, signature, signingBytes))
			throw new GeneralSecurityException("Key request signature is invalid");
	}

	private static byte[] buildSigningBytes(int groupId, byte[] epochId, byte[] requesterPublicKey, byte[] keyId) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		writeBytes(bytes, SIGNING_DOMAIN);
		writeInt(bytes, groupId);
		writeBytes(bytes, epochId);
		writeBytes(bytes, requesterPublicKey);
		bytes.write(keyId == null ? 0 : 1);
		if (keyId != null)
			writeBytes(bytes, keyId);

		return bytes.toByteArray();
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
