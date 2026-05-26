package org.qortium.transform.transaction;

import com.google.common.base.Utf8;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdatePollTransactionData;
import org.qortium.data.voting.PollOptionData;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;
import org.qortium.voting.Poll;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class UpdatePollTransactionTransformer extends TransactionTransformer {

	private static final int POLL_ID_LENGTH = INT_LENGTH;
	private static final int NAME_SIZE_LENGTH = INT_LENGTH;
	private static final int DESCRIPTION_SIZE_LENGTH = INT_LENGTH;
	private static final int OPTIONS_SIZE_LENGTH = INT_LENGTH;

	private static final int EXTRAS_LENGTH = POLL_ID_LENGTH + NAME_SIZE_LENGTH + DESCRIPTION_SIZE_LENGTH + OPTIONS_SIZE_LENGTH;
	private static final int TRAILING_LENGTH = FEE_LENGTH + SIGNATURE_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.UPDATE_POLL.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("poll owner's public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.UPDATE_POLL);
		layout.add("poll ID", TransformationType.INT);
		layout.add("poll's new name length", TransformationType.INT);
		layout.add("poll's new name", TransformationType.STRING);
		layout.add("poll's new description length", TransformationType.INT);
		layout.add("poll's new description", TransformationType.STRING);
		layout.add("number of new options", TransformationType.INT);
		layout.add("* poll option length", TransformationType.INT);
		layout.add("* poll option", TransformationType.STRING);
		layout.add("poll end time (optional)", TransformationType.LONG);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();
		byte[] ownerPublicKey = Serialization.deserializePublicKey(byteBuffer);

		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.UPDATE_POLL);

		int pollId = byteBuffer.getInt();
		String newPollName = Serialization.deserializeSizedString(byteBuffer, Poll.MAX_NAME_SIZE);
		String newDescription = Serialization.deserializeSizedString(byteBuffer, Poll.MAX_DESCRIPTION_SIZE);

		int optionsCount = byteBuffer.getInt();
		if (optionsCount < 2 || optionsCount > Poll.MAX_OPTIONS)
			throw new TransformationException("Invalid number of options for UpdatePollTransaction");

		List<PollOptionData> newPollOptions = new ArrayList<>();
		for (int optionIndex = 0; optionIndex < optionsCount; ++optionIndex)
			newPollOptions.add(new PollOptionData(Serialization.deserializeSizedString(byteBuffer, Poll.MAX_NAME_SIZE)));

		Long newEndTime = null;
		if (byteBuffer.remaining() > TRAILING_LENGTH)
			newEndTime = byteBuffer.getLong();

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, ownerPublicKey, fee, nonce, signature);

		return new UpdatePollTransactionData(baseTransactionData, pollId, newPollName, newDescription, newPollOptions, newEndTime);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		UpdatePollTransactionData updatePollTransactionData = (UpdatePollTransactionData) transactionData;

		int dataLength = getBaseLength(transactionData) + EXTRAS_LENGTH
				+ Utf8.encodedLength(updatePollTransactionData.getNewPollName())
				+ Utf8.encodedLength(updatePollTransactionData.getNewDescription());

		for (PollOptionData pollOptionData : updatePollTransactionData.getNewPollOptions())
			dataLength += INT_LENGTH + Utf8.encodedLength(pollOptionData.getOptionName());

		if (updatePollTransactionData.getNewEndTime() != null)
			dataLength += LONG_LENGTH;

		return dataLength;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			UpdatePollTransactionData updatePollTransactionData = (UpdatePollTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			bytes.write(Ints.toByteArray(updatePollTransactionData.getPollId()));
			Serialization.serializeSizedString(bytes, updatePollTransactionData.getNewPollName());
			Serialization.serializeSizedString(bytes, updatePollTransactionData.getNewDescription());

			List<PollOptionData> newPollOptions = updatePollTransactionData.getNewPollOptions();
			bytes.write(Ints.toByteArray(newPollOptions.size()));
			for (PollOptionData pollOptionData : newPollOptions)
				Serialization.serializeSizedString(bytes, pollOptionData.getOptionName());

			if (updatePollTransactionData.getNewEndTime() != null)
				bytes.write(Longs.toByteArray(updatePollTransactionData.getNewEndTime()));

			bytes.write(Longs.toByteArray(updatePollTransactionData.getFee()));

			if (updatePollTransactionData.getSignature() != null)
				bytes.write(updatePollTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
