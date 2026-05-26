package org.qortium.repository;

import org.qortium.arbitrary.misc.Service;
import org.qortium.data.rating.ResourceRatingData;
import org.qortium.data.rating.ResourceRatingSummaryData;

import java.util.List;

public interface ResourceRatingRepository {

	public ResourceRatingData getRating(Service service, String nameKey, String identifier, byte[] raterPublicKey) throws DataException;

	public void save(ResourceRatingData resourceRatingData) throws DataException;

	public void delete(Service service, String nameKey, String identifier, byte[] raterPublicKey) throws DataException;

	public ResourceRatingSummaryData getRatingSummary(Service service, String nameKey, String displayName, String identifier) throws DataException;

	public List<ResourceRatingSummaryData> getRatingSummaries(Service service, String nameKey, String identifier,
			Integer limit, Integer offset, Boolean reverse) throws DataException;

}
