package org.qortal.test;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortal.asset.Asset;
import org.qortal.chat.ChatService;
import org.qortal.chat.crypto.PrivateGroupChatEnvelope;
import org.qortal.chat.crypto.PrivateGroupChatKeyAnnouncement;
import org.qortal.chat.crypto.PrivateGroupChatKeyRequest;
import org.qortal.chat.crypto.PrivateGroupChatMembership;
import org.qortal.chat.crypto.PrivateGroupChatRotationRequest;
import org.qortal.data.group.GroupAdminData;
import org.qortal.data.group.GroupData;
import org.qortal.data.group.GroupMemberData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.group.Group;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.test.common.Common;
import org.qortal.test.common.GroupUtils;
import org.qortal.test.common.TestAccount;
import org.qortal.transaction.ChatTransaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.Transformer;
import org.qortal.utils.NTP;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChatServiceTests extends Common {

	private static final ChatService CHAT_SERVICE = ChatService.getInstance();

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testValidSignedChatStoresInDedicatedStoreOnly() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			ChatTransactionData chatData = signedChat(repository, alice, Group.NO_GROUP, null, "stored chat", now());

			assertTrue(CHAT_SERVICE.isSignatureValid(repository, chatData));
			assertEquals(ValidationResult.OK, CHAT_SERVICE.validateAndStore(repository, chatData));

			assertNotNull(repository.getChatStoreRepository().fromSignature(chatData.getSignature()));
			assertNull(repository.getTransactionRepository().fromSignature(chatData.getSignature()));
			assertFalse(repository.getTransactionRepository().exists(chatData.getSignature()));

			List<?> unconfirmedTransactions = repository.getTransactionRepository().getUnconfirmedTransactions();
			assertTrue(unconfirmedTransactions.isEmpty());
		}
	}

	@Test
	public void testDuplicateSignatureIsRejected() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			ChatTransactionData chatData = signedChat(repository, alice, Group.NO_GROUP, null, "duplicate chat", now());

			assertEquals(ValidationResult.OK, CHAT_SERVICE.validateAndStore(repository, chatData));
			assertEquals(ValidationResult.TRANSACTION_ALREADY_EXISTS, CHAT_SERVICE.validateAndStore(repository, chatData));
		}
	}

	@Test
	public void testInvalidSignatureAndIncorrectNonceFailSignatureCheck() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			ChatTransactionData invalidSignatureData = signedChat(repository, alice, Group.NO_GROUP, null, "invalid signature", now());
			invalidSignatureData.setSignature(new byte[64]);
			assertFalse(CHAT_SERVICE.isSignatureValid(repository, invalidSignatureData));

			ChatTransactionData incorrectNonceData = unsignedChat(alice, Group.NO_GROUP, null, "incorrect nonce", now(), null);
			new ChatTransaction(repository, incorrectNonceData).sign(alice);
			assertFalse(CHAT_SERVICE.isSignatureValid(repository, incorrectNonceData));
		}
	}

	@Test
	public void testChatDifficultyDoesNotDependOnNativeBalance() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			alice.setConfirmedBalance(Asset.NATIVE, 0L);
			bob.setConfirmedBalance(Asset.NATIVE, 500000000L);

			ChatTransaction zeroBalanceChat = new ChatTransaction(repository,
					unsignedChat(alice, Group.NO_GROUP, null, "zero balance", now(), null));
			ChatTransaction fundedChat = new ChatTransaction(repository,
					unsignedChat(bob, Group.NO_GROUP, null, "funded", now(), null));

			assertEquals(8, zeroBalanceChat.getPoWDifficulty());
			assertEquals(zeroBalanceChat.getPoWDifficulty(), fundedChat.getPoWDifficulty());
		}
	}

	@Test
	public void testInvalidRecipientIsRejected() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			ChatTransactionData chatData = unsignedChat(alice, Group.NO_GROUP, "not-an-address", "invalid recipient", now(), signature(10));

			assertEquals(ValidationResult.INVALID_ADDRESS, CHAT_SERVICE.validateForStore(repository, chatData));
		}
	}

	@Test
	public void testInvalidDataLengthIsRejected() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			ChatTransactionData emptyData = unsignedChat(alice, Group.NO_GROUP, null, new byte[0], now(), signature(11));
			assertEquals(ValidationResult.INVALID_DATA_LENGTH, CHAT_SERVICE.validateForStore(repository, emptyData));

			ChatTransactionData oversizedData = unsignedChat(alice, Group.NO_GROUP, null, new byte[ChatTransaction.MAX_DATA_SIZE + 1], now(), signature(12));
			assertEquals(ValidationResult.INVALID_DATA_LENGTH, CHAT_SERVICE.validateForStore(repository, oversizedData));
		}
	}

	@Test
	public void testStoreBackedRateLimitIsEnforced() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			FieldUtils.writeField(Settings.getInstance(), "maxRecentChatMessagesPerAccount", 1, true);

			TestAccount alice = Common.getTestAccount(repository, "alice");
			ChatTransactionData existingChatData = unsignedChat(alice, Group.NO_GROUP, null, "existing", now(), signature(13));
			repository.getChatStoreRepository().save(existingChatData);
			repository.saveChanges();

			ChatTransactionData newChatData = signedChat(repository, alice, Group.NO_GROUP, null, "rate limited", now() + 1);

			assertEquals(ValidationResult.TOO_MANY_UNCONFIRMED, CHAT_SERVICE.validateForStore(repository, newChatData));
		}
	}

	@Test
	public void testOldChatOutsideRetentionIsRejected() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			long oldTimestamp = now() - Settings.getInstance().getChatMessageRetentionPeriod() - 1;
			ChatTransactionData chatData = signedChat(repository, alice, Group.NO_GROUP, null, "old chat", oldTimestamp);

			assertTrue(CHAT_SERVICE.isSignatureValid(repository, chatData));
			assertEquals(ValidationResult.TIMESTAMP_TOO_OLD, CHAT_SERVICE.validateForStore(repository, chatData));
		}
	}

	@Test
	public void testGroupMembershipIsValidated() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			int groupId = GroupUtils.createGroup(repository, alice, "chat-service-group", true, ApprovalThreshold.ONE, 10, 40);
			GroupUtils.joinGroup(repository, bob, groupId);

			ChatTransactionData memberChatData = signedChat(repository, bob, groupId, null, "member chat", now());
			assertEquals(ValidationResult.OK, CHAT_SERVICE.validateForStore(repository, memberChatData));

			ChatTransactionData nonMemberChatData = signedChat(repository, chloe, groupId, null, "non member chat", now() + 1);
			assertEquals(ValidationResult.INVALID_TX_GROUP_ID, CHAT_SERVICE.validateForStore(repository, nonMemberChatData));
		}
	}

	@Test
	public void testPlaintextStillAllowedOutsideClosedGroupBroadcasts() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			ChatTransactionData noGroupChatData = signedChat(repository, alice, Group.NO_GROUP, null, "no group chat", now());
			assertEquals(ValidationResult.OK, CHAT_SERVICE.validateForStore(repository, noGroupChatData));

			int openGroupId = GroupUtils.createGroup(repository, alice, "chat-service-open-plain", true,
					ApprovalThreshold.ONE, 10, 40);
			ChatTransactionData openGroupChatData = signedChat(repository, alice, openGroupId, null,
					"open group chat", now() + 1);
			assertEquals(ValidationResult.OK, CHAT_SERVICE.validateForStore(repository, openGroupChatData));

			int closedGroupId = GroupUtils.createGroup(repository, alice, "chat-service-closed-direct", false,
					ApprovalThreshold.ONE, 10, 40);
			addMember(repository, closedGroupId, bob);
			ChatTransactionData closedGroupDirectChatData = signedChat(repository, alice, closedGroupId,
					bob.getAddress(), "closed group direct chat", now() + 2);
			assertEquals(ValidationResult.OK, CHAT_SERVICE.validateForStore(repository, closedGroupDirectChatData));
		}
	}

	@Test
	public void testClosedGroupPlaintextAndMalformedPrivatePayloadsAreRejected() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			int groupId = GroupUtils.createGroup(repository, alice, "chat-service-closed-plain", false,
					ApprovalThreshold.ONE, 10, 40);

			ChatTransactionData plaintextChatData = signedChat(repository, alice, groupId, null,
					"closed plaintext", now());
			assertEquals(ValidationResult.INVALID_DATA_LENGTH, CHAT_SERVICE.validateForStore(repository, plaintextChatData));

			ChatTransactionData malformedPrivateChatData = signedChat(repository, alice, groupId, null,
					"not an envelope".getBytes(StandardCharsets.UTF_8), true, true, now() + 1);
			assertEquals(ValidationResult.INVALID_DATA_LENGTH,
					CHAT_SERVICE.validateForStore(repository, malformedPrivateChatData));
		}
	}

	@Test
	public void testClosedGroupPrivateMessageEnvelopeIsAccepted() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			int groupId = GroupUtils.createGroup(repository, alice, "chat-service-closed-message", false,
					ApprovalThreshold.ONE, 10, 40);
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					groupId);
			PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.message(groupId, epoch.getEpochId(),
					bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 1),
					bytes(PrivateGroupChatEnvelope.NONCE_LENGTH, 2),
					bytes(32, 3));

			ChatTransactionData privateChatData = signedChat(repository, alice, groupId, null,
					envelope.toBytes(), true, true, now());

			assertEquals(ValidationResult.OK, CHAT_SERVICE.validateForStore(repository, privateChatData));
		}
	}

	@Test
	public void testClosedGroupKeyAnnouncementEnvelopeIsAccepted() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = GroupUtils.createGroup(repository, alice, "chat-svc-key-announce", false,
					ApprovalThreshold.ONE, 10, 40);
			addMember(repository, groupId, bob);
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					groupId);
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 10);
			PrivateGroupChatEnvelope keyAnnouncement = PrivateGroupChatKeyAnnouncement.create(epoch,
					groupKey, alice.getPrivateKey());

			ChatTransactionData keyAnnouncementChatData = signedChat(repository, alice, groupId, null,
					keyAnnouncement.toBytes(), false, true, now());

			assertEquals(ValidationResult.OK, CHAT_SERVICE.validateForStore(repository, keyAnnouncementChatData));

			ChatTransactionData relayedKeyAnnouncementData = signedChat(repository, bob, groupId, null,
					keyAnnouncement.toBytes(), false, true, now() + 1);
			assertEquals(ValidationResult.OK, CHAT_SERVICE.validateForStore(repository, relayedKeyAnnouncementData));
		}
	}

	@Test
	public void testClosedGroupKeyRequestEnvelopeIsAccepted() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = GroupUtils.createGroup(repository, alice, "chat-service-closed-key-request", false,
					ApprovalThreshold.ONE, 10, 40);
			addMember(repository, groupId, bob);
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					groupId);
			PrivateGroupChatEnvelope keyRequest = PrivateGroupChatKeyRequest.create(epoch, bob.getPrivateKey(),
					bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 20));

			ChatTransactionData keyRequestData = signedChat(repository, bob, groupId, null,
					keyRequest.toBytes(), false, true, now());

			assertEquals(ValidationResult.OK, CHAT_SERVICE.validateForStore(repository, keyRequestData));
		}
	}

	@Test
	public void testClosedGroupRotationRequestEnvelopeIsAcceptedForOwnerOrAdmin() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = GroupUtils.createGroup(repository, alice, "chat-svc-rotation-req", false,
					ApprovalThreshold.ONE, 10, 40);
			addMember(repository, groupId, bob);
			addAdmin(repository, groupId, bob);
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					groupId);

			PrivateGroupChatEnvelope ownerRequest = PrivateGroupChatRotationRequest.create(epoch, alice.getPrivateKey());
			ChatTransactionData ownerRequestData = signedChat(repository, alice, groupId, null,
					ownerRequest.toBytes(), false, true, now());
			assertEquals(ValidationResult.OK, CHAT_SERVICE.validateForStore(repository, ownerRequestData));

			PrivateGroupChatEnvelope adminRequest = PrivateGroupChatRotationRequest.create(epoch, bob.getPrivateKey());
			ChatTransactionData adminRequestData = signedChat(repository, bob, groupId, null,
					adminRequest.toBytes(), false, true, now() + 1);
			assertEquals(ValidationResult.OK, CHAT_SERVICE.validateForStore(repository, adminRequestData));
		}
	}

	@Test
	public void testClosedGroupHistoricalRecoveryControlsAreAccepted() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			int groupId = GroupUtils.createGroup(repository, alice, "chat-service-historical-recovery", false,
					ApprovalThreshold.ONE, 10, 40);
			addMember(repository, groupId, bob);

			PrivateGroupChatMembership.MembershipEpoch oldEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(
					repository, groupId);
			byte[] keyId = bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 60);
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 61);
			PrivateGroupChatEnvelope keyAnnouncement = PrivateGroupChatKeyAnnouncement.create(oldEpoch, groupKey,
					alice.getPrivateKey());
			PrivateGroupChatEnvelope keyRequest = PrivateGroupChatKeyRequest.create(groupId,
					oldEpoch.getEpochId(), bob.getPrivateKey(), keyId);
			PrivateGroupChatEnvelope keyRequestWithoutKeyId = PrivateGroupChatKeyRequest.create(groupId,
					oldEpoch.getEpochId(), bob.getPrivateKey(), null);
			PrivateGroupChatEnvelope message = PrivateGroupChatEnvelope.message(groupId, oldEpoch.getEpochId(),
					keyId, bytes(PrivateGroupChatEnvelope.NONCE_LENGTH, 62), bytes(32, 63));
			PrivateGroupChatEnvelope rotationRequest = PrivateGroupChatRotationRequest.create(oldEpoch,
					alice.getPrivateKey());

			addMember(repository, groupId, chloe);

			ChatTransactionData historicalAnnouncementData = signedChat(repository, bob, groupId, null,
					keyAnnouncement.toBytes(), false, true, now());
			assertEquals(ValidationResult.OK, CHAT_SERVICE.validateForStore(repository, historicalAnnouncementData));

			ChatTransactionData historicalRequestData = signedChat(repository, bob, groupId, null,
					keyRequest.toBytes(), false, true, now() + 1);
			assertEquals(ValidationResult.OK, CHAT_SERVICE.validateForStore(repository, historicalRequestData));

			ChatTransactionData historicalRequestWithoutKeyData = signedChat(repository, bob, groupId, null,
					keyRequestWithoutKeyId.toBytes(), false, true, now() + 2);
			assertEquals(ValidationResult.INVALID_DATA_LENGTH,
					CHAT_SERVICE.validateForStore(repository, historicalRequestWithoutKeyData));

			ChatTransactionData historicalMessageData = signedChat(repository, alice, groupId, null,
					message.toBytes(), true, true, now() + 3);
			assertEquals(ValidationResult.INVALID_DATA_LENGTH, CHAT_SERVICE.validateForStore(repository,
					historicalMessageData));

			ChatTransactionData historicalRotationData = signedChat(repository, alice, groupId, null,
					rotationRequest.toBytes(), false, true, now() + 4);
			assertEquals(ValidationResult.INVALID_DATA_LENGTH, CHAT_SERVICE.validateForStore(repository,
					historicalRotationData));
		}
	}

	@Test
	public void testClosedGroupPrivateEnvelopeContextAndUnsupportedTypesAreRejected() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int firstGroupId = GroupUtils.createGroup(repository, alice, "chat-svc-context-one", false,
					ApprovalThreshold.ONE, 10, 40);
			int secondGroupId = GroupUtils.createGroup(repository, alice, "chat-svc-context-two", false,
					ApprovalThreshold.ONE, 10, 40);
			addMember(repository, firstGroupId, bob);
			PrivateGroupChatMembership.MembershipEpoch firstEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					firstGroupId);
			PrivateGroupChatMembership.MembershipEpoch secondEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					secondGroupId);

			PrivateGroupChatEnvelope wrongGroupEnvelope = PrivateGroupChatEnvelope.message(secondGroupId,
					secondEpoch.getEpochId(), bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 20),
					bytes(PrivateGroupChatEnvelope.NONCE_LENGTH, 21), bytes(32, 22));
			ChatTransactionData wrongGroupData = signedChat(repository, alice, firstGroupId, null,
					wrongGroupEnvelope.toBytes(), true, true, now());
			assertEquals(ValidationResult.INVALID_DATA_LENGTH, CHAT_SERVICE.validateForStore(repository, wrongGroupData));

			PrivateGroupChatEnvelope keyRequest = PrivateGroupChatEnvelope.keyRequest(firstGroupId, firstEpoch.getEpochId(),
					alice.getPublicKey(), null, bytes(PrivateGroupChatEnvelope.SIGNATURE_LENGTH, 30));
			ChatTransactionData keyRequestData = signedChat(repository, alice, firstGroupId, null,
					keyRequest.toBytes(), false, true, now() + 1);
			assertEquals(ValidationResult.INVALID_DATA_LENGTH, CHAT_SERVICE.validateForStore(repository, keyRequestData));

			PrivateGroupChatEnvelope relayedKeyRequest = PrivateGroupChatKeyRequest.create(firstEpoch,
					bob.getPrivateKey(), null);
			ChatTransactionData relayedKeyRequestData = signedChat(repository, alice, firstGroupId, null,
					relayedKeyRequest.toBytes(), false, true, now() + 2);
			assertEquals(ValidationResult.INVALID_DATA_LENGTH,
					CHAT_SERVICE.validateForStore(repository, relayedKeyRequestData));

			PrivateGroupChatEnvelope rotationRequest = PrivateGroupChatEnvelope.rotationRequest(firstGroupId,
					firstEpoch.getEpochId(), alice.getPublicKey(), bytes(PrivateGroupChatEnvelope.SIGNATURE_LENGTH, 40));
			ChatTransactionData rotationRequestData = signedChat(repository, alice, firstGroupId, null,
					rotationRequest.toBytes(), false, true, now() + 3);
			assertEquals(ValidationResult.INVALID_DATA_LENGTH, CHAT_SERVICE.validateForStore(repository, rotationRequestData));

			PrivateGroupChatEnvelope memberRotationRequest = PrivateGroupChatRotationRequest.create(firstEpoch,
					bob.getPrivateKey());
			ChatTransactionData memberRotationRequestData = signedChat(repository, bob, firstGroupId, null,
					memberRotationRequest.toBytes(), false, true, now() + 4);
			assertEquals(ValidationResult.INVALID_DATA_LENGTH,
					CHAT_SERVICE.validateForStore(repository, memberRotationRequestData));

			PrivateGroupChatEnvelope messageEnvelope = PrivateGroupChatEnvelope.message(firstGroupId, firstEpoch.getEpochId(),
					bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 50),
					bytes(PrivateGroupChatEnvelope.NONCE_LENGTH, 51),
					bytes(32, 52));
			ChatTransactionData missingEncryptedFlagData = signedChat(repository, alice, firstGroupId, null,
					messageEnvelope.toBytes(), true, false, now() + 5);
			assertEquals(ValidationResult.INVALID_DATA_LENGTH,
					CHAT_SERVICE.validateForStore(repository, missingEncryptedFlagData));
		}
	}

	private static ChatTransactionData signedChat(Repository repository, TestAccount sender, int groupId, String recipient,
			String message, long timestamp) throws DataException {
		return signedChat(repository, sender, groupId, recipient, message.getBytes(StandardCharsets.UTF_8),
				true, false, timestamp);
	}

	private static ChatTransactionData signedChat(Repository repository, TestAccount sender, int groupId, String recipient,
			byte[] data, boolean isText, boolean isEncrypted, long timestamp) throws DataException {
		ChatTransactionData chatData = unsignedChat(sender, groupId, recipient, data, isText, isEncrypted,
				timestamp, null);
		ChatTransaction chatTransaction = new ChatTransaction(repository, chatData);
		chatTransaction.computeNonce();
		chatTransaction.sign(sender);
		return chatData;
	}

	private static ChatTransactionData unsignedChat(TestAccount sender, int groupId, String recipient,
			String message, long timestamp, byte[] signature) {
		return unsignedChat(sender, groupId, recipient, message.getBytes(StandardCharsets.UTF_8), timestamp, signature);
	}

	private static ChatTransactionData unsignedChat(TestAccount sender, int groupId, String recipient,
			byte[] data, long timestamp, byte[] signature) {
		return unsignedChat(sender, groupId, recipient, data, true, false, timestamp, signature);
	}

	private static ChatTransactionData unsignedChat(TestAccount sender, int groupId, String recipient,
			byte[] data, boolean isText, boolean isEncrypted, long timestamp, byte[] signature) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				timestamp,
				groupId,
				sender.getPublicKey(),
				0L,
				0,
				signature);

		return new ChatTransactionData(baseTransactionData, sender.getAddress(), 0, recipient, null,
				data, isText, isEncrypted);
	}

	private static long now() {
		Long now = NTP.getTime();
		return now != null ? now : System.currentTimeMillis();
	}

	private static byte[] signature(int value) {
		byte[] signature = new byte[64];
		signature[63] = (byte) value;
		return signature;
	}

	private static void addMember(Repository repository, int groupId, TestAccount account) throws DataException {
		account.ensureAccount();

		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
		repository.getGroupRepository().save(new GroupMemberData(groupId, account.getAddress(),
				groupData.getCreated(), groupData.getReference()));
		repository.saveChanges();
	}

	private static void addAdmin(Repository repository, int groupId, TestAccount account) throws DataException {
		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
		repository.getGroupRepository().save(new GroupAdminData(groupId, account.getAddress(),
				groupData.getReference()));
		repository.saveChanges();
	}

	private static byte[] bytes(int length, int seed) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < bytes.length; ++i)
			bytes[i] = (byte) (seed + i);

		return bytes;
	}

}
