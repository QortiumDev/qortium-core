package org.qortium.group;

import org.qortium.account.Account;
import org.qortium.account.PublicKeyAccount;
import org.qortium.block.BlockChain;
import org.qortium.controller.Controller;
import org.qortium.crypto.Crypto;
import org.qortium.data.group.*;
import org.qortium.data.transaction.*;
import org.qortium.repository.DataException;
import org.qortium.repository.GroupRepository;
import org.qortium.repository.Repository;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.utils.Groups;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

public class Group {

	/** Group-admin quora threshold for approving transactions */
	public enum ApprovalThreshold {
		// NOTE: value needs to fit into byte
		NONE(0, false),
		ONE(1, false),
		PCT20(20, true),
		PCT40(40, true),
		PCT60(60, true),
		PCT80(80, true),
		PCT100(100, true);

		public final int value;
		public final boolean isPercentage;

		private static final Map<Integer, ApprovalThreshold> map = stream(ApprovalThreshold.values())
				.collect(toMap(threshold -> threshold.value, threshold -> threshold));

		ApprovalThreshold(int value, boolean isPercentage) {
			this.value = value;
			this.isPercentage = isPercentage;
		}

		public static ApprovalThreshold valueOf(int value) {
			return map.get(value);
		}

		public boolean meetsTheshold(int currentApprovals, int totalAdmins) {
			if (!this.isPercentage)
				return currentApprovals >= this.value;

			// Multiply currentApprovals by 100 instead of dividing right-hand-side by 100 to prevent rounding errors!
			// Examples using 2 current approvals, 4 total admins, 60% threshold:
			// WRONG: 2 >= 4 * 60 / 100, i.e. 2 >= (240 / 100) which rounds to: 2 >= 2, returns true
			// RIGHT: 2 * 100 >= 4 * 60, i.e. 200 >= 240, returns false
			return (currentApprovals * 100) >= (totalAdmins * this.value);
		}
	}

	// Properties
	private Repository repository;
	private GroupRepository groupRepository;
	private GroupData groupData;

	// Useful constants
	public static final int NO_GROUP = 0;

	// Null owner address corresponds with public key "11111111111111111111111111111111"
	public static String NULL_OWNER_ADDRESS = "QdSnUy6sUiEnaN87dWmE92g1uQjrvPgrWG";

	public static final int MIN_NAME_SIZE = 3;
	public static final int MAX_NAME_SIZE = 32;
	public static final int MAX_DESCRIPTION_SIZE = 128;
	/** Max size of kick/ban reason */
	public static final int MAX_REASON_SIZE = 128;

	public static boolean isNullOwner(String address) {
		return Objects.equals(address, NULL_OWNER_ADDRESS);
	}

	public static boolean hasUsableAdmins(Repository repository, int groupId) throws DataException {
		return repository.getGroupRepository().countUsableGroupAdmins(groupId) > 0;
	}

	public static boolean canApprove(Repository repository, int groupId, String address, TransactionType transactionType, int approvalHeight) throws DataException {
		if (isNullOwner(address))
			return false;

		GroupRepository groupRepository = repository.getGroupRepository();
		GroupData groupData = groupRepository.fromGroupId(groupId);
		if (groupData == null)
			return false;

		if (!isNullOwner(groupData.getOwner()))
			return groupRepository.adminExists(groupId, address);

		if (!hasUsableAdmins(repository, groupId))
			return groupRepository.memberExists(groupId, address);

		if (usesMemberApprovalAuthorities(groupData, transactionType, approvalHeight))
			return groupRepository.memberExists(groupId, address);

		return groupRepository.usableAdminExists(groupId, address);
	}

	private static boolean usesMemberApprovalAuthorities(GroupData groupData, TransactionType transactionType, int approvalHeight) {
		if (approvalHeight < BlockChain.getInstance().getDevGroupApprovalSplitHeight())
			return false;

		if (!isNullOwner(groupData.getOwner()))
			return false;

		if (!Groups.getGroupIdsAtHeight(BlockChain.getInstance().getDevGroupIds(), approvalHeight).contains(groupData.getGroupId()))
			return false;

		return GroupApprovalCategory.fromTransactionType(transactionType) == GroupApprovalCategory.GOVERNANCE;
	}

	public static int countApprovalAuthorities(Repository repository, int groupId, TransactionType transactionType, int approvalHeight) throws DataException {
		GroupRepository groupRepository = repository.getGroupRepository();
		GroupData groupData = groupRepository.fromGroupId(groupId);
		if (groupData == null)
			return 0;

		if (!isNullOwner(groupData.getOwner())) {
			Integer adminCount = groupRepository.countGroupAdmins(groupId);
			return adminCount == null ? 0 : adminCount;
		}

		int usableAdminCount = groupRepository.countUsableGroupAdmins(groupId);
		if (usableAdminCount <= 0)
			return groupRepository.countNonNullGroupMembers(groupId);

		if (usesMemberApprovalAuthorities(groupData, transactionType, approvalHeight))
			return groupRepository.countNonNullGroupMembers(groupId);

		return usableAdminCount;
	}

