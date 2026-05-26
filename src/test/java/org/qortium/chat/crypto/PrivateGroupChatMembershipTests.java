package org.qortium.chat.crypto;

import org.junit.Before;
import org.junit.Test;
import org.qortium.data.account.AccountData;
import org.qortium.data.group.GroupData;
import org.qortium.data.group.GroupMemberData;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestAccount;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class PrivateGroupChatMembershipTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testCurrentClosedGroupEpochIsStable() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			int groupId = createClosedGroup(repository, alice, "membership-stable");
			addMember(repository, groupId, bob);

			PrivateGroupChatMembership.MembershipEpoch firstEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId);
			PrivateGroupChatMembership.MembershipEpoch secondEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId);

			assertEquals(groupId, firstEpoch.getGroupId());
			assertEquals(2, firstEpoch.getMemberPublicKeys().size());
			assertArrayEquals(firstEpoch.getEpochId(), secondEpoch.getEpochId());
			assertArrayEquals(firstEpoch.getEpochId(),
					PrivateGroupChatMembership.computeEpochId(groupId, firstEpoch.getMemberPublicKeys()));
		}
	}

	@Test
	public void testMemberOrderDoesNotAffectEpochId() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			int groupId = createClosedGroup(repository, alice, "membership-order");

			byte[] firstEpochId = PrivateGroupChatMembership.computeEpochId(groupId,
					Arrays.asList(alice.getPublicKey(), bob.getPublicKey(), chloe.getPublicKey()));
			byte[] secondEpochId = PrivateGroupChatMembership.computeEpochId(groupId,
					Arrays.asList(chloe.getPublicKey(), alice.getPublicKey(), bob.getPublicKey()));

			assertArrayEquals(firstEpochId, secondEpochId);
		}
	}

	@Test
	public void testMembershipChangesAndRestorationAffectEpochId() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			int groupId = createClosedGroup(repository, alice, "membership-change");
			addMember(repository, groupId, bob);

			byte[] originalEpochId = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId).getEpochId();

			addMember(repository, groupId, chloe);
			byte[] expandedEpochId = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId).getEpochId();
			assertFalse(Arrays.equals(originalEpochId, expandedEpochId));

			repository.getGroupRepository().deleteMember(groupId, chloe.getAddress());
			repository.saveChanges();

			byte[] restoredEpochId = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId).getEpochId();
			assertArrayEquals(originalEpochId, restoredEpochId);
		}
	}

	@Test
	public void testGroupIdIsPartOfEpochId() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			int firstGroupId = createClosedGroup(repository, alice, "membership-first-group");
			int secondGroupId = createClosedGroup(repository, alice, "membership-second-group");
			addMember(repository, firstGroupId, bob);
			addMember(repository, secondGroupId, bob);

			byte[] firstEpochId = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, firstGroupId).getEpochId();
			byte[] secondEpochId = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, secondGroupId).getEpochId();

			assertFalse(Arrays.equals(firstEpochId, secondEpochId));
		}
	}

	@Test
	public void testInvalidGroupStateIsRejected() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			int closedGroupId = createClosedGroup(repository, alice, "membership-invalid");
			addUnknownMember(repository, closedGroupId, "QmissingPublicKey");

			assertThrows(IllegalStateException.class,
					() -> PrivateGroupChatMembership.currentClosedGroupEpoch(repository, closedGroupId));

			int openGroupId = GroupUtils.createGroup(repository, alice, "membership-open", true,
					ApprovalThreshold.ONE, 10, 40);
			assertThrows(IllegalArgumentException.class,
					() -> PrivateGroupChatMembership.currentClosedGroupEpoch(repository, openGroupId));

			int emptyGroupId = createClosedGroup(repository, alice, "membership-empty");
			repository.getGroupRepository().deleteMember(emptyGroupId, alice.getAddress());
			repository.saveChanges();
			assertThrows(IllegalStateException.class,
					() -> PrivateGroupChatMembership.currentClosedGroupEpoch(repository, emptyGroupId));

			assertThrows(IllegalArgumentException.class,
					() -> PrivateGroupChatMembership.currentClosedGroupEpoch(repository, 999999));
		}
	}

	@Test
	public void testDefensiveCopies() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			int groupId = createClosedGroup(repository, alice, "membership-copies");
			addMember(repository, groupId, bob);

			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId);

			byte[] epochId = epoch.getEpochId();
			epochId[0] ^= 1;
			assertArrayEquals(PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId).getEpochId(),
					epoch.getEpochId());

			List<byte[]> memberPublicKeys = epoch.getMemberPublicKeys();
			assertThrows(UnsupportedOperationException.class,
					() -> memberPublicKeys.add(alice.getPublicKey()));

			memberPublicKeys.get(0)[0] ^= 1;
			assertArrayEquals(PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId).getEpochId(),
					epoch.getEpochId());
		}
	}

	@Test
	public void testInvalidComputeInputsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> PrivateGroupChatMembership.computeEpochId(1, null));
		assertThrows(IllegalArgumentException.class, () -> PrivateGroupChatMembership.computeEpochId(1, List.of()));
		assertThrows(IllegalArgumentException.class,
				() -> PrivateGroupChatMembership.computeEpochId(1, List.of(new byte[31])));
	}

	private static int createClosedGroup(Repository repository, TestAccount owner, String groupName) throws DataException {
		return GroupUtils.createGroup(repository, owner, groupName, false, ApprovalThreshold.ONE, 10, 40);
	}

	private static void addMember(Repository repository, int groupId, TestAccount account) throws DataException {
		account.ensureAccount();
		addMember(repository, groupId, account.getAddress());
	}

	private static void addUnknownMember(Repository repository, int groupId, String address) throws DataException {
		repository.getAccountRepository().ensureAccount(new AccountData(address));
		addMember(repository, groupId, address);
	}

	private static void addMember(Repository repository, int groupId, String address) throws DataException {
		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
		repository.getGroupRepository().save(new GroupMemberData(groupId, address, groupData.getCreated(), groupData.getReference()));
		repository.saveChanges();
	}
}
