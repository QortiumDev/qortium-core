package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.crypto.Crypto;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Random;

public class ChatTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		Random random = new Random();
		byte[] orderId = new byte[64];
		random.nextBytes(orderId);

		String sender = Crypto.toAddress(account.getPublicKey());
		int nonce = 1234567;

		// Generate random recipient
		byte[] randomPrivateKey = new byte[32];
		random.nextBytes(randomPrivateKey);
		PrivateKeyAccount recipientAccount = new PrivateKeyAccount(repository, randomPrivateKey);
		String recipient = Crypto.toAddress(recipientAccount.getPublicKey());

		byte[] chatReference = new byte[64];
		random.nextBytes(chatReference);

		byte[] data = new byte[4000];
		random.nextBytes(data);

		boolean isText = true;
		boolean isEncrypted = true;

		return new ChatTransactionData(generateBase(account), sender, nonce, recipient, chatReference, data, isText, isEncrypted);
	}

}
