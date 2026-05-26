package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdateAssetTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.test.common.AssetUtils;

public class UpdateAssetTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		final long assetId = 1;
		String newDescription = "updated random test asset";
		String newData = AssetUtils.randomData();

		return new UpdateAssetTransactionData(generateBase(account), assetId, newDescription, newData);
	}

}
