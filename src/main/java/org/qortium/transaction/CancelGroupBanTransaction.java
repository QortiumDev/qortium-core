package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.crypto.Crypto;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.CancelGroupBanTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Collections;
import java.util.List;

public class CancelGroupBanTransaction extends Transaction {

	// Properties

	private CancelGroupBanTransactionData groupUnbanTransactionData;
	private Account memberAccount = null;

	// Constructors

	public CancelGroupBanTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.groupUnbanTransactionData = (CancelGroupBanTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.groupUnbanTransactionData.getMember());
	}

	// Navigation

	public Account getAdmin() {
		return this.getCreator();
	}

	public Account getMember() {
		if (this.memberAccount == null)
			this.memberAccount = new Account(this.repository, this.groupUnbanTransactionData.getMember());

		return this.memberAccount;
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		int groupId = this.groupUnbanTransactionData.getGroupId();

		// Check member address is valid
		if (!Crypto.isValidAddress(this.groupUnbanTransactionData.getMember()))
			return ValidationResult.INVALID_ADDRESS;

		GroupData groupData = this.repository.getGroupRepository().fromGroupId(groupId);

		// Check group exists
		if (groupData == null)
			return ValidationResult.GROUP_DOES_NOT_EXIST;

		Account admin = getAdmin();

		// Can't unban if not part of the group's current management authority
		int approvalHeight = this.repository.getBlockRepository().getBlockchainHeight() + 1;
		if (!Group.canApprove(this.repository, groupId, admin.getAddress(), this.transactionData.getType(), approvalHeight))
			return ValidationResult.NOT_GROUP_ADMIN;

		String groupOwner = this.repository.getGroupRepository().getOwner(groupId);
		boolean groupOwnedByNullAccount = Group.isNullOwner(groupOwner);

		if (groupOwnedByNullAccount) {
			// Require approval if transaction relates to a group owned by the null account
			if (!this.needsGroupApproval())
				return ValidationResult.GROUP_APPROVAL_REQUIRED;
		} else if (!admin.getAddress().equals(groupData.getOwner())) {
			// Can't cancel ban if not group's current owner
			return ValidationResult.INVALID_GROUP_OWNER;
		}

		Account member = getMember();

		// Check ban actually exists
		if (!this.repository.getGroupRepository().banExists(groupId, member.getAddress(), this.groupUnbanTransactionData.getTimestamp()))
			return ValidationResult.BAN_UNKNOWN;

		// Check admin has enough funds
		if (admin.getConfirmedBalance(Asset.NATIVE) < this.groupUnbanTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}


	@Override
	public void process() throws DataException {
		// Update Group Membership
		Group group = new Group(this.repository, this.groupUnbanTransactionData.getGroupId());
		group.cancelBan(this.groupUnbanTransactionData);

		// Save this transaction with updated member/admin references to transactions that can help restore state
		this.repository.getTransactionRepository().save(this.groupUnbanTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert group membership
		Group group = new Group(this.repository, this.groupUnbanTransactionData.getGroupId());
		group.uncancelBan(this.groupUnbanTransactionData);

		// Save this transaction with removed member/admin references
		this.repository.getTransactionRepository().save(this.groupUnbanTransactionData);
	}

}
