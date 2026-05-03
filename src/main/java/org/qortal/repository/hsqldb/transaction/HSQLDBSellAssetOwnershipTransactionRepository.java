package org.qortal.repository.hsqldb.transaction;

import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.SellAssetOwnershipTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.hsqldb.HSQLDBRepository;
import org.qortal.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBSellAssetOwnershipTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBSellAssetOwnershipTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT asset_id, amount, recipient FROM SellAssetOwnershipTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			long assetId = resultSet.getLong(1);
			long amount = resultSet.getLong(2);
			String recipient = resultSet.getString(3);

			return new SellAssetOwnershipTransactionData(baseTransactionData, assetId, amount, recipient);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch sell asset ownership transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		SellAssetOwnershipTransactionData sellAssetOwnershipTransactionData = (SellAssetOwnershipTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("SellAssetOwnershipTransactions");

		saveHelper.bind("signature", sellAssetOwnershipTransactionData.getSignature())
				.bind("owner", sellAssetOwnershipTransactionData.getOwnerPublicKey())
				.bind("asset_id", sellAssetOwnershipTransactionData.getAssetId())
				.bind("amount", sellAssetOwnershipTransactionData.getAmount())
				.bind("recipient", sellAssetOwnershipTransactionData.getRecipient());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save sell asset ownership transaction into repository", e);
		}
	}

}
