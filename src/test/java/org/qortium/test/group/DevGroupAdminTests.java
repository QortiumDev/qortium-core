package org.qortium.test.group;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.arbitrary.misc.Service;
import org.qortium.block.BlockChain;
import org.qortium.data.group.GroupAdminData;
import org.qortium.data.group.GroupApprovalData;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.*;
import org.qortium.group.Group;
import org.qortium.group.GroupApprovalCategory;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ValidationResult;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Dev group admin tests
 *
 * The dev group (ID 1) is owned by the null account with public key 11111111111111111111111111111111
 * To regain access to otherwise blocked owner-based rules, it has different validation logic
 * which applies to groups with this same null owner.
 *
 * The main difference is that approval is required for certain transaction types relating to
 * null-owned groups. This allows existing admins to approve updates to the group (using group's
 * approval threshold) instead of these actions being performed by the owner.
 *
 * Since these apply to all null-owned groups, this allows anyone to update their group to
 * the null owner if they want to take advantage of this decentralized approval system.
 *
 * The affected transaction types are:
 * - AddGroupAdminTransaction
 * - RemoveGroupAdminTransaction
 * - GroupInviteTransaction
 * - CancelGroupInviteTransaction
 * - GroupKickTransaction
 * - GroupBanTransaction
 * - CancelGroupBanTransaction
 * - UpdateGroupTransaction
 */
public class DevGroupAdminTests extends Common {

	private static final int DEV_GROUP_ID = 1;
	private static final int DEV_GROUP_APPROVAL_SPLIT_HEIGHT = 1000;

