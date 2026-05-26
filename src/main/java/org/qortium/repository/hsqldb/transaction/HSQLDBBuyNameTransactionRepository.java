package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.BuyNameTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBBuyNameTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBBuyNameTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT name, amount, seller, name_reference, sale_recipient FROM BuyNameTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			String name = resultSet.getString(1);
			long amount = resultSet.getLong(2);
			String seller = resultSet.getString(3);
			byte[] nameReference = resultSet.getBytes(4);
			String saleRecipient = resultSet.getString(5);

			return new BuyNameTransactionData(baseTransactionData, name, amount, seller, nameReference, saleRecipient);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch buy name transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		BuyNameTransactionData buyNameTransactionData = (BuyNameTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("BuyNameTransactions");

		saveHelper.bind("signature", buyNameTransactionData.getSignature()).bind("buyer", buyNameTransactionData.getBuyerPublicKey())
				.bind("name", buyNameTransactionData.getName()).bind("amount", buyNameTransactionData.getAmount())
				.bind("seller", buyNameTransactionData.getSeller()).bind("name_reference", buyNameTransactionData.getNameReference())
				.bind("sale_recipient", buyNameTransactionData.getSaleRecipient());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save buy name transaction into repository", e);
		}
	}

}
