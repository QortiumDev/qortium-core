package org.qortium.transaction;
import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.avatar.AvatarResource;
import org.qortium.block.BlockChain;
import org.qortium.data.transaction.SetAccountAvatarTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Collections;
import java.util.List;
public class SetAccountAvatarTransaction extends Transaction {
	private final SetAccountAvatarTransactionData data;
	public SetAccountAvatarTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);
		this.data = (SetAccountAvatarTransactionData) transactionData;
	}

	@Override
	public List<String> getRecipientAddresses() { return Collections.emptyList(); }

	@Override
	public ValidationResult isValid() throws DataException {
		if (this.data.getTxGroupId() != Group.NO_GROUP) return ValidationResult.INVALID_TX_GROUP_ID;
		if (this.repository.getBlockRepository().getBlockchainHeight() + 1L < BlockChain.getInstance().getAvatarTransactionsHeight())
			return ValidationResult.NOT_YET_RELEASED;
		Account account = this.getCreator();
		byte[] avatar = this.data.getAvatarSignature();
		if (avatar != null) {
			ValidationResult result = AvatarResource.validate(this.repository, avatar, account.getAddress());
			if (result != ValidationResult.OK) return result;
		}
		return account.getConfirmedBalance(Asset.NATIVE) < this.data.getFee() ? ValidationResult.NO_BALANCE : ValidationResult.OK;
	}

	@Override public ValidationResult isProcessable() { return ValidationResult.OK; }

	@Override
	public void process() throws DataException {
		String address = this.getCreator().getAddress();
		this.data.setPreviousAvatarSignature(this.repository.getAccountRepository().getAvatarSignature(address));
		this.repository.getAccountRepository().setAvatarSignature(address, this.data.getAvatarSignature());
		this.repository.getTransactionRepository().save(this.data);
	}

	@Override
	public void orphan() throws DataException {
		this.repository.getAccountRepository().setAvatarSignature(this.getCreator().getAddress(), this.data.getPreviousAvatarSignature());
		this.data.setPreviousAvatarSignature(null);
		this.repository.getTransactionRepository().save(this.data);
	}
}
