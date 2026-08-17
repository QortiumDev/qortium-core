package org.qortium.chat;

import org.qortium.transform.Transformer;
import org.qortium.utils.Base58;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Opaque stable cursor for retained private-group control envelopes. */
public final class PrivateGroupChatControlCursor {

	private static final int VERSION = 1;
	private static final int SERIALIZED_LENGTH = 1 + Long.BYTES + Transformer.SIGNATURE_LENGTH;
	private static final long MIN_CHAT_TIMESTAMP = 1_500_000_000_000L;

	private final long timestamp;
	private final byte[] signature;

	public PrivateGroupChatControlCursor(long timestamp, byte[] signature) {
		if (timestamp < MIN_CHAT_TIMESTAMP)
			throw new IllegalArgumentException("private group control cursor timestamp is invalid");
		if (signature == null || signature.length != Transformer.SIGNATURE_LENGTH)
			throw new IllegalArgumentException("private group control cursor signature is invalid");

		this.timestamp = timestamp;
		this.signature = Arrays.copyOf(signature, signature.length);
	}

	public static PrivateGroupChatControlCursor decode(String encoded) {
		if (encoded == null || encoded.isBlank())
			return null;

		byte[] bytes;
		try {
			bytes = Base58.decode(encoded);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("private group control cursor is not valid Base58", e);
		}
		if (bytes.length != SERIALIZED_LENGTH)
			throw new IllegalArgumentException("private group control cursor length is invalid");

		try {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			int version = buffer.get() & 0xff;
			if (version != VERSION)
				throw new IllegalArgumentException("private group control cursor version is unsupported");
			long timestamp = buffer.getLong();
			byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
			buffer.get(signature);
			return new PrivateGroupChatControlCursor(timestamp, signature);
		} catch (BufferUnderflowException e) {
			throw new IllegalArgumentException("private group control cursor is truncated", e);
		}
	}

	public String encode() {
		ByteBuffer buffer = ByteBuffer.allocate(SERIALIZED_LENGTH);
		buffer.put((byte) VERSION);
		buffer.putLong(this.timestamp);
		buffer.put(this.signature);
		return Base58.encode(buffer.array());
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public byte[] getSignature() {
		return Arrays.copyOf(this.signature, this.signature.length);
	}
}
