package org.qortal.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.block.BlockChain;
import org.qortal.data.group.GroupInviteData;
import org.qortal.data.group.GroupAdminData;
import org.qortal.data.transaction.AddGroupAdminTransactionData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.CancelGroupInviteTransactionData;
import org.qortal.data.transaction.CreateGroupTransactionData;
import org.qortal.data.transaction.GroupInviteTransactionData;
import org.qortal.data.transaction.JoinGroupTransactionData;
import org.qortal.data.transaction.LeaveGroupTransactionData;
import org.qortal.group.Group;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.Transaction.ValidationResult;

import static org.junit.Assert.*;

public class MiscTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		Common.orphanCheck();
	}

	@Test
	public void testCreateGroupWithExistingName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			// Create group
			createGroup(repository, alice, "test-group", false);

			// duplicate
			String duplicateGroupName = "TEST-gr0up";
			String description = duplicateGroupName + " (description)";

			boolean isOpen = false;
			ApprovalThreshold approvalThreshold = ApprovalThreshold.ONE;
			int minimumBlockDelay = 10;
			int maximumBlockDelay = 1440;

			CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(alice), duplicateGroupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay);
			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertTrue("Transaction should be invalid", ValidationResult.OK != result);
		}
	}

	@Test
	public void testJoinOpenGroup() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = createGroup(repository, alice, "open-group", true);

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Bob to join
			joinGroup(repository, bob, groupId);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));
		}
	}

	@Test
	public void testJoinClosedGroup() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = createGroup(repository, alice, "closed-group", false);

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Bob to join
			joinGroup(repository, bob, groupId);

			// Confirm Bob still not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Have Alice 'invite' Bob to confirm membership
			groupInvite(repository, alice, groupId, bob.getAddress(), 0); // non-expiring invite

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));
		}
	}

	@Test
	public void testJoinGroupViaInvite() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = createGroup(repository, alice, "closed-group", false);

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Have Alice 'invite' Bob to join
			groupInvite(repository, alice, groupId, bob.getAddress(), 0); // non-expiring invite

			// Confirm Bob still not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Bob uses invite to join
			joinGroup(repository, bob, groupId);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));
		}
	}

	@Test
	public void testJoinClosedGroupWithExpiredInviteCreatesJoinRequest() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			int groupId = createGroup(repository, alice, "expired-invite-group", false);

			int timeToLive = 1;
			GroupInviteTransactionData inviteTransactionData = groupInviteAtTimestamp(repository, alice, groupId, bob.getAddress(),
					timeToLive, TransactionUtils.nextTimestamp(repository) - 2000L);
			long expiry = inviteTransactionData.getTimestamp() + timeToLive * 1000L;

			assertNotNull(repository.getGroupRepository().getInvite(groupId, bob.getAddress()));
			assertTrue(repository.getGroupRepository().inviteExists(groupId, bob.getAddress(), expiry - 1));
			assertFalse(repository.getGroupRepository().inviteExists(groupId, bob.getAddress(), expiry));

			joinGroup(repository, bob, groupId);

			assertFalse(isMember(repository, bob.getAddress(), groupId));
			assertTrue(repository.getGroupRepository().joinRequestExists(groupId, bob.getAddress()));
		}
	}

	@Test
	public void testCancelExpiredGroupInvite() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			int groupId = createGroup(repository, alice, "expired-cancel-invite-group", false);

			int timeToLive = 1;
			GroupInviteTransactionData inviteTransactionData = groupInviteAtTimestamp(repository, alice, groupId, bob.getAddress(),
					timeToLive, TransactionUtils.nextTimestamp(repository) - 2000L);
			long expiry = inviteTransactionData.getTimestamp() + timeToLive * 1000L;

			assertNotNull(repository.getGroupRepository().getInvite(groupId, bob.getAddress()));
			assertFalse(repository.getGroupRepository().inviteExists(groupId, bob.getAddress(), expiry));

			ValidationResult result = cancelGroupInvite(repository, alice, groupId, bob.getAddress());
			assertEquals(ValidationResult.INVITE_UNKNOWN, result);
			assertNotNull(repository.getGroupRepository().getInvite(groupId, bob.getAddress()));
		}
	}

	@Test
	public void testLongGroupInviteExpiryDoesNotOverflow() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			int groupId = createGroup(repository, alice, "long-invite-group", false);

			int timeToLive = 30 * 24 * 60 * 60;
			GroupInviteTransactionData inviteTransactionData = groupInviteAtTimestamp(repository, alice, groupId, bob.getAddress(),
					timeToLive, TransactionUtils.nextTimestamp(repository));

			GroupInviteData inviteData = repository.getGroupRepository().getInvite(groupId, bob.getAddress());
			long expectedExpiry = inviteTransactionData.getTimestamp() + timeToLive * 1000L;

			assertEquals(Long.valueOf(expectedExpiry), inviteData.getExpiry());
			assertTrue(repository.getGroupRepository().inviteExists(groupId, bob.getAddress(),
					inviteTransactionData.getTimestamp() + 29L * 24 * 60 * 60 * 1000));
			assertFalse(repository.getGroupRepository().inviteExists(groupId, bob.getAddress(),
					inviteTransactionData.getTimestamp() + 31L * 24 * 60 * 60 * 1000));
		}
	}

	@Test
	public void testLeaveGroup() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = createGroup(repository, alice, "open-group", true);

			// Confirm Bob is not a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Bob to join
			joinGroup(repository, bob, groupId);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Bob leaves
			leaveGroup(repository, bob, groupId);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob now a member
			assertTrue(isMember(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob no longer a member
			assertFalse(isMember(repository, bob.getAddress(), groupId));
		}
	}

	@Test
	public void testLeaveGroupAsAdminRestoresCorrectAdminReference() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");

			// Create group
			int groupId = createGroup(repository, alice, "open-group", true);

			// Bob joins and becomes admin
			joinGroup(repository, bob, groupId);
			addGroupAdmin(repository, alice, groupId, bob.getAddress());

			GroupAdminData originalBobAdminData = repository.getGroupRepository().getAdmin(groupId, bob.getAddress());
			assertNotNull(originalBobAdminData);

			// Bob leaves
			leaveGroup(repository, bob, groupId);

			// Confirm Bob is no longer a member or admin
			assertFalse(isMember(repository, bob.getAddress(), groupId));
			assertFalse(isAdmin(repository, bob.getAddress(), groupId));

			// Orphan last block
			BlockUtils.orphanLastBlock(repository);

			// Confirm Bob is again a member and admin, with the same admin reference
			assertTrue(isMember(repository, bob.getAddress(), groupId));
			assertTrue(isAdmin(repository, bob.getAddress(), groupId));
			GroupAdminData restoredBobAdminData = repository.getGroupRepository().getAdmin(groupId, bob.getAddress());
			assertNotNull(restoredBobAdminData);
			assertArrayEquals(originalBobAdminData.getReference(), restoredBobAdminData.getReference());
		}
	}

	private Integer createGroup(Repository repository, PrivateKeyAccount owner, String groupName, boolean isOpen) throws DataException {
		String description = groupName + " (description)";

		ApprovalThreshold approvalThreshold = ApprovalThreshold.ONE;
		int minimumBlockDelay = 10;
		int maximumBlockDelay = 1440;

		CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(owner), groupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay);
		TransactionUtils.signAndMint(repository, transactionData, owner);

		return repository.getGroupRepository().fromGroupName(groupName).getGroupId();
	}

	private void joinGroup(Repository repository, PrivateKeyAccount joiner, int groupId) throws DataException {
		JoinGroupTransactionData transactionData = new JoinGroupTransactionData(TestTransaction.generateBase(joiner), groupId);
		TransactionUtils.signAndMint(repository, transactionData, joiner);
	}

	private void groupInvite(Repository repository, PrivateKeyAccount admin, int groupId, String invitee, int timeToLive) throws DataException {
		GroupInviteTransactionData transactionData = new GroupInviteTransactionData(TestTransaction.generateBase(admin), groupId, invitee, timeToLive);
		TransactionUtils.signAndMint(repository, transactionData, admin);
	}

	private GroupInviteTransactionData groupInviteAtTimestamp(Repository repository, PrivateKeyAccount admin, int groupId, String invitee,
			int timeToLive, long timestamp) throws DataException {
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, admin.getPublicKey(),
				BlockChain.getInstance().getUnitFeeAtTimestamp(timestamp), null);
		GroupInviteTransactionData transactionData = new GroupInviteTransactionData(baseTransactionData, groupId, invitee, timeToLive);
		TransactionUtils.signAndMint(repository, transactionData, admin);

		return transactionData;
	}

	private ValidationResult cancelGroupInvite(Repository repository, PrivateKeyAccount admin, int groupId, String invitee) throws DataException {
		CancelGroupInviteTransactionData transactionData = new CancelGroupInviteTransactionData(TestTransaction.generateBase(admin), groupId, invitee);
		ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, admin);

		if (result == ValidationResult.OK)
			BlockUtils.mintBlock(repository);

		return result;
	}

	private void addGroupAdmin(Repository repository, PrivateKeyAccount owner, int groupId, String member) throws DataException {
		AddGroupAdminTransactionData transactionData = new AddGroupAdminTransactionData(TestTransaction.generateBase(owner), groupId, member);
		TransactionUtils.signAndMint(repository, transactionData, owner);
	}

	private void leaveGroup(Repository repository, PrivateKeyAccount leaver, int groupId) throws DataException {
		LeaveGroupTransactionData transactionData = new LeaveGroupTransactionData(TestTransaction.generateBase(leaver), groupId);
		TransactionUtils.signAndMint(repository, transactionData, leaver);
	}

	private boolean isMember(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().memberExists(groupId, address);
	}

	private boolean isAdmin(Repository repository, String address, int groupId) throws DataException {
		return repository.getGroupRepository().adminExists(groupId, address);
	}

}
