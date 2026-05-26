package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.TransferPrivsTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBTransferPrivsTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBTransferPrivsTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT recipient, previous_sender_blocks_minted FROM TransferPrivsTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			String recipient = resultSet.getString(1);

			Integer previousSenderBlocksMinted = resultSet.getInt(2);
			if (previousSenderBlocksMinted == 0 && resultSet.wasNull())
				previousSenderBlocksMinted = null;

			return new TransferPrivsTransactionData(baseTransactionData, recipient, previousSenderBlocksMinted);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch transfer privs transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		TransferPrivsTransactionData transferPrivsTransactionData = (TransferPrivsTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("TransferPrivsTransactions");
		saveHelper.bind("signature", transferPrivsTransactionData.getSignature()).bind("sender", transferPrivsTransactionData.getSenderPublicKey())
				.bind("recipient", transferPrivsTransactionData.getRecipient())
				.bind("previous_sender_blocks_minted", transferPrivsTransactionData.getPreviousSenderBlocksMinted());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save transfer privs transaction into repository", e);
		}
	}

}
