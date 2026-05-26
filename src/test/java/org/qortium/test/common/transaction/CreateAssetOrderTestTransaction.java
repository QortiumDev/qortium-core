package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.data.transaction.CreateAssetOrderTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.utils.Amounts;

public class CreateAssetOrderTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		final long haveAssetId = Asset.NATIVE;
		final long wantAssetId = 1;
		long amount = 123L * Amounts.MULTIPLIER;
		long price = 123L * Amounts.MULTIPLIER;

		return new CreateAssetOrderTransactionData(generateBase(account), haveAssetId, wantAssetId, amount, price);
	}

}
