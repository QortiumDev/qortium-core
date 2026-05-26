package org.qortium.transform.transaction;

import com.google.common.primitives.Longs;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.BuyAssetOwnershipTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class BuyAssetOwnershipTransactionTransformer extends TransactionTransformer {

	private static final int SELLER_LENGTH = ADDRESS_LENGTH;

	private static final int EXTRAS_LENGTH = ASSET_ID_LENGTH + AMOUNT_LENGTH + SELLER_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.BUY_ASSET_OWNERSHIP.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("buyer's public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.BUY_ASSET_OWNERSHIP);
		layout.add("asset ID", TransformationType.LONG);
		layout.add("buy price", TransformationType.AMOUNT);
		layout.add("seller", TransformationType.ADDRESS);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();
		byte[] buyerPublicKey = Serialization.deserializePublicKey(byteBuffer);

		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.BUY_ASSET_OWNERSHIP);

		long assetId = byteBuffer.getLong();

		long amount = byteBuffer.getLong();

		String seller = Serialization.deserializeAddress(byteBuffer);

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, buyerPublicKey, fee, nonce, signature);

		return new BuyAssetOwnershipTransactionData(baseTransactionData, assetId, amount, seller);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		return getBaseLength(transactionData) + EXTRAS_LENGTH;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			BuyAssetOwnershipTransactionData buyAssetOwnershipTransactionData = (BuyAssetOwnershipTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			bytes.write(Longs.toByteArray(buyAssetOwnershipTransactionData.getAssetId()));

			bytes.write(Longs.toByteArray(buyAssetOwnershipTransactionData.getAmount()));

			Serialization.serializeAddress(bytes, buyAssetOwnershipTransactionData.getSeller());

			bytes.write(Longs.toByteArray(buyAssetOwnershipTransactionData.getFee()));

			if (buyAssetOwnershipTransactionData.getSignature() != null)
				bytes.write(buyAssetOwnershipTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