	// Constructors

	/**
	 * Construct Group business object using info from create group transaction.
	 * 
	 * @param repository
	 * @param createGroupTransactionData
	 */
	public Group(Repository repository, CreateGroupTransactionData createGroupTransactionData) {
		this.repository = repository;
		this.groupRepository = repository.getGroupRepository();

		String owner = Crypto.toAddress(createGroupTransactionData.getCreatorPublicKey());

		this.groupData = new GroupData(owner, createGroupTransactionData.getGroupName(),
				createGroupTransactionData.getDescription(), createGroupTransactionData.getTimestamp(),
				createGroupTransactionData.isOpen(), createGroupTransactionData.getApprovalThreshold(),
				createGroupTransactionData.getMinimumBlockDelay(), createGroupTransactionData.getMaximumBlockDelay(),
				createGroupTransactionData.getSignature(), createGroupTransactionData.getTxGroupId(),
				createGroupTransactionData.getReducedGroupName());
	}

	/**
	 * Construct Group business object using existing group in repository.
	 * 
	 * @param repository
	 * @param groupId
	 * @throws DataException
	 */
	public Group(Repository repository, int groupId) throws DataException {
		this.repository = repository;
		this.groupRepository = repository.getGroupRepository();

		this.groupData = this.repository.getGroupRepository().fromGroupId(groupId);
	}

	// Getters / setters

	public GroupData getGroupData() {
		return this.groupData;
	}

	// Membership

	private GroupMemberData getMember(String member) throws DataException {
		return groupRepository.getMember(this.groupData.getGroupId(), member);
	}

	private boolean memberExists(String member) throws DataException {
		return groupRepository.memberExists(this.groupData.getGroupId(), member);
	}

	private void addMember(String member, long joined, byte[] reference) throws DataException {
		GroupMemberData groupMemberData = new GroupMemberData(this.groupData.getGroupId(), member, joined, reference);
		groupRepository.save(groupMemberData);

		Controller.getInstance().onGroupMembershipChange(this.groupData.getGroupId());
	}

	private void addMember(String member, TransactionData transactionData) throws DataException {
		this.addMember(member, transactionData.getTimestamp(), transactionData.getSignature());
	}

	private void rebuildMember(String member, byte[] reference) throws DataException {
		TransactionData transactionData = this.repository.getTransactionRepository().fromSignature(reference);
		this.addMember(member, transactionData);
	}

	private void deleteMember(String member) throws DataException {
		groupRepository.deleteMember(this.groupData.getGroupId(), member);

		Controller.getInstance().onGroupMembershipChange(this.groupData.getGroupId());
	}

	// Adminship

	private GroupAdminData getAdmin(String admin) throws DataException {
		return groupRepository.getAdmin(this.groupData.getGroupId(), admin);
	}

	private boolean adminExists(String admin) throws DataException {
		return groupRepository.adminExists(this.groupData.getGroupId(), admin);
	}

	private void addAdmin(String admin, byte[] reference) throws DataException {
		GroupAdminData groupAdminData = new GroupAdminData(this.groupData.getGroupId(), admin, reference);
		groupRepository.save(groupAdminData);
	}

	private void addAdmin(String admin, TransactionData transactionData) throws DataException {
		this.addAdmin(admin, transactionData.getSignature());
	}

	private void rebuildAdmin(String admin, byte[] reference) throws DataException {
		TransactionData transactionData = this.repository.getTransactionRepository().fromSignature(reference);
		this.addAdmin(admin, transactionData);
	}

	private void deleteAdmin(String admin) throws DataException {
		groupRepository.deleteAdmin(this.groupData.getGroupId(), admin);
	}

	// Join request

	private GroupJoinRequestData getJoinRequest(String joiner) throws DataException {
		return groupRepository.getJoinRequest(this.groupData.getGroupId(), joiner);
	}

	private void addJoinRequest(String joiner, byte[] reference) throws DataException {
		GroupJoinRequestData groupJoinRequestData = new GroupJoinRequestData(this.groupData.getGroupId(), joiner, reference);
		groupRepository.save(groupJoinRequestData);
	}

	private void rebuildJoinRequest(String joiner, byte[] reference) throws DataException {
		this.addJoinRequest(joiner, reference);
	}

	private void deleteJoinRequest(String joiner) throws DataException {
		groupRepository.deleteJoinRequest(this.groupData.getGroupId(), joiner);
	}

	// Invites

	private GroupInviteData getActiveInvite(String invitee, long timestamp) throws DataException {
		return groupRepository.getInvite(this.groupData.getGroupId(), invitee, timestamp);
	}

