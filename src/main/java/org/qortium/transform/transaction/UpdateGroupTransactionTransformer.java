package org.qortium.transform.transaction;

import com.google.common.base.Utf8;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdateGroupTransactionData;
import org.qortium.group.Group;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class UpdateGroupTransactionTransformer extends TransactionTransformer {

	// Property lengths
	private static final int GROUPID_LENGTH = INT_LENGTH;
	private static final int NEW_NAME_SIZE_LENGTH = INT_LENGTH;
	private static final int NEW_DESCRIPTION_SIZE_LENGTH = INT_LENGTH;
	private static final int NEW_IS_OPEN_LENGTH = BOOLEAN_LENGTH;
	private static final int NEW_APPROVAL_THRESHOLD_LENGTH = BYTE_LENGTH;
	private static final int NEW_MINIMUM_BLOCK_DELAY_LENGTH = INT_LENGTH;
	private static final int NEW_MAXIMUM_BLOCK_DELAY_LENGTH = INT_LENGTH;

	private static final int EXTRAS_LENGTH = GROUPID_LENGTH + NEW_NAME_SIZE_LENGTH + NEW_DESCRIPTION_SIZE_LENGTH + NEW_IS_OPEN_LENGTH
			+ NEW_APPROVAL_THRESHOLD_LENGTH + NEW_MINIMUM_BLOCK_DELAY_LENGTH + NEW_MAXIMUM_BLOCK_DELAY_LENGTH;

	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.UPDATE_GROUP.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("group owner's public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.UPDATE_GROUP);
		layout.add("group ID", TransformationType.INT);
		layout.add("group's new name length (0 for no change)", TransformationType.INT);
		layout.add("group's new name", TransformationType.STRING);
		layout.add("group's new description length", TransformationType.INT);
		layout.add("group's new description", TransformationType.STRING);
		layout.add("is group \"open\"?", TransformationType.BOOLEAN);
		layout.add("new group transaction approval threshold", TransformationType.BYTE);
		layout.add("new group approval minimum block delay", TransformationType.INT);
		layout.add("new group approval maximum block delay", TransformationType.INT);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();

		int txGroupId = byteBuffer.getInt();
		byte[] ownerPublicKey = Serialization.deserializePublicKey(byteBuffer);

		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.UPDATE_GROUP);

		int groupId = byteBuffer.getInt();

		String newName = Serialization.deserializeSizedString(byteBuffer, Group.MAX_NAME_SIZE);

		String newDescription = Serialization.deserializeSizedString(byteBuffer, Group.MAX_DESCRIPTION_SIZE);

		boolean newIsOpen = byteBuffer.get() != 0;

		ApprovalThreshold newApprovalThreshold = ApprovalThreshold.valueOf(byteBuffer.get());

		int newMinBlockDelay = byteBuffer.getInt();

		int newMaxBlockDelay = byteBuffer.getInt();

		long fee = byteBuffer.getLong();

		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, txGroupId, ownerPublicKey, fee, nonce, signature);

		return new UpdateGroupTransactionData(baseTransactionData, groupId, newName, newDescription, newIsOpen,
				newApprovalThreshold, newMinBlockDelay, newMaxBlockDelay);
	}

	public static int getDataLength(TransactionData transactionData) throws TransformationException {
		UpdateGroupTransactionData updateGroupTransactionData = (UpdateGroupTransactionData) transactionData;

		return getBaseLength(transactionData) + EXTRAS_LENGTH + Utf8.encodedLength(updateGroupTransactionData.getNewName())
				+ Utf8.encodedLength(updateGroupTransactionData.getNewDescription());
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			UpdateGroupTransactionData updateGroupTransactionData = (UpdateGroupTransactionData) transactionData;

			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			transformCommonBytes(transactionData, bytes);

			bytes.write(Ints.toByteArray(updateGroupTransactionData.getGroupId()));

			Serialization.serializeSizedString(bytes, updateGroupTransactionData.getNewName());

			Serialization.serializeSizedString(bytes, updateGroupTransactionData.getNewDescription());

			bytes.write((byte) (updateGroupTransactionData.getNewIsOpen() ? 1 : 0));

			Serialization.serializeUnsignedByte(bytes, updateGroupTransactionData.getNewApprovalThreshold().value);

			bytes.write(Ints.toByteArray(updateGroupTransactionData.getNewMinimumBlockDelay()));

			bytes.write(Ints.toByteArray(updateGroupTransactionData.getNewMaximumBlockDelay()));

			bytes.write(Longs.toByteArray(updateGroupTransactionData.getFee()));

			if (updateGroupTransactionData.getSignature() != null)
				bytes.write(updateGroupTransactionData.getSignature());

			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}

}
