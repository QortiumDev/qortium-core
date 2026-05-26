package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.RateAccountTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.test.common.Common;

public class RateAccountTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		PrivateKeyAccount target = Common.getTestAccount(repository, "bob");

		return new RateAccountTransactionData(generateBase(account), target.getPublicKey(), 1);
	}
}
