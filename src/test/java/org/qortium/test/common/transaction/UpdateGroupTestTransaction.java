package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdateGroupTransactionData;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

public class UpdateGroupTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		final int groupId = 1;
		String newDescription = "updated random test group";
		final boolean newIsOpen = false;
		ApprovalThreshold newApprovalThreshold = ApprovalThreshold.PCT20;
		final int newMinimumBlockDelay = 10;
		final int newMaximumBlockDelay = 60;

		return new UpdateGroupTransactionData(generateBase(account), groupId, newDescription, newIsOpen, newApprovalThreshold, newMinimumBlockDelay, newMaximumBlockDelay);
	}

}
