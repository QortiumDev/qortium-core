package org.qortium.transform.transaction;

import com.google.common.base.Utf8;
import com.google.common.primitives.Longs;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.SellNameTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.naming.Name;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class SellNameTransactionTransformer extends TransactionTransformer {

	// Property lengths
	private static final int NAME_SIZE_LENGTH = INT_LENGTH;
	private static final int RECIPIENT_PRESENT_LENGTH = BOOLEAN_LENGTH;

	private static final int EXTRAS_LENGTH = NAME_SIZE_LENGTH + AMOUNT_LENGTH + RECIPIENT_PRESENT_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.SELL_NAME.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("name owner's public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.SELL_NAME);
		layout.add("name length", TransformationType.INT);
		layout.add("name", TransformationType.STRING);
		layout.add("sale price", TransformationType.AMOUNT);
		layout.add("has direct-sale recipient?", TransformationType.BOOLEAN);
		layout.add("direct-sale recipient", TransformationType.ADDRESS);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();
		byte[] ownerPublicKey = Serialization.deserializePublicKey(byteBuffer);

		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.SELL_NAME);

		String name = Serialization.deserializeSizedString(byteBuffer, Name.MAX_NAME_SIZE);

		long amount = byteBuffer.getLong();

		boolean hasRecipient = byteBuffer.get() != 0;
		String recipient = hasRecipient ? Serialization.deserializeAddress(byteBuffer) : null;

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, ownerPublicKey, fee, nonce, signature);

		return new SellNameTransactionData(baseTransactionData, name, amount, recipient);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		SellNameTransactionData sellNameTransactionData = (SellNameTransactionData) transactionData;

		int dataLength = getBaseLength(transactionData) + EXTRAS_LENGTH + Utf8.encodedLength(sellNameTransactionData.getName());

		if (sellNameTransactionData.getRecipient() != null)
			dataLength += ADDRESS_LENGTH;

		return dataLength;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			SellNameTransactionData sellNameTransactionData = (SellNameTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			Serialization.serializeSizedString(bytes, sellNameTransactionData.getName());

			bytes.write(Longs.toByteArray(sellNameTransactionData.getAmount()));

			if (sellNameTransactionData.getRecipient() != null) {
				bytes.write((byte) 1);
				Serialization.serializeAddress(bytes, sellNameTransactionData.getRecipient());
			} else {
				bytes.write((byte) 0);
			}

			bytes.write(Longs.toByteArray(sellNameTransactionData.getFee()));

			if (sellNameTransactionData.getSignature() != null)
				bytes.write(sellNameTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
