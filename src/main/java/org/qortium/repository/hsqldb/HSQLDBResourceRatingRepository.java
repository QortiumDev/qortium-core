package org.qortium.repository.hsqldb;

import org.qortium.arbitrary.misc.Service;
import org.qortium.block.BlockChain;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.rating.ResourceRatingData;
import org.qortium.data.rating.ResourceRatingDistributionData;
import org.qortium.data.rating.ResourceRatingSummaryData;
import org.qortium.rating.ResourceRating;
import org.qortium.repository.DataException;
import org.qortium.repository.ResourceRatingRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class HSQLDBResourceRatingRepository implements ResourceRatingRepository {

	private static final String ACTIVE_TRUST_STATUS_SQL = HSQLDBTrustWeightSql.activeTrustStatusSql("ats");
	private static final String RAW_RATING_WEIGHT_SQL = "CAST(COALESCE(a.blocks_minted, 0) AS BIGINT)";

	protected HSQLDBRepository repository;

	public HSQLDBResourceRatingRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	@Override
	public ResourceRatingData getRating(Service service, String nameKey, String identifier, byte[] raterPublicKey) throws DataException {
		String sql = "SELECT name, rating FROM ResourceRatings "
				+ "WHERE service = ? AND name_key = ? AND identifier = ? AND rater = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, service.value, nameKey, identifier, raterPublicKey)) {
			if (resultSet == null)
				return null;

			String name = resultSet.getString(1);
			int rating = resultSet.getInt(2);

			return new ResourceRatingData(service, nameKey, name, identifier, raterPublicKey, rating);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch resource rating from repository", e);
		}
	}

	@Override
	public void save(ResourceRatingData resourceRatingData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("ResourceRatings");

		saveHelper.bind("service", resourceRatingData.getService().value)
				.bind("name_key", resourceRatingData.getNameKey())
				.bind("name", resourceRatingData.getName())
				.bind("identifier", resourceRatingData.getIdentifier())
				.bind("rater", resourceRatingData.getRaterPublicKey())
				.bind("rating", resourceRatingData.getRating());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save resource rating into repository", e);
		}
	}

	@Override
	public void delete(Service service, String nameKey, String identifier, byte[] raterPublicKey) throws DataException {
		try {
			this.repository.delete("ResourceRatings", "service = ? AND name_key = ? AND identifier = ? AND rater = ?",
					service.value, nameKey, identifier, raterPublicKey);
		} catch (SQLException e) {
			throw new DataException("Unable to delete resource rating from repository", e);
		}
	}

	@Override
	public ResourceRatingSummaryData getRatingSummary(Service service, String nameKey, String displayName, String identifier) throws DataException {
		int currentHeight = this.repository.getBlockRepository().getBlockchainHeight();
		int[] voteWeightPercents = BlockChain.getInstance().getAccountTrustStatusVoteWeightPercents(this.repository, currentHeight);
		String effectiveRatingWeightSql = HSQLDBTrustWeightSql.effectiveWeightSql(ACTIVE_TRUST_STATUS_SQL,
				RAW_RATING_WEIGHT_SQL, voteWeightPercents);
		String sql = "SELECT rr.rating, COUNT(rr.rater), "
				+ "COALESCE(SUM(" + RAW_RATING_WEIGHT_SQL + "), 0), "
				+ "COALESCE(SUM(" + effectiveRatingWeightSql + "), 0) "
				+ "FROM ResourceRatings rr "
				+ "LEFT JOIN Accounts a ON rr.rater = a.public_key "
				+ "LEFT JOIN AccountTrustDerivationSnapshots ats ON ats.account = a.account "
				+ "AND ats.category = " + AccountRatingCategory.SUBJECT.value + " "
				+ "WHERE rr.service = ? AND rr.name_key = ? AND rr.identifier = ? "
				+ "GROUP BY rr.rating "
				+ "ORDER BY rr.rating";

		List<ResourceRatingDistributionData> distribution = emptyDistribution();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, service.value, nameKey, identifier)) {
			if (resultSet != null) {
				do {
					int rating = resultSet.getInt(1);
					int ratingCount = resultSet.getInt(2);
					long rawRatingWeight = resultSet.getLong(3);
					long ratingWeight = resultSet.getLong(4);

					distribution.set(rating - ResourceRating.MIN_RATING,
							new ResourceRatingDistributionData(rating, ratingCount, rawRatingWeight, ratingWeight));
				} while (resultSet.next());
			}

			return new ResourceRatingSummaryData(service, displayName, identifier, distribution);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch resource rating summary from repository", e);
		}
	}

	@Override
	public List<ResourceRatingSummaryData> getRatingSummaries(Service service, String nameKey, String identifier,
			Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT service, name_key, MIN(name), identifier FROM ResourceRatings");

		List<String> whereClauses = new ArrayList<>();
		if (service != null) {
			whereClauses.add("service = ?");
			bindParams.add(service.value);
		}
		if (nameKey != null) {
			whereClauses.add("name_key = ?");
			bindParams.add(nameKey);
		}
		if (identifier != null) {
			whereClauses.add("identifier = ?");
			bindParams.add(identifier);
		}

		if (!whereClauses.isEmpty())
			sql.append(" WHERE ").append(String.join(" AND ", whereClauses));

		String sortDirection = Boolean.TRUE.equals(reverse) ? " DESC" : "";
		sql.append(" GROUP BY service, name_key, identifier ORDER BY service").append(sortDirection)
				.append(", name_key").append(sortDirection)
				.append(", identifier").append(sortDirection);

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<ResourceRatingSummaryData> summaries = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return summaries;

			do {
				Service rowService = Service.valueOf(resultSet.getInt(1));
				String rowNameKey = resultSet.getString(2);
				String rowDisplayName = resultSet.getString(3);
				String rowIdentifier = resultSet.getString(4);

				summaries.add(this.getRatingSummary(rowService, rowNameKey, rowDisplayName, rowIdentifier));
			} while (resultSet.next());

			return summaries;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch resource rating summaries from repository", e);
		}
	}

	private static List<ResourceRatingDistributionData> emptyDistribution() {
		List<ResourceRatingDistributionData> distribution = new ArrayList<>();

		for (int rating = ResourceRating.MIN_RATING; rating <= ResourceRating.MAX_RATING; ++rating)
			distribution.add(new ResourceRatingDistributionData(rating, 0, 0L, 0L));

		return distribution;
	}

}