	private void addInvite(GroupInviteTransactionData groupInviteTransactionData) throws DataException {
		Account inviter = new PublicKeyAccount(this.repository, groupInviteTransactionData.getAdminPublicKey());
		String invitee = groupInviteTransactionData.getInvitee();
		Long expiry = null;
		int timeToLive = groupInviteTransactionData.getTimeToLive();
		if (timeToLive != 0)
			expiry = groupInviteTransactionData.getTimestamp() + timeToLive * 1000L;

		GroupInviteData groupInviteData = new GroupInviteData(this.groupData.getGroupId(), inviter.getAddress(), invitee, expiry,
				groupInviteTransactionData.getSignature());
		groupRepository.save(groupInviteData);
	}

	private void rebuildInvite(String invitee, byte[] reference) throws DataException {
		TransactionData previousTransactionData = this.repository.getTransactionRepository().fromSignature(reference);
		this.addInvite((GroupInviteTransactionData) previousTransactionData);
	}

	private void deleteInvite(String invitee) throws DataException {
		groupRepository.deleteInvite(this.groupData.getGroupId(), invitee);
	}

	// Bans

	private void addBan(GroupBanTransactionData groupBanTransactionData) throws DataException {
		String offender = groupBanTransactionData.getOffender();
		Account admin = new PublicKeyAccount(this.repository, groupBanTransactionData.getAdminPublicKey());
		long banned = groupBanTransactionData.getTimestamp();
		String reason = groupBanTransactionData.getReason();

		Long expiry = null;
		int timeToLive = groupBanTransactionData.getTimeToLive();
		if (timeToLive != 0)
			expiry = groupBanTransactionData.getTimestamp() + timeToLive * 1000L;

		// Save reference to this banning transaction so cancel-ban can rebuild ban during orphaning.
		byte[] reference = groupBanTransactionData.getSignature();

		GroupBanData groupBanData = new GroupBanData(this.groupData.getGroupId(), offender, admin.getAddress(), banned, reason, expiry, reference);
		groupRepository.save(groupBanData);
	}

	// Processing

	// "un"-methods are the orphaning versions. e.g. "uncreate" undoes "create" processing.

	/*
	 * GroupData records can be changed by CREATE_GROUP or UPDATE_GROUP transactions.
	 * 
	 * GroupData stores the signature of the last transaction that caused a change to its contents
	 * in a field called "reference".
	 * 
	 * During orphaning, "reference" is used to fetch the previous GroupData-changing transaction
	 * and that transaction's contents are used to restore the previous GroupData state,
	 * including GroupData's previous "reference" value.
	 */

	// CREATE GROUP

	public void create(CreateGroupTransactionData createGroupTransactionData) throws DataException {
		// Note: this.groupData already populated by our constructor above
		this.repository.getGroupRepository().save(this.groupData);

		// Add owner as member
		this.addMember(this.groupData.getOwner(), createGroupTransactionData);

		// Add owner as admin
		this.addAdmin(this.groupData.getOwner(), createGroupTransactionData);
	}

	public void uncreate() throws DataException {
		// Repository takes care of cleaning up ancilliary data!
		this.repository.getGroupRepository().delete(this.groupData.getGroupId());
	}

	// UPDATE GROUP

	/*
	 * In UPDATE_GROUP transactions we store the current GroupData's "reference" in the
	 * transaction's field "group_reference" and update GroupData's "reference" to
	 * our transaction's signature to form an undo chain.
	 */

	public void updateGroup(UpdateGroupTransactionData updateGroupTransactionData) throws DataException {
		// Save GroupData's reference in our transaction data
		updateGroupTransactionData.setGroupReference(this.groupData.getReference());

		// Update GroupData's reference to this transaction's signature
		this.groupData.setReference(updateGroupTransactionData.getSignature());

		// Update Group's mutable settings. Ownership changes are handled by future group-sale transactions.
		if (!updateGroupTransactionData.getNewName().isEmpty()) {
			this.groupData.setGroupName(updateGroupTransactionData.getNewName());
			this.groupData.setReducedGroupName(updateGroupTransactionData.getReducedNewName());
		}

		this.groupData.setDescription(updateGroupTransactionData.getNewDescription());
		this.groupData.setIsOpen(updateGroupTransactionData.getNewIsOpen());
		this.groupData.setApprovalThreshold(updateGroupTransactionData.getNewApprovalThreshold());
		this.groupData.setMinimumBlockDelay(updateGroupTransactionData.getNewMinimumBlockDelay());
		this.groupData.setMaximumBlockDelay(updateGroupTransactionData.getNewMaximumBlockDelay());
		this.groupData.setUpdated(updateGroupTransactionData.getTimestamp());

		// Save updated group data
		groupRepository.save(this.groupData);
	}

	public void unupdateGroup(UpdateGroupTransactionData updateGroupTransactionData) throws DataException {
		// Previous group reference is taken from this transaction's cached copy
		this.groupData.setReference(updateGroupTransactionData.getGroupReference());

		// Previous Group's mutable settings taken from referenced transaction
		this.revertGroupUpdate();

		// Save reverted group data
		groupRepository.save(this.groupData);

		// Remove cached reference to previous group change from transaction data
		updateGroupTransactionData.setGroupReference(null);
	}

