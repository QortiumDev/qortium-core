package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdateAssetTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBUpdateAssetTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBUpdateAssetTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT asset_id, new_name, new_description, new_data, reduced_new_name, orphan_reference "
				+ "FROM UpdateAssetTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			long assetId = resultSet.getLong(1);
			String newName = resultSet.getString(2);
			String newDescription = resultSet.getString(3);
			String newData = resultSet.getString(4);
			String reducedNewName = resultSet.getString(5);
			byte[] orphanReference = resultSet.getBytes(6);

			return new UpdateAssetTransactionData(baseTransactionData, assetId, newName, newDescription, newData, reducedNewName, orphanReference);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch update asset transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		UpdateAssetTransactionData updateAssetTransactionData = (UpdateAssetTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("UpdateAssetTransactions");

		saveHelper.bind("signature", updateAssetTransactionData.getSignature())
				.bind("owner", updateAssetTransactionData.getOwnerPublicKey())
				.bind("asset_id", updateAssetTransactionData.getAssetId())
				.bind("new_name", updateAssetTransactionData.getNewName())
				.bind("new_description", updateAssetTransactionData.getNewDescription())
				.bind("new_data", updateAssetTransactionData.getNewData())
				.bind("reduced_new_name", updateAssetTransactionData.getReducedNewName())
				.bind("orphan_reference", updateAssetTransactionData.getOrphanReference());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save update asset transaction into repository", e);
		}
	}

}
