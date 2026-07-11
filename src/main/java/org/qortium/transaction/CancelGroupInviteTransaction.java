package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.crypto.Crypto;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.CancelGroupInviteTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Collections;
import java.util.List;

public class CancelGroupInviteTransaction extends Transaction {

	// Properties

	private CancelGroupInviteTransactionData cancelGroupInviteTransactionData;
	private Account inviteeAccount = null;

	// Constructors

	public CancelGroupInviteTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.cancelGroupInviteTransactionData = (CancelGroupInviteTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.cancelGroupInviteTransactionData.getInvitee());
	}

	// Navigation

	public Account getAdmin() {
		return this.getCreator();
	}

	public Account getInvitee() {
		if (this.inviteeAccount == null)
			this.inviteeAccount = new Account(this.repository, this.cancelGroupInviteTransactionData.getInvitee());

		return this.inviteeAccount;
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		int groupId = this.cancelGroupInviteTransactionData.getGroupId();

		// Check invitee address is valid
		if (!Crypto.isValidAddress(this.cancelGroupInviteTransactionData.getInvitee()))
			return ValidationResult.INVALID_ADDRESS;

		GroupData groupData = this.repository.getGroupRepository().fromGroupId(groupId);

		// Check group exists
		if (groupData == null)
			return ValidationResult.GROUP_DOES_NOT_EXIST;

		Account admin = getAdmin();

		// Check admin is part of the group's current management authority
		int approvalHeight = this.repository.getBlockRepository().getBlockchainHeight() + 1;
		if (!Group.canApprove(this.repository, groupId, admin.getAddress(), this.transactionData.getType(), approvalHeight))
			return ValidationResult.NOT_GROUP_ADMIN;

		Account invitee = getInvitee();

		// Check invite exists
		if (!this.repository.getGroupRepository().inviteExists(groupId, invitee.getAddress(), this.cancelGroupInviteTransactionData.getTimestamp()))
			return ValidationResult.INVITE_UNKNOWN;

		// Check creator has enough funds
		if (admin.getConfirmedBalance(Asset.NATIVE) < this.cancelGroupInviteTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		String groupOwner = this.repository.getGroupRepository().getOwner(groupId);
		boolean groupOwnedByNullAccount = Group.isNullOwner(groupOwner);

		// Require approval if transaction relates to a group owned by the null account
		if (groupOwnedByNullAccount && !this.needsGroupApproval())
			return ValidationResult.GROUP_APPROVAL_REQUIRED;

		return ValidationResult.OK;
	}


	@Override
	public void process() throws DataException {
		// Update Group Membership
		Group group = new Group(this.repository, this.cancelGroupInviteTransactionData.getGroupId());
		group.cancelInvite(this.cancelGroupInviteTransactionData);

		// Save this transaction with updated member/admin references to transactions that can help restore state
		this.repository.getTransactionRepository().save(this.cancelGroupInviteTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert group membership
		Group group = new Group(this.repository, this.cancelGroupInviteTransactionData.getGroupId());
		group.uncancelInvite(this.cancelGroupInviteTransactionData);

		// Save this transaction with removed member/admin references
		this.repository.getTransactionRepository().save(this.cancelGroupInviteTransactionData);
	}

}
