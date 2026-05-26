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
import org.qortium.transform.Transformer;

import java.security.GeneralSecurityException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class PrivateGroupChatKeyCacheTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		PrivateGroupChatKeyCache.getInstance().clear();
	}

	@Test
	public void testPutLocalAndExactLookup() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "key-cache-local");
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 1);
			PrivateGroupChatEnvelope announcement = PrivateGroupChatKeyAnnouncement.create(fixture.epoch,
					groupKey, fixture.alice.getPrivateKey());

			PrivateGroupChatKeyCache cache = new PrivateGroupChatKeyCache();
			PrivateGroupChatKeyCache.Entry storedEntry = cache.putLocal(fixture.epoch, announcement, groupKey);
			PrivateGroupChatKeyCache.Entry fetchedEntry = cache.get(fixture.groupId,
					fixture.epoch.getEpochId(), announcement.getKeyId());

			assertNotNull(fetchedEntry);
			assertArrayEquals(storedEntry.getGroupKey(), fetchedEntry.getGroupKey());
			assertArrayEquals(groupKey, fetchedEntry.getGroupKey());
			assertArrayEquals(announcement.toBytes(), fetchedEntry.getAnnouncementBytes());
			assertArrayEquals(announcement.getCreatorPublicKey(), fetchedEntry.getCreatorPublicKey());
			assertEquals(1, cache.size());
		}
	}

	@Test
	public void testPutFromAnnouncementUnwrapsForRecipient() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "key-cache-recipient");
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 10);
			PrivateGroupChatEnvelope announcement = PrivateGroupChatKeyAnnouncement.create(fixture.epoch,
					groupKey, fixture.alice.getPrivateKey());

			PrivateGroupChatKeyCache cache = new PrivateGroupChatKeyCache();
			PrivateGroupChatKeyCache.Entry storedEntry = cache.putFromAnnouncement(fixture.epoch,
					announcement, fixture.bob.getPrivateKey());

			assertArrayEquals(groupKey, storedEntry.getGroupKey());
			assertArrayEquals(groupKey, cache.get(fixture.groupId, fixture.epoch.getEpochId(),
					announcement.getKeyId()).getGroupKey());
		}
	}

	@Test
	public void testPutFromHistoricalAnnouncementDoesNotRequireCurrentEpoch() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "key-cache-historical");
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 15);
			PrivateGroupChatEnvelope announcement = PrivateGroupChatKeyAnnouncement.create(fixture.epoch,
					groupKey, fixture.alice.getPrivateKey());

			addMember(repository, fixture.groupId, fixture.chloe);
			PrivateGroupChatMembership.MembershipEpoch currentEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);

			PrivateGroupChatKeyCache cache = new PrivateGroupChatKeyCache();
			assertThrows(GeneralSecurityException.class,
					() -> cache.putFromAnnouncement(currentEpoch, announcement, fixture.bob.getPrivateKey()));

			PrivateGroupChatKeyCache.Entry storedEntry = cache.putFromHistoricalAnnouncement(announcement,
					fixture.bob.getPrivateKey());
			assertArrayEquals(groupKey, storedEntry.getGroupKey());
			assertArrayEquals(groupKey, cache.get(fixture.groupId, fixture.epoch.getEpochId(),
					announcement.getKeyId()).getGroupKey());

			assertThrows(GeneralSecurityException.class,
					() -> cache.putFromHistoricalAnnouncement(announcement, fixture.chloe.getPrivateKey()));
		}
	}

	@Test
	public void testDuplicateKeyRefreshesSingleEntry() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "key-cache-duplicate");
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 20);
			PrivateGroupChatEnvelope announcement = PrivateGroupChatKeyAnnouncement.create(fixture.epoch,
					groupKey, fixture.alice.getPrivateKey());

			PrivateGroupChatKeyCache cache = new PrivateGroupChatKeyCache();
			PrivateGroupChatKeyCache.Entry firstEntry = cache.putLocal(fixture.epoch, announcement, groupKey);
			PrivateGroupChatKeyCache.Entry secondEntry = cache.putLocal(fixture.epoch, announcement, groupKey);

			assertEquals(1, cache.size());
			assertEquals(firstEntry.getCreatedTimestamp(), secondEntry.getCreatedTimestamp());
			assertArrayEquals(groupKey, secondEntry.getGroupKey());
		}
	}

	@Test
	public void testMultipleKeysCanCoexistInOneEpoch() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "key-cache-multiple");
			byte[] firstGroupKey = bytes(Transformer.AES256_LENGTH, 30);
			byte[] secondGroupKey = bytes(Transformer.AES256_LENGTH, 70);
			PrivateGroupChatEnvelope firstAnnouncement = PrivateGroupChatKeyAnnouncement.create(fixture.epoch,
					firstGroupKey, fixture.alice.getPrivateKey());
			PrivateGroupChatEnvelope secondAnnouncement = PrivateGroupChatKeyAnnouncement.create(fixture.epoch,
					secondGroupKey, fixture.bob.getPrivateKey());

			PrivateGroupChatKeyCache cache = new PrivateGroupChatKeyCache();
			cache.putLocal(fixture.epoch, firstAnnouncement, firstGroupKey);
			cache.putLocal(fixture.epoch, secondAnnouncement, secondGroupKey);

			assertEquals(2, cache.size());
			assertArrayEquals(firstGroupKey, cache.get(fixture.groupId, fixture.epoch.getEpochId(),
					firstAnnouncement.getKeyId()).getGroupKey());
			assertArrayEquals(secondGroupKey, cache.get(fixture.groupId, fixture.epoch.getEpochId(),
					secondAnnouncement.getKeyId()).getGroupKey());
			assertNotNull(cache.getAny(fixture.groupId, fixture.epoch.getEpochId()));
			assertArrayEquals(secondGroupKey, cache.getAny(fixture.groupId, fixture.epoch.getEpochId()).getGroupKey());

			cache.get(fixture.groupId, fixture.epoch.getEpochId(), firstAnnouncement.getKeyId());
			assertArrayEquals(firstGroupKey, cache.getAny(fixture.groupId, fixture.epoch.getEpochId()).getGroupKey());
		}
	}

	@Test
	public void testNewestCreatedSelectionIgnoresRecentUse() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "key-cache-newest-created");
			byte[] firstGroupKey = bytes(Transformer.AES256_LENGTH, 80);
			byte[] secondGroupKey = bytes(Transformer.AES256_LENGTH, 120);
			PrivateGroupChatEnvelope firstAnnouncement = PrivateGroupChatKeyAnnouncement.create(fixture.epoch,
					firstGroupKey, fixture.alice.getPrivateKey());
			PrivateGroupChatEnvelope secondAnnouncement = PrivateGroupChatKeyAnnouncement.create(fixture.epoch,
					secondGroupKey, fixture.bob.getPrivateKey());

			PrivateGroupChatKeyCache cache = new PrivateGroupChatKeyCache();
			cache.putLocal(fixture.epoch, firstAnnouncement, firstGroupKey);
			cache.putLocal(fixture.epoch, secondAnnouncement, secondGroupKey);

			cache.get(fixture.groupId, fixture.epoch.getEpochId(), firstAnnouncement.getKeyId());
			assertArrayEquals(firstGroupKey, cache.getAny(fixture.groupId, fixture.epoch.getEpochId()).getGroupKey());
			assertArrayEquals(secondGroupKey, cache.getNewestCreated(fixture.groupId,
					fixture.epoch.getEpochId()).getGroupKey());
		}
	}

	@Test
	public void testInvalidAnnouncementsAndKeysAreRejected() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "key-cache-invalid");
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 110);
			PrivateGroupChatEnvelope announcement = PrivateGroupChatKeyAnnouncement.create(fixture.epoch,
					groupKey, fixture.alice.getPrivateKey());
			PrivateGroupChatKeyCache cache = new PrivateGroupChatKeyCache();

			assertThrows(GeneralSecurityException.class,
					() -> cache.putLocal(fixture.epoch, announcement, bytes(Transformer.AES256_LENGTH, 120)));

			byte[] tamperedSignature = announcement.getSignature();
			tamperedSignature[0] ^= 1;
			PrivateGroupChatEnvelope invalidAnnouncement = PrivateGroupChatEnvelope.keyAnnouncement(
					announcement.getGroupId(), announcement.getEpochId(), announcement.getKeyId(),
					announcement.getCreatorPublicKey(), announcement.getKeyWrappers(), tamperedSignature);
			assertThrows(GeneralSecurityException.class,
					() -> cache.putLocal(fixture.epoch, invalidAnnouncement, groupKey));

			assertThrows(GeneralSecurityException.class,
					() -> cache.putFromAnnouncement(fixture.epoch, announcement, fixture.dilbert.getPrivateKey()));

			addMember(repository, fixture.groupId, fixture.chloe);
			PrivateGroupChatMembership.MembershipEpoch expandedEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);
			assertThrows(GeneralSecurityException.class,
					() -> cache.putLocal(expandedEpoch, announcement, groupKey));
		}
	}

	@Test
	public void testDefensiveCopies() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "key-cache-copies");
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 150);
			PrivateGroupChatEnvelope announcement = PrivateGroupChatKeyAnnouncement.create(fixture.epoch,
					groupKey, fixture.alice.getPrivateKey());

			PrivateGroupChatKeyCache cache = new PrivateGroupChatKeyCache();
			PrivateGroupChatKeyCache.Entry entry = cache.putLocal(fixture.epoch, announcement, groupKey);

			groupKey[0] ^= 1;
			assertArrayEquals(bytes(Transformer.AES256_LENGTH, 150), cache.get(fixture.groupId,
					fixture.epoch.getEpochId(), announcement.getKeyId()).getGroupKey());

			byte[] cachedGroupKey = entry.getGroupKey();
			cachedGroupKey[0] ^= 1;
			assertArrayEquals(bytes(Transformer.AES256_LENGTH, 150), cache.get(fixture.groupId,
					fixture.epoch.getEpochId(), announcement.getKeyId()).getGroupKey());

			byte[] cachedAnnouncementBytes = entry.getAnnouncementBytes();
			cachedAnnouncementBytes[0] ^= 1;
			assertArrayEquals(announcement.toBytes(), cache.get(fixture.groupId,
					fixture.epoch.getEpochId(), announcement.getKeyId()).getAnnouncementBytes());

			byte[] cachedCreatorPublicKey = entry.getCreatorPublicKey();
			cachedCreatorPublicKey[0] ^= 1;
			assertArrayEquals(announcement.getCreatorPublicKey(), cache.get(fixture.groupId,
					fixture.epoch.getEpochId(), announcement.getKeyId()).getCreatorPublicKey());
		}
	}

	@Test
	public void testClearRemovesEntries() throws DataException, GeneralSecurityException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "key-cache-clear");
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 190);
			PrivateGroupChatEnvelope announcement = PrivateGroupChatKeyAnnouncement.create(fixture.epoch,
					groupKey, fixture.alice.getPrivateKey());

			PrivateGroupChatKeyCache cache = new PrivateGroupChatKeyCache();
			cache.putLocal(fixture.epoch, announcement, groupKey);
			assertEquals(1, cache.size());

			cache.clear();
			assertEquals(0, cache.size());
			assertNull(cache.get(fixture.groupId, fixture.epoch.getEpochId(), announcement.getKeyId()));
			assertNull(cache.getAny(fixture.groupId, fixture.epoch.getEpochId()));
		}
	}

	@Test
	public void testInvalidLookupInputsAreRejected() {
		PrivateGroupChatKeyCache cache = new PrivateGroupChatKeyCache();

		assertThrows(IllegalArgumentException.class,
				() -> cache.get(1, new byte[PrivateGroupChatEnvelope.EPOCH_ID_LENGTH - 1],
						new byte[PrivateGroupChatEnvelope.KEY_ID_LENGTH]));
		assertThrows(IllegalArgumentException.class,
				() -> cache.get(1, new byte[PrivateGroupChatEnvelope.EPOCH_ID_LENGTH],
						new byte[PrivateGroupChatEnvelope.KEY_ID_LENGTH - 1]));
		assertThrows(IllegalArgumentException.class,
				() -> cache.getAny(1, new byte[PrivateGroupChatEnvelope.EPOCH_ID_LENGTH - 1]));
	}

	private static Fixture createFixture(Repository repository, String groupName) throws DataException {
		TestAccount alice = Common.getTestAccount(repository, "alice");
		TestAccount bob = Common.getTestAccount(repository, "bob");
		TestAccount chloe = Common.getTestAccount(repository, "chloe");
		TestAccount dilbert = Common.getTestAccount(repository, "dilbert");

		int groupId = createClosedGroup(repository, alice, groupName);
		addMember(repository, groupId, bob);

		return new Fixture(alice, bob, chloe, dilbert, groupId,
				PrivateGroupChatMembership.currentClosedGroupEpoch(repository, groupId));
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

	private static class Fixture {
		private final TestAccount alice;
		private final TestAccount bob;
		private final TestAccount chloe;
		private final TestAccount dilbert;
		private final int groupId;
		private final PrivateGroupChatMembership.MembershipEpoch epoch;

		private Fixture(TestAccount alice, TestAccount bob, TestAccount chloe, TestAccount dilbert,
				int groupId, PrivateGroupChatMembership.MembershipEpoch epoch) {
			this.alice = alice;
			this.bob = bob;
			this.chloe = chloe;
			this.dilbert = dilbert;
			this.groupId = groupId;
			this.epoch = epoch;
		}
	}
}
