package org.qortium.chat.crypto;

import org.qortium.transaction.ChatTransaction;
import org.qortium.transform.TransformationException;
import org.qortium.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PrivateGroupChatEnvelope {

	public static final int MAGIC = 0x51504743; // QPGC
	public static final int VERSION = 1;
	public static final int EPOCH_ID_LENGTH = Transformer.SHA256_LENGTH;
	public static final int KEY_ID_LENGTH = Transformer.SHA256_LENGTH;
	public static final int NONCE_LENGTH = 12;
	public static final int PUBLIC_KEY_LENGTH = Transformer.PUBLIC_KEY_LENGTH;
	public static final int SIGNATURE_LENGTH = Transformer.SIGNATURE_LENGTH;

	private static final int MAX_VARIABLE_LENGTH = ChatTransaction.MAX_DATA_SIZE;

	public enum Type {
		MESSAGE(1),
		KEY_ANNOUNCEMENT(2),
		KEY_REQUEST(3),
		ROTATION_REQUEST(4);

		private final int value;

		Type(int value) {
			this.value = value;
		}

		private int getValue() {
			return this.value;
		}

		private static Type fromValue(int value) throws TransformationException {
			for (Type type : values())
				if (type.value == value)
					return type;

			throw new TransformationException("Unknown private group chat envelope type");
		}
	}

	public static class KeyWrapper {
		private final byte[] recipientPublicKey;
		private final byte[] wrappedKey;

		public KeyWrapper(byte[] recipientPublicKey, byte[] wrappedKey) {
			this.recipientPublicKey = copyExact(recipientPublicKey, PUBLIC_KEY_LENGTH, "recipient public key");
			this.wrappedKey = copyVariable(wrappedKey, false, "wrapped key");
		}

		public byte[] getRecipientPublicKey() {
			return copy(this.recipientPublicKey);
		}

		public byte[] getWrappedKey() {
			return copy(this.wrappedKey);
		}
	}

	private final Type type;
	private final int groupId;
	private final byte[] epochId;
	private final byte[] keyId;
	private final byte[] nonce;
	private final byte[] ciphertext;
	private final byte[] creatorPublicKey;
	private final List<KeyWrapper> keyWrappers;
	private final byte[] requesterPublicKey;
	private final boolean keyRequestHasKeyId;
	private final byte[] signature;

	private PrivateGroupChatEnvelope(Type type, int groupId, byte[] epochId, byte[] keyId, byte[] nonce,
			byte[] ciphertext, byte[] creatorPublicKey, List<KeyWrapper> keyWrappers, byte[] requesterPublicKey,
			boolean keyRequestHasKeyId, byte[] signature) {
		this.type = type;
		this.groupId = groupId;
		this.epochId = copyExact(epochId, EPOCH_ID_LENGTH, "epoch id");
		this.keyId = keyId == null ? null : copyExact(keyId, KEY_ID_LENGTH, "key id");
		this.nonce = nonce == null ? null : copyExact(nonce, NONCE_LENGTH, "nonce");
		this.ciphertext = ciphertext == null ? null : copyVariable(ciphertext, false, "ciphertext");
		this.creatorPublicKey = creatorPublicKey == null ? null : copyExact(creatorPublicKey, PUBLIC_KEY_LENGTH, "creator public key");
		this.keyWrappers = keyWrappers == null ? Collections.emptyList() : copyWrappers(keyWrappers);
		this.requesterPublicKey = requesterPublicKey == null ? null : copyExact(requesterPublicKey, PUBLIC_KEY_LENGTH, "requester public key");
		this.keyRequestHasKeyId = keyRequestHasKeyId;
		this.signature = signature == null ? null : copyExact(signature, SIGNATURE_LENGTH, "signature");
	}

	public static PrivateGroupChatEnvelope message(int groupId, byte[] epochId, byte[] keyId, byte[] nonce, byte[] ciphertext) {
		return new PrivateGroupChatEnvelope(Type.MESSAGE, groupId, epochId, keyId, nonce, ciphertext,
				null, null, null, false, null);
	}

	public static PrivateGroupChatEnvelope keyAnnouncement(int groupId, byte[] epochId, byte[] keyId,
			byte[] creatorPublicKey, List<KeyWrapper> keyWrappers, byte[] signature) {
		if (keyWrappers == null || keyWrappers.isEmpty())
			throw new IllegalArgumentException("key announcement must include at least one wrapper");

		return new PrivateGroupChatEnvelope(Type.KEY_ANNOUNCEMENT, groupId, epochId, keyId, null, null,
				creatorPublicKey, keyWrappers, null, false, signature);
	}

	public static PrivateGroupChatEnvelope keyRequest(int groupId, byte[] epochId, byte[] requesterPublicKey,
			byte[] keyId, byte[] signature) {
		return new PrivateGroupChatEnvelope(Type.KEY_REQUEST, groupId, epochId, keyId, null, null,
				null, null, requesterPublicKey, keyId != null, signature);
	}

	public static PrivateGroupChatEnvelope rotationRequest(int groupId, byte[] epochId, byte[] requesterPublicKey,
			byte[] signature) {
		return new PrivateGroupChatEnvelope(Type.ROTATION_REQUEST, groupId, epochId, null, null, null,
				null, null, requesterPublicKey, false, signature);
	}

	public static PrivateGroupChatEnvelope fromBytes(byte[] bytes) throws TransformationException {
		if (bytes == null)
			throw new TransformationException("Private group chat envelope data is missing");

		try {
			ByteBuffer byteBuffer = ByteBuffer.wrap(bytes).asReadOnlyBuffer();

			if (byteBuffer.remaining() < Integer.BYTES + 2)
				throw new TransformationException("Private group chat envelope data is too short");

			int magic = byteBuffer.getInt();
			if (magic != MAGIC)
				throw new TransformationException("Private group chat envelope magic is invalid");

			int version = byteBuffer.get() & 0xff;
			if (version != VERSION)
				throw new TransformationException("Private group chat envelope version is unsupported");

			Type type = Type.fromValue(byteBuffer.get() & 0xff);
			int groupId = byteBuffer.getInt();
			byte[] epochId = readFixed(byteBuffer, EPOCH_ID_LENGTH, "epoch id");

			PrivateGroupChatEnvelope envelope;
			switch (type) {
				case MESSAGE:
					envelope = parseMessage(byteBuffer, groupId, epochId);
					break;

				case KEY_ANNOUNCEMENT:
					envelope = parseKeyAnnouncement(byteBuffer, groupId, epochId);
					break;

				case KEY_REQUEST:
					envelope = parseKeyRequest(byteBuffer, groupId, epochId);
					break;

				case ROTATION_REQUEST:
					envelope = parseRotationRequest(byteBuffer, groupId, epochId);
					break;

				default:
					throw new TransformationException("Unhandled private group chat envelope type");
			}

			if (byteBuffer.hasRemaining())
				throw new TransformationException("Private group chat envelope has trailing data");

			return envelope;
		} catch (BufferUnderflowException e) {
			throw new TransformationException("Private group chat envelope data is truncated", e);
		} catch (IllegalArgumentException e) {
			throw new TransformationException(e);
		}
	}

	private static PrivateGroupChatEnvelope parseMessage(ByteBuffer byteBuffer, int groupId, byte[] epochId) throws TransformationException {
		byte[] keyId = readFixed(byteBuffer, KEY_ID_LENGTH, "key id");
		byte[] nonce = readFixed(byteBuffer, NONCE_LENGTH, "nonce");
		byte[] ciphertext = readVariable(byteBuffer, false, "ciphertext");

		return message(groupId, epochId, keyId, nonce, ciphertext);
	}

	private static PrivateGroupChatEnvelope parseKeyAnnouncement(ByteBuffer byteBuffer, int groupId, byte[] epochId) throws TransformationException {
		byte[] keyId = readFixed(byteBuffer, KEY_ID_LENGTH, "key id");
		byte[] creatorPublicKey = readFixed(byteBuffer, PUBLIC_KEY_LENGTH, "creator public key");

		int wrapperCount = byteBuffer.getInt();
		if (wrapperCount <= 0)
			throw new TransformationException("Key announcement must include at least one wrapper");

		int minimumWrapperLength = PUBLIC_KEY_LENGTH + Integer.BYTES + 1;
		if (wrapperCount > byteBuffer.remaining() / minimumWrapperLength)
			throw new TransformationException("Key announcement wrapper count is invalid");

		List<KeyWrapper> keyWrappers = new ArrayList<>(wrapperCount);
		for (int i = 0; i < wrapperCount; ++i) {
			byte[] recipientPublicKey = readFixed(byteBuffer, PUBLIC_KEY_LENGTH, "recipient public key");
			byte[] wrappedKey = readVariable(byteBuffer, false, "wrapped key");
			keyWrappers.add(new KeyWrapper(recipientPublicKey, wrappedKey));
		}

		byte[] signature = readFixed(byteBuffer, SIGNATURE_LENGTH, "signature");
		return keyAnnouncement(groupId, epochId, keyId, creatorPublicKey, keyWrappers, signature);
	}

	private static PrivateGroupChatEnvelope parseKeyRequest(ByteBuffer byteBuffer, int groupId, byte[] epochId) throws TransformationException {
		byte[] requesterPublicKey = readFixed(byteBuffer, PUBLIC_KEY_LENGTH, "requester public key");
		int hasKeyId = byteBuffer.get() & 0xff;
		if (hasKeyId != 0 && hasKeyId != 1)
			throw new TransformationException("Key request optional key marker is invalid");

		byte[] keyId = hasKeyId == 1 ? readFixed(byteBuffer, KEY_ID_LENGTH, "key id") : null;
		byte[] signature = readFixed(byteBuffer, SIGNATURE_LENGTH, "signature");
		return keyRequest(groupId, epochId, requesterPublicKey, keyId, signature);
	}

	private static PrivateGroupChatEnvelope parseRotationRequest(ByteBuffer byteBuffer, int groupId, byte[] epochId) throws TransformationException {
		byte[] requesterPublicKey = readFixed(byteBuffer, PUBLIC_KEY_LENGTH, "requester public key");
		byte[] signature = readFixed(byteBuffer, SIGNATURE_LENGTH, "signature");
		return rotationRequest(groupId, epochId, requesterPublicKey, signature);
	}

	public byte[] toBytes() {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		writeInt(bytes, MAGIC);
		bytes.write(VERSION);
		bytes.write(this.type.getValue());
		writeInt(bytes, this.groupId);
		writeBytes(bytes, this.epochId);

		switch (this.type) {
			case MESSAGE:
				writeBytes(bytes, this.keyId);
				writeBytes(bytes, this.nonce);
				writeVariable(bytes, this.ciphertext);
				break;

			case KEY_ANNOUNCEMENT:
				writeBytes(bytes, this.keyId);
				writeBytes(bytes, this.creatorPublicKey);
				writeInt(bytes, this.keyWrappers.size());
				for (KeyWrapper keyWrapper : this.keyWrappers) {
					writeBytes(bytes, keyWrapper.recipientPublicKey);
					writeVariable(bytes, keyWrapper.wrappedKey);
				}
				writeBytes(bytes, this.signature);
				break;

			case KEY_REQUEST:
				writeBytes(bytes, this.requesterPublicKey);
				bytes.write(this.keyRequestHasKeyId ? 1 : 0);
				if (this.keyRequestHasKeyId)
					writeBytes(bytes, this.keyId);
				writeBytes(bytes, this.signature);
				break;

			case ROTATION_REQUEST:
				writeBytes(bytes, this.requesterPublicKey);
				writeBytes(bytes, this.signature);
				break;

			default:
				throw new IllegalStateException("Unhandled private group chat envelope type");
		}

		return bytes.toByteArray();
	}

	public Type getType() {
		return this.type;
	}

	public int getGroupId() {
		return this.groupId;
	}

	public byte[] getEpochId() {
		return copy(this.epochId);
	}

	public byte[] getKeyId() {
		return copy(this.keyId);
	}

	public byte[] getNonce() {
		return copy(this.nonce);
	}

	public byte[] getCiphertext() {
		return copy(this.ciphertext);
	}

	public byte[] getCreatorPublicKey() {
		return copy(this.creatorPublicKey);
	}

	public List<KeyWrapper> getKeyWrappers() {
		return this.keyWrappers;
	}

	public byte[] getRequesterPublicKey() {
		return copy(this.requesterPublicKey);
	}

	public boolean hasRequestedKeyId() {
		return this.keyRequestHasKeyId;
	}

	public byte[] getSignature() {
		return copy(this.signature);
	}

	private static byte[] readFixed(ByteBuffer byteBuffer, int length, String fieldName) throws TransformationException {
		if (byteBuffer.remaining() < length)
			throw new TransformationException("Private group chat envelope is missing " + fieldName);

		byte[] bytes = new byte[length];
		byteBuffer.get(bytes);
		return bytes;
	}

	private static byte[] readVariable(ByteBuffer byteBuffer, boolean allowEmpty, String fieldName) throws TransformationException {
		if (byteBuffer.remaining() < Integer.BYTES)
			throw new TransformationException("Private group chat envelope is missing " + fieldName + " length");

		int length = byteBuffer.getInt();
		if (length < 0)
			throw new TransformationException("Private group chat envelope " + fieldName + " length is negative");

		if (!allowEmpty && length == 0)
			throw new TransformationException("Private group chat envelope " + fieldName + " is empty");

		if (length > MAX_VARIABLE_LENGTH)
			throw new TransformationException("Private group chat envelope " + fieldName + " length is too large");

		if (length > byteBuffer.remaining())
			throw new TransformationException("Private group chat envelope " + fieldName + " length exceeds remaining data");

		byte[] bytes = new byte[length];
		byteBuffer.get(bytes);
		return bytes;
	}

	private static void writeVariable(ByteArrayOutputStream bytes, byte[] value) {
		writeInt(bytes, value.length);
		writeBytes(bytes, value);
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

	private static List<KeyWrapper> copyWrappers(List<KeyWrapper> keyWrappers) {
		if (keyWrappers.isEmpty())
			throw new IllegalArgumentException("key announcement must include at least one wrapper");

		List<KeyWrapper> copiedWrappers = new ArrayList<>(keyWrappers.size());
		for (KeyWrapper keyWrapper : keyWrappers) {
			if (keyWrapper == null)
				throw new IllegalArgumentException("key announcement wrapper is missing");

			copiedWrappers.add(new KeyWrapper(keyWrapper.recipientPublicKey, keyWrapper.wrappedKey));
		}

		return Collections.unmodifiableList(copiedWrappers);
	}

	private static byte[] copyExact(byte[] bytes, int length, String fieldName) {
		if (bytes == null)
			throw new IllegalArgumentException(fieldName + " is missing");

		if (bytes.length != length)
			throw new IllegalArgumentException(fieldName + " has invalid length");

		return copy(bytes);
	}

	private static byte[] copyVariable(byte[] bytes, boolean allowEmpty, String fieldName) {
		if (bytes == null)
			throw new IllegalArgumentException(fieldName + " is missing");

		if (!allowEmpty && bytes.length == 0)
			throw new IllegalArgumentException(fieldName + " is empty");

		if (bytes.length > MAX_VARIABLE_LENGTH)
			throw new IllegalArgumentException(fieldName + " is too large");

		return copy(bytes);
	}

	private static byte[] copy(byte[] bytes) {
		return bytes == null ? null : bytes.clone();
	}

}
