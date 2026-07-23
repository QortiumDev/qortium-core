package org.qortium.transaction;
import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.avatar.AvatarResource;
import org.qortium.block.BlockChain;
import org.qortium.data.avatar.AvatarData;
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
		AvatarData avatar = this.data.getAvatar();
		if (avatar != null) {
			ValidationResult result = AvatarResource.validate(avatar.getService(), avatar.getName(), avatar.getIdentifier());
			if (result != ValidationResult.OK) return result;
		}
		return account.getConfirmedBalance(Asset.NATIVE) < this.data.getFee() ? ValidationResult.NO_BALANCE : ValidationResult.OK;
	}

	@Override public ValidationResult isProcessable() { return ValidationResult.OK; }

	@Override
	public void process() throws DataException {
		String address = this.getCreator().getAddress();
		this.data.setPreviousAvatar(this.repository.getAccountRepository().getAvatar(address));
		this.repository.getAccountRepository().setAvatar(address, this.data.getAvatar());
		this.repository.getTransactionRepository().save(this.data);
	}

	@Override
	public void orphan() throws DataException {
		this.repository.getAccountRepository().setAvatar(this.getCreator().getAddress(), this.data.getPreviousAvatar());
		this.data.setPreviousAvatar(null);
		this.repository.getTransactionRepository().save(this.data);
	}
}
