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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PrivateGroupChatKeyRequestTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testCreateVerifyWithSpecificKeyId() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "key-request-specific");
			byte[] keyId = bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 1);

			PrivateGroupChatEnvelope envelope = PrivateGroupChatKeyRequest.create(fixture.epoch,
					fixture.bob.getPrivateKey(), keyId);

			assertEquals(PrivateGroupChatEnvelope.Type.KEY_REQUEST, envelope.getType());
			assertEquals(fixture.groupId, envelope.getGroupId());
			assertArrayEquals(fixture.epoch.getEpochId(), envelope.getEpochId());
			assertArrayEquals(fixture.bob.getPublicKey(), envelope.getRequesterPublicKey());
			assertTrue(envelope.hasRequestedKeyId());
			assertArrayEquals(keyId, envelope.getKeyId());
			assertTrue(PrivateGroupChatKeyRequest.isValid(fixture.epoch, envelope));
		}
	}

	@Test
	public void testCreateVerifyWithoutSpecificKeyId() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "key-request-any");

			PrivateGroupChatEnvelope envelope = PrivateGroupChatKeyRequest.create(fixture.epoch,
					fixture.bob.getPrivateKey(), null);

			assertEquals(PrivateGroupChatEnvelope.Type.KEY_REQUEST, envelope.getType());
			assertFalse(envelope.hasRequestedKeyId());
			assertNull(envelope.getKeyId());
			assertTrue(PrivateGroupChatKeyRequest.isValid(fixture.epoch, envelope));
		}
	}

	@Test
	public void testNonMemberCannotCreateOrValidate() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "key-request-nonmember");

			assertThrows(GeneralSecurityException.class,
					() -> PrivateGroupChatKeyRequest.create(fixture.epoch, fixture.chloe.getPrivateKey(),
							bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 10)));

			PrivateGroupChatEnvelope nonMemberEnvelope = PrivateGroupChatEnvelope.keyRequest(fixture.groupId,
					fixture.epoch.getEpochId(), fixture.chloe.getPublicKey(),
					bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 11),
					bytes(PrivateGroupChatEnvelope.SIGNATURE_LENGTH, 12));
			assertFalse(PrivateGroupChatKeyRequest.isValid(fixture.epoch, nonMemberEnvelope));
		}
	}

	@Test
	public void testContextAndSignatureMustMatch() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "key-request-context");
			byte[] keyId = bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 20);
			PrivateGroupChatEnvelope envelope = PrivateGroupChatKeyRequest.create(fixture.epoch,
					fixture.bob.getPrivateKey(), keyId);

			PrivateGroupChatEnvelope wrongGroupId = PrivateGroupChatEnvelope.keyRequest(fixture.groupId + 1,
					envelope.getEpochId(), envelope.getRequesterPublicKey(), envelope.getKeyId(),
					envelope.getSignature());
			assertFalse(PrivateGroupChatKeyRequest.isValid(fixture.epoch, wrongGroupId));

			byte[] wrongEpochId = envelope.getEpochId();
			wrongEpochId[0] ^= 1;
			PrivateGroupChatEnvelope wrongEpoch = PrivateGroupChatEnvelope.keyRequest(fixture.groupId,
					wrongEpochId, envelope.getRequesterPublicKey(), envelope.getKeyId(), envelope.getSignature());
			assertFalse(PrivateGroupChatKeyRequest.isValid(fixture.epoch, wrongEpoch));

			byte[] tamperedSignature = envelope.getSignature();
			tamperedSignature[0] ^= 1;
			PrivateGroupChatEnvelope invalidSignature = PrivateGroupChatEnvelope.keyRequest(fixture.groupId,
					envelope.getEpochId(), envelope.getRequesterPublicKey(), envelope.getKeyId(), tamperedSignature);
			assertFalse(PrivateGroupChatKeyRequest.isValid(fixture.epoch, invalidSignature));

			byte[] tamperedKeyId = envelope.getKeyId();
			tamperedKeyId[0] ^= 1;
			PrivateGroupChatEnvelope invalidKeyId = PrivateGroupChatEnvelope.keyRequest(fixture.groupId,
					envelope.getEpochId(), envelope.getRequesterPublicKey(), tamperedKeyId, envelope.getSignature());
			assertFalse(PrivateGroupChatKeyRequest.isValid(fixture.epoch, invalidKeyId));
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
