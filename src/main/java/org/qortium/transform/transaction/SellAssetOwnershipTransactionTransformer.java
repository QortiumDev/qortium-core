package org.qortium.transform.transaction;

import com.google.common.primitives.Longs;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.SellAssetOwnershipTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class SellAssetOwnershipTransactionTransformer extends TransactionTransformer {

	// Property lengths
	private static final int RECIPIENT_PRESENT_LENGTH = BOOLEAN_LENGTH;

	private static final int EXTRAS_LENGTH = ASSET_ID_LENGTH + AMOUNT_LENGTH + RECIPIENT_PRESENT_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.SELL_ASSET_OWNERSHIP.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("asset owner's public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.SELL_ASSET_OWNERSHIP);
		layout.add("asset ID", TransformationType.LONG);
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

		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.SELL_ASSET_OWNERSHIP);

		long assetId = byteBuffer.getLong();

		long amount = byteBuffer.getLong();

		boolean hasRecipient = byteBuffer.get() != 0;
		String recipient = hasRecipient ? Serialization.deserializeAddress(byteBuffer) : null;

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, ownerPublicKey, fee, nonce, signature);

		return new SellAssetOwnershipTransactionData(baseTransactionData, assetId, amount, recipient);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		SellAssetOwnershipTransactionData sellAssetOwnershipTransactionData = (SellAssetOwnershipTransactionData) transactionData;

		int dataLength = getBaseLength(transactionData) + EXTRAS_LENGTH;

		if (sellAssetOwnershipTransactionData.getRecipient() != null)
			dataLength += ADDRESS_LENGTH;

		return dataLength;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			SellAssetOwnershipTransactionData sellAssetOwnershipTransactionData = (SellAssetOwnershipTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			bytes.write(Longs.toByteArray(sellAssetOwnershipTransactionData.getAssetId()));

			bytes.write(Longs.toByteArray(sellAssetOwnershipTransactionData.getAmount()));

			if (sellAssetOwnershipTransactionData.getRecipient() != null) {
				bytes.write((byte) 1);
				Serialization.serializeAddress(bytes, sellAssetOwnershipTransactionData.getRecipient());
			} else {
				bytes.write((byte) 0);
			}

			bytes.write(Longs.toByteArray(sellAssetOwnershipTransactionData.getFee()));

			if (sellAssetOwnershipTransactionData.getSignature() != null)
				bytes.write(sellAssetOwnershipTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
