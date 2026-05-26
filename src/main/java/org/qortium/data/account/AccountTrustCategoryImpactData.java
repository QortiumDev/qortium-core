package org.qortium.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
	private List<String> trustBranchKeys;
	private int trustBranchCount;

	protected AccountTrustCategoryImpactData() {
	}

	public AccountTrustCategoryImpactData(byte[] raterPublicKey, String raterAddress, int evaluatorLevel,
			long evaluatorScore, int rating, long impact) {
		this(raterPublicKey, raterAddress, evaluatorLevel, evaluatorScore, rating, impact, null);
	}

	public AccountTrustCategoryImpactData(byte[] raterPublicKey, String raterAddress, int evaluatorLevel,
			long evaluatorScore, int rating, long impact, List<String> trustBranchKeys) {
		this.raterPublicKey = raterPublicKey;
		this.raterAddress = raterAddress;
		this.evaluatorLevel = evaluatorLevel;
		this.evaluatorScore = evaluatorScore;
		this.rating = rating;
		this.ratingDirection = AccountRating.getDirection(rating);
		this.ratingConfidence = AccountRating.getConfidence(rating);
		this.impact = impact;
		this.trustBranchKeys = trustBranchKeys == null ? new ArrayList<>() : new ArrayList<>(trustBranchKeys);
		Collections.sort(this.trustBranchKeys);
		this.trustBranchCount = this.trustBranchKeys.size();
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

	public List<String> getTrustBranchKeys() {
		return this.trustBranchKeys;
	}

	public int getTrustBranchCount() {
		return this.trustBranchCount;
	}
}
