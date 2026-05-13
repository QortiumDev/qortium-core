package org.qortal.test.common.transaction;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.UpdatePollTransactionData;
import org.qortal.data.voting.PollOptionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import java.util.List;
import java.util.Random;

public class UpdatePollTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		Random random = new Random();

		int pollId = random.nextInt(1_000_000) + 1;
		String newPollName = "updated test poll " + random.nextInt(1_000_000);
		String newDescription = "Updated test poll description";
		List<PollOptionData> newPollOptions = List.of(
				new PollOptionData("Yes"),
				new PollOptionData("No"));

		return new UpdatePollTransactionData(generateBase(account), pollId, newPollName, newDescription, newPollOptions, null);
	}

}
