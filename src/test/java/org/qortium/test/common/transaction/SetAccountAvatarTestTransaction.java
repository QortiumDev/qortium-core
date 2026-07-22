package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.SetAccountAvatarTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Random;

public class SetAccountAvatarTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		byte[] avatarSignature = new byte[64];
		new Random().nextBytes(avatarSignature);

		return new SetAccountAvatarTransactionData(generateBase(account), avatarSignature);
	}

}