	/** Reverts groupData using previous values stored in referenced transaction. */
	private void revertGroupUpdate() throws DataException {
		TransactionData previousTransactionData = this.repository.getTransactionRepository().fromSignature(this.groupData.getReference());
		if (previousTransactionData == null)
			throw new DataException("Unable to revert group transaction as referenced transaction not found in repository");

		switch (previousTransactionData.getType()) {
			case CREATE_GROUP: {
				CreateGroupTransactionData previousCreateGroupTransactionData = (CreateGroupTransactionData) previousTransactionData;

				String owner = Crypto.toAddress(previousCreateGroupTransactionData.getCreatorPublicKey());

				this.groupData.setOwner(owner);
				this.groupData.setGroupName(previousCreateGroupTransactionData.getGroupName());
				this.groupData.setReducedGroupName(previousCreateGroupTransactionData.getReducedGroupName());
				this.groupData.setDescription(previousCreateGroupTransactionData.getDescription());
				this.groupData.setIsOpen(previousCreateGroupTransactionData.isOpen());
				this.groupData.setApprovalThreshold(previousCreateGroupTransactionData.getApprovalThreshold());
				this.groupData.setMinimumBlockDelay(previousCreateGroupTransactionData.getMinimumBlockDelay());
				this.groupData.setMaximumBlockDelay(previousCreateGroupTransactionData.getMaximumBlockDelay());
				this.groupData.setUpdated(null);
				break;
			}

			case UPDATE_GROUP: {
				UpdateGroupTransactionData previousUpdateGroupTransactionData = (UpdateGroupTransactionData) previousTransactionData;
				if (!previousUpdateGroupTransactionData.getNewName().isEmpty()) {
					this.groupData.setGroupName(previousUpdateGroupTransactionData.getNewName());
					this.groupData.setReducedGroupName(previousUpdateGroupTransactionData.getReducedNewName());
				} else {
					this.revertGroupName(previousUpdateGroupTransactionData.getGroupReference());
				}
				this.groupData.setDescription(previousUpdateGroupTransactionData.getNewDescription());
				this.groupData.setIsOpen(previousUpdateGroupTransactionData.getNewIsOpen());
				this.groupData.setApprovalThreshold(previousUpdateGroupTransactionData.getNewApprovalThreshold());
				this.groupData.setMinimumBlockDelay(previousUpdateGroupTransactionData.getNewMinimumBlockDelay());
				this.groupData.setMaximumBlockDelay(previousUpdateGroupTransactionData.getNewMaximumBlockDelay());
				this.groupData.setUpdated(previousUpdateGroupTransactionData.getTimestamp());
				break;
			}

			default:
				throw new IllegalStateException("Unable to revert group transaction due to unsupported referenced transaction");
		}

		// Previous owner will still be admin and member at this point
	}

	private void revertGroupName(byte[] groupReference) throws DataException {
		while (true) {
			TransactionData previousTransactionData = this.repository.getTransactionRepository().fromSignature(groupReference);
			if (previousTransactionData == null)
				throw new DataException("Unable to revert group name as referenced transaction not found in repository");

			switch (previousTransactionData.getType()) {
				case CREATE_GROUP: {
					CreateGroupTransactionData previousCreateGroupTransactionData = (CreateGroupTransactionData) previousTransactionData;

					this.groupData.setGroupName(previousCreateGroupTransactionData.getGroupName());
					this.groupData.setReducedGroupName(previousCreateGroupTransactionData.getReducedGroupName());
					return;
				}

				case UPDATE_GROUP: {
					UpdateGroupTransactionData previousUpdateGroupTransactionData = (UpdateGroupTransactionData) previousTransactionData;

					if (!previousUpdateGroupTransactionData.getNewName().isEmpty()) {
						this.groupData.setGroupName(previousUpdateGroupTransactionData.getNewName());
						this.groupData.setReducedGroupName(previousUpdateGroupTransactionData.getReducedNewName());
						return;
					}

					groupReference = previousUpdateGroupTransactionData.getGroupReference();
					break;
				}

				default:
					throw new IllegalStateException("Unable to revert group name due to unsupported referenced transaction");
			}
		}
	}

	public void promoteToAdmin(AddGroupAdminTransactionData addGroupAdminTransactionData) throws DataException {
		this.addAdmin(addGroupAdminTransactionData.getMember(), addGroupAdminTransactionData.getSignature());
	}

	public void unpromoteToAdmin(AddGroupAdminTransactionData addGroupAdminTransactionData) throws DataException {
		this.deleteAdmin(addGroupAdminTransactionData.getMember());
	}

