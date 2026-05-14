package org.qortal.data.rating;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class ResourceRatingDistributionData {

	private int rating;
	private int ratingCount;
	private long rawRatingWeight;
	private long ratingWeight;
	private long derivedRatingWeight;

	protected ResourceRatingDistributionData() {
	}

	public ResourceRatingDistributionData(int rating, int ratingCount, long rawRatingWeight, long ratingWeight) {
		this(rating, ratingCount, rawRatingWeight, ratingWeight, 0L);
	}

	public ResourceRatingDistributionData(int rating, int ratingCount, long rawRatingWeight, long ratingWeight,
			long derivedRatingWeight) {
		this.rating = rating;
		this.ratingCount = ratingCount;
		this.rawRatingWeight = rawRatingWeight;
		this.ratingWeight = ratingWeight;
		this.derivedRatingWeight = derivedRatingWeight;
	}

	public int getRating() {
		return this.rating;
	}

	public int getRatingCount() {
		return this.ratingCount;
	}

	public long getRawRatingWeight() {
		return this.rawRatingWeight;
	}

	public long getRatingWeight() {
		return this.ratingWeight;
	}

	public long getDerivedRatingWeight() {
		return this.derivedRatingWeight;
	}

}
