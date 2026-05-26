package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.CancelSellAssetOwnershipTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBCancelSellAssetOwnershipTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBCancelSellAssetOwnershipTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT asset_id, sale_price, sale_recipient FROM CancelSellAssetOwnershipTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			long assetId = resultSet.getLong(1);
			Long salePrice = resultSet.getLong(2);
			if (salePrice == 0 && resultSet.wasNull())
				salePrice = null;
			String saleRecipient = resultSet.getString(3);

			return new CancelSellAssetOwnershipTransactionData(baseTransactionData, assetId, salePrice, saleRecipient);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch cancel sell asset ownership transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		CancelSellAssetOwnershipTransactionData cancelSellAssetOwnershipTransactionData = (CancelSellAssetOwnershipTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("CancelSellAssetOwnershipTransactions");

		saveHelper.bind("signature", cancelSellAssetOwnershipTransactionData.getSignature())
				.bind("owner", cancelSellAssetOwnershipTransactionData.getOwnerPublicKey())
				.bind("asset_id", cancelSellAssetOwnershipTransactionData.getAssetId())
				.bind("sale_price", cancelSellAssetOwnershipTransactionData.getSalePrice())
				.bind("sale_recipient", cancelSellAssetOwnershipTransactionData.getSaleRecipient());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save cancel sell asset ownership transaction into repository", e);
		}
	}

}
