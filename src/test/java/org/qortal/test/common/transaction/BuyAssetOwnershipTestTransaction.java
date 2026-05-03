package org.qortal.test.common.transaction;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.BuyAssetOwnershipTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.test.common.AssetUtils;
import org.qortal.utils.Amounts;

public class BuyAssetOwnershipTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		long amount = 123L * Amounts.MULTIPLIER;

		return new BuyAssetOwnershipTransactionData(generateBase(account), AssetUtils.testAssetId, amount, account.getAddress());
	}

}
