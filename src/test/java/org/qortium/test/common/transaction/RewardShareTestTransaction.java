package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.RewardShareTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

public class RewardShareTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		String recipient = account.getAddress();
		byte[] rewardSharePublicKey = account.getRewardSharePrivateKey(account.getPublicKey());
		int sharePercent = 50_00;

		return new RewardShareTransactionData(generateBase(account), recipient, rewardSharePublicKey, sharePercent);
	}

}
