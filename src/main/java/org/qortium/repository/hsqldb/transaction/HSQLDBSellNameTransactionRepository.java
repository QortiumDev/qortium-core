package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.SellNameTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBSellNameTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBSellNameTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT name, amount, recipient FROM SellNameTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			String name = resultSet.getString(1);
			long amount = resultSet.getLong(2);
			String recipient = resultSet.getString(3);

			return new SellNameTransactionData(baseTransactionData, name, amount, recipient);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch sell name transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		SellNameTransactionData sellNameTransactionData = (SellNameTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("SellNameTransactions");

		saveHelper.bind("signature", sellNameTransactionData.getSignature()).bind("owner", sellNameTransactionData.getOwnerPublicKey())
				.bind("name", sellNameTransactionData.getName()).bind("amount", sellNameTransactionData.getAmount())
				.bind("recipient", sellNameTransactionData.getRecipient());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save sell name transaction into repository", e);
		}
	}

}
