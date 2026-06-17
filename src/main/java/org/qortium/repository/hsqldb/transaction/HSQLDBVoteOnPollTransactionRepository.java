package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.VoteOnPollTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HSQLDBVoteOnPollTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBVoteOnPollTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT poll_id, option_index, previous_option_index FROM VoteOnPollTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			int pollId = resultSet.getInt(1);
			int optionIndex = resultSet.getInt(2);

			// Special null-checking for previous option index
			Integer previousOptionIndex = resultSet.getInt(3);
			if (previousOptionIndex == 0 && resultSet.wasNull())
				previousOptionIndex = null;

			List<Integer> optionIndexes = getOptionIndexes("VoteOnPollTransactionOptions", baseTransactionData.getSignature());
			if (optionIndexes.isEmpty() && optionIndex != 0)
				optionIndexes = Collections.singletonList(optionIndex);

			List<Integer> previousOptionIndexes = getOptionIndexes("VoteOnPollTransactionPreviousOptions", baseTransactionData.getSignature());
			if (previousOptionIndexes.isEmpty() && previousOptionIndex != null)
				previousOptionIndexes = Collections.singletonList(previousOptionIndex);

			return new VoteOnPollTransactionData(baseTransactionData, pollId, optionIndexes,
					previousOptionIndexes.isEmpty() ? null : previousOptionIndexes);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch vote on poll transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		VoteOnPollTransactionData voteOnPollTransactionData = (VoteOnPollTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("VoteOnPollTransactions");

		saveHelper.bind("signature", voteOnPollTransactionData.getSignature()).bind("poll_id", voteOnPollTransactionData.getPollId())
				.bind("voter", voteOnPollTransactionData.getVoterPublicKey()).bind("option_index", voteOnPollTransactionData.getOptionIndex())
				.bind("previous_option_index", voteOnPollTransactionData.getPreviousOptionIndex());

		try {
			saveHelper.execute(this.repository);
			saveOptionIndexes("VoteOnPollTransactionOptions", voteOnPollTransactionData.getSignature(),
					voteOnPollTransactionData.getSelectedOptionIndexes());
			saveOptionIndexes("VoteOnPollTransactionPreviousOptions", voteOnPollTransactionData.getSignature(),
					voteOnPollTransactionData.getPreviousOptionIndexes());
		} catch (SQLException e) {
			throw new DataException("Unable to save vote on poll transaction into repository", e);
		}
	}

	private List<Integer> getOptionIndexes(String tableName, byte[] signature) throws SQLException {
		String sql = String.format("SELECT option_index FROM %s WHERE signature = ? ORDER BY option_index ASC", tableName);
		List<Integer> optionIndexes = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, signature)) {
			if (resultSet == null)
				return optionIndexes;

			do {
				optionIndexes.add(resultSet.getInt(1));
			} while (resultSet.next());

			return optionIndexes;
		}
	}

	private void saveOptionIndexes(String tableName, byte[] signature, List<Integer> optionIndexes) throws SQLException {
		this.repository.delete(tableName, "signature = ?", signature);

		if (optionIndexes == null)
			return;

		for (Integer optionIndex : optionIndexes) {
			HSQLDBSaver saveHelper = new HSQLDBSaver(tableName);
			saveHelper.bind("signature", signature).bind("option_index", optionIndex);
			saveHelper.execute(this.repository);
		}
	}

}
