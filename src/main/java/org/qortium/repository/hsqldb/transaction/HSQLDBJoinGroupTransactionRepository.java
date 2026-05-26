package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.JoinGroupTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBJoinGroupTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBJoinGroupTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData baseTransactionData) throws DataException {
		String sql = "SELECT group_id, minting_public_key, minting_authorization_created, invite_reference, previous_group_id FROM JoinGroupTransactions WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, baseTransactionData.getSignature())) {
			if (resultSet == null)
				return null;

			int groupId = resultSet.getInt(1);
			byte[] mintingPublicKey = resultSet.getBytes(2);
			boolean mintingAuthorizationCreated = resultSet.getBoolean(3);
			byte[] inviteReference = resultSet.getBytes(4);

			Integer previousGroupId = resultSet.getInt(5);
			if (previousGroupId == 0 && resultSet.wasNull())
				previousGroupId = null;

			return new JoinGroupTransactionData(baseTransactionData, groupId, mintingPublicKey,
					mintingAuthorizationCreated, inviteReference, previousGroupId);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch join group transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		JoinGroupTransactionData joinGroupTransactionData = (JoinGroupTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("JoinGroupTransactions");

		saveHelper.bind("signature", joinGroupTransactionData.getSignature()).bind("joiner", joinGroupTransactionData.getJoinerPublicKey())
				.bind("group_id", joinGroupTransactionData.getGroupId()).bind("minting_public_key", joinGroupTransactionData.getMintingPublicKey())
				.bind("minting_authorization_created", joinGroupTransactionData.isMintingAuthorizationCreated())
				.bind("invite_reference", joinGroupTransactionData.getInviteReference())
				.bind("previous_group_id", joinGroupTransactionData.getPreviousGroupId());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save join group transaction into repository", e);
		}
	}

}
