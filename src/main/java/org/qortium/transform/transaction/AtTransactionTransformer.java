package org.qortium.transform.transaction;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortium.account.NullAccount;
import org.qortium.data.transaction.ATTransactionData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.transaction.AtTransaction;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class AtTransactionTransformer extends TransactionTransformer {

	protected static final TransactionLayout layout = null;

	// Property lengths

	private static final int MESSAGE_SIZE_LENGTH = INT_LENGTH;
	private static final int TYPE_LENGTH = INT_LENGTH;


	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		String atAddress = Serialization.deserializeAddress(byteBuffer);

		String recipient = Serialization.deserializeAddress(byteBuffer);

		int type = byteBuffer.getInt();
		boolean isMessageType = (type == 1);

		int messageLength = 0;
		byte[] message = null;
		long assetId = 0L;
		long amount = 0L;

		if (isMessageType) {
			messageLength = byteBuffer.getInt();

			if (messageLength < 0)
				throw new TransformationException("negative message length " + messageLength);

			if (messageLength > AtTransaction.MAX_DATA_SIZE)
				throw new TransformationException("excessive message length " + messageLength);

			if (messageLength > byteBuffer.remaining())
				throw new TransformationException("message length exceeds remaining bytes " + messageLength);

			message = new byte[messageLength];
			byteBuffer.get(message);
		}
		else {
			assetId = byteBuffer.getLong();

			amount = byteBuffer.getLong();
		}

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, NullAccount.PUBLIC_KEY, fee, signature);

		if (isMessageType) {
			// MESSAGE-type
			return new ATTransactionData(baseTransactionData, atAddress, recipient, message);
		}
		else {
			// PAYMENT-type
			return new ATTransactionData(baseTransactionData, atAddress, recipient, amount, assetId);
		}

	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		ATTransactionData atTransactionData = (ATTransactionData) transactionData;

		final int baseLength = TYPE_LENGTH + TIMESTAMP_LENGTH + ADDRESS_LENGTH + ADDRESS_LENGTH +
				FEE_LENGTH + SIGNATURE_LENGTH;

		int typeSpecificLength = 0;

		byte[] message = atTransactionData.getMessage();
		boolean isMessageType = (message != null);

		// MESSAGE-type and PAYMENT-type transactions will have differing lengths
		if (isMessageType) {
			typeSpecificLength = MESSAGE_SIZE_LENGTH + message.length;
		}
		else {
			typeSpecificLength = ASSET_ID_LENGTH + AMOUNT_LENGTH;
		}

		return baseLength + typeSpecificLength + TYPE_LENGTH;
	}

	// Used for generating fake transaction signatures
	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			ATTransactionData atTransactionData = (ATTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(Ints.toByteArray(atTransactionData.getType().value));
			bytes.write(Longs.toByteArray(atTransactionData.getTimestamp()));

			Serialization.serializeAddress(bytes, atTransactionData.getATAddress());

			Serialization.serializeAddress(bytes, atTransactionData.getRecipient());

			byte[] message = atTransactionData.getMessage();

			boolean isMessageType = (message != null);
			int type = isMessageType ? 1 : 0;

			bytes.write(Ints.toByteArray(type));

			if (isMessageType) {
				// MESSAGE-type
				bytes.write(Ints.toByteArray(message.length));
				bytes.write(message);
			} else {
				// PAYMENT-type
				bytes.write(Longs.toByteArray(atTransactionData.getAssetId()));
				bytes.write(Longs.toByteArray(atTransactionData.getAmount()));
			}

			bytes.write(Longs.toByteArray(atTransactionData.getFee()));

			if (atTransactionData.getSignature() != null)
				bytes.write(atTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
