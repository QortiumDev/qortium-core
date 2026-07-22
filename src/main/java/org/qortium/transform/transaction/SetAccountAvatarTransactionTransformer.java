package org.qortium.transform.transaction;

import com.google.common.primitives.Longs;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.SetAccountAvatarTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class SetAccountAvatarTransactionTransformer extends TransactionTransformer {

	private static final int EXTRAS_LENGTH = BOOLEAN_LENGTH + SIGNATURE_LENGTH;
	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.SET_ACCOUNT_AVATAR.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("account public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.SET_ACCOUNT_AVATAR);
		layout.add("avatar present", TransformationType.BOOLEAN);
		layout.add("authorized ARBITRARY signature (zeroed when clear)", TransformationType.SIGNATURE);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();
		int txGroupId = byteBuffer.getInt();
		byte[] ownerPublicKey = Serialization.deserializePublicKey(byteBuffer);
		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.SET_ACCOUNT_AVATAR);
		boolean present = byteBuffer.get() != 0;
		byte[] avatarSignature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(avatarSignature);
		if (!present) avatarSignature = null;
		long fee = byteBuffer.getLong();
		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);
		return new SetAccountAvatarTransactionData(new BaseTransactionData(timestamp, txGroupId, ownerPublicKey, fee, nonce, signature), avatarSignature);
	}

	public static int getDataLength(TransactionData transactionData) {
		return getBaseLength(transactionData) + EXTRAS_LENGTH;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			SetAccountAvatarTransactionData tx = (SetAccountAvatarTransactionData) transactionData;
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			transformCommonBytes(transactionData, bytes);
			bytes.write(tx.getAvatarSignature() == null ? 0 : 1);
			bytes.write(tx.getAvatarSignature() == null ? new byte[SIGNATURE_LENGTH] : tx.getAvatarSignature());
			bytes.write(Longs.toByteArray(tx.getFee()));
			if (tx.getSignature() != null) bytes.write(tx.getSignature());
			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}
}
