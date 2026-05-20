package org.qortal.chat;

import org.junit.Before;
import org.junit.Test;
import org.qortal.chat.crypto.PrivateGroupChatEnvelope;
import org.qortal.chat.crypto.PrivateGroupChatKeyCache;
import org.qortal.data.group.GroupData;
import org.qortal.data.group.GroupMemberData;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.test.common.GroupUtils;
import org.qortal.test.common.TestAccount;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PrivateGroupChatServiceTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		PrivateGroupChatKeyCache.getInstance().clear();
	}

	@Test
	public void testFirstSendStoresKeyAnnouncementAndMessage() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-first-send");

			PrivateGroupChatService.SendResult result = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("secret"), true, null);

			assertNotNull(result.getKeyAnnouncementSignature());
			assertNotNull(result.getMessageSignature());
			assertNotNull(result.getEpochId());
			assertNotNull(result.getKeyId());

			ChatTransactionData keyAnnouncementData = repository.getChatStoreRepository().fromSignature(
					result.getKeyAnnouncementSignature());
			ChatTransactionData messageData = repository.getChatStoreRepository().fromSignature(result.getMessageSignature());
			assertNotNull(keyAnnouncementData);
			assertNotNull(messageData);
			assertEquals(fixture.groupId, keyAnnouncementData.getTxGroupId());
			assertEquals(fixture.groupId, messageData.getTxGroupId());
			assertTrue(keyAnnouncementData.getIsEncrypted());
			assertTrue(messageData.getIsEncrypted());

			assertEquals(PrivateGroupChatEnvelope.Type.KEY_ANNOUNCEMENT,
					PrivateGroupChatEnvelope.fromBytes(keyAnnouncementData.getData()).getType());
			assertEquals(PrivateGroupChatEnvelope.Type.MESSAGE,
					PrivateGroupChatEnvelope.fromBytes(messageData.getData()).getType());
		}
	}

	@Test
	public void testSecondSendReusesCachedKey() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-reuse");

			PrivateGroupChatService.SendResult firstResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("first"), true, null);
			PrivateGroupChatService.SendResult secondResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("second"), true, firstResult.getMessageSignature());

			assertNotNull(firstResult.getKeyAnnouncementSignature());
			assertNull(secondResult.getKeyAnnouncementSignature());
			assertArrayEquals(firstResult.getEpochId(), secondResult.getEpochId());
			assertArrayEquals(firstResult.getKeyId(), secondResult.getKeyId());

			ChatTransactionData secondMessageData = repository.getChatStoreRepository().fromSignature(
					secondResult.getMessageSignature());
			assertArrayEquals(firstResult.getMessageSignature(), secondMessageData.getChatReference());
		}
	}

	@Test
	public void testCurrentMembersCanDecryptCachedMessage() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-decrypt");
			byte[] plaintext = bytes("member secret");

			PrivateGroupChatService.SendResult sendResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, plaintext, true, null);
			PrivateGroupChatService.DecryptResult decryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.bob.getPrivateKey(), sendResult.getMessageSignature());

			assertArrayEquals(plaintext, decryptResult.getData());
			assertTrue(decryptResult.isText());
			assertEquals(fixture.groupId, decryptResult.getGroupId());
			assertArrayEquals(sendResult.getEpochId(), decryptResult.getEpochId());
			assertArrayEquals(sendResult.getKeyId(), decryptResult.getKeyId());
		}
	}

	@Test
	public void testNonMemberCannotSendOrDecrypt() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-nonmember");

			assertThrows(GeneralSecurityException.class,
					() -> PrivateGroupChatService.getInstance().send(repository, fixture.chloe.getPrivateKey(),
							fixture.groupId, bytes("blocked"), true, null));

			PrivateGroupChatService.SendResult sendResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("secret"), true, null);

			assertThrows(GeneralSecurityException.class,
					() -> PrivateGroupChatService.getInstance().decrypt(repository, fixture.chloe.getPrivateKey(),
							sendResult.getMessageSignature()));
		}
	}

	@Test
	public void testOpenGroupIsRejected() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			int groupId = GroupUtils.createGroup(repository, alice, "private-service-open", true,
					ApprovalThreshold.ONE, 10, 40);

			assertThrows(IllegalArgumentException.class,
					() -> PrivateGroupChatService.getInstance().send(repository, alice.getPrivateKey(), groupId,
							bytes("not private"), true, null));
		}
	}

	@Test
	public void testDecryptRequiresCachedKey() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-missing-key");
			PrivateGroupChatService.SendResult sendResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("secret"), true, null);

			PrivateGroupChatKeyCache.getInstance().clear();

			assertThrows(PrivateGroupChatService.PrivateGroupChatException.class,
					() -> PrivateGroupChatService.getInstance().decrypt(repository, fixture.bob.getPrivateKey(),
							sendResult.getMessageSignature()));
		}
	}

	@Test
	public void testDefensiveCopies() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-copies");
			PrivateGroupChatService.SendResult sendResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("secret"), true, null);

			byte[] epochId = sendResult.getEpochId();
			byte[] keyId = sendResult.getKeyId();
			byte[] expectedEpochId = epochId.clone();
			byte[] expectedKeyId = keyId.clone();
			epochId[0] ^= 1;
			keyId[0] ^= 1;

			PrivateGroupChatService.DecryptResult decryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.bob.getPrivateKey(), sendResult.getMessageSignature());

			assertArrayEquals(expectedEpochId, decryptResult.getEpochId());
			assertArrayEquals(expectedKeyId, decryptResult.getKeyId());
			assertArrayEquals(bytes("secret"), decryptResult.getData());
		}
	}

	private static Fixture createFixture(Repository repository, String groupName) throws DataException {
		TestAccount alice = Common.getTestAccount(repository, "alice");
		TestAccount bob = Common.getTestAccount(repository, "bob");
		TestAccount chloe = Common.getTestAccount(repository, "chloe");

		int groupId = GroupUtils.createGroup(repository, alice, groupName, false, ApprovalThreshold.ONE, 10, 40);
		addMember(repository, groupId, bob);

		return new Fixture(alice, bob, chloe, groupId);
	}

	private static void addMember(Repository repository, int groupId, TestAccount account) throws DataException {
		account.ensureAccount();

		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
		repository.getGroupRepository().save(new GroupMemberData(groupId, account.getAddress(),
				groupData.getCreated(), groupData.getReference()));
		repository.saveChanges();
	}

	private static byte[] bytes(String message) {
		return message.getBytes(StandardCharsets.UTF_8);
	}

	private static class Fixture {
		private final TestAccount alice;
		private final TestAccount bob;
		private final TestAccount chloe;
		private final int groupId;

		private Fixture(TestAccount alice, TestAccount bob, TestAccount chloe, int groupId) {
			this.alice = alice;
			this.bob = bob;
			this.chloe = chloe;
			this.groupId = groupId;
		}
	}

}
