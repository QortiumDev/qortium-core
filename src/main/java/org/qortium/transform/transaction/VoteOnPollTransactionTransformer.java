package org.qortium.transform.transaction;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.VoteOnPollTransactionData;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;
import org.qortium.voting.Poll;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class VoteOnPollTransactionTransformer extends TransactionTransformer {

	// Property lengths
	private static final int POLL_ID_LENGTH = INT_LENGTH;
	private static final int POLL_OPTION_LENGTH = INT_LENGTH;
	private static final int TRAILING_LENGTH = FEE_LENGTH + SIGNATURE_LENGTH;

	private static final int EXTRAS_LENGTH = POLL_ID_LENGTH + POLL_OPTION_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.VOTE_ON_POLL.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("voter's public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.VOTE_ON_POLL);
		layout.add("poll ID", TransformationType.INT);
		layout.add("poll option index or option-index count followed by indexes", TransformationType.INT);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();
		byte[] voterPublicKey = Serialization.deserializePublicKey(byteBuffer);

		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.VOTE_ON_POLL);

		int pollId = byteBuffer.getInt();

		int optionBytesLength = byteBuffer.remaining() - TRAILING_LENGTH;
		if (optionBytesLength < INT_LENGTH || optionBytesLength % INT_LENGTH != 0)
			throw new TransformationException("Invalid option bytes for VoteOnPollTransaction");

		int firstOptionValue = byteBuffer.getInt();
		List<Integer> optionIndexes = new ArrayList<>();
		if (optionBytesLength == INT_LENGTH) {
			if (firstOptionValue < Poll.NO_VOTE_OPTION_INDEX || firstOptionValue > Poll.MAX_OPTIONS)
				throw new TransformationException("Invalid option number for VoteOnPollTransaction");

			if (firstOptionValue != Poll.NO_VOTE_OPTION_INDEX)
				optionIndexes.add(firstOptionValue);
		} else {
			int optionsCount = firstOptionValue;
			if (optionsCount < 2 || optionsCount > Poll.MAX_OPTIONS)
				throw new TransformationException("Invalid number of options for VoteOnPollTransaction");

			if (optionBytesLength != INT_LENGTH + optionsCount * INT_LENGTH)
				throw new TransformationException("Invalid option count for VoteOnPollTransaction");

			for (int i = 0; i < optionsCount; ++i) {
				int optionIndex = byteBuffer.getInt();
				if (optionIndex <= Poll.NO_VOTE_OPTION_INDEX || optionIndex > Poll.MAX_OPTIONS)
					throw new TransformationException("Invalid option number for VoteOnPollTransaction");

				optionIndexes.add(optionIndex);
			}
		}

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, voterPublicKey, fee, nonce, signature);

		return new VoteOnPollTransactionData(baseTransactionData, pollId, optionIndexes);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		VoteOnPollTransactionData voteOnPollTransactionData = (VoteOnPollTransactionData) transactionData;
		int selectionsCount = voteOnPollTransactionData.getSelectedOptionIndexes().size();

		if (selectionsCount <= 1)
			return getBaseLength(transactionData) + EXTRAS_LENGTH;

		return getBaseLength(transactionData) + POLL_ID_LENGTH + POLL_OPTION_LENGTH + selectionsCount * INT_LENGTH;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			VoteOnPollTransactionData voteOnPollTransactionData = (VoteOnPollTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			bytes.write(Ints.toByteArray(voteOnPollTransactionData.getPollId()));

			List<Integer> optionIndexes = voteOnPollTransactionData.getSelectedOptionIndexes();
			if (optionIndexes.size() <= 1) {
				bytes.write(Ints.toByteArray(voteOnPollTransactionData.getOptionIndex()));
			} else {
				bytes.write(Ints.toByteArray(optionIndexes.size()));
				for (Integer optionIndex : optionIndexes)
					bytes.write(Ints.toByteArray(optionIndex));
			}

			bytes.write(Longs.toByteArray(voteOnPollTransactionData.getFee()));

			if (voteOnPollTransactionData.getSignature() != null)
				bytes.write(voteOnPollTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
