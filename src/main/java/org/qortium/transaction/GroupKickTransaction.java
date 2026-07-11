package org.qortium.transaction;

import org.qortium.account.Account;
import org.qortium.account.PublicKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.crypto.Crypto;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.GroupKickTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.GroupRepository;
import org.qortium.repository.Repository;

import java.util.Collections;
import java.util.List;

public class GroupKickTransaction extends Transaction {

	// Properties
	private GroupKickTransactionData groupKickTransactionData;

	// Constructors

	public GroupKickTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.groupKickTransactionData = (GroupKickTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.groupKickTransactionData.getMember());
	}

	// Navigation

	public Account getAdmin() throws DataException {
		return new PublicKeyAccount(this.repository, this.groupKickTransactionData.getAdminPublicKey());
	}

	public Account getMember() throws DataException {
		return new Account(this.repository, this.groupKickTransactionData.getMember());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		int groupId = this.groupKickTransactionData.getGroupId();

		// Check member address is valid
		if (!Crypto.isValidAddress(this.groupKickTransactionData.getMember()))
			return ValidationResult.INVALID_ADDRESS;

		GroupRepository groupRepository = this.repository.getGroupRepository();
		GroupData groupData = groupRepository.fromGroupId(groupId);

		// Check group exists
		if (groupData == null)
			return ValidationResult.GROUP_DOES_NOT_EXIST;

		Account admin = getAdmin();

		// Can't kick if not part of the group's current management authority
		int approvalHeight = this.repository.getBlockRepository().getBlockchainHeight() + 1;
		if (!Group.canApprove(this.repository, groupId, admin.getAddress(), this.transactionData.getType(), approvalHeight))
			return ValidationResult.NOT_GROUP_ADMIN;

		Account member = getMember();

		// Check member actually in group UNLESS there's a pending join request
		if (!groupRepository.joinRequestExists(groupId, member.getAddress()) && !groupRepository.memberExists(groupId, member.getAddress()))
			return ValidationResult.NOT_GROUP_MEMBER;

		// Can't kick group owner
		if (member.getAddress().equals(groupData.getOwner()))
			return ValidationResult.INVALID_GROUP_OWNER;

		// Can't kick another admin unless kicker is the group owner
		if (!admin.getAddress().equals(groupData.getOwner()) && groupRepository.adminExists(groupId, member.getAddress()))
			return ValidationResult.INVALID_GROUP_OWNER;

		String groupOwner = this.repository.getGroupRepository().getOwner(groupId);
		boolean groupOwnedByNullAccount = Group.isNullOwner(groupOwner);

		if (groupOwnedByNullAccount) {
			// Require approval if transaction relates to a group owned by the null account
			if (!this.needsGroupApproval())
				return ValidationResult.GROUP_APPROVAL_REQUIRED;
		} else if (!admin.getAddress().equals(groupData.getOwner())) {
			// Can't kick if not group's current owner
			return ValidationResult.INVALID_GROUP_OWNER;
		}

		// Check creator has enough funds
		if (admin.getConfirmedBalance(Asset.NATIVE) < this.groupKickTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}


	@Override
	public void process() throws DataException {
		// Update Group Membership
		Group group = new Group(this.repository, this.groupKickTransactionData.getGroupId());
		group.kick(this.groupKickTransactionData);

		// Save this transaction with updated member/admin references to transactions that can help restore state
		this.repository.getTransactionRepository().save(this.groupKickTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert group membership
		Group group = new Group(this.repository, this.groupKickTransactionData.getGroupId());
		group.unkick(this.groupKickTransactionData);

		// Save this transaction with removed member/admin references
		this.repository.getTransactionRepository().save(this.groupKickTransactionData);
	}

}
