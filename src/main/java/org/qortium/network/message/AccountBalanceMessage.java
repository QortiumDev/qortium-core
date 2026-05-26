package org.qortium.network.message;

import com.google.common.primitives.Longs;
import org.qortium.data.account.AccountBalanceData;
import org.qortium.data.network.LiteDataAnchor;
import org.qortium.transform.Transformer;
import org.qortium.utils.Base58;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class AccountBalanceMessage extends Message {

	private static final int ADDRESS_LENGTH = Transformer.ADDRESS_LENGTH;

	private LiteDataResponseStatus status;
	private LiteDataAnchor anchor;
	private AccountBalanceData accountBalanceData;

	public AccountBalanceMessage(AccountBalanceData accountBalanceData, LiteDataAnchor anchor) {
		super(MessageType.ACCOUNT_BALANCE);

		if (accountBalanceData == null)
			throw new IllegalArgumentException("Account balance data is required for lite DATA response");

		this.status = LiteDataResponseStatus.DATA;
		this.anchor = anchor;
		this.accountBalanceData = accountBalanceData;

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			LiteDataMessageUtils.serializeStatusAndAnchor(bytes, this.status, this.anchor);

			// Send raw address instead of base58 encoded
			byte[] address = Base58.decode(accountBalanceData.getAddress());
			bytes.write(address);

			bytes.write(Longs.toByteArray(accountBalanceData.getAssetId()));

			bytes.write(Longs.toByteArray(accountBalanceData.getBalance()));

		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private AccountBalanceMessage(LiteDataResponseStatus status, LiteDataAnchor anchor) {
		super(MessageType.ACCOUNT_BALANCE);

		if (status != LiteDataResponseStatus.UNKNOWN)
			throw new IllegalArgumentException("Only UNKNOWN responses can omit account balance data");

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

	private AccountBalanceMessage(int id, LiteDataResponseStatus status, LiteDataAnchor anchor, AccountBalanceData accountBalanceData) {
		super(id, MessageType.ACCOUNT_BALANCE);

		this.status = status;
		this.anchor = anchor;
		this.accountBalanceData = accountBalanceData;
	}

	public static AccountBalanceMessage unknown(LiteDataAnchor anchor) {
		return new AccountBalanceMessage(LiteDataResponseStatus.UNKNOWN, anchor);
	}

	public LiteDataResponseStatus getStatus() {
		return this.status;
	}

	public LiteDataAnchor getAnchor() {
		return this.anchor;
	}

	public AccountBalanceData getAccountBalanceData() {
		return this.accountBalanceData;
	}


	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
		LiteDataResponseStatus status = LiteDataMessageUtils.deserializeStatus(byteBuffer);
		LiteDataAnchor anchor = LiteDataMessageUtils.deserializeAnchor(byteBuffer);

		if (status == LiteDataResponseStatus.UNKNOWN)
			return new AccountBalanceMessage(id, status, anchor, null);

		byte[] addressBytes = new byte[ADDRESS_LENGTH];
		byteBuffer.get(addressBytes);
		String address = Base58.encode(addressBytes);

		long assetId = byteBuffer.getLong();

		long balance = byteBuffer.getLong();

		AccountBalanceData accountBalanceData = new AccountBalanceData(address, assetId, balance);
		return new AccountBalanceMessage(id, status, anchor, accountBalanceData);
	}

	public AccountBalanceMessage cloneWithNewId(int newId) {
		AccountBalanceMessage clone = this.status == LiteDataResponseStatus.UNKNOWN
				? AccountBalanceMessage.unknown(this.anchor)
				: new AccountBalanceMessage(this.accountBalanceData, this.anchor);
		clone.setId(newId);
		return clone;
	}

}
