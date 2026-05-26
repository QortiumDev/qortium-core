package org.qortium.chat.crypto;

import org.junit.Before;
import org.junit.Test;
import org.qortium.crypto.Crypto;
import org.qortium.data.group.GroupData;
import org.qortium.data.group.GroupMemberData;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestAccount;
import org.qortium.transform.Transformer;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PrivateGroupChatKeyAnnouncementTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testCreateVerifyAndUnwrapForEveryMember() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			int groupId = createClosedGroup(repository, alice, "key-announcement-basic");
			addMember(repository, groupId, bob);
			addMember(repository, groupId, chloe);
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId);
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 1);

			PrivateGroupChatEnvelope envelope = PrivateGroupChatKeyAnnouncement.create(epoch, groupKey, alice.getPrivateKey());

			assertEquals(PrivateGroupChatEnvelope.Type.KEY_ANNOUNCEMENT, envelope.getType());
			assertEquals(groupId, envelope.getGroupId());
			assertArrayEquals(epoch.getEpochId(), envelope.getEpochId());
			assertArrayEquals(alice.getPublicKey(), envelope.getCreatorPublicKey());
			assertArrayEquals(PrivateGroupChatCrypto.computeKeyId(groupId, epoch.getEpochId(), groupKey), envelope.getKeyId());
			assertEquals(epoch.getMemberPublicKeys().size(), envelope.getKeyWrappers().size());
			assertTrue(PrivateGroupChatKeyAnnouncement.isValid(epoch, envelope));

			assertArrayEquals(groupKey, PrivateGroupChatKeyAnnouncement.unwrapForRecipient(epoch, envelope, alice.getPrivateKey()));
			assertArrayEquals(groupKey, PrivateGroupChatKeyAnnouncement.unwrapForRecipient(epoch, envelope, bob.getPrivateKey()));
			assertArrayEquals(groupKey, PrivateGroupChatKeyAnnouncement.unwrapForRecipient(epoch, envelope, chloe.getPrivateKey()));
		}
	}

	@Test
	public void testNonMemberCannotAnnounceOrUnwrap() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			int groupId = createClosedGroup(repository, alice, "key-announcement-nonmember");
			addMember(repository, groupId, bob);
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId);
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 10);

			assertThrows(GeneralSecurityException.class,
					() -> PrivateGroupChatKeyAnnouncement.create(epoch, groupKey, chloe.getPrivateKey()));

			PrivateGroupChatEnvelope envelope = PrivateGroupChatKeyAnnouncement.create(epoch, groupKey, alice.getPrivateKey());
			assertThrows(GeneralSecurityException.class,
					() -> PrivateGroupChatKeyAnnouncement.unwrapForRecipient(epoch, envelope, chloe.getPrivateKey()));
		}
	}

	@Test
	public void testWrapperOrderDoesNotAffectSignatureVerification() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			int groupId = createClosedGroup(repository, alice, "key-announcement-order");
			addMember(repository, groupId, bob);
			addMember(repository, groupId, chloe);
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId);

			PrivateGroupChatEnvelope envelope = PrivateGroupChatKeyAnnouncement.create(epoch,
					bytes(Transformer.AES256_LENGTH, 20), alice.getPrivateKey());
			List<PrivateGroupChatEnvelope.KeyWrapper> reversedWrappers = new ArrayList<>(envelope.getKeyWrappers());
			Collections.reverse(reversedWrappers);

			PrivateGroupChatEnvelope reorderedEnvelope = PrivateGroupChatEnvelope.keyAnnouncement(envelope.getGroupId(),
					envelope.getEpochId(), envelope.getKeyId(), envelope.getCreatorPublicKey(), reversedWrappers,
					envelope.getSignature());

			assertTrue(PrivateGroupChatKeyAnnouncement.isValid(epoch, reorderedEnvelope));
		}
	}

	@Test
	public void testAnnouncementContextMustMatchCurrentEpoch() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			int groupId = createClosedGroup(repository, alice, "key-announcement-context");
			addMember(repository, groupId, bob);
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId);
			PrivateGroupChatEnvelope envelope = PrivateGroupChatKeyAnnouncement.create(epoch,
					bytes(Transformer.AES256_LENGTH, 30), alice.getPrivateKey());

			PrivateGroupChatEnvelope wrongGroupId = PrivateGroupChatEnvelope.keyAnnouncement(groupId + 1,
					envelope.getEpochId(), envelope.getKeyId(), envelope.getCreatorPublicKey(),
					envelope.getKeyWrappers(), envelope.getSignature());
			assertFalse(PrivateGroupChatKeyAnnouncement.isValid(epoch, wrongGroupId));

			byte[] wrongEpochId = envelope.getEpochId();
			wrongEpochId[0] ^= 1;
			PrivateGroupChatEnvelope wrongEpoch = PrivateGroupChatEnvelope.keyAnnouncement(groupId,
					wrongEpochId, envelope.getKeyId(), envelope.getCreatorPublicKey(), envelope.getKeyWrappers(),
					envelope.getSignature());
			assertFalse(PrivateGroupChatKeyAnnouncement.isValid(epoch, wrongEpoch));

			addMember(repository, groupId, chloe);
			PrivateGroupChatMembership.MembershipEpoch expandedEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId);
			assertFalse(PrivateGroupChatKeyAnnouncement.isValid(expandedEpoch, envelope));
		}
	}

	@Test
	public void testHistoricalUnwrapAcceptsSignedOldEpochAfterMembershipChanges() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			int groupId = createClosedGroup(repository, alice, "key-announcement-historical");
			addMember(repository, groupId, bob);
			PrivateGroupChatMembership.MembershipEpoch oldEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId);
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 35);
			PrivateGroupChatEnvelope envelope = PrivateGroupChatKeyAnnouncement.create(oldEpoch,
					groupKey, alice.getPrivateKey());

			addMember(repository, groupId, chloe);
			PrivateGroupChatMembership.MembershipEpoch currentEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					groupId);
			assertFalse(PrivateGroupChatKeyAnnouncement.isValid(currentEpoch, envelope));
			assertThrows(GeneralSecurityException.class,
					() -> PrivateGroupChatKeyAnnouncement.unwrapForRecipient(currentEpoch, envelope, bob.getPrivateKey()));

			assertArrayEquals(groupKey,
					PrivateGroupChatKeyAnnouncement.unwrapHistoricalForRecipient(envelope, bob.getPrivateKey()));
			assertThrows(GeneralSecurityException.class,
					() -> PrivateGroupChatKeyAnnouncement.unwrapHistoricalForRecipient(envelope, chloe.getPrivateKey()));

			byte[] tamperedSignature = envelope.getSignature();
			tamperedSignature[0] ^= 1;
			PrivateGroupChatEnvelope invalidSignature = PrivateGroupChatEnvelope.keyAnnouncement(
					envelope.getGroupId(), envelope.getEpochId(), envelope.getKeyId(), envelope.getCreatorPublicKey(),
					envelope.getKeyWrappers(), tamperedSignature);
			assertThrows(GeneralSecurityException.class,
					() -> PrivateGroupChatKeyAnnouncement.unwrapHistoricalForRecipient(invalidSignature,
							bob.getPrivateKey()));
		}
	}

	@Test
	public void testWrapperCoverageMustMatchCurrentMembers() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

			int groupId = createClosedGroup(repository, alice, "key-announcement-wrappers");
			addMember(repository, groupId, bob);
			addMember(repository, groupId, chloe);
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId);
			PrivateGroupChatEnvelope envelope = PrivateGroupChatKeyAnnouncement.create(epoch,
					bytes(Transformer.AES256_LENGTH, 40), alice.getPrivateKey());

			List<PrivateGroupChatEnvelope.KeyWrapper> missingWrappers = new ArrayList<>(envelope.getKeyWrappers());
			missingWrappers.remove(0);
			assertFalse(PrivateGroupChatKeyAnnouncement.isValid(epoch, withWrappers(envelope, missingWrappers)));

			List<PrivateGroupChatEnvelope.KeyWrapper> duplicateWrappers = new ArrayList<>(envelope.getKeyWrappers());
			duplicateWrappers.set(1, duplicateWrappers.get(0));
			assertFalse(PrivateGroupChatKeyAnnouncement.isValid(epoch, withWrappers(envelope, duplicateWrappers)));

			List<PrivateGroupChatEnvelope.KeyWrapper> outsideWrappers = new ArrayList<>(envelope.getKeyWrappers());
			outsideWrappers.add(new PrivateGroupChatEnvelope.KeyWrapper(dilbert.getPublicKey(),
					outsideWrappers.get(0).getWrappedKey()));
			assertFalse(PrivateGroupChatKeyAnnouncement.isValid(epoch, withWrappers(envelope, outsideWrappers)));
		}
	}

	@Test
	public void testSignatureAndWrappedKeyTamperingFail() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			int groupId = createClosedGroup(repository, alice, "key-announcement-tamper");
			addMember(repository, groupId, bob);
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId);
			PrivateGroupChatEnvelope envelope = PrivateGroupChatKeyAnnouncement.create(epoch,
					bytes(Transformer.AES256_LENGTH, 50), alice.getPrivateKey());

			byte[] tamperedSignature = envelope.getSignature();
			tamperedSignature[0] ^= 1;
			PrivateGroupChatEnvelope invalidSignature = PrivateGroupChatEnvelope.keyAnnouncement(envelope.getGroupId(),
					envelope.getEpochId(), envelope.getKeyId(), envelope.getCreatorPublicKey(),
					envelope.getKeyWrappers(), tamperedSignature);
			assertFalse(PrivateGroupChatKeyAnnouncement.isValid(epoch, invalidSignature));

			List<PrivateGroupChatEnvelope.KeyWrapper> tamperedWrappers = new ArrayList<>(envelope.getKeyWrappers());
			PrivateGroupChatEnvelope.KeyWrapper firstWrapper = tamperedWrappers.get(0);
			byte[] tamperedWrappedKey = firstWrapper.getWrappedKey();
			tamperedWrappedKey[tamperedWrappedKey.length - 1] ^= 1;
			tamperedWrappers.set(0, new PrivateGroupChatEnvelope.KeyWrapper(firstWrapper.getRecipientPublicKey(),
					tamperedWrappedKey));
			PrivateGroupChatEnvelope invalidWrappedKey = withWrappers(envelope, tamperedWrappers);
			assertFalse(PrivateGroupChatKeyAnnouncement.isValid(epoch, invalidWrappedKey));
			assertThrows(GeneralSecurityException.class,
					() -> PrivateGroupChatKeyAnnouncement.unwrapForRecipient(epoch, invalidWrappedKey, alice.getPrivateKey()));
		}
	}

	@Test
	public void testNonAnnouncementEnvelopeIsInvalid() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			int groupId = createClosedGroup(repository, alice, "key-announcement-type");
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId);

			PrivateGroupChatEnvelope messageEnvelope = PrivateGroupChatEnvelope.message(groupId, epoch.getEpochId(),
					Crypto.digest(bytes(16, 60)), bytes(PrivateGroupChatEnvelope.NONCE_LENGTH, 61), bytes(16, 62));

			assertFalse(PrivateGroupChatKeyAnnouncement.isValid(epoch, messageEnvelope));
		}
	}

	@Test
	public void testInvalidCreateInputsAreRejected() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			int groupId = createClosedGroup(repository, alice, "key-announcement-inputs");
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId);

			assertThrows(IllegalArgumentException.class,
					() -> PrivateGroupChatKeyAnnouncement.create(null, bytes(Transformer.AES256_LENGTH, 70), alice.getPrivateKey()));
			assertThrows(IllegalArgumentException.class,
					() -> PrivateGroupChatKeyAnnouncement.create(epoch, new byte[31], alice.getPrivateKey()));
			assertThrows(IllegalArgumentException.class,
					() -> PrivateGroupChatKeyAnnouncement.create(epoch, bytes(Transformer.AES256_LENGTH, 71), new byte[31]));
		}
	}

	private static PrivateGroupChatEnvelope withWrappers(PrivateGroupChatEnvelope envelope,
			List<PrivateGroupChatEnvelope.KeyWrapper> keyWrappers) {
		return PrivateGroupChatEnvelope.keyAnnouncement(envelope.getGroupId(), envelope.getEpochId(),
				envelope.getKeyId(), envelope.getCreatorPublicKey(), keyWrappers, envelope.getSignature());
	}

	private static int createClosedGroup(Repository repository, TestAccount owner, String groupName) throws DataException {
		return GroupUtils.createGroup(repository, owner, groupName, false, ApprovalThreshold.ONE, 10, 40);
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
}
