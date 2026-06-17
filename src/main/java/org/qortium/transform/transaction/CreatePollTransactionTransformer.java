package org.qortium.transform.transaction;

import com.google.common.base.Utf8;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.CreatePollTransactionData;
import org.qortium.data.transaction.TransactionData;
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

public class CreatePollTransactionTransformer extends TransactionTransformer {

	// Property lengths
	private static final int OWNER_LENGTH = ADDRESS_LENGTH;
	private static final int NAME_SIZE_LENGTH = INT_LENGTH;
	private static final int DESCRIPTION_SIZE_LENGTH = INT_LENGTH;
	private static final int OPTIONS_SIZE_LENGTH = INT_LENGTH;
	private static final int TIME_FLAGS_LENGTH = BYTE_LENGTH;
	private static final int START_TIME_FLAG = 1;
	private static final int END_TIME_FLAG = 2;

	private static final int EXTRAS_LENGTH = OWNER_LENGTH + NAME_SIZE_LENGTH + DESCRIPTION_SIZE_LENGTH + OPTIONS_SIZE_LENGTH;
	private static final int OLD_TRAILING_LENGTH = FEE_LENGTH + SIGNATURE_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.CREATE_POLL.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("poll creator's public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.CREATE_POLL);
		layout.add("poll owner's address", TransformationType.ADDRESS);
		layout.add("poll name length", TransformationType.INT);
		layout.add("poll name", TransformationType.STRING);
		layout.add("poll description length", TransformationType.INT);
		layout.add("poll description", TransformationType.STRING);
		layout.add("number of options", TransformationType.INT);
		layout.add("* poll option length", TransformationType.INT);
		layout.add("* poll option", TransformationType.STRING);
		layout.add("poll start/end time flags plus optional times", TransformationType.BYTE);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();
		byte[] creatorPublicKey = Serialization.deserializePublicKey(byteBuffer);

		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.CREATE_POLL);

		String owner = Serialization.deserializeAddress(byteBuffer);

		String pollName = Serialization.deserializeSizedString(byteBuffer, Poll.MAX_NAME_SIZE);

		String description = Serialization.deserializeSizedString(byteBuffer, Poll.MAX_DESCRIPTION_SIZE);

		int optionsCount = byteBuffer.getInt();
		if (optionsCount < 2 || optionsCount > Poll.MAX_OPTIONS)
			throw new TransformationException("Invalid number of options for CreatePollTransaction");

		List<PollOptionData> pollOptions = new ArrayList<>();
		for (int optionIndex = 0; optionIndex < optionsCount; ++optionIndex) {
			String optionName = Serialization.deserializeSizedString(byteBuffer, Poll.MAX_NAME_SIZE);

			pollOptions.add(new PollOptionData(optionName));
		}

		Long startTime = null;
		Long endTime = null;
		int extraBytes = byteBuffer.remaining() - OLD_TRAILING_LENGTH;
		if (extraBytes == LONG_LENGTH) {
			endTime = byteBuffer.getLong();
		} else if (extraBytes > 0) {
			byte timeFlags = byteBuffer.get();
			extraBytes -= TIME_FLAGS_LENGTH;

			if ((timeFlags & START_TIME_FLAG) != 0) {
				if (extraBytes < LONG_LENGTH)
					throw new TransformationException("Missing start time for CreatePollTransaction");
				startTime = byteBuffer.getLong();
				extraBytes -= LONG_LENGTH;
			}

			if ((timeFlags & END_TIME_FLAG) != 0) {
				if (extraBytes < LONG_LENGTH)
					throw new TransformationException("Missing end time for CreatePollTransaction");
				endTime = byteBuffer.getLong();
				extraBytes -= LONG_LENGTH;
			}

			if (extraBytes != 0)
				throw new TransformationException("Unexpected poll time bytes for CreatePollTransaction");
		}

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, creatorPublicKey, fee, nonce, signature);

		return new CreatePollTransactionData(baseTransactionData, owner, pollName, description, pollOptions, startTime, endTime);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		CreatePollTransactionData createPollTransactionData = (CreatePollTransactionData) transactionData;

		int dataLength = getBaseLength(transactionData) + EXTRAS_LENGTH + Utf8.encodedLength(createPollTransactionData.getPollName())
				+ Utf8.encodedLength(createPollTransactionData.getDescription());

		// Add lengths for each poll options
		for (PollOptionData pollOptionData : createPollTransactionData.getPollOptions())
			// option-string-length, option-string
			dataLength += INT_LENGTH + Utf8.encodedLength(pollOptionData.getOptionName());

		if (createPollTransactionData.getStartTime() != null)
			dataLength += TIME_FLAGS_LENGTH + LONG_LENGTH + (createPollTransactionData.getEndTime() == null ? 0 : LONG_LENGTH);
		else if (createPollTransactionData.getEndTime() != null)
			dataLength += LONG_LENGTH;

		return dataLength;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			CreatePollTransactionData createPollTransactionData = (CreatePollTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			Serialization.serializeAddress(bytes, createPollTransactionData.getOwner());

			Serialization.serializeSizedString(bytes, createPollTransactionData.getPollName());

			Serialization.serializeSizedString(bytes, createPollTransactionData.getDescription());

			List<PollOptionData> pollOptions = createPollTransactionData.getPollOptions();
			bytes.write(Ints.toByteArray(pollOptions.size()));

			for (PollOptionData pollOptionData : pollOptions)
				Serialization.serializeSizedString(bytes, pollOptionData.getOptionName());

			Long startTime = createPollTransactionData.getStartTime();
			Long endTime = createPollTransactionData.getEndTime();
			if (startTime != null) {
				int timeFlags = START_TIME_FLAG | (endTime == null ? 0 : END_TIME_FLAG);
				bytes.write(timeFlags);
				bytes.write(Longs.toByteArray(startTime));
				if (endTime != null)
					bytes.write(Longs.toByteArray(endTime));
			} else if (endTime != null) {
				bytes.write(Longs.toByteArray(endTime));
			}

			bytes.write(Longs.toByteArray(createPollTransactionData.getFee()));

			if (createPollTransactionData.getSignature() != null)
				bytes.write(createPollTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
