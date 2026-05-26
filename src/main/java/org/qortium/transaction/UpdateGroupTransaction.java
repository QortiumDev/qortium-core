package org.qortium.transaction;

import com.google.common.base.Utf8;
import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.UpdateGroupTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.utils.Unicode;

import java.util.Collections;
import java.util.List;

public class UpdateGroupTransaction extends Transaction {

	// Properties
	private UpdateGroupTransactionData updateGroupTransactionData;

	// Constructors

	public UpdateGroupTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.updateGroupTransactionData = (UpdateGroupTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.emptyList();
	}

	// Navigation

	public Account getOwner() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		// Check new approval threshold is valid
		if (this.updateGroupTransactionData.getNewApprovalThreshold() == null)
			return ValidationResult.INVALID_GROUP_APPROVAL_THRESHOLD;

		// Check min/max block delay values
		if (this.updateGroupTransactionData.getNewMinimumBlockDelay() < 0)
			return ValidationResult.INVALID_GROUP_BLOCK_DELAY;

		if (this.updateGroupTransactionData.getNewMaximumBlockDelay() < 1)
			return ValidationResult.INVALID_GROUP_BLOCK_DELAY;

		if (this.updateGroupTransactionData.getNewMaximumBlockDelay() < this.updateGroupTransactionData.getNewMinimumBlockDelay())
			return ValidationResult.INVALID_GROUP_BLOCK_DELAY;

		// Check new name (0 length means don't update name)
		String newName = this.updateGroupTransactionData.getNewName();
		int newNameLength = Utf8.encodedLength(newName);
		if (newNameLength != 0) {
			// Check new name size bounds
			if (newNameLength < Group.MIN_NAME_SIZE || newNameLength > Group.MAX_NAME_SIZE)
				return ValidationResult.INVALID_NAME_LENGTH;

			// Check new name is in normalized form (no leading/trailing whitespace, etc.)
			if (!newName.equals(Unicode.normalize(newName)))
				return ValidationResult.NAME_NOT_NORMALIZED;
		}

		// Check new description size bounds
		int newDescriptionLength = Utf8.encodedLength(this.updateGroupTransactionData.getNewDescription());
		if (newDescriptionLength < 1 || newDescriptionLength > Group.MAX_DESCRIPTION_SIZE)
			return ValidationResult.INVALID_DESCRIPTION_LENGTH;

		GroupData groupData = this.repository.getGroupRepository().fromGroupId(this.updateGroupTransactionData.getGroupId());

		// Check group exists
		if (groupData == null)
			return ValidationResult.GROUP_DOES_NOT_EXIST;

		boolean groupOwnedByNullAccount = Group.isNullOwner(groupData.getOwner());

		// Require approval if transaction relates to a group owned by the null account
		if (groupOwnedByNullAccount && !this.needsGroupApproval())
			return ValidationResult.GROUP_APPROVAL_REQUIRED;

		// Null-owned group management is approved by the group being updated.
		if (groupOwnedByNullAccount && this.updateGroupTransactionData.getTxGroupId() != this.updateGroupTransactionData.getGroupId())
			return ValidationResult.TX_GROUP_ID_MISMATCH;

		// Non-null-owned groups retain the inherited approval group chosen at creation.
		if (!groupOwnedByNullAccount && groupData.getCreationGroupId() != this.updateGroupTransactionData.getTxGroupId())
			return ValidationResult.TX_GROUP_ID_MISMATCH;

		Account owner = getOwner();

		// Check creator is group's current owner (except for groups owned by the null account)
		if (!groupOwnedByNullAccount && !owner.getAddress().equals(groupData.getOwner()))
			return ValidationResult.INVALID_GROUP_OWNER;

		// Check creator has enough funds
		if (owner.getConfirmedBalance(Asset.NATIVE) < this.updateGroupTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public ValidationResult isProcessable() throws DataException {
		GroupData groupData = this.repository.getGroupRepository().fromGroupId(this.updateGroupTransactionData.getGroupId());
		Account owner = getOwner();
		boolean groupOwnedByNullAccount = Group.isNullOwner(groupData.getOwner());

		// Check transaction's public key matches group's current owner (except for groups owned by the null account)
		if (!groupOwnedByNullAccount && !owner.getAddress().equals(groupData.getOwner()))
			return ValidationResult.INVALID_GROUP_OWNER;

		if (!this.updateGroupTransactionData.getNewName().isEmpty()) {
			GroupData newNameGroupData = this.repository.getGroupRepository().fromReducedGroupName(this.updateGroupTransactionData.getReducedNewName());
			if (newNameGroupData != null && !newNameGroupData.getGroupId().equals(groupData.getGroupId()))
				return ValidationResult.GROUP_ALREADY_EXISTS;
		}

		return ValidationResult.OK;
	}


	@Override
	public void process() throws DataException {
		// Update Group
		Group group = new Group(this.repository, this.updateGroupTransactionData.getGroupId());
		group.updateGroup(this.updateGroupTransactionData);

		// Save this transaction, now with updated "group reference" to previous transaction that updated group
		this.repository.getTransactionRepository().save(this.updateGroupTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert Group update
		Group group = new Group(this.repository, this.updateGroupTransactionData.getGroupId());
		group.unupdateGroup(this.updateGroupTransactionData);

		// Save this transaction, now with removed "group reference"
		this.repository.getTransactionRepository().save(this.updateGroupTransactionData);
	}

}
