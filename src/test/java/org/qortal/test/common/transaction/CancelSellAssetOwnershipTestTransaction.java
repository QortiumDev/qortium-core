package org.qortal.test.common.transaction;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.CancelSellAssetOwnershipTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.test.common.AssetUtils;

public class CancelSellAssetOwnershipTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		return new CancelSellAssetOwnershipTransactionData(generateBase(account), AssetUtils.testAssetId);
	}

}
