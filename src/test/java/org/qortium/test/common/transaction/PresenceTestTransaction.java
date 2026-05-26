package org.qortium.test.common.transaction;

import com.google.common.primitives.Longs;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.data.transaction.PresenceTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transaction.PresenceTransaction.PresenceType;
import org.qortium.utils.NTP;

public class PresenceTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		final int nonce = 0;

		byte[] tradePrivateKey = new byte[32];
		PrivateKeyAccount tradeNativeAccount = new PrivateKeyAccount(repository, tradePrivateKey);
		long timestamp = NTP.getTime();
		byte[] timestampSignature = tradeNativeAccount.sign(Longs.toByteArray(timestamp));

		return new PresenceTransactionData(generateBase(account), nonce, PresenceType.TRADE_BOT, timestampSignature);
	}

}
