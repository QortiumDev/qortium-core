package org.qortium.test;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.model.PollVotes;
import org.qortium.api.resource.PollsResource;
import org.qortium.block.BlockChain;
import org.qortium.data.account.AccountData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.CreatePollTransactionData;
import org.qortium.data.transaction.VoteOnPollTransactionData;
import org.qortium.data.voting.PollData;
import org.qortium.data.voting.PollOptionData;
import org.qortium.data.voting.VoteOnPollData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.CreatePollTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.VoteOnPollTransaction;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.voting.Poll;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PollVoteRemovalTests extends ApiCommon {

	private PollsResource pollsResource;

	@Before
	public void buildResource() throws DataException {
		this.pollsResource = (PollsResource) ApiCommon.buildResource(PollsResource.class);
	}

	@Test
	public void testMultiOptionVoteValidationProcessAndOrphan() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			String pollName = "multi-option-vote";
			createTestPoll(repository, alice, pollName, null);

			assertEquals(Transaction.ValidationResult.OK,
					new VoteOnPollTransaction(repository, voteData(repository, bob, pollName, List.of(1, 2))).isValid());
			assertEquals(Transaction.ValidationResult.POLL_OPTION_DOES_NOT_EXIST,
					new VoteOnPollTransaction(repository, voteData(repository, bob, pollName, List.of(1, 1))).isValid());
			assertEquals(Transaction.ValidationResult.POLL_OPTION_DOES_NOT_EXIST,
					new VoteOnPollTransaction(repository, voteData(repository, bob, pollName, List.of(0, 2))).isValid());
			assertEquals(Transaction.ValidationResult.POLL_OPTION_DOES_NOT_EXIST,
					new VoteOnPollTransaction(repository, voteData(repository, bob, pollName, List.of(1, 3))).isValid());

			VoteOnPollTransactionData multiVoteData = voteData(repository, bob, pollName, List.of(1, 2));
			new VoteOnPollTransaction(repository, multiVoteData).sign(bob);
			byte[] multiVoteBytes = TransactionTransformer.toBytes(multiVoteData);
			VoteOnPollTransactionData deserializedMultiVoteData = (VoteOnPollTransactionData) TransactionTransformer.fromBytes(multiVoteBytes);
			assertEquals(List.of(1, 2), deserializedMultiVoteData.getSelectedOptionIndexes());
			assertArrayEquals(multiVoteBytes, TransactionTransformer.toBytes(deserializedMultiVoteData));

			TransactionUtils.signAndMint(repository, multiVoteData, bob);
			VoteOnPollData storedVote = repository.getVotingRepository().getVote(pollName, bob.getPublicKey());
			assertNotNull(storedVote);
			assertEquals(List.of(1, 2), storedVote.getOptionIndexes());
			assertEquals(Transaction.ValidationResult.ALREADY_VOTED_FOR_THAT_OPTION,
					new VoteOnPollTransaction(repository, voteData(repository, bob, pollName, List.of(2, 1))).isValid());

			TransactionUtils.signAndMint(repository, voteData(repository, bob, pollName, List.of(2)), bob);
			assertEquals(List.of(2), repository.getVotingRepository().getVote(pollName, bob.getPublicKey()).getOptionIndexes());

			BlockUtils.orphanLastBlock(repository);
			assertEquals(List.of(1, 2), repository.getVotingRepository().getVote(pollName, bob.getPublicKey()).getOptionIndexes());

			TransactionUtils.signAndMint(repository, voteData(repository, bob, pollName, List.of()), bob);
			assertNull(repository.getVotingRepository().getVote(pollName, bob.getPublicKey()));
		}
	}

	@Test
	public void testVoteRemovalValidationProcessAndOrphan() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			String pollName = "vote-removal-validation";
			createTestPoll(repository, alice, pollName, null);

			assertEquals(Transaction.ValidationResult.ALREADY_VOTED_FOR_THAT_OPTION,
					new VoteOnPollTransaction(repository, voteData(repository, bob, pollName, Poll.NO_VOTE_OPTION_INDEX)).isValid());
			assertEquals(Transaction.ValidationResult.POLL_OPTION_DOES_NOT_EXIST,
					new VoteOnPollTransaction(repository, voteData(repository, bob, pollName, -1)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					new VoteOnPollTransaction(repository, voteData(repository, bob, pollName, 1)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					new VoteOnPollTransaction(repository, voteData(repository, bob, pollName, 2)).isValid());
			assertEquals(Transaction.ValidationResult.POLL_OPTION_DOES_NOT_EXIST,
					new VoteOnPollTransaction(repository, voteData(repository, bob, pollName, 3)).isValid());

			TransactionUtils.signAndMint(repository, voteData(repository, bob, pollName, 1), bob);
			assertEquals(1, repository.getVotingRepository().getVote(pollName, bob.getPublicKey()).getOptionIndex());
			assertEquals(Transaction.ValidationResult.ALREADY_VOTED_FOR_THAT_OPTION,
					new VoteOnPollTransaction(repository, voteData(repository, bob, pollName, 1)).isValid());
			assertEquals(Transaction.ValidationResult.OK,
					new VoteOnPollTransaction(repository, voteData(repository, bob, pollName, Poll.NO_VOTE_OPTION_INDEX)).isValid());

			TransactionUtils.signAndMint(repository, voteData(repository, bob, pollName, Poll.NO_VOTE_OPTION_INDEX), bob);
			assertNull(repository.getVotingRepository().getVote(pollName, bob.getPublicKey()));

			BlockUtils.orphanLastBlock(repository);
			assertEquals(1, repository.getVotingRepository().getVote(pollName, bob.getPublicKey()).getOptionIndex());
		}
	}

	@Test
	public void testVoteRemovalUpdatesLivePollResults() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			String pollName = "vote-removal-results";
			createTestPoll(repository, alice, pollName, null);
			setVoteAccount(repository, bob, 100);
			setVoteAccount(repository, chloe, 101);

			TransactionUtils.signAndMint(repository, voteData(repository, bob, pollName, 1), bob);
			TransactionUtils.signAndMint(repository, voteData(repository, chloe, pollName, 2), chloe);

			PollVotes beforeRemoval = this.pollsResource.getPollVotes(pollName, true);
			assertEquals(Integer.valueOf(2), beforeRemoval.totalVotes);
			assertEquals(Integer.valueOf(0), beforeRemoval.totalWeight);
			assertEquals(Integer.valueOf(201), beforeRemoval.rawTotalWeight);
			assertEquals(0, findOptionWeight(beforeRemoval.voteWeights, "Yes"));
			assertEquals(0, findOptionWeight(beforeRemoval.voteWeights, "No"));

			TransactionUtils.signAndMint(repository, voteData(repository, bob, pollName, Poll.NO_VOTE_OPTION_INDEX), bob);

			PollVotes afterRemoval = this.pollsResource.getPollVotes(pollName, true);
			assertEquals(Integer.valueOf(1), afterRemoval.totalVotes);
			assertEquals(Integer.valueOf(0), afterRemoval.totalWeight);
			assertEquals(Integer.valueOf(101), afterRemoval.rawTotalWeight);
			assertEquals(0, findOptionWeight(afterRemoval.voteWeights, "Yes"));
			assertEquals(0, findOptionWeight(afterRemoval.voteWeights, "No"));
			assertNull(repository.getVotingRepository().getVote(pollName, bob.getPublicKey()));
		}
	}

	@Test
	public void testClosedPollRejectsVoteRemoval() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			String pollName = "closed-removal-poll";
			long endTime = repository.getBlockRepository().getLastBlock().getTimestamp() + 1;
			createTestPoll(repository, alice, pollName, endTime);
			repository.getVotingRepository().save(new VoteOnPollData(pollName, bob.getPublicKey(), 1));
			repository.saveChanges();

			BlockUtils.mintBlock(repository);

			VoteOnPollTransaction removalTransaction = new VoteOnPollTransaction(repository,
					voteData(repository, bob, pollName, Poll.NO_VOTE_OPTION_INDEX));
			assertEquals(Transaction.ValidationResult.OK, removalTransaction.isValid());
			assertEquals(Transaction.ValidationResult.POLL_CLOSED,
					removalTransaction.isValidAtTimestamp(repository.getBlockRepository().getLastBlock().getTimestamp()));
		}
	}

	@Test
	public void testPollOptionsMustBeSeparateEntries() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			long timestamp = TransactionUtils.nextTimestamp(repository);

			CreatePollTransactionData commaPackedData = createPollData(alice, "comma-packed-poll", timestamp,
					List.of(new PollOptionData("Yes,No")), null);
			assertEquals(Transaction.ValidationResult.INVALID_OPTIONS_COUNT,
					new CreatePollTransaction(repository, commaPackedData).isValid());

			CreatePollTransactionData separateOptionsData = createPollData(alice, "separate-options-poll", timestamp,
					buildPollOptions(), null);
			CreatePollTransaction createPollTransaction = new CreatePollTransaction(repository, separateOptionsData);
			assertEquals(Transaction.ValidationResult.OK, createPollTransaction.isValid());
			createPollTransaction.sign(alice);

			byte[] bytes = TransactionTransformer.toBytes(separateOptionsData);
			CreatePollTransactionData deserializedData = (CreatePollTransactionData) TransactionTransformer.fromBytes(bytes);
			assertArrayEquals(bytes, TransactionTransformer.toBytes(deserializedData));
			assertEquals(2, deserializedData.getPollOptions().size());
			assertEquals("Yes", deserializedData.getPollOptions().get(0).getOptionName());
			assertEquals("No", deserializedData.getPollOptions().get(1).getOptionName());
		}
	}

	private VoteOnPollTransactionData voteData(Repository repository, PrivateKeyAccount voter, String pollName, int optionIndex) throws DataException {
		return voteData(repository, voter, pollName, List.of(optionIndex));
	}

	private VoteOnPollTransactionData voteData(Repository repository, PrivateKeyAccount voter, String pollName, List<Integer> optionIndexes) throws DataException {
		long timestamp = System.currentTimeMillis();
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				timestamp,
				Group.NO_GROUP,
				voter.getPublicKey(),
				BlockChain.getInstance().getUnitFeeAtTimestamp(timestamp),
				null);

		return new VoteOnPollTransactionData(baseTransactionData, pollId(repository, pollName), optionIndexes);
	}

	private int pollId(Repository repository, String pollName) throws DataException {
		return repository.getVotingRepository().fromPollName(pollName).getPollId();
	}

	private CreatePollTransactionData createPollData(PrivateKeyAccount creator, String pollName, long timestamp,
			List<PollOptionData> pollOptions, Long endTime) throws DataException {
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				timestamp,
				Group.NO_GROUP,
				creator.getPublicKey(),
				BlockChain.getInstance().getUnitFeeAtTimestamp(timestamp),
				null);

		return new CreatePollTransactionData(baseTransactionData, creator.getAddress(), pollName, "Test poll", pollOptions, endTime);
	}

	private void createTestPoll(Repository repository, TestAccount creator, String pollName, Long endTime) throws DataException {
		PollData pollData = new PollData(
				creator.getPublicKey(),
				creator.getAddress(),
				pollName,
				"Test poll",
				buildPollOptions(),
				System.currentTimeMillis(),
				endTime);

		repository.getVotingRepository().save(pollData);
		repository.saveChanges();
	}

	private List<PollOptionData> buildPollOptions() {
		return List.of(
				new PollOptionData("Yes"),
				new PollOptionData("No"));
	}

	private void setVoteAccount(Repository repository, TestAccount account, int blocksMinted) throws DataException {
		AccountData accountData = repository.getAccountRepository().getAccount(account.getAddress());
		if (accountData == null)
			accountData = new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, blocksMinted);

		accountData.setPublicKey(account.getPublicKey());
		accountData.setBlocksMinted(blocksMinted);

		repository.getAccountRepository().setMintedBlockCount(accountData);
		repository.saveChanges();
	}

	private int findOptionWeight(List<PollVotes.OptionWeight> voteWeights, String optionName) {
		return voteWeights.stream()
				.filter(optionWeight -> optionWeight.optionName.equals(optionName))
				.findFirst()
				.map(optionWeight -> optionWeight.voteWeight)
				.orElseThrow(() -> new AssertionError("Missing vote weight for option " + optionName));
	}
}