	public void demoteFromAdmin(RemoveGroupAdminTransactionData removeGroupAdminTransactionData) throws DataException {
		String admin = removeGroupAdminTransactionData.getAdmin();

		// Save reference to the transaction that caused adminship so we can revert if orphaning.
		GroupAdminData groupAdminData = this.getAdmin(admin);
		// Reference now part of this transaction but actually saved into repository by caller.
		removeGroupAdminTransactionData.setAdminReference(groupAdminData.getReference());

		// Demote
		this.deleteAdmin(admin);
	}

	public void undemoteFromAdmin(RemoveGroupAdminTransactionData removeGroupAdminTransactionData) throws DataException {
		String admin = removeGroupAdminTransactionData.getAdmin();

		// Rebuild admin entry using stored reference to transaction that causes adminship
		this.rebuildAdmin(admin, removeGroupAdminTransactionData.getAdminReference());

		// Clean cached reference in this transaction
		removeGroupAdminTransactionData.setAdminReference(null);
	}

	public void kick(GroupKickTransactionData groupKickTransactionData) throws DataException {
		String member = groupKickTransactionData.getMember();

		// If there is a pending join request then this is a essentially a deny response so delete join request and exit
		GroupJoinRequestData groupJoinRequestData = this.getJoinRequest(member);
		if (groupJoinRequestData != null) {
			// Save reference to the transaction that created join request so we can rebuild join request during orphaning.
			groupKickTransactionData.setJoinReference(groupJoinRequestData.getReference());

			// Delete join request
			this.deleteJoinRequest(member);

			// Make sure kick transaction's member/admin-references are null to indicate that there
			// was no existing member but actually only a join request. This should prevent orphaning code
			// from trying to incorrectly recreate a member/admin.
			groupKickTransactionData.setMemberReference(null);
			groupKickTransactionData.setAdminReference(null);

			return;
		} else {
			// Clear any cached join reference
			groupKickTransactionData.setJoinReference(null);
		}

		GroupAdminData groupAdminData = this.getAdmin(member);
		if (groupAdminData != null) {
			// Save reference to the transaction that caused adminship so we can rebuild adminship during orphaning.
			groupKickTransactionData.setAdminReference(groupAdminData.getReference());

			// Kicked, so no longer an admin
			this.deleteAdmin(member);
		} else {
			// Not an admin so no reference
			groupKickTransactionData.setAdminReference(null);
		}

		GroupMemberData groupMemberData = this.getMember(member);
		// Save reference to the transaction that caused membership so we can rebuild membership during orphaning.
		groupKickTransactionData.setMemberReference(groupMemberData.getReference());

		// Kicked, so no longer a member
		this.deleteMember(member);

		// If member had this group set as their defaultGroupId then change it to NO_GROUP
		Account memberAccount = new Account(this.repository, member);
		if (memberAccount.getDefaultGroupId() == groupKickTransactionData.getGroupId()) {
			memberAccount.setDefaultGroupId(Group.NO_GROUP);
			// Reflect that this has happened in joinGroupTransactionData
			groupKickTransactionData.setPreviousGroupId(groupKickTransactionData.getGroupId());
		}
	}

	public void unkick(GroupKickTransactionData groupKickTransactionData) throws DataException {
		String member = groupKickTransactionData.getMember();

		// If there's no cached reference to the transaction that caused membership then the kick was only a deny response to a join-request.
		byte[] joinReference = groupKickTransactionData.getJoinReference();
		if (joinReference != null) {
			// Rebuild join-request
			this.rebuildJoinRequest(member, joinReference);
		} else {
			// Rebuild member entry using stored transaction reference
			this.rebuildMember(member, groupKickTransactionData.getMemberReference());

			// Revert member's defaultGroupId if necessary
			Integer previousDefaultGroupId = groupKickTransactionData.getPreviousGroupId();
			if (previousDefaultGroupId != null) {
				Account memberAccount = new Account(this.repository, member);
				memberAccount.setDefaultGroupId(previousDefaultGroupId);
			}

			if (groupKickTransactionData.getAdminReference() != null)
				// Rebuild admin entry using stored transaction reference
				this.rebuildAdmin(member, groupKickTransactionData.getAdminReference());
		}

		// Clean cached references to transactions used to rebuild member/admin info
		groupKickTransactionData.setMemberReference(null);
		groupKickTransactionData.setAdminReference(null);
		groupKickTransactionData.setJoinReference(null);
		groupKickTransactionData.setPreviousGroupId(null);
	}

