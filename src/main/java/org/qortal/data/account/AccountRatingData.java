package org.qortal.data.account;

import org.qortal.crypto.Crypto;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountRatingData {

	private byte[] targetPublicKey;
	private String targetAddress;
	private byte[] raterPublicKey;
	private String raterAddress;
	private AccountRatingLevel ratingLevel;
	private int ratingValue;

	protected AccountRatingData() {
	}

	public AccountRatingData(byte[] targetPublicKey, byte[] raterPublicKey, AccountRatingLevel ratingLevel) {
		this(targetPublicKey, Crypto.toAddress(targetPublicKey), raterPublicKey, Crypto.toAddress(raterPublicKey), ratingLevel);
	}

	public AccountRatingData(byte[] targetPublicKey, String targetAddress, byte[] raterPublicKey, String raterAddress,
			AccountRatingLevel ratingLevel) {
		this.targetPublicKey = targetPublicKey;
		this.targetAddress = targetAddress;
		this.raterPublicKey = raterPublicKey;
		this.raterAddress = raterAddress;
		this.ratingLevel = ratingLevel;
		this.ratingValue = ratingLevel == null ? AccountRatingLevel.UNKNOWN.getValue() : ratingLevel.getValue();
	}

	public byte[] getTargetPublicKey() {
		return this.targetPublicKey;
	}

	public String getTargetAddress() {
		return this.targetAddress;
	}

	public byte[] getRaterPublicKey() {
		return this.raterPublicKey;
	}

	public String getRaterAddress() {
		return this.raterAddress;
	}

	public AccountRatingLevel getRatingLevel() {
		return this.ratingLevel;
	}

	public int getRatingValue() {
		return this.ratingValue;
	}
}
