package org.qortal.test.api;

import org.bouncycastle.util.encoders.Base64;
import org.junit.Before;
import org.junit.Test;
import org.qortal.api.ApiError;
import org.qortal.api.resource.ChatResource;
import org.qortal.data.chat.ActiveChats;
import org.qortal.data.chat.ChatMessage;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.Common;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.transaction.ChatTransaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ChatResourceTests extends ApiCommon {

	private ChatResource chatResource;

	@Before
	public void buildResource() {
		this.chatResource = (ChatResource) ApiCommon.buildResource(ChatResource.class);
	}

	@Test
	public void testSearchChatReadsDedicatedStore() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			ChatTransactionData chatData = storeChat(repository, alice, Group.NO_GROUP, null, "stored general", signature(1), now());

			List<ChatMessage> messages = this.chatResource.searchChat(
					null, null, Group.NO_GROUP, Arrays.asList(), null, null, null,
					ChatMessage.Encoding.BASE64, null, null, null);

			assertEquals(1, messages.size());
			assertArrayEquals(chatData.getSignature(), messages.get(0).getSignature());
			assertEquals(Base64.toBase64String(chatData.getData()), messages.get(0).getData());
		}
	}

	@Test
	public void testSearchChatDoesNotReadLegacyUnconfirmedChatRows() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			importLegacyUnconfirmedChat(repository, alice, null, "legacy general");
		}

		List<ChatMessage> messages = this.chatResource.searchChat(
				null, null, Group.NO_GROUP, Arrays.asList(), null, null, null,
				ChatMessage.Encoding.BASE64, null, null, null);

		assertTrue(messages.isEmpty());
	}

	@Test
	public void testCountChatMessagesReturnsTrueTotalCount() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");

			storeChat(repository, alice, Group.NO_GROUP, null, "first", signature(2), now());
			storeChat(repository, alice, Group.NO_GROUP, null, "second", signature(3), now() + 1);
			storeChat(repository, alice, Group.NO_GROUP, null, "third", signature(4), now() + 2);
		}

		int count = this.chatResource.countChatMessages(
				null, null, Group.NO_GROUP, Arrays.asList(), null, null, null,
				ChatMessage.Encoding.BASE64, 1, 1, true);

		assertEquals(3, count);
	}

	@Test
	public void testGetMessageBySignatureReadsDedicatedStore() throws DataException {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = storeChat(repository, alice, Group.NO_GROUP, null, "by signature", signature(5), now());
		}

		ChatMessage message = this.chatResource.getMessageBySignature(Base58.encode(chatData.getSignature()), ChatMessage.Encoding.BASE64);

		assertNotNull(message);
		assertArrayEquals(chatData.getSignature(), message.getSignature());
		assertEquals(Base64.toBase64String(chatData.getData()), message.getData());
	}

	@Test
	public void testGetMessageBySignatureRejectsMissingMessage() {
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.chatResource.getMessageBySignature(Base58.encode(signature(6)), ChatMessage.Encoding.BASE64));
	}

	@Test
	public void testActiveChatsReadsDedicatedStore() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			storeChat(repository, alice, Group.NO_GROUP, null, "general active", signature(7), now());
			storeChat(repository, bob, Group.NO_GROUP, alice.getAddress(), "direct active", signature(8), now() + 1);

			ActiveChats activeChats = this.chatResource.getActiveChats(alice.getAddress(), ChatMessage.Encoding.BASE64, null);

			ActiveChats.GroupChat generalChat = activeChats.getGroups().stream()
					.filter(groupChat -> groupChat.getGroupId() == Group.NO_GROUP)
					.findFirst()
					.orElse(null);

			assertNotNull(generalChat);
			assertEquals(alice.getAddress(), generalChat.getSender());

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
	public void testInvalidSearchCriteriaStillRejected() {
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.chatResource.searchChat(
						null, null, null, Arrays.asList(), null, null, null,
						ChatMessage.Encoding.BASE64, null, null, null));

		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.chatResource.searchChat(
						null, null, Group.NO_GROUP, Arrays.asList(aliceAddress, bobAddress), null, null, null,
						ChatMessage.Encoding.BASE64, null, null, null));
	}

	@Test
	public void testInvalidInvolvingAddressStillRejected() {
		assertApiError(ApiError.INVALID_ADDRESS,
				() -> this.chatResource.searchChat(
						null, null, null, Arrays.asList(aliceAddress, "not-an-address"), null, null, null,
						ChatMessage.Encoding.BASE64, null, null, null));
	}

	private static ChatTransactionData storeChat(Repository repository, TestAccount sender, int groupId, String recipient,
			String message, byte[] signature, long timestamp) throws DataException {
		ChatTransactionData chatData = chat(sender, groupId, recipient, message, signature, timestamp);

		repository.getChatStoreRepository().save(chatData);
		repository.saveChanges();

		return chatData;
	}

	private static ChatTransactionData importLegacyUnconfirmedChat(Repository repository, TestAccount sender, String recipient, String message) throws DataException {
		ChatTransactionData chatData = chat(sender, Group.NO_GROUP, recipient, message, null, TransactionUtils.nextTimestamp(repository));
		ChatTransaction chatTransaction = new ChatTransaction(repository, chatData);

		assertEquals(ValidationResult.OK, chatTransaction.isValid());

		chatTransaction.computeNonce();
		chatTransaction.sign(sender);

		assertTrue(chatTransaction.isSignatureValid());
		assertEquals(ValidationResult.OK, chatTransaction.importAsUnconfirmed());

		return chatData;
	}

	private static ChatTransactionData chat(TestAccount sender, int groupId, String recipient, String message, byte[] signature, long timestamp) {
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				timestamp,
				groupId,
				sender.getPublicKey(),
				0L,
				0,
				signature);

		return new ChatTransactionData(baseTransactionData, sender.getAddress(), 0, recipient, null,
				message.getBytes(StandardCharsets.UTF_8), true, false);
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

}
