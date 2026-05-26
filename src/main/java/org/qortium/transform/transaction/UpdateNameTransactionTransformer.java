package org.qortium.transform.transaction;

import com.google.common.base.Utf8;
import com.google.common.primitives.Longs;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdateNameTransactionData;
import org.qortium.naming.Name;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class UpdateNameTransactionTransformer extends TransactionTransformer {

	// Property lengths
	private static final int NAME_SIZE_LENGTH = INT_LENGTH;
	private static final int NEW_NAME_SIZE_LENGTH = INT_LENGTH;
	private static final int NEW_DATA_SIZE_LENGTH = INT_LENGTH;
	private static final int PRIMARY_PRESENT_LENGTH = BOOLEAN_LENGTH;

	private static final int EXTRAS_LENGTH = NAME_SIZE_LENGTH + NEW_NAME_SIZE_LENGTH + NEW_DATA_SIZE_LENGTH + PRIMARY_PRESENT_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.UPDATE_NAME.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("name owner's public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.UPDATE_NAME);
		layout.add("name length", TransformationType.INT);
		layout.add("name", TransformationType.STRING);
		layout.add("new name's length (0 for no change)", TransformationType.INT);
		layout.add("new name", TransformationType.STRING);
		layout.add("new data length (0 for no change)", TransformationType.INT);
		layout.add("new data", TransformationType.STRING);
		layout.add("has primary-name setting?", TransformationType.BOOLEAN);
		layout.add("primary-name setting", TransformationType.BOOLEAN);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();
		byte[] ownerPublicKey = Serialization.deserializePublicKey(byteBuffer);

		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.UPDATE_NAME);

		String name = Serialization.deserializeSizedString(byteBuffer, Name.MAX_NAME_SIZE);

		String newName = Serialization.deserializeSizedString(byteBuffer, Name.MAX_NAME_SIZE);

		String newData = Serialization.deserializeSizedString(byteBuffer, Name.MAX_DATA_SIZE);

		boolean hasPrimary = byteBuffer.get() != 0;
		Boolean primary = hasPrimary ? byteBuffer.get() != 0 : null;

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, ownerPublicKey, fee, nonce, signature);

		return new UpdateNameTransactionData(baseTransactionData, name, newName, newData, primary);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		UpdateNameTransactionData updateNameTransactionData = (UpdateNameTransactionData) transactionData;

		return getBaseLength(transactionData) + EXTRAS_LENGTH + Utf8.encodedLength(updateNameTransactionData.getName())
				+ Utf8.encodedLength(updateNameTransactionData.getNewName())
				+ Utf8.encodedLength(updateNameTransactionData.getNewData())
				+ (updateNameTransactionData.getPrimary() != null ? BOOLEAN_LENGTH : 0);
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			UpdateNameTransactionData updateNameTransactionData = (UpdateNameTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			Serialization.serializeSizedString(bytes, updateNameTransactionData.getName());

			Serialization.serializeSizedString(bytes, updateNameTransactionData.getNewName());

			Serialization.serializeSizedString(bytes, updateNameTransactionData.getNewData());

			Boolean primary = updateNameTransactionData.getPrimary();
			if (primary != null) {
				bytes.write((byte) 1);
				bytes.write((byte) (primary ? 1 : 0));
			} else {
				bytes.write((byte) 0);
			}

			bytes.write(Longs.toByteArray(updateNameTransactionData.getFee()));

			if (updateNameTransactionData.getSignature() != null)
				bytes.write(updateNameTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
