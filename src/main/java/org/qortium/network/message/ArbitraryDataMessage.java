package org.qortium.network.message;

import com.google.common.primitives.Ints;
import org.qortium.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

public class ArbitraryDataMessage extends Message {

	private byte[] signature;
	private byte[] data;

	public ArbitraryDataMessage(byte[] signature, byte[] data) {
		super(MessageType.ARBITRARY_DATA);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			bytes.write(signature);

			bytes.write(Ints.toByteArray(data.length));

			bytes.write(data);
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private ArbitraryDataMessage(int id, byte[] signature, byte[] data) {
		super(id, MessageType.ARBITRARY_DATA);

		this.signature = signature;
		this.data = data;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	public byte[] getData() {
		return this.data;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
		byte[] signature = new byte[Transformer.SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		int dataLength = byteBuffer.getInt();

		// A declared length is attacker-controlled and signed, so it must be rejected before it
		// reaches an allocation. remaining() is never negative, so the underflow check below cannot
		// catch a negative length on its own. This is MessageException rather than
		// BufferUnderflowException on purpose: underflow means "incomplete, wait for more bytes"
		// and Message.fromByteBuffer turns it into null, whereas a negative length is malformed and
		// the peer sending it should be disconnected.
		if (dataLength < 0)
			throw new MessageException("Negative data length in ARBITRARY_DATA message: " + dataLength);

		if (byteBuffer.remaining() < dataLength)
			throw new BufferUnderflowException();

		byte[] data = new byte[dataLength];
		byteBuffer.get(data);

		return new ArbitraryDataMessage(id, signature, data);
	}

}
