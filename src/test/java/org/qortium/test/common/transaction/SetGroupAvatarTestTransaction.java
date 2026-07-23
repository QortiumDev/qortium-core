package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.arbitrary.misc.Service;
import org.qortium.data.avatar.AvatarData;
import org.qortium.data.transaction.SetGroupAvatarTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Random;

public class SetGroupAvatarTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		final int groupId = 1;
		AvatarData avatar = new AvatarData(Service.IMAGE, "test-avatar", "avatar-" + new Random().nextInt(1_000_000));
		return new SetGroupAvatarTransactionData(generateBase(account), groupId, avatar);
	}

}
