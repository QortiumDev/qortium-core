package org.qortal.test.common.transaction;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Amounts;

public class MessageTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		final int nonce = 0;
		String recipient = account.getAddress();
		final long assetId = Asset.QORT;
		long amount = 123L * Amounts.MULTIPLIER;
		byte[] data = "message contents".getBytes();
		final boolean isText = true;
		final boolean isEncrypted = false;
		BaseTransactionData baseTransactionData = generateBase(account);
		final int version = Transaction.getVersionByTimestamp(baseTransactionData.getTimestamp());

		return new MessageTransactionData(baseTransactionData, version, nonce, recipient, amount, assetId, data, isText, isEncrypted);
	}

}
