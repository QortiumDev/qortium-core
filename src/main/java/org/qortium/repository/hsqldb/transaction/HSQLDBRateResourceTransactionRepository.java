package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.RateResourceTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBRateResourceTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBRateResourceTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT service, name, identifier, rating, previous_rating FROM RateResourceTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			int service = resultSet.getInt(1);
			String name = resultSet.getString(2);
			String identifier = resultSet.getString(3);
			int rating = resultSet.getInt(4);

			Integer previousRating = resultSet.getInt(5);
			if (previousRating == 0 && resultSet.wasNull())
				previousRating = null;

			return new RateResourceTransactionData(baseTransactionData, service, name, identifier, rating, previousRating);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch rate resource transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		RateResourceTransactionData rateResourceTransactionData = (RateResourceTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("RateResourceTransactions");

		saveHelper.bind("signature", rateResourceTransactionData.getSignature())
				.bind("rater", rateResourceTransactionData.getRaterPublicKey())
				.bind("service", rateResourceTransactionData.getServiceInt())
				.bind("name", rateResourceTransactionData.getName())
				.bind("identifier", rateResourceTransactionData.getIdentifier())
				.bind("rating", rateResourceTransactionData.getRating())
				.bind("previous_rating", rateResourceTransactionData.getPreviousRating());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save rate resource transaction into repository", e);
		}
	}

}
