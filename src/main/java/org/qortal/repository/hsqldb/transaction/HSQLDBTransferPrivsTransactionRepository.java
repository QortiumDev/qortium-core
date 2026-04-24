package org.qortal.repository.hsqldb.transaction;

import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.TransferPrivsTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.hsqldb.HSQLDBRepository;
import org.qortal.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBTransferPrivsTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBTransferPrivsTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT recipient, previous_recipient_existed, previous_sender_blocks_minted FROM TransferPrivsTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			String recipient = resultSet.getString(1);

			Boolean previousRecipientExisted = resultSet.getBoolean(2);
			if (!previousRecipientExisted && resultSet.wasNull())
				previousRecipientExisted = null;

			Integer previousSenderBlocksMinted = resultSet.getInt(3);
			if (previousSenderBlocksMinted == 0 && resultSet.wasNull())
				previousSenderBlocksMinted = null;

			return new TransferPrivsTransactionData(baseTransactionData, recipient, previousRecipientExisted, previousSenderBlocksMinted);
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
				.bind("previous_recipient_existed", transferPrivsTransactionData.getPreviousRecipientExisted())
				.bind("previous_sender_blocks_minted", transferPrivsTransactionData.getPreviousSenderBlocksMinted());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save transfer privs transaction into repository", e);
		}
	}

}
