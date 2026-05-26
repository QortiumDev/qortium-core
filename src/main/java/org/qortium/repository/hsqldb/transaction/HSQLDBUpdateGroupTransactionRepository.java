package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdateGroupTransactionData;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBUpdateGroupTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBUpdateGroupTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT group_id, new_name, new_description, new_is_open, new_approval_threshold, "
				+ "new_min_block_delay, new_max_block_delay, reduced_new_name, group_reference FROM UpdateGroupTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			int groupId = resultSet.getInt(1);
			String newName = resultSet.getString(2);
			String newDescription = resultSet.getString(3);
			boolean newIsOpen = resultSet.getBoolean(4);
			ApprovalThreshold newApprovalThreshold = ApprovalThreshold.valueOf(resultSet.getInt(5));
			int newMinBlockDelay = resultSet.getInt(6);
			int newMaxBlockDelay = resultSet.getInt(7);
			String reducedNewName = resultSet.getString(8);
			byte[] groupReference = resultSet.getBytes(9);

			return new UpdateGroupTransactionData(baseTransactionData, groupId, newName, newDescription, newIsOpen,
					newApprovalThreshold, newMinBlockDelay, newMaxBlockDelay, reducedNewName, groupReference);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch update group transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		UpdateGroupTransactionData updateGroupTransactionData = (UpdateGroupTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("UpdateGroupTransactions");

		saveHelper.bind("signature", updateGroupTransactionData.getSignature()).bind("owner", updateGroupTransactionData.getOwnerPublicKey())
				.bind("group_id", updateGroupTransactionData.getGroupId()).bind("new_name", updateGroupTransactionData.getNewName())
				.bind("reduced_new_name", updateGroupTransactionData.getReducedNewName())
				.bind("new_description", updateGroupTransactionData.getNewDescription()).bind("new_is_open", updateGroupTransactionData.getNewIsOpen())
				.bind("new_approval_threshold", updateGroupTransactionData.getNewApprovalThreshold().value)
				.bind("new_min_block_delay", updateGroupTransactionData.getNewMinimumBlockDelay())
				.bind("new_max_block_delay", updateGroupTransactionData.getNewMaximumBlockDelay())
				.bind("group_reference", updateGroupTransactionData.getGroupReference());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save update group transaction into repository", e);
		}
	}

}
