package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.BlockChain;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.CreatePollTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.VoteOnPollTransactionData;
import org.qortal.data.voting.PollData;
import org.qortal.data.voting.PollOptionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.utils.Unicode;

import java.sql.PreparedStatement;
import java.sql.SQLException;
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
			assertEquals(pollData.getPollName(), repository.getVotingRepository().fromPollId(pollData.getPollId()).getPollName());

			TransactionData fetchedTransactionData = repository.getTransactionRepository().fromSignature(transactionData.getSignature());
			CreatePollTransactionData fetchedCreatePollData = (CreatePollTransactionData) fetchedTransactionData;
			assertNotNull(fetchedCreatePollData);
			assertEquals(pollData.getPollId(), fetchedCreatePollData.getPollId());

			BlockUtils.orphanLastBlock(repository);

			assertNull(repository.getVotingRepository().fromPollName(transactionData.getPollName()));
			TransactionData orphanedTransactionData = repository.getTransactionRepository().fromSignature(transactionData.getSignature());
			if (orphanedTransactionData != null)
				assertNull(((CreatePollTransactionData) orphanedTransactionData).getPollId());
		}
	}

	@Test
	public void testVoteTransactionSurvivesPollRenameById() throws DataException, SQLException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			CreatePollTransactionData createPollData = buildCreatePollTransactionData(repository, alice, "identified-vote-poll");

			TransactionUtils.signAndMint(repository, createPollData, alice);
			TransactionData fetchedTransactionData = repository.getTransactionRepository().fromSignature(createPollData.getSignature());
			CreatePollTransactionData fetchedCreatePollData = (CreatePollTransactionData) fetchedTransactionData;
			assertNotNull(fetchedCreatePollData);
			assertNotNull(fetchedCreatePollData.getPollId());
			int pollId = fetchedCreatePollData.getPollId();

			VoteOnPollTransactionData voteData = buildVoteOnPollTransactionData(repository, bob, pollId, 1);
			TransactionUtils.signAndMint(repository, voteData, bob);
			assertEquals(1, repository.getVotingRepository().getVote(pollId, bob.getPublicKey()).getOptionIndex());

			renamePoll(repository, pollId, "renamed-identified-vote-poll");
			assertNull(repository.getVotingRepository().fromPollName("identified-vote-poll"));
			assertEquals("renamed-identified-vote-poll", repository.getVotingRepository().fromPollId(pollId).getPollName());

			BlockUtils.orphanLastBlock(repository);

			assertNull(repository.getVotingRepository().getVote(pollId, bob.getPublicKey()));
			assertEquals("renamed-identified-vote-poll", repository.getVotingRepository().fromPollId(pollId).getPollName());
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

	private VoteOnPollTransactionData buildVoteOnPollTransactionData(Repository repository, PrivateKeyAccount voter, int pollId, int optionIndex) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				timestamp,
				Group.NO_GROUP,
				voter.getPublicKey(),
				BlockChain.getInstance().getUnitFeeAtTimestamp(timestamp),
				null);

		return new VoteOnPollTransactionData(baseTransactionData, pollId, optionIndex);
	}

	private void renamePoll(Repository repository, int pollId, String pollName) throws DataException, SQLException {
		String sql = "UPDATE Polls SET poll_name = ?, reduced_poll_name = ? WHERE poll_id = ?";
		try (PreparedStatement preparedStatement = repository.getConnection().prepareStatement(sql)) {
			preparedStatement.setString(1, pollName);
			preparedStatement.setString(2, Unicode.sanitize(pollName));
			preparedStatement.setInt(3, pollId);
			preparedStatement.executeUpdate();
		}

		repository.saveChanges();
	}

	private List<PollOptionData> buildPollOptions() {
		return List.of(
				new PollOptionData("Yes"),
				new PollOptionData("No"));
	}

}
