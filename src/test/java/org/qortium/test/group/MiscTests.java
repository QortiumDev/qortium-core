package org.qortium.test.group;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.block.BlockChain;
import org.qortium.crypto.Crypto;
import org.qortium.data.account.AccountData;
import org.qortium.data.group.GroupInviteData;
import org.qortium.data.group.GroupAdminData;
import org.qortium.data.account.RewardShareData;
import org.qortium.data.transaction.AddGroupAdminTransactionData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.CancelGroupInviteTransactionData;
import org.qortium.data.transaction.CreateGroupTransactionData;
import org.qortium.data.transaction.GroupInviteTransactionData;
import org.qortium.data.transaction.JoinGroupTransactionData;
import org.qortium.data.transaction.LeaveGroupTransactionData;
import org.qortium.group.Group;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.Unicode;

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
	public void testCreateGroupRejectsIConfusableReducedName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");

			createGroup(repository, alice, "sample-label", false);

			String duplicateGroupName = "sample-Iabel";
			String description = duplicateGroupName + " (description)";
			boolean isOpen = false;
			ApprovalThreshold approvalThreshold = ApprovalThreshold.ONE;
			int minimumBlockDelay = 10;
			int maximumBlockDelay = 1440;

			CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(alice),
					duplicateGroupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay);

			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertEquals(ValidationResult.GROUP_ALREADY_EXISTS, result);
		}
	}

	@Test
	public void testCreateGroupRejectsTrailingVisualBlankName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String groupName = "sample-space";
			String spoofedGroupName = groupName + Unicode.BRAILLE_PATTERN_BLANK;
			String description = "description";
			boolean isOpen = false;
			ApprovalThreshold approvalThreshold = ApprovalThreshold.ONE;
			int minimumBlockDelay = 10;
			int maximumBlockDelay = 1440;

			CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(alice),
					spoofedGroupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay);
			assertEquals(Unicode.sanitize(groupName), transactionData.getReducedGroupName());

			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertEquals(ValidationResult.NAME_NOT_NORMALIZED, result);
		}
	}

	@Test
	public void testCreateGroupRejectsBidiControlName() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			String groupName = "sample-name";
			String spoofedGroupName = "sam\u202eple-name";
			String description = "description";
			boolean isOpen = false;
			ApprovalThreshold approvalThreshold = ApprovalThreshold.ONE;
			int minimumBlockDelay = 10;
			int maximumBlockDelay = 1440;

			CreateGroupTransactionData transactionData = new CreateGroupTransactionData(TestTransaction.generateBase(alice),
					spoofedGroupName, description, isOpen, approvalThreshold, minimumBlockDelay, maximumBlockDelay);
			assertEquals(Unicode.sanitize(groupName), transactionData.getReducedGroupName());

			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, alice);
			assertEquals(ValidationResult.NAME_NOT_NORMALIZED, result);
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
	public void testFirstJoinGroupTransactionPublishesAccount() throws DataException {
		Common.useSettings("test-settings-v2-no-native-asset.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			alice.ensureAccount();
			TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
			repository.saveChanges();

			PrivateKeyAccount joiner = Common.generateRandomSeedAccount(repository);
			int groupId = TestChainBootstrapUtils.MINTING_GROUP_ID;

			assertNull(repository.getAccountRepository().getAccount(joiner.getAddress()));

			BaseTransactionData baseTransactionData = new BaseTransactionData(TransactionUtils.nextTimestamp(repository),
					Group.NO_GROUP, joiner.getPublicKey(), 0L, null);
			JoinGroupTransactionData transactionData = new JoinGroupTransactionData(baseTransactionData, groupId);
			TransactionUtils.signAndMint(repository, transactionData, joiner);

			AccountData joinerData = repository.getAccountRepository().getAccount(joiner.getAddress());
			assertNotNull(joinerData);
			assertArrayEquals(joiner.getPublicKey(), joinerData.getPublicKey());
			assertEquals(groupId, joinerData.getDefaultGroupId());
			assertTrue(repository.getGroupRepository().memberExists(groupId, joiner.getAddress()));
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
	public void testJoinMintingGroupCreatesMintingAuthorization() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			byte[] mintingPublicKey = getSelfMintingPublicKey(bob);

			assertNull(repository.getAccountRepository().getRewardShare(mintingPublicKey));

			JoinGroupTransactionData transactionData = new JoinGroupTransactionData(TestTransaction.generateBase(bob),
					TestChainBootstrapUtils.MINTING_GROUP_ID, mintingPublicKey);

			Transaction transaction = Transaction.fromData(repository, transactionData);
			transaction.sign(bob);
			byte[] serializedTransaction = TransactionTransformer.toBytes(transactionData);
			JoinGroupTransactionData deserializedTransactionData = (JoinGroupTransactionData) TransactionTransformer.fromBytes(serializedTransaction);
			assertArrayEquals(mintingPublicKey, deserializedTransactionData.getMintingPublicKey());

			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, bob);
			assertEquals(ValidationResult.OK, result);
			BlockUtils.mintBlock(repository);

			assertTrue(isMember(repository, bob.getAddress(), TestChainBootstrapUtils.MINTING_GROUP_ID));

			RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(mintingPublicKey);
			assertNotNull(rewardShareData);
			assertArrayEquals(bob.getPublicKey(), rewardShareData.getMinterPublicKey());
			assertEquals(bob.getAddress(), rewardShareData.getMinter());
			assertEquals(bob.getAddress(), rewardShareData.getRecipient());
			assertEquals(0, rewardShareData.getSharePercent());

			BlockUtils.orphanLastBlock(repository);

			assertFalse(isMember(repository, bob.getAddress(), TestChainBootstrapUtils.MINTING_GROUP_ID));
			assertNull(repository.getAccountRepository().getRewardShare(mintingPublicKey));
		}
	}

	@Test
	public void testJoinNonMintingGroupRejectsMintingAuthorization() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			byte[] mintingPublicKey = getSelfMintingPublicKey(bob);

			JoinGroupTransactionData transactionData = new JoinGroupTransactionData(TestTransaction.generateBase(bob),
					TestChainBootstrapUtils.DEVELOPMENT_GROUP_ID, mintingPublicKey);

			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, bob);
			assertEquals(ValidationResult.INVALID_GROUP_ID, result);
		}
	}

	@Test
	public void testJoinMintingGroupDoesNotReplaceExistingSelfShare() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount chloeRewardShare = Common.getTestAccount(repository, "chloe-reward-share");
			byte[] requestedMintingPublicKey = getSelfMintingPublicKey(bob);

			RewardShareData existingSelfShareData = new RewardShareData(bob.getPublicKey(), bob.getAddress(),
					bob.getAddress(), chloeRewardShare.getPublicKey(), 0);
			repository.getAccountRepository().save(existingSelfShareData);
			repository.saveChanges();

			JoinGroupTransactionData transactionData = new JoinGroupTransactionData(TestTransaction.generateBase(bob),
					TestChainBootstrapUtils.MINTING_GROUP_ID, requestedMintingPublicKey);
			TransactionUtils.signAndMint(repository, transactionData, bob);

			RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(bob.getPublicKey(), bob.getAddress());
			assertNotNull(rewardShareData);
			assertArrayEquals(chloeRewardShare.getPublicKey(), rewardShareData.getRewardSharePublicKey());
			assertNull(repository.getAccountRepository().getRewardShare(requestedMintingPublicKey));
		}
	}

	@Test
	public void testJoinMintingGroupRejectsReusedMintingPublicKey() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount aliceRewardShare = Common.getTestAccount(repository, "alice-reward-share");

			RewardShareData existingSelfShareData = new RewardShareData(alice.getPublicKey(), alice.getAddress(),
					alice.getAddress(), aliceRewardShare.getPublicKey(), 0);
			repository.getAccountRepository().save(existingSelfShareData);
			repository.saveChanges();

			JoinGroupTransactionData transactionData = new JoinGroupTransactionData(TestTransaction.generateBase(bob),
					TestChainBootstrapUtils.MINTING_GROUP_ID, aliceRewardShare.getPublicKey());

			ValidationResult result = TransactionUtils.signAndImport(repository, transactionData, bob);
			assertEquals(ValidationResult.INVALID_PUBLIC_KEY, result);
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

	private byte[] getSelfMintingPublicKey(PrivateKeyAccount account) {
		return Crypto.toPublicKey(account.getRewardSharePrivateKey(account.getPublicKey()));
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
