package org.qortal.repository;

import org.qortal.data.voting.PollData;
import org.qortal.data.voting.PollDataWithVotes;
import org.qortal.data.voting.PollVoteWeightData;
import org.qortal.data.voting.VoteOnPollData;

import java.util.List;

public interface VotingRepository {

	// Polls

	public List<PollData> getAllPolls(Integer limit, Integer offset, Boolean reverse) throws DataException;

	public PollData fromPollName(String pollName) throws DataException;

	public boolean pollExists(String pollName) throws DataException;

	public void save(PollData pollData) throws DataException;

	public void delete(String pollName) throws DataException;

	// Frozen poll results

	public void freezeClosedPolls(int blockHeight, long blockTimestamp) throws DataException;

	public void deleteFrozenPollResultsAtHeight(int blockHeight) throws DataException;

	public PollDataWithVotes getFrozenPollResults(String pollName) throws DataException;

	public List<PollVoteWeightData> getFrozenPollVoteWeights(String pollName) throws DataException;

	// Votes

	public List<VoteOnPollData> getVotes(String pollName) throws DataException;

	public VoteOnPollData getVote(String pollName, byte[] voterPublicKey) throws DataException;

	public void save(VoteOnPollData voteOnPollData) throws DataException;

	public void delete(String pollName, byte[] voterPublicKey) throws DataException;

}
