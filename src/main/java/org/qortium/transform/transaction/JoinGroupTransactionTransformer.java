package org.qortium.transform.transaction;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.JoinGroupTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class JoinGroupTransactionTransformer extends TransactionTransformer {

	// Property lengths
	private static final int GROUPID_LENGTH = INT_LENGTH;

	private static final int EXTRAS_LENGTH = GROUPID_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.JOIN_GROUP.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("joiner's public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.JOIN_GROUP);
		layout.add("group ID", TransformationType.INT);
		layout.add("optional minting public key", TransformationType.PUBLIC_KEY);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();
		byte[] joinerPublicKey = Serialization.deserializePublicKey(byteBuffer);

		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.JOIN_GROUP);

		int groupId = byteBuffer.getInt();

		byte[] mintingPublicKey = null;
		if (byteBuffer.remaining() == PUBLIC_KEY_LENGTH + FEE_LENGTH + SIGNATURE_LENGTH)
			mintingPublicKey = Serialization.deserializePublicKey(byteBuffer);
		else if (byteBuffer.remaining() != FEE_LENGTH + SIGNATURE_LENGTH)
			throw new TransformationException("Unexpected byte data length for JOIN_GROUP transaction");

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, joinerPublicKey, fee, nonce, signature);

		return new JoinGroupTransactionData(baseTransactionData, groupId, mintingPublicKey);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		JoinGroupTransactionData joinGroupTransactionData = (JoinGroupTransactionData) transactionData;

		return getBaseLength(transactionData) + EXTRAS_LENGTH
				+ (joinGroupTransactionData.getMintingPublicKey() == null ? 0 : PUBLIC_KEY_LENGTH);
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			JoinGroupTransactionData joinGroupTransactionData = (JoinGroupTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			bytes.write(Ints.toByteArray(joinGroupTransactionData.getGroupId()));

			if (joinGroupTransactionData.getMintingPublicKey() != null)
				bytes.write(joinGroupTransactionData.getMintingPublicKey());

			bytes.write(Longs.toByteArray(joinGroupTransactionData.getFee()));

			if (joinGroupTransactionData.getSignature() != null)
				bytes.write(joinGroupTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
