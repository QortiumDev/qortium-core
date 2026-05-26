package org.qortium.transaction;

import com.google.common.base.Utf8;
import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdatePollTransactionData;
import org.qortium.data.voting.PollData;
import org.qortium.data.voting.PollOptionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.VotingRepository;
import org.qortium.utils.Unicode;
import org.qortium.voting.Poll;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class UpdatePollTransaction extends Transaction {

	private final UpdatePollTransactionData updatePollTransactionData;

	public UpdatePollTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.updatePollTransactionData = (UpdatePollTransactionData) this.transactionData;
	}

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	public Account getOwner() {
		return this.getCreator();
	}

	@Override
	public ValidationResult isValid() throws DataException {
		if (this.updatePollTransactionData.getPollId() <= 0)
			return ValidationResult.POLL_DOES_NOT_EXIST;

		ValidationResult detailsValidationResult = validatePollDetails();
		if (detailsValidationResult != ValidationResult.OK)
			return detailsValidationResult;

		PollData pollData = this.repository.getVotingRepository().fromPollId(this.updatePollTransactionData.getPollId());
		if (pollData == null)
			return ValidationResult.POLL_DOES_NOT_EXIST;

		Account owner = getOwner();
		if (!owner.getAddress().equals(pollData.getOwner()))
			return ValidationResult.INVALID_POLL_OWNER;

		if (owner.getConfirmedBalance(Asset.NATIVE) < this.updatePollTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isValidAtTimestamp(long timestamp) throws DataException {
		PollData pollData = this.repository.getVotingRepository().fromPollId(this.updatePollTransactionData.getPollId());
		if (pollData == null)
			return ValidationResult.POLL_DOES_NOT_EXIST;

		if (pollData.isClosedAt(timestamp))
			return ValidationResult.POLL_CLOSED;

		Long newEndTime = this.updatePollTransactionData.getNewEndTime();
		if (newEndTime != null && newEndTime <= timestamp)
			return ValidationResult.INVALID_LIFETIME;

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		VotingRepository votingRepository = this.repository.getVotingRepository();
		PollData pollData = votingRepository.fromPollId(this.updatePollTransactionData.getPollId());
		if (pollData == null)
			return ValidationResult.POLL_DOES_NOT_EXIST;

		Account owner = getOwner();
		if (!owner.getAddress().equals(pollData.getOwner()))
			return ValidationResult.INVALID_POLL_OWNER;

		PollData namePollData = votingRepository.fromPollName(this.updatePollTransactionData.getNewPollName());
		if (namePollData != null && !namePollData.getPollId().equals(pollData.getPollId()))
			return ValidationResult.POLL_ALREADY_EXISTS;

		if (!votingRepository.hasVotes(pollData.getPollId()))
			return ValidationResult.OK;

		if (!pollMetadataMatches(pollData))
			return ValidationResult.POLL_ALREADY_HAS_VOTES;

		Long currentEndTime = pollData.getEndTime();
		Long newEndTime = this.updatePollTransactionData.getNewEndTime();
		if (currentEndTime == null || newEndTime == null)
			return ValidationResult.POLL_ALREADY_HAS_VOTES;

		if (newEndTime <= currentEndTime)
			return ValidationResult.INVALID_LIFETIME;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		Poll poll = new Poll(this.repository, this.updatePollTransactionData.getPollId());
		poll.update(this.updatePollTransactionData);

		this.repository.getTransactionRepository().save(this.updatePollTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		Poll poll = new Poll(this.repository, this.updatePollTransactionData.getPollId());
		poll.unupdate(this.updatePollTransactionData);

		this.repository.getTransactionRepository().save(this.updatePollTransactionData);
	}

	private ValidationResult validatePollDetails() {
		String newPollName = this.updatePollTransactionData.getNewPollName();
		if (newPollName == null)
			return ValidationResult.INVALID_NAME_LENGTH;

		int newPollNameLength = Utf8.encodedLength(newPollName);
		if (newPollNameLength < Poll.MIN_NAME_SIZE || newPollNameLength > Poll.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		if (!newPollName.equals(Unicode.normalize(newPollName)))
			return ValidationResult.NAME_NOT_NORMALIZED;

		String newDescription = this.updatePollTransactionData.getNewDescription();
		if (newDescription == null)
			return ValidationResult.INVALID_DESCRIPTION_LENGTH;

		int newDescriptionLength = Utf8.encodedLength(newDescription);
		if (newDescriptionLength < 1 || newDescriptionLength > Poll.MAX_DESCRIPTION_SIZE)
			return ValidationResult.INVALID_DESCRIPTION_LENGTH;

		Long newEndTime = this.updatePollTransactionData.getNewEndTime();
		if (newEndTime != null && newEndTime <= this.updatePollTransactionData.getTimestamp())
			return ValidationResult.INVALID_LIFETIME;

		List<PollOptionData> pollOptions = this.updatePollTransactionData.getNewPollOptions();
		if (pollOptions == null || pollOptions.size() < 2 || pollOptions.size() > Poll.MAX_OPTIONS)
			return ValidationResult.INVALID_OPTIONS_COUNT;

		List<String> optionNames = new ArrayList<>();
		for (PollOptionData pollOptionData : pollOptions) {
			if (pollOptionData == null || pollOptionData.getOptionName() == null)
				return ValidationResult.INVALID_OPTION_LENGTH;

			int optionNameLength = Utf8.encodedLength(pollOptionData.getOptionName());
			if (optionNameLength < 1 || optionNameLength > Poll.MAX_NAME_SIZE)
				return ValidationResult.INVALID_OPTION_LENGTH;

			if (optionNames.contains(pollOptionData.getOptionName()))
				return ValidationResult.DUPLICATE_OPTION;

			optionNames.add(pollOptionData.getOptionName());
		}

		return ValidationResult.OK;
	}

	private boolean pollMetadataMatches(PollData pollData) {
		return this.updatePollTransactionData.getNewPollName().equals(pollData.getPollName())
				&& this.updatePollTransactionData.getNewDescription().equals(pollData.getDescription())
				&& pollOptionsMatch(this.updatePollTransactionData.getNewPollOptions(), pollData.getPollOptions());
	}

	private static boolean pollOptionsMatch(List<PollOptionData> firstOptions, List<PollOptionData> secondOptions) {
		if (firstOptions.size() != secondOptions.size())
			return false;

		for (int i = 0; i < firstOptions.size(); ++i) {
			if (!firstOptions.get(i).getOptionName().equals(secondOptions.get(i).getOptionName()))
				return false;
		}

		return true;
	}

}
