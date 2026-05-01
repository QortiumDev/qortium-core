package org.qortal.test.common.transaction;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.data.transaction.CreateAssetOrderTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Amounts;

public class CreateAssetOrderTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		final long haveAssetId = Asset.NATIVE;
		final long wantAssetId = 1;
		long amount = 123L * Amounts.MULTIPLIER;
		long price = 123L * Amounts.MULTIPLIER;

		return new CreateAssetOrderTransactionData(generateBase(account), haveAssetId, wantAssetId, amount, price);
	}

}
