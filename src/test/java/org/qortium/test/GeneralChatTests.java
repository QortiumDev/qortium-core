package org.qortium.test;

import org.bouncycastle.util.encoders.Base64;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.data.chat.ActiveChats;
import org.qortium.data.chat.ChatMessage;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.ChatTransaction;
import org.qortium.transaction.Transaction.ValidationResult;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GeneralChatTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TransactionUtils.deleteUnconfirmedTransactions(repository);
		}

		Common.orphanCheck();
	}

	@Test
	public void testGeneralChatCanBeImportedAndFetched() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			ChatTransactionData transactionData = importChat(repository, alice, null, "general chat is back");

			List<ChatMessage> messages = repository.getChatRepository().getMessagesMatchingCriteria(
					null, null, Group.NO_GROUP, null, null, null, null, ChatMessage.Encoding.BASE64, null, null, null);

			assertEquals(1, messages.size());

			ChatMessage message = messages.get(0);
			assertEquals(Group.NO_GROUP, message.getTxGroupId());
			assertEquals(alice.getAddress(), message.getSender());
			assertNull(message.getRecipient());
			assertArrayEquals(transactionData.getSignature(), message.getSignature());
			assertEquals(Base64.toBase64String(transactionData.getData()), message.getData());
		}
	}

	@Test
	public void testGeneralChatIsSeparateFromDirectChat() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			ChatTransactionData generalChatData = importChat(repository, alice, null, "public message");
			ChatTransactionData directChatData = importChat(repository, alice, bob.getAddress(), "direct message");

			List<ChatMessage> generalMessages = repository.getChatRepository().getMessagesMatchingCriteria(
					null, null, Group.NO_GROUP, null, null, null, null, ChatMessage.Encoding.BASE64, null, null, null);

			assertEquals(1, generalMessages.size());
			assertArrayEquals(generalChatData.getSignature(), generalMessages.get(0).getSignature());

			List<ChatMessage> directMessages = repository.getChatRepository().getMessagesMatchingCriteria(
					null, null, null, null, null, Arrays.asList(alice.getAddress(), bob.getAddress()), null,
					ChatMessage.Encoding.BASE64, null, null, null);

			assertEquals(1, directMessages.size());
			assertArrayEquals(directChatData.getSignature(), directMessages.get(0).getSignature());
		}
	}

	@Test
	public void testGeneralChatAppearsInActiveChats() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			ChatTransactionData transactionData = importChat(repository, alice, null, "active general chat");

			ActiveChats activeChats = repository.getChatRepository().getActiveChats(alice.getAddress(), ChatMessage.Encoding.BASE64, null);

			ActiveChats.GroupChat generalChat = activeChats.getGroups().stream()
					.filter(groupChat -> groupChat.getGroupId() == Group.NO_GROUP)
					.findFirst()
					.orElse(null);

			assertNotNull(generalChat);
			assertNull(generalChat.getGroupName());
			assertEquals(alice.getAddress(), generalChat.getSender());
			assertArrayEquals(transactionData.getSignature(), generalChat.getSignature());
			assertEquals(Base64.toBase64String(transactionData.getData()), generalChat.getData());
		}
	}

	private ChatTransactionData importChat(Repository repository, TestAccount sender, String recipient, String message) throws DataException {
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				TransactionUtils.nextTimestamp(repository),
				Group.NO_GROUP,
				sender.getPublicKey(),
				0L,
				null);

		byte[] data = message.getBytes(StandardCharsets.UTF_8);
		ChatTransactionData transactionData = new ChatTransactionData(
				baseTransactionData,
				sender.getAddress(),
				0,
				recipient,
				null,
				data,
				true,
				false);

		ChatTransaction transaction = new ChatTransaction(repository, transactionData);
		assertEquals(ValidationResult.OK, transaction.isValid());

		transaction.computeNonce();
		transaction.sign(sender);

		assertTrue(transaction.isSignatureValid());
		assertEquals(ValidationResult.OK, transaction.importAsUnconfirmed());

		return transactionData;
	}

}
