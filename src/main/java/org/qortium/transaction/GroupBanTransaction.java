package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.asset.Asset;
import org.qortium.crypto.Crypto;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.GroupBanTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.util.Collections;
import java.util.List;

public class GroupBanTransaction extends Transaction {

	// Properties

	private GroupBanTransactionData groupBanTransactionData;
	private Account offenderAccount = null;

	// Constructors

	public GroupBanTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.groupBanTransactionData = (GroupBanTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.groupBanTransactionData.getOffender());
	}

	// Navigation

	public Account getAdmin() {
		return this.getCreator();
	}

	public Account getOffender() {
		if (this.offenderAccount == null)
			this.offenderAccount = new Account(this.repository, this.groupBanTransactionData.getOffender());

		return this.offenderAccount;
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		int groupId = this.groupBanTransactionData.getGroupId();

		// Check offender address is valid
		if (!Crypto.isValidAddress(this.groupBanTransactionData.getOffender()))
			return ValidationResult.INVALID_ADDRESS;

		GroupData groupData = this.repository.getGroupRepository().fromGroupId(groupId);

		// Check group exists
		if (groupData == null)
			return ValidationResult.GROUP_DOES_NOT_EXIST;

		Account admin = getAdmin();

		// Can't ban if not part of the group's current management authority
		if (!Group.canApprove(this.repository, groupId, admin.getAddress()))
			return ValidationResult.NOT_GROUP_ADMIN;

		String groupOwner = this.repository.getGroupRepository().getOwner(groupId);
		boolean groupOwnedByNullAccount = Group.isNullOwner(groupOwner);

		if (groupOwnedByNullAccount) {
			// Require approval if transaction relates to a group owned by the null account
			if (!this.needsGroupApproval())
				return ValidationResult.GROUP_APPROVAL_REQUIRED;
		} else if (!admin.getAddress().equals(groupData.getOwner())) {
			return ValidationResult.INVALID_GROUP_OWNER;
		}

		Account offender = getOffender();

		// Can't ban group owner
		if (offender.getAddress().equals(groupData.getOwner()))
			return ValidationResult.INVALID_GROUP_OWNER;

		// Can't ban another admin unless admin is the group owner
		if (!admin.getAddress().equals(groupData.getOwner()) && this.repository.getGroupRepository().adminExists(groupId, offender.getAddress()))
			return ValidationResult.INVALID_GROUP_OWNER;

		// Check admin has enough funds
		if (admin.getConfirmedBalance(Asset.NATIVE) < this.groupBanTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}


	@Override
	public void process() throws DataException {
		// Update Group Membership
		Group group = new Group(this.repository, this.groupBanTransactionData.getGroupId());
		group.ban(this.groupBanTransactionData);

		// Save this transaction with updated member/admin references to transactions that can help restore state
		this.repository.getTransactionRepository().save(this.groupBanTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert group membership
		Group group = new Group(this.repository, this.groupBanTransactionData.getGroupId());
		group.unban(this.groupBanTransactionData);

		// Save this transaction with removed member/admin references
		this.repository.getTransactionRepository().save(this.groupBanTransactionData);
	}

}
