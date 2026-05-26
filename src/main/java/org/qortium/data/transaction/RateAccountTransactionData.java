package org.qortium.data.transaction;

import io.swagger.v3.oas.annotations.media.Schema;
import org.eclipse.persistence.oxm.annotations.XmlDiscriminatorValue;
import org.qortium.crypto.Crypto;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;

@XmlAccessorType(XmlAccessType.FIELD)
@Schema(allOf = { TransactionData.class })
@XmlDiscriminatorValue("RATE_ACCOUNT")
public class RateAccountTransactionData extends TransactionData {

	@Schema(description = "Rating creator's public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] raterPublicKey;
	@Schema(description = "Target account public key", example = "2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP")
	private byte[] targetPublicKey;
	@Schema(description = "Aura-style account rating category", defaultValue = "SUBJECT")
	private AccountRatingCategory category = AccountRatingCategory.SUBJECT;
	@Schema(description = "Aura-style account rating: -4 through -1 for negative confidence, 0 to remove an existing rating, 1 through 4 for positive confidence")
	private int rating;

	@XmlTransient
	@Schema(hidden = true)
	private Integer previousRating;
	@XmlTransient
	@Schema(hidden = true)
	private Integer ratingChangeHeight;

	protected RateAccountTransactionData() {
		super(TransactionType.RATE_ACCOUNT);
	}

	public void afterUnmarshal(Unmarshaller u, Object parent) {
		this.creatorPublicKey = this.raterPublicKey;
		if (this.category == null)
			this.category = AccountRatingCategory.SUBJECT;
	}

	public RateAccountTransactionData(BaseTransactionData baseTransactionData, byte[] targetPublicKey, AccountRatingCategory category,
			int rating, Integer previousRating) {
		super(TransactionType.RATE_ACCOUNT, baseTransactionData);

		this.raterPublicKey = baseTransactionData.creatorPublicKey;
		this.targetPublicKey = targetPublicKey;
		this.category = category == null ? AccountRatingCategory.SUBJECT : category;
		this.rating = rating;
		this.previousRating = previousRating;
	}

	public RateAccountTransactionData(BaseTransactionData baseTransactionData, byte[] targetPublicKey, int rating, Integer previousRating) {
		this(baseTransactionData, targetPublicKey, AccountRatingCategory.SUBJECT, rating, previousRating);
	}

	public RateAccountTransactionData(BaseTransactionData baseTransactionData, byte[] targetPublicKey, AccountRatingCategory category,
			int rating) {
		this(baseTransactionData, targetPublicKey, category, rating, null);
	}

	public RateAccountTransactionData(BaseTransactionData baseTransactionData, byte[] targetPublicKey, int rating) {
		this(baseTransactionData, targetPublicKey, AccountRatingCategory.SUBJECT, rating, null);
	}

	public byte[] getRaterPublicKey() {
		return this.raterPublicKey;
	}

	@XmlElement(name = "raterAddress")
	@Schema(description = "Rating creator's address")
	protected String getRaterAddress() {
		return this.raterPublicKey == null ? null : Crypto.toAddress(this.raterPublicKey);
	}

	public byte[] getTargetPublicKey() {
		return this.targetPublicKey;
	}

	@XmlElement(name = "targetAddress")
	@Schema(description = "Target account address")
	protected String getTargetAddress() {
		return this.targetPublicKey == null ? null : Crypto.toAddress(this.targetPublicKey);
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

	public Integer getPreviousRating() {
		return this.previousRating;
	}

	public void setPreviousRating(Integer previousRating) {
		this.previousRating = previousRating;
	}

	public Integer getRatingChangeHeight() {
		return this.ratingChangeHeight;
	}

	public void setRatingChangeHeight(Integer ratingChangeHeight) {
		this.ratingChangeHeight = ratingChangeHeight;
	}
}
