package org.qortal.test.common.transaction;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.SellAssetOwnershipTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.test.common.AssetUtils;
import org.qortal.utils.Amounts;

public class SellAssetOwnershipTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		long assetId = AssetUtils.testAssetId;
		long amount = 123L * Amounts.MULTIPLIER;

		return new SellAssetOwnershipTransactionData(generateBase(account), assetId, amount);
	}

}
