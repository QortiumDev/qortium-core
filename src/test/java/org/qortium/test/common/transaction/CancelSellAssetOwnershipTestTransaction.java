package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.CancelSellAssetOwnershipTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.test.common.AssetUtils;

public class CancelSellAssetOwnershipTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		return new CancelSellAssetOwnershipTransactionData(generateBase(account), AssetUtils.testAssetId);
	}

}