	public void ban(GroupBanTransactionData groupBanTransactionData) throws DataException {
		String offender = groupBanTransactionData.getOffender();

		// Kick if member
		if (this.memberExists(offender)) {
			GroupAdminData groupAdminData = this.getAdmin(offender);
			if (groupAdminData != null) {
				// Save reference to the transaction that caused adminship so we can revert if orphaning.
				groupBanTransactionData.setAdminReference(groupAdminData.getReference());

				// Kicked, so no longer an admin
				this.deleteAdmin(offender);
			} else {
				// Not an admin so no reference
				groupBanTransactionData.setAdminReference(null);
			}

			GroupMemberData groupMemberData = this.getMember(offender);
			// Save reference to the transaction that caused membership so we can revert if orphaning.
			groupBanTransactionData.setMemberReference(groupMemberData.getReference());

			// Kicked, so no longer a member
			this.deleteMember(offender);

			// If offender had this group set as their defaultGroupId then change it to NO_GROUP
			Account offenderAccount = new Account(this.repository, offender);
			if (offenderAccount.getDefaultGroupId() == groupBanTransactionData.getGroupId()) {
				offenderAccount.setDefaultGroupId(Group.NO_GROUP);
				// Reflect that this has happened in joinGroupTransactionData
				groupBanTransactionData.setPreviousGroupId(groupBanTransactionData.getGroupId());
			}
		} else {
			// If there is a pending join request then this is a essentially a deny response so delete join request
			GroupJoinRequestData groupJoinRequestData = this.getJoinRequest(offender);
			if (groupJoinRequestData != null) {
				// Save reference to join request so we can rebuild join request if orphaning,
				// and differentiate between needing to rebuild join request and rebuild invite.
				groupBanTransactionData.setJoinInviteReference(groupJoinRequestData.getReference());

				// Delete join request
				this.deleteJoinRequest(offender);

				// Make sure kick transaction's member/admin-references are null to indicate that there
				// was no existing member but actually only a join request. This should prevent orphaning code
				// from trying to incorrectly recreate a member/admin.
				groupBanTransactionData.setMemberReference(null);
				groupBanTransactionData.setAdminReference(null);
			} else {
				// No join request, but there could be a pending invite
				GroupInviteData groupInviteData = this.getActiveInvite(offender, groupBanTransactionData.getTimestamp());
				if (groupInviteData != null) {
					// Save reference to invite so we can rebuild invite if orphaning,
					// and differentiate between needing to rebuild join request and rebuild invite.
					groupBanTransactionData.setJoinInviteReference(groupInviteData.getReference());

					// Delete invite
					this.deleteInvite(offender);

					// Make sure kick transaction's member/admin-references are null to indicate that there
					// was no existing member but actually only a join request. This should prevent orphaning code
					// from trying to incorrectly recreate a member/admin.
					groupBanTransactionData.setMemberReference(null);
					groupBanTransactionData.setAdminReference(null);
				}
			}
		}

		// Create ban
		this.addBan(groupBanTransactionData);
	}

	public void unban(GroupBanTransactionData groupBanTransactionData) throws DataException {
		// Orphaning version of "ban" - not "cancel-ban"!
		String offender = groupBanTransactionData.getOffender();

		// Delete ban
		groupRepository.deleteBan(this.groupData.getGroupId(), offender);

		// If member was kicked as part of ban then reinstate
		if (groupBanTransactionData.getMemberReference() != null) {
			this.rebuildMember(offender, groupBanTransactionData.getMemberReference());

			if (groupBanTransactionData.getAdminReference() != null)
				// Rebuild admin entry using stored transaction reference
				this.rebuildAdmin(offender, groupBanTransactionData.getAdminReference());

			// Revert offender's defaultGroupId if necessary
			Integer previousDefaultGroupId = groupBanTransactionData.getPreviousGroupId();
			if (previousDefaultGroupId != null) {
				Account offenderAccount = new Account(this.repository, offender);
				offenderAccount.setDefaultGroupId(previousDefaultGroupId);
			}
		} else {
			// Do we need to reinstate pending invite or join-request?
			byte[] groupReference = groupBanTransactionData.getJoinInviteReference();
			if (groupReference != null) {
				TransactionData transactionData = this.repository.getTransactionRepository().fromSignature(groupReference);

				switch (transactionData.getType()) {
					case GROUP_INVITE:
						// Reinstate invite
						this.rebuildInvite(offender, groupReference);
						break;

					case JOIN_GROUP:
						// Rebuild join-request
						this.rebuildJoinRequest(offender, groupReference);
						break;

					default:
						throw new IllegalStateException("Unable to revert group transaction due to unsupported referenced transaction");
				}
			}
		}

		// Remove any group-related references from transaction data
		groupBanTransactionData.setMemberReference(null);
		groupBanTransactionData.setAdminReference(null);
		groupBanTransactionData.setJoinInviteReference(null);
		groupBanTransactionData.setPreviousGroupId(null);
	}

	public void cancelBan(CancelGroupBanTransactionData groupUnbanTransactionData) throws DataException {
		String member = groupUnbanTransactionData.getMember();

		GroupBanData groupBanData = groupRepository.getBan(this.groupData.getGroupId(), member);

		// Save reference to banning transaction for orphaning purposes
		groupUnbanTransactionData.setBanReference(groupBanData.getReference());

		// Delete ban
		groupRepository.deleteBan(this.groupData.getGroupId(), member);
	}