	public static final String ALICE = "alice";
	public static final String BOB = "bob";
	public static final String CHLOE = "chloe";
	public static final String DILBERT = "dilbert";

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestChainBootstrapUtils.ensureDevelopmentAdmin(repository, ALICE);
			repository.saveChanges();
		}
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testUpdateGroupRequiresApproval() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);

			GroupData originalGroupData = repository.getGroupRepository().fromGroupId(DEV_GROUP_ID);
			String updatedDescription = "Updated Qortium Development";

			ValidationResult result = updateGroup(repository, alice, Group.NO_GROUP, DEV_GROUP_ID, updatedDescription);
			assertEquals(ValidationResult.GROUP_APPROVAL_REQUIRED, result);
			assertEquals(originalGroupData.getDescription(), repository.getGroupRepository().fromGroupId(DEV_GROUP_ID).getDescription());

			TransactionData updateGroupTransactionData = createUpdateGroupForGroupApproval(repository, alice, DEV_GROUP_ID, updatedDescription);
			assertEquals(Transaction.ApprovalStatus.PENDING, GroupUtils.getApprovalStatus(repository, updateGroupTransactionData.getSignature()));

			assertEquals(Transaction.ApprovalStatus.APPROVED,
					signForGroupApproval(repository, updateGroupTransactionData, List.of(alice)));

			assertEquals(updatedDescription, repository.getGroupRepository().fromGroupId(DEV_GROUP_ID).getDescription());
		}
	}

	@Test
	public void testStaleApprovalAuthorityIsIgnored() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);

			assertEquals(Transaction.ApprovalStatus.APPROVED,
					approveGroupInvite(repository, alice, DEV_GROUP_ID, bob.getAddress(), 3600, List.of(alice)));
			assertEquals(ValidationResult.OK, joinGroup(repository, bob, DEV_GROUP_ID));

			TransactionData addBobAsAdmin = addGroupAdmin(repository, alice, DEV_GROUP_ID, bob.getAddress());
			assertEquals(Transaction.ApprovalStatus.APPROVED, signForGroupApproval(repository, addBobAsAdmin, List.of(alice)));
			assertTrue(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));

			TransactionData updateGroupTransactionData = createUpdateGroupForGroupApproval(repository, alice, DEV_GROUP_ID, "Ignored stale approval");
			GroupUtils.approveTransaction(repository, ALICE, updateGroupTransactionData.getSignature(), true);

			GroupApprovalData approvalData = repository.getTransactionRepository().getApprovalData(updateGroupTransactionData.getSignature(),
					repository.getBlockRepository().getBlockchainHeight());
			assertEquals(1, approvalData.approvingAdmins.size());

			TransactionData removeAliceAsAdmin = removeGroupAdmin(repository, bob, DEV_GROUP_ID, alice.getAddress());
			assertEquals(Transaction.ApprovalStatus.APPROVED, signForGroupApproval(repository, removeAliceAsAdmin, List.of(bob)));
			assertFalse(isAdmin(repository, alice.getAddress(), DEV_GROUP_ID));

			approvalData = repository.getTransactionRepository().getApprovalData(updateGroupTransactionData.getSignature(),
					repository.getBlockRepository().getBlockchainHeight());
			assertEquals(0, approvalData.approvingAdmins.size());
		}
	}

	@Test
	public void testGroupKickMember() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);

			// Dev group
			int groupId = DEV_GROUP_ID;

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Attempt to kick Bob
			ValidationResult result = groupKick(repository, alice, groupId, bob.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Alice invites Bob and the dev-group admins approve it
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					approveGroupInvite(repository, alice, groupId, bob.getAddress(), 3600, List.of(alice)));

			// Bob to join
			joinGroup(repository, bob, groupId);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Attempt to kick Bob
			result = groupKick(repository, alice, groupId, bob.getAddress());
			// Should not be OK without the required null-owner group approval
			assertNotSame(ValidationResult.OK, result);

			// Confirm Bob remains a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));
		}
	}

	@Test
	public void testGroupKickAdmin() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);

			// Dev group
			int groupId = DEV_GROUP_ID;

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Alice invites Bob and the dev-group admins approve it
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					approveGroupInvite(repository, alice, groupId, bob.getAddress(), 3600, List.of(alice)));

			// Bob to join
			joinGroup(repository, bob, groupId);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Promote Bob to admin
			TransactionData addGroupAdminTransactionData = addGroupAdmin(repository, alice, groupId, bob.getAddress());

			// Confirm transaction needs approval, and hasn't been approved
			Transaction.ApprovalStatus approvalStatus = GroupUtils.getApprovalStatus(repository, addGroupAdminTransactionData.getSignature());
			assertEquals("incorrect transaction approval status", Transaction.ApprovalStatus.PENDING, approvalStatus);

			// Have Alice approve Bob's approval-needed transaction
			approvalStatus = signForGroupApproval(repository, addGroupAdminTransactionData, List.of(alice));
			assertEquals("incorrect transaction approval status", Transaction.ApprovalStatus.APPROVED, approvalStatus);

			// Confirm Bob is now admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Attempt to kick Bob
			ValidationResult result = groupKick(repository, alice, groupId, bob.getAddress());
			// Shouldn't be allowed
			assertEquals(ValidationResult.INVALID_GROUP_OWNER, result);

			// Confirm Bob is still a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Confirm Bob still an admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob no longer an admin (ADD_GROUP_ADMIN no longer approved)
			assertFalse(isAdmin(repository, bob.getAddress(), groupId));

			// Have Alice try to kick herself!
			result = groupKick(repository, alice, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Have Bob try to kick Alice
			result = groupKick(repository, bob, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);
		}
	}

	@Test
	public void testGroupBanMember() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);

			// Dev group
			int groupId = DEV_GROUP_ID;

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Attempt to cancel non-existent Bob ban
			ValidationResult result = cancelGroupBan(repository, alice, groupId, bob.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Attempt to ban Bob
			result = groupBan(repository, alice, groupId, bob.getAddress());
			// Should not be OK without the required null-owner group approval
			assertNotSame(ValidationResult.OK, result);

			// Bob attempts to join
			result = joinGroup(repository, bob, groupId);
			// Should be OK because the default development group is public
			assertEquals(ValidationResult.OK, result);

			// Confirm Bob is now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Attempt to ban Bob
			result = groupBan(repository, alice, groupId, bob.getAddress());
			// Should not be OK without the required null-owner group approval
			assertNotSame(ValidationResult.OK, result);

			// Confirm Bob is still a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Bob attempts to rejoin
			result = joinGroup(repository, bob, groupId);
			// Should NOT be OK, because he is already a member
			assertNotSame(ValidationResult.OK, result);

			// Cancel Bob's ban
			result = cancelGroupBan(repository, alice, groupId, bob.getAddress());
			// Should not be OK, because there was no ban to begin with
			assertNotSame(ValidationResult.OK, result);
		}
	}

	@Test
	public void testGroupBanAdmin() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);

			// Dev group
			int groupId = DEV_GROUP_ID;

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Alice invites Bob and the dev-group admins approve it
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					approveGroupInvite(repository, alice, groupId, bob.getAddress(), 3600, List.of(alice)));

			// Bob to join
			ValidationResult result = joinGroup(repository, bob, groupId);
			// Should be OK
			assertEquals(ValidationResult.OK, result);

			// Promote Bob to admin
			TransactionData addGroupAdminTransactionData = addGroupAdmin(repository, alice, groupId, bob.getAddress());

			// Confirm transaction needs approval, and hasn't been approved
			Transaction.ApprovalStatus approvalStatus = GroupUtils.getApprovalStatus(repository, addGroupAdminTransactionData.getSignature());
			assertEquals("incorrect transaction approval status", Transaction.ApprovalStatus.PENDING, approvalStatus);

			// Have Alice approve Bob's approval-needed transaction
			approvalStatus = signForGroupApproval(repository, addGroupAdminTransactionData, List.of(alice));
			assertEquals("incorrect transaction approval status", Transaction.ApprovalStatus.APPROVED, approvalStatus);

			// Confirm Bob is now admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Attempt to ban Bob
			result = groupBan(repository, alice, groupId, bob.getAddress());
			// Direct bans in null-owned groups now require approval from genesis
			assertEquals(ValidationResult.GROUP_APPROVAL_REQUIRED, result);

			// Confirm Bob still a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// ... and still an admin
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));

			// Have Alice try to ban herself!
			result = groupBan(repository, alice, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);

			// Have Bob try to ban Alice
			result = groupBan(repository, bob, groupId, alice.getAddress());
			// Should NOT be OK
			assertNotSame(ValidationResult.OK, result);
		}
	}

	@Test
	public void testAddAdminExcludesNullAdminFromThreshold() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {

			// establish accounts
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);
			PrivateKeyAccount chloe = Common.getTestAccount(repository, CHLOE);
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, DILBERT);

			// assert admin statuses
			assertEquals(2, repository.getGroupRepository().countGroupAdmins(DEV_GROUP_ID).intValue());
			assertTrue(isAdmin(repository, Group.NULL_OWNER_ADDRESS, DEV_GROUP_ID));
			assertTrue(isAdmin(repository, alice.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, chloe.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, dilbert.getAddress(), DEV_GROUP_ID));

			// confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), DEV_GROUP_ID));

			// Alice invites Bob and the dev-group admins approve it
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					approveGroupInvite(repository, alice, DEV_GROUP_ID, bob.getAddress(), 3600, List.of(alice)));

			// bob joins
			joinGroup(repository, bob, DEV_GROUP_ID);

			// confirm Bob is a member now, but still not an admin
			assertTrue(isMember(repository, bob.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));

			// bob creates transaction to add himself as an admin
			TransactionData addGroupAdminTransactionData1 = addGroupAdmin(repository, bob, DEV_GROUP_ID, bob.getAddress());

			// bob creates add admin transaction for himself, alice signs which is 50% approval while 40% is needed
			signForGroupApproval(repository, addGroupAdminTransactionData1, List.of(alice));

			// assert 3 admins in group and bob is an admin now
			assertEquals(3, repository.getGroupRepository().countGroupAdmins(DEV_GROUP_ID).intValue() );
			assertTrue(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));

			// Bob invites Chloe and the dev-group admins approve it
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					approveGroupInvite(repository, bob, DEV_GROUP_ID, chloe.getAddress(), 3600, List.of(alice, bob)));

			// chloe joins
			joinGroup(repository, chloe, DEV_GROUP_ID);

			// confirm Chloe is a member now, but still not an admin
			assertTrue(isMember(repository, chloe.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, chloe.getAddress(), DEV_GROUP_ID));

			// chloe creates transaction to add herself as an admin
			TransactionData addChloeAsGroupAdmin = addGroupAdmin(repository, chloe, DEV_GROUP_ID, chloe.getAddress());

			// no one has signed, so it should be pending
			Transaction.ApprovalStatus addChloeAsGroupAdminStatus1 = GroupUtils.getApprovalStatus(repository, addChloeAsGroupAdmin.getSignature());
			assertEquals( Transaction.ApprovalStatus.PENDING, addChloeAsGroupAdminStatus1);

			// signer 1
			Transaction.ApprovalStatus addChloeAsGroupAdminStatus2 = signForGroupApproval(repository, addChloeAsGroupAdmin, List.of(alice));

			// 1 out of 2 usable admins has signed, so it should be approved because the null admin is ignored
			assertEquals( Transaction.ApprovalStatus.APPROVED, addChloeAsGroupAdminStatus2);
		}
	}

	@Test
	public void testOrphanSecondInviteApproval() throws DataException {

		try (final Repository repository = RepositoryManager.getRepository()) {

			// establish accounts
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);
			PrivateKeyAccount chloe = Common.getTestAccount(repository, CHLOE);
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, DILBERT);

			// assert admin statuses
			assertEquals(2, repository.getGroupRepository().countGroupAdmins(DEV_GROUP_ID).intValue());
			assertTrue(isAdmin(repository, Group.NULL_OWNER_ADDRESS, DEV_GROUP_ID));
			assertTrue(isAdmin(repository, alice.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, chloe.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, dilbert.getAddress(), DEV_GROUP_ID));

			// confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), DEV_GROUP_ID));

			// alice invites bob, alice signs which is 50% approval while 40% is needed
			TransactionData createInviteTransactionData = createGroupInviteForGroupApproval(repository, alice, DEV_GROUP_ID, bob.getAddress(), 3600);
			Transaction.ApprovalStatus bobsInviteStatus = signForGroupApproval(repository, createInviteTransactionData, List.of(alice));

			// assert approval
			assertEquals(Transaction.ApprovalStatus.APPROVED, bobsInviteStatus);

			// bob joins
			joinGroup(repository, bob, DEV_GROUP_ID);

			// confirm Bob is a member now, but still not an admin
			assertTrue(isMember(repository, bob.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));

			// bob creates transaction to add himself as an admin
			TransactionData addGroupAdminTransactionData1 = addGroupAdmin(repository, bob, DEV_GROUP_ID, bob.getAddress());

			// bob creates add admin transaction for himself, alice signs which is 50% approval while 40% is needed
			signForGroupApproval(repository, addGroupAdminTransactionData1, List.of(alice));

			// assert 3 admins in group and bob is an admin now
			assertEquals(3, repository.getGroupRepository().countGroupAdmins(DEV_GROUP_ID).intValue());
			assertTrue(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));

			// Add Chloe as a third usable admin so a single approval remains pending
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					approveGroupInvite(repository, bob, DEV_GROUP_ID, chloe.getAddress(), 3600, List.of(bob)));
			joinGroup(repository, chloe, DEV_GROUP_ID);
			TransactionData addChloeAsGroupAdmin = addGroupAdmin(repository, chloe, DEV_GROUP_ID, chloe.getAddress());
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					signForGroupApproval(repository, addChloeAsGroupAdmin, List.of(alice)));
			assertTrue(isAdmin(repository, chloe.getAddress(), DEV_GROUP_ID));

			// bob invites dilbert, bob signs which is 1 of 3 usable admins and remains pending
			TransactionData dilbertInvite1 = createGroupInviteForGroupApproval(repository, bob, DEV_GROUP_ID, dilbert.getAddress(), 3600);
			Transaction.ApprovalStatus dilbertInvite1Status = signForGroupApproval(repository, dilbertInvite1, List.of(bob));
			assertEquals(Transaction.ApprovalStatus.PENDING, dilbertInvite1Status);

			// bob invites dilbert again while the first invite is still pending
			TransactionData dilbertInvite2 = createGroupInviteForGroupApproval(repository, bob, DEV_GROUP_ID, dilbert.getAddress(), 3600);
			Transaction.ApprovalStatus dilbertInvite2Status = signForGroupApproval(repository, dilbertInvite2, List.of(bob));
			assertEquals(Transaction.ApprovalStatus.PENDING, dilbertInvite2Status);

			// alice signs which is 2 of 3 usable admins
			dilbertInvite1Status = signForGroupApproval(repository, dilbertInvite1, List.of(alice));
			assertEquals(Transaction.ApprovalStatus.APPROVED, dilbertInvite1Status);

			// dilbert joins
			joinGroup(repository, dilbert, DEV_GROUP_ID);
			assertTrue(isMember(repository, dilbert.getAddress(), DEV_GROUP_ID));

			// alice signs invite 2 after Dilbert joined
			dilbertInvite2Status = signForGroupApproval(repository, dilbertInvite2, List.of(alice));
			assertEquals(Transaction.ApprovalStatus.APPROVED, dilbertInvite2Status);

			boolean exceptionThrown = false;

			try {
				// confront the bug by orphaning the block of the second approval, approval after the join
				// prior to the fix, this would raise an exception
				BlockUtils.orphanLastBlock(repository);
			} catch (DataException e) {
				exceptionThrown = true;
			}

			Assert.assertFalse(exceptionThrown);

			// assert dilbert is still a member
			assertTrue(isMember(repository, dilbert.getAddress(), DEV_GROUP_ID));
		}
	}

	@Test
	public void testNullOwnedGroupFallsBackToMemberApprovalWithoutUsableAdmins() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);
			PrivateKeyAccount chloe = Common.getTestAccount(repository, CHLOE);

			assertEquals(2, repository.getGroupRepository().countGroupAdmins(DEV_GROUP_ID).intValue());
			assertEquals(1, repository.getGroupRepository().countUsableGroupAdmins(DEV_GROUP_ID));
			assertTrue(isAdmin(repository, Group.NULL_OWNER_ADDRESS, DEV_GROUP_ID));
			assertTrue(isAdmin(repository, alice.getAddress(), DEV_GROUP_ID));

			assertEquals(Transaction.ApprovalStatus.APPROVED,
					approveGroupInvite(repository, alice, DEV_GROUP_ID, bob.getAddress(), 3600, List.of(alice)));
			assertEquals(ValidationResult.OK, joinGroup(repository, bob, DEV_GROUP_ID));

			assertEquals(Transaction.ApprovalStatus.APPROVED,
					approveGroupInvite(repository, alice, DEV_GROUP_ID, chloe.getAddress(), 3600, List.of(alice)));
			assertEquals(ValidationResult.OK, joinGroup(repository, chloe, DEV_GROUP_ID));

			TransactionData removeAliceAsAdmin = removeGroupAdmin(repository, alice, DEV_GROUP_ID, alice.getAddress());
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					signForGroupApproval(repository, removeAliceAsAdmin, List.of(alice)));

			assertEquals(1, repository.getGroupRepository().countGroupAdmins(DEV_GROUP_ID).intValue());
			assertEquals(0, repository.getGroupRepository().countUsableGroupAdmins(DEV_GROUP_ID));
			assertTrue(isAdmin(repository, Group.NULL_OWNER_ADDRESS, DEV_GROUP_ID));
			assertFalse(isAdmin(repository, alice.getAddress(), DEV_GROUP_ID));

			TransactionData addBobAsAdmin = addGroupAdmin(repository, bob, DEV_GROUP_ID, bob.getAddress());
			assertEquals(Transaction.ApprovalStatus.PENDING,
					signForGroupApproval(repository, addBobAsAdmin, List.of(bob)));

			assertEquals(Transaction.ApprovalStatus.APPROVED,
					signForGroupApproval(repository, addBobAsAdmin, List.of(chloe)));

			assertTrue(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));
			assertEquals(1, repository.getGroupRepository().countUsableGroupAdmins(DEV_GROUP_ID));

			TransactionData addChloeAsAdmin = addGroupAdmin(repository, chloe, DEV_GROUP_ID, chloe.getAddress());
			assertEquals(ValidationResult.NOT_GROUP_ADMIN,
					groupApproval(repository, alice, addChloeAsAdmin, true));

			assertEquals(Transaction.ApprovalStatus.APPROVED,
					signForGroupApproval(repository, addChloeAsAdmin, List.of(bob)));

			assertTrue(isAdmin(repository, chloe.getAddress(), DEV_GROUP_ID));
		}
	}

	@Test
	public void testNullOwnershipMembership()  throws DataException{
		try (final Repository repository = RepositoryManager.getRepository()) {

			// establish accounts
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);
			PrivateKeyAccount chloe = Common.getTestAccount(repository, CHLOE);
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, DILBERT);

			// assert admin statuses
			assertEquals(2, repository.getGroupRepository().countGroupAdmins(DEV_GROUP_ID).intValue());
			assertTrue(isAdmin(repository, Group.NULL_OWNER_ADDRESS, DEV_GROUP_ID));
			assertTrue(isAdmin(repository, alice.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, chloe.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, dilbert.getAddress(), DEV_GROUP_ID));

			// confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), DEV_GROUP_ID));

			// alice invites bob, alice signs which is 50% approval while 40% is needed
			TransactionData createInviteTransactionData = createGroupInviteForGroupApproval(repository, alice, DEV_GROUP_ID, bob.getAddress(), 3600);
			Transaction.ApprovalStatus bobsInviteStatus = signForGroupApproval(repository, createInviteTransactionData, List.of(alice));

			// assert approval
			assertEquals(Transaction.ApprovalStatus.APPROVED, bobsInviteStatus);

			// bob joins
			joinGroup(repository, bob, DEV_GROUP_ID);

			// confirm Bob is a member now, but still not an admin
			assertTrue(isMember(repository, bob.getAddress(), DEV_GROUP_ID));
			assertFalse(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));

			// bob creates transaction to add himself as an admin
			TransactionData addGroupAdminTransactionData1 = addGroupAdmin(repository, bob, DEV_GROUP_ID, bob.getAddress());

			// bob creates add admin transaction for himself, alice signs which is 50% approval while 40% is needed
			signForGroupApproval(repository, addGroupAdminTransactionData1, List.of(alice));

			// assert 3 admins in group and bob is an admin now
			assertEquals(3, repository.getGroupRepository().countGroupAdmins(DEV_GROUP_ID).intValue());
			assertTrue(isAdmin(repository, bob.getAddress(), DEV_GROUP_ID));

			// bob invites chloe, bob signs which is 1 of 2 usable admins
			TransactionData chloeInvite = createGroupInviteForGroupApproval(repository, bob, DEV_GROUP_ID, chloe.getAddress(), 3600);
			Transaction.ApprovalStatus chloeInviteStatus = signForGroupApproval(repository, chloeInvite, List.of(bob));

			// assert approval
			assertEquals(Transaction.ApprovalStatus.APPROVED, chloeInviteStatus);

			// chloe joins
			joinGroup(repository, chloe, DEV_GROUP_ID);

			// assert chloe is in the group
			assertTrue(isMember(repository, chloe.getAddress(), DEV_GROUP_ID));

			// alice kicks chloe, alice signs which is 1 of 2 usable admins
			TransactionData chloeKick = createGroupKickForGroupApproval(repository, alice, DEV_GROUP_ID, chloe.getAddress(),"testing chloe kick");
			Transaction.ApprovalStatus chloeKickStatus = signForGroupApproval(repository, chloeKick, List.of(alice));

			// assert approval
			assertEquals(Transaction.ApprovalStatus.APPROVED, chloeKickStatus);

			// assert chloe is not in the group
			assertFalse(isMember(repository, chloe.getAddress(), DEV_GROUP_ID));

			// bob invites chloe, alice and bob signs which is 66% approval while 40% is needed
			TransactionData chloeInviteAgain = createGroupInviteForGroupApproval(repository, bob, DEV_GROUP_ID, chloe.getAddress(), 3600);
			Transaction.ApprovalStatus chloeInviteAgainStatus = signForGroupApproval(repository, chloeInviteAgain, List.of(alice, bob));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, chloeInviteAgainStatus);

			// chloe joins again
			joinGroup(repository, chloe, DEV_GROUP_ID);

			// assert chloe is in the group
			assertTrue(isMember(repository, chloe.getAddress(), DEV_GROUP_ID));

			// alice bans chloe, alice signs which is 1 of 2 usable admins
			TransactionData chloeBan = createGroupBanForGroupApproval(repository, alice, DEV_GROUP_ID, chloe.getAddress(), "testing group ban", 3600);
			Transaction.ApprovalStatus chloeBanStatus1 = signForGroupApproval(repository, chloeBan, List.of(alice));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, chloeBanStatus1);

			// assert chloe is not in the group
			assertFalse(isMember(repository, chloe.getAddress(), DEV_GROUP_ID));

			// bob invites chloe, alice and bob signs which is 66% approval while 40% is needed
			ValidationResult chloeInviteValidation = signAndImportGroupInvite(repository, bob, DEV_GROUP_ID, chloe.getAddress(), 3600);

			// assert banned status on invite attempt
			assertEquals(ValidationResult.BANNED_FROM_GROUP, chloeInviteValidation);

			// bob cancels ban on chloe, bob signs which is 1 of 2 usable admins
			TransactionData chloeCancelBan = createCancelGroupBanForGroupApproval( repository, bob, DEV_GROUP_ID, chloe.getAddress());
			Transaction.ApprovalStatus chloeCancelBanStatus1 = signForGroupApproval(repository, chloeCancelBan, List.of(bob));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, chloeCancelBanStatus1);

			// bob invites chloe, alice and bob signs which is 66% approval while 40% is needed
			TransactionData chloeInvite4 = createGroupInviteForGroupApproval(repository, bob, DEV_GROUP_ID, chloe.getAddress(), 3600);
			Transaction.ApprovalStatus chloeInvite4Status = signForGroupApproval(repository, chloeInvite4, List.of(alice, bob));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, chloeInvite4Status);

			// chloe joins again
			joinGroup(repository, chloe, DEV_GROUP_ID);

			// assert chloe is in the group
			assertTrue(isMember(repository, chloe.getAddress(), DEV_GROUP_ID));

			// bob invites dilbert, alice and bob signs which is 66% approval while 40% is needed
			TransactionData dilbertInvite1 = createGroupInviteForGroupApproval(repository, bob, DEV_GROUP_ID, dilbert.getAddress(), 3600);
			Transaction.ApprovalStatus dibertInviteStatus1 = signForGroupApproval(repository, dilbertInvite1, List.of(alice, bob));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, dibertInviteStatus1);

			// alice cancels dilbert's invite, alice signs which is 1 of 2 usable admins
			TransactionData cancelDilbertInvite = createCancelInviteForGroupApproval(repository, alice, DEV_GROUP_ID, dilbert.getAddress());
			Transaction.ApprovalStatus cancelDilbertInviteStatus1 = signForGroupApproval(repository, cancelDilbertInvite, List.of(alice));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, cancelDilbertInviteStatus1);

			// Dilbert joins after the invite is cancelled. Public groups still allow direct joins.
			joinGroup(repository, dilbert, DEV_GROUP_ID);

			// assert dilbert is in the group because membership no longer depends on an invite
			assertTrue(isMember(repository, dilbert.getAddress(), DEV_GROUP_ID));

			// alice kicks out dilbert, alice and bob sign which is 66% approval while 40% is needed
			TransactionData kickDilbert = createGroupKickForGroupApproval(repository, alice, DEV_GROUP_ID, dilbert.getAddress(), "he is sneaky");
			Transaction.ApprovalStatus kickDilbertStatus = signForGroupApproval(repository, kickDilbert, List.of(alice, bob));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, kickDilbertStatus);

			// assert dilbert is out of the group
			assertFalse(isMember(repository, dilbert.getAddress(), DEV_GROUP_ID));

			// bob invites dilbert again, alice and bob signs which is 66% approval while 40% is needed
			TransactionData dilbertInvite2 = createGroupInviteForGroupApproval(repository, bob, DEV_GROUP_ID, dilbert.getAddress(), 3600);
			Transaction.ApprovalStatus dibertInviteStatus2 = signForGroupApproval(repository, dilbertInvite2, List.of(alice, bob));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, dibertInviteStatus2);

			// alice cancels dilbert's invite, alice and bob signs which is 66% approval while 40% is needed
			TransactionData cancelDilbertInvite2 = createCancelInviteForGroupApproval(repository, alice, DEV_GROUP_ID, dilbert.getAddress());
			Transaction.ApprovalStatus cancelDilbertInviteStatus2 = signForGroupApproval(repository, cancelDilbertInvite2, List.of(alice, bob));

			// assert approved
			assertEquals(Transaction.ApprovalStatus.APPROVED, cancelDilbertInviteStatus2);

			// Dilbert joins after the group approves cancellation. Public groups still allow direct joins.
			joinGroup(repository, dilbert, DEV_GROUP_ID);

			// assert dilbert is in the group because membership no longer depends on an invite
			assertTrue(isMember(repository, dilbert.getAddress(), DEV_GROUP_ID));
		}
	}

	@Test
	public void testGetAdmin()  throws DataException{
		try (final Repository repository = RepositoryManager.getRepository()) {

			// establish accounts
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);

			GroupAdminData aliceAdminData = repository.getGroupRepository().getAdmin(DEV_GROUP_ID, alice.getAddress());

			assertNotNull(aliceAdminData);
			assertEquals( alice.getAddress(), aliceAdminData.getAdmin() );
			assertEquals( DEV_GROUP_ID, aliceAdminData.getGroupId());

			GroupAdminData bobAdminData = repository.getGroupRepository().getAdmin(DEV_GROUP_ID, bob.getAddress());

			assertNull(bobAdminData);
		}
	}

	@Test
	public void testDevGroupApprovalSplit() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, ALICE);
			PrivateKeyAccount bob = Common.getTestAccount(repository, BOB);
			PrivateKeyAccount chloe = Common.getTestAccount(repository, CHLOE);
			PrivateKeyAccount dilbert = Common.getTestAccount(repository, DILBERT);
			PrivateKeyAccount nonMember = Common.getTestAccount(repository, "alice-reward-share");

			assertEquals(DEV_GROUP_APPROVAL_SPLIT_HEIGHT, BlockChain.getInstance().getDevGroupApprovalSplitHeight());

			assertEquals(Transaction.ApprovalStatus.APPROVED,
					approveGroupInvite(repository, alice, DEV_GROUP_ID, bob.getAddress(), 3600, List.of(alice)));
			assertEquals(ValidationResult.OK, joinGroup(repository, bob, DEV_GROUP_ID));
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					approveGroupInvite(repository, alice, DEV_GROUP_ID, chloe.getAddress(), 3600, List.of(alice)));
			assertEquals(ValidationResult.OK, joinGroup(repository, chloe, DEV_GROUP_ID));
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					approveGroupInvite(repository, alice, DEV_GROUP_ID, dilbert.getAddress(), 3600, List.of(alice)));
			assertEquals(ValidationResult.OK, joinGroup(repository, dilbert, DEV_GROUP_ID));

			TransactionData addBobAsAdmin = addGroupAdmin(repository, alice, DEV_GROUP_ID, bob.getAddress());
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					signForGroupApproval(repository, addBobAsAdmin, List.of(alice)));

			int nextHeight = repository.getBlockRepository().getBlockchainHeight() + 1;
			assertTrue(nextHeight < DEV_GROUP_APPROVAL_SPLIT_HEIGHT);
			assertEquals(2, Group.countApprovalAuthorities(repository, DEV_GROUP_ID, Transaction.TransactionType.UPDATE_GROUP, nextHeight));
			assertEquals(2, Group.countApprovalAuthorities(repository, DEV_GROUP_ID, Transaction.TransactionType.ARBITRARY, nextHeight));

			TransactionData preTriggerUpdate = createUpdateGroupForGroupApproval(repository, chloe, DEV_GROUP_ID, "Pre-trigger admin approval");
			assertEquals(ValidationResult.NOT_GROUP_ADMIN, groupApproval(repository, chloe, preTriggerUpdate, true));
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					signForGroupApproval(repository, preTriggerUpdate, List.of(alice)));

			mintToHeight(repository, DEV_GROUP_APPROVAL_SPLIT_HEIGHT - 2);
			TransactionData boundaryUpdate = createUpdateGroupForGroupApproval(repository, chloe, DEV_GROUP_ID, "Post-trigger member approval");
			assertEquals(DEV_GROUP_APPROVAL_SPLIT_HEIGHT - 1, repository.getBlockRepository().getBlockchainHeight());

			nextHeight = repository.getBlockRepository().getBlockchainHeight() + 1;
			assertEquals(DEV_GROUP_APPROVAL_SPLIT_HEIGHT, nextHeight);
			assertEquals(4, Group.countApprovalAuthorities(repository, DEV_GROUP_ID, Transaction.TransactionType.UPDATE_GROUP, nextHeight));
			assertEquals(Transaction.ApprovalStatus.PENDING,
					signForGroupApproval(repository, boundaryUpdate, List.of(chloe)));
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					signForGroupApproval(repository, boundaryUpdate, List.of(dilbert)));

			TransactionData operationsTransaction = createAutoUpdateTransaction(repository, chloe, DEV_GROUP_ID);
			nextHeight = repository.getBlockRepository().getBlockchainHeight() + 1;
			assertEquals(2, Group.countApprovalAuthorities(repository, DEV_GROUP_ID, Transaction.TransactionType.ARBITRARY, nextHeight));
			assertEquals(ValidationResult.NOT_GROUP_ADMIN, groupApproval(repository, chloe, operationsTransaction, true));
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					signForGroupApproval(repository, operationsTransaction, List.of(alice)));

			TransactionData governanceTransaction = createGroupKickForGroupApproval(repository, chloe, DEV_GROUP_ID,
					dilbert.getAddress(), "member-decided kick");
			nextHeight = repository.getBlockRepository().getBlockchainHeight() + 1;
			assertEquals(4, Group.countApprovalAuthorities(repository, DEV_GROUP_ID, Transaction.TransactionType.GROUP_KICK, nextHeight));
			assertEquals(ValidationResult.NOT_GROUP_ADMIN, groupApproval(repository, nonMember, governanceTransaction, true));
			assertEquals(Transaction.ApprovalStatus.PENDING,
					signForGroupApproval(repository, governanceTransaction, List.of(alice)));
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					signForGroupApproval(repository, governanceTransaction, List.of(chloe)));
			assertFalse(isMember(repository, dilbert.getAddress(), DEV_GROUP_ID));

			TransactionData removeAliceAsAdmin = removeGroupAdmin(repository, chloe, DEV_GROUP_ID, alice.getAddress());
			assertEquals(Transaction.ApprovalStatus.PENDING,
					signForGroupApproval(repository, removeAliceAsAdmin, List.of(alice)));
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					signForGroupApproval(repository, removeAliceAsAdmin, List.of(chloe)));

			TransactionData removeBobAsAdmin = removeGroupAdmin(repository, chloe, DEV_GROUP_ID, bob.getAddress());
			assertEquals(Transaction.ApprovalStatus.PENDING,
					signForGroupApproval(repository, removeBobAsAdmin, List.of(bob)));
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					signForGroupApproval(repository, removeBobAsAdmin, List.of(chloe)));
			assertEquals(0, repository.getGroupRepository().countUsableGroupAdmins(DEV_GROUP_ID));

			nextHeight = repository.getBlockRepository().getBlockchainHeight() + 1;
			assertEquals(3, Group.countApprovalAuthorities(repository, DEV_GROUP_ID, Transaction.TransactionType.ARBITRARY, nextHeight));
			assertEquals(3, Group.countApprovalAuthorities(repository, DEV_GROUP_ID, Transaction.TransactionType.GROUP_KICK, nextHeight));

			TransactionData fallbackOperationsTransaction = createAutoUpdateTransaction(repository, chloe, DEV_GROUP_ID);
			assertEquals(Transaction.ApprovalStatus.PENDING,
					signForGroupApproval(repository, fallbackOperationsTransaction, List.of(chloe)));
			assertEquals(Transaction.ApprovalStatus.APPROVED,
					signForGroupApproval(repository, fallbackOperationsTransaction, List.of(alice)));
		}
	}

	@Test
	public void testDevGroupApprovalCategoryMapping() {
		EnumSet<Transaction.TransactionType> governanceTypes = EnumSet.of(
				Transaction.TransactionType.UPDATE_GROUP,
				Transaction.TransactionType.ADD_GROUP_ADMIN,
				Transaction.TransactionType.REMOVE_GROUP_ADMIN,
				Transaction.TransactionType.GROUP_KICK,
				Transaction.TransactionType.GROUP_BAN,
				Transaction.TransactionType.CANCEL_GROUP_BAN,
				Transaction.TransactionType.GROUP_INVITE,
				Transaction.TransactionType.CANCEL_GROUP_INVITE);

		for (Transaction.TransactionType transactionType : Transaction.TransactionType.values()) {
			GroupApprovalCategory expectedCategory = governanceTypes.contains(transactionType)
					? GroupApprovalCategory.GOVERNANCE
					: GroupApprovalCategory.OPERATIONS;
			assertEquals(expectedCategory, GroupApprovalCategory.fromTransactionType(transactionType));
		}
	}

	private Transaction.ApprovalStatus signForGroupApproval(Repository repository, TransactionData data, List<PrivateKeyAccount> signers) throws DataException {

		for (PrivateKeyAccount signer : signers) {
			signTransactionDataForGroupApproval(repository, signer, data);
		}

		BlockUtils.mintBlocks(repository, getApprovalSettlementBlockCount(repository, data));

		// return approval status
		return GroupUtils.getApprovalStatus(repository, data.getSignature());
	}

	private int getApprovalSettlementBlockCount(Repository repository, TransactionData data) throws DataException {
		int groupId = data.getTxGroupId();
		if (groupId == Group.NO_GROUP)
			return 2;

		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
		if (groupData == null)
			return 2;

		return Math.max(2, groupData.getMinimumBlockDelay() + 1);
	}

	private static void signTransactionDataForGroupApproval(Repository repository, PrivateKeyAccount signer, TransactionData transactionData) throws DataException {
		assertEquals(ValidationResult.OK, groupApproval(repository, signer, transactionData, true));
	}

	private static ValidationResult groupApproval(Repository repository, PrivateKeyAccount signer, TransactionData transactionData, boolean decision) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);

		BaseTransactionData baseTransactionData
				= new BaseTransactionData(timestamp, Group.NO_GROUP, signer.getPublicKey(), GroupUtils.fee, null);
		TransactionData groupApprovalTransactionData
				= new GroupApprovalTransactionData(baseTransactionData, transactionData.getSignature(), decision);

		return TransactionUtils.signAndImport(repository, groupApprovalTransactionData, signer);
	}

	private static void mintToHeight(Repository repository, int height) throws DataException {
		int blockCount = height - repository.getBlockRepository().getBlockchainHeight();
		if (blockCount > 0)
			BlockUtils.mintBlocks(repository, blockCount);
	}

	private static TransactionData createAutoUpdateTransaction(Repository repository, PrivateKeyAccount creator, int groupId) throws DataException {
		BaseTransactionData baseTransactionData = TestTransaction.generateBase(creator, groupId);
		int version = Transaction.getVersionByTimestamp(baseTransactionData.getTimestamp());
		byte[] data = new byte[60];
		ArbitraryTransactionData transactionData = new ArbitraryTransactionData(baseTransactionData, version,
				Service.AUTO_UPDATE.value, 0, data.length, null, null, ArbitraryTransactionData.Method.PUT,
				null, ArbitraryTransactionData.Compression.NONE, data, ArbitraryTransactionData.DataType.RAW_DATA,
				null, Collections.emptyList());

		TransactionUtils.signAndMint(repository, transactionData, creator);
		return transactionData;
	}

	private ValidationResult joinGroup(Repository repository, PrivateKeyAccount joiner, int groupId) throws DataException {
		JoinGroupTransactionData transactionData = new JoinGroupTransactionData(TestTransaction.generateBase(joiner), groupId);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, joiner);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private Transaction.ApprovalStatus approveGroupInvite(Repository repository, PrivateKeyAccount admin, int groupId,
			String invitee, int timeToLive, List<PrivateKeyAccount> signers) throws DataException {
		TransactionData transactionData = createGroupInviteForGroupApproval(repository, admin, groupId, invitee, timeToLive);
		return signForGroupApproval(repository, transactionData, signers);
	}

	private TransactionData createGroupInviteForGroupApproval(Repository repository, PrivateKeyAccount admin, int groupId, String invitee, int timeToLive) throws DataException {
		GroupInviteTransactionData transactionData = new GroupInviteTransactionData(TestTransaction.generateBase(admin, groupId), groupId, invitee, timeToLive);
		TransactionUtils.signAndMint(repository, transactionData, admin);
		return transactionData;
	}

	private TransactionData createCancelInviteForGroupApproval(Repository repository, PrivateKeyAccount admin, int groupId, String inviteeToCancel) throws  DataException {
		CancelGroupInviteTransactionData transactionData = new CancelGroupInviteTransactionData(TestTransaction.generateBase(admin, groupId), groupId, inviteeToCancel);
		TransactionUtils.signAndMint(repository, transactionData, admin);
		return transactionData;
	}

	private ValidationResult signAndImportGroupInvite(Repository repository, PrivateKeyAccount admin, int groupId, String invitee, int timeToLive) throws DataException {
		GroupInviteTransactionData transactionData = new GroupInviteTransactionData(TestTransaction.generateBase(admin, groupId), groupId, invitee, timeToLive);
		return TransactionUtils.signAndImport(repository, transactionData, admin);
	}

	private ValidationResult groupKick(Repository repository, PrivateKeyAccount admin, int groupId, String member) throws DataException {
		GroupKickTransactionData transactionData = new GroupKickTransactionData(TestTransaction.generateBase(admin), groupId, member, "testing");
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private TransactionData createGroupKickForGroupApproval(Repository repository, PrivateKeyAccount admin, int groupId, String kicked, String reason) throws DataException {
		GroupKickTransactionData transactionData = new GroupKickTransactionData(TestTransaction.generateBase(admin, groupId), groupId, kicked, reason);
		TransactionUtils.signAndMint(repository, transactionData, admin);

		return transactionData;
	}

	private ValidationResult groupBan(Repository repository, PrivateKeyAccount admin, int groupId, String member) throws DataException {
		GroupBanTransactionData transactionData = new GroupBanTransactionData(TestTransaction.generateBase(admin), groupId, member, "testing", 0);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private TransactionData createGroupBanForGroupApproval(Repository repository, PrivateKeyAccount admin, int groupId, String banned, String reason, int timeToLive) throws DataException {
		GroupBanTransactionData transactionData = new GroupBanTransactionData(TestTransaction.generateBase(admin, groupId), groupId, banned, reason, timeToLive);
		TransactionUtils.signAndMint(repository, transactionData, admin);

		return transactionData;
	}

	private ValidationResult cancelGroupBan(Repository repository, PrivateKeyAccount admin, int groupId, String member) throws DataException {
		CancelGroupBanTransactionData transactionData = new CancelGroupBanTransactionData(TestTransaction.generateBase(admin), groupId, member);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private TransactionData createCancelGroupBanForGroupApproval(Repository repository, PrivateKeyAccount admin, int groupId, String unbanned ) throws  DataException {
		CancelGroupBanTransactionData transactionData = new CancelGroupBanTransactionData( TestTransaction.generateBase(admin, groupId), groupId, unbanned);

		TransactionUtils.signAndMint(repository, transactionData, admin);

		return transactionData;
	}

	private TransactionData addGroupAdmin(Repository repository, PrivateKeyAccount owner, int groupId, String member) throws DataException {
		AddGroupAdminTransactionData transactionData = new AddGroupAdminTransactionData(TestTransaction.generateBase(owner), groupId, member);
		transactionData.setTxGroupId(groupId);
		TransactionUtils.signAndMint(repository, transactionData, owner);
		return transactionData;
	}

	private TransactionData removeGroupAdmin(Repository repository, PrivateKeyAccount owner, int groupId, String admin) throws DataException {
		RemoveGroupAdminTransactionData transactionData = new RemoveGroupAdminTransactionData(TestTransaction.generateBase(owner, groupId), groupId, admin);
		TransactionUtils.signAndMint(repository, transactionData, owner);
		return transactionData;
	}

	private ValidationResult updateGroup(Repository repository, PrivateKeyAccount updater, int txGroupId, int groupId, String newDescription) throws DataException {
		UpdateGroupTransactionData transactionData = createUpdateGroupTransactionData(repository, updater, txGroupId, groupId, newDescription);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, updater);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private TransactionData createUpdateGroupForGroupApproval(Repository repository, PrivateKeyAccount updater, int groupId, String newDescription) throws DataException {
		UpdateGroupTransactionData transactionData = createUpdateGroupTransactionData(repository, updater, groupId, groupId, newDescription);
		TransactionUtils.signAndMint(repository, transactionData, updater);
		return transactionData;
	}

	private UpdateGroupTransactionData createUpdateGroupTransactionData(Repository repository, PrivateKeyAccount updater, int txGroupId, int groupId, String newDescription) throws DataException {
		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);

		return new UpdateGroupTransactionData(TestTransaction.generateBase(updater, txGroupId), groupId,
				newDescription, groupData.isOpen(), groupData.getApprovalThreshold(), groupData.getMinimumBlockDelay(),
				groupData.getMaximumBlockDelay());
	}

	private boolean isMember(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().memberExists(groupId, address);
	}

	private boolean isAdmin(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().adminExists(groupId, address);
	}

}
