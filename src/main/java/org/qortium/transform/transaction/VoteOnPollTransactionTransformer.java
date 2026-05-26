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

public class VoteOnPollTransactionTransformer extends TransactionTransformer {

	// Property lengths
	private static final int POLL_ID_LENGTH = INT_LENGTH;
	private static final int POLL_OPTION_LENGTH = INT_LENGTH;

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
		layout.add("poll option index (0 removes vote, 1+ selects option)", TransformationType.INT);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();
		byte[] voterPublicKey = Serialization.deserializePublicKey(byteBuffer);

		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.VOTE_ON_POLL);

		int pollId = byteBuffer.getInt();

		int optionIndex = byteBuffer.getInt();
		if (optionIndex < Poll.NO_VOTE_OPTION_INDEX || optionIndex > Poll.MAX_OPTIONS)
			throw new TransformationException("Invalid option number for VoteOnPollTransaction");

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, voterPublicKey, fee, nonce, signature);

		return new VoteOnPollTransactionData(baseTransactionData, pollId, optionIndex);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		return getBaseLength(transactionData) + EXTRAS_LENGTH;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			VoteOnPollTransactionData voteOnPollTransactionData = (VoteOnPollTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			bytes.write(Ints.toByteArray(voteOnPollTransactionData.getPollId()));

			bytes.write(Ints.toByteArray(voteOnPollTransactionData.getOptionIndex()));

			bytes.write(Longs.toByteArray(voteOnPollTransactionData.getFee()));

			if (voteOnPollTransactionData.getSignature() != null)
				bytes.write(voteOnPollTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
