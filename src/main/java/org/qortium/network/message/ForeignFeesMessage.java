package org.qortium.network.message;

import org.qortium.data.crosschain.ForeignFeeDecodedData;
import org.qortium.utils.ForeignFeesMessageUtils;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * For sending online accounts info to remote peer.
 *
 * Carries current foreign-chain fee and mempow nonce information.
 */
public class ForeignFeesMessage extends Message {

	public static final long MIN_PEER_VERSION = 0x300060000L; // 3.6.0

	private List<ForeignFeeDecodedData> foreignFees;

	public ForeignFeesMessage(List<ForeignFeeDecodedData> foreignFeeDecodedData) {
		super(MessageType.FOREIGN_FEES);

		this.dataBytes = ForeignFeesMessageUtils.fromDataToSendBytes(foreignFeeDecodedData);
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private ForeignFeesMessage(int id, List<ForeignFeeDecodedData> foreignFees) {
		super(id, MessageType.FOREIGN_FEES);

		this.foreignFees = foreignFees;
	}

	public List<ForeignFeeDecodedData> getForeignFees() {
		return this.foreignFees;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
		List<ForeignFeeDecodedData> foreignFeeDecodedData = ForeignFeesMessageUtils.fromSendBytesToData(bytes);

		return new ForeignFeesMessage(id, foreignFeeDecodedData);
	}

}
