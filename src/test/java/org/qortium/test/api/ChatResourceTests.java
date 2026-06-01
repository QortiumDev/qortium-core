package org.qortium.test.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.primitives.Bytes;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.bouncycastle.util.encoders.Base64;
import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.MarshallerProperties;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.ApiError;
import org.qortium.api.model.DirectPrivateChatActiveChatResponse;
import org.qortium.api.model.DirectPrivateChatActiveChatsRequest;
import org.qortium.api.model.DirectPrivateChatMessageResponse;
import org.qortium.api.model.DirectPrivateChatMessagesRequest;
import org.qortium.api.model.DirectPrivateChatSendRequest;
import org.qortium.api.model.DirectPrivateChatSendResponse;
import org.qortium.api.model.PrivateGroupChatActiveChatResponse;
import org.qortium.api.model.PrivateGroupChatActiveChatsRequest;
import org.qortium.api.model.PrivateGroupChatDecryptRequest;
import org.qortium.api.model.PrivateGroupChatDecryptResponse;
import org.qortium.api.model.PrivateGroupChatKeyAnnouncementRelayRequest;
import org.qortium.api.model.PrivateGroupChatKeyAnnouncementRelayResponse;
import org.qortium.api.model.PrivateGroupChatKeyRequestRequest;
import org.qortium.api.model.PrivateGroupChatKeyRequestRecoveryRequest;
import org.qortium.api.model.PrivateGroupChatKeyRequestRecoveryResponse;
import org.qortium.api.model.PrivateGroupChatKeyRequestResponse;
import org.qortium.api.model.PrivateGroupChatMessageResponse;
import org.qortium.api.model.PrivateGroupChatMessageCountRequest;
import org.qortium.api.model.PrivateGroupChatMessagesRequest;
import org.qortium.api.model.PrivateGroupChatRotateRequest;
import org.qortium.api.model.PrivateGroupChatRotateResponse;
import org.qortium.api.model.PrivateGroupChatRotationRequestRequest;
import org.qortium.api.model.PrivateGroupChatRotationRequestResponse;
import org.qortium.api.model.PrivateGroupChatSendRequest;
import org.qortium.api.model.PrivateGroupChatSendResponse;
import org.qortium.api.resource.ChatResource;
import org.qortium.chat.ChatService;
import org.qortium.chat.crypto.PrivateGroupChatCrypto;
import org.qortium.chat.crypto.PrivateGroupChatEnvelope;
import org.qortium.chat.crypto.PrivateGroupChatKeyAnnouncement;
import org.qortium.chat.crypto.PrivateGroupChatKeyCache;
import org.qortium.chat.crypto.PrivateGroupChatMembership;
import org.qortium.data.chat.ActiveChats;
import org.qortium.data.chat.ChatMessage;
import org.qortium.data.group.GroupData;
import org.qortium.data.group.GroupMemberData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.data.transaction.PaymentTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.group.Group.ApprovalThreshold;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.Common;
import org.qortium.test.common.GroupUtils;
import org.qortium.test.common.TestAccount;
import org.qortium.test.common.TransactionUtils;
import org.qortium.test.common.transaction.TestTransaction;
import org.qortium.transaction.ChatTransaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.transform.TransformationException;
import org.qortium.transform.Transformer;
import org.qortium.transform.transaction.ChatTransactionTransformer;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.Base58;
import org.qortium.utils.NTP;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChatResourceTests extends ApiCommon {

	private static final ObjectMapper JSON = new ObjectMapper();

	private ChatResource chatResource;

	@Before
	public void buildResource() {
		ApiCommon.installTestApiKey();
		this.chatResource = (ChatResource) ApiCommon.buildResource(ChatResource.class, ApiCommon.TEST_API_KEY);
		PrivateGroupChatKeyCache.getInstance().clear();
	}

	@After
	public void cleanupResource() {
		ApiCommon.clearTestApiKey();
		PrivateGroupChatKeyCache.getInstance().clear();
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

	@Test
	public void testDirectPrivateSendMessagesAndActiveChats() throws Exception {
		byte[] payload = "direct private api message".getBytes(StandardCharsets.UTF_8);
		DirectPrivateChatSendRequest sendRequest = new DirectPrivateChatSendRequest();
		DirectPrivateChatMessagesRequest messagesRequest = new DirectPrivateChatMessagesRequest();
		DirectPrivateChatActiveChatsRequest activeChatsRequest = new DirectPrivateChatActiveChatsRequest();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			bob.ensureAccount();
			repository.saveChanges();

			sendRequest.senderPrivateKey = alice.getPrivateKey();
			sendRequest.recipient = bob.getAddress();
			sendRequest.data = payload;
			sendRequest.isText = true;

			messagesRequest.accountPrivateKey = bob.getPrivateKey();
			messagesRequest.otherAddress = alice.getAddress();
			messagesRequest.encoding = ChatMessage.Encoding.BASE64;

			activeChatsRequest.accountPrivateKey = bob.getPrivateKey();
			activeChatsRequest.encoding = ChatMessage.Encoding.BASE64;
		}

		DirectPrivateChatSendResponse sendResponse = this.chatResource.sendDirectPrivateChat(null, sendRequest);
		assertEquals(org.qortium.chat.DirectPrivateChatService.SendStatus.STORED, sendResponse.status);
		assertNotNull(sendResponse.messageSignature);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChatTransactionData stored = repository.getChatStoreRepository().fromSignature(sendResponse.messageSignature);
			assertNotNull(stored);
			assertTrue(stored.getIsEncrypted());
			assertFalse(Arrays.equals(payload, stored.getData()));
		}

		List<DirectPrivateChatMessageResponse> messages = this.chatResource.listDirectPrivateChatMessages(null,
				messagesRequest);

		assertEquals(1, messages.size());
		DirectPrivateChatMessageResponse message = messages.get(0);
		assertEquals(org.qortium.chat.DirectPrivateChatService.DecryptionStatus.DECRYPTED, message.decryptionStatus);
		assertEquals(Base64.toBase64String(payload), message.data);
		assertTrue(message.isText);
		assertTrue(message.isEncrypted);
		assertArrayEquals(sendResponse.messageSignature, message.signature);

		List<DirectPrivateChatActiveChatResponse> activeChats = this.chatResource.listDirectPrivateActiveChats(null,
				activeChatsRequest);
		DirectPrivateChatActiveChatResponse directChat = activeChats.stream()
				.filter(chat -> messagesRequest.otherAddress.equals(chat.address))
				.findFirst()
				.orElse(null);

		assertNotNull(directChat);
		assertEquals(org.qortium.chat.DirectPrivateChatService.DecryptionStatus.DECRYPTED, directChat.decryptionStatus);
		assertEquals(Base64.toBase64String(payload), directChat.data);
		assertArrayEquals(sendResponse.messageSignature, directChat.signature);
	}

	@Test
	public void testDirectPrivateMessagesReportPlainAndUnsupportedRows() throws Exception {
		byte[] plainPayload = "plain direct row".getBytes(StandardCharsets.UTF_8);

		DirectPrivateChatMessagesRequest messagesRequest = new DirectPrivateChatMessagesRequest();
		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");

			ChatTransactionData plainData = chat(alice, Group.NO_GROUP, bob.getAddress(), plainPayload,
					true, false, signature(60), now());
			ChatTransactionData legacyEncryptedData = chat(bob, Group.NO_GROUP, alice.getAddress(),
					"legacy encrypted direct".getBytes(StandardCharsets.UTF_8), true, true, signature(61),
					now() + 1);

			repository.getChatStoreRepository().save(plainData);
			repository.getChatStoreRepository().save(legacyEncryptedData);
			repository.saveChanges();

			messagesRequest.accountPrivateKey = alice.getPrivateKey();
			messagesRequest.otherAddress = bob.getAddress();
			messagesRequest.encoding = ChatMessage.Encoding.BASE64;
		}

		List<DirectPrivateChatMessageResponse> messages = this.chatResource.listDirectPrivateChatMessages(null,
				messagesRequest);

		assertEquals(2, messages.size());
		assertEquals(org.qortium.chat.DirectPrivateChatService.DecryptionStatus.PLAIN,
				messages.get(0).decryptionStatus);
		assertEquals(Base64.toBase64String(plainPayload), messages.get(0).data);
		assertFalse(messages.get(0).isEncrypted);

		assertEquals(org.qortium.chat.DirectPrivateChatService.DecryptionStatus.UNSUPPORTED,
				messages.get(1).decryptionStatus);
		assertNull(messages.get(1).data);
		assertTrue(messages.get(1).isEncrypted);
	}

	@Test
	public void testDirectPrivateSendFailsWithoutKnownRecipientPublicKey() throws Exception {
		DirectPrivateChatSendRequest sendRequest = new DirectPrivateChatSendRequest();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount unknownRecipient = Common.generateDeterministicSeedAccount(repository,
					"direct-private-api-unknown", 1);

			sendRequest.senderPrivateKey = alice.getPrivateKey();
			sendRequest.recipient = unknownRecipient.getAddress();
			sendRequest.data = "unknown public key".getBytes(StandardCharsets.UTF_8);
			sendRequest.isText = true;
		}

		assertApiError(ApiError.TRANSACTION_INVALID,
				() -> this.chatResource.sendDirectPrivateChat(null, sendRequest));
	}

	@Test
	public void testPrivateGroupSendAndDecrypt() throws Exception {
		byte[] payload = "private api message".getBytes(StandardCharsets.UTF_8);
		PrivateGroupChatSendRequest sendRequest = new PrivateGroupChatSendRequest();
		PrivateGroupChatDecryptRequest decryptRequest = new PrivateGroupChatDecryptRequest();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createClosedGroup(repository, alice, "chat-api-private-send");
			addMember(repository, groupId, bob);

			sendRequest.senderPrivateKey = alice.getPrivateKey();
			sendRequest.groupId = groupId;
			sendRequest.data = payload;
			sendRequest.isText = true;
			decryptRequest.recipientPrivateKey = bob.getPrivateKey();
		}

		PrivateGroupChatSendResponse sendResponse = this.chatResource.sendPrivateGroupChat(null, sendRequest);
		assertNotNull(sendResponse.keyAnnouncementSignature);
		assertNotNull(sendResponse.messageSignature);
		assertNotNull(sendResponse.epochId);
		assertNotNull(sendResponse.keyId);

		decryptRequest.messageSignature = sendResponse.messageSignature;
		PrivateGroupChatDecryptResponse decryptResponse = this.chatResource.decryptPrivateGroupChat(null, decryptRequest);

		assertArrayEquals(payload, decryptResponse.data);
		assertTrue(decryptResponse.isText);
		assertEquals(sendRequest.groupId, decryptResponse.groupId);
		assertArrayEquals(sendResponse.epochId, decryptResponse.epochId);
		assertArrayEquals(sendResponse.keyId, decryptResponse.keyId);
	}

	@Test
	public void testPrivateGroupDecryptRehydratesCachedKeyFromStoredAnnouncement() throws Exception {
		byte[] payload = "private api message".getBytes(StandardCharsets.UTF_8);
		PrivateGroupChatSendRequest sendRequest = new PrivateGroupChatSendRequest();
		PrivateGroupChatDecryptRequest decryptRequest = new PrivateGroupChatDecryptRequest();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createClosedGroup(repository, alice, "chat-api-private-missing-key");
			addMember(repository, groupId, bob);

			sendRequest.senderPrivateKey = alice.getPrivateKey();
			sendRequest.groupId = groupId;
			sendRequest.data = payload;
			sendRequest.isText = true;
			decryptRequest.recipientPrivateKey = bob.getPrivateKey();
		}

		PrivateGroupChatSendResponse sendResponse = this.chatResource.sendPrivateGroupChat(null, sendRequest);
		decryptRequest.messageSignature = sendResponse.messageSignature;
		PrivateGroupChatKeyCache.getInstance().clear();

		PrivateGroupChatDecryptResponse decryptResponse = this.chatResource.decryptPrivateGroupChat(null, decryptRequest);

		assertArrayEquals(payload, decryptResponse.data);
		assertArrayEquals(sendResponse.epochId, decryptResponse.epochId);
		assertArrayEquals(sendResponse.keyId, decryptResponse.keyId);
	}

	@Test
	public void testPrivateGroupMessagesDecryptsUserMessagesAndSkipsControls() throws Exception {
		byte[] payload = "private api inbox message".getBytes(StandardCharsets.UTF_8);
		PrivateGroupChatSendRequest sendRequest = new PrivateGroupChatSendRequest();
		PrivateGroupChatMessagesRequest messagesRequest = new PrivateGroupChatMessagesRequest();
		PrivateGroupChatKeyRequestRequest keyRequest = new PrivateGroupChatKeyRequestRequest();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createClosedGroup(repository, alice, "chat-api-private-inbox");
			addMember(repository, groupId, bob);

			sendRequest.senderPrivateKey = alice.getPrivateKey();
			sendRequest.groupId = groupId;
			sendRequest.data = payload;
			sendRequest.isText = true;
			messagesRequest.recipientPrivateKey = bob.getPrivateKey();
			messagesRequest.groupId = groupId;
			messagesRequest.encoding = ChatMessage.Encoding.BASE64;
			keyRequest.requesterPrivateKey = bob.getPrivateKey();
			keyRequest.groupId = groupId;
		}

		PrivateGroupChatSendResponse sendResponse = this.chatResource.sendPrivateGroupChat(null, sendRequest);
		keyRequest.keyId = sendResponse.keyId;
		this.chatResource.requestPrivateGroupChatKey(null, keyRequest);

		List<PrivateGroupChatMessageResponse> messages = this.chatResource.listPrivateGroupChatMessages(null,
				messagesRequest);

		assertEquals(1, messages.size());
		PrivateGroupChatMessageResponse message = messages.get(0);
		assertEquals(PrivateGroupChatMessageResponse.Status.DECRYPTED, message.status);
		assertEquals(Base64.toBase64String(payload), message.data);
		assertTrue(message.isText);
		assertTrue(message.isEncrypted);
		assertEquals(sendRequest.groupId, message.txGroupId);
		assertArrayEquals(sendResponse.messageSignature, message.signature);
		assertArrayEquals(sendResponse.epochId, message.epochId);
		assertArrayEquals(sendResponse.keyId, message.keyId);
	}

	@Test
	public void testPrivateGroupMessageCountMatchesInboxFiltersAndSkipsControls() throws Exception {
		PrivateGroupChatSendRequest sendRequest = new PrivateGroupChatSendRequest();
		PrivateGroupChatKeyRequestRequest keyRequest = new PrivateGroupChatKeyRequestRequest();
		PrivateGroupChatRotationRequestRequest rotationRequest = new PrivateGroupChatRotationRequestRequest();
		PrivateGroupChatMessageCountRequest countRequest = new PrivateGroupChatMessageCountRequest();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createClosedGroup(repository, alice, "chat-api-private-count");
			addMember(repository, groupId, bob);

			sendRequest.senderPrivateKey = alice.getPrivateKey();
			sendRequest.groupId = groupId;
			sendRequest.isText = true;
			keyRequest.requesterPrivateKey = bob.getPrivateKey();
			keyRequest.groupId = groupId;
			rotationRequest.requesterPrivateKey = alice.getPrivateKey();
			rotationRequest.groupId = groupId;
			countRequest.recipientPrivateKey = bob.getPrivateKey();
			countRequest.groupId = groupId;
		}

		sendRequest.data = "private count first".getBytes(StandardCharsets.UTF_8);
		PrivateGroupChatSendResponse firstResponse = this.chatResource.sendPrivateGroupChat(null, sendRequest);
		sendRequest.data = "private count second".getBytes(StandardCharsets.UTF_8);
		sendRequest.chatReference = firstResponse.messageSignature;
		PrivateGroupChatSendResponse secondResponse = this.chatResource.sendPrivateGroupChat(null, sendRequest);
		keyRequest.keyId = secondResponse.keyId;
		this.chatResource.requestPrivateGroupChatKey(null, keyRequest);
		this.chatResource.requestPrivateGroupChatRotation(null, rotationRequest);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			repository.getChatStoreRepository().save(chat(alice, sendRequest.groupId, null,
					"plain count noise", signature(91), now()));
			repository.getChatStoreRepository().save(chat(alice, sendRequest.groupId, null,
					"malformed encrypted count noise".getBytes(StandardCharsets.UTF_8), false, true,
					signature(92), now() + 1));
			repository.saveChanges();
		}

		assertEquals(2, this.chatResource.countPrivateGroupChatMessages(null, countRequest));

		countRequest.hasChatReference = true;
		assertEquals(1, this.chatResource.countPrivateGroupChatMessages(null, countRequest));

		countRequest.hasChatReference = false;
		assertEquals(1, this.chatResource.countPrivateGroupChatMessages(null, countRequest));

		countRequest.hasChatReference = null;
		countRequest.chatReference = firstResponse.messageSignature;
		assertEquals(1, this.chatResource.countPrivateGroupChatMessages(null, countRequest));
	}

	@Test
	public void testPrivateGroupMessageCountIncludesMissingKeyMessagesAndRejectsInvalidRequests() throws Exception {
		PrivateGroupChatMessageCountRequest countRequest = new PrivateGroupChatMessageCountRequest();
		int groupId;
		int openGroupId;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			groupId = createClosedGroup(repository, alice, "chat-api-count-miss-key");
			openGroupId = GroupUtils.createGroup(repository, alice, "chat-api-private-count-open", true,
					ApprovalThreshold.ONE, 10, 40);
			addMember(repository, groupId, bob);
			addMember(repository, openGroupId, bob);

			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(
					repository, groupId);
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 50);
			byte[] keyId = PrivateGroupChatCrypto.computeKeyId(groupId, epoch.getEpochId(), groupKey);
			byte[] nonce = PrivateGroupChatCrypto.generateNonce();
			byte[] ciphertext = PrivateGroupChatCrypto.encryptMessage(groupKey, groupId, epoch.getEpochId(),
					keyId, nonce, "missing count key".getBytes(StandardCharsets.UTF_8));
			PrivateGroupChatEnvelope messageEnvelope = PrivateGroupChatEnvelope.message(groupId,
					epoch.getEpochId(), keyId, nonce, ciphertext);
			ChatTransactionData chatData = chat(alice, groupId, null, messageEnvelope.toBytes(), true, true,
					null, now());
			ChatTransaction chatTransaction = new ChatTransaction(repository, chatData);
			chatTransaction.computeNonce();
			chatTransaction.sign(alice);

			assertTrue(ChatService.getInstance().isSignatureValid(repository, chatData));
			assertEquals(ValidationResult.OK, ChatService.getInstance().validateAndStore(repository, chatData));

			countRequest.recipientPrivateKey = bob.getPrivateKey();
			countRequest.groupId = groupId;
		}

		PrivateGroupChatKeyCache.getInstance().clear();
		assertEquals(1, this.chatResource.countPrivateGroupChatMessages(null, countRequest));

		countRequest.groupId = openGroupId;
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.chatResource.countPrivateGroupChatMessages(null, countRequest));

		countRequest.groupId = groupId;
		countRequest.recipientPrivateKey = new byte[Transformer.PRIVATE_KEY_LENGTH - 1];
		assertApiError(ApiError.INVALID_PRIVATE_KEY,
				() -> this.chatResource.countPrivateGroupChatMessages(null, countRequest));
	}

	@Test
	public void testNormalChatViewsHidePrivateGroupControls() throws Exception {
		byte[] payload = "private api normal views".getBytes(StandardCharsets.UTF_8);
		PrivateGroupChatSendRequest sendRequest = new PrivateGroupChatSendRequest();
		PrivateGroupChatKeyRequestRequest keyRequest = new PrivateGroupChatKeyRequestRequest();
		String aliceAddress;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createClosedGroup(repository, alice, "chat-api-private-normal-views");
			addMember(repository, groupId, bob);

			sendRequest.senderPrivateKey = alice.getPrivateKey();
			sendRequest.groupId = groupId;
			sendRequest.data = payload;
			sendRequest.isText = true;
			keyRequest.requesterPrivateKey = bob.getPrivateKey();
			keyRequest.groupId = groupId;
			aliceAddress = alice.getAddress();
		}

		PrivateGroupChatSendResponse sendResponse = this.chatResource.sendPrivateGroupChat(null, sendRequest);
		keyRequest.keyId = sendResponse.keyId;
		PrivateGroupChatKeyRequestResponse keyRequestResponse = this.chatResource.requestPrivateGroupChatKey(null,
				keyRequest);

		List<ChatMessage> messages = this.chatResource.searchChat(
				null, null, sendRequest.groupId, Arrays.asList(), null, null, null,
				ChatMessage.Encoding.BASE64, null, null, null);

		assertEquals(1, messages.size());
		assertArrayEquals(sendResponse.messageSignature, messages.get(0).getSignature());

		int count = this.chatResource.countChatMessages(
				null, null, sendRequest.groupId, Arrays.asList(), null, null, null,
				ChatMessage.Encoding.BASE64, null, null, null);

		assertEquals(1, count);

		ActiveChats activeChats = this.chatResource.getActiveChats(aliceAddress, ChatMessage.Encoding.BASE64, null);
		ActiveChats.GroupChat groupChat = activeChats.getGroups().stream()
				.filter(activeGroupChat -> activeGroupChat.getGroupId() == sendRequest.groupId)
				.findFirst()
				.orElse(null);

		assertNotNull(groupChat);
		assertArrayEquals(sendResponse.messageSignature, groupChat.getSignature());

		ChatMessage keyRequestMessage = this.chatResource.getMessageBySignature(
				Base58.encode(keyRequestResponse.requestSignature), ChatMessage.Encoding.BASE64);
		assertArrayEquals(keyRequestResponse.requestSignature, keyRequestMessage.getSignature());
	}

	@Test
	public void testPrivateGroupActiveChatsReturnsLatestDecryptedMessageAndSkipsControls() throws Exception {
		byte[] firstPayload = "private active first".getBytes(StandardCharsets.UTF_8);
		byte[] secondPayload = "private active latest".getBytes(StandardCharsets.UTF_8);
		PrivateGroupChatSendRequest sendRequest = new PrivateGroupChatSendRequest();
		PrivateGroupChatKeyRequestRequest keyRequest = new PrivateGroupChatKeyRequestRequest();
		PrivateGroupChatActiveChatsRequest activeRequest = new PrivateGroupChatActiveChatsRequest();
		String groupName = "chat-api-private-active";

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createClosedGroup(repository, alice, groupName);
			addMember(repository, groupId, bob);

			sendRequest.senderPrivateKey = alice.getPrivateKey();
			sendRequest.groupId = groupId;
			sendRequest.isText = true;
			keyRequest.requesterPrivateKey = bob.getPrivateKey();
			keyRequest.groupId = groupId;
			activeRequest.recipientPrivateKey = bob.getPrivateKey();
			activeRequest.encoding = ChatMessage.Encoding.BASE64;
		}

		sendRequest.data = firstPayload;
		PrivateGroupChatSendResponse firstResponse = this.chatResource.sendPrivateGroupChat(null, sendRequest);
		sendRequest.data = secondPayload;
		sendRequest.chatReference = firstResponse.messageSignature;
		PrivateGroupChatSendResponse secondResponse = this.chatResource.sendPrivateGroupChat(null, sendRequest);
		keyRequest.keyId = secondResponse.keyId;
		this.chatResource.requestPrivateGroupChatKey(null, keyRequest);

		List<PrivateGroupChatActiveChatResponse> activeChats = this.chatResource.listPrivateGroupActiveChats(null,
				activeRequest);
		PrivateGroupChatActiveChatResponse activeChat = activeChats.stream()
				.filter(chat -> chat.groupId == sendRequest.groupId)
				.findFirst()
				.orElse(null);

		assertNotNull(activeChat);
		assertEquals(groupName, activeChat.groupName);
		assertEquals(PrivateGroupChatActiveChatResponse.Status.DECRYPTED, activeChat.status);
		assertEquals(Base64.toBase64String(secondPayload), activeChat.data);
		assertEquals(Boolean.TRUE, activeChat.isText);
		assertEquals(Boolean.TRUE, activeChat.isEncrypted);
		assertArrayEquals(secondResponse.messageSignature, activeChat.signature);
		assertArrayEquals(firstResponse.messageSignature, activeChat.chatReference);
		assertArrayEquals(secondResponse.epochId, activeChat.epochId);
		assertArrayEquals(secondResponse.keyId, activeChat.keyId);
	}

	@Test
	public void testPrivateGroupActiveChatsReportsMissingKeyNoMessagesAndExcludesOpenGroups() throws Exception {
		PrivateGroupChatActiveChatsRequest activeRequest = new PrivateGroupChatActiveChatsRequest();
		int missingKeyGroupId;
		int emptyGroupId;
		int openGroupId;
		byte[] missingEpochId;
		byte[] missingKeyId;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			missingKeyGroupId = createClosedGroup(repository, alice, "chat-api-active-missing-key");
			emptyGroupId = createClosedGroup(repository, alice, "chat-api-active-empty");
			openGroupId = GroupUtils.createGroup(repository, alice, "chat-api-active-open", true,
					ApprovalThreshold.ONE, 10, 40);
			addMember(repository, missingKeyGroupId, bob);
			addMember(repository, emptyGroupId, bob);
			addMember(repository, openGroupId, bob);

			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(
					repository, missingKeyGroupId);
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 40);
			missingKeyId = PrivateGroupChatCrypto.computeKeyId(missingKeyGroupId, epoch.getEpochId(), groupKey);
			byte[] nonce = PrivateGroupChatCrypto.generateNonce();
			byte[] ciphertext = PrivateGroupChatCrypto.encryptMessage(groupKey, missingKeyGroupId,
					epoch.getEpochId(), missingKeyId, nonce, "missing active key".getBytes(StandardCharsets.UTF_8));
			PrivateGroupChatEnvelope messageEnvelope = PrivateGroupChatEnvelope.message(missingKeyGroupId,
					epoch.getEpochId(), missingKeyId, nonce, ciphertext);
			ChatTransactionData chatData = chat(alice, missingKeyGroupId, null, messageEnvelope.toBytes(),
					true, true, null, now());
			ChatTransaction chatTransaction = new ChatTransaction(repository, chatData);
			chatTransaction.computeNonce();
			chatTransaction.sign(alice);

			assertTrue(ChatService.getInstance().isSignatureValid(repository, chatData));
			assertEquals(ValidationResult.OK, ChatService.getInstance().validateAndStore(repository, chatData));

			activeRequest.recipientPrivateKey = bob.getPrivateKey();
			activeRequest.encoding = ChatMessage.Encoding.BASE64;
			missingEpochId = epoch.getEpochId();
		}

		PrivateGroupChatKeyCache.getInstance().clear();
		List<PrivateGroupChatActiveChatResponse> activeChats = this.chatResource.listPrivateGroupActiveChats(null,
				activeRequest);

		PrivateGroupChatActiveChatResponse missingKeyChat = activeChats.stream()
				.filter(chat -> chat.groupId == missingKeyGroupId)
				.findFirst()
				.orElse(null);
		PrivateGroupChatActiveChatResponse emptyChat = activeChats.stream()
				.filter(chat -> chat.groupId == emptyGroupId)
				.findFirst()
				.orElse(null);

		assertNotNull(missingKeyChat);
		assertEquals(PrivateGroupChatActiveChatResponse.Status.MISSING_KEY, missingKeyChat.status);
		assertNull(missingKeyChat.data);
		assertEquals(Boolean.TRUE, missingKeyChat.isText);
		assertArrayEquals(missingEpochId, missingKeyChat.epochId);
		assertArrayEquals(missingKeyId, missingKeyChat.keyId);

		assertNotNull(emptyChat);
		assertEquals(PrivateGroupChatActiveChatResponse.Status.NO_MESSAGES, emptyChat.status);
		assertNull(emptyChat.timestamp);
		assertNull(emptyChat.data);
		assertNull(emptyChat.epochId);
		assertNull(emptyChat.keyId);

		assertTrue(activeChats.stream().noneMatch(chat -> chat.groupId == openGroupId));
	}

	@Test
	public void testPrivateGroupMessagesRehydrateCachedKeyFromStoredAnnouncement() throws Exception {
		byte[] payload = "private api inbox rehydrate".getBytes(StandardCharsets.UTF_8);
		PrivateGroupChatSendRequest sendRequest = new PrivateGroupChatSendRequest();
		PrivateGroupChatMessagesRequest messagesRequest = new PrivateGroupChatMessagesRequest();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createClosedGroup(repository, alice, "chat-api-private-inbox-rehydrate");
			addMember(repository, groupId, bob);

			sendRequest.senderPrivateKey = alice.getPrivateKey();
			sendRequest.groupId = groupId;
			sendRequest.data = payload;
			sendRequest.isText = true;
			messagesRequest.recipientPrivateKey = bob.getPrivateKey();
			messagesRequest.groupId = groupId;
			messagesRequest.encoding = ChatMessage.Encoding.BASE64;
		}

		this.chatResource.sendPrivateGroupChat(null, sendRequest);
		PrivateGroupChatKeyCache.getInstance().clear();

		List<PrivateGroupChatMessageResponse> messages = this.chatResource.listPrivateGroupChatMessages(null,
				messagesRequest);

		assertEquals(1, messages.size());
		assertEquals(PrivateGroupChatMessageResponse.Status.DECRYPTED, messages.get(0).status);
		assertEquals(Base64.toBase64String(payload), messages.get(0).data);
	}

	@Test
	public void testPrivateGroupMessagesReportMissingKey() throws Exception {
		PrivateGroupChatMessagesRequest messagesRequest = new PrivateGroupChatMessagesRequest();
		byte[] epochId;
		byte[] keyId;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createClosedGroup(repository, alice, "chat-api-inbox-miss-key");
			addMember(repository, groupId, bob);

			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
					groupId);
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 30);
			keyId = PrivateGroupChatCrypto.computeKeyId(groupId, epoch.getEpochId(), groupKey);
			byte[] nonce = PrivateGroupChatCrypto.generateNonce();
			byte[] ciphertext = PrivateGroupChatCrypto.encryptMessage(groupKey, groupId, epoch.getEpochId(),
					keyId, nonce, "missing key".getBytes(StandardCharsets.UTF_8));
			PrivateGroupChatEnvelope messageEnvelope = PrivateGroupChatEnvelope.message(groupId, epoch.getEpochId(),
					keyId, nonce, ciphertext);
			ChatTransactionData chatData = chat(alice, groupId, null, messageEnvelope.toBytes(), true, true,
					null, now());
			ChatTransaction chatTransaction = new ChatTransaction(repository, chatData);
			chatTransaction.computeNonce();
			chatTransaction.sign(alice);

			assertTrue(ChatService.getInstance().isSignatureValid(repository, chatData));
			assertEquals(ValidationResult.OK, ChatService.getInstance().validateAndStore(repository, chatData));

			messagesRequest.recipientPrivateKey = bob.getPrivateKey();
			messagesRequest.groupId = groupId;
			messagesRequest.encoding = ChatMessage.Encoding.BASE64;
			epochId = epoch.getEpochId();
		}

		PrivateGroupChatKeyCache.getInstance().clear();
		List<PrivateGroupChatMessageResponse> messages = this.chatResource.listPrivateGroupChatMessages(null,
				messagesRequest);

		assertEquals(1, messages.size());
		assertEquals(PrivateGroupChatMessageResponse.Status.MISSING_KEY, messages.get(0).status);
		assertNull(messages.get(0).data);
		assertArrayEquals(epochId, messages.get(0).epochId);
		assertArrayEquals(keyId, messages.get(0).keyId);
	}

	@Test
	public void testPrivateGroupMessagesDoNotExposePlaintextToNonMember() throws Exception {
		byte[] payload = "private api inbox nonmember".getBytes(StandardCharsets.UTF_8);
		PrivateGroupChatSendRequest sendRequest = new PrivateGroupChatSendRequest();
		PrivateGroupChatMessagesRequest messagesRequest = new PrivateGroupChatMessagesRequest();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			int groupId = createClosedGroup(repository, alice, "chat-api-private-inbox-nonmember");
			addMember(repository, groupId, bob);

			sendRequest.senderPrivateKey = alice.getPrivateKey();
			sendRequest.groupId = groupId;
			sendRequest.data = payload;
			sendRequest.isText = true;
			messagesRequest.recipientPrivateKey = chloe.getPrivateKey();
			messagesRequest.groupId = groupId;
			messagesRequest.encoding = ChatMessage.Encoding.BASE64;
		}

		this.chatResource.sendPrivateGroupChat(null, sendRequest);
		List<PrivateGroupChatMessageResponse> messages = this.chatResource.listPrivateGroupChatMessages(null,
				messagesRequest);

		assertEquals(1, messages.size());
		assertEquals(PrivateGroupChatMessageResponse.Status.MISSING_KEY, messages.get(0).status);
		assertNull(messages.get(0).data);
	}

	@Test
	public void testPrivateGroupKeyRequest() throws Exception {
		PrivateGroupChatKeyRequestRequest keyRequest = new PrivateGroupChatKeyRequestRequest();
		byte[] keyId = bytes(PrivateGroupChatEnvelope.KEY_ID_LENGTH, 1);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createClosedGroup(repository, alice, "chat-api-private-key-request");
			addMember(repository, groupId, bob);

			keyRequest.requesterPrivateKey = bob.getPrivateKey();
			keyRequest.groupId = groupId;
			keyRequest.keyId = keyId;
		}

		PrivateGroupChatKeyRequestResponse response = this.chatResource.requestPrivateGroupChatKey(null, keyRequest);

		assertNotNull(response.requestSignature);
		assertNotNull(response.epochId);
		assertArrayEquals(keyId, response.keyId);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChatTransactionData keyRequestData = repository.getChatStoreRepository().fromSignature(response.requestSignature);
			assertNotNull(keyRequestData);
			assertTrue(keyRequestData.getIsEncrypted());
			PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.fromBytes(keyRequestData.getData());
			assertEquals(PrivateGroupChatEnvelope.Type.KEY_REQUEST, envelope.getType());
			assertArrayEquals(response.epochId, envelope.getEpochId());
			assertArrayEquals(response.keyId, envelope.getKeyId());
		}
	}

	@Test
	public void testPrivateGroupKeyAnnouncementRelay() throws Exception {
		byte[] payload = "private api relay message".getBytes(StandardCharsets.UTF_8);
		PrivateGroupChatSendRequest sendRequest = new PrivateGroupChatSendRequest();
		PrivateGroupChatKeyAnnouncementRelayRequest relayRequest = new PrivateGroupChatKeyAnnouncementRelayRequest();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createClosedGroup(repository, alice, "chat-api-private-key-relay");
			addMember(repository, groupId, bob);

			sendRequest.senderPrivateKey = alice.getPrivateKey();
			sendRequest.groupId = groupId;
			sendRequest.data = payload;
			sendRequest.isText = true;
			relayRequest.relayerPrivateKey = bob.getPrivateKey();
			relayRequest.groupId = groupId;
		}

		PrivateGroupChatSendResponse sendResponse = this.chatResource.sendPrivateGroupChat(null, sendRequest);
		relayRequest.epochId = sendResponse.epochId;
		relayRequest.keyId = sendResponse.keyId;

		PrivateGroupChatKeyAnnouncementRelayResponse relayResponse =
				this.chatResource.relayPrivateGroupChatKeyAnnouncement(null, relayRequest);

		assertNotNull(relayResponse.announcementSignature);
		assertArrayEquals(sendResponse.epochId, relayResponse.epochId);
		assertArrayEquals(sendResponse.keyId, relayResponse.keyId);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChatTransactionData originalAnnouncementData = repository.getChatStoreRepository().fromSignature(
					sendResponse.keyAnnouncementSignature);
			ChatTransactionData relayData = repository.getChatStoreRepository().fromSignature(
					relayResponse.announcementSignature);
			assertNotNull(relayData);
			assertTrue(relayData.getIsEncrypted());
			assertArrayEquals(originalAnnouncementData.getData(), relayData.getData());
			PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.fromBytes(relayData.getData());
			assertEquals(PrivateGroupChatEnvelope.Type.KEY_ANNOUNCEMENT, envelope.getType());
			assertArrayEquals(relayResponse.keyId, envelope.getKeyId());
		}
	}

	@Test
	public void testPrivateGroupKeyRequestRecovery() throws Exception {
		byte[] payload = "private api recovery message".getBytes(StandardCharsets.UTF_8);
		PrivateGroupChatSendRequest sendRequest = new PrivateGroupChatSendRequest();
		PrivateGroupChatKeyRequestRequest keyRequest = new PrivateGroupChatKeyRequestRequest();
		PrivateGroupChatKeyRequestRecoveryRequest recoveryRequest = new PrivateGroupChatKeyRequestRecoveryRequest();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createClosedGroup(repository, alice, "chat-api-private-key-recovery");
			addMember(repository, groupId, bob);

			sendRequest.senderPrivateKey = alice.getPrivateKey();
			sendRequest.groupId = groupId;
			sendRequest.data = payload;
			sendRequest.isText = true;
			keyRequest.requesterPrivateKey = bob.getPrivateKey();
			keyRequest.groupId = groupId;
			recoveryRequest.relayerPrivateKey = alice.getPrivateKey();
			recoveryRequest.groupId = groupId;
		}

		PrivateGroupChatSendResponse sendResponse = this.chatResource.sendPrivateGroupChat(null, sendRequest);
		keyRequest.keyId = sendResponse.keyId;
		PrivateGroupChatKeyRequestResponse keyRequestResponse = this.chatResource.requestPrivateGroupChatKey(null,
				keyRequest);

		List<PrivateGroupChatKeyRequestRecoveryResponse> recoveryResponses =
				this.chatResource.resolvePrivateGroupChatKeyRequests(null, recoveryRequest);

		assertEquals(1, recoveryResponses.size());
		PrivateGroupChatKeyRequestRecoveryResponse recoveryResponse = recoveryResponses.get(0);
		assertEquals(org.qortium.chat.PrivateGroupChatService.KeyRequestRecoveryStatus.RELAYED,
				recoveryResponse.status);
		assertArrayEquals(keyRequestResponse.requestSignature, recoveryResponse.requestSignature);
		assertArrayEquals(sendResponse.keyId, recoveryResponse.requestedKeyId);
		assertArrayEquals(sendResponse.keyId, recoveryResponse.relayedKeyId);
		assertNotNull(recoveryResponse.announcementSignature);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChatTransactionData originalAnnouncementData = repository.getChatStoreRepository().fromSignature(
					sendResponse.keyAnnouncementSignature);
			ChatTransactionData relayedAnnouncementData = repository.getChatStoreRepository().fromSignature(
					recoveryResponse.announcementSignature);
			assertNotNull(relayedAnnouncementData);
			assertTrue(relayedAnnouncementData.getIsEncrypted());
			assertArrayEquals(originalAnnouncementData.getData(), relayedAnnouncementData.getData());
			PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.fromBytes(relayedAnnouncementData.getData());
			assertEquals(PrivateGroupChatEnvelope.Type.KEY_ANNOUNCEMENT, envelope.getType());
			assertArrayEquals(sendResponse.keyId, envelope.getKeyId());
		}
	}

	@Test
	public void testPrivateGroupWorkflowRecoversMissingKeyEndToEnd() throws Exception {
		byte[] payload = "private api workflow recovery".getBytes(StandardCharsets.UTF_8);
		PrivateGroupChatActiveChatsRequest activeRequest = new PrivateGroupChatActiveChatsRequest();
		PrivateGroupChatMessageCountRequest countRequest = new PrivateGroupChatMessageCountRequest();
		PrivateGroupChatMessagesRequest messagesRequest = new PrivateGroupChatMessagesRequest();
		PrivateGroupChatKeyRequestRequest keyRequest = new PrivateGroupChatKeyRequestRequest();
		PrivateGroupChatKeyRequestRecoveryRequest recoveryRequest = new PrivateGroupChatKeyRequestRecoveryRequest();
		byte[] groupKey;
		byte[] epochId;
		byte[] keyId;
		int groupId;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			groupId = createClosedGroup(repository, alice, "chat-api-workflow-rec");
			addMember(repository, groupId, bob);

			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(
					repository, groupId);
			groupKey = bytes(Transformer.AES256_LENGTH, 60);
			keyId = PrivateGroupChatCrypto.computeKeyId(groupId, epoch.getEpochId(), groupKey);
			byte[] nonce = PrivateGroupChatCrypto.generateNonce();
			byte[] ciphertext = PrivateGroupChatCrypto.encryptMessage(groupKey, groupId, epoch.getEpochId(),
					keyId, nonce, payload);
			PrivateGroupChatEnvelope messageEnvelope = PrivateGroupChatEnvelope.message(groupId,
					epoch.getEpochId(), keyId, nonce, ciphertext);
			ChatTransactionData chatData = chat(alice, groupId, null, messageEnvelope.toBytes(), true, true,
					null, now());
			ChatTransaction chatTransaction = new ChatTransaction(repository, chatData);
			chatTransaction.computeNonce();
			chatTransaction.sign(alice);

			assertTrue(ChatService.getInstance().isSignatureValid(repository, chatData));
			assertEquals(ValidationResult.OK, ChatService.getInstance().validateAndStore(repository, chatData));

			activeRequest.recipientPrivateKey = bob.getPrivateKey();
			activeRequest.encoding = ChatMessage.Encoding.BASE64;
			countRequest.recipientPrivateKey = bob.getPrivateKey();
			countRequest.groupId = groupId;
			messagesRequest.recipientPrivateKey = bob.getPrivateKey();
			messagesRequest.groupId = groupId;
			messagesRequest.encoding = ChatMessage.Encoding.BASE64;
			keyRequest.requesterPrivateKey = bob.getPrivateKey();
			keyRequest.groupId = groupId;
			keyRequest.keyId = keyId;
			recoveryRequest.relayerPrivateKey = alice.getPrivateKey();
			recoveryRequest.groupId = groupId;
			epochId = epoch.getEpochId();
		}

		PrivateGroupChatKeyCache.getInstance().clear();

		List<PrivateGroupChatActiveChatResponse> activeChats = this.chatResource.listPrivateGroupActiveChats(null,
				activeRequest);
		PrivateGroupChatActiveChatResponse activeChat = activeChats.stream()
				.filter(chat -> chat.groupId == groupId)
				.findFirst()
				.orElse(null);

		assertNotNull(activeChat);
		assertEquals(PrivateGroupChatActiveChatResponse.Status.MISSING_KEY, activeChat.status);
		assertArrayEquals(epochId, activeChat.epochId);
		assertArrayEquals(keyId, activeChat.keyId);
		assertEquals(1, this.chatResource.countPrivateGroupChatMessages(null, countRequest));

		List<PrivateGroupChatMessageResponse> missingMessages = this.chatResource.listPrivateGroupChatMessages(null,
				messagesRequest);
		assertEquals(1, missingMessages.size());
		assertEquals(PrivateGroupChatMessageResponse.Status.MISSING_KEY, missingMessages.get(0).status);
		assertArrayEquals(epochId, missingMessages.get(0).epochId);
		assertArrayEquals(keyId, missingMessages.get(0).keyId);

		PrivateGroupChatKeyRequestResponse keyRequestResponse = this.chatResource.requestPrivateGroupChatKey(null,
				keyRequest);
		assertArrayEquals(epochId, keyRequestResponse.epochId);
		assertArrayEquals(keyId, keyRequestResponse.keyId);

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(
					repository, groupId);
			PrivateGroupChatEnvelope keyAnnouncement = PrivateGroupChatKeyAnnouncement.create(epoch, groupKey,
					alice.getPrivateKey());
			PrivateGroupChatKeyCache.getInstance().putLocal(epoch, keyAnnouncement, groupKey);
		}

		List<PrivateGroupChatKeyRequestRecoveryResponse> recoveryResponses =
				this.chatResource.resolvePrivateGroupChatKeyRequests(null, recoveryRequest);
		assertEquals(1, recoveryResponses.size());
		PrivateGroupChatKeyRequestRecoveryResponse recoveryResponse = recoveryResponses.get(0);
		assertEquals(org.qortium.chat.PrivateGroupChatService.KeyRequestRecoveryStatus.RELAYED,
				recoveryResponse.status);
		assertArrayEquals(keyRequestResponse.requestSignature, recoveryResponse.requestSignature);
		assertArrayEquals(keyId, recoveryResponse.requestedKeyId);
		assertArrayEquals(keyId, recoveryResponse.relayedKeyId);
		assertNotNull(recoveryResponse.announcementSignature);

		PrivateGroupChatKeyCache.getInstance().clear();
		List<PrivateGroupChatMessageResponse> recoveredMessages = this.chatResource.listPrivateGroupChatMessages(null,
				messagesRequest);

		assertEquals(1, recoveredMessages.size());
		PrivateGroupChatMessageResponse recoveredMessage = recoveredMessages.get(0);
		assertEquals(PrivateGroupChatMessageResponse.Status.DECRYPTED, recoveredMessage.status);
		assertEquals(Base64.toBase64String(payload), recoveredMessage.data);
		assertArrayEquals(epochId, recoveredMessage.epochId);
		assertArrayEquals(keyId, recoveredMessage.keyId);
	}

	@Test
	public void testPrivateGroupHistoricalKeyRequestUsesSuppliedEpoch() throws Exception {
		byte[] payload = "private api historical recovery".getBytes(StandardCharsets.UTF_8);
		PrivateGroupChatMessagesRequest messagesRequest = new PrivateGroupChatMessagesRequest();
		PrivateGroupChatKeyRequestRequest keyRequest = new PrivateGroupChatKeyRequestRequest();
		byte[] epochId;
		byte[] keyId;
		int groupId;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			TestAccount chloe = Common.getTestAccount(repository, "chloe");
			groupId = createClosedGroup(repository, alice, "chat-api-history-req");
			addMember(repository, groupId, bob);

			PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(
					repository, groupId);
			byte[] groupKey = bytes(Transformer.AES256_LENGTH, 80);
			keyId = PrivateGroupChatCrypto.computeKeyId(groupId, epoch.getEpochId(), groupKey);
			byte[] nonce = PrivateGroupChatCrypto.generateNonce();
			byte[] ciphertext = PrivateGroupChatCrypto.encryptMessage(groupKey, groupId, epoch.getEpochId(),
					keyId, nonce, payload);
			PrivateGroupChatEnvelope messageEnvelope = PrivateGroupChatEnvelope.message(groupId,
					epoch.getEpochId(), keyId, nonce, ciphertext);
			ChatTransactionData chatData = chat(alice, groupId, null, messageEnvelope.toBytes(), true, true,
					null, now());
			ChatTransaction chatTransaction = new ChatTransaction(repository, chatData);
			chatTransaction.computeNonce();
			chatTransaction.sign(alice);

			assertEquals(ValidationResult.OK, ChatService.getInstance().validateAndStore(repository, chatData));
			addMember(repository, groupId, chloe);

			messagesRequest.recipientPrivateKey = bob.getPrivateKey();
			messagesRequest.groupId = groupId;
			messagesRequest.encoding = ChatMessage.Encoding.BASE64;
			keyRequest.requesterPrivateKey = bob.getPrivateKey();
			keyRequest.groupId = groupId;
			keyRequest.epochId = epoch.getEpochId();
			keyRequest.keyId = keyId;
			epochId = epoch.getEpochId();
		}

		PrivateGroupChatKeyCache.getInstance().clear();
		List<PrivateGroupChatMessageResponse> messages = this.chatResource.listPrivateGroupChatMessages(null,
				messagesRequest);
		assertEquals(1, messages.size());
		assertEquals(PrivateGroupChatMessageResponse.Status.MISSING_KEY, messages.get(0).status);
		assertArrayEquals(epochId, messages.get(0).epochId);
		assertArrayEquals(keyId, messages.get(0).keyId);

		PrivateGroupChatKeyRequestResponse response = this.chatResource.requestPrivateGroupChatKey(null, keyRequest);
		assertArrayEquals(epochId, response.epochId);
		assertArrayEquals(keyId, response.keyId);

		keyRequest.keyId = null;
		assertApiError(ApiError.INVALID_CRITERIA,
				() -> this.chatResource.requestPrivateGroupChatKey(null, keyRequest));
	}

	@Test
	public void testPrivateGroupRotateKey() throws Exception {
		PrivateGroupChatRotateRequest rotateRequest = new PrivateGroupChatRotateRequest();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createClosedGroup(repository, alice, "chat-api-private-key-rotate");
			addMember(repository, groupId, bob);

			rotateRequest.rotatorPrivateKey = alice.getPrivateKey();
			rotateRequest.groupId = groupId;
		}

		PrivateGroupChatRotateResponse rotateResponse = this.chatResource.rotatePrivateGroupChatKey(null, rotateRequest);

		assertNotNull(rotateResponse.keyAnnouncementSignature);
		assertNotNull(rotateResponse.epochId);
		assertNotNull(rotateResponse.keyId);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChatTransactionData keyAnnouncementData = repository.getChatStoreRepository().fromSignature(
					rotateResponse.keyAnnouncementSignature);
			assertNotNull(keyAnnouncementData);
			assertTrue(keyAnnouncementData.getIsEncrypted());
			PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.fromBytes(keyAnnouncementData.getData());
			assertEquals(PrivateGroupChatEnvelope.Type.KEY_ANNOUNCEMENT, envelope.getType());
			assertArrayEquals(rotateResponse.epochId, envelope.getEpochId());
			assertArrayEquals(rotateResponse.keyId, envelope.getKeyId());
		}
	}

	@Test
	public void testPrivateGroupRotationRequest() throws Exception {
		PrivateGroupChatRotationRequestRequest rotationRequest = new PrivateGroupChatRotationRequestRequest();

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			int groupId = createClosedGroup(repository, alice, "chat-api-rotation-req");
			addMember(repository, groupId, bob);

			rotationRequest.requesterPrivateKey = alice.getPrivateKey();
			rotationRequest.groupId = groupId;
		}

		PrivateGroupChatRotationRequestResponse rotationResponse =
				this.chatResource.requestPrivateGroupChatRotation(null, rotationRequest);

		assertNotNull(rotationResponse.requestSignature);
		assertNotNull(rotationResponse.epochId);

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChatTransactionData rotationRequestData = repository.getChatStoreRepository().fromSignature(
					rotationResponse.requestSignature);
			assertNotNull(rotationRequestData);
			assertTrue(rotationRequestData.getIsEncrypted());
			PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.fromBytes(rotationRequestData.getData());
			assertEquals(PrivateGroupChatEnvelope.Type.ROTATION_REQUEST, envelope.getType());
			assertArrayEquals(rotationResponse.epochId, envelope.getEpochId());
		}
	}

	@Test
	public void testBuildChatUsesDedicatedServiceWithoutStoring() throws DataException, TransformationException {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = chat(alice, Group.NO_GROUP, null, "build only", null, now());
		}

		String rawChat = this.chatResource.buildChat(null, chatData);
		ChatTransactionData decodedChatData = decodeUnsignedChat(rawChat);

		assertEquals(chatData.getTimestamp(), decodedChatData.getTimestamp());
		assertEquals(chatData.getTxGroupId(), decodedChatData.getTxGroupId());
		assertArrayEquals(chatData.getSenderPublicKey(), decodedChatData.getSenderPublicKey());
		assertEquals(chatData.getSender(), decodedChatData.getSender());
		assertEquals(chatData.getRecipient(), decodedChatData.getRecipient());
		assertArrayEquals(chatData.getData(), decodedChatData.getData());
		assertEquals(chatData.getNonce(), decodedChatData.getNonce());
		assertNull(decodedChatData.getSignature());

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertEquals(0, repository.getChatStoreRepository().countMessagesMatchingCriteria(
					null, null, Group.NO_GROUP, null, null, Arrays.asList(), null));
			assertTrue(repository.getTransactionRepository().getUnconfirmedTransactions().isEmpty());
		}
	}

	@Test
	public void testBuildChatAcceptsTypedAndEndpointJsonPayloads() throws Exception {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = chat(alice, Group.NO_GROUP, null, "typed build", null, now());
		}

		String typedJson = marshalChat(chatData);
		assertTrue(typedJson.contains("\"type\":\"CHAT\""));

		ChatTransactionData typedChatData = unmarshalChat(typedJson);
		assertBuildChatMatches(chatData, typedChatData);

		String endpointJson = removeType(typedJson);
		ChatTransactionData endpointChatData = unmarshalChat(endpointJson);
		assertBuildChatMatches(chatData, endpointChatData);
	}

	@Test
	public void testBuildChatUsesChatStoreBackedRateLimit() throws Exception {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			FieldUtils.writeField(Settings.getInstance(), "maxRecentChatMessagesPerAccount", 1, true);

			TestAccount alice = Common.getTestAccount(repository, "alice");
			storeChat(repository, alice, Group.NO_GROUP, null, "existing", signature(9), now());
			chatData = chat(alice, Group.NO_GROUP, null, "rate limited", null, now() + 1);
		}

		assertApiError(ApiError.TRANSACTION_INVALID, () -> this.chatResource.buildChat(null, chatData));
	}

	@Test
	public void testComputeChatUsesDedicatedServiceNonce() throws DataException, TransformationException {
		ChatTransactionData chatData;
		TestAccount alice;

		try (final Repository repository = RepositoryManager.getRepository()) {
			alice = Common.getTestAccount(repository, "alice");
			chatData = chat(alice, Group.NO_GROUP, null, "compute nonce", null, now());
		}

		String computedRawChat = this.chatResource.buildChat(null, rawUnsignedChat(chatData));
		ChatTransactionData computedChatData = decodeUnsignedChat(computedRawChat);

		assertArrayEquals(chatData.getData(), computedChatData.getData());
		assertNull(computedChatData.getSignature());

		try (final Repository repository = RepositoryManager.getRepository()) {
			new ChatTransaction(repository, computedChatData).sign(alice);

			assertTrue(ChatService.getInstance().isSignatureValid(repository, computedChatData));
			assertNull(repository.getChatStoreRepository().fromSignature(computedChatData.getSignature()));
			assertTrue(repository.getTransactionRepository().getUnconfirmedTransactions().isEmpty());
		}
	}

	@Test
	public void testComputeChatRejectsInvalidDataLengthThroughDedicatedService() throws DataException, TransformationException {
		ChatTransactionData chatData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			chatData = chat(alice, Group.NO_GROUP, null, new byte[0], null, now());
		}

		String rawChat = rawUnsignedChat(chatData);
		assertApiError(ApiError.TRANSACTION_INVALID, () -> this.chatResource.buildChat(null, rawChat));
	}

	@Test
	public void testComputeChatRejectsNonChatTransactionData() throws DataException, TransformationException {
		PaymentTransactionData paymentData;

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestAccount alice = Common.getTestAccount(repository, "alice");
			TestAccount bob = Common.getTestAccount(repository, "bob");
			paymentData = new PaymentTransactionData(TestTransaction.generateBase(alice), bob.getAddress(), 1L);
		}

		String rawPayment = rawUnsignedTransaction(paymentData);
		assertApiError(ApiError.INVALID_DATA, () -> this.chatResource.buildChat(null, rawPayment));
	}

	private void assertBuildChatMatches(ChatTransactionData expectedChatData, ChatTransactionData requestChatData)
			throws TransformationException {
		String rawChat = this.chatResource.buildChat(null, requestChatData);
		ChatTransactionData decodedChatData = decodeUnsignedChat(rawChat);

		assertEquals(expectedChatData.getTimestamp(), decodedChatData.getTimestamp());
		assertEquals(expectedChatData.getTxGroupId(), decodedChatData.getTxGroupId());
		assertArrayEquals(expectedChatData.getSenderPublicKey(), decodedChatData.getSenderPublicKey());
		assertEquals(expectedChatData.getSender(), decodedChatData.getSender());
		assertEquals(expectedChatData.getRecipient(), decodedChatData.getRecipient());
		assertArrayEquals(expectedChatData.getData(), decodedChatData.getData());
		assertEquals(expectedChatData.getNonce(), decodedChatData.getNonce());
		assertNull(decodedChatData.getSignature());
	}

	private static String marshalChat(ChatTransactionData chatData) throws JAXBException {
		JAXBContext context = JAXBContextFactory.createContext(new Class[] {TransactionData.class}, null);
		Marshaller marshaller = context.createMarshaller();
		marshaller.setProperty(MarshallerProperties.MEDIA_TYPE, "application/json");
		marshaller.setProperty(MarshallerProperties.JSON_INCLUDE_ROOT, false);

		StringWriter writer = new StringWriter();
		marshaller.marshal(chatData, writer);
		return writer.toString();
	}

	private static ChatTransactionData unmarshalChat(String json) throws JAXBException {
		JAXBContext context = JAXBContextFactory.createContext(new Class[] {ChatTransactionData.class}, null);
		Unmarshaller unmarshaller = context.createUnmarshaller();
		unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, "application/json");
		unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, false);

		return unmarshaller.unmarshal(new StreamSource(new StringReader(json)), ChatTransactionData.class).getValue();
	}

	private static String removeType(String json) throws Exception {
		Map<String, Object> values = JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
		values.remove("type");
		return JSON.writeValueAsString(values);
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

	private static int createClosedGroup(Repository repository, TestAccount owner, String groupName) throws DataException {
		return GroupUtils.createGroup(repository, owner, groupName, false, ApprovalThreshold.ONE, 10, 40);
	}

	private static void addMember(Repository repository, int groupId, TestAccount account) throws DataException {
		account.ensureAccount();

		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
		repository.getGroupRepository().save(new GroupMemberData(groupId, account.getAddress(),
				groupData.getCreated(), groupData.getReference()));
		repository.saveChanges();
	}

	private static ChatTransactionData chat(TestAccount sender, int groupId, String recipient, String message, byte[] signature, long timestamp) {
		return chat(sender, groupId, recipient, message.getBytes(StandardCharsets.UTF_8), signature, timestamp);
	}

	private static ChatTransactionData chat(TestAccount sender, int groupId, String recipient, byte[] data, byte[] signature, long timestamp) {
		return chat(sender, groupId, recipient, data, true, false, signature, timestamp);
	}

	private static ChatTransactionData chat(TestAccount sender, int groupId, String recipient, byte[] data,
			boolean isText, boolean isEncrypted, byte[] signature, long timestamp) {
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

	private static String rawUnsignedChat(ChatTransactionData chatData) throws TransformationException {
		return Base58.encode(ChatTransactionTransformer.toBytes(chatData));
	}

	private static String rawUnsignedTransaction(TransactionData transactionData) throws TransformationException {
		return Base58.encode(TransactionTransformer.toBytes(transactionData));
	}

	private static ChatTransactionData decodeUnsignedChat(String rawBytes58) throws TransformationException {
		byte[] rawBytes = Base58.decode(rawBytes58);
		rawBytes = Bytes.concat(rawBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]);

		TransactionData transactionData = TransactionTransformer.fromBytes(rawBytes);
		transactionData.setSignature(null);

		return (ChatTransactionData) transactionData;
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

	private static byte[] bytes(int length, int seed) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; ++i)
			bytes[i] = (byte) (seed + i);

		return bytes;
	}

}
