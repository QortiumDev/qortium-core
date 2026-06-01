package org.qortium.test;

import org.bouncycastle.util.encoders.Base64;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.chat.DirectPrivateChatService;
import org.qortium.chat.crypto.DirectPrivateChatEnvelope;
import org.qortium.data.chat.ChatMessage;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestAccount;
import org.qortium.transaction.Transaction.ValidationResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DirectPrivateChatServiceTests extends Common {

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testSendStoresEncryptedDirectMessageAndBothParticipantsCanList() throws Exception {
		byte[] payload = "service direct private message".getBytes(StandardCharsets.UTF_8);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			bob.ensureAccount();
			repository.saveChanges();

			DirectPrivateChatService.SendResult sendResult = DirectPrivateChatService.getInstance()
					.send(repository, alice.getPrivateKey(), bob.getAddress(), payload, true, null);

			assertEquals(DirectPrivateChatService.SendStatus.STORED, sendResult.getStatus());
			assertNotNull(sendResult.getMessageSignature());

			ChatTransactionData stored = repository.getChatStoreRepository().fromSignature(sendResult.getMessageSignature());
			assertNotNull(stored);
			assertEquals(Group.NO_GROUP, stored.getTxGroupId());
			assertEquals(alice.getAddress(), stored.getSender());
			assertEquals(bob.getAddress(), stored.getRecipient());
			assertTrue(stored.getIsEncrypted());
			assertTrue(stored.getIsText());
			assertFalse(Base64.toBase64String(payload).equals(Base64.toBase64String(stored.getData())));

			DirectPrivateChatEnvelope envelope = DirectPrivateChatEnvelope.fromBytes(stored.getData());
			assertArrayEquals(alice.getPublicKey(), envelope.getSenderPublicKey());
			assertArrayEquals(bob.getPublicKey(), envelope.getRecipientPublicKey());

			List<DirectPrivateChatService.ListMessageResult> recipientMessages = DirectPrivateChatService.getInstance()
					.listMessages(repository, bob.getPrivateKey(), alice.getAddress(), null, null, null,
							null, null, ChatMessage.Encoding.BASE64, null, null, null);
			assertEquals(1, recipientMessages.size());
			assertEquals(DirectPrivateChatService.DecryptionStatus.DECRYPTED, recipientMessages.get(0).getStatus());
			assertArrayEquals(payload, recipientMessages.get(0).getData());

			List<DirectPrivateChatService.ListMessageResult> senderMessages = DirectPrivateChatService.getInstance()
					.listMessages(repository, alice.getPrivateKey(), bob.getAddress(), null, null, null,
							null, null, ChatMessage.Encoding.BASE64, null, null, null);
			assertEquals(1, senderMessages.size());
			assertEquals(DirectPrivateChatService.DecryptionStatus.DECRYPTED, senderMessages.get(0).getStatus());
			assertArrayEquals(payload, senderMessages.get(0).getData());
		}
	}

	@Test
	public void testSendFailsWhenRecipientPublicKeyIsUnknown() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount unknownRecipient = Common.generateDeterministicSeedAccount(repository,
					"direct-private-unknown", 1);

			try {
				DirectPrivateChatService.getInstance().send(repository, alice.getPrivateKey(),
						unknownRecipient.getAddress(), "missing public key".getBytes(StandardCharsets.UTF_8),
						true, null);
				fail("Expected unknown recipient public key to fail");
			} catch (DirectPrivateChatService.ValidationException e) {
				assertEquals(ValidationResult.PUBLIC_KEY_UNKNOWN, e.getValidationResult());
			}
		}
	}

	@Test
	public void testActiveChatsReturnsLatestDirectMessage() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			bob.ensureAccount();
			chloe.ensureAccount();
			repository.saveChanges();

			DirectPrivateChatService.getInstance().send(repository, alice.getPrivateKey(), bob.getAddress(),
					"older".getBytes(StandardCharsets.UTF_8), true, null);
			DirectPrivateChatService.getInstance().send(repository, chloe.getPrivateKey(), bob.getAddress(),
					"latest".getBytes(StandardCharsets.UTF_8), true, null);

			List<DirectPrivateChatService.ActiveChatResult> activeChats = DirectPrivateChatService.getInstance()
					.listActiveChats(repository, bob.getPrivateKey(), ChatMessage.Encoding.BASE64, null);

			assertEquals(2, activeChats.size());
			assertEquals(chloe.getAddress(), activeChats.get(0).getAddress());
			assertEquals(DirectPrivateChatService.DecryptionStatus.DECRYPTED,
					activeChats.get(0).getMessageResult().getStatus());
			assertArrayEquals("latest".getBytes(StandardCharsets.UTF_8),
					activeChats.get(0).getMessageResult().getData());
		}
	}

}
