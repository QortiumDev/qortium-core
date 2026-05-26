package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.block.BlockChain;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;

import java.util.Random;

public abstract class TestTransaction {

	protected static final Random random = new Random();

	public static BaseTransactionData generateBase(PrivateKeyAccount account, int txGroupId) throws DataException {
		long timestamp = System.currentTimeMillis();
		return new BaseTransactionData(timestamp, txGroupId, account.getPublicKey(), BlockChain.getInstance().getUnitFeeAtTimestamp(timestamp), null);
	}

	public static BaseTransactionData generateBase(PrivateKeyAccount account) throws DataException {
		return generateBase(account, Group.NO_GROUP);
	}

}
