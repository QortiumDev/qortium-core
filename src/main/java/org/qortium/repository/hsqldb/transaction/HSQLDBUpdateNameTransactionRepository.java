package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdateNameTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBUpdateNameTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBUpdateNameTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT name, new_name, new_data, is_primary, reduced_new_name, name_reference, previous_primary_name "
				+ "FROM UpdateNameTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			String name = resultSet.getString(1);
			String newName = resultSet.getString(2);
			String newData = resultSet.getString(3);
			Boolean primary = resultSet.getBoolean(4);
			if (!primary && resultSet.wasNull())
				primary = null;
			String reducedNewName = resultSet.getString(5);
			byte[] nameReference = resultSet.getBytes(6);
			String previousPrimaryName = resultSet.getString(7);

			return new UpdateNameTransactionData(baseTransactionData, name, newName, newData, primary, reducedNewName, nameReference, previousPrimaryName);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch update name transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		UpdateNameTransactionData updateNameTransactionData = (UpdateNameTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("UpdateNameTransactions");

		saveHelper.bind("signature", updateNameTransactionData.getSignature()).bind("owner", updateNameTransactionData.getOwnerPublicKey())
				.bind("name", updateNameTransactionData.getName()).bind("new_name", updateNameTransactionData.getNewName())
				.bind("new_data", updateNameTransactionData.getNewData()).bind("is_primary", updateNameTransactionData.getPrimary())
				.bind("reduced_new_name", updateNameTransactionData.getReducedNewName()).bind("name_reference", updateNameTransactionData.getNameReference())
				.bind("previous_primary_name", updateNameTransactionData.getPreviousPrimaryName());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save update name transaction into repository", e);
		}
	}

}
