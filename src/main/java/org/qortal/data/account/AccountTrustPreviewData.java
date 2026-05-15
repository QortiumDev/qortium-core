package org.qortal.data.account;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class AccountTrustPreviewData {

	protected AccountTrustPreviewData() {
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

		public RatingCounts(int positiveLowCount, int positiveMediumCount, int positiveHighCount, int positiveVeryHighCount,
				int negativeLowCount, int negativeMediumCount, int negativeHighCount, int negativeVeryHighCount) {
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

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class CategoryTrust {
		private AccountRatingCategory category;
		private long score;
		private long levelScore;
		private long levelScoreCap;
		private int level;
		private AccountTrustStatus mappedTrustStatus;
		private int mappedTrustStatusValue;
		private RatingCounts inboundRatings;
		private List<CategoryImpact> impacts;

		protected CategoryTrust() {
		}

		public CategoryTrust(AccountRatingCategory category, long score, int level, AccountTrustStatus mappedTrustStatus,
				RatingCounts inboundRatings, List<CategoryImpact> impacts) {
			this(category, score, score, 0L, level, mappedTrustStatus, inboundRatings, impacts);
		}

		public CategoryTrust(AccountRatingCategory category, long score, long levelScore, long levelScoreCap, int level,
				AccountTrustStatus mappedTrustStatus, RatingCounts inboundRatings, List<CategoryImpact> impacts) {
			AccountTrustStatus effectiveMappedStatus = mappedTrustStatus == null ? AccountTrustStatus.UNVERIFIED : mappedTrustStatus;

			this.category = category == null ? AccountRatingCategory.SUBJECT : category;
			this.score = score;
			this.levelScore = levelScore;
			this.levelScoreCap = levelScoreCap;
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

		public long getLevelScore() {
			return this.levelScore;
		}

		public long getLevelScoreCap() {
			return this.levelScoreCap;
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
