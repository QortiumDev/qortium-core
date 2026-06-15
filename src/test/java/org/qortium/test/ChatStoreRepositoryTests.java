package org.qortium.test;

import org.bouncycastle.util.encoders.Base64;
import org.junit.Before;
import org.junit.Test;
import org.qortium.chat.crypto.PrivateGroupChatEnvelope;
import org.qortium.data.chat.ActiveChats;
import org.qortium.data.chat.ChatMessage;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestAccount;
import org.qortium.utils.NTP;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChatStoreRepositoryTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testSaveAndFetchBySignaturePreservesOpaquePayload() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			byte[] payload = new byte[] { 0, 1, 2, 3, 4, 5, 42, 100 };
			byte[] signature = signature(1);
			byte[] chatReference = signature(2);

			ChatTransactionData chatData = chat(repository, alice, Group.NO_GROUP, bob.getAddress(),
					signature, payload, false, true, now(), chatReference);

			repository.getChatStoreRepository().save(chatData);
			repository.saveChanges();

			ChatTransactionData stored = repository.getChatStoreRepository().fromSignature(signature);

			assertNotNull(stored);
			assertArrayEquals(signature, stored.getSignature());
			assertArrayEquals(payload, stored.getData());
			assertArrayEquals(chatReference, stored.getChatReference());
			assertEquals(alice.getAddress(), stored.getSender());
			assertEquals(bob.getAddress(), stored.getRecipient());
			assertFalse(stored.getIsText());
			assertTrue(stored.getIsEncrypted());
		}
	}

	@Test
	public void testDuplicateSignatureRejected() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			byte[] signature = signature(3);

			ChatTransactionData chatData = chat(repository, alice, Group.NO_GROUP, null,
					signature, bytes("first"), true, false, now(), null);

			repository.getChatStoreRepository().save(chatData);
			repository.saveChanges();

			try {
				repository.getChatStoreRepository().save(chatData);
				fail("Expected duplicate chat signature to be rejected");
			} catch (DataException expected) {
				// Expected
			}

			assertTrue(repository.getChatStoreRepository().exists(signature));
		}
	}

	@Test
	public void testGeneralMessageQueryAndCount() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			ChatTransactionData generalChatData = chat(repository, alice, Group.NO_GROUP, null,
					signature(4), bytes("general"), true, false, now(), null);
			ChatTransactionData directChatData = chat(repository, alice, Group.NO_GROUP, bob.getAddress(),
					signature(5), bytes("direct"), true, false, now() + 1, null);

			repository.getChatStoreRepository().save(generalChatData);
			repository.getChatStoreRepository().save(directChatData);
			repository.saveChanges();

			List<ChatMessage> messages = repository.getChatStoreRepository().getMessagesMatchingCriteria(
					null, null, Group.NO_GROUP, null, null, null, null, ChatMessage.Encoding.BASE64, null, null, null);

			assertEquals(1, messages.size());
			assertArrayEquals(generalChatData.getSignature(), messages.get(0).getSignature());
			assertNull(messages.get(0).getRecipient());
			assertEquals(Base64.toBase64String(generalChatData.getData()), messages.get(0).getData());

			int count = repository.getChatStoreRepository().countMessagesMatchingCriteria(
					null, null, Group.NO_GROUP, null, null, null, null);

			assertEquals(1, count);
		}
	}

	@Test
	public void testDirectMessageQuery() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			ChatTransactionData bobChatData = chat(repository, alice, Group.NO_GROUP, bob.getAddress(),
					signature(6), bytes("bob"), true, false, now(), null);
			ChatTransactionData chloeChatData = chat(repository, alice, Group.NO_GROUP, chloe.getAddress(),
					signature(7), bytes("chloe"), true, false, now() + 1, null);

			repository.getChatStoreRepository().save(bobChatData);
			repository.getChatStoreRepository().save(chloeChatData);
			repository.saveChanges();

			List<ChatMessage> messages = repository.getChatStoreRepository().getMessagesMatchingCriteria(
					null, null, null, null, null, Arrays.asList(alice.getAddress(), bob.getAddress()),
					null, ChatMessage.Encoding.BASE64, null, null, null);

			assertEquals(1, messages.size());
			assertArrayEquals(bobChatData.getSignature(), messages.get(0).getSignature());
			assertEquals(bob.getAddress(), messages.get(0).getRecipient());

			int count = repository.getChatStoreRepository().countMessagesMatchingCriteria(
					null, null, null, null, null, Arrays.asList(alice.getAddress(), bob.getAddress()), null);

			assertEquals(1, count);
		}
	}

	@Test
	public void testDirectMessageRawQuery() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			ChatTransactionData olderBobChatData = chat(repository, alice, Group.NO_GROUP, bob.getAddress(),
					signature(40), bytes("older bob"), true, false, now(), null);
			ChatTransactionData newerBobChatData = chat(repository, bob, Group.NO_GROUP, alice.getAddress(),
					signature(41), bytes("newer bob"), true, false, now() + 1, signature(42));
			ChatTransactionData chloeChatData = chat(repository, alice, Group.NO_GROUP, chloe.getAddress(),
					signature(43), bytes("chloe"), true, false, now() + 2, null);

			repository.getChatStoreRepository().save(olderBobChatData);
			repository.getChatStoreRepository().save(newerBobChatData);
			repository.getChatStoreRepository().save(chloeChatData);
			repository.saveChanges();

			List<ChatTransactionData> messages = repository.getChatStoreRepository().getDirectMessagesMatchingCriteria(
					null, null, null, null, Arrays.asList(alice.getAddress(), bob.getAddress()),
					null, 1, 0, true);

			assertEquals(1, messages.size());
			assertArrayEquals(newerBobChatData.getSignature(), messages.get(0).getSignature());
			assertArrayEquals(signature(42), messages.get(0).getChatReference());

			List<ChatTransactionData> referencedMessages = repository.getChatStoreRepository().getDirectMessagesMatchingCriteria(
					null, null, null, true, Arrays.asList(alice.getAddress(), bob.getAddress()),
					null, null, null, null);

			assertEquals(1, referencedMessages.size());
			assertArrayEquals(newerBobChatData.getSignature(), referencedMessages.get(0).getSignature());
		}
	}

	@Test
	public void testActiveChatsFromStore() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			ChatTransactionData generalChatData = chat(repository, alice, Group.NO_GROUP, null,
					signature(8), bytes("general active"), true, false, now(), null);
			ChatTransactionData directChatData = chat(repository, bob, Group.NO_GROUP, alice.getAddress(),
					signature(9), bytes("direct active"), true, false, now() + 1, null);

			repository.getChatStoreRepository().save(generalChatData);
			repository.getChatStoreRepository().save(directChatData);
			repository.saveChanges();

			ActiveChats activeChats = repository.getChatStoreRepository().getActiveChats(
					alice.getAddress(), ChatMessage.Encoding.BASE64, null);

			ActiveChats.GroupChat generalChat = activeChats.getGroups().stream()
					.filter(groupChat -> groupChat.getGroupId() == Group.NO_GROUP)
					.findFirst()
					.orElse(null);

			assertNotNull(generalChat);
			assertEquals(alice.getAddress(), generalChat.getSender());
			assertArrayEquals(generalChatData.getSignature(), generalChat.getSignature());
			assertEquals(Base64.toBase64String(generalChatData.getData()), generalChat.getData());

			ActiveChats.DirectChat directChat = activeChats.getDirect().stream()
					.filter(chat -> bob.getAddress().equals(chat.getAddress()))
					.findFirst()
					.orElse(null);

			assertNotNull(directChat);
			assertEquals(bob.getAddress(), directChat.getAddress());
			assertEquals(bob.getAddress(), directChat.getSender());
		}
	}

	@Test
	public void testLatestDirectMessagesReturnsNewestPerOtherParty() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");

			ChatTransactionData olderBobChatData = chat(repository, alice, Group.NO_GROUP, bob.getAddress(),
					signature(44), bytes("older bob"), true, false, now(), null);
			ChatTransactionData newerBobChatData = chat(repository, bob, Group.NO_GROUP, alice.getAddress(),
					signature(45), bytes("newer bob"), true, false, now() + 1, null);
			ChatTransactionData chloeChatData = chat(repository, chloe, Group.NO_GROUP, alice.getAddress(),
					signature(46), bytes("chloe"), true, false, now() + 2, null);

			repository.getChatStoreRepository().save(olderBobChatData);
			repository.getChatStoreRepository().save(newerBobChatData);
			repository.getChatStoreRepository().save(chloeChatData);
			repository.saveChanges();

			List<ChatTransactionData> latestDirectMessages = repository.getChatStoreRepository()
					.getLatestDirectMessages(alice.getAddress(), null);

			assertEquals(2, latestDirectMessages.size());
			assertArrayEquals(chloeChatData.getSignature(), latestDirectMessages.get(0).getSignature());
			assertArrayEquals(newerBobChatData.getSignature(), latestDirectMessages.get(1).getSignature());
		}
	}

	@Test
	public void testRetentionCleanupAndRateLimitCount() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			long timestamp = now();
			long cutoffTimestamp = timestamp - 50_000L;
			byte[] oldSignature = signature(10);
			byte[] recentSignature = signature(11);

			ChatTransactionData oldChatData = chat(repository, alice, Group.NO_GROUP, null,
					oldSignature, bytes("old"), true, false, timestamp - 100_000L, null);
			ChatTransactionData recentChatData = chat(repository, alice, Group.NO_GROUP, null,
					recentSignature, bytes("recent"), true, false, timestamp, null);

			repository.getChatStoreRepository().save(oldChatData);
			repository.getChatStoreRepository().save(recentChatData);
			repository.saveChanges();

			assertEquals(1, repository.getChatStoreRepository().countRecentBySender(alice.getPublicKey(), cutoffTimestamp));
			assertEquals(1, repository.getChatStoreRepository().deleteOlderThan(cutoffTimestamp));
			repository.saveChanges();

			assertNull(repository.getChatStoreRepository().fromSignature(oldSignature));
			assertNotNull(repository.getChatStoreRepository().fromSignature(recentSignature));
		}
	}

	@Test
	public void testGetGroupMessagesReturnsBroadcastRowsNewestFirst() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = 501;
			long timestamp = now();

			ChatTransactionData olderGroupData = chat(repository, alice, groupId, null,
					signature(12), bytes("older group"), true, false, timestamp, null);
			ChatTransactionData newerGroupData = chat(repository, alice, groupId, null,
					signature(13), bytes("newer group"), true, false, timestamp + 1, null);
			ChatTransactionData directGroupData = chat(repository, alice, groupId, bob.getAddress(),
					signature(14), bytes("direct group"), true, false, timestamp + 2, null);
			ChatTransactionData otherGroupData = chat(repository, alice, groupId + 1, null,
					signature(15), bytes("other group"), true, false, timestamp + 3, null);

			repository.getChatStoreRepository().save(olderGroupData);
			repository.getChatStoreRepository().save(newerGroupData);
			repository.getChatStoreRepository().save(directGroupData);
			repository.getChatStoreRepository().save(otherGroupData);
			repository.saveChanges();

			List<ChatTransactionData> groupMessages = repository.getChatStoreRepository().getGroupMessages(groupId);

			assertEquals(2, groupMessages.size());
			assertArrayEquals(newerGroupData.getSignature(), groupMessages.get(0).getSignature());
			assertArrayEquals(olderGroupData.getSignature(), groupMessages.get(1).getSignature());
			assertNull(groupMessages.get(0).getRecipient());
			assertNull(groupMessages.get(1).getRecipient());
		}
	}

	@Test
	public void testGroupMessagesFromNonMembersRemainVisible() throws DataException {
		// A sender who is not a current group member (e.g. has left the group) must still have
		// their past group messages returned, so they expire naturally rather than vanishing at once.
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = GroupUtils.createGroup(repository, alice, "chat-store-non-member-visibility", false);
			long timestamp = now();

			// alice is the group owner/member; bob is NOT a member of this group
			ChatTransactionData memberData = chat(repository, alice, groupId, null,
					signature(50), bytes("from member"), true, false, timestamp, null);
			ChatTransactionData nonMemberData = chat(repository, bob, groupId, null,
					signature(51), bytes("from non-member"), true, false, timestamp + 1, null);

			repository.getChatStoreRepository().save(memberData);
			repository.getChatStoreRepository().save(nonMemberData);
			repository.saveChanges();

			List<ChatMessage> messages = repository.getChatStoreRepository().getMessagesMatchingCriteria(
					null, null, groupId, null, null, null, null, ChatMessage.Encoding.BASE64, null, null, null);

			assertEquals(2, messages.size());
			assertArrayEquals(memberData.getSignature(), messages.get(0).getSignature());
			assertArrayEquals(nonMemberData.getSignature(), messages.get(1).getSignature());

			int count = repository.getChatStoreRepository().countMessagesMatchingCriteria(
					null, null, groupId, null, null, null, null);

			assertEquals(2, count);
		}
	}

	@Test
	public void testPrivateGroupControlsHiddenFromNormalQueriesButRawScanRetainsThem() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			int groupId = GroupUtils.createGroup(repository, alice, "chat-store-private-classify", false);
			long timestamp = now();
			byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 20);

			PrivateGroupChatEnvelope messageEnvelope = PrivateGroupChatEnvelope.message(groupId, epochId,
					bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 21),
					bytes(PrivateGroupChatEnvelope.NONCE_LENGTH, 22),
					bytes("private group message"));
			PrivateGroupChatEnvelope keyRequestEnvelope = PrivateGroupChatEnvelope.keyRequest(groupId, epochId,
					alice.getPublicKey(), bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 23), signature(24));

			ChatTransactionData messageData = chat(repository, alice, groupId, null,
					signature(16), messageEnvelope.toBytes(), true, true, timestamp, null);
			ChatTransactionData keyRequestData = chat(repository, alice, groupId, null,
					signature(17), keyRequestEnvelope.toBytes(), false, true, timestamp + 1, null);

			repository.getChatStoreRepository().save(messageData);
			repository.getChatStoreRepository().save(keyRequestData);
			repository.saveChanges();

			List<ChatMessage> messages = repository.getChatStoreRepository().getMessagesMatchingCriteria(
					null, null, groupId, null, null, null, null, ChatMessage.Encoding.BASE64, null, null, null);

			assertEquals(1, messages.size());
			assertArrayEquals(messageData.getSignature(), messages.get(0).getSignature());

			int count = repository.getChatStoreRepository().countMessagesMatchingCriteria(
					null, null, groupId, null, null, null, null);

			assertEquals(1, count);
			assertNotNull(repository.getChatStoreRepository().fromSignature(keyRequestData.getSignature()));

			List<ChatTransactionData> groupMessages = repository.getChatStoreRepository().getGroupMessages(groupId);
			assertEquals(2, groupMessages.size());
			assertArrayEquals(keyRequestData.getSignature(), groupMessages.get(0).getSignature());
			assertArrayEquals(messageData.getSignature(), groupMessages.get(1).getSignature());
		}
	}

	@Test
	public void testPrivateGroupControlsDoNotBumpActiveGroupChat() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			int groupId = GroupUtils.createGroup(repository, alice, "chat-store-private-active", false);
			long timestamp = now();
			byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 30);

			PrivateGroupChatEnvelope messageEnvelope = PrivateGroupChatEnvelope.message(groupId, epochId,
					bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 31),
					bytes(PrivateGroupChatEnvelope.NONCE_LENGTH, 32),
					bytes("active private group message"));
			PrivateGroupChatEnvelope rotationRequestEnvelope = PrivateGroupChatEnvelope.rotationRequest(groupId,
					epochId, alice.getPublicKey(), signature(33));

			ChatTransactionData messageData = chat(repository, alice, groupId, null,
					signature(18), messageEnvelope.toBytes(), true, true, timestamp, null);
			ChatTransactionData rotationRequestData = chat(repository, alice, groupId, null,
					signature(19), rotationRequestEnvelope.toBytes(), false, true, timestamp + 1, null);

			repository.getChatStoreRepository().save(messageData);
			repository.getChatStoreRepository().save(rotationRequestData);
			repository.saveChanges();

			ActiveChats activeChats = repository.getChatStoreRepository().getActiveChats(
					alice.getAddress(), ChatMessage.Encoding.BASE64, null);

			ActiveChats.GroupChat groupChat = activeChats.getGroups().stream()
					.filter(activeGroupChat -> activeGroupChat.getGroupId() == groupId)
					.findFirst()
					.orElse(null);

			assertNotNull(groupChat);
			assertArrayEquals(messageData.getSignature(), groupChat.getSignature());
			assertEquals(Base64.toBase64String(messageData.getData()), groupChat.getData());
		}
	}

	private static ChatTransactionData chat(Repository repository, TestAccount sender, int groupId, String recipient,
			byte[] signature, byte[] data, boolean isText, boolean isEncrypted, long timestamp, byte[] chatReference) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				timestamp,
				groupId,
				sender.getPublicKey(),
				0L,
				0,
				signature);

		return new ChatTransactionData(baseTransactionData, sender.getAddress(), 0, recipient, chatReference, data, isText, isEncrypted);
	}

	private static long now() {
		Long now = NTP.getTime();
		return now != null ? now : System.currentTimeMillis();
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
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

}
