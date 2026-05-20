package org.qortal.chat;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.chat.crypto.PrivateGroupChatCrypto;
import org.qortal.chat.crypto.PrivateGroupChatEnvelope;
import org.qortal.chat.crypto.PrivateGroupChatKeyAnnouncement;
import org.qortal.chat.crypto.PrivateGroupChatKeyCache;
import org.qortal.chat.crypto.PrivateGroupChatKeyRequest;
import org.qortal.chat.crypto.PrivateGroupChatMembership;
import org.qortal.chat.crypto.PrivateGroupChatRotationRequest;
import org.qortal.controller.ChatNotifier;
import org.qortal.controller.Controller;
import org.qortal.data.group.GroupData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transaction.ChatTransaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.utils.NTP;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
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

	public KeyRequestResult requestKey(Repository repository, byte[] requesterPrivateKey, int groupId, byte[] keyId)
			throws DataException, GeneralSecurityException, TransformationException, PrivateGroupChatException,
			ValidationException {
		validatePrivateKey(requesterPrivateKey, "requester private key");
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
			return this.doRequestKey(repository, requesterPrivateKey, groupId, keyId);
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

	private static boolean isRelayableKeyAnnouncement(PrivateGroupChatMembership.MembershipEpoch epoch,
			PrivateGroupChatEnvelope announcementEnvelope, byte[] keyId) {
		if (announcementEnvelope.getType() != PrivateGroupChatEnvelope.Type.KEY_ANNOUNCEMENT)
			return false;

		if (keyId != null && !Arrays.equals(announcementEnvelope.getKeyId(), keyId))
			return false;

		return PrivateGroupChatKeyAnnouncement.isValid(epoch, announcementEnvelope);
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

	private KeyRequestResult doRequestKey(Repository repository, byte[] requesterPrivateKey, int groupId, byte[] keyId)
			throws DataException, GeneralSecurityException, TransformationException, PrivateGroupChatException,
			ValidationException {
		PrivateKeyAccount requester = new PrivateKeyAccount(repository, requesterPrivateKey);
		PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
				groupId);

		if (!isMember(epoch, requester.getPublicKey()))
			throw new GeneralSecurityException("Key requester is not a current member of this private group chat epoch");

		PrivateGroupChatEnvelope keyRequest = PrivateGroupChatKeyRequest.create(epoch, requesterPrivateKey, keyId);
		ChatTransactionData keyRequestData = buildChatTransaction(requester, groupId, null,
				keyRequest.toBytes(), false, true);
		storeSignedChat(repository, keyRequestData, requester);

		return new KeyRequestResult(keyRequestData.getSignature(), epoch.getEpochId(), keyId);
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
