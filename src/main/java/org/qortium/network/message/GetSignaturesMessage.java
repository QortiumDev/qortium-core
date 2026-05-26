package org.qortium.network.message;

import com.google.common.primitives.Ints;
import org.qortium.transform.block.BlockTransformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class GetSignaturesMessage extends Message {

	private byte[] parentSignature;
	private int numberRequested;

	public GetSignaturesMessage(byte[] parentSignature, int numberRequested) {
		super(MessageType.GET_SIGNATURES);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			bytes.write(parentSignature);

			bytes.write(Ints.toByteArray(numberRequested));
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private GetSignaturesMessage(int id, byte[] parentSignature, int numberRequested) {
		super(id, MessageType.GET_SIGNATURES);

		this.parentSignature = parentSignature;
		this.numberRequested = numberRequested;
	}

	public byte[] getParentSignature() {
		return this.parentSignature;
	}

	public int getNumberRequested() {
		return this.numberRequested;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) {
		byte[] parentSignature = new byte[BlockTransformer.BLOCK_SIGNATURE_LENGTH];
		bytes.get(parentSignature);

		int numberRequested = bytes.getInt();

		return new GetSignaturesMessage(id, parentSignature, numberRequested);
	}

}
