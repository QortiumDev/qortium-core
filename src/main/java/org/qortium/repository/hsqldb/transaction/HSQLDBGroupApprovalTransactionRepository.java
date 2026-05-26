package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.GroupApprovalTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBGroupApprovalTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBGroupApprovalTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT pending_signature, approval, prior_reference FROM GroupApprovalTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			byte[] pendingSignature = resultSet.getBytes(1);
			boolean approval = resultSet.getBoolean(2);
			byte[] priorReference = resultSet.getBytes(3);

			return new GroupApprovalTransactionData(baseTransactionData, pendingSignature, approval, priorReference);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group approval transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		GroupApprovalTransactionData groupApprovalTransactionData = (GroupApprovalTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("GroupApprovalTransactions");

		saveHelper.bind("signature", groupApprovalTransactionData.getSignature()).bind("admin", groupApprovalTransactionData.getAdminPublicKey())
				.bind("pending_signature", groupApprovalTransactionData.getPendingSignature()).bind("approval", groupApprovalTransactionData.getApproval())
				.bind("prior_reference", groupApprovalTransactionData.getPriorReference());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save group approval transaction into repository", e);
		}
	}

}
