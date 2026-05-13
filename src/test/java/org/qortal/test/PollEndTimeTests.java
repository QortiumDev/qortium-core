package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.BlockChain;
import org.qortal.crypto.Crypto;
import org.qortal.data.account.AccountData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.CreatePollTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.VoteOnPollTransactionData;
import org.qortal.data.voting.PollData;
import org.qortal.data.voting.PollDataWithVotes;
import org.qortal.data.voting.PollOptionData;
import org.qortal.data.voting.PollVoteWeightData;
import org.qortal.data.voting.VoteOnPollData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.transaction.CreatePollTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.VoteOnPollTransaction;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.transaction.TransactionTransformer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PollEndTimeTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testCreatePollEndTimeValidation() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			long timestamp = TransactionUtils.nextTimestamp(repository);

			CreatePollTransactionData openEndedData = buildCreatePollTransactionData(alice, "open-ended-poll", timestamp, null);
			assertEquals(Transaction.ValidationResult.OK, new CreatePollTransaction(repository, openEndedData).isValid());

			CreatePollTransactionData validEndData = buildCreatePollTransactionData(alice, "valid-ended-poll", timestamp, timestamp + 1);
			assertEquals(Transaction.ValidationResult.OK, new CreatePollTransaction(repository, validEndData).isValid());

			CreatePollTransactionData equalEndData = buildCreatePollTransactionData(alice, "equal-ended-poll", timestamp, timestamp);
			assertEquals(Transaction.ValidationResult.INVALID_LIFETIME, new CreatePollTransaction(repository, equalEndData).isValid());

			CreatePollTransactionData pastEndData = buildCreatePollTransactionData(alice, "past-ended-poll", timestamp, timestamp - 1);
			assertEquals(Transaction.ValidationResult.INVALID_LIFETIME, new CreatePollTransaction(repository, pastEndData).isValid());
		}
	}

	@Test
	public void testVoteValidationUsesPollEndTime() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			long published = TransactionUtils.nextTimestamp(repository);
			long endTime = published + 1000;
			String pollName = "ended-vote-poll";

			repository.getVotingRepository().save(new PollData(alice.getPublicKey(), alice.getAddress(), pollName, "Test poll", buildPollOptions(), published, endTime));
			repository.saveChanges();

			VoteOnPollTransaction voteTransaction = new VoteOnPollTransaction(repository, buildVoteOnPollTransactionData(repository, bob, pollName, 1));
			assertEquals(Transaction.ValidationResult.OK, voteTransaction.isValid());
			assertEquals(Transaction.ValidationResult.OK, voteTransaction.isValidAtTimestamp(endTime - 1));
			assertEquals(Transaction.ValidationResult.POLL_CLOSED, voteTransaction.isValidAtTimestamp(endTime));
			assertEquals(Transaction.ValidationResult.POLL_CLOSED, voteTransaction.isValidAtTimestamp(endTime + 1));

			VoteOnPollTransaction changedVoteTransaction = new VoteOnPollTransaction(repository, buildVoteOnPollTransactionData(repository, bob, pollName, 2));
			assertEquals(Transaction.ValidationResult.POLL_CLOSED, changedVoteTransaction.isValidAtTimestamp(endTime));
		}
	}

	@Test
	public void testCreatePollSerializationPreservesOptionalEndTime() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			long timestamp = TransactionUtils.nextTimestamp(repository);

			CreatePollTransactionData openEndedData = buildCreatePollTransactionData(alice, "serialized-poll", timestamp, null);
			new CreatePollTransaction(repository, openEndedData).sign(alice);
			byte[] openEndedBytes = TransactionTransformer.toBytes(openEndedData);
			CreatePollTransactionData openEndedDeserialized = (CreatePollTransactionData) TransactionTransformer.fromBytes(openEndedBytes);
			assertNull(openEndedDeserialized.getEndTime());
			assertArrayEquals(openEndedBytes, TransactionTransformer.toBytes(openEndedDeserialized));

			CreatePollTransactionData endedData = buildCreatePollTransactionData(alice, "serialized-poll", timestamp, timestamp + 60_000L);
			new CreatePollTransaction(repository, endedData).sign(alice);
			byte[] endedBytes = TransactionTransformer.toBytes(endedData);
			CreatePollTransactionData endedDeserialized = (CreatePollTransactionData) TransactionTransformer.fromBytes(endedBytes);
			assertEquals(endedData.getEndTime(), endedDeserialized.getEndTime());
			assertEquals(openEndedBytes.length + Transformer.LONG_LENGTH, endedBytes.length);
			assertArrayEquals(endedBytes, TransactionTransformer.toBytes(endedDeserialized));
		}
	}

	@Test
	public void testCreatePollRepositoryPreservesEndTime() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			long timestamp = TransactionUtils.nextTimestamp(repository);
			long endTime = timestamp + 60_000L;
			CreatePollTransactionData transactionData = buildCreatePollTransactionData(alice, "repository-ended-poll", timestamp, endTime);

			TransactionUtils.signAndMint(repository, transactionData, alice);

			PollData pollData = repository.getVotingRepository().fromPollName(transactionData.getPollName());
			assertEquals(Long.valueOf(endTime), pollData.getEndTime());

			TransactionData fetchedTransactionData = repository.getTransactionRepository().fromSignature(transactionData.getSignature());
			CreatePollTransactionData fetchedCreatePollData = (CreatePollTransactionData) fetchedTransactionData;
			assertEquals(Long.valueOf(endTime), fetchedCreatePollData.getEndTime());
		}
	}

	@Test
	public void testBlockProcessingFreezesAndOrphanDeletesClosedPollResults() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			String pollName = "block-frozen-results-poll";
			long published = repository.getBlockRepository().getLastBlock().getTimestamp();
			long endTime = published + 1;

			repository.getVotingRepository().save(new PollData(alice.getPublicKey(), alice.getAddress(), pollName, "Test poll", buildPollOptions(), published, endTime));
			setVoteAccount(repository, "bob", 101, AccountTrustStatus.SILVER);
			setVoteAccount(repository, "chloe", 101, AccountTrustStatus.BRONZE);
			repository.getVotingRepository().save(new VoteOnPollData(pollName, bob.getPublicKey(), 1));
			repository.getVotingRepository().save(new VoteOnPollData(pollName, chloe.getPublicKey(), 2));
			repository.saveChanges();

			assertNull(repository.getVotingRepository().getFrozenPollResults(pollName));

			BlockUtils.mintBlock(repository);

			PollDataWithVotes frozenResults = repository.getVotingRepository().getFrozenPollResults(pollName);
			assertNotNull(frozenResults);
			assertEquals(Integer.valueOf(2), frozenResults.getTotalVotes());
			assertEquals(Integer.valueOf(75), frozenResults.getTotalWeight());
			assertEquals(Integer.valueOf(202), frozenResults.getRawTotalWeight());
			assertEquals(Integer.valueOf(1), frozenResults.getVoteCountMap().get("Yes"));
			assertEquals(Integer.valueOf(1), frozenResults.getVoteCountMap().get("No"));
			assertEquals(Integer.valueOf(50), frozenResults.getVoteWeightMap().get("Yes"));
			assertEquals(Integer.valueOf(25), frozenResults.getVoteWeightMap().get("No"));

			List<PollVoteWeightData> frozenVoteWeights = repository.getVotingRepository().getFrozenPollVoteWeights(pollName);
			assertEquals(2, frozenVoteWeights.size());
			PollVoteWeightData bobVoteWeight = findVoteWeight(frozenVoteWeights, bob.getAddress());
			assertEquals(AccountTrustStatus.SILVER, bobVoteWeight.getTrustStatus());
			assertEquals(101, bobVoteWeight.getRawVoteWeight());
			assertEquals(50, bobVoteWeight.getEffectiveVoteWeight());

			BlockUtils.orphanLastBlock(repository);

			assertNull(repository.getVotingRepository().getFrozenPollResults(pollName));
			assertEquals(0, repository.getVotingRepository().getFrozenPollVoteWeights(pollName).size());
		}
	}

	private CreatePollTransactionData buildCreatePollTransactionData(PrivateKeyAccount creator, String pollName, long timestamp, Long endTime) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				timestamp,
				Group.NO_GROUP,
				creator.getPublicKey(),
				BlockChain.getInstance().getUnitFeeAtTimestamp(timestamp),
				null);

		return new CreatePollTransactionData(baseTransactionData, creator.getAddress(), pollName, "Test poll", buildPollOptions(), endTime);
	}

	private VoteOnPollTransactionData buildVoteOnPollTransactionData(Repository repository, PrivateKeyAccount voter, String pollName, int optionIndex) throws DataException {
		long timestamp = System.currentTimeMillis();
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				timestamp,
				Group.NO_GROUP,
				voter.getPublicKey(),
				BlockChain.getInstance().getUnitFeeAtTimestamp(timestamp),
				null);

		int pollId = repository.getVotingRepository().fromPollName(pollName).getPollId();
		return new VoteOnPollTransactionData(baseTransactionData, pollId, optionIndex);
	}

	private void setVoteAccount(Repository repository, String accountName, int blocksMinted, AccountTrustStatus trustStatus) throws DataException {
		TestAccount account = Common.getTestAccount(repository, accountName);
		AccountData accountData = repository.getAccountRepository().getAccount(account.getAddress());
		if (accountData == null)
			accountData = new AccountData(account.getAddress(), account.getPublicKey(), Group.NO_GROUP, 0, blocksMinted);

		accountData.setPublicKey(account.getPublicKey());
		accountData.setBlocksMinted(blocksMinted);

		repository.getAccountRepository().setMintedBlockCount(accountData);
		repository.getAccountRepository().setTrustStatus(account.getAddress(), trustStatus);
	}

	private PollVoteWeightData findVoteWeight(List<PollVoteWeightData> voteWeights, String voterAddress) {
		return voteWeights.stream()
				.filter(voteWeight -> Crypto.toAddress(voteWeight.getVoterPublicKey()).equals(voterAddress))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing frozen vote weight for " + voterAddress));
	}

	private List<PollOptionData> buildPollOptions() {
		List<PollOptionData> pollOptions = new ArrayList<>();
		pollOptions.add(new PollOptionData("Yes"));
		pollOptions.add(new PollOptionData("No"));
		return pollOptions;
	}

}
