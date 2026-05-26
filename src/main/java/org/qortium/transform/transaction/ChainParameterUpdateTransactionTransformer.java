package org.qortium.transform.transaction;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortium.block.ChainParameter;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChainParameterUpdateTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class ChainParameterUpdateTransactionTransformer extends TransactionTransformer {

	private static final int PARAMETER_ID_LENGTH = INT_LENGTH;
	private static final int ACTIVATION_HEIGHT_LENGTH = INT_LENGTH;
	private static final int VALUE_SIZE_LENGTH = INT_LENGTH;

	private static final int EXTRAS_LENGTH = PARAMETER_ID_LENGTH + ACTIVATION_HEIGHT_LENGTH + VALUE_SIZE_LENGTH;
	private static final int TRAILING_LENGTH = FEE_LENGTH + SIGNATURE_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.CHAIN_PARAMETER_UPDATE.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("updater's public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.CHAIN_PARAMETER_UPDATE);
		layout.add("chain parameter ID", TransformationType.INT);
		layout.add("activation height", TransformationType.INT);
		layout.add("parameter value length", TransformationType.INT);
		layout.add("parameter value", TransformationType.DATA);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();
		byte[] updaterPublicKey = Serialization.deserializePublicKey(byteBuffer);

		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.CHAIN_PARAMETER_UPDATE);

		int parameterId = byteBuffer.getInt();
		int activationHeight = byteBuffer.getInt();

		int valueLength = byteBuffer.getInt();
		if (valueLength < 0 || valueLength > ChainParameter.MAX_VALUE_LENGTH)
			throw new TransformationException("Invalid parameter value length for ChainParameterUpdateTransaction");

		if (valueLength > byteBuffer.remaining() - TRAILING_LENGTH)
			throw new TransformationException("Byte data too short for chain parameter value");

		byte[] value = new byte[valueLength];
		byteBuffer.get(value);

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, updaterPublicKey, fee, nonce, signature);

		return new ChainParameterUpdateTransactionData(baseTransactionData, parameterId, activationHeight, value);
	}

	public static int getDataLength(TransactionData transactionData) {
		ChainParameterUpdateTransactionData chainParameterUpdateTransactionData = (ChainParameterUpdateTransactionData) transactionData;
		byte[] value = chainParameterUpdateTransactionData.getValue();

		return getBaseLength(transactionData) + EXTRAS_LENGTH + (value == null ? 0 : value.length);
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			ChainParameterUpdateTransactionData chainParameterUpdateTransactionData = (ChainParameterUpdateTransactionData) transactionData;
			byte[] value = chainParameterUpdateTransactionData.getValue();
			int valueLength = value == null ? 0 : value.length;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			bytes.write(Ints.toByteArray(chainParameterUpdateTransactionData.getParameterId()));
			bytes.write(Ints.toByteArray(chainParameterUpdateTransactionData.getActivationHeight()));
			bytes.write(Ints.toByteArray(valueLength));
			if (valueLength > 0)
				bytes.write(value);

			bytes.write(Longs.toByteArray(chainParameterUpdateTransactionData.getFee()));

			if (chainParameterUpdateTransactionData.getSignature() != null)
				bytes.write(chainParameterUpdateTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}
}
