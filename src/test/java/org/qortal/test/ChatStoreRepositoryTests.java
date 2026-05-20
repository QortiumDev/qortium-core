package org.qortal.test;

import org.bouncycastle.util.encoders.Base64;
import org.junit.Before;
import org.junit.Test;
import org.qortal.data.chat.ActiveChats;
import org.qortal.data.chat.ChatMessage;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.utils.NTP;

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

	private static byte[] signature(int value) {
		byte[] signature = new byte[64];
		signature[63] = (byte) value;
		return signature;
	}

}
