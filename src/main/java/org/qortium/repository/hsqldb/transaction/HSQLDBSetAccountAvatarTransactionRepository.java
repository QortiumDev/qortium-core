package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.SetAccountAvatarTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBAvatars;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;

import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBSetAccountAvatarTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBSetAccountAvatarTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(BaseTransactionData base) throws DataException {
		String sql = "SELECT avatar_service, avatar_name, avatar_identifier, previous_avatar_service, previous_avatar_name, previous_avatar_identifier FROM SetAccountAvatarTransactions WHERE signature = ?";
		try (ResultSet resultSet = this.repository.checkedExecute(sql, base.getSignature())) {
			if (resultSet == null) return null;
			return new SetAccountAvatarTransactionData(base, HSQLDBAvatars.read(resultSet, 1), HSQLDBAvatars.read(resultSet, 4));
		} catch (SQLException e) {
			throw new DataException("Unable to fetch set account avatar transaction", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		SetAccountAvatarTransactionData tx = (SetAccountAvatarTransactionData) transactionData;
		HSQLDBSaver save = new HSQLDBSaver("SetAccountAvatarTransactions");
		save.bind("signature", tx.getSignature()).bind("owner", tx.getOwnerPublicKey());
		HSQLDBAvatars.bind(save, "avatar", tx.getAvatar());
		HSQLDBAvatars.bind(save, "previous_avatar", tx.getPreviousAvatar());
		try {
			save.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save set account avatar transaction", e);
		}
	}
}
