package org.qortal.test.api;

import com.google.common.primitives.Bytes;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.bouncycastle.util.encoders.Base64;
import org.junit.Before;
import org.junit.Test;
import org.qortal.api.ApiError;
import org.qortal.api.model.PrivateGroupChatDecryptRequest;
import org.qortal.api.model.PrivateGroupChatDecryptResponse;
import org.qortal.api.model.PrivateGroupChatKeyAnnouncementRelayRequest;
import org.qortal.api.model.PrivateGroupChatKeyAnnouncementRelayResponse;
import org.qortal.api.model.PrivateGroupChatKeyRequestRequest;
import org.qortal.api.model.PrivateGroupChatKeyRequestResponse;
import org.qortal.api.model.PrivateGroupChatMessageResponse;
import org.qortal.api.model.PrivateGroupChatMessagesRequest;
import org.qortal.api.model.PrivateGroupChatRotateRequest;
import org.qortal.api.model.PrivateGroupChatRotateResponse;
import org.qortal.api.model.PrivateGroupChatRotationRequestRequest;
import org.qortal.api.model.PrivateGroupChatRotationRequestResponse;
import org.qortal.api.model.PrivateGroupChatSendRequest;
import org.qortal.api.model.PrivateGroupChatSendResponse;
import org.qortal.api.resource.ChatResource;
import org.qortal.chat.ChatService;
import org.qortal.chat.crypto.PrivateGroupChatCrypto;
import org.qortal.chat.crypto.PrivateGroupChatEnvelope;
import org.qortal.chat.crypto.PrivateGroupChatKeyCache;
import org.qortal.chat.crypto.PrivateGroupChatMembership;
import org.qortal.data.chat.ActiveChats;
import org.qortal.data.chat.ChatMessage;
import org.qortal.data.group.GroupData;
import org.qortal.data.group.GroupMemberData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.group.Group.ApprovalThreshold;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.Common;
import org.qortal.test.common.GroupUtils;
import org.qortal.test.common.TestAccount;
import org.qortal.test.common.TransactionUtils;
import org.qortal.test.common.transaction.TestTransaction;
import org.qortal.transaction.ChatTransaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.transaction.ChatTransactionTransformer;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChatResourceTests extends ApiCommon {

	private ChatResource chatResource;

	@Before
	public void buildResource() {
		this.chatResource = (ChatResource) ApiCommon.buildResource(ChatResource.class);
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
			int groupId = createClosedGroup(repository, alice, "chat-api-private-inbox-missing-key");
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
			int groupId = createClosedGroup(repository, alice, "chat-api-private-rotation-request");
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
