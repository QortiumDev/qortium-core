package org.qortium.network.message;

import com.google.common.primitives.Ints;
import org.qortium.data.account.AccountData;
import org.qortium.data.network.LiteDataAnchor;
import org.qortium.transform.Transformer;
import org.qortium.utils.Base58;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class AccountMessage extends Message {

	private static final int ADDRESS_LENGTH = Transformer.ADDRESS_LENGTH;
	private static final int PUBLIC_KEY_LENGTH = Transformer.PUBLIC_KEY_LENGTH;

	private LiteDataResponseStatus status;
	private LiteDataAnchor anchor;
	private AccountData accountData;

	public AccountMessage(AccountData accountData, LiteDataAnchor anchor) {
		super(MessageType.ACCOUNT);

		if (accountData == null)
			throw new IllegalArgumentException("Account data is required for lite DATA response");

		this.status = LiteDataResponseStatus.DATA;
		this.anchor = anchor;
		this.accountData = accountData;

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			LiteDataMessageUtils.serializeStatusAndAnchor(bytes, this.status, this.anchor);

			// Send raw address instead of base58 encoded
			byte[] address = Base58.decode(accountData.getAddress());
			bytes.write(address);

			bytes.write(accountData.getPublicKey());

			bytes.write(Ints.toByteArray(accountData.getDefaultGroupId()));

			bytes.write(Ints.toByteArray(accountData.getLevel()));

			bytes.write(Ints.toByteArray(accountData.getBlocksMinted()));

		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private AccountMessage(LiteDataResponseStatus status, LiteDataAnchor anchor) {
		super(MessageType.ACCOUNT);

		if (status != LiteDataResponseStatus.UNKNOWN)
			throw new IllegalArgumentException("Only UNKNOWN responses can omit account data");

		this.status = status;
		this.anchor = anchor;

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			LiteDataMessageUtils.serializeStatusAndAnchor(bytes, this.status, this.anchor);
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private AccountMessage(int id, LiteDataResponseStatus status, LiteDataAnchor anchor, AccountData accountData) {
		super(id, MessageType.ACCOUNT);

		this.status = status;
		this.anchor = anchor;
		this.accountData = accountData;
	}

	public static AccountMessage unknown(LiteDataAnchor anchor) {
		return new AccountMessage(LiteDataResponseStatus.UNKNOWN, anchor);
	}

	public LiteDataResponseStatus getStatus() {
		return this.status;
	}

	public LiteDataAnchor getAnchor() {
		return this.anchor;
	}

	public AccountData getAccountData() {
		return this.accountData;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
		LiteDataResponseStatus status = LiteDataMessageUtils.deserializeStatus(byteBuffer);
		LiteDataAnchor anchor = LiteDataMessageUtils.deserializeAnchor(byteBuffer);

		if (status == LiteDataResponseStatus.UNKNOWN)
			return new AccountMessage(id, status, anchor, null);

		byte[] addressBytes = new byte[ADDRESS_LENGTH];
		byteBuffer.get(addressBytes);
		String address = Base58.encode(addressBytes);

		byte[] publicKey = new byte[PUBLIC_KEY_LENGTH];
		byteBuffer.get(publicKey);

		int defaultGroupId = byteBuffer.getInt();

		int level = byteBuffer.getInt();

		int blocksMinted = byteBuffer.getInt();

		AccountData accountData = new AccountData(address, publicKey, defaultGroupId, level, blocksMinted);
		return new AccountMessage(id, status, anchor, accountData);
	}

	public AccountMessage cloneWithNewId(int newId) {
		AccountMessage clone = this.status == LiteDataResponseStatus.UNKNOWN
				? AccountMessage.unknown(this.anchor)
				: new AccountMessage(this.accountData, this.anchor);
		clone.setId(newId);
		return clone;
	}

}
