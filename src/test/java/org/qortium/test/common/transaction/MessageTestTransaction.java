package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.MessageTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transaction.Transaction;
import org.qortium.utils.Amounts;

public class MessageTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		final int nonce = 0;
		String recipient = account.getAddress();
		final long assetId = Asset.NATIVE;
		long amount = 123L * Amounts.MULTIPLIER;
		byte[] data = "message contents".getBytes();
		final boolean isText = true;
		final boolean isEncrypted = false;
		BaseTransactionData baseTransactionData = generateBase(account);
		final int version = Transaction.getVersionByTimestamp(baseTransactionData.getTimestamp());

		return new MessageTransactionData(baseTransactionData, version, nonce, recipient, amount, assetId, data, isText, isEncrypted);
	}

}
