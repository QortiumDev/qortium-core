package org.qortium.api.websocket;

import org.junit.Before;
import org.junit.Test;
import org.qortium.chat.crypto.PrivateGroupChatEnvelope;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestAccount;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChatMessagesWebSocketTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testLiveGroupVisibilityMatchesStoredHistoryClassification() throws Exception {
		try (Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = GroupUtils.createGroup(repository, alice, "websocket-private-control-filter", false);
			byte[] epochId = bytes(PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, 10);

			PrivateGroupChatEnvelope messageEnvelope = PrivateGroupChatEnvelope.message(groupId, epochId,
					bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 20),
					bytes(PrivateGroupChatEnvelope.NONCE_LENGTH, 30), bytes(16, 40));
			PrivateGroupChatEnvelope keyRequestEnvelope = PrivateGroupChatEnvelope.keyRequest(groupId, epochId,
					alice.getPublicKey(), bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 50), signature(60));

			ChatTransactionData message = chat(alice, groupId, null, signature(1), messageEnvelope.toBytes());
			ChatTransactionData control = chat(alice, groupId, null, signature(2), keyRequestEnvelope.toBytes());
			ChatTransactionData direct = chat(alice, groupId, bob.getAddress(), signature(3), bytes(8, 70));

			repository.getChatStoreRepository().save(message);
			repository.getChatStoreRepository().save(control);
			repository.getChatStoreRepository().save(direct);
			repository.saveChanges();

			assertTrue(ChatMessagesWebSocket.isVisibleGroupNotification(
					repository.getChatStoreRepository(), message, groupId));
			assertFalse(ChatMessagesWebSocket.isVisibleGroupNotification(
					repository.getChatStoreRepository(), control, groupId));
			assertFalse(ChatMessagesWebSocket.isVisibleGroupNotification(
					repository.getChatStoreRepository(), direct, groupId));
			assertFalse(ChatMessagesWebSocket.isVisibleGroupNotification(
					repository.getChatStoreRepository(), message, groupId + 1));
		}
	}

	private static ChatTransactionData chat(TestAccount sender, int groupId, String recipient,
			byte[] signature, byte[] data) {
		BaseTransactionData base = new BaseTransactionData(System.currentTimeMillis(), groupId,
				sender.getPublicKey(), 0L, 0, signature);
		return new ChatTransactionData(base, sender.getAddress(), 0, recipient, null, data, true, true);
	}

	private static byte[] bytes(int length, int seed) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; ++i)
			bytes[i] = (byte) (seed + i);
		return bytes;
	}

	private static byte[] signature(int seed) {
		return bytes(64, seed);
	}
}
