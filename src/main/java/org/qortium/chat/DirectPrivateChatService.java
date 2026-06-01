package org.qortium.chat;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.chat.crypto.DirectPrivateChatCrypto;
import org.qortium.chat.crypto.DirectPrivateChatEnvelope;
import org.qortium.controller.ChatNotifier;
import org.qortium.controller.Controller;
import org.qortium.crypto.Crypto;
import org.qortium.data.account.AccountData;
import org.qortium.data.chat.ChatMessage;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.group.Group;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class DirectPrivateChatService {

	private static final DirectPrivateChatService INSTANCE = new DirectPrivateChatService();
	private static final int BLOCKCHAIN_LOCK_TIMEOUT_SECONDS = 60;

	public static DirectPrivateChatService getInstance() {
		return INSTANCE;
	}

	private DirectPrivateChatService() {
	}

	public SendResult send(Repository repository, byte[] senderPrivateKey, String recipientAddress, byte[] data,
			boolean isText, byte[] chatReference) throws DataException, GeneralSecurityException,
			TransformationException, DirectPrivateChatException, ValidationException {
		validatePrivateKey(senderPrivateKey, "sender private key");
		validateAddress(recipientAddress, "recipient address");
		if (data == null || data.length == 0)
			throw new DirectPrivateChatException("Direct private chat message data is missing");

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
			return this.doSend(repository, senderPrivateKey, recipientAddress, data, isText, chatReference);
		} finally {
			blockchainLock.unlock();
		}
	}

	public List<ListMessageResult> listMessages(Repository repository, byte[] accountPrivateKey, String otherAddress,
			Long before, Long after, byte[] chatReference, Boolean hasChatReference, String sender,
			ChatMessage.Encoding encoding, Integer limit, Integer offset, Boolean reverse) throws DataException {
		validatePrivateKey(accountPrivateKey, "account private key");
		validateAddress(otherAddress, "other address");

		PrivateKeyAccount account = new PrivateKeyAccount(repository, accountPrivateKey);
		List<String> involving = Arrays.asList(account.getAddress(), otherAddress);
		List<ChatTransactionData> chatTransactionData = repository.getChatStoreRepository()
				.getDirectMessagesMatchingCriteria(before, after, chatReference, hasChatReference, involving,
						sender, limit, offset, reverse);

		List<ListMessageResult> results = new ArrayList<>(chatTransactionData.size());
		for (ChatTransactionData chatData : chatTransactionData)
			results.add(toListMessageResult(repository, accountPrivateKey, chatData, encoding));

		return results;
	}

	public List<ActiveChatResult> listActiveChats(Repository repository, byte[] accountPrivateKey,
			ChatMessage.Encoding encoding, Boolean hasChatReference) throws DataException {
		validatePrivateKey(accountPrivateKey, "account private key");

		PrivateKeyAccount account = new PrivateKeyAccount(repository, accountPrivateKey);
		List<ChatTransactionData> latestDirectMessages = repository.getChatStoreRepository()
				.getLatestDirectMessages(account.getAddress(), hasChatReference);

		List<ActiveChatResult> results = new ArrayList<>(latestDirectMessages.size());
		for (ChatTransactionData chatData : latestDirectMessages) {
			ListMessageResult messageResult = toListMessageResult(repository, accountPrivateKey, chatData, encoding);
			results.add(ActiveChatResult.from(account.getAddress(), messageResult));
		}

		return results;
	}

	private SendResult doSend(Repository repository, byte[] senderPrivateKey, String recipientAddress, byte[] data,
			boolean isText, byte[] chatReference) throws DataException, GeneralSecurityException,
			TransformationException, DirectPrivateChatException, ValidationException {
		PrivateKeyAccount sender = new PrivateKeyAccount(repository, senderPrivateKey);
		byte[] recipientPublicKey = getKnownPublicKey(repository, recipientAddress);
		byte[] encryptedData = DirectPrivateChatCrypto.encryptMessage(senderPrivateKey, recipientPublicKey, data);
		if (encryptedData.length > ChatTransaction.MAX_DATA_SIZE)
			throw new ValidationException(ValidationResult.INVALID_DATA_LENGTH);

		ChatTransactionData messageData = buildChatTransaction(sender, recipientAddress, chatReference,
				encryptedData, isText, true);
		storeSignedChat(repository, messageData, sender);

		return new SendResult(messageData.getSignature(), SendStatus.STORED);
	}

	private static byte[] getKnownPublicKey(Repository repository, String address)
			throws DataException, ValidationException {
		AccountData accountData = repository.getAccountRepository().getAccount(address);
		byte[] publicKey = accountData == null ? null : accountData.getPublicKey();
		if (publicKey == null)
			throw new ValidationException(ValidationResult.PUBLIC_KEY_UNKNOWN);

		if (publicKey.length != Transformer.PUBLIC_KEY_LENGTH)
			throw new ValidationException(ValidationResult.INVALID_PUBLIC_KEY);

		return publicKey;
	}

	private static ListMessageResult toListMessageResult(Repository repository, byte[] accountPrivateKey,
			ChatTransactionData chatData, ChatMessage.Encoding encoding) throws DataException {
		ChatMessage message = repository.getChatStoreRepository().toChatMessage(chatData, encoding);
		if (!chatData.getIsEncrypted())
			return ListMessageResult.plain(message, chatData.getData(), chatData.getIsText());

		DirectPrivateChatEnvelope envelope;
		try {
			envelope = DirectPrivateChatEnvelope.fromBytes(chatData.getData());
		} catch (TransformationException e) {
			return ListMessageResult.unreadable(message, chatData.getIsText(), DecryptionStatus.UNSUPPORTED);
		}

		if (!isEnvelopeBoundToTransaction(chatData, envelope))
			return ListMessageResult.unreadable(message, chatData.getIsText(), DecryptionStatus.FAILED);

		try {
			byte[] decryptedData = DirectPrivateChatCrypto.decryptMessage(accountPrivateKey, envelope);
			return ListMessageResult.decrypted(message, decryptedData, chatData.getIsText());
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			return ListMessageResult.unreadable(message, chatData.getIsText(), DecryptionStatus.FAILED);
		}
	}

	private static boolean isEnvelopeBoundToTransaction(ChatTransactionData chatData,
			DirectPrivateChatEnvelope envelope) {
		if (!Arrays.equals(chatData.getSenderPublicKey(), envelope.getSenderPublicKey()))
			return false;

		String recipient = chatData.getRecipient();
		return recipient != null && recipient.equals(Crypto.toAddress(envelope.getRecipientPublicKey()));
	}

	private static ChatTransactionData buildChatTransaction(PrivateKeyAccount sender, String recipientAddress,
			byte[] chatReference, byte[] data, boolean isText, boolean isEncrypted) throws DirectPrivateChatException {
		Long now = NTP.getTime();
		if (now == null)
			throw new DirectPrivateChatException("Clock is not synced");

		BaseTransactionData baseTransactionData = new BaseTransactionData(
				now,
				Group.NO_GROUP,
				sender.getPublicKey(),
				0L,
				0,
				null);

		return new ChatTransactionData(baseTransactionData, sender.getAddress(), 0, recipientAddress,
				chatReference, data, isText, isEncrypted);
	}

	private static void storeSignedChat(Repository repository, ChatTransactionData chatTransactionData,
			PrivateKeyAccount signer) throws DataException, TransformationException, ValidationException,
			DirectPrivateChatException {
		ChatTransaction chatTransaction = new ChatTransaction(repository, chatTransactionData);
		chatTransaction.computeNonce();
		chatTransaction.sign(signer);

		if (!ChatService.getInstance().isSignatureValid(repository, chatTransactionData))
			throw new DirectPrivateChatException("Direct private chat transaction signature is invalid");

		ValidationResult validationResult = ChatService.getInstance().validateAndStore(repository, chatTransactionData);
		if (validationResult != ValidationResult.OK)
			throw new ValidationException(validationResult);

		ChatNotifier.getInstance().onNewChatTransaction(chatTransactionData);
	}

	private static void validatePrivateKey(byte[] privateKey, String fieldName) {
		if (privateKey == null || privateKey.length != Transformer.PRIVATE_KEY_LENGTH)
			throw new IllegalArgumentException(fieldName + " is invalid");
	}

	private static void validateAddress(String address, String fieldName) {
		if (address == null || !Crypto.isValidAddress(address))
			throw new IllegalArgumentException(fieldName + " is invalid");
	}

	public enum SendStatus {
		STORED
	}

	public enum DecryptionStatus {
		DECRYPTED,
		PLAIN,
		UNSUPPORTED,
		FAILED
	}

	public static class SendResult {
		private final byte[] messageSignature;
		private final SendStatus status;

		private SendResult(byte[] messageSignature, SendStatus status) {
			this.messageSignature = copy(messageSignature);
			this.status = status;
		}

		public byte[] getMessageSignature() {
			return copy(this.messageSignature);
		}

		public SendStatus getStatus() {
			return this.status;
		}
	}

	public static class ActiveChatResult {
		private final String address;
		private final String name;
		private final ListMessageResult messageResult;

		private ActiveChatResult(String address, String name, ListMessageResult messageResult) {
			this.address = address;
			this.name = name;
			this.messageResult = messageResult;
		}

		private static ActiveChatResult from(String accountAddress, ListMessageResult messageResult) {
			ChatMessage message = messageResult.getMessage();
			boolean sentByAccount = accountAddress.equals(message.getSender());
			String address = sentByAccount ? message.getRecipient() : message.getSender();
			String name = sentByAccount ? message.getRecipientName() : message.getSenderName();
			return new ActiveChatResult(address, name, messageResult);
		}

		public String getAddress() {
			return this.address;
		}

		public String getName() {
			return this.name;
		}

		public ListMessageResult getMessageResult() {
			return this.messageResult;
		}
	}

	public static class ListMessageResult {
		private final ChatMessage message;
		private final byte[] data;
		private final boolean isText;
		private final DecryptionStatus status;

		private ListMessageResult(ChatMessage message, byte[] data, boolean isText, DecryptionStatus status) {
			this.message = message;
			this.data = copy(data);
			this.isText = isText;
			this.status = status;
		}

		private static ListMessageResult decrypted(ChatMessage message, byte[] data, boolean isText) {
			return new ListMessageResult(message, data, isText, DecryptionStatus.DECRYPTED);
		}

		private static ListMessageResult plain(ChatMessage message, byte[] data, boolean isText) {
			return new ListMessageResult(message, data, isText, DecryptionStatus.PLAIN);
		}

		private static ListMessageResult unreadable(ChatMessage message, boolean isText, DecryptionStatus status) {
			return new ListMessageResult(message, null, isText, status);
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

		public DecryptionStatus getStatus() {
			return this.status;
		}
	}

	public static class DirectPrivateChatException extends Exception {
		public DirectPrivateChatException(String message) {
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
