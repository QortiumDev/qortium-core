package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.avatar.AvatarResource;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.crypto.Crypto;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.data.transaction.SetGroupAvatarTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transform.Transformer;

import java.util.Collections;
import java.util.List;

/** Consensus authorization of an exact confirmed public THUMBNAIL transaction. */
public class SetGroupAvatarTransaction extends Transaction {
	private final SetGroupAvatarTransactionData setGroupAvatarTransactionData;
	public SetGroupAvatarTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData); this.setGroupAvatarTransactionData = (SetGroupAvatarTransactionData) transactionData;
	}
	@Override public List<String> getRecipientAddresses() { return Collections.emptyList(); }
	public Account getOwner() { return this.getCreator(); }

	public static String identifierFor(int groupId) { return "qortium-group-avatar-v1-" + groupId; }

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
		byte[] avatarSignature = this.setGroupAvatarTransactionData.getAvatarSignature();
		if (avatarSignature != null) {
			ValidationResult avatarResult = AvatarResource.validate(this.repository, avatarSignature, nullOwner ? null : groupData.getOwner());
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
