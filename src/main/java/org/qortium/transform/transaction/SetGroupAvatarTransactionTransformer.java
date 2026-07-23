package org.qortium.transform.transaction;

import com.google.common.base.Utf8;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortium.arbitrary.misc.Service;
import org.qortium.data.avatar.AvatarData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.SetGroupAvatarTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.naming.Name;
import org.qortium.transaction.ArbitraryTransaction;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class SetGroupAvatarTransactionTransformer extends TransactionTransformer {
	private static final int GROUP_AND_PRESENT_LENGTH = INT_LENGTH + BOOLEAN_LENGTH;
	protected static final TransactionLayout layout;
	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.SET_GROUP_AVATAR.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP); layout.add("transaction's groupID", TransformationType.INT);
		layout.add("group owner's public key", TransformationType.PUBLIC_KEY); addMempowFeeNonceToLayout(layout, TransactionType.SET_GROUP_AVATAR);
		layout.add("group ID", TransformationType.INT); layout.add("avatar present", TransformationType.BOOLEAN);
		layout.add("avatar service", TransformationType.INT);
		layout.add("avatar name length", TransformationType.INT); layout.add("avatar name", TransformationType.STRING);
		layout.add("avatar identifier length", TransformationType.INT); layout.add("avatar identifier", TransformationType.STRING);
		layout.add("fee", TransformationType.AMOUNT); layout.add("signature", TransformationType.SIGNATURE);
	}
	public static TransactionData fromByteBuffer(ByteBuffer b) throws TransformationException {
		long timestamp = b.getLong(); int txGroupId = b.getInt(); byte[] owner = Serialization.deserializePublicKey(b);
		Integer nonce = deserializeMempowFeeNonce(b, TransactionType.SET_GROUP_AVATAR); int groupId = b.getInt(); boolean present = b.get() != 0;
		AvatarData avatar = null;
		if (present) {
			Service service = Service.valueOf(b.getInt());
			String name = Serialization.deserializeSizedString(b, Name.MAX_NAME_SIZE);
			String identifier = Serialization.deserializeSizedString(b, ArbitraryTransaction.MAX_IDENTIFIER_LENGTH);
			avatar = new AvatarData(service, name, identifier);
		}
		long fee = b.getLong(); byte[] signature = new byte[SIGNATURE_LENGTH]; b.get(signature);
		return new SetGroupAvatarTransactionData(new BaseTransactionData(timestamp, txGroupId, owner, fee, nonce, signature), groupId, avatar);
	}
	public static int getDataLength(TransactionData data) {
		SetGroupAvatarTransactionData tx = (SetGroupAvatarTransactionData) data;
		int length = getBaseLength(data) + GROUP_AND_PRESENT_LENGTH;
		AvatarData avatar = tx.getAvatar();
		if (avatar != null) {
			String identifier = avatar.getIdentifier() == null ? "" : avatar.getIdentifier();
			length += INT_LENGTH + INT_LENGTH + Utf8.encodedLength(avatar.getName()) + INT_LENGTH + Utf8.encodedLength(identifier);
		}
		return length;
	}
	public static byte[] toBytes(TransactionData data) throws TransformationException {
		try { SetGroupAvatarTransactionData tx = (SetGroupAvatarTransactionData) data; ByteArrayOutputStream bytes = new ByteArrayOutputStream(); transformCommonBytes(data, bytes);
			bytes.write(Ints.toByteArray(tx.getGroupId()));
			AvatarData avatar = tx.getAvatar();
			bytes.write(avatar == null ? 0 : 1);
			if (avatar != null) {
				bytes.write(Ints.toByteArray(avatar.getService().value));
				Serialization.serializeSizedString(bytes, avatar.getName());
				Serialization.serializeSizedString(bytes, avatar.getIdentifier() == null ? "" : avatar.getIdentifier());
			}
			bytes.write(Longs.toByteArray(tx.getFee())); if (tx.getSignature() != null) bytes.write(tx.getSignature()); return bytes.toByteArray();
		} catch (IOException | ClassCastException e) { throw new TransformationException(e); }
	}
}
