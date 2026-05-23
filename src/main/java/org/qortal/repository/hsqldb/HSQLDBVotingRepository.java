package org.qortal.repository.hsqldb;

import org.qortal.block.BlockChain;
import org.qortal.data.voting.PollData;
import org.qortal.data.voting.PollDataWithVotes;
import org.qortal.data.voting.PollOptionData;
import org.qortal.data.voting.PollVoteWeightData;
import org.qortal.data.voting.VoteOnPollData;
import org.qortal.data.account.AccountRatingCategory;
import org.qortal.data.account.AccountTrustStatus;
import org.qortal.repository.DataException;
import org.qortal.repository.VotingRepository;
import org.qortal.utils.Unicode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HSQLDBVotingRepository implements VotingRepository {

	protected HSQLDBRepository repository;
	private static final String ACTIVE_TRUST_STATUS_SQL = HSQLDBTrustWeightSql.activeTrustStatusSql("ats");
	private static final String RAW_VOTE_WEIGHT_SQL = "CAST(COALESCE(a.blocks_minted, 0) AS BIGINT)";

	public HSQLDBVotingRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	// Polls

	@Override
	public List<PollData> getAllPolls(Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);

		sql.append("SELECT poll_id, poll_name, description, creator, owner, published_when, end_when FROM Polls ORDER BY poll_name");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<PollData> polls = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString())) {
			if (resultSet == null)
				return polls;

			do {
				polls.add(buildPollData(resultSet));
			} while (resultSet.next());

			return polls;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch polls from repository", e);
		}
	}

	@Override
	public List<PollData> searchPolls(String query, boolean prefixOnly, String owner, Boolean isClosed, Boolean hasEndTime,
			Long fromTimestamp, Long toTimestamp, long currentTimestamp, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		List<Object> bindParams = new ArrayList<>();

		sql.append("SELECT poll_id, poll_name, description, creator, owner, published_when, end_when FROM Polls");

		List<String> conditions = new ArrayList<>();
		String trimmedQuery = query == null ? null : query.trim();
		if (trimmedQuery != null && !trimmedQuery.isEmpty()) {
			String descriptionQuery = trimmedQuery.toLowerCase(Locale.ROOT);
			String descriptionWildcard = prefixOnly ? String.format("%s%%", descriptionQuery) : String.format("%%%s%%", descriptionQuery);
			String reducedQuery = Unicode.sanitize(trimmedQuery);

			if (reducedQuery.isEmpty()) {
				conditions.add("LCASE(description) LIKE ?");
				bindParams.add(descriptionWildcard);
			} else {
				String reducedWildcard = prefixOnly ? String.format("%s%%", reducedQuery) : String.format("%%%s%%", reducedQuery);
				conditions.add("(reduced_poll_name LIKE ? OR LCASE(description) LIKE ?)");
				bindParams.add(reducedWildcard);
				bindParams.add(descriptionWildcard);
			}
		}

		if (owner != null) {
			conditions.add("owner = ?");
			bindParams.add(owner);
		}

		if (isClosed != null) {
			if (isClosed) {
				conditions.add("end_when IS NOT NULL AND end_when <= ?");
				bindParams.add(currentTimestamp);
			} else {
				conditions.add("(end_when IS NULL OR end_when > ?)");
				bindParams.add(currentTimestamp);
			}
		}

		if (hasEndTime != null)
			conditions.add(hasEndTime ? "end_when IS NOT NULL" : "end_when IS NULL");

		if (fromTimestamp != null) {
			conditions.add("published_when >= ?");
			bindParams.add(fromTimestamp);
		}

		if (toTimestamp != null) {
			conditions.add("published_when <= ?");
			bindParams.add(toTimestamp);
		}

		if (!conditions.isEmpty())
			sql.append(" WHERE ").append(String.join(" AND ", conditions));

		sql.append(" ORDER BY published_when");
		if (reverse != null && reverse)
			sql.append(" DESC");
		sql.append(", poll_name");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<PollData> polls = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), bindParams.toArray())) {
			if (resultSet == null)
				return polls;

			do {
				polls.add(buildPollData(resultSet));
			} while (resultSet.next());

			return polls;
		} catch (SQLException e) {
			throw new DataException("Unable to search polls in repository", e);
		}
	}

	@Override
	public PollData fromPollId(int pollId) throws DataException {
		String sql = "SELECT poll_id, poll_name, description, creator, owner, published_when, end_when FROM Polls WHERE poll_id = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, pollId)) {
			if (resultSet == null)
				return null;

			return buildPollData(resultSet);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch poll by ID from repository", e);
		}
	}

	@Override
	public PollData fromPollName(String pollName) throws DataException {
		String sql = "SELECT poll_id, poll_name, description, creator, owner, published_when, end_when FROM Polls WHERE poll_name = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, pollName)) {
			if (resultSet == null)
				return null;

			return buildPollData(resultSet);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch poll from repository", e);
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
		if (pollData.getPollId() == null)
			insertPoll(pollData);
		else
			updatePoll(pollData);

		try {
			this.repository.delete("PollOptions", "poll_id = ?", pollData.getPollId());
		} catch (SQLException e) {
			throw new DataException("Unable to replace poll options in repository", e);
		}

		// Now attempt to save poll options
		List<PollOptionData> pollOptions = pollData.getPollOptions();
		for (int optionIndex = 0; optionIndex < pollOptions.size(); ++optionIndex) {
			PollOptionData pollOptionData = pollOptions.get(optionIndex);

			HSQLDBSaver optionSaveHelper = new HSQLDBSaver("PollOptions");

			optionSaveHelper.bind("poll_id", pollData.getPollId()).bind("option_index", optionIndex).bind("option_name", pollOptionData.getOptionName());

			try {
				optionSaveHelper.execute(this.repository);
			} catch (SQLException e) {
				throw new DataException("Unable to save poll option into repository", e);
			}
		}
	}

	private void insertPoll(PollData pollData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Polls");

		saveHelper.bind("poll_name", pollData.getPollName()).bind("description", pollData.getDescription()).bind("creator", pollData.getCreatorPublicKey())
				.bind("owner", pollData.getOwner()).bind("published_when", pollData.getPublished()).bind("end_when", pollData.getEndTime())
				.bind("reduced_poll_name", Unicode.sanitize(pollData.getPollName()));

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save poll into repository", e);
		}

		pollData.setPollId(fetchPollId(pollData.getPollName()));
	}

	private void updatePoll(PollData pollData) throws DataException {
		String sql = "UPDATE Polls SET poll_name = ?, description = ?, creator = ?, owner = ?, published_when = ?, end_when = ?, reduced_poll_name = ? WHERE poll_id = ?";

		try {
			int modifiedRows = this.repository.executeCheckedUpdate(sql, pollData.getPollName(), pollData.getDescription(), pollData.getCreatorPublicKey(),
					pollData.getOwner(), pollData.getPublished(), pollData.getEndTime(), Unicode.sanitize(pollData.getPollName()), pollData.getPollId());

			if (modifiedRows == 0 && fromPollId(pollData.getPollId()) == null)
				throw new DataException("Unable to update missing poll in repository");
		} catch (SQLException e) {
			throw new DataException("Unable to update poll in repository", e);
		}
	}

	@Override
	public boolean hasVotes(int pollId) throws DataException {
		try {
			return this.repository.exists("PollVotes", "poll_id = ?", pollId);
		} catch (SQLException e) {
			throw new DataException("Unable to check poll votes in repository", e);
		}
	}

	@Override
	public void delete(String pollName) throws DataException {
		// NOTE: The corresponding poll state rows are deleted automatically by the database
		// thanks to "ON DELETE CASCADE" in the child tables' FOREIGN KEY definitions.
		try {
			this.repository.delete("Polls", "poll_name = ?", pollName);
		} catch (SQLException e) {
			throw new DataException("Unable to delete poll from repository", e);
		}
	}

	// Frozen poll results

	@Override
	public void freezeClosedPolls(int blockHeight, long blockTimestamp) throws DataException {
		int[] voteWeightPercents = BlockChain.getInstance().getAccountTrustStatusVoteWeightPercents(this.repository, blockHeight);
		String effectiveVoteWeightSql = HSQLDBTrustWeightSql.effectiveWeightSql(ACTIVE_TRUST_STATUS_SQL,
				RAW_VOTE_WEIGHT_SQL, voteWeightPercents);
		String trustWeightPercentSql = HSQLDBTrustWeightSql.trustWeightPercentSql(ACTIVE_TRUST_STATUS_SQL,
				voteWeightPercents);
		String frozenResultsSql = "INSERT INTO PollFrozenResults "
				+ "(poll_id, option_index, vote_count, vote_weight, raw_vote_weight, freeze_height, freeze_timestamp) "
				+ "SELECT p.poll_id, po.option_index, COUNT(pv.voter), "
				+ "COALESCE(SUM(" + effectiveVoteWeightSql + "), 0), "
				+ "COALESCE(SUM(" + RAW_VOTE_WEIGHT_SQL + "), 0), ?, ? "
				+ "FROM Polls p "
				+ "JOIN PollOptions po ON po.poll_id = p.poll_id "
				+ "LEFT JOIN PollVotes pv ON pv.poll_id = p.poll_id AND pv.option_index = po.option_index + 1 "
				+ "LEFT JOIN Accounts a ON pv.voter = a.public_key "
				+ "LEFT JOIN AccountTrustDerivationSnapshots ats ON ats.account = a.account "
				+ "AND ats.category = " + AccountRatingCategory.SUBJECT.value + " "
				+ "WHERE p.end_when IS NOT NULL AND p.end_when <= ? "
				+ "AND NOT EXISTS (SELECT TRUE FROM PollFrozenResults pfr WHERE pfr.poll_id = p.poll_id) "
				+ "GROUP BY p.poll_id, po.option_index";

		String frozenVoteDetailsSql = "INSERT INTO PollFrozenVoteDetails "
				+ "(poll_id, voter, option_index, raw_vote_weight, trust_status, trust_weight_percent, effective_vote_weight, freeze_height, freeze_timestamp) "
				+ "SELECT p.poll_id, pv.voter, pv.option_index, " + RAW_VOTE_WEIGHT_SQL + ", " + ACTIVE_TRUST_STATUS_SQL + ", "
				+ trustWeightPercentSql + ", " + effectiveVoteWeightSql + ", ?, ? "
				+ "FROM Polls p "
				+ "JOIN PollVotes pv ON pv.poll_id = p.poll_id "
				+ "LEFT JOIN Accounts a ON pv.voter = a.public_key "
				+ "LEFT JOIN AccountTrustDerivationSnapshots ats ON ats.account = a.account "
				+ "AND ats.category = " + AccountRatingCategory.SUBJECT.value + " "
				+ "WHERE EXISTS (SELECT TRUE FROM PollFrozenResults pfr WHERE pfr.poll_id = p.poll_id AND pfr.freeze_height = ?) "
				+ "AND NOT EXISTS (SELECT TRUE FROM PollFrozenVoteDetails pfv WHERE pfv.poll_id = p.poll_id)";

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
				+ "LEFT JOIN PollFrozenResults pfr ON pfr.poll_id = po.poll_id AND pfr.option_index = po.option_index "
				+ "WHERE po.poll_id = ? "
				+ "ORDER BY po.option_index ASC";

		Map<String, Integer> voteCountMap = new HashMap<>();
		Map<String, Integer> voteWeightMap = new HashMap<>();
		Map<String, Integer> rawVoteWeightMap = new HashMap<>();
		int totalVotes = 0;
		int totalWeight = 0;
		int rawTotalWeight = 0;

		try (ResultSet resultSet = this.repository.checkedExecute(sql, pollData.getPollId())) {
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
		Integer pollId = fetchPollIdIfExists(pollName);
		if (pollId == null)
			return new ArrayList<>();

		String sql = "SELECT voter, option_index, raw_vote_weight, trust_status, trust_weight_percent, effective_vote_weight, freeze_height, freeze_timestamp "
				+ "FROM PollFrozenVoteDetails "
				+ "WHERE poll_id = ? "
				+ "ORDER BY option_index ASC, voter ASC";
		List<PollVoteWeightData> voteWeights = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, pollId)) {
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
		PollData pollData = fromPollName(pollName);
		if (pollData == null)
			return new ArrayList<>();

		return getVotes(pollData.getPollId(), pollData.getPollName());
	}

	@Override
	public List<VoteOnPollData> getVotes(int pollId) throws DataException {
		PollData pollData = fromPollId(pollId);
		if (pollData == null)
			return new ArrayList<>();

		return getVotes(pollData.getPollId(), pollData.getPollName());
	}

	private List<VoteOnPollData> getVotes(int pollId, String pollName) throws DataException {
		String sql = "SELECT voter, option_index FROM PollVotes WHERE poll_id = ?";
		List<VoteOnPollData> votes = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, pollId)) {
			if (resultSet == null)
				return votes;

			// NOTE: do-while because checkedExecute() above has already called rs.next() for us
			do {
				byte[] voterPublicKey = resultSet.getBytes(1);
				int optionIndex = resultSet.getInt(2);

				votes.add(new VoteOnPollData(pollId, pollName, voterPublicKey, optionIndex));
			} while (resultSet.next());

			return votes;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch poll votes from repository", e);
		}
	}

	@Override
	public VoteOnPollData getVote(String pollName, byte[] voterPublicKey) throws DataException {
		PollData pollData = fromPollName(pollName);
		if (pollData == null)
			return null;

		return getVote(pollData.getPollId(), pollData.getPollName(), voterPublicKey);
	}

	@Override
	public VoteOnPollData getVote(int pollId, byte[] voterPublicKey) throws DataException {
		PollData pollData = fromPollId(pollId);
		if (pollData == null)
			return null;

		return getVote(pollData.getPollId(), pollData.getPollName(), voterPublicKey);
	}

	private VoteOnPollData getVote(int pollId, String pollName, byte[] voterPublicKey) throws DataException {
		String sql = "SELECT option_index FROM PollVotes WHERE poll_id = ? AND voter = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, pollId, voterPublicKey)) {
			if (resultSet == null)
				return null;

			int optionIndex = resultSet.getInt(1);

			return new VoteOnPollData(pollId, pollName, voterPublicKey, optionIndex);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch poll vote from repository", e);
		}
	}

	@Override
	public void save(VoteOnPollData voteOnPollData) throws DataException {
		Integer pollId = voteOnPollData.getPollId();
		if (pollId == null)
			pollId = requirePollId(voteOnPollData.getPollName());

		HSQLDBSaver saveHelper = new HSQLDBSaver("PollVotes");

		saveHelper.bind("poll_id", pollId).bind("voter", voteOnPollData.getVoterPublicKey())
				.bind("option_index", voteOnPollData.getOptionIndex());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save poll vote into repository", e);
		}
	}

	@Override
	public void delete(String pollName, byte[] voterPublicKey) throws DataException {
		Integer pollId = fetchPollIdIfExists(pollName);
		if (pollId == null)
			return;

		delete(pollId, voterPublicKey);
	}

	@Override
	public void delete(int pollId, byte[] voterPublicKey) throws DataException {
		try {
			this.repository.delete("PollVotes", "poll_id = ? AND voter = ?", pollId, voterPublicKey);
		} catch (SQLException e) {
			throw new DataException("Unable to delete poll vote from repository", e);
		}
	}

	private PollData buildPollData(ResultSet resultSet) throws SQLException, DataException {
		Integer pollId = getNullableInteger(resultSet, 1);
		String pollName = resultSet.getString(2);
		String description = resultSet.getString(3);
		byte[] creatorPublicKey = resultSet.getBytes(4);
		String owner = resultSet.getString(5);
		long published = resultSet.getLong(6);
		Long endTime = getNullableLong(resultSet, 7);

		return new PollData(pollId, creatorPublicKey, owner, pollName, description, getPollOptions(pollId), published, endTime);
	}

	private Integer fetchPollId(String pollName) throws DataException {
		Integer pollId = fetchPollIdIfExists(pollName);
		if (pollId == null)
			throw new DataException("Unable to fetch new poll ID from repository");

		return pollId;
	}

	private Integer fetchPollIdIfExists(String pollName) throws DataException {
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT poll_id FROM Polls WHERE poll_name = ?", pollName)) {
			if (resultSet == null)
				return null;

			return getNullableInteger(resultSet, 1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch poll ID from repository", e);
		}
	}

	private int requirePollId(String pollName) throws DataException {
		Integer pollId = fetchPollIdIfExists(pollName);
		if (pollId == null)
			throw new DataException("Unable to resolve poll ID in repository");

		return pollId;
	}

	private List<PollOptionData> getPollOptions(Integer pollId) throws DataException {
		if (pollId == null)
			return new ArrayList<>();

		String optionsSql = "SELECT option_name FROM PollOptions WHERE poll_id = ? ORDER BY option_index ASC";
		List<PollOptionData> pollOptions = new ArrayList<>();

		try (ResultSet optionsResultSet = this.repository.checkedExecute(optionsSql, pollId)) {
			if (optionsResultSet == null)
				return pollOptions;

			// NOTE: do-while because checkedExecute() above has already called rs.next() for us
			do {
				pollOptions.add(new PollOptionData(optionsResultSet.getString(1)));
			} while (optionsResultSet.next());

			return pollOptions;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch poll options from repository", e);
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
