package org.qortium.chat.crypto;

import org.qortium.crypto.Crypto;
import org.qortium.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

public class PrivateGroupChatRotationRequest {

	private static final byte[] SIGNING_DOMAIN = "QPGC rotation request v1".getBytes(StandardCharsets.US_ASCII);

	private PrivateGroupChatRotationRequest() {
	}

	public static PrivateGroupChatEnvelope create(PrivateGroupChatMembership.MembershipEpoch epoch,
			byte[] requesterPrivateKey) throws GeneralSecurityException {
		validateEpoch(epoch);
		validateLength(requesterPrivateKey, Transformer.PRIVATE_KEY_LENGTH, "requester private key");

		byte[] requesterPublicKey = Crypto.toPublicKey(requesterPrivateKey);
		if (!containsPublicKey(epoch.getMemberPublicKeys(), requesterPublicKey))
			throw new GeneralSecurityException("Rotation requester is not a current group member");

		byte[] signature = Crypto.sign(requesterPrivateKey, buildSigningBytes(epoch.getGroupId(),
				epoch.getEpochId(), requesterPublicKey));

		return PrivateGroupChatEnvelope.rotationRequest(epoch.getGroupId(), epoch.getEpochId(),
				requesterPublicKey, signature);
	}

	public static boolean isValid(PrivateGroupChatMembership.MembershipEpoch epoch, PrivateGroupChatEnvelope envelope) {
		try {
			validate(epoch, envelope);
			return true;
		} catch (GeneralSecurityException | IllegalArgumentException | IllegalStateException e) {
			return false;
		}
	}

	private static void validate(PrivateGroupChatMembership.MembershipEpoch epoch, PrivateGroupChatEnvelope envelope)
			throws GeneralSecurityException {
		validateEpoch(epoch);
		if (envelope == null)
			throw new GeneralSecurityException("Rotation request envelope is missing");

		if (envelope.getType() != PrivateGroupChatEnvelope.Type.ROTATION_REQUEST)
			throw new GeneralSecurityException("Envelope is not a rotation request");

		if (envelope.getGroupId() != epoch.getGroupId())
			throw new GeneralSecurityException("Rotation request group id does not match current epoch");

		if (!Arrays.equals(envelope.getEpochId(), epoch.getEpochId()))
			throw new GeneralSecurityException("Rotation request epoch id does not match current epoch");

		byte[] requesterPublicKey = envelope.getRequesterPublicKey();
		byte[] signature = envelope.getSignature();
		validateLength(requesterPublicKey, PrivateGroupChatEnvelope.PUBLIC_KEY_LENGTH, "requester public key");
		validateLength(signature, PrivateGroupChatEnvelope.SIGNATURE_LENGTH, "signature");

		if (!containsPublicKey(epoch.getMemberPublicKeys(), requesterPublicKey))
			throw new GeneralSecurityException("Rotation request creator is not a current group member");

		byte[] signingBytes = buildSigningBytes(envelope.getGroupId(), envelope.getEpochId(), requesterPublicKey);
		if (!Crypto.verify(requesterPublicKey, signature, signingBytes))
			throw new GeneralSecurityException("Rotation request signature is invalid");
	}

	private static byte[] buildSigningBytes(int groupId, byte[] epochId, byte[] requesterPublicKey) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		writeBytes(bytes, SIGNING_DOMAIN);
		writeInt(bytes, groupId);
		writeBytes(bytes, epochId);
		writeBytes(bytes, requesterPublicKey);
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
