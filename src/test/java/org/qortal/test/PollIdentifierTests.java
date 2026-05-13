package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.BlockChain;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.CreatePollTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.voting.PollData;
import org.qortal.data.voting.PollOptionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PollIdentifierTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testCreatePollStoresAndClearsPollId() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			CreatePollTransactionData transactionData = buildCreatePollTransactionData(repository, alice, "identified-poll");

			TransactionUtils.signAndMint(repository, transactionData, alice);

			PollData pollData = repository.getVotingRepository().fromPollName(transactionData.getPollName());
			assertNotNull(pollData);
			assertNotNull(pollData.getPollId());
			assertEquals(pollData.getPollId(), transactionData.getPollId());
			assertEquals(pollData.getPollName(), repository.getVotingRepository().fromPollId(pollData.getPollId()).getPollName());

			TransactionData fetchedTransactionData = repository.getTransactionRepository().fromSignature(transactionData.getSignature());
			CreatePollTransactionData fetchedCreatePollData = (CreatePollTransactionData) fetchedTransactionData;
			assertEquals(pollData.getPollId(), fetchedCreatePollData.getPollId());

			BlockUtils.orphanLastBlock(repository);

			assertNull(repository.getVotingRepository().fromPollName(transactionData.getPollName()));
			TransactionData orphanedTransactionData = repository.getTransactionRepository().fromSignature(transactionData.getSignature());
			if (orphanedTransactionData != null)
				assertNull(((CreatePollTransactionData) orphanedTransactionData).getPollId());
		}
	}

	private CreatePollTransactionData buildCreatePollTransactionData(Repository repository, PrivateKeyAccount creator, String pollName) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				timestamp,
				Group.NO_GROUP,
				creator.getPublicKey(),
				BlockChain.getInstance().getUnitFeeAtTimestamp(timestamp),
				null);

		return new CreatePollTransactionData(baseTransactionData, creator.getAddress(), pollName, "Test poll", buildPollOptions(), null);
	}

	private List<PollOptionData> buildPollOptions() {
		return List.of(
				new PollOptionData("Yes"),
				new PollOptionData("No"));
	}

}
