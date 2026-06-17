package org.qortium.transaction;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.VoteOnPollTransactionData;
import org.qortium.data.voting.PollData;
import org.qortium.data.voting.PollOptionData;
import org.qortium.data.voting.VoteOnPollData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.VotingRepository;
import org.qortium.voting.Poll;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VoteOnPollTransaction extends Transaction {

	private static final Logger LOGGER = LogManager.getLogger(VoteOnPollTransaction.class);

	// Properties
	private VoteOnPollTransactionData voteOnPollTransactionData;

	// Constructors

	public VoteOnPollTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.voteOnPollTransactionData = (VoteOnPollTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	// Navigation

	public Account getVoter() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		int pollId = this.voteOnPollTransactionData.getPollId();
		if (pollId <= 0)
			return ValidationResult.POLL_DOES_NOT_EXIST;

		VotingRepository votingRepository = this.repository.getVotingRepository();

		// Check poll exists
		PollData pollData = votingRepository.fromPollId(pollId);
		if (pollData == null)
			return ValidationResult.POLL_DOES_NOT_EXIST;

		// Check poll option indexes are within bounds
		List<PollOptionData> pollOptions = pollData.getPollOptions();
		List<Integer> optionIndexes = getNormalizedOptionIndexes(pollOptions.size());
		if (optionIndexes == null)
			return ValidationResult.POLL_OPTION_DOES_NOT_EXIST;

		// Check if vote already exists
		VoteOnPollData voteOnPollData = votingRepository.getVote(pollId, this.voteOnPollTransactionData.getVoterPublicKey());
		if (voteOnPollData == null && optionIndexes.isEmpty())
			return ValidationResult.ALREADY_VOTED_FOR_THAT_OPTION;

		if (voteOnPollData != null && voteOnPollData.getOptionIndexes().equals(optionIndexes))
			return ValidationResult.ALREADY_VOTED_FOR_THAT_OPTION;

		// Check reference is correct
		Account voter = getVoter();

		// Check voter has enough funds
		if (voter.getConfirmedBalance(Asset.NATIVE) < this.voteOnPollTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isValidAtTimestamp(long timestamp) throws DataException {
		PollData pollData = this.repository.getVotingRepository().fromPollId(this.voteOnPollTransactionData.getPollId());
		if (pollData == null)
			return ValidationResult.POLL_DOES_NOT_EXIST;

		if (!pollData.isStartedAt(timestamp))
			return ValidationResult.POLL_NOT_STARTED;

		if (pollData.isClosedAt(timestamp))
			return ValidationResult.POLL_CLOSED;

		return ValidationResult.OK;
	}


	@Override
	public void process() throws DataException {
		int pollId = this.voteOnPollTransactionData.getPollId();

		Account voter = getVoter();

		VotingRepository votingRepository = this.repository.getVotingRepository();

		// Check for previous vote so we can save option in case of orphaning
		VoteOnPollData previousVoteOnPollData = votingRepository.getVote(pollId, this.voteOnPollTransactionData.getVoterPublicKey());
		if (previousVoteOnPollData != null) {
			voteOnPollTransactionData.setPreviousOptionIndexes(previousVoteOnPollData.getOptionIndexes());
			LOGGER.trace(() -> String.format("Previous vote by %s on poll ID %d was option indexes %s",
					voter.getAddress(), pollId, previousVoteOnPollData.getOptionIndexes()));
		}

		// Save this transaction, now with possible previous vote
		this.repository.getTransactionRepository().save(voteOnPollTransactionData);

		List<Integer> optionIndexes = getNormalizedOptionIndexesForProcessing();
		if (optionIndexes.isEmpty()) {
			LOGGER.trace(() -> String.format("Deleting vote by %s on poll ID %d", voter.getAddress(), pollId));
			votingRepository.delete(pollId, this.voteOnPollTransactionData.getVoterPublicKey());
			return;
		}

		// Apply vote to poll
		LOGGER.trace(() -> String.format("Vote by %s on poll ID %d with option indexes %s",
				voter.getAddress(), pollId, optionIndexes));
		VoteOnPollData newVoteOnPollData = new VoteOnPollData(pollId, this.voteOnPollTransactionData.getVoterPublicKey(),
				optionIndexes);
		votingRepository.save(newVoteOnPollData);
	}

	@Override
	public void orphan() throws DataException {
		Account voter = getVoter();

		// Does this transaction have previous vote info?
		VotingRepository votingRepository = this.repository.getVotingRepository();
		List<Integer> previousOptionIndexes = this.voteOnPollTransactionData.getPreviousOptionIndexes();
		int pollId = this.voteOnPollTransactionData.getPollId();
		if (previousOptionIndexes != null) {
			// Reinstate previous vote
			LOGGER.trace(() -> String.format("Reinstating previous vote by %s on poll ID %d with option indexes %s",
					voter.getAddress(), pollId, previousOptionIndexes));
			VoteOnPollData previousVoteOnPollData = new VoteOnPollData(pollId, this.voteOnPollTransactionData.getVoterPublicKey(),
					previousOptionIndexes);
			if (previousOptionIndexes.isEmpty())
				votingRepository.delete(pollId, this.voteOnPollTransactionData.getVoterPublicKey());
			else
				votingRepository.save(previousVoteOnPollData);
		} else {
			// Delete vote
			LOGGER.trace(() -> String.format("Deleting vote by %s on poll ID %d with option indexes %s",
					voter.getAddress(), pollId, this.voteOnPollTransactionData.getSelectedOptionIndexes()));
			votingRepository.delete(pollId, this.voteOnPollTransactionData.getVoterPublicKey());
		}

		// Save this transaction, with removed previous vote info
		this.voteOnPollTransactionData.clearPreviousOptionIndexes();
		this.repository.getTransactionRepository().save(this.voteOnPollTransactionData);
	}

	private List<Integer> getNormalizedOptionIndexes(int pollOptionsCount) {
		if (this.voteOnPollTransactionData.hasConflictingOptionInputs())
			return null;

		List<Integer> submittedOptionIndexes = this.voteOnPollTransactionData.getSelectedOptionIndexes();
		if (submittedOptionIndexes.isEmpty())
			return Collections.emptyList();

		if (submittedOptionIndexes.size() == 1 && submittedOptionIndexes.get(0) != null
				&& submittedOptionIndexes.get(0) == Poll.NO_VOTE_OPTION_INDEX)
			return Collections.emptyList();

		List<Integer> normalizedOptionIndexes = new ArrayList<>();
		Set<Integer> uniqueOptionIndexes = new HashSet<>();
		for (Integer optionIndex : submittedOptionIndexes) {
			if (optionIndex == null)
				return null;

			if (optionIndex == Poll.NO_VOTE_OPTION_INDEX)
				return null;

			if (optionIndex < Poll.NO_VOTE_OPTION_INDEX || optionIndex > pollOptionsCount)
				return null;

			if (!uniqueOptionIndexes.add(optionIndex))
				return null;

			normalizedOptionIndexes.add(optionIndex);
		}

		Collections.sort(normalizedOptionIndexes);
		return normalizedOptionIndexes;
	}

	private List<Integer> getNormalizedOptionIndexesForProcessing() throws DataException {
		PollData pollData = this.repository.getVotingRepository().fromPollId(this.voteOnPollTransactionData.getPollId());
		if (pollData == null)
			throw new DataException("Unable to process vote for missing poll");

		List<Integer> optionIndexes = getNormalizedOptionIndexes(pollData.getPollOptions().size());
		if (optionIndexes == null)
			throw new DataException("Unable to process invalid poll vote option indexes");

		return optionIndexes;
	}

}
