package org.qortal.data.rating;

import org.qortal.arbitrary.misc.Service;
import org.qortal.rating.ResourceRating;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.ArrayList;
import java.util.List;

@XmlAccessorType(XmlAccessType.FIELD)
public class ResourceRatingSummaryData {

	private Service service;
	private String name;
	private String identifier;
	private int ratingCount;
	private long ratingTotal;
	private Long rawTotalWeight;
	private Long totalWeight;
	private Long derivedTotalWeight;
	private Long storedTotalWeight;
	private Double averageRating;
	private Double rawWeightedAverageRating;
	private Double weightedAverageRating;
	private Double derivedWeightedAverageRating;
	private Double storedWeightedAverageRating;
	private List<ResourceRatingDistributionData> ratingDistribution;

	protected ResourceRatingSummaryData() {
	}

	public ResourceRatingSummaryData(Service service, String name, String identifier, List<ResourceRatingDistributionData> ratingDistribution) {
		this.service = service;
		this.name = name;
		this.identifier = identifier;
		this.ratingDistribution = ratingDistribution == null ? new ArrayList<>() : ratingDistribution;
		this.recalculate();
	}

	public static ResourceRatingSummaryData empty(Service service, String name, String identifier) {
		List<ResourceRatingDistributionData> distribution = new ArrayList<>();
		for (int rating = ResourceRating.MIN_RATING; rating <= ResourceRating.MAX_RATING; ++rating)
			distribution.add(new ResourceRatingDistributionData(rating, 0, 0L, 0L));

		return new ResourceRatingSummaryData(service, name, identifier, distribution);
	}

	private void recalculate() {
		long rawWeightedRatingTotal = 0L;
		long weightedRatingTotal = 0L;
		long derivedWeightedRatingTotal = 0L;
		long storedWeightedRatingTotal = 0L;
		long rawWeight = 0L;
		long effectiveWeight = 0L;
		long derivedWeight = 0L;
		long storedWeight = 0L;
		int count = 0;
		long total = 0L;

		for (ResourceRatingDistributionData ratingData : this.ratingDistribution) {
			int rating = ratingData.getRating();
			int ratingCount = ratingData.getRatingCount();
			long rawRatingWeight = ratingData.getRawRatingWeight();
			long ratingWeight = ratingData.getRatingWeight();
			long derivedRatingWeight = ratingData.getDerivedRatingWeight();
			long storedRatingWeight = ratingData.getStoredRatingWeight();

			count += ratingCount;
			total += (long) rating * ratingCount;
			rawWeight += rawRatingWeight;
			effectiveWeight += ratingWeight;
			derivedWeight += derivedRatingWeight;
			storedWeight += storedRatingWeight;
			rawWeightedRatingTotal += (long) rating * rawRatingWeight;
			weightedRatingTotal += (long) rating * ratingWeight;
			derivedWeightedRatingTotal += (long) rating * derivedRatingWeight;
			storedWeightedRatingTotal += (long) rating * storedRatingWeight;
		}

		this.ratingCount = count;
		this.ratingTotal = total;
		this.rawTotalWeight = rawWeight;
		this.totalWeight = effectiveWeight;
		this.derivedTotalWeight = derivedWeight;
		this.storedTotalWeight = storedWeight;
		this.averageRating = count == 0 ? null : (double) total / count;
		this.rawWeightedAverageRating = rawWeight == 0 ? null : (double) rawWeightedRatingTotal / rawWeight;
		this.weightedAverageRating = effectiveWeight == 0 ? null : (double) weightedRatingTotal / effectiveWeight;
		this.derivedWeightedAverageRating = derivedWeight == 0 ? null : (double) derivedWeightedRatingTotal / derivedWeight;
		this.storedWeightedAverageRating = storedWeight == 0 ? null : (double) storedWeightedRatingTotal / storedWeight;
	}

	public Service getService() {
		return this.service;
	}

	public String getName() {
		return this.name;
	}

	public String getIdentifier() {
		return this.identifier;
	}

	public int getRatingCount() {
		return this.ratingCount;
	}

	public long getRatingTotal() {
		return this.ratingTotal;
	}

	public Long getRawTotalWeight() {
		return this.rawTotalWeight;
	}

	public Long getTotalWeight() {
		return this.totalWeight;
	}

	public Long getDerivedTotalWeight() {
		return this.derivedTotalWeight;
	}

	public Long getStoredTotalWeight() {
		return this.storedTotalWeight;
	}

	public Double getAverageRating() {
		return this.averageRating;
	}

	public Double getRawWeightedAverageRating() {
		return this.rawWeightedAverageRating;
	}

	public Double getWeightedAverageRating() {
		return this.weightedAverageRating;
	}

	public Double getDerivedWeightedAverageRating() {
		return this.derivedWeightedAverageRating;
	}

	public Double getStoredWeightedAverageRating() {
		return this.storedWeightedAverageRating;
	}

	public List<ResourceRatingDistributionData> getRatingDistribution() {
		return this.ratingDistribution;
	}

}
