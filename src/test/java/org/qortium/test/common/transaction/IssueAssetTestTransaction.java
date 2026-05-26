package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.IssueAssetTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.test.common.AssetUtils;

import java.util.Random;

public class IssueAssetTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		Random random = new Random();

		String assetName = "test-asset-" + random.nextInt(1_000_000);
		String description = "random test asset";
		final long quantity = 1_000_000L;
		final boolean isDivisible = true;
		String data = AssetUtils.randomData();
		final boolean isUnspendable = false;

		return new IssueAssetTransactionData(generateBase(account), assetName, description, quantity, isDivisible, data, isUnspendable);
	}

}
