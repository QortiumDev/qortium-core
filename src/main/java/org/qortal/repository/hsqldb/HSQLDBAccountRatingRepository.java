package org.qortal.repository.hsqldb;

import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountRatingSummaryData;
import org.qortal.repository.AccountRatingRepository;
import org.qortal.repository.DataException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class HSQLDBAccountRatingRepository implements AccountRatingRepository {

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

	private static AccountRatingCategory defaultCategory(AccountRatingCategory category) {
		return category == null ? AccountRatingCategory.SUBJECT : category;
	}
}
