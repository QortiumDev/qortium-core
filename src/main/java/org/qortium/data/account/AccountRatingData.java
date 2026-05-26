package org.qortium.data.account;

import org.qortium.crypto.Crypto;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountRatingData {

	private byte[] targetPublicKey;
	private String targetAddress;
	private byte[] raterPublicKey;
	private String raterAddress;
	private AccountRatingCategory category;
	private int rating;
	private String ratingDirection;
	private int ratingConfidence;

	protected AccountRatingData() {
	}

	public AccountRatingData(byte[] targetPublicKey, byte[] raterPublicKey, int rating) {
		this(targetPublicKey, Crypto.toAddress(targetPublicKey), raterPublicKey, Crypto.toAddress(raterPublicKey),
				AccountRatingCategory.SUBJECT, rating);
	}

	public AccountRatingData(byte[] targetPublicKey, byte[] raterPublicKey, AccountRatingCategory category, int rating) {
		this(targetPublicKey, Crypto.toAddress(targetPublicKey), raterPublicKey, Crypto.toAddress(raterPublicKey),
				category, rating);
	}

	public AccountRatingData(byte[] targetPublicKey, String targetAddress, byte[] raterPublicKey, String raterAddress,
			int rating) {
		this(targetPublicKey, targetAddress, raterPublicKey, raterAddress, AccountRatingCategory.SUBJECT, rating);
	}

	public AccountRatingData(byte[] targetPublicKey, String targetAddress, byte[] raterPublicKey, String raterAddress,
			AccountRatingCategory category, int rating) {
		this.targetPublicKey = targetPublicKey;
		this.targetAddress = targetAddress;
		this.raterPublicKey = raterPublicKey;
		this.raterAddress = raterAddress;
		this.category = category == null ? AccountRatingCategory.SUBJECT : category;
		this.rating = rating;
		this.ratingDirection = AccountRating.getDirection(rating);
		this.ratingConfidence = AccountRating.getConfidence(rating);
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

	public AccountRatingCategory getCategory() {
		return this.category == null ? AccountRatingCategory.SUBJECT : this.category;
	}

	public int getCategoryValue() {
		return getCategory().value;
	}

	public int getRating() {
		return this.rating;
	}

	public int getRatingValue() {
		return this.rating;
	}

	public String getRatingDirection() {
		return this.ratingDirection;
	}

	public int getRatingConfidence() {
		return this.ratingConfidence;
	}
}
