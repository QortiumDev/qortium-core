package org.qortal.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustCategoryImpactData {
	private byte[] raterPublicKey;
	private String raterAddress;
	private int evaluatorLevel;
	private long evaluatorScore;
	private int rating;
	private String ratingDirection;
	private int ratingConfidence;
	private long impact;

	protected AccountTrustCategoryImpactData() {
	}

	public AccountTrustCategoryImpactData(byte[] raterPublicKey, String raterAddress, int evaluatorLevel,
			long evaluatorScore, int rating, long impact) {
		this.raterPublicKey = raterPublicKey;
		this.raterAddress = raterAddress;
		this.evaluatorLevel = evaluatorLevel;
		this.evaluatorScore = evaluatorScore;
		this.rating = rating;
		this.ratingDirection = AccountRating.getDirection(rating);
		this.ratingConfidence = AccountRating.getConfidence(rating);
		this.impact = impact;
	}

	public byte[] getRaterPublicKey() {
		return this.raterPublicKey;
	}

	public String getRaterAddress() {
		return this.raterAddress;
	}

	public int getEvaluatorLevel() {
		return this.evaluatorLevel;
	}

	public long getEvaluatorScore() {
		return this.evaluatorScore;
	}

	public int getRating() {
		return this.rating;
	}

	public String getRatingDirection() {
		return this.ratingDirection;
	}

	public int getRatingConfidence() {
		return this.ratingConfidence;
	}

	public long getImpact() {
		return this.impact;
	}
}
