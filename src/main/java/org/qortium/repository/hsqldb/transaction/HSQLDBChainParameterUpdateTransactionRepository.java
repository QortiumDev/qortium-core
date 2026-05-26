package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChainParameterUpdateTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBChainParameterUpdateTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBChainParameterUpdateTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT parameter_id, activation_height, parameter_value "
				+ "FROM ChainParameterUpdateTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			int parameterId = resultSet.getInt(1);
			int activationHeight = resultSet.getInt(2);
			byte[] value = resultSet.getBytes(3);

			return new ChainParameterUpdateTransactionData(baseTransactionData, parameterId, activationHeight, value);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch chain parameter update transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		ChainParameterUpdateTransactionData chainParameterUpdateTransactionData = (ChainParameterUpdateTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("ChainParameterUpdateTransactions");

		saveHelper.bind("signature", chainParameterUpdateTransactionData.getSignature())
				.bind("updater", chainParameterUpdateTransactionData.getUpdaterPublicKey())
				.bind("parameter_id", chainParameterUpdateTransactionData.getParameterId())
				.bind("activation_height", chainParameterUpdateTransactionData.getActivationHeight())
				.bind("parameter_value", chainParameterUpdateTransactionData.getValue());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save chain parameter update transaction into repository", e);
		}
	}
}
