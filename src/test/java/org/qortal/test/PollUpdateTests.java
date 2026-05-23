package org.qortal.test;

import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.BlockChain;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.UpdatePollTransactionData;
import org.qortal.data.voting.PollData;
import org.qortal.data.voting.PollOptionData;
import org.qortal.data.voting.VoteOnPollData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.UpdatePollTransaction;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.transaction.TransactionTransformer;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PollUpdateTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testOwnerCanUpdatePollAndOrphanRestoresPreviousDetails() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			PollData pollData = createTestPoll(repository, alice, "update-poll-original", "Original description", buildYesNoOptions(), null);
			Long newEndTime = System.currentTimeMillis() + 600_000L;

			UpdatePollTransactionData transactionData = buildUpdatePollTransactionData(repository, alice, pollData.getPollId(),
					"update-poll-renamed", "Updated description", buildThreeOptions(), newEndTime);
			TransactionUtils.signAndMint(repository, transactionData, alice);

			PollData updatedPollData = repository.getVotingRepository().fromPollId(pollData.getPollId());
			assertEquals("update-poll-renamed", updatedPollData.getPollName());
			assertEquals("Updated description", updatedPollData.getDescription());
			assertEquals(List.of("Yes", "No", "Abstain"), pollOptionNames(updatedPollData));
			assertEquals(newEndTime, updatedPollData.getEndTime());
			assertNull(repository.getVotingRepository().fromPollName("update-poll-original"));
			assertEquals(pollData.getPollId(), repository.getVotingRepository().fromPollName("update-poll-renamed").getPollId());

			TransactionData fetchedTransactionData = repository.getTransactionRepository().fromSignature(transactionData.getSignature());
			UpdatePollTransactionData fetchedUpdateData = (UpdatePollTransactionData) fetchedTransactionData;
			assertEquals("update-poll-original", fetchedUpdateData.getPreviousPollName());
			assertEquals("Original description", fetchedUpdateData.getPreviousDescription());
			assertEquals(List.of("Yes", "No"), optionNames(fetchedUpdateData.getPreviousPollOptions()));
			assertNull(fetchedUpdateData.getPreviousEndTime());

			BlockUtils.orphanLastBlock(repository);

			PollData revertedPollData = repository.getVotingRepository().fromPollId(pollData.getPollId());
			assertEquals("update-poll-original", revertedPollData.getPollName());
			assertEquals("Original description", revertedPollData.getDescription());
			assertEquals(List.of("Yes", "No"), pollOptionNames(revertedPollData));
			assertNull(revertedPollData.getEndTime());
			assertNull(repository.getVotingRepository().fromPollName("update-poll-renamed"));
		}
	}

	@Test
	public void testUpdatingToFewerOptionsRemovesStaleOptionsAndOrphanRestoresThem() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			PollData pollData = createTestPoll(repository, alice, "shorter-options-update-poll",
					"Original description", buildThreeOptions(), null);

			UpdatePollTransactionData transactionData = buildUpdatePollTransactionData(repository, alice, pollData.getPollId(),
					pollData.getPollName(), "Updated description", buildYesNoOptions(), null);
			TransactionUtils.signAndMint(repository, transactionData, alice);

			PollData updatedPollData = repository.getVotingRepository().fromPollId(pollData.getPollId());
			assertEquals(List.of("Yes", "No"), pollOptionNames(updatedPollData));

			BlockUtils.orphanLastBlock(repository);

			PollData revertedPollData = repository.getVotingRepository().fromPollId(pollData.getPollId());
			assertEquals(List.of("Yes", "No", "Abstain"), pollOptionNames(revertedPollData));
		}
	}

	@Test
	public void testOnlyOwnerCanUpdatePoll() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			PollData pollData = createTestPoll(repository, alice, "owner-update-poll", "Original description", buildYesNoOptions(), null);

			UpdatePollTransactionData transactionData = buildUpdatePollTransactionData(repository, bob, pollData.getPollId(),
					"owner-update-poll-renamed", "Updated description", buildYesNoOptions(), null);
			assertEquals(Transaction.ValidationResult.INVALID_POLL_OWNER,
					new UpdatePollTransaction(repository, transactionData).isValid());
		}
	}

	@Test
	public void testDuplicatePollNameRejected() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			PollData pollData = createTestPoll(repository, alice, "duplicate-update-source", "Original description", buildYesNoOptions(), null);
			createTestPoll(repository, alice, "duplicate-update-target", "Other description", buildYesNoOptions(), null);

			UpdatePollTransactionData transactionData = buildUpdatePollTransactionData(repository, alice, pollData.getPollId(),
					"duplicate-update-target", "Updated description", buildYesNoOptions(), null);
			UpdatePollTransaction transaction = new UpdatePollTransaction(repository, transactionData);
			assertEquals(Transaction.ValidationResult.OK, transaction.isValid());
			assertEquals(Transaction.ValidationResult.POLL_ALREADY_EXISTS, transaction.isProcessable());
		}
	}

	@Test
	public void testActiveVotesBlockMetadataChanges() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			PollData pollData = createTestPoll(repository, alice, "active-vote-metadata-poll", "Original description", buildYesNoOptions(), null);
			repository.getVotingRepository().save(new VoteOnPollData(pollData.getPollId(), bob.getPublicKey(), 1));
			repository.saveChanges();

			UpdatePollTransactionData transactionData = buildUpdatePollTransactionData(repository, alice, pollData.getPollId(),
					pollData.getPollName(), "Changed description", buildYesNoOptions(), null);
			UpdatePollTransaction transaction = new UpdatePollTransaction(repository, transactionData);
			assertEquals(Transaction.ValidationResult.OK, transaction.isValid());
			assertEquals(Transaction.ValidationResult.POLL_ALREADY_HAS_VOTES, transaction.isProcessable());
		}
	}

	@Test
	public void testActiveVotesAllowOnlyEndTimeExtension() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			long currentEndTime = TransactionUtils.nextTimestamp(repository) + 120_000L;
			PollData pollData = createTestPoll(repository, alice, "active-vote-extension-poll", "Original description", buildYesNoOptions(), currentEndTime);
			repository.getVotingRepository().save(new VoteOnPollData(pollData.getPollId(), bob.getPublicKey(), 1));
			repository.saveChanges();

			UpdatePollTransactionData shorterEndData = buildUpdatePollTransactionData(repository, alice, pollData.getPollId(),
					pollData.getPollName(), pollData.getDescription(), buildYesNoOptions(), currentEndTime - 1);
			assertEquals(Transaction.ValidationResult.INVALID_LIFETIME,
					new UpdatePollTransaction(repository, shorterEndData).isProcessable());

			long extendedEndTime = currentEndTime + 120_000L;
			UpdatePollTransactionData extensionData = buildUpdatePollTransactionData(repository, alice, pollData.getPollId(),
					pollData.getPollName(), pollData.getDescription(), buildYesNoOptions(), extendedEndTime);
			TransactionUtils.signAndMint(repository, extensionData, alice);

			assertEquals(Long.valueOf(extendedEndTime), repository.getVotingRepository().fromPollId(pollData.getPollId()).getEndTime());

			BlockUtils.orphanLastBlock(repository);
			assertEquals(Long.valueOf(currentEndTime), repository.getVotingRepository().fromPollId(pollData.getPollId()).getEndTime());
		}
	}

	@Test
	public void testRemovedVotesAllowFullEditsAgain() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			PollData pollData = createTestPoll(repository, alice, "removed-vote-update-poll", "Original description", buildYesNoOptions(), null);
			repository.getVotingRepository().save(new VoteOnPollData(pollData.getPollId(), bob.getPublicKey(), 1));
			repository.getVotingRepository().delete(pollData.getPollId(), bob.getPublicKey());
			repository.saveChanges();

			UpdatePollTransactionData transactionData = buildUpdatePollTransactionData(repository, alice, pollData.getPollId(),
					"removed-vote-update-renamed", "Updated description", buildThreeOptions(), null);
			UpdatePollTransaction transaction = new UpdatePollTransaction(repository, transactionData);
			assertEquals(Transaction.ValidationResult.OK, transaction.isValid());
			assertEquals(Transaction.ValidationResult.OK, transaction.isProcessable());
		}
	}

	@Test
	public void testClosedPollRejectsUpdates() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			long endTime = repository.getBlockRepository().getLastBlock().getTimestamp() + 1;
			PollData pollData = createTestPoll(repository, alice, "closed-update-poll", "Original description", buildYesNoOptions(), endTime);
			BlockUtils.mintBlock(repository);

			UpdatePollTransactionData transactionData = buildUpdatePollTransactionData(repository, alice, pollData.getPollId(),
					pollData.getPollName(), pollData.getDescription(), buildYesNoOptions(), endTime + 60_000L);
			UpdatePollTransaction transaction = new UpdatePollTransaction(repository, transactionData);
			assertEquals(Transaction.ValidationResult.OK, transaction.isValid());
			assertEquals(Transaction.ValidationResult.POLL_CLOSED,
					transaction.isValidAtTimestamp(repository.getBlockRepository().getLastBlock().getTimestamp()));
		}
	}

	@Test
	public void testUpdatePollSerializationPreservesOptionalEndTime() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			PollData pollData = createTestPoll(repository, alice, "serialized-update-poll", "Original description", buildYesNoOptions(), null);

			UpdatePollTransactionData openEndedData = buildUpdatePollTransactionData(repository, alice, pollData.getPollId(),
					"serialized-update-case", "Updated description", buildThreeOptions(), null);
			new UpdatePollTransaction(repository, openEndedData).sign(alice);
			byte[] openEndedBytes = TransactionTransformer.toBytes(openEndedData);
			UpdatePollTransactionData openEndedDeserialized = (UpdatePollTransactionData) TransactionTransformer.fromBytes(openEndedBytes);
			assertNull(openEndedDeserialized.getNewEndTime());
			assertArrayEquals(openEndedBytes, TransactionTransformer.toBytes(openEndedDeserialized));
			assertEquals(List.of("Yes", "No", "Abstain"), optionNames(openEndedDeserialized.getNewPollOptions()));

			Long endTime = TransactionUtils.nextTimestamp(repository) + 60_000L;
			UpdatePollTransactionData endedData = buildUpdatePollTransactionData(repository, alice, pollData.getPollId(),
					"serialized-update-case", "Updated description", buildThreeOptions(), endTime);
			new UpdatePollTransaction(repository, endedData).sign(alice);
			byte[] endedBytes = TransactionTransformer.toBytes(endedData);
			UpdatePollTransactionData endedDeserialized = (UpdatePollTransactionData) TransactionTransformer.fromBytes(endedBytes);
			assertEquals(endTime, endedDeserialized.getNewEndTime());
			assertEquals(openEndedBytes.length + Transformer.LONG_LENGTH, endedBytes.length);
			assertArrayEquals(endedBytes, TransactionTransformer.toBytes(endedDeserialized));
		}
	}

	private PollData createTestPoll(Repository repository, TestAccount owner, String pollName, String description,
			List<PollOptionData> pollOptions, Long endTime) throws DataException {
		PollData pollData = new PollData(
				owner.getPublicKey(),
				owner.getAddress(),
				pollName,
				description,
				pollOptions,
				System.currentTimeMillis(),
				endTime);

		repository.getVotingRepository().save(pollData);
		repository.saveChanges();
		assertNotNull(pollData.getPollId());
		return pollData;
	}

	private UpdatePollTransactionData buildUpdatePollTransactionData(Repository repository, PrivateKeyAccount owner, int pollId,
			String newPollName, String newDescription, List<PollOptionData> newPollOptions, Long newEndTime) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				timestamp,
				Group.NO_GROUP,
				owner.getPublicKey(),
				BlockChain.getInstance().getUnitFeeAtTimestamp(timestamp),
				null);

		return new UpdatePollTransactionData(baseTransactionData, pollId, newPollName, newDescription, newPollOptions, newEndTime);
	}

	private List<PollOptionData> buildYesNoOptions() {
		return List.of(
				new PollOptionData("Yes"),
				new PollOptionData("No"));
	}

	private List<PollOptionData> buildThreeOptions() {
		return List.of(
				new PollOptionData("Yes"),
				new PollOptionData("No"),
				new PollOptionData("Abstain"));
	}

	private List<String> pollOptionNames(PollData pollData) {
		return optionNames(pollData.getPollOptions());
	}

	private List<String> optionNames(List<PollOptionData> pollOptions) {
		return pollOptions.stream()
				.map(PollOptionData::getOptionName)
				.collect(Collectors.toList());
	}

}
