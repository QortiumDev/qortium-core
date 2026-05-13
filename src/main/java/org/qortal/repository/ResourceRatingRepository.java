package org.qortal.repository;

import org.qortal.arbitrary.misc.Service;
import org.qortal.data.rating.ResourceRatingData;
import org.qortal.data.rating.ResourceRatingSummaryData;

import java.util.List;

public interface ResourceRatingRepository {

	public ResourceRatingData getRating(Service service, String nameKey, String identifier, byte[] raterPublicKey) throws DataException;

	public void save(ResourceRatingData resourceRatingData) throws DataException;

	public void delete(Service service, String nameKey, String identifier, byte[] raterPublicKey) throws DataException;

	public ResourceRatingSummaryData getRatingSummary(Service service, String nameKey, String displayName, String identifier) throws DataException;

	public List<ResourceRatingSummaryData> getRatingSummaries(Service service, String nameKey, String identifier,
			Integer limit, Integer offset, Boolean reverse) throws DataException;

}
