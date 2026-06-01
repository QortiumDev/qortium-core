package org.qortium.chat.crypto;

import org.qortium.transaction.ChatTransaction;
import org.qortium.transform.TransformationException;
import org.qortium.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

public class DirectPrivateChatEnvelope {

	public static final int MAGIC = 0x51444d31; // QDM1
	public static final int NONCE_LENGTH = 12;
	public static final int PUBLIC_KEY_LENGTH = Transformer.PUBLIC_KEY_LENGTH;

	private static final int MAX_VARIABLE_LENGTH = ChatTransaction.MAX_DATA_SIZE;

	private final byte[] senderPublicKey;
	private final byte[] recipientPublicKey;
	private final byte[] nonce;
	private final byte[] ciphertext;

	private DirectPrivateChatEnvelope(byte[] senderPublicKey, byte[] recipientPublicKey, byte[] nonce,
			byte[] ciphertext) {
		this.senderPublicKey = copyExact(senderPublicKey, PUBLIC_KEY_LENGTH, "sender public key");
		this.recipientPublicKey = copyExact(recipientPublicKey, PUBLIC_KEY_LENGTH, "recipient public key");
		this.nonce = copyExact(nonce, NONCE_LENGTH, "nonce");
		this.ciphertext = copyVariable(ciphertext, false, "ciphertext");
	}

	public static DirectPrivateChatEnvelope message(byte[] senderPublicKey, byte[] recipientPublicKey,
			byte[] nonce, byte[] ciphertext) {
		return new DirectPrivateChatEnvelope(senderPublicKey, recipientPublicKey, nonce, ciphertext);
	}

	public static DirectPrivateChatEnvelope fromBytes(byte[] bytes) throws TransformationException {
		if (bytes == null)
			throw new TransformationException("Direct private chat envelope data is missing");

		try {
			ByteBuffer byteBuffer = ByteBuffer.wrap(bytes).asReadOnlyBuffer();

			if (byteBuffer.remaining() < Integer.BYTES + PUBLIC_KEY_LENGTH + PUBLIC_KEY_LENGTH
					+ NONCE_LENGTH + Integer.BYTES + 1)
				throw new TransformationException("Direct private chat envelope data is too short");

			int magic = byteBuffer.getInt();
			if (magic != MAGIC)
				throw new TransformationException("Direct private chat envelope magic is invalid");

			byte[] senderPublicKey = readFixed(byteBuffer, PUBLIC_KEY_LENGTH, "sender public key");
			byte[] recipientPublicKey = readFixed(byteBuffer, PUBLIC_KEY_LENGTH, "recipient public key");
			byte[] nonce = readFixed(byteBuffer, NONCE_LENGTH, "nonce");
			byte[] ciphertext = readVariable(byteBuffer, false, "ciphertext");

			if (byteBuffer.hasRemaining())
				throw new TransformationException("Direct private chat envelope has trailing data");

			return message(senderPublicKey, recipientPublicKey, nonce, ciphertext);
		} catch (BufferUnderflowException e) {
			throw new TransformationException("Direct private chat envelope data is truncated", e);
		} catch (IllegalArgumentException e) {
			throw new TransformationException(e);
		}
	}

	public byte[] toBytes() {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		writeInt(bytes, MAGIC);
		writeBytes(bytes, this.senderPublicKey);
		writeBytes(bytes, this.recipientPublicKey);
		writeBytes(bytes, this.nonce);
		writeVariable(bytes, this.ciphertext);
		return bytes.toByteArray();
	}

	public byte[] getSenderPublicKey() {
		return copy(this.senderPublicKey);
	}

	public byte[] getRecipientPublicKey() {
		return copy(this.recipientPublicKey);
	}

	public byte[] getNonce() {
		return copy(this.nonce);
	}

	public byte[] getCiphertext() {
		return copy(this.ciphertext);
	}

	private static byte[] readFixed(ByteBuffer byteBuffer, int length, String fieldName) throws TransformationException {
		if (byteBuffer.remaining() < length)
			throw new TransformationException("Direct private chat envelope is missing " + fieldName);

		byte[] bytes = new byte[length];
		byteBuffer.get(bytes);
		return bytes;
	}

	private static byte[] readVariable(ByteBuffer byteBuffer, boolean allowEmpty, String fieldName) throws TransformationException {
		if (byteBuffer.remaining() < Integer.BYTES)
			throw new TransformationException("Direct private chat envelope is missing " + fieldName + " length");

		int length = byteBuffer.getInt();
		if (length < 0 || length > MAX_VARIABLE_LENGTH)
			throw new TransformationException("Direct private chat envelope " + fieldName + " length is invalid");

		if (!allowEmpty && length == 0)
			throw new TransformationException("Direct private chat envelope " + fieldName + " is empty");

		if (byteBuffer.remaining() < length)
			throw new TransformationException("Direct private chat envelope " + fieldName + " is truncated");

		byte[] bytes = new byte[length];
		byteBuffer.get(bytes);
		return bytes;
	}

	private static byte[] copyExact(byte[] bytes, int expectedLength, String fieldName) {
		if (bytes == null)
			throw new IllegalArgumentException(fieldName + " is missing");

		if (bytes.length != expectedLength)
			throw new IllegalArgumentException(fieldName + " has invalid length");

		return copy(bytes);
	}

	private static byte[] copyVariable(byte[] bytes, boolean allowEmpty, String fieldName) {
		if (bytes == null)
			throw new IllegalArgumentException(fieldName + " is missing");

		if (!allowEmpty && bytes.length == 0)
			throw new IllegalArgumentException(fieldName + " is empty");

		if (bytes.length > MAX_VARIABLE_LENGTH)
			throw new IllegalArgumentException(fieldName + " is too long");

		return copy(bytes);
	}

	private static void writeInt(ByteArrayOutputStream bytes, int value) {
		bytes.write((value >>> 24) & 0xff);
		bytes.write((value >>> 16) & 0xff);
		bytes.write((value >>> 8) & 0xff);
		bytes.write(value & 0xff);
	}

	private static void writeVariable(ByteArrayOutputStream bytes, byte[] value) {
		writeInt(bytes, value.length);
		writeBytes(bytes, value);
	}

	private static void writeBytes(ByteArrayOutputStream bytes, byte[] value) {
		bytes.write(value, 0, value.length);
	}

	private static byte[] copy(byte[] bytes) {
		return bytes == null ? null : bytes.clone();
	}

}
