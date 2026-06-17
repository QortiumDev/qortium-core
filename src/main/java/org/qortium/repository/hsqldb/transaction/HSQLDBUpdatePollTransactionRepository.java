package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdatePollTransactionData;
import org.qortium.data.voting.PollOptionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class HSQLDBUpdatePollTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBUpdatePollTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT poll_id, new_poll_name, new_description, new_start_when, new_end_when, "
				+ "previous_poll_name, previous_description, previous_start_when, previous_end_when "
				+ "FROM UpdatePollTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			int pollId = resultSet.getInt(1);
			String newPollName = resultSet.getString(2);
			String newDescription = resultSet.getString(3);
			Long newStartTime = getNullableLong(resultSet, 4);
			Long newEndTime = getNullableLong(resultSet, 5);
			String previousPollName = resultSet.getString(6);
			String previousDescription = resultSet.getString(7);
			Long previousStartTime = getNullableLong(resultSet, 8);
			Long previousEndTime = getNullableLong(resultSet, 9);

			List<PollOptionData> newPollOptions = getOptions("UpdatePollTransactionOptions", baseTransactionData.getSignature());
			List<PollOptionData> previousPollOptions = previousPollName == null ? null
					: getOptions("UpdatePollTransactionPreviousOptions", baseTransactionData.getSignature());

			return new UpdatePollTransactionData(baseTransactionData, pollId, newPollName, newDescription, newPollOptions,
					newStartTime, newEndTime, previousPollName, previousDescription, previousPollOptions, previousStartTime, previousEndTime);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch update poll transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		UpdatePollTransactionData updatePollTransactionData = (UpdatePollTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("UpdatePollTransactions");

		saveHelper.bind("signature", updatePollTransactionData.getSignature())
				.bind("owner", updatePollTransactionData.getOwnerPublicKey())
				.bind("poll_id", updatePollTransactionData.getPollId())
				.bind("new_poll_name", updatePollTransactionData.getNewPollName())
				.bind("new_description", updatePollTransactionData.getNewDescription())
				.bind("new_start_when", updatePollTransactionData.getNewStartTime())
				.bind("new_end_when", updatePollTransactionData.getNewEndTime())
				.bind("previous_poll_name", updatePollTransactionData.getPreviousPollName())
				.bind("previous_description", updatePollTransactionData.getPreviousDescription())
				.bind("previous_start_when", updatePollTransactionData.getPreviousStartTime())
				.bind("previous_end_when", updatePollTransactionData.getPreviousEndTime());

		try {
			saveHelper.execute(this.repository);
			saveOptions("UpdatePollTransactionOptions", updatePollTransactionData.getSignature(), updatePollTransactionData.getNewPollOptions());
			saveOptions("UpdatePollTransactionPreviousOptions", updatePollTransactionData.getSignature(), updatePollTransactionData.getPreviousPollOptions());
		} catch (SQLException e) {
			throw new DataException("Unable to save update poll transaction into repository", e);
		}
	}

	private List<PollOptionData> getOptions(String tableName, byte[] signature) throws SQLException {
		String sql = String.format("SELECT option_name FROM %s WHERE signature = ? ORDER BY option_index ASC", tableName);
		List<PollOptionData> pollOptions = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, signature)) {
			if (resultSet == null)
				return pollOptions;

			do {
				pollOptions.add(new PollOptionData(resultSet.getString(1)));
			} while (resultSet.next());

			return pollOptions;
		}
	}

	private void saveOptions(String tableName, byte[] signature, List<PollOptionData> pollOptions) throws SQLException {
		this.repository.delete(tableName, "signature = ?", signature);

		if (pollOptions == null)
			return;

		for (int optionIndex = 0; optionIndex < pollOptions.size(); ++optionIndex) {
			HSQLDBSaver optionSaveHelper = new HSQLDBSaver(tableName);
			optionSaveHelper.bind("signature", signature)
					.bind("option_index", optionIndex)
					.bind("option_name", pollOptions.get(optionIndex).getOptionName());
			optionSaveHelper.execute(this.repository);
		}
	}

	private static Long getNullableLong(ResultSet resultSet, int columnIndex) throws SQLException {
		long value = resultSet.getLong(columnIndex);
		return resultSet.wasNull() ? null : value;
	}

}
