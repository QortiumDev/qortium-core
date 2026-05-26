package org.qortium.test.common.transaction;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.block.BlockChain;
import org.qortium.block.ChainParameter;
import org.qortium.data.transaction.ChainParameterUpdateTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.test.common.TestChainBootstrapUtils;

public class ChainParameterUpdateTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid)
			throws DataException {
		int activationHeight = repository.getBlockRepository().getBlockchainHeight()
				+ BlockChain.getInstance().getChainParameterUpdateMinActivationDelay() + 10;
		long blockReward = BlockChain.getInstance().getRewardAtHeight(repository, activationHeight);

		return new ChainParameterUpdateTransactionData(
				generateBase(account, TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID),
				ChainParameter.BLOCK_REWARD.id,
				activationHeight,
				ChainParameter.BLOCK_REWARD.encodeLongValue(blockReward));
	}
}
