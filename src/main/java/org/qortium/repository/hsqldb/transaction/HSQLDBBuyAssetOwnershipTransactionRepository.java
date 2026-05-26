package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.BuyAssetOwnershipTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBBuyAssetOwnershipTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBBuyAssetOwnershipTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT asset_id, amount, seller, asset_reference, sale_recipient FROM BuyAssetOwnershipTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			long assetId = resultSet.getLong(1);
			long amount = resultSet.getLong(2);
			String seller = resultSet.getString(3);
			byte[] assetReference = resultSet.getBytes(4);
			String saleRecipient = resultSet.getString(5);

			return new BuyAssetOwnershipTransactionData(baseTransactionData, assetId, amount, seller, assetReference, saleRecipient);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch buy asset ownership transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		BuyAssetOwnershipTransactionData buyAssetOwnershipTransactionData = (BuyAssetOwnershipTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("BuyAssetOwnershipTransactions");

		saveHelper.bind("signature", buyAssetOwnershipTransactionData.getSignature())
				.bind("buyer", buyAssetOwnershipTransactionData.getBuyerPublicKey())
				.bind("asset_id", buyAssetOwnershipTransactionData.getAssetId())
				.bind("amount", buyAssetOwnershipTransactionData.getAmount())
				.bind("seller", buyAssetOwnershipTransactionData.getSeller())
				.bind("asset_reference", buyAssetOwnershipTransactionData.getAssetReference())
				.bind("sale_recipient", buyAssetOwnershipTransactionData.getSaleRecipient());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save buy asset ownership transaction into repository", e);
		}
	}

}
