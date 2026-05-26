package org.qortium.transform.transaction;

import com.google.common.base.Utf8;
import com.google.common.primitives.Longs;
import org.qortium.asset.Asset;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.IssueAssetTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class IssueAssetTransactionTransformer extends TransactionTransformer {

	// Property lengths
	private static final int NAME_SIZE_LENGTH = INT_LENGTH;
	private static final int DESCRIPTION_SIZE_LENGTH = INT_LENGTH;
	private static final int QUANTITY_LENGTH = AMOUNT_LENGTH;
	private static final int IS_DIVISIBLE_LENGTH = BOOLEAN_LENGTH;
	private static final int DATA_SIZE_LENGTH = INT_LENGTH;
	private static final int IS_UNSPENDABLE_LENGTH = BOOLEAN_LENGTH;
	private static final int REQUESTED_ASSET_ID_PRESENT_LENGTH = BOOLEAN_LENGTH;

	private static final int EXTRAS_LENGTH = NAME_SIZE_LENGTH + DESCRIPTION_SIZE_LENGTH + QUANTITY_LENGTH
			+ IS_DIVISIBLE_LENGTH + DATA_SIZE_LENGTH + IS_UNSPENDABLE_LENGTH + REQUESTED_ASSET_ID_PRESENT_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.ISSUE_ASSET.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("asset issuer's public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.ISSUE_ASSET);
		layout.add("has requested asset ID?", TransformationType.BOOLEAN);
		layout.add("? requested asset ID", TransformationType.LONG);
		layout.add("asset name length", TransformationType.INT);
		layout.add("asset name", TransformationType.STRING);
		layout.add("asset description length", TransformationType.INT);
		layout.add("asset description", TransformationType.STRING);
		layout.add("asset quantity", TransformationType.AMOUNT);
		layout.add("can asset quantities be fractional?", TransformationType.BOOLEAN);
		layout.add("asset data length", TransformationType.INT);
		layout.add("asset data", TransformationType.STRING);
		layout.add("are non-owner holders barred from using asset?", TransformationType.BOOLEAN);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();
		byte[] issuerPublicKey = Serialization.deserializePublicKey(byteBuffer);

		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.ISSUE_ASSET);

		boolean hasRequestedAssetId = byteBuffer.get() != 0;
		Long requestedAssetId = hasRequestedAssetId ? byteBuffer.getLong() : null;

		String assetName = Serialization.deserializeSizedString(byteBuffer, Asset.MAX_NAME_SIZE);

		String description = Serialization.deserializeSizedString(byteBuffer, Asset.MAX_DESCRIPTION_SIZE);

		long quantity = byteBuffer.getLong();

		boolean isDivisible = byteBuffer.get() != 0;

		String data = Serialization.deserializeSizedString(byteBuffer, Asset.MAX_DATA_SIZE);

		boolean isUnspendable = byteBuffer.get() != 0;

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, issuerPublicKey, fee, nonce, signature);

		return new IssueAssetTransactionData(baseTransactionData, requestedAssetId, assetName, description, quantity, isDivisible, data, isUnspendable);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		IssueAssetTransactionData issueAssetTransactionData = (IssueAssetTransactionData) transactionData;

		return getBaseLength(transactionData) + EXTRAS_LENGTH
				+ (issueAssetTransactionData.getRequestedAssetId() != null ? ASSET_ID_LENGTH : 0)
				+ Utf8.encodedLength(issueAssetTransactionData.getAssetName())
				+ Utf8.encodedLength(issueAssetTransactionData.getDescription())
				+ Utf8.encodedLength(issueAssetTransactionData.getData());
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			IssueAssetTransactionData issueAssetTransactionData = (IssueAssetTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			Long requestedAssetId = issueAssetTransactionData.getRequestedAssetId();
			bytes.write((byte) (requestedAssetId != null ? 1 : 0));
			if (requestedAssetId != null)
				bytes.write(Longs.toByteArray(requestedAssetId));

			Serialization.serializeSizedString(bytes, issueAssetTransactionData.getAssetName());

			Serialization.serializeSizedString(bytes, issueAssetTransactionData.getDescription());

			bytes.write(Longs.toByteArray(issueAssetTransactionData.getQuantity()));
			bytes.write((byte) (issueAssetTransactionData.isDivisible() ? 1 : 0));

			Serialization.serializeSizedString(bytes, issueAssetTransactionData.getData());

			bytes.write((byte) (issueAssetTransactionData.isUnspendable() ? 1 : 0));

			bytes.write(Longs.toByteArray(issueAssetTransactionData.getFee()));

			if (issueAssetTransactionData.getSignature() != null)
				bytes.write(issueAssetTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
