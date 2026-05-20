package org.qortal.chat;

import org.junit.Before;
import org.junit.Test;
import org.qortal.chat.crypto.PrivateGroupChatCrypto;
import org.qortal.chat.crypto.PrivateGroupChatEnvelope;
import org.qortal.chat.crypto.PrivateGroupChatKeyAnnouncement;
import org.qortal.chat.crypto.PrivateGroupChatKeyCache;
import org.qortal.chat.crypto.PrivateGroupChatMembership;
import org.qortal.data.group.GroupData;
import org.qortal.data.group.GroupMemberData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.test.common.GroupUtils;
import org.qortal.test.common.TestAccount;
import org.qortal.transaction.ChatTransaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.Transformer;
import org.qortal.utils.NTP;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

			assertThrows(PrivateGroupChatService.PrivateGroupChatException.class,
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
	public void testDecryptRehydratesCachedKeyFromStoredAnnouncement() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-missing-key");
			byte[] plaintext = bytes("secret");
			PrivateGroupChatService.SendResult sendResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, plaintext, true, null);

			PrivateGroupChatKeyCache.getInstance().clear();

			PrivateGroupChatService.DecryptResult decryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.bob.getPrivateKey(), sendResult.getMessageSignature());

			assertArrayEquals(plaintext, decryptResult.getData());
			assertArrayEquals(sendResult.getKeyId(), decryptResult.getKeyId());
			assertNotNull(PrivateGroupChatKeyCache.getInstance().get(fixture.groupId,
					sendResult.getEpochId(), sendResult.getKeyId()));
		}
	}

	@Test
	public void testDecryptStillFailsWithoutStoredAnnouncement() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-no-announcement");
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 10);
			byte[] keyId = PrivateGroupChatCrypto.computeKeyId(fixture.groupId, epoch.getEpochId(), groupKey);
			byte[] nonce = PrivateGroupChatCrypto.generateNonce();
			byte[] ciphertext = PrivateGroupChatCrypto.encryptMessage(groupKey, fixture.groupId, epoch.getEpochId(),
					keyId, nonce, bytes("secret"));
			PrivateGroupChatEnvelope messageEnvelope = PrivateGroupChatEnvelope.message(fixture.groupId,
					epoch.getEpochId(), keyId, nonce, ciphertext);
			ChatTransactionData messageData = signedChat(repository, fixture.alice, fixture.groupId,
					messageEnvelope.toBytes(), true, true, now());

			assertEquals(ValidationResult.OK, ChatService.getInstance().validateAndStore(repository, messageData));
			PrivateGroupChatKeyCache.getInstance().clear();

			assertThrows(PrivateGroupChatService.PrivateGroupChatException.class,
					() -> PrivateGroupChatService.getInstance().decrypt(repository, fixture.bob.getPrivateKey(),
							messageData.getSignature()));
		}
	}

	@Test
	public void testHistoricalMemberCanDecryptOldMessageAfterMembershipChange() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-historical-decrypt");
			byte[] plaintext = bytes("old epoch secret");

			PrivateGroupChatService.SendResult oldResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, plaintext, true, null);
			PrivateGroupChatKeyCache.getInstance().clear();

			addMember(repository, fixture.groupId, fixture.chloe);
			PrivateGroupChatMembership.MembershipEpoch currentEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);
			assertFalse(Arrays.equals(oldResult.getEpochId(), currentEpoch.getEpochId()));

			PrivateGroupChatService.DecryptResult decryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.bob.getPrivateKey(), oldResult.getMessageSignature());
			assertArrayEquals(plaintext, decryptResult.getData());
			assertArrayEquals(oldResult.getEpochId(), decryptResult.getEpochId());
			assertArrayEquals(oldResult.getKeyId(), decryptResult.getKeyId());

			assertThrows(PrivateGroupChatService.PrivateGroupChatException.class,
					() -> PrivateGroupChatService.getInstance().decrypt(repository, fixture.chloe.getPrivateKey(),
							oldResult.getMessageSignature()));
		}
	}

	@Test
	public void testSendAfterMembershipChangeUsesNewEpoch() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-new-epoch");
			PrivateGroupChatService.SendResult oldResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, bytes("old message"), true, null);

			addMember(repository, fixture.groupId, fixture.chloe);
			byte[] plaintext = bytes("new epoch message");
			PrivateGroupChatService.SendResult newResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, plaintext, true, oldResult.getMessageSignature());
			PrivateGroupChatMembership.MembershipEpoch currentEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);

			assertNotNull(newResult.getKeyAnnouncementSignature());
			assertFalse(Arrays.equals(oldResult.getEpochId(), newResult.getEpochId()));
			assertArrayEquals(currentEpoch.getEpochId(), newResult.getEpochId());

			PrivateGroupChatService.DecryptResult bobDecryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.bob.getPrivateKey(), newResult.getMessageSignature());
			assertArrayEquals(plaintext, bobDecryptResult.getData());

			PrivateGroupChatService.DecryptResult chloeDecryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.chloe.getPrivateKey(), newResult.getMessageSignature());
			assertArrayEquals(plaintext, chloeDecryptResult.getData());
		}
	}

	@Test
	public void testRehydrateIgnoresUnusableAnnouncementsBeforeValidAnnouncement() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Fixture fixture = createFixture(repository, "private-service-noisy-announcements");
			byte[] plaintext = bytes("secret");
			PrivateGroupChatService.SendResult sendResult = PrivateGroupChatService.getInstance().send(repository,
					fixture.alice.getPrivateKey(), fixture.groupId, plaintext, true, null);
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					fixture.groupId);

			ChatTransactionData malformedData = rawChat(fixture.alice, fixture.groupId, bytes("not an envelope"),
					false, true, now() + 10, signature(61));
			byte[] unrelatedGroupKey = bytes(Transformer.AES256_LENGTH, 70);
			PrivateGroupChatEnvelope unrelatedAnnouncement = PrivateGroupChatKeyAnnouncement.create(epoch,
					unrelatedGroupKey, fixture.alice.getPrivateKey());
			ChatTransactionData unrelatedAnnouncementData = rawChat(fixture.alice, fixture.groupId,
					unrelatedAnnouncement.toBytes(), false, true, now() + 11, signature(62));
			repository.getChatStoreRepository().save(malformedData);
			repository.getChatStoreRepository().save(unrelatedAnnouncementData);
			repository.saveChanges();
			PrivateGroupChatKeyCache.getInstance().clear();

			PrivateGroupChatService.DecryptResult decryptResult = PrivateGroupChatService.getInstance().decrypt(repository,
					fixture.bob.getPrivateKey(), sendResult.getMessageSignature());

			assertArrayEquals(plaintext, decryptResult.getData());
			assertArrayEquals(sendResult.getKeyId(), decryptResult.getKeyId());
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

	private static ChatTransactionData signedChat(Repository repository, TestAccount sender, int groupId,
			byte[] data, boolean isText, boolean isEncrypted, long timestamp) throws DataException {
		ChatTransactionData chatData = rawChat(sender, groupId, data, isText, isEncrypted, timestamp, null);
		ChatTransaction chatTransaction = new ChatTransaction(repository, chatData);
		chatTransaction.computeNonce();
		chatTransaction.sign(sender);
		return chatData;
	}

	private static ChatTransactionData rawChat(TestAccount sender, int groupId, byte[] data, boolean isText,
			boolean isEncrypted, long timestamp, byte[] signature) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				timestamp,
				groupId,
				sender.getPublicKey(),
				0L,
				0,
				signature);

		return new ChatTransactionData(baseTransactionData, sender.getAddress(), 0, null, null,
				data, isText, isEncrypted);
	}

	private static long now() {
		Long now = NTP.getTime();
		return now != null ? now : System.currentTimeMillis();
	}

	private static byte[] bytes(String message) {
		return message.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] bytes(int length, int seed) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < bytes.length; ++i)
			bytes[i] = (byte) (seed + i);

		return bytes;
	}

	private static byte[] signature(int value) {
		byte[] signature = new byte[64];
		signature[63] = (byte) value;
		return signature;
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
