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
	private AccountTrustStatus derivedTrustStatus;
	private int derivedTrustStatusValue;
	private int derivedTrustWeightPercent;
	private boolean mintingSeedMember;
	private List<CategoryTrust> categories;

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
		this(targetPublicKey, targetAddress, trustStatus, inboundRatings, outboundRatings, mutualPositiveCount,
				evaluatorImpacts, null, false, null);
	}

	public AccountTrustPreviewData(byte[] targetPublicKey, String targetAddress, AccountTrustStatus trustStatus,
			RatingCounts inboundRatings, RatingCounts outboundRatings, int mutualPositiveCount,
			List<EvaluatorImpact> evaluatorImpacts, AccountTrustStatus derivedTrustStatus, boolean mintingSeedMember,
			List<CategoryTrust> categories) {
		AccountTrustStatus storedTrustStatus = trustStatus == null ? AccountTrustStatus.UNVERIFIED : trustStatus;
		AccountTrustStatus effectiveDerivedTrustStatus = derivedTrustStatus == null ? AccountTrustStatus.UNVERIFIED : derivedTrustStatus;

		this.targetPublicKey = targetPublicKey;
		this.targetAddress = targetAddress;
		this.trustStatus = storedTrustStatus;
		this.trustStatusValue = storedTrustStatus.getValue();
		this.trustWeightPercent = storedTrustStatus.getVoteWeightPercent();
		this.derivedTrustStatus = effectiveDerivedTrustStatus;
		this.derivedTrustStatusValue = effectiveDerivedTrustStatus.getValue();
		this.derivedTrustWeightPercent = effectiveDerivedTrustStatus.getVoteWeightPercent();
		this.mintingSeedMember = mintingSeedMember;
		this.categories = categories == null ? new ArrayList<>() : categories;
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

	public AccountTrustStatus getDerivedTrustStatus() {
		return this.derivedTrustStatus;
	}

	public int getDerivedTrustStatusValue() {
		return this.derivedTrustStatusValue;
	}

	public int getDerivedTrustWeightPercent() {
		return this.derivedTrustWeightPercent;
	}

	public boolean isMintingSeedMember() {
		return this.mintingSeedMember;
	}

	public List<CategoryTrust> getCategories() {
		return this.categories;
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

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class CategoryTrust {
		private AccountRatingCategory category;
		private long score;
		private int level;
		private AccountTrustStatus mappedTrustStatus;
		private int mappedTrustStatusValue;
		private RatingCounts inboundRatings;
		private List<CategoryImpact> impacts;

		protected CategoryTrust() {
		}

		public CategoryTrust(AccountRatingCategory category, long score, int level, AccountTrustStatus mappedTrustStatus,
				RatingCounts inboundRatings, List<CategoryImpact> impacts) {
			AccountTrustStatus effectiveMappedStatus = mappedTrustStatus == null ? AccountTrustStatus.UNVERIFIED : mappedTrustStatus;

			this.category = category == null ? AccountRatingCategory.SUBJECT : category;
			this.score = score;
			this.level = level;
			this.mappedTrustStatus = effectiveMappedStatus;
			this.mappedTrustStatusValue = effectiveMappedStatus.getValue();
			this.inboundRatings = inboundRatings == null ? new RatingCounts() : inboundRatings;
			this.impacts = impacts == null ? new ArrayList<>() : impacts;
		}

		public AccountRatingCategory getCategory() {
			return this.category;
		}

		public long getScore() {
			return this.score;
		}

		public int getLevel() {
			return this.level;
		}

		public AccountTrustStatus getMappedTrustStatus() {
			return this.mappedTrustStatus;
		}

		public int getMappedTrustStatusValue() {
			return this.mappedTrustStatusValue;
		}

		public RatingCounts getInboundRatings() {
			return this.inboundRatings;
		}

		public List<CategoryImpact> getImpacts() {
			return this.impacts;
		}
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class CategoryImpact {
		private byte[] raterPublicKey;
		private String raterAddress;
		private int evaluatorLevel;
		private long evaluatorScore;
		private int rating;
		private String ratingDirection;
		private int ratingConfidence;
		private long impact;

		protected CategoryImpact() {
		}

		public CategoryImpact(byte[] raterPublicKey, String raterAddress, int evaluatorLevel, long evaluatorScore,
				int rating, long impact) {
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
}
