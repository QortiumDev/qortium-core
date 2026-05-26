package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.IssueAssetTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBIssueAssetTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBIssueAssetTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT asset_name, description, quantity, is_divisible, data, is_unspendable, asset_id, requested_asset_id, reduced_asset_name "
				+ "FROM IssueAssetTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			String assetName = resultSet.getString(1);
			String description = resultSet.getString(2);
			long quantity = resultSet.getLong(3);
			boolean isDivisible = resultSet.getBoolean(4);
			String data = resultSet.getString(5);
			boolean isUnspendable = resultSet.getBoolean(6);

			// Special null-checking for asset ID
			Long assetId = resultSet.getLong(7);
			if (assetId == 0 && resultSet.wasNull())
				assetId = null;

			Long requestedAssetId = resultSet.getLong(8);
			if (requestedAssetId == 0 && resultSet.wasNull())
				requestedAssetId = null;

			String reducedAssetName = resultSet.getString(9);

			return new IssueAssetTransactionData(baseTransactionData, assetId, requestedAssetId, assetName, description, quantity, isDivisible,
					data, isUnspendable, reducedAssetName);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch issue asset transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		IssueAssetTransactionData issueAssetTransactionData = (IssueAssetTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("IssueAssetTransactions");

		saveHelper.bind("signature", issueAssetTransactionData.getSignature()).bind("issuer", issueAssetTransactionData.getIssuerPublicKey())
				.bind("asset_name", issueAssetTransactionData.getAssetName()).bind("reduced_asset_name", issueAssetTransactionData.getReducedAssetName())
				.bind("description", issueAssetTransactionData.getDescription()).bind("quantity", issueAssetTransactionData.getQuantity())
				.bind("is_divisible", issueAssetTransactionData.isDivisible()).bind("data", issueAssetTransactionData.getData())
				.bind("is_unspendable", issueAssetTransactionData.isUnspendable()).bind("asset_id", issueAssetTransactionData.getAssetId())
				.bind("requested_asset_id", issueAssetTransactionData.getRequestedAssetId());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save issue asset transaction into repository", e);
		}
	}

}
