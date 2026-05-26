package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.arbitrary.misc.Service;
import org.qortium.asset.Asset;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.PaymentData;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.ArbitraryTransactionData.DataType;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transaction.Transaction;
import org.qortium.utils.Amounts;

import java.util.ArrayList;
import java.util.List;

public class ArbitraryTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		final Service service = Service.ARBITRARY_DATA;
		final int nonce = 0;
		final int size = 4 * 1024 * 1024;
		final String name = "TEST";
		final String identifier = "qdn_avatar";
		final ArbitraryTransactionData.Method method = ArbitraryTransactionData.Method.PUT;

		final byte[] secret = new byte[32];
		random.nextBytes(secret);

        final ArbitraryTransactionData.Compression compression = ArbitraryTransactionData.Compression.ZIP;

		final byte[] metadataHash = new byte[32];
		random.nextBytes(metadataHash);

		byte[] data = new byte[256];
		random.nextBytes(data);

		DataType dataType = DataType.RAW_DATA;

		String recipient = account.getAddress();
		final long assetId = Asset.NATIVE;
		long amount = 123L * Amounts.MULTIPLIER;

		List<PaymentData> payments = new ArrayList<>();
		payments.add(new PaymentData(recipient, assetId, amount));
		BaseTransactionData baseTransactionData = generateBase(account);
		final int version = Transaction.getVersionByTimestamp(baseTransactionData.getTimestamp());

		return new ArbitraryTransactionData(baseTransactionData, version, service.value, nonce, size,name, identifier,
				method, secret, compression, data, dataType, metadataHash, payments);
	}

}
