package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.crypto.Crypto;
import org.qortium.data.transaction.ATTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.utils.Amounts;

public class AtTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		return AtTestTransaction.paymentType(repository, account, wantValid);
	}

	public static TransactionData paymentType(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		byte[] signature = new byte[64];
		random.nextBytes(signature);
		String atAddress = Crypto.toATAddress(signature);
		String recipient = account.getAddress();

		// Use PAYMENT-type
		long amount = 123L * Amounts.MULTIPLIER;
		final long assetId = Asset.NATIVE;

		return new ATTransactionData(generateBase(account), atAddress, recipient, amount, assetId);
	}

	public static TransactionData messageType(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		byte[] signature = new byte[64];
		random.nextBytes(signature);
		String atAddress = Crypto.toATAddress(signature);
		String recipient = account.getAddress();

		// Use MESSAGE-type
		byte[] message = new byte[32];
		random.nextBytes(message);

		return new ATTransactionData(generateBase(account), atAddress, recipient, message);
	}

}
