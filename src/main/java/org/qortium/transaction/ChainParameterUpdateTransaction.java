package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.block.ChainParameter;
import org.qortium.data.blockchain.ChainParameterData;
import org.qortium.data.transaction.ChainParameterUpdateTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.utils.Groups;

import java.util.Collections;
import java.util.List;

public class ChainParameterUpdateTransaction extends Transaction {

	private final ChainParameterUpdateTransactionData chainParameterUpdateTransactionData;

	public ChainParameterUpdateTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.chainParameterUpdateTransactionData = (ChainParameterUpdateTransactionData) this.transactionData;
	}

	@Override
	public List<String> getRecipientAddresses() {
		return Collections.emptyList();
	}

	@Override
	public boolean needsGroupApproval() {
		return true;
	}

	@Override
	public ValidationResult isValid() throws DataException {
		ValidationResult staticValidation = validateParameterUpdateForHeight(getNextBlockHeight());
		if (staticValidation != ValidationResult.OK)
			return staticValidation;

		Account updater = getCreator();
		Long fee = this.chainParameterUpdateTransactionData.getFee();
		if (fee == null)
			return ValidationResult.INSUFFICIENT_FEE;

		if (updater.getConfirmedBalance(Asset.NATIVE) < fee)
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		ValidationResult validationResult = validateParameterUpdateForHeight(getNextBlockHeight());
		if (validationResult != ValidationResult.OK)
			return validationResult;

		if (this.repository.getChainParameterRepository().hasParameterAtHeight(
				this.chainParameterUpdateTransactionData.getParameterId(),
				this.chainParameterUpdateTransactionData.getActivationHeight()))
			return ValidationResult.TRANSACTION_ALREADY_EXISTS;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		ChainParameterData chainParameterData = new ChainParameterData(
				this.chainParameterUpdateTransactionData.getSignature(),
				this.chainParameterUpdateTransactionData.getParameterId(),
				this.chainParameterUpdateTransactionData.getActivationHeight(),
				this.chainParameterUpdateTransactionData.getValue());

		this.repository.getChainParameterRepository().save(chainParameterData);
	}

	@Override
	public void orphan() throws DataException {
		this.repository.getChainParameterRepository().delete(this.chainParameterUpdateTransactionData.getSignature());
	}

	private ValidationResult validateParameterUpdateForHeight(int approvalHeight) throws DataException {
		int txGroupId = this.chainParameterUpdateTransactionData.getTxGroupId();
		if (txGroupId == Group.NO_GROUP)
			return ValidationResult.INVALID_TX_GROUP_ID;

		List<Integer> activeDevGroupIds = Groups.getGroupIdsAtHeight(BlockChain.getInstance().getDevGroupIds(), approvalHeight);
		if (!activeDevGroupIds.contains(txGroupId))
			return ValidationResult.INVALID_TX_GROUP_ID;

		ChainParameter parameter = ChainParameter.valueOf(this.chainParameterUpdateTransactionData.getParameterId());
		if (parameter == null)
			return ValidationResult.NOT_SUPPORTED;

		if (!parameter.isValidValue(this.chainParameterUpdateTransactionData.getValue()))
			return ValidationResult.INVALID_VALUE_LENGTH;

		long minimumActivationHeight = (long) approvalHeight + BlockChain.getInstance().getChainParameterUpdateMinActivationDelay();
		if (this.chainParameterUpdateTransactionData.getActivationHeight() < minimumActivationHeight)
			return ValidationResult.INVALID_LIFETIME;

		if (parameter == ChainParameter.BLOCK_REWARD && !isValidBlockRewardActivationHeight(
				this.chainParameterUpdateTransactionData.getActivationHeight()))
			return ValidationResult.INVALID_LIFETIME;

		if (!parameter.isValidValue(this.repository, this.chainParameterUpdateTransactionData.getActivationHeight(),
				this.chainParameterUpdateTransactionData.getValue()))
			return ValidationResult.INVALID_VALUE_LENGTH;

		return ValidationResult.OK;
	}

	private boolean isValidBlockRewardActivationHeight(int activationHeight) {
		long batchStartHeight = BlockChain.getInstance().getBlockRewardBatchStartHeight();
		if (activationHeight <= batchStartHeight)
			return true;

		int batchSize = BlockChain.getInstance().getBlockRewardBatchSize();
		long firstBatchedHeight = batchStartHeight + 1L;
		return (activationHeight - firstBatchedHeight) % batchSize == 0;
	}

	private int getNextBlockHeight() throws DataException {
		return this.repository.getBlockRepository().getBlockchainHeight() + 1;
	}
}
