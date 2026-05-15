package org.qortal.repository.hsqldb;

import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountRatingSummaryData;
import org.qortal.data.account.AccountTrustDerivationData;
import org.qortal.data.account.AccountTrustPreviewData;
import org.qortal.data.account.AccountTrustSnapshotData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.repository.AccountRatingRepository;
import org.qortal.repository.DataException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HSQLDBAccountRatingRepository implements AccountRatingRepository {

	private static final String TRUST_SNAPSHOT_SELECT_COLUMNS = "account_public_key, account, category, score, "
			+ "level_score, level_score_cap, level, mapped_trust_status, minting_seed_member, "
			+ "positive_low_count, positive_medium_count, positive_high_count, positive_very_high_count, "
			+ "negative_low_count, negative_medium_count, negative_high_count, negative_very_high_count, "
			+ "snapshot_height, snapshot_timestamp";

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
		try {
			this.repository.delete("AccountTrustDerivationSnapshots");
		} catch (SQLException e) {
			throw new DataException("Unable to delete account trust derivation snapshots from repository", e);
		}

		if (derivedAccounts == null || derivedAccounts.isEmpty())
			return;

		try {
			for (AccountTrustDerivationData derivedAccount : derivedAccounts) {
				for (AccountTrustPreviewData.CategoryTrust categoryTrust : derivedAccount.getCategories())
					saveTrustDerivationSnapshot(derivedAccount, categoryTrust, snapshotHeight, snapshotTimestamp);
			}
		} catch (SQLException e) {
			throw new DataException("Unable to save account trust derivation snapshot into repository", e);
		}
	}

	private void saveTrustDerivationSnapshot(AccountTrustDerivationData derivedAccount,
			AccountTrustPreviewData.CategoryTrust categoryTrust, int snapshotHeight, long snapshotTimestamp)
			throws SQLException {
		AccountTrustPreviewData.RatingCounts inboundRatings = categoryTrust.getInboundRatings();
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
				AccountTrustPreviewData.RatingCounts inboundRatings = new AccountTrustPreviewData.RatingCounts(
						resultSet.getInt(10), resultSet.getInt(11), resultSet.getInt(12), resultSet.getInt(13),
						resultSet.getInt(14), resultSet.getInt(15), resultSet.getInt(16), resultSet.getInt(17));
				int snapshotHeight = resultSet.getInt(18);
				long snapshotTimestamp = resultSet.getLong(19);

				snapshots.add(new AccountTrustSnapshotData(accountPublicKey, accountAddress, defaultCategory(category),
						score, levelScore, levelScoreCap, level, mappedTrustStatus, mintingSeedMember, inboundRatings,
						snapshotHeight, snapshotTimestamp));
			} while (resultSet.next());

			return snapshots;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account trust derivation snapshots from repository", e);
		}
	}

	private static AccountRatingCategory defaultCategory(AccountRatingCategory category) {
		return category == null ? AccountRatingCategory.SUBJECT : category;
	}
}
