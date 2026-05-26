package org.qortium.transform.transaction;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.RateAccountTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class RateAccountTransactionTransformer extends TransactionTransformer {

	private static final int TARGET_PUBLIC_KEY_LENGTH = PUBLIC_KEY_LENGTH;
	private static final int CATEGORY_LENGTH = INT_LENGTH;
	private static final int RATING_LENGTH = INT_LENGTH;

	private static final int EXTRAS_LENGTH = TARGET_PUBLIC_KEY_LENGTH + CATEGORY_LENGTH + RATING_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.RATE_ACCOUNT.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("rater's public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.RATE_ACCOUNT);
		layout.add("target account public key", TransformationType.PUBLIC_KEY);
		layout.add("account rating category", TransformationType.INT);
		layout.add("rating", TransformationType.INT);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();
		byte[] raterPublicKey = Serialization.deserializePublicKey(byteBuffer);

		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.RATE_ACCOUNT);

		byte[] targetPublicKey = Serialization.deserializePublicKey(byteBuffer);
		AccountRatingCategory category = AccountRatingCategory.valueOf(byteBuffer.getInt());
		if (category == null)
			throw new TransformationException("Invalid account rating category");

		int rating = byteBuffer.getInt();

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, raterPublicKey, fee, nonce, signature);

		return new RateAccountTransactionData(baseTransactionData, targetPublicKey, category, rating);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		return getBaseLength(transactionData) + EXTRAS_LENGTH;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			RateAccountTransactionData rateAccountTransactionData = (RateAccountTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			bytes.write(rateAccountTransactionData.getTargetPublicKey());
			bytes.write(Ints.toByteArray(rateAccountTransactionData.getCategoryValue()));
			bytes.write(Ints.toByteArray(rateAccountTransactionData.getRating()));

			bytes.write(Longs.toByteArray(rateAccountTransactionData.getFee()));

			if (rateAccountTransactionData.getSignature() != null)
				bytes.write(rateAccountTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}
}
