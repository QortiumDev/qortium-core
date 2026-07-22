package org.qortium.transform.transaction;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.SetGroupAvatarTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class SetGroupAvatarTransactionTransformer extends TransactionTransformer {
	private static final int AVATAR_PRESENT_LENGTH = BOOLEAN_LENGTH;
	private static final int EXTRAS_LENGTH = INT_LENGTH + AVATAR_PRESENT_LENGTH + SIGNATURE_LENGTH;
	protected static final TransactionLayout layout;
	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.SET_GROUP_AVATAR.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP); layout.add("transaction's groupID", TransformationType.INT);
		layout.add("group owner's public key", TransformationType.PUBLIC_KEY); addMempowFeeNonceToLayout(layout, TransactionType.SET_GROUP_AVATAR);
		layout.add("group ID", TransformationType.INT); layout.add("avatar present", TransformationType.BOOLEAN);
		layout.add("authorized ARBITRARY signature (zeroed when clear)", TransformationType.SIGNATURE); layout.add("fee", TransformationType.AMOUNT); layout.add("signature", TransformationType.SIGNATURE);
	}
	public static TransactionData fromByteBuffer(ByteBuffer b) throws TransformationException {
		long timestamp = b.getLong(); int txGroupId = b.getInt(); byte[] owner = Serialization.deserializePublicKey(b);
		Integer nonce = deserializeMempowFeeNonce(b, TransactionType.SET_GROUP_AVATAR); int groupId = b.getInt(); boolean present = b.get() != 0;
		byte[] avatar = new byte[SIGNATURE_LENGTH]; b.get(avatar); if (!present) avatar = null;
		long fee = b.getLong(); byte[] signature = new byte[SIGNATURE_LENGTH]; b.get(signature);
		return new SetGroupAvatarTransactionData(new BaseTransactionData(timestamp, txGroupId, owner, fee, nonce, signature), groupId, avatar);
	}
	public static int getDataLength(TransactionData data) { return getBaseLength(data) + EXTRAS_LENGTH; }
	public static byte[] toBytes(TransactionData data) throws TransformationException {
		try { SetGroupAvatarTransactionData tx = (SetGroupAvatarTransactionData) data; ByteArrayOutputStream bytes = new ByteArrayOutputStream(); transformCommonBytes(data, bytes);
			bytes.write(Ints.toByteArray(tx.getGroupId())); bytes.write(tx.getAvatarSignature() == null ? 0 : 1);
			bytes.write(tx.getAvatarSignature() == null ? new byte[SIGNATURE_LENGTH] : tx.getAvatarSignature()); bytes.write(Longs.toByteArray(tx.getFee())); if (tx.getSignature() != null) bytes.write(tx.getSignature()); return bytes.toByteArray();
		} catch (IOException | ClassCastException e) { throw new TransformationException(e); }
	}
}
