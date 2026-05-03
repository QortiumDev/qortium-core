package org.qortal.transform.transaction;

import com.google.common.primitives.Longs;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.CancelSellAssetOwnershipTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.transaction.Transaction.TransactionType;
import org.qortal.transform.TransformationException;
import org.qortal.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class CancelSellAssetOwnershipTransactionTransformer extends TransactionTransformer {

	private static final int EXTRAS_LENGTH = ASSET_ID_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.CANCEL_SELL_ASSET_OWNERSHIP.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("asset owner's public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.CANCEL_SELL_ASSET_OWNERSHIP);
		layout.add("asset ID", TransformationType.LONG);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();
		byte[] ownerPublicKey = Serialization.deserializePublicKey(byteBuffer);

		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.CANCEL_SELL_ASSET_OWNERSHIP);

		long assetId = byteBuffer.getLong();

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, ownerPublicKey, fee, nonce, signature);

		return new CancelSellAssetOwnershipTransactionData(baseTransactionData, assetId);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		return getBaseLength(transactionData) + EXTRAS_LENGTH;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			CancelSellAssetOwnershipTransactionData cancelSellAssetOwnershipTransactionData = (CancelSellAssetOwnershipTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			bytes.write(Longs.toByteArray(cancelSellAssetOwnershipTransactionData.getAssetId()));

			bytes.write(Longs.toByteArray(cancelSellAssetOwnershipTransactionData.getFee()));

			if (cancelSellAssetOwnershipTransactionData.getSignature() != null)
				bytes.write(cancelSellAssetOwnershipTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
