package org.qortal.repository.hsqldb;

import org.qortal.data.voting.PollData;
import org.qortal.data.voting.PollDataWithVotes;
import org.qortal.data.voting.PollOptionData;
import org.qortal.data.voting.PollVoteWeightData;
import org.qortal.data.voting.VoteOnPollData;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.repository.DataException;
import org.qortal.repository.VotingRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HSQLDBVotingRepository implements VotingRepository {

	protected HSQLDBRepository repository;
	private static final String EFFECTIVE_VOTE_WEIGHT_SQL = "CASE COALESCE(a.trust_status, 0) "
			+ "WHEN 3 THEN a.blocks_minted "
			+ "WHEN 2 THEN a.blocks_minted / 2 "
			+ "WHEN 1 THEN a.blocks_minted / 4 "
			+ "ELSE 0 END";
	private static final String TRUST_WEIGHT_PERCENT_SQL = "CASE COALESCE(a.trust_status, 0) "
			+ "WHEN 3 THEN 100 "
			+ "WHEN 2 THEN 50 "
			+ "WHEN 1 THEN 25 "
			+ "ELSE 0 END";

	public HSQLDBVotingRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	// Polls

	@Override
	public List<PollData> getAllPolls(Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);

		sql.append("SELECT poll_name, description, creator, owner, published_when, end_when FROM Polls ORDER BY poll_name");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<PollData> polls = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString())) {
			if (resultSet == null)
				return polls;

			do {
				String pollName = resultSet.getString(1);
				String description = resultSet.getString(2);
				byte[] creatorPublicKey = resultSet.getBytes(3);
				String owner = resultSet.getString(4);
				long published = resultSet.getLong(5);
				Long endTime = getNullableLong(resultSet, 6);

				String optionsSql = "SELECT option_name FROM PollOptions WHERE poll_name = ? ORDER BY option_index ASC";
				try (ResultSet optionsResultSet = this.repository.checkedExecute(optionsSql, pollName)) {
					if (optionsResultSet == null)
						return null;

					List<PollOptionData> pollOptions = new ArrayList<>();

					// NOTE: do-while because checkedExecute() above has already called rs.next() for us
					do {
						String optionName = optionsResultSet.getString(1);

						pollOptions.add(new PollOptionData(optionName));
					} while (optionsResultSet.next());

					polls.add(new PollData(creatorPublicKey, owner, pollName, description, pollOptions, published, endTime));
				}
				
			} while (resultSet.next());

			return polls;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch polls from repository", e);
		}
	}

	@Override
	public PollData fromPollName(String pollName) throws DataException {
		String sql = "SELECT description, creator, owner, published_when, end_when FROM Polls WHERE poll_name = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, pollName)) {
			if (resultSet == null)
				return null;

			String description = resultSet.getString(1);
			byte[] creatorPublicKey = resultSet.getBytes(2);
			String owner = resultSet.getString(3);
			long published = resultSet.getLong(4);
			Long endTime = getNullableLong(resultSet, 5);

			String optionsSql = "SELECT option_name FROM PollOptions WHERE poll_name = ? ORDER BY option_index ASC";
			try (ResultSet optionsResultSet = this.repository.checkedExecute(optionsSql, pollName)) {
				if (optionsResultSet == null)
					return null;

				List<PollOptionData> pollOptions = new ArrayList<>();

				// NOTE: do-while because checkedExecute() above has already called rs.next() for us
				do {
					String optionName = optionsResultSet.getString(1);

					pollOptions.add(new PollOptionData(optionName));
				} while (optionsResultSet.next());

				return new PollData(creatorPublicKey, owner, pollName, description, pollOptions, published, endTime);
			}
		} catch (SQLException e) {
			throw new DataException("Unable to fetch poll from repository", e);
		}
	}

	@Override
	public List<PollDataWithVotes> getPollsByPrefix(String prefix, Integer limit, Integer offset) throws DataException {
		StringBuilder sql = new StringBuilder(1024);
		StringBuilder pollNamesSql = new StringBuilder(256);

		pollNamesSql.append("SELECT poll_name FROM Polls WHERE poll_name LIKE ? ORDER BY poll_name");
		HSQLDBRepository.limitOffsetSql(pollNamesSql, limit, offset);

		// Query to get all polls matching prefix with their options and aggregated vote data
		sql.append("SELECT ");
		sql.append("  p.poll_name, p.description, p.creator, p.owner, p.published_when, p.end_when, ");
		sql.append("  po.option_index, po.option_name, ");
		sql.append("  COUNT(pv.voter) AS vote_count, ");
		sql.append("  COALESCE(SUM(").append(EFFECTIVE_VOTE_WEIGHT_SQL).append("), 0) AS vote_weight, ");
		sql.append("  COALESCE(SUM(a.blocks_minted), 0) AS raw_vote_weight ");
		sql.append("FROM (");
		sql.append(pollNamesSql);
		sql.append(") matching_polls ");
		sql.append("JOIN Polls p ON p.poll_name = matching_polls.poll_name ");
		sql.append("LEFT JOIN PollOptions po ON p.poll_name = po.poll_name ");
		sql.append("LEFT JOIN PollVotes pv ON p.poll_name = pv.poll_name AND po.option_index = pv.option_index ");
		sql.append("LEFT JOIN Accounts a ON pv.voter = a.public_key ");
		sql.append("GROUP BY p.poll_name, p.description, p.creator, p.owner, p.published_when, p.end_when, po.option_index, po.option_name ");
		sql.append("ORDER BY p.poll_name, po.option_index");

		List<PollDataWithVotes> results = new ArrayList<>();
		Map<String, PollDataWithVotes> pollMap = new LinkedHashMap<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), prefix + "%")) {
			if (resultSet == null)
				return results;

			// Process results - multiple rows per poll (one per option)
			do {
				String pollName = resultSet.getString(1);
				String description = resultSet.getString(2);
				byte[] creatorPublicKey = resultSet.getBytes(3);
				String owner = resultSet.getString(4);
				long published = resultSet.getLong(5);
				Long endTime = getNullableLong(resultSet, 6);
				Integer optionIndex = resultSet.getInt(7);
				String optionName = resultSet.getString(8);
				int voteCount = resultSet.getInt(9);
				int voteWeight = resultSet.getInt(10);
				int rawVoteWeight = resultSet.getInt(11);

				// Get or create PollDataWithVotes for this poll
				PollDataWithVotes pollWithVotes = pollMap.get(pollName);
				if (pollWithVotes == null) {
					// Create new poll data
					PollData pollData = new PollData(creatorPublicKey, owner, pollName, description, new ArrayList<>(), published, endTime);
					Map<String, Integer> voteCountMap = new HashMap<>();
					Map<String, Integer> voteWeightMap = new HashMap<>();
					Map<String, Integer> rawVoteWeightMap = new HashMap<>();
					pollWithVotes = new PollDataWithVotes(pollData, 0, 0, 0, voteCountMap, voteWeightMap, rawVoteWeightMap);
					pollMap.put(pollName, pollWithVotes);
				}

				// Add option to poll if not null
				if (optionName != null) {
					pollWithVotes.getPollData().getPollOptions().add(new PollOptionData(optionName));

					// Add vote counts and weights
					pollWithVotes.getVoteCountMap().put(optionName, voteCount);
					pollWithVotes.getVoteWeightMap().put(optionName, voteWeight);
					pollWithVotes.getRawVoteWeightMap().put(optionName, rawVoteWeight);

					// Update totals
					pollWithVotes.setTotalVotes(pollWithVotes.getTotalVotes() + voteCount);
					pollWithVotes.setTotalWeight(pollWithVotes.getTotalWeight() + voteWeight);
					pollWithVotes.setRawTotalWeight(pollWithVotes.getRawTotalWeight() + rawVoteWeight);
				}

			} while (resultSet.next());

			// Convert map to list
			results.addAll(pollMap.values());

			return results;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch polls by prefix from repository", e);
		}
	}

	@Override
	public boolean pollExists(String pollName) throws DataException {
		try {
			return this.repository.exists("Polls", "poll_name = ?", pollName);
		} catch (SQLException e) {
			throw new DataException("Unable to check for poll in repository", e);
		}
	}

	@Override
	public void save(PollData pollData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Polls");

		saveHelper.bind("poll_name", pollData.getPollName()).bind("description", pollData.getDescription()).bind("creator", pollData.getCreatorPublicKey())
				.bind("owner", pollData.getOwner()).bind("published_when", pollData.getPublished()).bind("end_when", pollData.getEndTime());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save poll into repository", e);
		}

		// Now attempt to save poll options
		List<PollOptionData> pollOptions = pollData.getPollOptions();
		for (int optionIndex = 0; optionIndex < pollOptions.size(); ++optionIndex) {
			PollOptionData pollOptionData = pollOptions.get(optionIndex);

			HSQLDBSaver optionSaveHelper = new HSQLDBSaver("PollOptions");

			optionSaveHelper.bind("poll_name", pollData.getPollName()).bind("option_index", optionIndex).bind("option_name", pollOptionData.getOptionName());

			try {
				optionSaveHelper.execute(this.repository);
			} catch (SQLException e) {
				throw new DataException("Unable to save poll option into repository", e);
			}
		}
	}

	@Override
	public void delete(String pollName) throws DataException {
		// NOTE: The corresponding rows in PollOptions are deleted automatically by the database
		// thanks to "ON DELETE CASCADE" in the PollOptions' FOREIGN KEY definition.
		try {
			this.repository.delete("Polls", "poll_name = ?", pollName);
		} catch (SQLException e) {
			throw new DataException("Unable to delete poll from repository", e);
		}
	}

	// Frozen poll results

	@Override
	public void freezeClosedPolls(int blockHeight, long blockTimestamp) throws DataException {
		String frozenResultsSql = "INSERT INTO PollFrozenResults "
				+ "(poll_name, option_index, vote_count, vote_weight, raw_vote_weight, freeze_height, freeze_timestamp) "
				+ "SELECT p.poll_name, po.option_index, COUNT(pv.voter), "
				+ "COALESCE(SUM(" + EFFECTIVE_VOTE_WEIGHT_SQL + "), 0), "
				+ "COALESCE(SUM(a.blocks_minted), 0), ?, ? "
				+ "FROM Polls p "
				+ "JOIN PollOptions po ON po.poll_name = p.poll_name "
				+ "LEFT JOIN PollVotes pv ON pv.poll_name = p.poll_name AND pv.option_index = po.option_index "
				+ "LEFT JOIN Accounts a ON pv.voter = a.public_key "
				+ "WHERE p.end_when IS NOT NULL AND p.end_when <= ? "
				+ "AND NOT EXISTS (SELECT TRUE FROM PollFrozenResults pfr WHERE pfr.poll_name = p.poll_name) "
				+ "GROUP BY p.poll_name, po.option_index";

		String frozenVoteDetailsSql = "INSERT INTO PollFrozenVoteDetails "
				+ "(poll_name, voter, option_index, raw_vote_weight, trust_status, trust_weight_percent, effective_vote_weight, freeze_height, freeze_timestamp) "
				+ "SELECT p.poll_name, pv.voter, pv.option_index, COALESCE(a.blocks_minted, 0), COALESCE(a.trust_status, 0), "
				+ TRUST_WEIGHT_PERCENT_SQL + ", " + EFFECTIVE_VOTE_WEIGHT_SQL + ", ?, ? "
				+ "FROM Polls p "
				+ "JOIN PollVotes pv ON pv.poll_name = p.poll_name "
				+ "LEFT JOIN Accounts a ON pv.voter = a.public_key "
				+ "WHERE EXISTS (SELECT TRUE FROM PollFrozenResults pfr WHERE pfr.poll_name = p.poll_name AND pfr.freeze_height = ?) "
				+ "AND NOT EXISTS (SELECT TRUE FROM PollFrozenVoteDetails pfv WHERE pfv.poll_name = p.poll_name)";

		try {
			this.repository.executeCheckedUpdate(frozenResultsSql, blockHeight, blockTimestamp, blockTimestamp);
			this.repository.executeCheckedUpdate(frozenVoteDetailsSql, blockHeight, blockTimestamp, blockHeight);
		} catch (SQLException e) {
			throw new DataException("Unable to freeze closed poll results in repository", e);
		}
	}

	@Override
	public void deleteFrozenPollResultsAtHeight(int blockHeight) throws DataException {
		try {
			this.repository.delete("PollFrozenVoteDetails", "freeze_height = ?", blockHeight);
			this.repository.delete("PollFrozenResults", "freeze_height = ?", blockHeight);
		} catch (SQLException e) {
			throw new DataException("Unable to delete frozen poll results from repository", e);
		}
	}

	@Override
	public PollDataWithVotes getFrozenPollResults(String pollName) throws DataException {
		PollData pollData = this.fromPollName(pollName);
		if (pollData == null)
			return null;

		String sql = "SELECT po.option_name, pfr.vote_count, pfr.vote_weight, pfr.raw_vote_weight, pfr.freeze_height "
				+ "FROM PollOptions po "
				+ "LEFT JOIN PollFrozenResults pfr ON pfr.poll_name = po.poll_name AND pfr.option_index = po.option_index "
				+ "WHERE po.poll_name = ? "
				+ "ORDER BY po.option_index ASC";

		Map<String, Integer> voteCountMap = new HashMap<>();
		Map<String, Integer> voteWeightMap = new HashMap<>();
		Map<String, Integer> rawVoteWeightMap = new HashMap<>();
		int totalVotes = 0;
		int totalWeight = 0;
		int rawTotalWeight = 0;

		try (ResultSet resultSet = this.repository.checkedExecute(sql, pollName)) {
			if (resultSet == null)
				return null;

			do {
				String optionName = resultSet.getString(1);
				int voteCount = resultSet.getInt(2);
				int voteWeight = resultSet.getInt(3);
				int rawVoteWeight = resultSet.getInt(4);
				Integer freezeHeight = getNullableInteger(resultSet, 5);

				if (freezeHeight == null)
					return null;

				voteCountMap.put(optionName, voteCount);
				voteWeightMap.put(optionName, voteWeight);
				rawVoteWeightMap.put(optionName, rawVoteWeight);

				totalVotes += voteCount;
				totalWeight += voteWeight;
				rawTotalWeight += rawVoteWeight;
			} while (resultSet.next());

			return new PollDataWithVotes(pollData, totalVotes, totalWeight, rawTotalWeight, voteCountMap, voteWeightMap, rawVoteWeightMap);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch frozen poll results from repository", e);
		}
	}

	@Override
	public List<PollVoteWeightData> getFrozenPollVoteWeights(String pollName) throws DataException {
		String sql = "SELECT voter, option_index, raw_vote_weight, trust_status, trust_weight_percent, effective_vote_weight, freeze_height, freeze_timestamp "
				+ "FROM PollFrozenVoteDetails "
				+ "WHERE poll_name = ? "
				+ "ORDER BY option_index ASC, voter ASC";
		List<PollVoteWeightData> voteWeights = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, pollName)) {
			if (resultSet == null)
				return voteWeights;

			do {
				byte[] voterPublicKey = resultSet.getBytes(1);
				int optionIndex = resultSet.getInt(2);
				int rawVoteWeight = resultSet.getInt(3);
				AccountTrustStatus trustStatus = AccountTrustStatus.valueOf(resultSet.getInt(4));
				int trustWeightPercent = resultSet.getInt(5);
				int effectiveVoteWeight = resultSet.getInt(6);
				int freezeHeight = resultSet.getInt(7);
				long freezeTimestamp = resultSet.getLong(8);

				voteWeights.add(new PollVoteWeightData(pollName, voterPublicKey, optionIndex, rawVoteWeight,
						trustStatus, trustWeightPercent, effectiveVoteWeight, freezeHeight, freezeTimestamp));
			} while (resultSet.next());

			return voteWeights;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch frozen poll vote weights from repository", e);
		}
	}

	// Votes

	@Override
	public List<VoteOnPollData> getVotes(String pollName) throws DataException {
		String sql = "SELECT voter, option_index FROM PollVotes WHERE poll_name = ?";
		List<VoteOnPollData> votes = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, pollName)) {
			if (resultSet == null)
				return votes;

			// NOTE: do-while because checkedExecute() above has already called rs.next() for us
			do {
				byte[] voterPublicKey = resultSet.getBytes(1);
				int optionIndex = resultSet.getInt(2);

				votes.add(new VoteOnPollData(pollName, voterPublicKey, optionIndex));
			} while (resultSet.next());

			return votes;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch poll votes from repository", e);
		}
	}

	@Override
	public VoteOnPollData getVote(String pollName, byte[] voterPublicKey) throws DataException {
		String sql = "SELECT option_index FROM PollVotes WHERE poll_name = ? AND voter = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, pollName, voterPublicKey)) {
			if (resultSet == null)
				return null;

			int optionIndex = resultSet.getInt(1);

			return new VoteOnPollData(pollName, voterPublicKey, optionIndex);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch poll vote from repository", e);
		}
	}

	@Override
	public void save(VoteOnPollData voteOnPollData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("PollVotes");

		saveHelper.bind("poll_name", voteOnPollData.getPollName()).bind("voter", voteOnPollData.getVoterPublicKey())
				.bind("option_index", voteOnPollData.getOptionIndex());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save poll vote into repository", e);
		}
	}

	@Override
	public void delete(String pollName, byte[] voterPublicKey) throws DataException {
		try {
			this.repository.delete("PollVotes", "poll_name = ? AND voter = ?", pollName, voterPublicKey);
		} catch (SQLException e) {
			throw new DataException("Unable to delete poll vote from repository", e);
		}
	}

	private static Long getNullableLong(ResultSet resultSet, int columnIndex) throws SQLException {
		long value = resultSet.getLong(columnIndex);
		return resultSet.wasNull() ? null : value;
	}

	private static Integer getNullableInteger(ResultSet resultSet, int columnIndex) throws SQLException {
		int value = resultSet.getInt(columnIndex);
		return resultSet.wasNull() ? null : value;
	}

}
