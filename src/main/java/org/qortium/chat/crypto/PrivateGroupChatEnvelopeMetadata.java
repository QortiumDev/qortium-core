package org.qortium.chat.crypto;

import org.qortium.transform.TransformationException;

/**
 * Indexed routing metadata extracted from a structurally valid QPGC envelope.
 *
 * <p>The metadata is derived from the retained CHAT payload and is never a
 * replacement for parsing and validating the full envelope before using it.</p>
 */
public final class PrivateGroupChatEnvelopeMetadata {

	private final PrivateGroupChatEnvelope.Type type;
	private final byte[] epochId;
	private final byte[] keyId;

	private PrivateGroupChatEnvelopeMetadata(PrivateGroupChatEnvelope.Type type, byte[] epochId, byte[] keyId) {
		this.type = type;
		this.epochId = epochId.clone();
		this.keyId = keyId == null ? null : keyId.clone();
	}

	public static PrivateGroupChatEnvelopeMetadata fromBytes(byte[] data, int expectedGroupId)
			throws TransformationException {
		PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.fromBytes(data);
		if (envelope.getGroupId() != expectedGroupId)
			throw new TransformationException("Private group chat envelope group does not match CHAT group");

		return new PrivateGroupChatEnvelopeMetadata(envelope.getType(), envelope.getEpochId(), envelope.getKeyId());
	}

	public PrivateGroupChatEnvelope.Type getType() {
		return this.type;
	}

	public byte[] getEpochId() {
		return this.epochId.clone();
	}

	public byte[] getKeyId() {
		return this.keyId == null ? null : this.keyId.clone();
	}
}
