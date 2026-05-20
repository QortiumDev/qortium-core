package org.qortal.chat;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.chat.crypto.PrivateGroupChatCrypto;
import org.qortal.chat.crypto.PrivateGroupChatEnvelope;
import org.qortal.chat.crypto.PrivateGroupChatKeyAnnouncement;
import org.qortal.chat.crypto.PrivateGroupChatKeyCache;
import org.qortal.chat.crypto.PrivateGroupChatMembership;
import org.qortal.controller.ChatNotifier;
import org.qortal.controller.Controller;
import org.qortal.crypto.Crypto;
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

		PrivateGroupChatMembership.MembershipEpoch epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository,
				envelope.getGroupId());
		if (!Arrays.equals(epoch.getEpochId(), envelope.getEpochId()))
			throw new PrivateGroupChatException("Private group chat message is not for the current membership epoch");

		byte[] recipientPublicKey = Crypto.toPublicKey(recipientPrivateKey);
		if (!isMember(epoch, recipientPublicKey))
			throw new GeneralSecurityException("Recipient is not a current member of this private group chat epoch");

		PrivateGroupChatKeyCache.Entry keyEntry = PrivateGroupChatKeyCache.getInstance().get(envelope.getGroupId(),
				envelope.getEpochId(), envelope.getKeyId());
		if (keyEntry == null)
			keyEntry = rehydrateKeyFromAnnouncements(repository, epoch, envelope.getKeyId(), recipientPrivateKey);

		if (keyEntry == null)
			throw new PrivateGroupChatException("Private group chat key is not cached locally");

		byte[] plaintext = PrivateGroupChatCrypto.decryptMessage(keyEntry.getGroupKey(), envelope.getGroupId(),
				envelope.getEpochId(), envelope.getKeyId(), envelope.getNonce(), envelope.getCiphertext());

		return new DecryptResult(plaintext, chatTransactionData.getIsText(), envelope.getGroupId(),
				envelope.getEpochId(), envelope.getKeyId());
	}

	private static PrivateGroupChatKeyCache.Entry rehydrateKeyFromAnnouncements(Repository repository,
			PrivateGroupChatMembership.MembershipEpoch epoch, byte[] keyId, byte[] recipientPrivateKey) throws DataException {
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

			if (announcementEnvelope.getType() != PrivateGroupChatEnvelope.Type.KEY_ANNOUNCEMENT)
				continue;

			if (announcementEnvelope.getGroupId() != epoch.getGroupId())
				continue;

			if (!Arrays.equals(announcementEnvelope.getEpochId(), epoch.getEpochId()))
				continue;

			if (!Arrays.equals(announcementEnvelope.getKeyId(), keyId))
				continue;

			try {
				return PrivateGroupChatKeyCache.getInstance().putFromAnnouncement(epoch, announcementEnvelope,
						recipientPrivateKey);
			} catch (GeneralSecurityException | IllegalArgumentException e) {
				// Ignore unusable announcements and keep looking for another valid wrapper.
			}
		}

		return null;
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
		PrivateGroupChatKeyCache.Entry keyEntry = keyCache.getAny(groupId, epoch.getEpochId());

		byte[] groupKey;
		byte[] keyId;
		byte[] keyAnnouncementSignature = null;
		if (keyEntry == null) {
			groupKey = PrivateGroupChatCrypto.generateGroupKey();
			PrivateGroupChatEnvelope keyAnnouncement = PrivateGroupChatKeyAnnouncement.create(epoch,
					groupKey, senderPrivateKey);

			ChatTransactionData keyAnnouncementData = buildChatTransaction(sender, groupId, null,
					keyAnnouncement.toBytes(), false, true);
			storeSignedChat(repository, keyAnnouncementData, sender);
			keyCache.putLocal(epoch, keyAnnouncement, groupKey);

			keyId = keyAnnouncement.getKeyId();
			keyAnnouncementSignature = keyAnnouncementData.getSignature();
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
