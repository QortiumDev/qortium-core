package org.qortal.test.common.transaction;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.RateAccountTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.test.common.Common;

public class RateAccountTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		PrivateKeyAccount target = Common.getTestAccount(repository, "bob");

		return new RateAccountTransactionData(generateBase(account), target.getPublicKey(), 1);
	}
}
