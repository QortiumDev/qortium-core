package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.SetAccountAvatarTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBSetAccountAvatarTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBSetAccountAvatarTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData base) throws DataException {
		String sql = "SELECT avatar_signature, previous_avatar_signature FROM SetAccountAvatarTransactions WHERE signature = ?";
		try (ResultSet resultSet = this.repository.checkedExecute(sql, base.getSignature())) {
			if (resultSet == null) return null;
			return new SetAccountAvatarTransactionData(base, resultSet.getBytes(1), resultSet.getBytes(2));
		} catch (SQLException e) {
			throw new DataException("Unable to fetch set account avatar transaction", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		SetAccountAvatarTransactionData tx = (SetAccountAvatarTransactionData) transactionData;
		HSQLDBSaver save = new HSQLDBSaver("SetAccountAvatarTransactions");
		save.bind("signature", tx.getSignature()).bind("owner", tx.getOwnerPublicKey())
				.bind("avatar_signature", tx.getAvatarSignature()).bind("previous_avatar_signature", tx.getPreviousAvatarSignature());
		try {
			save.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save set account avatar transaction", e);
		}
	}
}
