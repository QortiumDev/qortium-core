package org.qortium.transform.transaction;

import com.google.common.base.Utf8;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortium.arbitrary.misc.Service;
import org.qortium.data.avatar.AvatarData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.SetAccountAvatarTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.naming.Name;
import org.qortium.transaction.ArbitraryTransaction;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.transform.TransformationException;
import org.qortium.utils.Serialization;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class SetAccountAvatarTransactionTransformer extends TransactionTransformer {

	// present flag; when present: service int + sized name + sized identifier (variable)
	private static final int PRESENT_LENGTH = BOOLEAN_LENGTH;
	protected static final TransactionLayout layout;

	static {
		layout = new TransactionLayout();
		layout.add("txType: " + TransactionType.SET_ACCOUNT_AVATAR.valueString, TransformationType.INT);
		layout.add("timestamp", TransformationType.TIMESTAMP);
		layout.add("transaction's groupID", TransformationType.INT);
		layout.add("account public key", TransformationType.PUBLIC_KEY);
		addMempowFeeNonceToLayout(layout, TransactionType.SET_ACCOUNT_AVATAR);
		layout.add("avatar present", TransformationType.BOOLEAN);
		layout.add("avatar service", TransformationType.INT);
		layout.add("avatar name length", TransformationType.INT);
		layout.add("avatar name", TransformationType.STRING);
		layout.add("avatar identifier length", TransformationType.INT);
		layout.add("avatar identifier", TransformationType.STRING);
		layout.add("fee", TransformationType.AMOUNT);
		layout.add("signature", TransformationType.SIGNATURE);
	}

	public static TransactionData fromByteBuffer(ByteBuffer byteBuffer) throws TransformationException {
		long timestamp = byteBuffer.getLong();
		int txGroupId = byteBuffer.getInt();
		byte[] ownerPublicKey = Serialization.deserializePublicKey(byteBuffer);
		Integer nonce = deserializeMempowFeeNonce(byteBuffer, TransactionType.SET_ACCOUNT_AVATAR);
		boolean present = byteBuffer.get() != 0;
		AvatarData avatar = null;
		if (present) {
			Service service = Service.valueOf(byteBuffer.getInt());
			String name = Serialization.deserializeSizedString(byteBuffer, Name.MAX_NAME_SIZE);
			String identifier = Serialization.deserializeSizedString(byteBuffer, ArbitraryTransaction.MAX_IDENTIFIER_LENGTH);
			avatar = new AvatarData(service, name, identifier);
		}
		long fee = byteBuffer.getLong();
		byte[] signature = new byte[SIGNATURE_LENGTH];
		byteBuffer.get(signature);
		return new SetAccountAvatarTransactionData(new BaseTransactionData(timestamp, txGroupId, ownerPublicKey, fee, nonce, signature), avatar);
	}

	public static int getDataLength(TransactionData transactionData) {
		SetAccountAvatarTransactionData tx = (SetAccountAvatarTransactionData) transactionData;
		int length = getBaseLength(transactionData) + PRESENT_LENGTH;
		AvatarData avatar = tx.getAvatar();
		if (avatar != null) {
			String identifier = avatar.getIdentifier() == null ? "" : avatar.getIdentifier();
			length += INT_LENGTH // service
					+ INT_LENGTH + Utf8.encodedLength(avatar.getName())
					+ INT_LENGTH + Utf8.encodedLength(identifier);
		}
		return length;
	}

	public static byte[] toBytes(TransactionData transactionData) throws TransformationException {
		try {
			SetAccountAvatarTransactionData tx = (SetAccountAvatarTransactionData) transactionData;
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			transformCommonBytes(transactionData, bytes);
			AvatarData avatar = tx.getAvatar();
			bytes.write(avatar == null ? 0 : 1);
			if (avatar != null) {
				bytes.write(Ints.toByteArray(avatar.getService().value));
				Serialization.serializeSizedString(bytes, avatar.getName());
				Serialization.serializeSizedString(bytes, avatar.getIdentifier() == null ? "" : avatar.getIdentifier());
			}
			bytes.write(Longs.toByteArray(tx.getFee()));
			if (tx.getSignature() != null) bytes.write(tx.getSignature());
			return bytes.toByteArray();
		} catch (IOException | ClassCastException e) {
			throw new TransformationException(e);
		}
	}
}
