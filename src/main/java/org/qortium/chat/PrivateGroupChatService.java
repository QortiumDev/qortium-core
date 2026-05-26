package org.qortium.chat;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.chat.crypto.PrivateGroupChatCrypto;
import org.qortium.chat.crypto.PrivateGroupChatEnvelope;
import org.qortium.chat.crypto.PrivateGroupChatKeyAnnouncement;
import org.qortium.chat.crypto.PrivateGroupChatKeyCache;
import org.qortium.chat.crypto.PrivateGroupChatKeyRequest;
import org.qortium.chat.crypto.PrivateGroupChatMembership;
import org.qortium.chat.crypto.PrivateGroupChatRotationRequest;
import org.qortium.controller.ChatNotifier;
import org.qortium.controller.Controller;
import org.qortium.crypto.Crypto;
import org.qortium.data.chat.ChatMessage;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transaction.ChatTransaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.transform.TransformationException;
import org.qortium.transform.Transformer;
import org.qortium.utils.NTP;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class PrivateGroupChatService {

	private static final PrivateGroupChatService INSTANCE = new PrivateGroupChatService();
	private static final int BLOCKCHAIN_LOCK_TIMEOUT_SECONDS = 60;

	public static PrivateGroupChatService getInstance() {
		return INSTANCE;
	}

	private PrivateGroupChatService() {
	}

	public SendResult send(Repository repository, byte[] senderPrivateKey, int groupId, byte[] data,
			boolean isText, byte[] chatReference) throws DataException, GeneralSecurityException,
			TransformationException, PrivateGroupChatException, ValidationException {
		validatePrivateKey(senderPrivateKey, "sender private key");
		if (data == null || data.length == 0)
			throw new PrivateGroupChatException("Private group chat message data is missing");

		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		boolean locked;
		try {
			locked = blockchainLock.tryLock(BLOCKCHAIN_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ValidationException(ValidationResult.NO_BLOCKCHAIN_LOCK, e);
		}

		if (!locked)
			throw new ValidationException(ValidationResult.NO_BLOCKCHAIN_LOCK);

		try {
			return this.doSend(repository, senderPrivateKey, groupId, data, isText, chatReference);
		} finally {
			blockchainLock.unlock();
		}
	}

	public DecryptResult decrypt(Repository repository, byte[] recipientPrivateKey, byte[] messageSignature)
			throws DataException, GeneralSecurityException, TransformationException, PrivateGroupChatException {
		validatePrivateKey(recipientPrivateKey, "recipient private key");
		if (messageSignature == null || messageSignature.length != Transformer.SIGNATURE_LENGTH)
			throw new PrivateGroupChatException("Private group chat message signature is invalid");

		ChatTransactionData chatTransactionData = repository.getChatStoreRepository().fromSignature(messageSignature);
		if (chatTransactionData == null)
			throw new PrivateGroupChatException("Private group chat message was not found");

		PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.fromBytes(chatTransactionData.getData());
		if (envelope.getType() != PrivateGroupChatEnvelope.Type.MESSAGE)
			throw new PrivateGroupChatException("Private group chat transaction is not an encrypted message");

		return decryptMessageData(repository, recipientPrivateKey, chatTransactionData, envelope);
	}

	public List<ListMessageResult> listMessages(Repository repository, byte[] recipientPrivateKey, int groupId,
			Long before, Long after, byte[] chatReference, Boolean hasChatReference, String sender,
			ChatMessage.Encoding encoding, Integer limit, Integer offset, Boolean reverse)
			throws DataException {
		validatePrivateKey(recipientPrivateKey, "recipient private key");

		List<ListedMessageData> listedMessages = listMatchingPrivateMessages(repository, groupId, before, after,
				chatReference, hasChatReference, sender);
		sortListedMessages(listedMessages, reverse);

		int fromIndex = Math.max(offset == null ? 0 : offset, 0);
		if (fromIndex >= listedMessages.size())
			return new ArrayList<>();

		int toIndex = listedMessages.size();
		if (limit != null && limit > 0)
			toIndex = Math.min(fromIndex + limit, listedMessages.size());

		List<ListMessageResult> results = new ArrayList<>();
		for (ListedMessageData listedMessage : listedMessages.subList(fromIndex, toIndex)) {
			ChatMessage message = repository.getChatStoreRepository().toChatMessage(listedMessage.chatTransactionData,
					encoding);

			try {
				DecryptResult decryptResult = decryptMessageData(repository, recipientPrivateKey,
						listedMessage.chatTransactionData, listedMessage.envelope);
				results.add(ListMessageResult.decrypted(message, decryptResult));
			} catch (PrivateGroupChatException | GeneralSecurityException e) {
				results.add(ListMessageResult.missingKey(message, listedMessage.envelope.getEpochId(),
						listedMessage.envelope.getKeyId(), listedMessage.chatTransactionData.getIsText()));
			}
		}

		return results;
	}

	public int countMessages(Repository repository, byte[] recipientPrivateKey, int groupId, Long before,
			Long after, byte[] chatReference, Boolean hasChatReference, String sender) throws DataException {
		validatePrivateKey(recipientPrivateKey, "recipient private key");

		return listMatchingPrivateMessages(repository, groupId, before, after, chatReference, hasChatReference,
				sender).size();
	}

	public List<ActiveChatResult> listActiveChats(Repository repository, byte[] recipientPrivateKey,
			ChatMessage.Encoding encoding) throws DataException {
		validatePrivateKey(recipientPrivateKey, "recipient private key");

		PrivateKeyAccount recipient = new PrivateKeyAccount(repository, recipientPrivateKey);
		List<GroupData> groups = repository.getGroupRepository().getGroupsWithMember(recipient.getAddress());

		List<ActiveChatResult> results = new ArrayList<>();
		for (GroupData groupData : groups) {
			if (groupData.isOpen())
				continue;

			ListedMessageData latestMessage = latestPrivateMessage(repository, groupData.getGroupId());
			if (latestMessage == null) {
				results.add(ActiveChatResult.noMessages(groupData));
				continue;
			}

			ChatMessage message = repository.getChatStoreRepository().toChatMessage(latestMessage.chatTransactionData,
					encoding);

			try {
				DecryptResult decryptResult = decryptMessageData(repository, recipientPrivateKey,
						latestMessage.chatTransactionData, latestMessage.envelope);
				results.add(ActiveChatResult.decrypted(groupData, message, decryptResult));
			} catch (PrivateGroupChatException | GeneralSecurityException e) {
				results.add(ActiveChatResult.missingKey(groupData, message, latestMessage.envelope.getEpochId(),
						latestMessage.envelope.getKeyId(), latestMessage.chatTransactionData.getIsText()));
			}
		}

		results.sort(PrivateGroupChatService::compareActiveChats);
		return results;
	}

	private static ListedMessageData latestPrivateMessage(Repository repository, int groupId) throws DataException {
		for (ChatTransactionData chatTransactionData : repository.getChatStoreRepository().getGroupMessages(groupId)) {
			ListedMessageData listedMessage = toListedPrivateMessage(chatTransactionData, groupId);
			if (listedMessage != null)
				return listedMessage;
		}

		return null;
	}

	private static List<ListedMessageData> listMatchingPrivateMessages(Repository repository, int groupId,
			Long before, Long after, byte[] chatReference, Boolean hasChatReference, String sender) throws DataException {
		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
		if (groupData == null)
			throw new IllegalArgumentException("group does not exist");
		if (groupData.isOpen())
			throw new IllegalArgumentException("group is not closed");

		List<ListedMessageData> listedMessages = new ArrayList<>();
		for (ChatTransactionData chatTransactionData : repository.getChatStoreRepository().getGroupMessages(groupId)) {
			if (!matchesListCriteria(chatTransactionData, before, after, chatReference, hasChatReference, sender))
				continue;

			ListedMessageData listedMessage = toListedPrivateMessage(chatTransactionData, groupId);
			if (listedMessage != null)
				listedMessages.add(listedMessage);
		}

		return listedMessages;
	}

	private static ListedMessageData toListedPrivateMessage(ChatTransactionData chatTransactionData, int groupId) {
		if (!chatTransactionData.getIsEncrypted())
			return null;

		PrivateGroupChatEnvelope envelope;
		try {
			envelope = PrivateGroupChatEnvelope.fromBytes(chatTransactionData.getData());
		} catch (TransformationException e) {
			return null;
		}

		if (envelope.getType() != PrivateGroupChatEnvelope.Type.MESSAGE)
			return null;

		if (envelope.getGroupId() != groupId)
			return null;

		return new ListedMessageData(chatTransactionData, envelope);
	}

	private static void sortListedMessages(List<ListedMessageData> listedMessages, Boolean reverse) {
		Comparator<ListedMessageData> comparator = Comparator
				.comparingLong((ListedMessageData data) -> data.chatTransactionData.getTimestamp())
				.thenComparing((left, right) -> compareBytes(left.chatTransactionData.getSignature(),
						right.chatTransactionData.getSignature()));
		if (reverse != null && reverse)
			comparator = comparator.reversed();

		listedMessages.sort(comparator);
	}

	private static int compareActiveChats(ActiveChatResult left, ActiveChatResult right) {
		Long leftTimestamp = left.getTimestamp();
		Long rightTimestamp = right.getTimestamp();
		boolean leftHasMessage = leftTimestamp != null;
		boolean rightHasMessage = rightTimestamp != null;

		if (leftHasMessage != rightHasMessage)
			return leftHasMessage ? -1 : 1;

		if (leftHasMessage) {
			int timestampComparison = Long.compare(rightTimestamp, leftTimestamp);
			if (timestampComparison != 0)
				return timestampComparison;
		}

		String leftName = left.getGroupName() == null ? "" : left.getGroupName();
		String rightName = right.getGroupName() == null ? "" : right.getGroupName();
		return String.CASE_INSENSITIVE_ORDER.compare(leftName, rightName);
	}

	private static DecryptResult decryptMessageData(Repository repository, byte[] recipientPrivateKey,
			ChatTransactionData chatTransactionData, PrivateGroupChatEnvelope envelope)
			throws DataException, GeneralSecurityException, PrivateGroupChatException {
		PrivateGroupChatKeyCache.Entry keyEntry = getAuthorizedCachedKey(envelope, recipientPrivateKey);
		if (keyEntry == null)
			keyEntry = rehydrateKeyFromAnnouncements(repository, envelope, recipientPrivateKey);

		if (keyEntry == null)
			throw new PrivateGroupChatException("Private group chat key is not cached locally");

		byte[] plaintext = PrivateGroupChatCrypto.decryptMessage(keyEntry.getGroupKey(), envelope.getGroupId(),
				envelope.getEpochId(), envelope.getKeyId(), envelope.getNonce(), envelope.getCiphertext());

		return new DecryptResult(plaintext, chatTransactionData.getIsText(), envelope.getGroupId(),
				envelope.getEpochId(), envelope.getKeyId());
	}

	private static boolean matchesListCriteria(ChatTransactionData chatTransactionData, Long before, Long after,
			byte[] chatReference, Boolean hasChatReference, String sender) {
		if (before != null && chatTransactionData.getTimestamp() >= before)
			return false;

		if (after != null && chatTransactionData.getTimestamp() <= after)
			return false;

		byte[] messageChatReference = chatTransactionData.getChatReference();
		if (chatReference != null && !Arrays.equals(messageChatReference, chatReference))
			return false;

		if (hasChatReference != null && hasChatReference && messageChatReference == null)
			return false;

		if (hasChatReference != null && !hasChatReference && messageChatReference != null)
			return false;

		return sender == null || sender.equals(chatTransactionData.getSender());
	}

	private static int compareBytes(byte[] left, byte[] right) {
		if (left == right)
			return 0;
		if (left == null)
			return -1;
		if (right == null)
			return 1;

		int minLength = Math.min(left.length, right.length);
		for (int i = 0; i < minLength; ++i) {
			int comparison = Byte.compare(left[i], right[i]);
			if (comparison != 0)
				return comparison;
		}

		return Integer.compare(left.length, right.length);
	}

	public KeyRequestResult requestKey(Repository repository, byte[] requesterPrivateKey, int groupId, byte[] keyId)
			throws DataException, GeneralSecurityException, TransformationException, PrivateGroupChatException,
			ValidationException {
		return this.requestKey(repository, requesterPrivateKey, groupId, null, keyId);
	}

	public KeyRequestResult requestKey(Repository repository, byte[] requesterPrivateKey, int groupId, byte[] epochId,
			byte[] keyId) throws DataException, GeneralSecurityException, TransformationException,
			PrivateGroupChatException, ValidationException {
		validatePrivateKey(requesterPrivateKey, "requester private key");
		if (epochId != null && epochId.length != PrivateGroupChatEnvelope.EPOCH_ID_LENGTH)
			throw new IllegalArgumentException("epoch id is invalid");
		if (keyId != null && keyId.length != PrivateGroupChatEnvelope.KEY_ID_LENGTH)
			throw new IllegalArgumentException("key id is invalid");
		if (epochId != null && keyId == null)
			throw new IllegalArgumentException("historical key request requires key id");

		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		boolean locked;
		try {
			locked = blockchainLock.tryLock(BLOCKCHAIN_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ValidationException(ValidationResult.NO_BLOCKCHAIN_LOCK, e);
		}

		if (!locked)
			throw new ValidationException(ValidationResult.NO_BLOCKCHAIN_LOCK);

		try {
			return this.doRequestKey(repository, requesterPrivateKey, groupId, epochId, keyId);
		} finally {
			blockchainLock.unlock();
		}
	}

	public KeyAnnouncementRelayResult relayKeyAnnouncement(Repository repository, byte[] relayerPrivateKey,
			int groupId, byte[] epochId, byte[] keyId) throws DataException, GeneralSecurityException,
			TransformationException, PrivateGroupChatException, ValidationException {
		validatePrivateKey(relayerPrivateKey, "relayer private key");
		if (epochId == null || epochId.length != PrivateGroupChatEnvelope.EPOCH_ID_LENGTH)
			throw new IllegalArgumentException("epoch id is invalid");
		if (keyId != null && keyId.length != PrivateGroupChatEnvelope.KEY_ID_LENGTH)
			throw new IllegalArgumentException("key id is invalid");

		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		boolean locked;
		try {
			locked = blockchainLock.tryLock(BLOCKCHAIN_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ValidationException(ValidationResult.NO_BLOCKCHAIN_LOCK, e);
		}

		if (!locked)
			throw new ValidationException(ValidationResult.NO_BLOCKCHAIN_LOCK);

		try {
			return this.doRelayKeyAnnouncement(repository, relayerPrivateKey, groupId, epochId, keyId);
		} finally {
			blockchainLock.unlock();
		}
	}

	public List<KeyRequestRecoveryResult> resolveKeyRequests(Repository repository, byte[] relayerPrivateKey,
			int groupId, Integer limit) throws DataException, GeneralSecurityException, ValidationException {
		validatePrivateKey(relayerPrivateKey, "relayer private key");

		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		boolean locked;
		try {
			locked = blockchainLock.tryLock(BLOCKCHAIN_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ValidationException(ValidationResult.NO_BLOCKCHAIN_LOCK, e);
		}

		if (!locked)
			throw new ValidationException(ValidationResult.NO_BLOCKCHAIN_LOCK);

		try {
			return this.doResolveKeyRequests(repository, relayerPrivateKey, groupId, limit);
		} finally {
			blockchainLock.unlock();
		}
	}

	public KeyRotationResult rotateKey(Repository repository, byte[] rotatorPrivateKey, int groupId)
			throws DataException, GeneralSecurityException, TransformationException, PrivateGroupChatException,
			ValidationException {
		validatePrivateKey(rotatorPrivateKey, "rotator private key");

		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		boolean locked;
		try {
			locked = blockchainLock.tryLock(BLOCKCHAIN_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ValidationException(ValidationResult.NO_BLOCKCHAIN_LOCK, e);
		}

		if (!locked)
			throw new ValidationException(ValidationResult.NO_BLOCKCHAIN_LOCK);

		try {
			return this.doRotateKey(repository, rotatorPrivateKey, groupId);
		} finally {
			blockchainLock.unlock();
		}
	}

	public RotationRequestResult requestRotation(Repository repository, byte[] requesterPrivateKey, int groupId)
			throws DataException, GeneralSecurityException, TransformationException, PrivateGroupChatException,
			ValidationException {
		validatePrivateKey(requesterPrivateKey, "requester private key");

		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		boolean locked;
		try {
			locked = blockchainLock.tryLock(BLOCKCHAIN_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ValidationException(ValidationResult.NO_BLOCKCHAIN_LOCK, e);
		}

		if (!locked)
			throw new ValidationException(ValidationResult.NO_BLOCKCHAIN_LOCK);

		try {
			return this.doRequestRotation(repository, requesterPrivateKey, groupId);
		} finally {
			blockchainLock.unlock();
		}
	}

	private static PrivateGroupChatKeyCache.Entry getAuthorizedCachedKey(PrivateGroupChatEnvelope messageEnvelope,
			byte[] recipientPrivateKey) {
		PrivateGroupChatKeyCache.Entry keyEntry = PrivateGroupChatKeyCache.getInstance().get(
				messageEnvelope.getGroupId(), messageEnvelope.getEpochId(), messageEnvelope.getKeyId());
		if (keyEntry == null)
			return null;

		try {
			PrivateGroupChatEnvelope announcementEnvelope = PrivateGroupChatEnvelope.fromBytes(keyEntry.getAnnouncementBytes());
			byte[] unwrappedGroupKey = PrivateGroupChatKeyAnnouncement.unwrapHistoricalForRecipient(announcementEnvelope,
					recipientPrivateKey);

			if (!Arrays.equals(unwrappedGroupKey, keyEntry.getGroupKey()))
				return null;

			return keyEntry;
		} catch (GeneralSecurityException | IllegalArgumentException | TransformationException e) {
			return null;
		}
	}

	private static PrivateGroupChatKeyCache.Entry rehydrateKeyFromAnnouncements(Repository repository,
			PrivateGroupChatEnvelope messageEnvelope, byte[] recipientPrivateKey) throws DataException {
		List<ChatTransactionData> groupMessages = repository.getChatStoreRepository().getGroupMessages(
				messageEnvelope.getGroupId());

		for (ChatTransactionData groupMessage : groupMessages) {
			if (!groupMessage.getIsEncrypted())
				continue;

			PrivateGroupChatEnvelope announcementEnvelope;
			try {
				announcementEnvelope = PrivateGroupChatEnvelope.fromBytes(groupMessage.getData());
			} catch (TransformationException e) {
				continue;
			}

			if (announcementEnvelope.getType() != PrivateGroupChatEnvelope.Type.KEY_ANNOUNCEMENT)
				continue;

			if (announcementEnvelope.getGroupId() != messageEnvelope.getGroupId())
				continue;

			if (!Arrays.equals(announcementEnvelope.getEpochId(), messageEnvelope.getEpochId()))
				continue;

			if (!Arrays.equals(announcementEnvelope.getKeyId(), messageEnvelope.getKeyId()))
				continue;

			try {
				return PrivateGroupChatKeyCache.getInstance().putFromHistoricalAnnouncement(announcementEnvelope,
						recipientPrivateKey);
			} catch (GeneralSecurityException | IllegalArgumentException e) {
				// Ignore unusable announcements and keep looking for another valid wrapper.
			}
		}

		return null;
	}

	private static PrivateGroupChatEnvelope findRelayableKeyAnnouncement(Repository repository,
			PrivateGroupChatMembership.MembershipEpoch epoch, byte[] keyId) throws DataException {
		PrivateGroupChatKeyCache.Entry keyEntry = keyId == null
				? PrivateGroupChatKeyCache.getInstance().getAny(epoch.getGroupId(), epoch.getEpochId())
				: PrivateGroupChatKeyCache.getInstance().get(epoch.getGroupId(), epoch.getEpochId(), keyId);

		if (keyEntry != null) {
			try {
				PrivateGroupChatEnvelope announcementEnvelope = PrivateGroupChatEnvelope.fromBytes(
						keyEntry.getAnnouncementBytes());
				if (isRelayableKeyAnnouncement(epoch, announcementEnvelope, keyId))
					return announcementEnvelope;
			} catch (TransformationException e) {
				// Ignore invalid local cache data and fall back to the stored chat history.
			}
		}

		List<ChatTransactionData> groupMessages = repository.getChatStoreRepository().getGroupMessages(epoch.getGroupId());
		for (ChatTransactionData groupMessage : groupMessages) {
			if (!groupMessage.getIsEncrypted())
				continue;

			PrivateGroupChatEnvelope announcementEnvelope;
			try {
				announcementEnvelope = PrivateGroupChatEnvelope.fromBytes(groupMessage.getData());
			} catch (TransformationException e) {
				continue;
			}

			if (isRelayableKeyAnnouncement(epoch, announcementEnvelope, keyId))
				return announcementEnvelope;
		}

		return null;
	}

	private static PrivateGroupChatEnvelope findRecoverableKeyAnnouncement(Repository repository,
			PrivateGroupChatMembership.MembershipEpoch currentEpoch, byte[] epochId, byte[] keyId,
			byte[] requesterPublicKey) throws DataException {
		if (Arrays.equals(epochId, currentEpoch.getEpochId()))
			return findRelayableKeyAnnouncement(repository, currentEpoch, keyId);

		if (keyId == null)
			return null;

		PrivateGroupChatKeyCache.Entry keyEntry = PrivateGroupChatKeyCache.getInstance().get(
				currentEpoch.getGroupId(), epochId, keyId);
		if (keyEntry != null) {
			try {
				PrivateGroupChatEnvelope announcementEnvelope = PrivateGroupChatEnvelope.fromBytes(
						keyEntry.getAnnouncementBytes());
				if (isRecoverableHistoricalKeyAnnouncement(currentEpoch.getGroupId(), epochId, keyId,
						requesterPublicKey, announcementEnvelope))
					return announcementEnvelope;
			} catch (TransformationException e) {
				// Ignore invalid local cache data and fall back to the stored chat history.
			}
		}

		List<ChatTransactionData> groupMessages = repository.getChatStoreRepository().getGroupMessages(
				currentEpoch.getGroupId());
		for (ChatTransactionData groupMessage : groupMessages) {
			if (!groupMessage.getIsEncrypted())
				continue;

			PrivateGroupChatEnvelope announcementEnvelope;
			try {
				announcementEnvelope = PrivateGroupChatEnvelope.fromBytes(groupMessage.getData());
			} catch (TransformationException e) {
				continue;
			}

			if (isRecoverableHistoricalKeyAnnouncement(currentEpoch.getGroupId(), epochId, keyId,
					requesterPublicKey, announcementEnvelope))
				return announcementEnvelope;
		}

		return null;
	}

	private static boolean isRelayableKeyAnnouncement(PrivateGroupChatMembership.MembershipEpoch epoch,
			PrivateGroupChatEnvelope announcementEnvelope, byte[] keyId) {
		if (announcementEnvelope.getType() != PrivateGroupChatEnvelope.Type.KEY_ANNOUNCEMENT)
			return false;

		if (keyId != null && !Arrays.equals(announcementEnvelope.getKeyId(), keyId))
			return false;

		return PrivateGroupChatKeyAnnouncement.isValid(epoch, announcementEnvelope);
	}

	private static boolean isRecoverableHistoricalKeyAnnouncement(int groupId, byte[] epochId, byte[] keyId,
			byte[] requesterPublicKey, PrivateGroupChatEnvelope announcementEnvelope) {
		if (announcementEnvelope.getType() != PrivateGroupChatEnvelope.Type.KEY_ANNOUNCEMENT)
			return false;

		if (announcementEnvelope.getGroupId() != groupId)
			return false;

		if (!Arrays.equals(announcementEnvelope.getEpochId(), epochId))
			return false;

		if (!Arrays.equals(announcementEnvelope.getKeyId(), keyId))
			return false;

		if (!hasRecipientWrapper(announcementEnvelope, requesterPublicKey))
			return false;

		return PrivateGroupChatKeyAnnouncement.isHistoricallyValid(announcementEnvelope);
	}

	private static boolean hasRecipientWrapper(PrivateGroupChatEnvelope announcementEnvelope, byte[] recipientPublicKey) {
		for (PrivateGroupChatEnvelope.KeyWrapper keyWrapper : announcementEnvelope.getKeyWrappers())
			if (Arrays.equals(keyWrapper.getRecipientPublicKey(), recipientPublicKey))
				return true;

		return false;
	}

	private SendResult doSend(Repository repository, byte[] senderPrivateKey, int groupId, byte[] data,
			boolean isText, byte[] chatReference) throws DataException, GeneralSecurityException,
			TransformationException, PrivateGroupChatException, ValidationException {
		PrivateKeyAccount sender = new PrivateKeyAccount(repository, senderPrivateKey);
		PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
				groupId);

		if (!isMember(epoch, sender.getPublicKey()))
			throw new GeneralSecurityException("Sender is not a current member of this private group chat epoch");

		PrivateGroupChatKeyCache keyCache = PrivateGroupChatKeyCache.getInstance();
		PrivateGroupChatKeyCache.Entry keyEntry = keyCache.getNewestCreated(groupId, epoch.getEpochId());
		Long latestRotationRequestTimestamp = latestAcceptedRotationRequestTimestamp(repository, epoch);
		if (keyEntry != null && !isUsableAfterRotationRequest(keyEntry, latestRotationRequestTimestamp))
			keyEntry = null;

		byte[] groupKey;
		byte[] keyId;
		byte[] keyAnnouncementSignature = null;
		if (keyEntry == null) {
			KeyAnnouncementResult keyAnnouncementResult = createAndStoreKeyAnnouncement(repository, sender,
					senderPrivateKey, epoch);
			groupKey = keyAnnouncementResult.keyEntry.getGroupKey();
			keyId = keyAnnouncementResult.keyEntry.getKeyId();
			keyAnnouncementSignature = keyAnnouncementResult.keyAnnouncementSignature;
		} else {
			groupKey = keyEntry.getGroupKey();
			keyId = keyEntry.getKeyId();
		}

		byte[] nonce = PrivateGroupChatCrypto.generateNonce();
		byte[] ciphertext = PrivateGroupChatCrypto.encryptMessage(groupKey, groupId, epoch.getEpochId(),
				keyId, nonce, data);
		PrivateGroupChatEnvelope messageEnvelope = PrivateGroupChatEnvelope.message(groupId, epoch.getEpochId(),
				keyId, nonce, ciphertext);

		ChatTransactionData messageData = buildChatTransaction(sender, groupId, chatReference,
				messageEnvelope.toBytes(), isText, true);
		storeSignedChat(repository, messageData, sender);

		return new SendResult(messageData.getSignature(), keyAnnouncementSignature, epoch.getEpochId(), keyId);
	}

	private KeyRotationResult doRotateKey(Repository repository, byte[] rotatorPrivateKey, int groupId)
			throws DataException, GeneralSecurityException, TransformationException, PrivateGroupChatException,
			ValidationException {
		PrivateKeyAccount rotator = new PrivateKeyAccount(repository, rotatorPrivateKey);
		PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
				groupId);

		if (!isMember(epoch, rotator.getPublicKey()))
			throw new GeneralSecurityException("Key rotator is not a current member of this private group chat epoch");

		KeyAnnouncementResult keyAnnouncementResult = createAndStoreKeyAnnouncement(repository, rotator,
				rotatorPrivateKey, epoch);

		return new KeyRotationResult(keyAnnouncementResult.keyAnnouncementSignature, epoch.getEpochId(),
				keyAnnouncementResult.keyEntry.getKeyId());
	}

	private KeyRequestResult doRequestKey(Repository repository, byte[] requesterPrivateKey, int groupId,
			byte[] epochId, byte[] keyId) throws DataException, GeneralSecurityException, TransformationException,
			PrivateGroupChatException, ValidationException {
		PrivateKeyAccount requester = new PrivateKeyAccount(repository, requesterPrivateKey);
		PrivateGroupChatMembership.MembershipEpoch currentEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(
				repository, groupId);

		if (!isMember(currentEpoch, requester.getPublicKey()))
			throw new GeneralSecurityException("Key requester is not a current member of this private group chat epoch");

		byte[] requestEpochId = epochId == null ? currentEpoch.getEpochId() : copy(epochId);
		PrivateGroupChatEnvelope keyRequest = epochId == null
				? PrivateGroupChatKeyRequest.create(currentEpoch, requesterPrivateKey, keyId)
				: PrivateGroupChatKeyRequest.create(groupId, requestEpochId, requesterPrivateKey, keyId);
		ChatTransactionData keyRequestData = buildChatTransaction(requester, groupId, null,
				keyRequest.toBytes(), false, true);
		storeSignedChat(repository, keyRequestData, requester);

		return new KeyRequestResult(keyRequestData.getSignature(), requestEpochId, keyId);
	}

	private List<KeyRequestRecoveryResult> doResolveKeyRequests(Repository repository, byte[] relayerPrivateKey,
			int groupId, Integer limit) throws DataException, GeneralSecurityException {
		PrivateKeyAccount relayer = new PrivateKeyAccount(repository, relayerPrivateKey);
		PrivateGroupChatMembership.MembershipEpoch currentEpoch = PrivateGroupChatMembership.currentClosedGroupEpoch(
				repository, groupId);

		if (!isMember(currentEpoch, relayer.getPublicKey()))
			throw new GeneralSecurityException("Key request relayer is not a current member of this private group chat epoch");

		Set<EpochKey> relayedKeys = new HashSet<>();
		byte[] relayedAnyRequestKeyId = null;
		List<KeyRequestRecoveryResult> results = new ArrayList<>();
		for (ChatTransactionData groupMessage : repository.getChatStoreRepository().getGroupMessages(groupId)) {
			if (limit != null && limit > 0 && results.size() >= limit)
				break;

			if (!groupMessage.getIsEncrypted())
				continue;

			PrivateGroupChatEnvelope keyRequest;
			try {
				keyRequest = PrivateGroupChatEnvelope.fromBytes(groupMessage.getData());
			} catch (TransformationException e) {
				continue;
			}

			if (keyRequest.getType() != PrivateGroupChatEnvelope.Type.KEY_REQUEST)
				continue;

			KeyRequestRecoveryResult validationResult = validateRecoverableKeyRequest(currentEpoch, groupMessage, keyRequest);
			if (validationResult != null) {
				results.add(validationResult);
				continue;
			}

			byte[] requestedKeyId = keyRequest.hasRequestedKeyId() ? keyRequest.getKeyId() : null;
			boolean currentEpochRequest = Arrays.equals(keyRequest.getEpochId(), currentEpoch.getEpochId());
			if (currentEpochRequest && requestedKeyId == null && relayedAnyRequestKeyId != null) {
				results.add(KeyRequestRecoveryResult.duplicate(groupMessage, keyRequest, relayedAnyRequestKeyId));
				continue;
			}

			PrivateGroupChatEnvelope keyAnnouncement = findRecoverableKeyAnnouncement(repository, currentEpoch,
					keyRequest.getEpochId(), requestedKeyId, keyRequest.getRequesterPublicKey());
			if (keyAnnouncement == null) {
				results.add(KeyRequestRecoveryResult.noKeyAvailable(groupMessage, keyRequest));
				continue;
			}

			byte[] relayedKeyId = keyAnnouncement.getKeyId();
			EpochKey relayedKey = new EpochKey(keyAnnouncement.getEpochId(), relayedKeyId);
			if (relayedKeys.contains(relayedKey)) {
				if (currentEpochRequest && requestedKeyId == null)
					relayedAnyRequestKeyId = relayedKeyId;

				results.add(KeyRequestRecoveryResult.duplicate(groupMessage, keyRequest, relayedKeyId));
				continue;
			}

			try {
				ChatTransactionData relayData = buildChatTransaction(relayer, groupId, null,
						keyAnnouncement.toBytes(), false, true);
				storeSignedChat(repository, relayData, relayer);
				relayedKeys.add(relayedKey);
				if (currentEpochRequest && requestedKeyId == null)
					relayedAnyRequestKeyId = relayedKeyId;

				results.add(KeyRequestRecoveryResult.relayed(groupMessage, keyRequest, relayData.getSignature(),
						relayedKeyId));
			} catch (TransformationException | PrivateGroupChatException | ValidationException e) {
				results.add(KeyRequestRecoveryResult.relayFailed(groupMessage, keyRequest, relayedKeyId));
			}
		}

		return results;
	}

	private static KeyRequestRecoveryResult validateRecoverableKeyRequest(
			PrivateGroupChatMembership.MembershipEpoch currentEpoch, ChatTransactionData chatTransactionData,
			PrivateGroupChatEnvelope keyRequest) {
		if (keyRequest.getGroupId() != currentEpoch.getGroupId())
			return KeyRequestRecoveryResult.invalidRequest(chatTransactionData, keyRequest);

		if (!Arrays.equals(keyRequest.getRequesterPublicKey(), chatTransactionData.getSenderPublicKey()))
			return KeyRequestRecoveryResult.invalidRequest(chatTransactionData, keyRequest);

		if (!isMember(currentEpoch, keyRequest.getRequesterPublicKey()))
			return KeyRequestRecoveryResult.invalidRequest(chatTransactionData, keyRequest);

		if (Arrays.equals(keyRequest.getEpochId(), currentEpoch.getEpochId())) {
			if (!PrivateGroupChatKeyRequest.isValid(currentEpoch, keyRequest))
				return KeyRequestRecoveryResult.invalidRequest(chatTransactionData, keyRequest);

			return null;
		}

		if (!keyRequest.hasRequestedKeyId())
			return KeyRequestRecoveryResult.invalidRequest(chatTransactionData, keyRequest);

		if (!PrivateGroupChatKeyRequest.isHistoricallyValid(keyRequest))
			return KeyRequestRecoveryResult.invalidRequest(chatTransactionData, keyRequest);

		return null;
	}

	private RotationRequestResult doRequestRotation(Repository repository, byte[] requesterPrivateKey, int groupId)
			throws DataException, GeneralSecurityException, TransformationException, PrivateGroupChatException,
			ValidationException {
		PrivateKeyAccount requester = new PrivateKeyAccount(repository, requesterPrivateKey);
		PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
				groupId);

		if (!isMember(epoch, requester.getPublicKey()))
			throw new GeneralSecurityException("Rotation requester is not a current member of this private group chat epoch");

		if (!isOwnerOrAdmin(repository, groupId, requester.getAddress()))
			throw new GeneralSecurityException("Rotation requester is not a current group owner or admin");

		PrivateGroupChatEnvelope rotationRequest = PrivateGroupChatRotationRequest.create(epoch, requesterPrivateKey);
		ChatTransactionData rotationRequestData = buildChatTransaction(requester, groupId, null,
				rotationRequest.toBytes(), false, true);
		storeSignedChat(repository, rotationRequestData, requester);

		return new RotationRequestResult(rotationRequestData.getSignature(), epoch.getEpochId());
	}

	private static Long latestAcceptedRotationRequestTimestamp(Repository repository,
			PrivateGroupChatMembership.MembershipEpoch epoch) throws DataException {
		List<ChatTransactionData> groupMessages = repository.getChatStoreRepository().getGroupMessages(epoch.getGroupId());
		for (ChatTransactionData groupMessage : groupMessages) {
			if (!groupMessage.getIsEncrypted())
				continue;

			PrivateGroupChatEnvelope envelope;
			try {
				envelope = PrivateGroupChatEnvelope.fromBytes(groupMessage.getData());
			} catch (TransformationException e) {
				continue;
			}

			if (isAcceptedRotationRequest(repository, epoch, envelope))
				return groupMessage.getTimestamp();
		}

		return null;
	}

	private static boolean isAcceptedRotationRequest(Repository repository,
			PrivateGroupChatMembership.MembershipEpoch epoch, PrivateGroupChatEnvelope envelope) throws DataException {
		if (envelope.getType() != PrivateGroupChatEnvelope.Type.ROTATION_REQUEST)
			return false;

		if (!PrivateGroupChatRotationRequest.isValid(epoch, envelope))
			return false;

		return isOwnerOrAdmin(repository, epoch.getGroupId(), Crypto.toAddress(envelope.getRequesterPublicKey()));
	}

	private static boolean isUsableAfterRotationRequest(PrivateGroupChatKeyCache.Entry keyEntry,
			Long latestRotationRequestTimestamp) {
		return latestRotationRequestTimestamp == null
				|| keyEntry.getCreatedTimestamp() > latestRotationRequestTimestamp;
	}

	private KeyAnnouncementRelayResult doRelayKeyAnnouncement(Repository repository, byte[] relayerPrivateKey,
			int groupId, byte[] epochId, byte[] keyId) throws DataException, GeneralSecurityException,
			TransformationException, PrivateGroupChatException, ValidationException {
		PrivateKeyAccount relayer = new PrivateKeyAccount(repository, relayerPrivateKey);
		PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
				groupId);

		if (!Arrays.equals(epoch.getEpochId(), epochId))
			throw new PrivateGroupChatException("Private group chat key announcement relay requires the current membership epoch");

		if (!isMember(epoch, relayer.getPublicKey()))
			throw new GeneralSecurityException("Key announcement relayer is not a current member of this private group chat epoch");

		PrivateGroupChatEnvelope keyAnnouncement = findRelayableKeyAnnouncement(repository, epoch, keyId);
		if (keyAnnouncement == null)
			throw new PrivateGroupChatException("Private group chat key announcement was not found");

		ChatTransactionData relayData = buildChatTransaction(relayer, groupId, null,
				keyAnnouncement.toBytes(), false, true);
		storeSignedChat(repository, relayData, relayer);

		return new KeyAnnouncementRelayResult(relayData.getSignature(), keyAnnouncement.getEpochId(),
				keyAnnouncement.getKeyId());
	}

	private static ChatTransactionData buildChatTransaction(PrivateKeyAccount sender, int groupId,
			byte[] chatReference, byte[] data, boolean isText, boolean isEncrypted) throws PrivateGroupChatException {
		Long now = NTP.getTime();
		if (now == null)
			throw new PrivateGroupChatException("Clock is not synced");

		BaseTransactionData baseTransactionData = new BaseTransactionData(
				now,
				groupId,
				sender.getPublicKey(),
				0L,
				0,
				null);

		return new ChatTransactionData(baseTransactionData, sender.getAddress(), 0, null, chatReference,
				data, isText, isEncrypted);
	}

	private static KeyAnnouncementResult createAndStoreKeyAnnouncement(Repository repository, PrivateKeyAccount sender,
			byte[] senderPrivateKey, PrivateGroupChatMembership.MembershipEpoch epoch) throws GeneralSecurityException,
			DataException, TransformationException, ValidationException, PrivateGroupChatException {
		byte[] groupKey = PrivateGroupChatCrypto.generateGroupKey();
		PrivateGroupChatEnvelope keyAnnouncement = PrivateGroupChatKeyAnnouncement.create(epoch, groupKey,
				senderPrivateKey);

		ChatTransactionData keyAnnouncementData = buildChatTransaction(sender, epoch.getGroupId(), null,
				keyAnnouncement.toBytes(), false, true);
		storeSignedChat(repository, keyAnnouncementData, sender);

		PrivateGroupChatKeyCache.Entry keyEntry = PrivateGroupChatKeyCache.getInstance().putLocal(epoch,
				keyAnnouncement, groupKey);
		return new KeyAnnouncementResult(keyEntry, keyAnnouncementData.getSignature());
	}

	private static void storeSignedChat(Repository repository, ChatTransactionData chatTransactionData,
			PrivateKeyAccount signer) throws DataException, TransformationException, ValidationException,
			PrivateGroupChatException {
		ChatTransaction chatTransaction = new ChatTransaction(repository, chatTransactionData);
		chatTransaction.computeNonce();
		chatTransaction.sign(signer);

		if (!ChatService.getInstance().isSignatureValid(repository, chatTransactionData))
			throw new PrivateGroupChatException("Private group chat transaction signature is invalid");

		ValidationResult validationResult = ChatService.getInstance().validateAndStore(repository, chatTransactionData);
		if (validationResult != ValidationResult.OK)
			throw new ValidationException(validationResult);

		ChatNotifier.getInstance().onNewChatTransaction(chatTransactionData);
	}

	private static void validatePrivateKey(byte[] privateKey, String fieldName) {
		if (privateKey == null || privateKey.length != Transformer.PRIVATE_KEY_LENGTH)
			throw new IllegalArgumentException(fieldName + " is invalid");
	}

	private static boolean isMember(PrivateGroupChatMembership.MembershipEpoch epoch, byte[] publicKey) {
		for (byte[] memberPublicKey : epoch.getMemberPublicKeys())
			if (Arrays.equals(memberPublicKey, publicKey))
				return true;

		return false;
	}

	private static boolean isOwnerOrAdmin(Repository repository, int groupId, String address) throws DataException {
		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
		if (groupData == null)
			throw new IllegalArgumentException("group does not exist");

		if (address.equals(groupData.getOwner()))
			return true;

		return repository.getGroupRepository().adminExists(groupId, address);
	}

	private static class ListedMessageData {
		private final ChatTransactionData chatTransactionData;
		private final PrivateGroupChatEnvelope envelope;

		private ListedMessageData(ChatTransactionData chatTransactionData, PrivateGroupChatEnvelope envelope) {
			this.chatTransactionData = chatTransactionData;
			this.envelope = envelope;
		}
	}

	private static class EpochKey {
		private final byte[] epochId;
		private final byte[] keyId;

		private EpochKey(byte[] epochId, byte[] keyId) {
			this.epochId = copy(epochId);
			this.keyId = copy(keyId);
		}

		@Override
		public boolean equals(Object other) {
			if (this == other)
				return true;

			if (!(other instanceof EpochKey))
				return false;

			EpochKey otherKey = (EpochKey) other;
			return Arrays.equals(this.epochId, otherKey.epochId)
					&& Arrays.equals(this.keyId, otherKey.keyId);
		}

		@Override
		public int hashCode() {
			int result = Arrays.hashCode(this.epochId);
			result = 31 * result + Arrays.hashCode(this.keyId);
			return result;
		}
	}

	public enum KeyRequestRecoveryStatus {
		RELAYED,
		NO_KEY_AVAILABLE,
		DUPLICATE_KEY,
		INVALID_REQUEST,
		NOT_CURRENT_EPOCH,
		RELAY_FAILED
	}

	public enum ActiveChatStatus {
		DECRYPTED,
		MISSING_KEY,
		NO_MESSAGES
	}

	public static class ActiveChatResult {
		private final GroupData groupData;
		private final ChatMessage message;
		private final byte[] data;
		private final Boolean isText;
		private final byte[] epochId;
		private final byte[] keyId;
		private final ActiveChatStatus status;

		private ActiveChatResult(GroupData groupData, ChatMessage message, byte[] data, Boolean isText,
				byte[] epochId, byte[] keyId, ActiveChatStatus status) {
			this.groupData = groupData;
			this.message = message;
			this.data = copy(data);
			this.isText = isText;
			this.epochId = copy(epochId);
			this.keyId = copy(keyId);
			this.status = status;
		}

		private static ActiveChatResult noMessages(GroupData groupData) {
			return new ActiveChatResult(groupData, null, null, null, null, null,
					ActiveChatStatus.NO_MESSAGES);
		}

		private static ActiveChatResult decrypted(GroupData groupData, ChatMessage message,
				DecryptResult decryptResult) {
			return new ActiveChatResult(groupData, message, decryptResult.getData(), decryptResult.isText(),
					decryptResult.getEpochId(), decryptResult.getKeyId(), ActiveChatStatus.DECRYPTED);
		}

		private static ActiveChatResult missingKey(GroupData groupData, ChatMessage message, byte[] epochId,
				byte[] keyId, boolean isText) {
			return new ActiveChatResult(groupData, message, null, isText, epochId, keyId,
					ActiveChatStatus.MISSING_KEY);
		}

		public int getGroupId() {
			return this.groupData.getGroupId();
		}

		public String getGroupName() {
			return this.groupData.getGroupName();
		}

		public ChatMessage getMessage() {
			return this.message;
		}

		public Long getTimestamp() {
			return this.message == null ? null : this.message.getTimestamp();
		}

		public byte[] getData() {
			return copy(this.data);
		}

		public Boolean isText() {
			return this.isText;
		}

		public byte[] getEpochId() {
			return copy(this.epochId);
		}

		public byte[] getKeyId() {
			return copy(this.keyId);
		}

		public ActiveChatStatus getStatus() {
			return this.status;
		}
	}

	public static class KeyRequestRecoveryResult {
		private final byte[] requestSignature;
		private final byte[] requesterPublicKey;
		private final byte[] epochId;
		private final byte[] requestedKeyId;
		private final byte[] relayedKeyId;
		private final byte[] announcementSignature;
		private final KeyRequestRecoveryStatus status;

		private KeyRequestRecoveryResult(byte[] requestSignature, byte[] requesterPublicKey, byte[] epochId,
				byte[] requestedKeyId, byte[] relayedKeyId, byte[] announcementSignature,
				KeyRequestRecoveryStatus status) {
			this.requestSignature = copy(requestSignature);
			this.requesterPublicKey = copy(requesterPublicKey);
			this.epochId = copy(epochId);
			this.requestedKeyId = copy(requestedKeyId);
			this.relayedKeyId = copy(relayedKeyId);
			this.announcementSignature = copy(announcementSignature);
			this.status = status;
		}

		private static KeyRequestRecoveryResult relayed(ChatTransactionData requestData,
				PrivateGroupChatEnvelope keyRequest, byte[] announcementSignature, byte[] relayedKeyId) {
			return fromRequest(requestData, keyRequest, relayedKeyId, announcementSignature,
					KeyRequestRecoveryStatus.RELAYED);
		}

		private static KeyRequestRecoveryResult noKeyAvailable(ChatTransactionData requestData,
				PrivateGroupChatEnvelope keyRequest) {
			return fromRequest(requestData, keyRequest, null, null, KeyRequestRecoveryStatus.NO_KEY_AVAILABLE);
		}

		private static KeyRequestRecoveryResult duplicate(ChatTransactionData requestData,
				PrivateGroupChatEnvelope keyRequest, byte[] relayedKeyId) {
			return fromRequest(requestData, keyRequest, relayedKeyId, null, KeyRequestRecoveryStatus.DUPLICATE_KEY);
		}

		private static KeyRequestRecoveryResult invalidRequest(ChatTransactionData requestData,
				PrivateGroupChatEnvelope keyRequest) {
			return fromRequest(requestData, keyRequest, null, null, KeyRequestRecoveryStatus.INVALID_REQUEST);
		}

		private static KeyRequestRecoveryResult notCurrentEpoch(ChatTransactionData requestData,
				PrivateGroupChatEnvelope keyRequest) {
			return fromRequest(requestData, keyRequest, null, null, KeyRequestRecoveryStatus.NOT_CURRENT_EPOCH);
		}

		private static KeyRequestRecoveryResult relayFailed(ChatTransactionData requestData,
				PrivateGroupChatEnvelope keyRequest, byte[] relayedKeyId) {
			return fromRequest(requestData, keyRequest, relayedKeyId, null, KeyRequestRecoveryStatus.RELAY_FAILED);
		}

		private static KeyRequestRecoveryResult fromRequest(ChatTransactionData requestData,
				PrivateGroupChatEnvelope keyRequest, byte[] relayedKeyId, byte[] announcementSignature,
				KeyRequestRecoveryStatus status) {
			return new KeyRequestRecoveryResult(requestData.getSignature(), keyRequest.getRequesterPublicKey(),
					keyRequest.getEpochId(), keyRequest.hasRequestedKeyId() ? keyRequest.getKeyId() : null,
					relayedKeyId, announcementSignature, status);
		}

		public byte[] getRequestSignature() {
			return copy(this.requestSignature);
		}

		public byte[] getRequesterPublicKey() {
			return copy(this.requesterPublicKey);
		}

		public byte[] getEpochId() {
			return copy(this.epochId);
		}

		public byte[] getRequestedKeyId() {
			return copy(this.requestedKeyId);
		}

		public byte[] getRelayedKeyId() {
			return copy(this.relayedKeyId);
		}

		public byte[] getAnnouncementSignature() {
			return copy(this.announcementSignature);
		}

		public KeyRequestRecoveryStatus getStatus() {
			return this.status;
		}
	}

	public static class ListMessageResult {
		private final ChatMessage message;
		private final byte[] data;
		private final boolean isText;
		private final byte[] epochId;
		private final byte[] keyId;
		private final boolean decrypted;

		private ListMessageResult(ChatMessage message, byte[] data, boolean isText, byte[] epochId, byte[] keyId,
				boolean decrypted) {
			this.message = message;
			this.data = copy(data);
			this.isText = isText;
			this.epochId = copy(epochId);
			this.keyId = copy(keyId);
			this.decrypted = decrypted;
		}

		private static ListMessageResult decrypted(ChatMessage message, DecryptResult decryptResult) {
			return new ListMessageResult(message, decryptResult.getData(), decryptResult.isText(),
					decryptResult.getEpochId(), decryptResult.getKeyId(), true);
		}

		private static ListMessageResult missingKey(ChatMessage message, byte[] epochId, byte[] keyId, boolean isText) {
			return new ListMessageResult(message, null, isText, epochId, keyId, false);
		}

		public ChatMessage getMessage() {
			return this.message;
		}

		public byte[] getData() {
			return copy(this.data);
		}

		public boolean isText() {
			return this.isText;
		}

		public byte[] getEpochId() {
			return copy(this.epochId);
		}

		public byte[] getKeyId() {
			return copy(this.keyId);
		}

		public boolean isDecrypted() {
			return this.decrypted;
		}
	}

	public static class SendResult {
		private final byte[] messageSignature;
		private final byte[] keyAnnouncementSignature;
		private final byte[] epochId;
		private final byte[] keyId;

		private SendResult(byte[] messageSignature, byte[] keyAnnouncementSignature, byte[] epochId, byte[] keyId) {
			this.messageSignature = copy(messageSignature);
			this.keyAnnouncementSignature = copy(keyAnnouncementSignature);
			this.epochId = copy(epochId);
			this.keyId = copy(keyId);
		}

		public byte[] getMessageSignature() {
			return copy(this.messageSignature);
		}

		public byte[] getKeyAnnouncementSignature() {
			return copy(this.keyAnnouncementSignature);
		}

		public byte[] getEpochId() {
			return copy(this.epochId);
		}

		public byte[] getKeyId() {
			return copy(this.keyId);
		}
	}

	public static class DecryptResult {
		private final byte[] data;
		private final boolean isText;
		private final int groupId;
		private final byte[] epochId;
		private final byte[] keyId;

		private DecryptResult(byte[] data, boolean isText, int groupId, byte[] epochId, byte[] keyId) {
			this.data = copy(data);
			this.isText = isText;
			this.groupId = groupId;
			this.epochId = copy(epochId);
			this.keyId = copy(keyId);
		}

		public byte[] getData() {
			return copy(this.data);
		}

		public boolean isText() {
			return this.isText;
		}

		public int getGroupId() {
			return this.groupId;
		}

		public byte[] getEpochId() {
			return copy(this.epochId);
		}

		public byte[] getKeyId() {
			return copy(this.keyId);
		}
	}

	public static class KeyRequestResult {
		private final byte[] requestSignature;
		private final byte[] epochId;
		private final byte[] keyId;

		private KeyRequestResult(byte[] requestSignature, byte[] epochId, byte[] keyId) {
			this.requestSignature = copy(requestSignature);
			this.epochId = copy(epochId);
			this.keyId = copy(keyId);
		}

		public byte[] getRequestSignature() {
			return copy(this.requestSignature);
		}

		public byte[] getEpochId() {
			return copy(this.epochId);
		}

		public byte[] getKeyId() {
			return copy(this.keyId);
		}
	}

	public static class KeyAnnouncementRelayResult {
		private final byte[] announcementSignature;
		private final byte[] epochId;
		private final byte[] keyId;

		private KeyAnnouncementRelayResult(byte[] announcementSignature, byte[] epochId, byte[] keyId) {
			this.announcementSignature = copy(announcementSignature);
			this.epochId = copy(epochId);
			this.keyId = copy(keyId);
		}

		public byte[] getAnnouncementSignature() {
			return copy(this.announcementSignature);
		}

		public byte[] getEpochId() {
			return copy(this.epochId);
		}

		public byte[] getKeyId() {
			return copy(this.keyId);
		}
	}

	public static class RotationRequestResult {
		private final byte[] requestSignature;
		private final byte[] epochId;

		private RotationRequestResult(byte[] requestSignature, byte[] epochId) {
			this.requestSignature = copy(requestSignature);
			this.epochId = copy(epochId);
		}

		public byte[] getRequestSignature() {
			return copy(this.requestSignature);
		}

		public byte[] getEpochId() {
			return copy(this.epochId);
		}
	}

	public static class KeyRotationResult {
		private final byte[] keyAnnouncementSignature;
		private final byte[] epochId;
		private final byte[] keyId;

		private KeyRotationResult(byte[] keyAnnouncementSignature, byte[] epochId, byte[] keyId) {
			this.keyAnnouncementSignature = copy(keyAnnouncementSignature);
			this.epochId = copy(epochId);
			this.keyId = copy(keyId);
		}

		public byte[] getKeyAnnouncementSignature() {
			return copy(this.keyAnnouncementSignature);
		}

		public byte[] getEpochId() {
			return copy(this.epochId);
		}

		public byte[] getKeyId() {
			return copy(this.keyId);
		}
	}

	private static class KeyAnnouncementResult {
		private final PrivateGroupChatKeyCache.Entry keyEntry;
		private final byte[] keyAnnouncementSignature;

		private KeyAnnouncementResult(PrivateGroupChatKeyCache.Entry keyEntry, byte[] keyAnnouncementSignature) {
			this.keyEntry = keyEntry;
			this.keyAnnouncementSignature = copy(keyAnnouncementSignature);
		}
	}

	public static class PrivateGroupChatException extends Exception {
		public PrivateGroupChatException(String message) {
			super(message);
		}
	}

	public static class ValidationException extends Exception {
		private final ValidationResult validationResult;

		public ValidationException(ValidationResult validationResult) {
			this(validationResult, null);
		}

		private ValidationException(ValidationResult validationResult, Throwable cause) {
			super(validationResult.name(), cause);
			this.validationResult = validationResult;
		}

		public ValidationResult getValidationResult() {
			return this.validationResult;
		}
	}

	private static byte[] copy(byte[] bytes) {
		return bytes == null ? null : bytes.clone();
	}

}
