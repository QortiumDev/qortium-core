package org.qortal.test.common.transaction;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.BlockChain;
import org.qortal.block.ChainParameter;
import org.qortal.data.transaction.ChainParameterUpdateTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.test.common.TestChainBootstrapUtils;

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
