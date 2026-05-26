package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.VoteOnPollTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Random;

public class VoteOnPollTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		Random random = new Random();

		int pollId = random.nextInt(1_000_000) + 1;
		final int optionIndex = random.nextInt(3) + 1;

		return new VoteOnPollTransactionData(generateBase(account), pollId, optionIndex);
	}

}
