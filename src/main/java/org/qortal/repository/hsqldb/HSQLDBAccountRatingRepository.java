package org.qortal.repository.hsqldb;

import org.qortal.data.account.AccountRatingData;
import org.qortal.data.account.AccountRatingLevel;
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
		String sql = "SELECT target_account, rater_account, rating FROM AccountRatings WHERE target = ? AND rater = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, targetPublicKey, raterPublicKey)) {
			if (resultSet == null)
				return null;

			String targetAddress = resultSet.getString(1);
			String raterAddress = resultSet.getString(2);
			AccountRatingLevel ratingLevel = AccountRatingLevel.valueOf(resultSet.getInt(3));

			return new AccountRatingData(targetPublicKey, targetAddress, raterPublicKey, raterAddress, ratingLevel);
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
				.bind("rating", accountRatingData.getRatingValue());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account rating into repository", e);
		}
	}

	@Override
	public void delete(byte[] targetPublicKey, byte[] raterPublicKey) throws DataException {
		try {
			this.repository.delete("AccountRatings", "target = ? AND rater = ?", targetPublicKey, raterPublicKey);
		} catch (SQLException e) {
			throw new DataException("Unable to delete account rating from repository", e);
		}
	}

	@Override
	public AccountRatingSummaryData getRatingSummary(byte[] targetPublicKey, String targetAddress) throws DataException {
		String sql = "SELECT rating, COUNT(*) FROM AccountRatings WHERE target = ? GROUP BY rating";

		int trustedCount = 0;
		int knownCount = 0;
		int untrustedCount = 0;

		try (ResultSet resultSet = this.repository.checkedExecute(sql, targetPublicKey)) {
			if (resultSet != null) {
				do {
					AccountRatingLevel ratingLevel = AccountRatingLevel.valueOf(resultSet.getInt(1));
					int count = resultSet.getInt(2);

					if (ratingLevel == AccountRatingLevel.TRUSTED)
						trustedCount = count;
					else if (ratingLevel == AccountRatingLevel.KNOWN)
						knownCount = count;
					else if (ratingLevel == AccountRatingLevel.UNTRUSTED)
						untrustedCount = count;
				} while (resultSet.next());
			}

			return new AccountRatingSummaryData(targetPublicKey, targetAddress, trustedCount, knownCount, untrustedCount);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account rating summary from repository", e);
		}
	}

	@Override
	public List<AccountRatingData> getRatings(byte[] targetPublicKey, byte[] raterPublicKey,
			Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(256);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT target, target_account, rater, rater_account, rating FROM AccountRatings");

		List<String> whereClauses = new ArrayList<>();
		if (targetPublicKey != null) {
			whereClauses.add("target = ?");
			bindParams.add(targetPublicKey);
		}
		if (raterPublicKey != null) {
			whereClauses.add("rater = ?");
			bindParams.add(raterPublicKey);
		}

		if (!whereClauses.isEmpty())
			sql.append(" WHERE ").append(String.join(" AND ", whereClauses));

		String sortDirection = Boolean.TRUE.equals(reverse) ? " DESC" : "";
		sql.append(" ORDER BY target_account").append(sortDirection)
				.append(", rater_account").append(sortDirection);

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
				AccountRatingLevel rowRatingLevel = AccountRatingLevel.valueOf(resultSet.getInt(5));

				accountRatings.add(new AccountRatingData(rowTargetPublicKey, rowTargetAddress, rowRaterPublicKey, rowRaterAddress,
						rowRatingLevel));
			} while (resultSet.next());

			return accountRatings;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account ratings from repository", e);
		}
	}
}
