package org.qortium.data.account;

import org.qortium.crypto.Crypto;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountRatingSummaryData {

	private byte[] targetPublicKey;
	private String targetAddress;
	private int positiveLowCount;
	private int positiveMediumCount;
	private int positiveHighCount;
	private int positiveVeryHighCount;
	private int negativeLowCount;
	private int negativeMediumCount;
	private int negativeHighCount;
	private int negativeVeryHighCount;
	private int positiveRatingCount;
	private int negativeRatingCount;
	private int totalRatingCount;

	protected AccountRatingSummaryData() {
	}

	public AccountRatingSummaryData(byte[] targetPublicKey) {
		this(targetPublicKey, Crypto.toAddress(targetPublicKey));
	}

	public AccountRatingSummaryData(byte[] targetPublicKey, String targetAddress) {
		this.targetPublicKey = targetPublicKey;
		this.targetAddress = targetAddress;
	}

	public void addRating(int rating, int count) {
		if (!AccountRating.isActive(rating) || count <= 0)
			return;

		if (AccountRating.isPositive(rating)) {
			addPositiveRating(AccountRating.getConfidence(rating), count);
		} else if (AccountRating.isNegative(rating)) {
			addNegativeRating(AccountRating.getConfidence(rating), count);
		}

		this.totalRatingCount = this.positiveRatingCount + this.negativeRatingCount;
	}

	private void addPositiveRating(int confidence, int count) {
		if (confidence == 1)
			this.positiveLowCount += count;
		else if (confidence == 2)
			this.positiveMediumCount += count;
		else if (confidence == 3)
			this.positiveHighCount += count;
		else if (confidence == 4)
			this.positiveVeryHighCount += count;

		this.positiveRatingCount += count;
	}

	private void addNegativeRating(int confidence, int count) {
		if (confidence == 1)
			this.negativeLowCount += count;
		else if (confidence == 2)
			this.negativeMediumCount += count;
		else if (confidence == 3)
			this.negativeHighCount += count;
		else if (confidence == 4)
			this.negativeVeryHighCount += count;

		this.negativeRatingCount += count;
	}

	public byte[] getTargetPublicKey() {
		return this.targetPublicKey;
	}

	public String getTargetAddress() {
		return this.targetAddress;
	}

	public int getPositiveLowCount() {
		return this.positiveLowCount;
	}

	public int getPositiveMediumCount() {
		return this.positiveMediumCount;
	}

	public int getPositiveHighCount() {
		return this.positiveHighCount;
	}

	public int getPositiveVeryHighCount() {
		return this.positiveVeryHighCount;
	}

	public int getNegativeLowCount() {
		return this.negativeLowCount;
	}

	public int getNegativeMediumCount() {
		return this.negativeMediumCount;
	}

	public int getNegativeHighCount() {
		return this.negativeHighCount;
	}

	public int getNegativeVeryHighCount() {
		return this.negativeVeryHighCount;
	}

	public int getPositiveRatingCount() {
		return this.positiveRatingCount;
	}

	public int getNegativeRatingCount() {
		return this.negativeRatingCount;
	}

	public int getTotalRatingCount() {
		return this.totalRatingCount;
	}
}