	public void uncancelBan(CancelGroupBanTransactionData groupUnbanTransactionData) throws DataException {
		// Reinstate ban using cached reference to banning transaction, stored in our transaction
		TransactionData transactionData = this.repository.getTransactionRepository().fromSignature(groupUnbanTransactionData.getBanReference());
		this.addBan((GroupBanTransactionData) transactionData);

		// Clear cached reference to banning transaction
		groupUnbanTransactionData.setBanReference(null);
	}

	public void invite(GroupInviteTransactionData groupInviteTransactionData) throws DataException {
		String invitee = groupInviteTransactionData.getInvitee();

		// If there is a pending "join request" then add new group member
		GroupJoinRequestData groupJoinRequestData = this.getJoinRequest(invitee);
		if (groupJoinRequestData != null) {
			this.addMember(invitee, groupInviteTransactionData);

			// Save reference to transaction that created join request so we can rebuild join request during orphaning.
			groupInviteTransactionData.setJoinReference(groupJoinRequestData.getReference());

			// If invitee's defaultGroupId is NO_GROUP then set it to joined group
			Account inviteeAccount = new Account(this.repository, invitee);
			if (inviteeAccount.getDefaultGroupId() == Group.NO_GROUP) {
				inviteeAccount.setDefaultGroupId(groupInviteTransactionData.getGroupId());
				// Reflect that this has happened in groupInviteTransactionData
				groupInviteTransactionData.setPreviousGroupId(Group.NO_GROUP);
			}

			// Delete join request
			this.deleteJoinRequest(invitee);

			return;
		}

		this.addInvite(groupInviteTransactionData);
	}

	public void uninvite(GroupInviteTransactionData groupInviteTransactionData) throws DataException {
		String invitee = groupInviteTransactionData.getInvitee();

		// If member exists and the join request is present then they were added when invite matched join request
		if (this.memberExists(invitee) && groupInviteTransactionData.getJoinReference() != null) {
			// Rebuild join request using cached reference to transaction that created join request.
			this.rebuildJoinRequest(invitee, groupInviteTransactionData.getJoinReference());

			// Revert invitee's defaultGroupId if necessary
			Integer previousDefaultGroupId = groupInviteTransactionData.getPreviousGroupId();
			if (previousDefaultGroupId != null) {
				Account inviteeAccount = new Account(this.repository, invitee);
				inviteeAccount.setDefaultGroupId(previousDefaultGroupId);
			}

			// Delete member
			this.deleteMember(invitee);

			// Clear cached reference
			groupInviteTransactionData.setJoinReference(null);
		}

		// Delete invite
		this.deleteInvite(invitee);

		// Clear cached references
		groupInviteTransactionData.setJoinReference(null);
		groupInviteTransactionData.setPreviousGroupId(null);
	}

	public void cancelInvite(CancelGroupInviteTransactionData cancelGroupInviteTransactionData) throws DataException {
		String invitee = cancelGroupInviteTransactionData.getInvitee();

		// Save reference to invite transaction so invite can be rebuilt during orphaning.
		GroupInviteData groupInviteData = this.getActiveInvite(invitee, cancelGroupInviteTransactionData.getTimestamp());
		if( groupInviteData != null) {
			cancelGroupInviteTransactionData.setInviteReference(groupInviteData.getReference());
		}

		// Delete invite
		this.deleteInvite(invitee);
	}

	public void uncancelInvite(CancelGroupInviteTransactionData cancelGroupInviteTransactionData) throws DataException {
		// Reinstate invite
		TransactionData transactionData = this.repository.getTransactionRepository().fromSignature(cancelGroupInviteTransactionData.getInviteReference());
		if( transactionData != null ) {
			this.addInvite((GroupInviteTransactionData) transactionData);
		}

		// Clear cached reference to invite transaction
		cancelGroupInviteTransactionData.setInviteReference(null);
	}

	public void join(JoinGroupTransactionData joinGroupTransactionData) throws DataException {
		Account joiner = new PublicKeyAccount(this.repository, joinGroupTransactionData.getJoinerPublicKey());

		// Any pending invite?
		GroupInviteData groupInviteData = this.getActiveInvite(joiner.getAddress(), joinGroupTransactionData.getTimestamp());

		// If there is no invites and this group is "closed" (i.e. invite-only) then
		// this is now a pending "join request"
		if (groupInviteData == null && !groupData.isOpen()) {
			// Save join request
			this.addJoinRequest(joiner.getAddress(), joinGroupTransactionData.getSignature());

			// Clear any reference to invite transaction to prevent invite rebuild during orphaning.
			joinGroupTransactionData.setInviteReference(null);

			return;
		}

		// Any invite?
		if (groupInviteData != null) {
			// Save reference to invite transaction so invite can be rebuilt during orphaning.
			joinGroupTransactionData.setInviteReference(groupInviteData.getReference());

			// Delete invite
			this.deleteInvite(joiner.getAddress());
		} else {
			// Clear any reference to invite transaction to prevent invite rebuild during orphaning.
			joinGroupTransactionData.setInviteReference(null);
		}

		joiner.ensureAccount();

		// Actually add new member to group
		this.addMember(joiner.getAddress(), joinGroupTransactionData);

		// If joiner's defaultGroupId is NO_GROUP then set it to joined group
		Integer defaultGroupId = joiner.getDefaultGroupId();
		if (defaultGroupId == null || defaultGroupId == Group.NO_GROUP) {
			joiner.setDefaultGroupId(joinGroupTransactionData.getGroupId());
			// Reflect that this has happened in joinGroupTransactionData
			joinGroupTransactionData.setPreviousGroupId(Group.NO_GROUP);
		}
	}

