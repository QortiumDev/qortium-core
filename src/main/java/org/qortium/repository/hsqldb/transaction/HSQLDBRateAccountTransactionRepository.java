package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.RateAccountTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.account.AccountRatingCategory;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBRateAccountTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBRateAccountTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT target, category, rating, previous_rating, rating_change_height "
				+ "FROM RateAccountTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			byte[] targetPublicKey = resultSet.getBytes(1);
			AccountRatingCategory category = AccountRatingCategory.valueOf(resultSet.getInt(2));
			int rating = resultSet.getInt(3);

			Integer previousRating = resultSet.getInt(4);
			if (previousRating == 0 && resultSet.wasNull())
				previousRating = null;

			Integer ratingChangeHeight = resultSet.getInt(5);
			if (ratingChangeHeight == 0 && resultSet.wasNull())
				ratingChangeHeight = null;

			RateAccountTransactionData transactionData = new RateAccountTransactionData(baseTransactionData,
					targetPublicKey, category, rating, previousRating);
			transactionData.setRatingChangeHeight(ratingChangeHeight);
			return transactionData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch rate account transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		RateAccountTransactionData rateAccountTransactionData = (RateAccountTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("RateAccountTransactions");

		saveHelper.bind("signature", rateAccountTransactionData.getSignature())
				.bind("rater", rateAccountTransactionData.getRaterPublicKey())
				.bind("target", rateAccountTransactionData.getTargetPublicKey())
				.bind("category", rateAccountTransactionData.getCategoryValue())
				.bind("rating", rateAccountTransactionData.getRating())
				.bind("previous_rating", rateAccountTransactionData.getPreviousRating())
				.bind("rating_change_height", rateAccountTransactionData.getRatingChangeHeight());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save rate account transaction into repository", e);
		}
	}
}
