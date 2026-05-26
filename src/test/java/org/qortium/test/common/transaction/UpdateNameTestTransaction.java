package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdateNameTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

public class UpdateNameTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		String newOwner = account.getAddress();
		String name = "test name";
		if (!wantValid)
			name += " " + random.nextInt(1_000_000);

		String newData = "{ \"key\": \"updated value\" }";

		return new UpdateNameTransactionData(generateBase(account), newOwner, name, newData);
	}

}
