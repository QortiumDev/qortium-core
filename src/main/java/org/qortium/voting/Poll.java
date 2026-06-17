package org.qortium.voting;

import org.qortium.data.transaction.CreatePollTransactionData;
import org.qortium.data.transaction.UpdatePollTransactionData;
import org.qortium.data.voting.PollData;
import org.qortium.data.voting.PollOptionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.List;
import java.util.stream.Collectors;

public class Poll {

	// Properties
	private Repository repository;
	private PollData pollData;

	// Other useful constants
	public static final int MIN_NAME_SIZE = 3;
	public static final int MAX_NAME_SIZE = 400;
	public static final int MAX_DESCRIPTION_SIZE = 4000;
	public static final int MAX_OPTIONS = 1000;
	public static final int NO_VOTE_OPTION_INDEX = 0;

	// Constructors

	/**
	 * Construct Poll business object using poll data.
	 * 
	 * @param repository
	 * @param pollData
	 */
	public Poll(Repository repository, PollData pollData) {
		this.repository = repository;
		this.pollData = pollData;
	}

	/**
	 * Construct Poll business object using info from create poll transaction.
	 * 
	 * @param repository
	 * @param createPollTransactionData
	 */
	public Poll(Repository repository, CreatePollTransactionData createPollTransactionData) {
		this.repository = repository;
		this.pollData = new PollData(createPollTransactionData.getCreatorPublicKey(), createPollTransactionData.getOwner(),
				createPollTransactionData.getPollName(), createPollTransactionData.getDescription(), createPollTransactionData.getPollOptions(),
				createPollTransactionData.getTimestamp(), createPollTransactionData.getStartTime(), createPollTransactionData.getEndTime());
	}

	/**
	 * Construct Poll business object using existing poll from repository, identified by pollName.
	 * 
	 * @param repository
	 * @param pollName
	 * @throws DataException
	 */
	public Poll(Repository repository, String pollName) throws DataException {
		this.repository = repository;
		this.pollData = this.repository.getVotingRepository().fromPollName(pollName);
	}

	/**
	 * Construct Poll business object using existing poll from repository, identified by pollId.
	 *
	 * @param repository
	 * @param pollId
	 * @throws DataException
	 */
	public Poll(Repository repository, int pollId) throws DataException {
		this.repository = repository;
		this.pollData = this.repository.getVotingRepository().fromPollId(pollId);
	}

	public PollData getPollData() {
		return this.pollData;
	}

	// Processing

	/**
	 * "Publish" poll to allow voting.
	 * 
	 * @throws DataException
	 */
	public void publish() throws DataException {
		this.repository.getVotingRepository().save(this.pollData);
	}

	/**
	 * "Unpublish" poll, removing it from blockchain.
	 * <p>
	 * Typically used when orphaning create poll transaction.
	 * 
	 * @throws DataException
	 */
	public void unpublish() throws DataException {
		this.repository.getVotingRepository().delete(this.pollData.getPollName());
	}

	public void update(UpdatePollTransactionData updatePollTransactionData) throws DataException {
		updatePollTransactionData.setPreviousPollData(copyPollData(this.pollData));

		this.pollData.setPollName(updatePollTransactionData.getNewPollName());
		this.pollData.setDescription(updatePollTransactionData.getNewDescription());
		this.pollData.setPollOptions(copyPollOptions(updatePollTransactionData.getNewPollOptions()));
		this.pollData.setStartTime(updatePollTransactionData.getNewStartTime());
		this.pollData.setEndTime(updatePollTransactionData.getNewEndTime());

		this.repository.getVotingRepository().save(this.pollData);
	}

	public void unupdate(UpdatePollTransactionData updatePollTransactionData) throws DataException {
		if (updatePollTransactionData.getPreviousPollName() == null)
			throw new DataException("Unable to revert poll update without previous poll data");

		this.pollData.setPollName(updatePollTransactionData.getPreviousPollName());
		this.pollData.setDescription(updatePollTransactionData.getPreviousDescription());
		this.pollData.setPollOptions(copyPollOptions(updatePollTransactionData.getPreviousPollOptions()));
		this.pollData.setStartTime(updatePollTransactionData.getPreviousStartTime());
		this.pollData.setEndTime(updatePollTransactionData.getPreviousEndTime());

		this.repository.getVotingRepository().save(this.pollData);

		updatePollTransactionData.clearPreviousPollData();
	}

	private static PollData copyPollData(PollData pollData) {
		return new PollData(pollData.getPollId(), pollData.getCreatorPublicKey(), pollData.getOwner(), pollData.getPollName(),
				pollData.getDescription(), copyPollOptions(pollData.getPollOptions()), pollData.getPublished(),
				pollData.getStartTime(), pollData.getEndTime());
	}

	private static List<PollOptionData> copyPollOptions(List<PollOptionData> pollOptions) {
		return pollOptions.stream()
				.map(pollOptionData -> new PollOptionData(pollOptionData.getOptionName()))
				.collect(Collectors.toList());
	}

}
