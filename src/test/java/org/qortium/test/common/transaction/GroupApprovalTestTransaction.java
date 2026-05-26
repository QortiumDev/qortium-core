package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.GroupApprovalTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Random;

public class GroupApprovalTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		Random random = new Random();

		byte[] pendingSignature = new byte[64];
		random.nextBytes(pendingSignature);
		final boolean approval = true;

		return new GroupApprovalTransactionData(generateBase(account), pendingSignature, approval);
	}

}
