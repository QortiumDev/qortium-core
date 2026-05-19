package org.qortal.test;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.qortal.chat.ChatService;
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

	private static ChatTransactionData signedChat(Repository repository, TestAccount sender, int groupId, String recipient,
			String message, long timestamp) throws DataException {
		ChatTransactionData chatData = unsignedChat(sender, groupId, recipient, message, timestamp, null);
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
		BaseTransactionData baseTransactionData = new BaseTransactionData(
				timestamp,
				groupId,
				sender.getPublicKey(),
				0L,
				0,
				signature);

		return new ChatTransactionData(baseTransactionData, sender.getAddress(), 0, recipient, null, data, true, false);
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
