package org.qortium.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustRatingCountsData {
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

	public AccountTrustRatingCountsData() {
	}

	public AccountTrustRatingCountsData(int positiveLowCount, int positiveMediumCount, int positiveHighCount,
			int positiveVeryHighCount, int negativeLowCount, int negativeMediumCount, int negativeHighCount,
			int negativeVeryHighCount) {
		this.positiveLowCount = positiveLowCount;
		this.positiveMediumCount = positiveMediumCount;
		this.positiveHighCount = positiveHighCount;
		this.positiveVeryHighCount = positiveVeryHighCount;
		this.negativeLowCount = negativeLowCount;
		this.negativeMediumCount = negativeMediumCount;
		this.negativeHighCount = negativeHighCount;
		this.negativeVeryHighCount = negativeVeryHighCount;
		this.positiveRatingCount = positiveLowCount + positiveMediumCount + positiveHighCount + positiveVeryHighCount;
		this.negativeRatingCount = negativeLowCount + negativeMediumCount + negativeHighCount + negativeVeryHighCount;
		this.totalRatingCount = this.positiveRatingCount + this.negativeRatingCount;
	}

	public void addRating(int rating) {
		if (!AccountRating.isActive(rating))
			return;

		if (AccountRating.isPositive(rating)) {
			addPositiveRating(AccountRating.getConfidence(rating));
		} else if (AccountRating.isNegative(rating)) {
			addNegativeRating(AccountRating.getConfidence(rating));
		}

		this.totalRatingCount = this.positiveRatingCount + this.negativeRatingCount;
	}

	private void addPositiveRating(int confidence) {
		if (confidence == 1)
			++this.positiveLowCount;
		else if (confidence == 2)
			++this.positiveMediumCount;
		else if (confidence == 3)
			++this.positiveHighCount;
		else if (confidence == 4)
			++this.positiveVeryHighCount;

		++this.positiveRatingCount;
	}

	private void addNegativeRating(int confidence) {
		if (confidence == 1)
			++this.negativeLowCount;
		else if (confidence == 2)
			++this.negativeMediumCount;
		else if (confidence == 3)
			++this.negativeHighCount;
		else if (confidence == 4)
			++this.negativeVeryHighCount;

		++this.negativeRatingCount;
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
