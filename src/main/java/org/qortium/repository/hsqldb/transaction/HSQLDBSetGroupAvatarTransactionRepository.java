package org.qortium.repository.hsqldb.transaction;

import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.SetGroupAvatarTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.hsqldb.HSQLDBAvatars;
import org.qortium.repository.hsqldb.HSQLDBRepository;
import org.qortium.repository.hsqldb.HSQLDBSaver;
import java.sql.ResultSet;
import java.sql.SQLException;

public class HSQLDBSetGroupAvatarTransactionRepository extends HSQLDBTransactionRepository {
	public HSQLDBSetGroupAvatarTransactionRepository(HSQLDBRepository repository) { this.repository = repository; }
	TransactionData fromBase(BaseTransactionData base) throws DataException {
		try (ResultSet rs = this.repository.checkedExecute("SELECT group_id, avatar_service, avatar_name, avatar_identifier, group_reference FROM SetGroupAvatarTransactions WHERE signature = ?", base.getSignature())) {
			if (rs == null) return null;
			return new SetGroupAvatarTransactionData(base, rs.getInt(1), HSQLDBAvatars.read(rs, 2), rs.getBytes(5));
		} catch (SQLException e) { throw new DataException("Unable to fetch set group avatar transaction from repository", e); }
	}
	@Override public void save(TransactionData data) throws DataException {
		SetGroupAvatarTransactionData tx = (SetGroupAvatarTransactionData) data;
		HSQLDBSaver save = new HSQLDBSaver("SetGroupAvatarTransactions");
		save.bind("signature", tx.getSignature()).bind("owner", tx.getOwnerPublicKey()).bind("group_id", tx.getGroupId());
		HSQLDBAvatars.bind(save, "avatar", tx.getAvatar());
		save.bind("group_reference", tx.getGroupReference());
		try { save.execute(this.repository); } catch (SQLException e) { throw new DataException("Unable to save set group avatar transaction", e); }
	}
}
