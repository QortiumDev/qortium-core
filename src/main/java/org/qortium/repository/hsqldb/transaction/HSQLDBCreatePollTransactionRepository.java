package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.CreatePollTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.voting.PollOptionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class HSQLDBCreatePollTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBCreatePollTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT owner, poll_name, description, start_when, end_when, poll_id FROM CreatePollTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			String owner = resultSet.getString(1);
			String pollName = resultSet.getString(2);
			String description = resultSet.getString(3);
			Long startTime = getNullableLong(resultSet, 4);
			Long endTime = getNullableLong(resultSet, 5);
			Integer pollId = resultSet.getInt(6);
			if (pollId == 0 && resultSet.wasNull())
				pollId = null;

			String optionsSql = "SELECT option_name FROM CreatePollTransactionOptions WHERE signature = ? ORDER BY option_index ASC";

			try (ResultSet optionsResultSet = this.repository.checkedExecute(optionsSql, baseTransactionData.getSignature())) {
				if (optionsResultSet == null)
					return null;

				List<PollOptionData> pollOptions = new ArrayList<>();

				// NOTE: do-while because checkedExecute() above has already called rs.next() for us
				do {
					String optionName = optionsResultSet.getString(1);

					pollOptions.add(new PollOptionData(optionName));
				} while (optionsResultSet.next());

				return new CreatePollTransactionData(baseTransactionData, owner, pollName, description, pollOptions, startTime, endTime, pollId);
			}
		} catch (SQLException e) {
			throw new DataException("Unable to fetch create poll transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		CreatePollTransactionData createPollTransactionData = (CreatePollTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("CreatePollTransactions");

		saveHelper.bind("signature", createPollTransactionData.getSignature()).bind("creator", createPollTransactionData.getCreatorPublicKey())
				.bind("owner", createPollTransactionData.getOwner()).bind("poll_name", createPollTransactionData.getPollName())
				.bind("description", createPollTransactionData.getDescription()).bind("start_when", createPollTransactionData.getStartTime())
				.bind("end_when", createPollTransactionData.getEndTime())
				.bind("poll_id", createPollTransactionData.getPollId());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save create poll transaction into repository", e);
		}

		// Now attempt to save poll options
		List<PollOptionData> pollOptions = createPollTransactionData.getPollOptions();
		for (int optionIndex = 0; optionIndex < pollOptions.size(); ++optionIndex) {
			PollOptionData pollOptionData = pollOptions.get(optionIndex);

			HSQLDBSaver optionSaveHelper = new HSQLDBSaver("CreatePollTransactionOptions");

			optionSaveHelper.bind("signature", createPollTransactionData.getSignature()).bind("option_name", pollOptionData.getOptionName())
					.bind("option_index", optionIndex);

			try {
				optionSaveHelper.execute(this.repository);
			} catch (SQLException e) {
				throw new DataException("Unable to save create poll transaction option into repository", e);
			}
		}
	}

	private static Long getNullableLong(ResultSet resultSet, int columnIndex) throws SQLException {
		long value = resultSet.getLong(columnIndex);
		return resultSet.wasNull() ? null : value;
	}

}
