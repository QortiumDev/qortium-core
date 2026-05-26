package org.qortium.chat.crypto;

import org.junit.Before;
import org.junit.Test;
import org.qortium.data.group.GroupData;
import org.qortium.data.group.GroupMemberData;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestAccount;

import java.security.GeneralSecurityException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PrivateGroupChatRotationRequestTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testCreateAndVerify() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "rotation-request-basic");

			PrivateGroupChatEnvelope envelope = PrivateGroupChatRotationRequest.create(fixture.epoch,
					fixture.alice.getPrivateKey());

			assertEquals(PrivateGroupChatEnvelope.Type.ROTATION_REQUEST, envelope.getType());
			assertEquals(fixture.groupId, envelope.getGroupId());
			assertArrayEquals(fixture.epoch.getEpochId(), envelope.getEpochId());
			assertArrayEquals(fixture.alice.getPublicKey(), envelope.getRequesterPublicKey());
			assertTrue(PrivateGroupChatRotationRequest.isValid(fixture.epoch, envelope));
		}
	}

	@Test
	public void testNonMemberCannotCreateOrValidate() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "rotation-request-nonmember");

			assertThrows(GeneralSecurityException.class,
					() -> PrivateGroupChatRotationRequest.create(fixture.epoch, fixture.chloe.getPrivateKey()));

			PrivateGroupChatEnvelope nonMemberEnvelope = PrivateGroupChatEnvelope.rotationRequest(fixture.groupId,
					fixture.epoch.getEpochId(), fixture.chloe.getPublicKey(),
					bytes(PrivateGroupChatEnvelope.SIGNATURE_LENGTH, 10));
			assertFalse(PrivateGroupChatRotationRequest.isValid(fixture.epoch, nonMemberEnvelope));
		}
	}

	@Test
	public void testContextAndSignatureMustMatch() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "rotation-request-context");
			PrivateGroupChatEnvelope envelope = PrivateGroupChatRotationRequest.create(fixture.epoch,
					fixture.alice.getPrivateKey());

			PrivateGroupChatEnvelope wrongGroupId = PrivateGroupChatEnvelope.rotationRequest(fixture.groupId + 1,
					envelope.getEpochId(), envelope.getRequesterPublicKey(), envelope.getSignature());
			assertFalse(PrivateGroupChatRotationRequest.isValid(fixture.epoch, wrongGroupId));

			byte[] wrongEpochId = envelope.getEpochId();
			wrongEpochId[0] ^= 1;
			PrivateGroupChatEnvelope wrongEpoch = PrivateGroupChatEnvelope.rotationRequest(fixture.groupId,
					wrongEpochId, envelope.getRequesterPublicKey(), envelope.getSignature());
			assertFalse(PrivateGroupChatRotationRequest.isValid(fixture.epoch, wrongEpoch));

			byte[] tamperedSignature = envelope.getSignature();
			tamperedSignature[0] ^= 1;
			PrivateGroupChatEnvelope invalidSignature = PrivateGroupChatEnvelope.rotationRequest(fixture.groupId,
					envelope.getEpochId(), envelope.getRequesterPublicKey(), tamperedSignature);
			assertFalse(PrivateGroupChatRotationRequest.isValid(fixture.epoch, invalidSignature));
		}
	}

	private static Fixture createFixture(Repository repository, String groupName) throws DataException {
		TestAccount alice = Common.getTestAccount(repository, "alice");
		TestAccount bob = Common.getTestAccount(repository, "bob");
		TestAccount chloe = Common.getTestAccount(repository, "chloe");

		int groupId = GroupUtils.createGroup(repository, alice, groupName, false, ApprovalThreshold.ONE, 10, 40);
		addMember(repository, groupId, bob);

		return new Fixture(alice, bob, chloe, groupId,
				PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId));
	}

	private static void addMember(Repository repository, int groupId, TestAccount account) throws DataException {
		account.ensureAccount();

		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
		repository.getGroupRepository().save(new GroupMemberData(groupId, account.getAddress(),
				groupData.getCreated(), groupData.getReference()));
		repository.saveChanges();
	}

	private static byte[] bytes(int length, int seed) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; ++i)
			bytes[i] = (byte) (seed + i);

		return bytes;
	}

	private static class Fixture {
		private final TestAccount alice;
		private final TestAccount bob;
		private final TestAccount chloe;
		private final int groupId;
		private final PrivateGroupChatMembership.MembershipEpoch epoch;

		private Fixture(TestAccount alice, TestAccount bob, TestAccount chloe, int groupId,
				PrivateGroupChatMembership.MembershipEpoch epoch) {
			this.alice = alice;
			this.bob = bob;
			this.chloe = chloe;
			this.groupId = groupId;
			this.epoch = epoch;
		}
	}
}
