package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.avatar.AvatarResource;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.data.avatar.AvatarData;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.SetGroupAvatarTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Collections;
import java.util.List;

/** Points a group's avatar at a QDN resource. The setter must be the group owner
 *  (or group approval for a null-owner group); the target resource is unrestricted. */
public class SetGroupAvatarTransaction extends Transaction {
	private final SetGroupAvatarTransactionData setGroupAvatarTransactionData;
	public SetGroupAvatarTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData); this.setGroupAvatarTransactionData = (SetGroupAvatarTransactionData) transactionData;
	}
	@Override public List<String> getRecipientAddresses() { return Collections.emptyList(); }
	public Account getOwner() { return this.getCreator(); }

	@Override public ValidationResult isValid() throws DataException {
		long nextHeight = this.repository.getBlockRepository().getBlockchainHeight() + 1L;
		if (nextHeight < BlockChain.getInstance().getAvatarTransactionsHeight()) return ValidationResult.NOT_YET_RELEASED;
		GroupData groupData = this.repository.getGroupRepository().fromGroupId(this.setGroupAvatarTransactionData.getGroupId());
		if (groupData == null) return ValidationResult.GROUP_DOES_NOT_EXIST;
		boolean nullOwner = Group.isNullOwner(groupData.getOwner());
		if (nullOwner && !this.needsGroupApproval()) return ValidationResult.GROUP_APPROVAL_REQUIRED;
		if (nullOwner && this.setGroupAvatarTransactionData.getTxGroupId() != groupData.getGroupId()) return ValidationResult.TX_GROUP_ID_MISMATCH;
		if (!nullOwner && groupData.getCreationGroupId() != this.setGroupAvatarTransactionData.getTxGroupId()) return ValidationResult.TX_GROUP_ID_MISMATCH;
		Account owner = this.getOwner();
		if (!nullOwner && !owner.getAddress().equals(groupData.getOwner())) return ValidationResult.INVALID_GROUP_OWNER;
		AvatarData avatar = this.setGroupAvatarTransactionData.getAvatar();
		if (avatar != null) {
			ValidationResult avatarResult = AvatarResource.validate(avatar.getService(), avatar.getName(), avatar.getIdentifier());
			if (avatarResult != ValidationResult.OK) return avatarResult;
		}
		return owner.getConfirmedBalance(Asset.NATIVE) < this.setGroupAvatarTransactionData.getFee() ? ValidationResult.NO_BALANCE : ValidationResult.OK;
	}
	@Override public ValidationResult isProcessable() throws DataException {
		GroupData groupData = this.repository.getGroupRepository().fromGroupId(this.setGroupAvatarTransactionData.getGroupId());
		return !Group.isNullOwner(groupData.getOwner()) && !this.getOwner().getAddress().equals(groupData.getOwner()) ? ValidationResult.INVALID_GROUP_OWNER : ValidationResult.OK;
	}
	@Override public void process() throws DataException {
		new Group(this.repository, this.setGroupAvatarTransactionData.getGroupId()).setAvatar(this.setGroupAvatarTransactionData);
		this.repository.getTransactionRepository().save(this.setGroupAvatarTransactionData);
	}
	@Override public void orphan() throws DataException {
		new Group(this.repository, this.setGroupAvatarTransactionData.getGroupId()).unsetAvatar(this.setGroupAvatarTransactionData);
		this.repository.getTransactionRepository().save(this.setGroupAvatarTransactionData);
	}
}
