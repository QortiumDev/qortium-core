package org.qortium.transform.transaction;

import com.google.common.base.Utf8;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.RateResourceTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.naming.Name;
import org.qortium.transaction.ArbitraryTransaction;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class RateResourceTransactionTransformer extends TransactionTransformer {

	private static final int SERVICE_LENGTH = INT_LENGTH;
	private static final int NAME_SIZE_LENGTH = INT_LENGTH;
	private static final int IDENTIFIER_SIZE_LENGTH = INT_LENGTH;
	private static final int RATING_LENGTH = INT_LENGTH;

	private static final int EXTRAS_LENGTH = SERVICE_LENGTH + NAME_SIZE_LENGTH + IDENTIFIER_SIZE_LENGTH + RATING_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.RATE_RESOURCE.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("rater's public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.RATE_RESOURCE);
		layout.add("service ID", TransformationType.INT);
		layout.add("name length", TransformationType.INT);
		layout.add("name", TransformationType.STRING);
		layout.add("identifier length", TransformationType.INT);
		layout.add("identifier", TransformationType.STRING);
		layout.add("rating", TransformationType.INT);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();
		byte[] raterPublicKey = Serialization.deserializePublicKey(byteBuffer);

		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.RATE_RESOURCE);

		int service = byteBuffer.getInt();
		String name = Serialization.deserializeSizedStringV2(byteBuffer, Name.MAX_NAME_SIZE);
		String identifier = Serialization.deserializeSizedStringV2(byteBuffer, ArbitraryTransaction.MAX_IDENTIFIER_LENGTH);
		int rating = byteBuffer.getInt();

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, raterPublicKey, fee, nonce, signature);

		return new RateResourceTransactionData(baseTransactionData, service, name, identifier, rating);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		RateResourceTransactionData rateResourceTransactionData = (RateResourceTransactionData) transactionData;

		int nameLength = rateResourceTransactionData.getName() == null ? 0 : Utf8.encodedLength(rateResourceTransactionData.getName());
		int identifierLength = rateResourceTransactionData.getIdentifier() == null ? 0 : Utf8.encodedLength(rateResourceTransactionData.getIdentifier());

		return getBaseLength(transactionData) + EXTRAS_LENGTH + nameLength + identifierLength;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			RateResourceTransactionData rateResourceTransactionData = (RateResourceTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			bytes.write(Ints.toByteArray(rateResourceTransactionData.getServiceInt()));
			Serialization.serializeSizedStringV2(bytes, rateResourceTransactionData.getName());
			Serialization.serializeSizedStringV2(bytes, rateResourceTransactionData.getIdentifier());
			bytes.write(Ints.toByteArray(rateResourceTransactionData.getRating()));

			bytes.write(Longs.toByteArray(rateResourceTransactionData.getFee()));

			if (rateResourceTransactionData.getSignature() != null)
				bytes.write(rateResourceTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
