package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.SellAssetOwnershipTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.test.common.AssetUtils;
import org.qortium.utils.Amounts;

public class SellAssetOwnershipTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		long assetId = AssetUtils.testAssetId;
		long amount = 123L * Amounts.MULTIPLIER;

		return new SellAssetOwnershipTransactionData(generateBase(account), assetId, amount);
	}

}
