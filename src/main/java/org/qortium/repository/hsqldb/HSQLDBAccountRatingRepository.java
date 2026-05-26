package org.qortium.repository.hsqldb;

import org.qortium.account.AccountTrustPolicy;
import org.qortium.block.BlockChain;
import org.qortium.data.account.AccountRatingData;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.data.account.AccountRatingSummaryData;
import org.qortium.data.account.AccountTrustDerivationData;
import org.qortium.data.account.AccountTrustCategoryData;
import org.qortium.data.account.AccountTrustRatingCountsData;
import org.qortium.data.account.AccountTrustSnapshotData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.data.account.AccountTrustStatusChangeData;
import org.qortium.data.account.AccountTrustSummaryData;
import org.qortium.repository.AccountRatingRepository;
import org.qortium.repository.DataException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HSQLDBAccountRatingRepository implements AccountRatingRepository {

	private static final String TRUST_SNAPSHOT_SELECT_COLUMNS = "account_public_key, account, category, score, "
			+ "level_score, level_score_cap, level, mapped_trust_status, minting_seed_member, "
			+ "positive_low_count, positive_medium_count, positive_high_count, positive_very_high_count, "
			+ "negative_low_count, negative_medium_count, negative_high_count, negative_very_high_count, "
			+ "snapshot_height, snapshot_timestamp";
	private static final String TRUST_SUMMARY_BLOCKS_MINTED_SQL = "CAST(COALESCE(a.blocks_minted, 0) AS BIGINT)";

	protected HSQLDBRepository repository;

	public HSQLDBAccountRatingRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	@Override
	public AccountRatingData getRating(byte[] targetPublicKey, byte[] raterPublicKey) throws DataException {
		return getRating(targetPublicKey, raterPublicKey, AccountRatingCategory.SUBJECT);
	}

	@Override
	public AccountRatingData getRating(byte[] targetPublicKey, byte[] raterPublicKey, AccountRatingCategory category) throws DataException {
		AccountRatingCategory effectiveCategory = defaultCategory(category);
		String sql = "SELECT target_account, rater_account, rating FROM AccountRatings WHERE target = ? AND rater = ? AND category = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, targetPublicKey, raterPublicKey, effectiveCategory.value)) {
			if (resultSet == null)
				return null;

			String targetAddress = resultSet.getString(1);
			String raterAddress = resultSet.getString(2);
			int rating = resultSet.getInt(3);

			return new AccountRatingData(targetPublicKey, targetAddress, raterPublicKey, raterAddress, effectiveCategory, rating);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account rating from repository", e);
		}
	}

	@Override
	public void save(AccountRatingData accountRatingData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("AccountRatings");

		saveHelper.bind("target", accountRatingData.getTargetPublicKey())
				.bind("target_account", accountRatingData.getTargetAddress())
				.bind("rater", accountRatingData.getRaterPublicKey())
				.bind("rater_account", accountRatingData.getRaterAddress())
				.bind("category", accountRatingData.getCategoryValue())
				.bind("rating", accountRatingData.getRatingValue());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account rating into repository", e);
		}
	}

	@Override
	public void delete(byte[] targetPublicKey, byte[] raterPublicKey) throws DataException {
		delete(targetPublicKey, raterPublicKey, AccountRatingCategory.SUBJECT);
	}

	@Override
	public void delete(byte[] targetPublicKey, byte[] raterPublicKey, AccountRatingCategory category) throws DataException {
		try {
			this.repository.delete("AccountRatings", "target = ? AND rater = ? AND category = ?", targetPublicKey, raterPublicKey,
					defaultCategory(category).value);
		} catch (SQLException e) {
			throw new DataException("Unable to delete account rating from repository", e);
		}
	}

	@Override
	public AccountRatingSummaryData getRatingSummary(byte[] targetPublicKey, String targetAddress) throws DataException {
		return getRatingSummary(targetPublicKey, targetAddress, AccountRatingCategory.SUBJECT);
	}

	@Override
	public AccountRatingSummaryData getRatingSummary(byte[] targetPublicKey, String targetAddress, AccountRatingCategory category) throws DataException {
		AccountRatingCategory effectiveCategory = defaultCategory(category);
		String sql = "SELECT rating, COUNT(*) FROM AccountRatings WHERE target = ? AND category = ? GROUP BY rating";

		AccountRatingSummaryData summary = new AccountRatingSummaryData(targetPublicKey, targetAddress);

		try (ResultSet resultSet = this.repository.checkedExecute(sql, targetPublicKey, effectiveCategory.value)) {
			if (resultSet != null) {
				do {
					int rating = resultSet.getInt(1);
					int count = resultSet.getInt(2);
					summary.addRating(rating, count);
				} while (resultSet.next());
			}

			return summary;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account rating summary from repository", e);
		}
	}

	@Override
	public List<AccountRatingData> getRatings(byte[] targetPublicKey, byte[] raterPublicKey,
			Integer limit, Integer offset, Boolean reverse) throws DataException {
		return getRatings(targetPublicKey, raterPublicKey, null, limit, offset, reverse);
	}

	@Override
	public List<AccountRatingData> getRatings(byte[] targetPublicKey, byte[] raterPublicKey, AccountRatingCategory category,
			Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(256);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT target, target_account, rater, rater_account, category, rating FROM AccountRatings");

		List<String> whereClauses = new ArrayList<>();
		if (targetPublicKey != null) {
			whereClauses.add("target = ?");
			bindParams.add(targetPublicKey);
		}
		if (raterPublicKey != null) {
			whereClauses.add("rater = ?");
			bindParams.add(raterPublicKey);
		}
		if (category != null) {
			whereClauses.add("category = ?");
			bindParams.add(category.value);
		}

		if (!whereClauses.isEmpty())
			sql.append(" WHERE ").append(String.join(" AND ", whereClauses));

		String sortDirection = Boolean.TRUE.equals(reverse) ? " DESC" : "";
		sql.append(" ORDER BY target_account").append(sortDirection)
				.append(", rater_account").append(sortDirection)
				.append(", category").append(sortDirection);

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<AccountRatingData> accountRatings = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return accountRatings;

			do {
				byte[] rowTargetPublicKey = resultSet.getBytes(1);
				String rowTargetAddress = resultSet.getString(2);
				byte[] rowRaterPublicKey = resultSet.getBytes(3);
				String rowRaterAddress = resultSet.getString(4);
				AccountRatingCategory rowCategory = AccountRatingCategory.valueOf(resultSet.getInt(5));
				int rating = resultSet.getInt(6);

				accountRatings.add(new AccountRatingData(rowTargetPublicKey, rowTargetAddress, rowRaterPublicKey, rowRaterAddress,
						defaultCategory(rowCategory), rating));
			} while (resultSet.next());

			return accountRatings;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account ratings from repository", e);
		}
	}

	@Override
	public void replaceTrustDerivationSnapshots(List<AccountTrustDerivationData> derivedAccounts, int snapshotHeight,
			long snapshotTimestamp) throws DataException {
		TrustSnapshotHealth currentMetadata = getTrustSnapshotHealth();
		boolean rollbackRefresh = currentMetadata.snapshotHeight != null && snapshotHeight < currentMetadata.snapshotHeight;
		Map<String, AccountTrustSnapshotData> previousSnapshotsByChangeKey = rollbackRefresh
				? Collections.emptyMap()
				: getTrustSnapshotsByChangeKey();

		try {
			if (rollbackRefresh)
				this.repository.delete("AccountTrustStatusChanges", "snapshot_height > ?", snapshotHeight);
			else
				this.repository.delete("AccountTrustStatusChanges", "snapshot_height >= ?", snapshotHeight);

			this.repository.delete("AccountTrustDerivationSnapshots");
		} catch (SQLException e) {
			throw new DataException("Unable to delete account trust derivation snapshot history from repository", e);
		}

		if (derivedAccounts == null || derivedAccounts.isEmpty())
			return;

		try {
			for (AccountTrustDerivationData derivedAccount : derivedAccounts) {
				for (AccountTrustCategoryData categoryTrust : derivedAccount.getCategories())
					saveTrustDerivationSnapshot(derivedAccount, categoryTrust, snapshotHeight, snapshotTimestamp);
			}

			if (!rollbackRefresh)
				saveTrustStatusChanges(previousSnapshotsByChangeKey, derivedAccounts, snapshotHeight, snapshotTimestamp);
		} catch (SQLException e) {
			throw new DataException("Unable to save account trust derivation snapshot into repository", e);
		}
	}

	private void saveTrustDerivationSnapshot(AccountTrustDerivationData derivedAccount,
			AccountTrustCategoryData categoryTrust, int snapshotHeight, long snapshotTimestamp)
			throws SQLException {
		AccountTrustRatingCountsData inboundRatings = categoryTrust.getInboundRatings();
		HSQLDBSaver saveHelper = new HSQLDBSaver("AccountTrustDerivationSnapshots");

		saveHelper.bind("account", derivedAccount.getAccountAddress())
				.bind("account_public_key", derivedAccount.getAccountPublicKey())
				.bind("category", categoryTrust.getCategory().value)
				.bind("score", categoryTrust.getScore())
				.bind("level_score", categoryTrust.getLevelScore())
				.bind("level_score_cap", categoryTrust.getLevelScoreCap())
				.bind("level", categoryTrust.getLevel())
				.bind("mapped_trust_status", categoryTrust.getMappedTrustStatusValue())
				.bind("minting_seed_member", derivedAccount.isMintingSeedMember())
				.bind("positive_low_count", inboundRatings.getPositiveLowCount())
				.bind("positive_medium_count", inboundRatings.getPositiveMediumCount())
				.bind("positive_high_count", inboundRatings.getPositiveHighCount())
				.bind("positive_very_high_count", inboundRatings.getPositiveVeryHighCount())
				.bind("negative_low_count", inboundRatings.getNegativeLowCount())
				.bind("negative_medium_count", inboundRatings.getNegativeMediumCount())
				.bind("negative_high_count", inboundRatings.getNegativeHighCount())
				.bind("negative_very_high_count", inboundRatings.getNegativeVeryHighCount())
				.bind("snapshot_height", snapshotHeight)
				.bind("snapshot_timestamp", snapshotTimestamp);

		saveHelper.execute(this.repository);
	}

	private Map<String, AccountTrustSnapshotData> getTrustSnapshotsByChangeKey() throws DataException {
		List<AccountTrustSnapshotData> snapshots = getTrustDerivationSnapshots(null, null, null);
		Map<String, AccountTrustSnapshotData> snapshotsByChangeKey = new HashMap<>();

		for (AccountTrustSnapshotData snapshot : snapshots)
			snapshotsByChangeKey.put(trustStatusChangeKey(snapshot.getAccountAddress(), snapshot.getCategory()), snapshot);

		return snapshotsByChangeKey;
	}

	private void saveTrustStatusChanges(Map<String, AccountTrustSnapshotData> previousSnapshotsByChangeKey,
			List<AccountTrustDerivationData> derivedAccounts, int snapshotHeight, long snapshotTimestamp)
			throws SQLException {
		for (AccountTrustDerivationData derivedAccount : derivedAccounts) {
			for (AccountTrustCategoryData categoryTrust : derivedAccount.getCategories()) {
				AccountTrustSnapshotData previousSnapshot = previousSnapshotsByChangeKey.get(
						trustStatusChangeKey(derivedAccount.getAccountAddress(), categoryTrust.getCategory()));
				if (previousSnapshot == null)
					continue;

				if (previousSnapshot.getLevel() == categoryTrust.getLevel()
						&& previousSnapshot.getMappedTrustStatus() == categoryTrust.getMappedTrustStatus())
					continue;

				saveTrustStatusChange(previousSnapshot, derivedAccount, categoryTrust, snapshotHeight, snapshotTimestamp);
			}
		}
	}

	private void saveTrustStatusChange(AccountTrustSnapshotData previousSnapshot,
			AccountTrustDerivationData derivedAccount, AccountTrustCategoryData categoryTrust, int snapshotHeight,
			long snapshotTimestamp) throws SQLException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("AccountTrustStatusChanges");

		saveHelper.bind("account", derivedAccount.getAccountAddress())
				.bind("account_public_key", derivedAccount.getAccountPublicKey())
				.bind("category", categoryTrust.getCategory().value)
				.bind("previous_level", previousSnapshot.getLevel())
				.bind("new_level", categoryTrust.getLevel())
				.bind("previous_mapped_trust_status", previousSnapshot.getMappedTrustStatusValue())
				.bind("new_mapped_trust_status", categoryTrust.getMappedTrustStatusValue())
				.bind("previous_score", previousSnapshot.getScore())
				.bind("new_score", categoryTrust.getScore())
				.bind("previous_level_score", previousSnapshot.getLevelScore())
				.bind("new_level_score", categoryTrust.getLevelScore())
				.bind("previous_minting_seed_member", previousSnapshot.isMintingSeedMember())
				.bind("new_minting_seed_member", derivedAccount.isMintingSeedMember())
				.bind("previous_snapshot_height", previousSnapshot.getSnapshotHeight())
				.bind("previous_snapshot_timestamp", previousSnapshot.getSnapshotTimestamp())
				.bind("snapshot_height", snapshotHeight)
				.bind("snapshot_timestamp", snapshotTimestamp);

		saveHelper.execute(this.repository);
	}

	@Override
	public AccountTrustSummaryData getTrustSummary(AccountRatingCategory activeCategory) throws DataException {
		AccountRatingCategory effectiveActiveCategory = defaultCategory(activeCategory);
		TrustSnapshotHealth snapshotHealth = getTrustSnapshotHealth();
		List<AccountTrustSummaryData.RatingCategorySummary> ratingCategorySummaries = getTrustRatingCategorySummaries();
		TrustStatusChangeHealth statusChangeHealth = getTrustStatusChangeHealth();

		return new AccountTrustSummaryData(effectiveActiveCategory, snapshotHealth.snapshotHeight,
				snapshotHealth.snapshotTimestamp, snapshotHealth.snapshotAccountCount, snapshotHealth.snapshotRowCount,
				snapshotHealth.expectedSnapshotRowCount, snapshotHealth.snapshotsComplete,
				getActiveRatingCount(ratingCategorySummaries), statusChangeHealth.changeCount,
				statusChangeHealth.latestChangeHeight, statusChangeHealth.latestChangeTimestamp,
				getTrustStatusSummaries(effectiveActiveCategory), getTrustCategorySummaries(), ratingCategorySummaries);
	}

	private TrustSnapshotHealth getTrustSnapshotHealth() throws DataException {
		String sql = "SELECT MAX(snapshot_height), MAX(snapshot_timestamp), COUNT(DISTINCT account), COUNT(*) "
				+ "FROM AccountTrustDerivationSnapshots";

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return new TrustSnapshotHealth(null, null, 0L, 0L);

			int snapshotHeight = resultSet.getInt(1);
			Integer snapshotHeightValue = resultSet.wasNull() ? null : snapshotHeight;
			long snapshotTimestamp = resultSet.getLong(2);
			Long snapshotTimestampValue = resultSet.wasNull() ? null : snapshotTimestamp;
			long snapshotAccountCount = resultSet.getLong(3);
			long snapshotRowCount = resultSet.getLong(4);

			return new TrustSnapshotHealth(snapshotHeightValue, snapshotTimestampValue, snapshotAccountCount,
					snapshotRowCount);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account trust snapshot health from repository", e);
		}
	}

	private List<AccountTrustSummaryData.StatusSummary> getTrustStatusSummaries(AccountRatingCategory activeCategory)
			throws DataException {
		Map<AccountTrustStatus, TrustStatusSummaryValues> summaryValuesByStatus = new EnumMap<>(AccountTrustStatus.class);
		for (AccountTrustStatus status : AccountTrustStatus.values())
			summaryValuesByStatus.put(status, new TrustStatusSummaryValues());

		int currentHeight = this.repository.getBlockRepository().getBlockchainHeight();
		int[] voteWeightPercents = BlockChain.getInstance().getAccountTrustStatusVoteWeightPercents(this.repository, currentHeight);
		String sql = "SELECT ats.mapped_trust_status, COUNT(*), "
				+ "COALESCE(SUM(CASE WHEN ats.minting_seed_member THEN 1 ELSE 0 END), 0), "
				+ "COALESCE(SUM(" + TRUST_SUMMARY_BLOCKS_MINTED_SQL + "), 0), "
				+ "COALESCE(SUM(" + trustSummaryEffectiveVoteWeightSql(voteWeightPercents) + "), 0) "
				+ "FROM AccountTrustDerivationSnapshots ats "
				+ "LEFT JOIN Accounts a ON a.account = ats.account "
				+ "WHERE ats.category = ? "
				+ "GROUP BY ats.mapped_trust_status";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, defaultCategory(activeCategory).value)) {
			if (resultSet != null) {
				do {
					AccountTrustStatus status = AccountTrustStatus.valueOf(resultSet.getInt(1));
					TrustStatusSummaryValues summaryValues = summaryValuesByStatus.get(status);
					summaryValues.accountCount = resultSet.getLong(2);
					summaryValues.seedMemberCount = resultSet.getLong(3);
					summaryValues.rawVoteWeight = resultSet.getLong(4);
					summaryValues.effectiveVoteWeight = resultSet.getLong(5);
				} while (resultSet.next());
			}
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account trust status summary from repository", e);
		}

		List<AccountTrustSummaryData.StatusSummary> statusSummaries = new ArrayList<>();
		for (AccountTrustStatus status : AccountTrustStatus.values()) {
			TrustStatusSummaryValues summaryValues = summaryValuesByStatus.get(status);
			statusSummaries.add(new AccountTrustSummaryData.StatusSummary(status,
					AccountTrustPolicy.getVoteWeightPercent(voteWeightPercents, status),
					summaryValues.accountCount, summaryValues.seedMemberCount, summaryValues.rawVoteWeight,
					summaryValues.effectiveVoteWeight));
		}

		return statusSummaries;
	}

	private List<AccountTrustSummaryData.CategorySummary> getTrustCategorySummaries() throws DataException {
		Map<AccountRatingCategory, Map<AccountTrustStatus, Long>> categoryStatusCounts = new EnumMap<>(AccountRatingCategory.class);
		for (AccountRatingCategory category : AccountRatingCategory.values()) {
			Map<AccountTrustStatus, Long> statusCounts = new EnumMap<>(AccountTrustStatus.class);
			for (AccountTrustStatus status : AccountTrustStatus.values())
				statusCounts.put(status, 0L);
			categoryStatusCounts.put(category, statusCounts);
		}

		String sql = "SELECT category, mapped_trust_status, COUNT(*) FROM AccountTrustDerivationSnapshots "
				+ "GROUP BY category, mapped_trust_status";

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet != null) {
				do {
					AccountRatingCategory category = AccountRatingCategory.valueOf(resultSet.getInt(1));
					AccountTrustStatus status = AccountTrustStatus.valueOf(resultSet.getInt(2));
					Map<AccountTrustStatus, Long> statusCounts = categoryStatusCounts.get(defaultCategory(category));
					statusCounts.put(status, resultSet.getLong(3));
				} while (resultSet.next());
			}
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account trust category summary from repository", e);
		}

		List<AccountTrustSummaryData.CategorySummary> categorySummaries = new ArrayList<>();
		for (AccountRatingCategory category : AccountRatingCategory.values()) {
			List<AccountTrustSummaryData.StatusCount> statusCounts = new ArrayList<>();
			for (AccountTrustStatus status : AccountTrustStatus.values())
				statusCounts.add(new AccountTrustSummaryData.StatusCount(status,
						categoryStatusCounts.get(category).get(status)));

			categorySummaries.add(new AccountTrustSummaryData.CategorySummary(category, statusCounts));
		}

		return categorySummaries;
	}

	private List<AccountTrustSummaryData.RatingCategorySummary> getTrustRatingCategorySummaries() throws DataException {
		Map<AccountRatingCategory, RatingCategorySummaryValues> summaryValuesByCategory =
				new EnumMap<>(AccountRatingCategory.class);
		for (AccountRatingCategory category : AccountRatingCategory.values())
			summaryValuesByCategory.put(category, new RatingCategorySummaryValues());

		String sql = "SELECT category, COUNT(*), "
				+ "COALESCE(SUM(CASE WHEN rating > 0 THEN 1 ELSE 0 END), 0), "
				+ "COALESCE(SUM(CASE WHEN rating < 0 THEN 1 ELSE 0 END), 0) "
				+ "FROM AccountRatings GROUP BY category";

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet != null) {
				do {
					AccountRatingCategory category = AccountRatingCategory.valueOf(resultSet.getInt(1));
					RatingCategorySummaryValues summaryValues = summaryValuesByCategory.get(defaultCategory(category));
					summaryValues.ratingCount = resultSet.getLong(2);
					summaryValues.positiveRatingCount = resultSet.getLong(3);
					summaryValues.negativeRatingCount = resultSet.getLong(4);
				} while (resultSet.next());
			}
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account rating category summary from repository", e);
		}

		List<AccountTrustSummaryData.RatingCategorySummary> ratingCategorySummaries = new ArrayList<>();
		for (AccountRatingCategory category : AccountRatingCategory.values()) {
			RatingCategorySummaryValues summaryValues = summaryValuesByCategory.get(category);
			ratingCategorySummaries.add(new AccountTrustSummaryData.RatingCategorySummary(category,
					summaryValues.ratingCount, summaryValues.positiveRatingCount, summaryValues.negativeRatingCount));
		}

		return ratingCategorySummaries;
	}

	private long getActiveRatingCount(List<AccountTrustSummaryData.RatingCategorySummary> ratingCategorySummaries) {
		long activeRatingCount = 0L;
		for (AccountTrustSummaryData.RatingCategorySummary ratingCategorySummary : ratingCategorySummaries)
			activeRatingCount += ratingCategorySummary.getRatingCount();

		return activeRatingCount;
	}

	private TrustStatusChangeHealth getTrustStatusChangeHealth() throws DataException {
		String sql = "SELECT COUNT(*), MAX(snapshot_height), MAX(snapshot_timestamp) FROM AccountTrustStatusChanges";

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return new TrustStatusChangeHealth(0L, null, null);

			long changeCount = resultSet.getLong(1);
			int latestChangeHeight = resultSet.getInt(2);
			Integer latestChangeHeightValue = resultSet.wasNull() ? null : latestChangeHeight;
			long latestChangeTimestamp = resultSet.getLong(3);
			Long latestChangeTimestampValue = resultSet.wasNull() ? null : latestChangeTimestamp;

			return new TrustStatusChangeHealth(changeCount, latestChangeHeightValue, latestChangeTimestampValue);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account trust status change health from repository", e);
		}
	}

	private String trustSummaryEffectiveVoteWeightSql(int[] voteWeightPercents) {
		return HSQLDBTrustWeightSql.effectiveWeightSql("ats.mapped_trust_status", TRUST_SUMMARY_BLOCKS_MINTED_SQL,
				voteWeightPercents);
	}

	@Override
	public List<AccountTrustSnapshotData> getTrustDerivationSnapshots(Integer limit, Integer offset, Boolean reverse)
			throws DataException {
		return getTrustDerivationSnapshots(null, null, null, null, null, limit, offset, reverse);
	}

	@Override
	public List<AccountTrustSnapshotData> getTrustDerivationSnapshots(String accountAddress) throws DataException {
		return getTrustDerivationSnapshots(accountAddress, null, null, null, null, null, null, null);
	}

	@Override
	public List<AccountTrustSnapshotData> getTrustDerivationSnapshots(String accountAddress, AccountRatingCategory category,
			AccountTrustStatus status, Boolean seedMember, Integer minLevel, Integer limit, Integer offset, Boolean reverse)
			throws DataException {
		if (limit != null && limit == 0)
			return new ArrayList<>();

		StringBuilder sql = new StringBuilder(512);
		List<Object> bindParams = new ArrayList<>();
		List<String> whereClauses = new ArrayList<>();

		sql.append("SELECT ").append(TRUST_SNAPSHOT_SELECT_COLUMNS)
				.append(" FROM AccountTrustDerivationSnapshots");

		if (accountAddress != null) {
			whereClauses.add("account = ?");
			bindParams.add(accountAddress);
		}

		if (category != null) {
			whereClauses.add("category = ?");
			bindParams.add(category.value);
		}

		if (status != null) {
			whereClauses.add("mapped_trust_status = ?");
			bindParams.add(status.getValue());
		}

		if (seedMember != null) {
			whereClauses.add("minting_seed_member = ?");
			bindParams.add(seedMember);
		}

		if (minLevel != null) {
			whereClauses.add("level >= ?");
			bindParams.add(minLevel);
		}

		if (!whereClauses.isEmpty())
			sql.append(" WHERE ").append(String.join(" AND ", whereClauses));

		String sortDirection = Boolean.TRUE.equals(reverse) ? " DESC" : "";
		sql.append(" ORDER BY account").append(sortDirection).append(", category").append(sortDirection);
		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		return getTrustDerivationSnapshotsFromSql(sql.toString(), bindParams.toArray());
	}

	@Override
	public List<AccountTrustSnapshotData> getTrustDerivationSnapshotsForDerivation(AccountTrustStatus status,
			AccountRatingCategory sortCategory, Boolean seedMember, Integer minLevel, Integer limit, Integer offset,
			Boolean reverse) throws DataException {
		if (limit != null && limit == 0)
			return new ArrayList<>();

		List<String> accountAddresses = getTrustDerivationSnapshotAccountPage(status, defaultCategory(sortCategory),
				seedMember, minLevel, limit, offset, reverse);
		if (accountAddresses.isEmpty())
			return new ArrayList<>();

		return getTrustDerivationSnapshotsForAccounts(accountAddresses);
	}

	private List<String> getTrustDerivationSnapshotAccountPage(AccountTrustStatus status, AccountRatingCategory sortCategory,
			Boolean seedMember, Integer minLevel, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		List<Object> bindParams = new ArrayList<>();
		List<String> whereClauses = new ArrayList<>();

		sql.append("SELECT subject_snapshot.account FROM AccountTrustDerivationSnapshots subject_snapshot ")
				.append("JOIN AccountTrustDerivationSnapshots sort_snapshot ")
				.append("ON sort_snapshot.account = subject_snapshot.account AND sort_snapshot.category = ?");
		bindParams.add(sortCategory.value);

		whereClauses.add("subject_snapshot.category = ?");
		bindParams.add(AccountRatingCategory.SUBJECT.value);

		if (status != null) {
			whereClauses.add("subject_snapshot.mapped_trust_status = ?");
			bindParams.add(status.getValue());
		}

		if (seedMember != null) {
			whereClauses.add("subject_snapshot.minting_seed_member = ?");
			bindParams.add(seedMember);
		}

		if (minLevel != null) {
			whereClauses.add("sort_snapshot.level >= ?");
			bindParams.add(minLevel);
		}

		sql.append(" WHERE ").append(String.join(" AND ", whereClauses));

		String scoreSortDirection = Boolean.TRUE.equals(reverse) ? " ASC" : " DESC";
		String accountSortDirection = Boolean.TRUE.equals(reverse) ? " DESC" : " ASC";
		sql.append(" ORDER BY sort_snapshot.level").append(scoreSortDirection)
				.append(", sort_snapshot.score").append(scoreSortDirection)
				.append(", subject_snapshot.account").append(accountSortDirection);
		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<String> accountAddresses = new ArrayList<>();
		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return accountAddresses;

			do {
				accountAddresses.add(resultSet.getString(1));
			} while (resultSet.next());

			return accountAddresses;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account trust derivation snapshot account page from repository", e);
		}
	}

	private List<AccountTrustSnapshotData> getTrustDerivationSnapshotsForAccounts(List<String> accountAddresses)
			throws DataException {
		StringBuilder sql = new StringBuilder(512);
		sql.append("SELECT ").append(TRUST_SNAPSHOT_SELECT_COLUMNS)
				.append(" FROM AccountTrustDerivationSnapshots WHERE account IN (")
				.append(String.join(", ", Collections.nCopies(accountAddresses.size(), "?")))
				.append(")");

		List<AccountTrustSnapshotData> snapshots = getTrustDerivationSnapshotsFromSql(sql.toString(),
				accountAddresses.toArray());
		Map<String, Integer> accountOrder = new HashMap<>();
		for (int i = 0; i < accountAddresses.size(); ++i)
			accountOrder.put(accountAddresses.get(i), i);

		snapshots.sort(Comparator
				.comparingInt((AccountTrustSnapshotData snapshot) -> accountOrder.get(snapshot.getAccountAddress()))
				.thenComparing(AccountTrustSnapshotData::getCategory));
		return snapshots;
	}

	@Override
	public AccountTrustSnapshotData getTrustDerivationSnapshot(String accountAddress, AccountRatingCategory category)
			throws DataException {
		String sql = "SELECT " + TRUST_SNAPSHOT_SELECT_COLUMNS + " FROM AccountTrustDerivationSnapshots "
				+ "WHERE account = ? AND category = ?";

		List<AccountTrustSnapshotData> snapshots = getTrustDerivationSnapshotsFromSql(sql, accountAddress,
				defaultCategory(category).value);
		return snapshots.isEmpty() ? null : snapshots.get(0);
	}

	private List<AccountTrustSnapshotData> getTrustDerivationSnapshotsFromSql(String sql, Object... bindParams)
			throws DataException {
		List<AccountTrustSnapshotData> snapshots = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, bindParams)) {
			if (resultSet == null)
				return snapshots;

			do {
				byte[] accountPublicKey = resultSet.getBytes(1);
				String accountAddress = resultSet.getString(2);
				AccountRatingCategory category = AccountRatingCategory.valueOf(resultSet.getInt(3));
				long score = resultSet.getLong(4);
				long levelScore = resultSet.getLong(5);
				long levelScoreCap = resultSet.getLong(6);
				int level = resultSet.getInt(7);
				AccountTrustStatus mappedTrustStatus = AccountTrustStatus.valueOf(resultSet.getInt(8));
				boolean mintingSeedMember = resultSet.getBoolean(9);
				AccountTrustRatingCountsData inboundRatings = new AccountTrustRatingCountsData(
						resultSet.getInt(10), resultSet.getInt(11), resultSet.getInt(12), resultSet.getInt(13),
						resultSet.getInt(14), resultSet.getInt(15), resultSet.getInt(16), resultSet.getInt(17));
				int snapshotHeight = resultSet.getInt(18);
				long snapshotTimestamp = resultSet.getLong(19);
				int trustWeightPercent = BlockChain.getInstance()
						.getAccountTrustStatusVoteWeightPercent(this.repository, snapshotHeight, mappedTrustStatus);

				snapshots.add(new AccountTrustSnapshotData(accountPublicKey, accountAddress, defaultCategory(category),
						score, levelScore, levelScoreCap, level, mappedTrustStatus, trustWeightPercent,
						mintingSeedMember, inboundRatings, snapshotHeight, snapshotTimestamp));
			} while (resultSet.next());

			return snapshots;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account trust derivation snapshots from repository", e);
		}
	}

	@Override
	public List<AccountTrustStatusChangeData> getTrustStatusChanges(String accountAddress, AccountRatingCategory category,
			AccountTrustStatus previousStatus, AccountTrustStatus newStatus, Integer limit, Integer offset,
			Boolean reverse) throws DataException {
		if (limit != null && limit == 0)
			return new ArrayList<>();

		StringBuilder sql = new StringBuilder(512);
		List<Object> bindParams = new ArrayList<>();
		List<String> whereClauses = new ArrayList<>();

		sql.append("SELECT account_public_key, account, category, previous_level, new_level, "
				+ "previous_mapped_trust_status, new_mapped_trust_status, previous_score, new_score, "
				+ "previous_level_score, new_level_score, previous_minting_seed_member, new_minting_seed_member, "
				+ "previous_snapshot_height, previous_snapshot_timestamp, snapshot_height, snapshot_timestamp "
				+ "FROM AccountTrustStatusChanges");

		if (accountAddress != null) {
			whereClauses.add("account = ?");
			bindParams.add(accountAddress);
		}

		if (category != null) {
			whereClauses.add("category = ?");
			bindParams.add(category.value);
		}

		if (previousStatus != null) {
			whereClauses.add("previous_mapped_trust_status = ?");
			bindParams.add(previousStatus.getValue());
		}

		if (newStatus != null) {
			whereClauses.add("new_mapped_trust_status = ?");
			bindParams.add(newStatus.getValue());
		}

		if (!whereClauses.isEmpty())
			sql.append(" WHERE ").append(String.join(" AND ", whereClauses));

		String sortDirection = Boolean.TRUE.equals(reverse) ? " ASC" : " DESC";
		sql.append(" ORDER BY snapshot_height").append(sortDirection)
				.append(", snapshot_timestamp").append(sortDirection)
				.append(", account ASC, category ASC");
		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<AccountTrustStatusChangeData> changes = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return changes;

			do {
				byte[] accountPublicKey = resultSet.getBytes(1);
				String rowAccountAddress = resultSet.getString(2);
				AccountRatingCategory rowCategory = AccountRatingCategory.valueOf(resultSet.getInt(3));
				int previousLevel = resultSet.getInt(4);
				int newLevel = resultSet.getInt(5);
				AccountTrustStatus rowPreviousStatus = AccountTrustStatus.valueOf(resultSet.getInt(6));
				AccountTrustStatus rowNewStatus = AccountTrustStatus.valueOf(resultSet.getInt(7));
				long previousScore = resultSet.getLong(8);
				long newScore = resultSet.getLong(9);
				long previousLevelScore = resultSet.getLong(10);
				long newLevelScore = resultSet.getLong(11);
				boolean previousMintingSeedMember = resultSet.getBoolean(12);
				boolean newMintingSeedMember = resultSet.getBoolean(13);
				int previousSnapshotHeight = resultSet.getInt(14);
				long previousSnapshotTimestamp = resultSet.getLong(15);
				int snapshotHeight = resultSet.getInt(16);
				long snapshotTimestamp = resultSet.getLong(17);

				changes.add(new AccountTrustStatusChangeData(accountPublicKey, rowAccountAddress,
						defaultCategory(rowCategory), previousLevel, newLevel, rowPreviousStatus, rowNewStatus,
						previousScore, newScore, previousLevelScore, newLevelScore, previousMintingSeedMember,
						newMintingSeedMember, previousSnapshotHeight, previousSnapshotTimestamp, snapshotHeight,
						snapshotTimestamp));
			} while (resultSet.next());

			return changes;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account trust status changes from repository", e);
		}
	}

	@Override
	public Integer getLatestRatingChangeHeight(byte[] targetPublicKey, byte[] raterPublicKey,
			AccountRatingCategory category) throws DataException {
		String sql = "SELECT MAX(rating_change_height) FROM RateAccountTransactions "
				+ "WHERE target = ? AND rater = ? AND category = ? AND rating_change_height IS NOT NULL";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, targetPublicKey, raterPublicKey,
				defaultCategory(category).value)) {
			if (resultSet == null)
				return null;

			int latestHeight = resultSet.getInt(1);
			return resultSet.wasNull() ? null : latestHeight;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch latest account rating change height from repository", e);
		}
	}

	private static String trustStatusChangeKey(String accountAddress, AccountRatingCategory category) {
		return accountAddress + "\u0000" + defaultCategory(category).value;
	}

	private static AccountRatingCategory defaultCategory(AccountRatingCategory category) {
		return category == null ? AccountRatingCategory.SUBJECT : category;
	}

	private static class TrustSnapshotHealth {
		private final Integer snapshotHeight;
		private final Long snapshotTimestamp;
		private final long snapshotAccountCount;
		private final long snapshotRowCount;
		private final long expectedSnapshotRowCount;
		private final boolean snapshotsComplete;

		private TrustSnapshotHealth(Integer snapshotHeight, Long snapshotTimestamp, long snapshotAccountCount,
				long snapshotRowCount) {
			this.snapshotHeight = snapshotHeight;
			this.snapshotTimestamp = snapshotTimestamp;
			this.snapshotAccountCount = snapshotAccountCount;
			this.snapshotRowCount = snapshotRowCount;
			this.expectedSnapshotRowCount = snapshotAccountCount * AccountRatingCategory.values().length;
			this.snapshotsComplete = snapshotRowCount == this.expectedSnapshotRowCount;
		}
	}

	private static class TrustStatusSummaryValues {
		private long accountCount;
		private long seedMemberCount;
		private long rawVoteWeight;
		private long effectiveVoteWeight;
	}

	private static class RatingCategorySummaryValues {
		private long ratingCount;
		private long positiveRatingCount;
		private long negativeRatingCount;
	}

	private static class TrustStatusChangeHealth {
		private final long changeCount;
		private final Integer latestChangeHeight;
		private final Long latestChangeTimestamp;

		private TrustStatusChangeHealth(long changeCount, Integer latestChangeHeight, Long latestChangeTimestamp) {
			this.changeCount = changeCount;
			this.latestChangeHeight = latestChangeHeight;
			this.latestChangeTimestamp = latestChangeTimestamp;
		}
	}
}