	public void unjoin(JoinGroupTransactionData joinGroupTransactionData) throws DataException {
		Account joiner = new PublicKeyAccount(this.repository, joinGroupTransactionData.getJoinerPublicKey());

		byte[] inviteReference = joinGroupTransactionData.getInviteReference();

		// Was this a join-request only?
		if (inviteReference == null && !groupData.isOpen()) {
			// Delete join request
			this.deleteJoinRequest(joiner.getAddress());
		} else {
			// Any invite to rebuild?
			if (inviteReference != null) {
				// Rebuild invite using cache reference to invite transaction
				TransactionData transactionData = this.repository.getTransactionRepository().fromSignature(inviteReference);
				this.addInvite((GroupInviteTransactionData) transactionData);

				// Clear cached reference to invite transaction
				joinGroupTransactionData.setInviteReference(null);
			}

			// Delete member
			this.deleteMember(joiner.getAddress());

			// Revert joiner's defaultGroupId if necessary
			Integer previousDefaultGroupId = joinGroupTransactionData.getPreviousGroupId();
			if (previousDefaultGroupId != null)
				joiner.setDefaultGroupId(previousDefaultGroupId);
		}

		// Clear cached references
		joinGroupTransactionData.setInviteReference(null);
		joinGroupTransactionData.setPreviousGroupId(null);
	}

	public void leave(LeaveGroupTransactionData leaveGroupTransactionData) throws DataException {
		Account leaver = new PublicKeyAccount(this.repository, leaveGroupTransactionData.getLeaverPublicKey());

		// Potentially record reference to transaction that restores previous admin state.
		// Owners can't leave as that would leave group ownerless and in unrecoverable state.

		// Owners are also admins, so skip if owner
		if (!leaver.getAddress().equals(this.groupData.getOwner())) {
			// Fetch admin data for leaver
			GroupAdminData groupAdminData = this.getAdmin(leaver.getAddress());

			if (groupAdminData != null) {
				// Save reference to transaction that caused adminship in our transaction so we can rebuild adminship during orphaning.
				leaveGroupTransactionData.setAdminReference(groupAdminData.getReference());

				// Remove as admin
				this.deleteAdmin(leaver.getAddress());
			}
		}

		// Save reference to transaction that caused membership in our transaction so we can rebuild membership during orphaning.
		GroupMemberData groupMemberData = this.getMember(leaver.getAddress());
		leaveGroupTransactionData.setMemberReference(groupMemberData.getReference());

		// Remove as member
		this.deleteMember(leaver.getAddress());

		// If member had this group set as their defaultGroupId then change it to NO_GROUP
		if (leaver.getDefaultGroupId() == leaveGroupTransactionData.getGroupId()) {
			leaver.setDefaultGroupId(Group.NO_GROUP);
			// Reflect that this has happened in joinGroupTransactionData
			leaveGroupTransactionData.setPreviousGroupId(leaveGroupTransactionData.getGroupId());
		}
	}

	public void unleave(LeaveGroupTransactionData leaveGroupTransactionData) throws DataException {
		Account leaver = new PublicKeyAccount(this.repository, leaveGroupTransactionData.getLeaverPublicKey());

		// Restore membership using cached reference to transaction that caused membership
		this.rebuildMember(leaver.getAddress(), leaveGroupTransactionData.getMemberReference());

		byte[] adminTransactionSignature = leaveGroupTransactionData.getAdminReference();
		if (adminTransactionSignature != null)
			// Restore adminship using cached reference to transaction that caused adminship
			this.rebuildAdmin(leaver.getAddress(), adminTransactionSignature);

		// Revert leaver's defaultGroupId if necessary
		Integer previousDefaultGroupId = leaveGroupTransactionData.getPreviousGroupId();
		if (previousDefaultGroupId != null)
			leaver.setDefaultGroupId(previousDefaultGroupId);

		// Clear cached references
		leaveGroupTransactionData.setAdminReference(null);
		leaveGroupTransactionData.setMemberReference(null);
		leaveGroupTransactionData.setPreviousGroupId(null);
	}

}
