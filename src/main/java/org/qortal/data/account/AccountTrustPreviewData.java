package org.qortal.data.account;

import org.qortal.crypto.Crypto;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustPreviewData {

	private byte[] targetPublicKey;
	private String targetAddress;
	private AccountTrustStatus trustStatus;
	private int trustStatusValue;
	private int trustWeightPercent;
	private RatingCounts inboundRatings;
	private RatingCounts outboundRatings;
	private int mutualPositiveCount;
	private int positiveScore;
	private int negativeScore;
	private int netScore;
	private List<EvaluatorImpact> evaluatorImpacts;

	protected AccountTrustPreviewData() {
	}

	public AccountTrustPreviewData(byte[] targetPublicKey, AccountTrustStatus trustStatus, RatingCounts inboundRatings,
			RatingCounts outboundRatings, int mutualPositiveCount, List<EvaluatorImpact> evaluatorImpacts) {
		this(targetPublicKey, Crypto.toAddress(targetPublicKey), trustStatus, inboundRatings, outboundRatings,
				mutualPositiveCount, evaluatorImpacts);
	}

	public AccountTrustPreviewData(byte[] targetPublicKey, String targetAddress, AccountTrustStatus trustStatus,
			RatingCounts inboundRatings, RatingCounts outboundRatings, int mutualPositiveCount,
			List<EvaluatorImpact> evaluatorImpacts) {
		AccountTrustStatus storedTrustStatus = trustStatus == null ? AccountTrustStatus.UNVERIFIED : trustStatus;

		this.targetPublicKey = targetPublicKey;
		this.targetAddress = targetAddress;
		this.trustStatus = storedTrustStatus;
		this.trustStatusValue = storedTrustStatus.getValue();
		this.trustWeightPercent = storedTrustStatus.getVoteWeightPercent();
		this.inboundRatings = inboundRatings == null ? new RatingCounts() : inboundRatings;
		this.outboundRatings = outboundRatings == null ? new RatingCounts() : outboundRatings;
		this.mutualPositiveCount = mutualPositiveCount;
		this.evaluatorImpacts = evaluatorImpacts == null ? new ArrayList<>() : evaluatorImpacts;

		long positiveScore = 0L;
		long negativeScore = 0L;

		for (EvaluatorImpact evaluatorImpact : this.evaluatorImpacts) {
			int impact = evaluatorImpact.getImpact();
			if (impact > 0)
				positiveScore = Math.min(Integer.MAX_VALUE, positiveScore + impact);
			else if (impact < 0)
				negativeScore = Math.min(Integer.MAX_VALUE, negativeScore - (long) impact);
		}

		this.positiveScore = (int) positiveScore;
		this.negativeScore = (int) negativeScore;
		this.netScore = saturatedInt(positiveScore - negativeScore);
	}

	private static int saturatedInt(long value) {
		if (value > Integer.MAX_VALUE)
			return Integer.MAX_VALUE;

		if (value < Integer.MIN_VALUE)
			return Integer.MIN_VALUE;

		return (int) value;
	}

	public byte[] getTargetPublicKey() {
		return this.targetPublicKey;
	}

	public String getTargetAddress() {
		return this.targetAddress;
	}

	public AccountTrustStatus getTrustStatus() {
		return this.trustStatus;
	}

	public int getTrustStatusValue() {
		return this.trustStatusValue;
	}

	public int getTrustWeightPercent() {
		return this.trustWeightPercent;
	}

	public RatingCounts getInboundRatings() {
		return this.inboundRatings;
	}

	public RatingCounts getOutboundRatings() {
		return this.outboundRatings;
	}

	public int getInboundTotalRatingCount() {
		return this.inboundRatings.getTotalRatingCount();
	}

	public int getOutboundTotalRatingCount() {
		return this.outboundRatings.getTotalRatingCount();
	}

	public int getMutualPositiveCount() {
		return this.mutualPositiveCount;
	}

	public int getPositiveScore() {
		return this.positiveScore;
	}

	public int getNegativeScore() {
		return this.negativeScore;
	}

	public int getNetScore() {
		return this.netScore;
	}

	public List<EvaluatorImpact> getEvaluatorImpacts() {
		return this.evaluatorImpacts;
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class RatingCounts {
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

		public RatingCounts() {
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

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class EvaluatorImpact {
		private byte[] raterPublicKey;
		private String raterAddress;
		private AccountTrustStatus trustStatus;
		private int trustStatusValue;
		private int trustWeightPercent;
		private int rawVoteWeight;
		private int effectiveVoteWeight;
		private int rating;
		private String ratingDirection;
		private int ratingConfidence;
		private int impact;

		protected EvaluatorImpact() {
		}

		public EvaluatorImpact(byte[] raterPublicKey, String raterAddress, AccountTrustStatus trustStatus,
				int rawVoteWeight, int effectiveVoteWeight, int rating, int impact) {
			AccountTrustStatus storedTrustStatus = trustStatus == null ? AccountTrustStatus.UNVERIFIED : trustStatus;

			this.raterPublicKey = raterPublicKey;
			this.raterAddress = raterAddress;
			this.trustStatus = storedTrustStatus;
			this.trustStatusValue = storedTrustStatus.getValue();
			this.trustWeightPercent = storedTrustStatus.getVoteWeightPercent();
			this.rawVoteWeight = rawVoteWeight;
			this.effectiveVoteWeight = effectiveVoteWeight;
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

		public AccountTrustStatus getTrustStatus() {
			return this.trustStatus;
		}

		public int getTrustStatusValue() {
			return this.trustStatusValue;
		}

		public int getTrustWeightPercent() {
			return this.trustWeightPercent;
		}

		public int getRawVoteWeight() {
			return this.rawVoteWeight;
		}

		public int getEffectiveVoteWeight() {
			return this.effectiveVoteWeight;
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

		public int getImpact() {
			return this.impact;
		}
	}
}
