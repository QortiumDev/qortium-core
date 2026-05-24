package org.qortal.chat;

import org.qortal.account.PublicKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.block.BlockChain;
import org.qortal.chat.crypto.PrivateGroupChatEnvelope;
import org.qortal.chat.crypto.PrivateGroupChatKeyAnnouncement;
import org.qortal.chat.crypto.PrivateGroupChatMembership;
import org.qortal.chat.crypto.PrivateGroupChatKeyRequest;
import org.qortal.chat.crypto.PrivateGroupChatRotationRequest;
import org.qortal.crypto.Crypto;
import org.qortal.crypto.MemoryPoW;
import org.qortal.data.group.GroupData;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.GroupRepository;
import org.qortal.repository.Repository;
import org.qortal.settings.Settings;
import org.qortal.transaction.ChatTransaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.Transformer;
import org.qortal.transform.transaction.ChatTransactionTransformer;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.ListUtils;
import org.qortal.utils.NTP;

import java.util.Arrays;
import java.util.List;

public class ChatService {

	private static final ChatService INSTANCE = new ChatService();

	private static final long CHAT_FUTURE_TIMESTAMP_ALLOWANCE = 5 * 60 * 1000L;

	public static ChatService getInstance() {
		return INSTANCE;
	}

	private ChatService() {
	}

	public boolean isSignatureValid(Repository repository, ChatTransactionData chatTransactionData) {
		byte[] senderPublicKey = chatTransactionData.getSenderPublicKey();
		byte[] signature = chatTransactionData.getSignature();

		if (!isUsablePublicKey(senderPublicKey) || signature == null)
			return false;

		if (chatTransactionData.getFee() == null || chatTransactionData.getData() == null)
			return false;

		byte[] transactionBytes;
		try {
			transactionBytes = TransactionTransformer.toBytesForSigning(chatTransactionData);
		} catch (TransformationException | RuntimeException e) {
			return false;
		}

		if (!Crypto.verify(senderPublicKey, signature, transactionBytes))
			return false;

		ChatTransactionTransformer.clearNonce(transactionBytes);

		int difficulty;
		try {
			difficulty = getPoWDifficulty(repository, senderPublicKey);
		} catch (DataException e) {
			return false;
		}

		return MemoryPoW.verify2(transactionBytes, ChatTransaction.POW_BUFFER_SIZE, difficulty, chatTransactionData.getNonce());
	}

	public ValidationResult validateForBuild(Repository repository, ChatTransactionData chatTransactionData) throws DataException {
		return validateForStore(repository, chatTransactionData);
	}

	public ValidationResult validateForStore(Repository repository, ChatTransactionData chatTransactionData) throws DataException {
		Long now = NTP.getTime();
		if (now == null)
			return ValidationResult.CLOCK_NOT_SYNCED;

		byte[] senderPublicKey = chatTransactionData.getSenderPublicKey();
		if (!isUsablePublicKey(senderPublicKey))
			return ValidationResult.INVALID_PUBLIC_KEY;

		String senderAddress = Crypto.toAddress(senderPublicKey);
		String suppliedSender = chatTransactionData.getSender();
		if (suppliedSender != null && !suppliedSender.equals(senderAddress))
			return ValidationResult.INVALID_ADDRESS;

		Long fee = chatTransactionData.getFee();
		if (fee != null && fee < 0)
			return ValidationResult.NEGATIVE_FEE;

		long cutoffTimestamp = now - Settings.getInstance().getChatMessageRetentionPeriod();
		if (chatTransactionData.getTimestamp() <= cutoffTimestamp)
			return ValidationResult.TIMESTAMP_TOO_OLD;

		if (chatTransactionData.getTimestamp() > now + CHAT_FUTURE_TIMESTAMP_ALLOWANCE)
			return ValidationResult.TIMESTAMP_TOO_NEW;

		if (ListUtils.isAddressBlocked(senderAddress))
			return ValidationResult.ADDRESS_BLOCKED;

		List<NameData> names = repository.getNameRepository().getNamesByOwner(senderAddress);
		if (names != null) {
			for (NameData nameData : names) {
				if (nameData != null && nameData.getName() != null && ListUtils.isNameBlocked(nameData.getName()))
					return ValidationResult.NAME_BLOCKED;
			}
		}

		if (!isValidTxGroupId(repository, chatTransactionData, senderAddress))
			return ValidationResult.INVALID_TX_GROUP_ID;

		byte[] signature = chatTransactionData.getSignature();
		if (signature != null) {
			if (repository.getChatStoreRepository().exists(signature) || repository.getTransactionRepository().exists(signature))
				return ValidationResult.TRANSACTION_ALREADY_EXISTS;
		}

		String recipientAddress = chatTransactionData.getRecipient();
		if (recipientAddress != null && !Crypto.isValidAddress(recipientAddress))
			return ValidationResult.INVALID_ADDRESS;

		byte[] data = chatTransactionData.getData();
		if (data == null || data.length < 1 || data.length > ChatTransaction.MAX_DATA_SIZE)
			return ValidationResult.INVALID_DATA_LENGTH;

		ValidationResult privateGroupChatResult = validatePrivateGroupChatPayload(repository, chatTransactionData);
		if (privateGroupChatResult != ValidationResult.OK)
			return privateGroupChatResult;

		long rateLimitCutoffTimestamp = now - Settings.getInstance().getRecentChatMessagesMaxAge();
		int recentCount = repository.getChatStoreRepository().countRecentBySender(senderPublicKey, rateLimitCutoffTimestamp);
		if (recentCount >= Settings.getInstance().getMaxRecentChatMessagesPerAccount())
			return ValidationResult.TOO_MANY_UNCONFIRMED;

		return ValidationResult.OK;
	}

	public void computeNonce(Repository repository, ChatTransactionData chatTransactionData) throws DataException, TransformationException {
		byte[] transactionBytes = TransactionTransformer.toBytesForSigning(chatTransactionData);

		ChatTransactionTransformer.clearNonce(transactionBytes);

		int difficulty = getPoWDifficulty(repository, chatTransactionData.getSenderPublicKey());
		chatTransactionData.setNonce(MemoryPoW.compute2(transactionBytes, ChatTransaction.POW_BUFFER_SIZE, difficulty));
	}

	/**
	 * Stores a chat transaction after non-signature validation.
	 * Callers should check {@link #isSignatureValid(Repository, ChatTransactionData)}
	 * first so API paths can keep reporting invalid signatures separately.
	 */
	public ValidationResult validateAndStore(Repository repository, ChatTransactionData chatTransactionData) throws DataException {
		ValidationResult validationResult = validateForStore(repository, chatTransactionData);
		if (validationResult != ValidationResult.OK)
			return validationResult;

		repository.discardChanges();

		byte[] signature = chatTransactionData.getSignature();
		if (repository.getChatStoreRepository().exists(signature) || repository.getTransactionRepository().exists(signature))
			return ValidationResult.TRANSACTION_ALREADY_EXISTS;

		new PublicKeyAccount(repository, chatTransactionData.getSenderPublicKey()).ensureAccount();
		repository.getChatStoreRepository().save(chatTransactionData);
		repository.saveChanges();

		return ValidationResult.OK;
	}

	private boolean isValidTxGroupId(Repository repository, ChatTransactionData chatTransactionData, String senderAddress) throws DataException {
		int txGroupId = chatTransactionData.getTxGroupId();

		if (txGroupId == Group.NO_GROUP)
			return true;

		if (!repository.getGroupRepository().groupExists(txGroupId))
			return false;

		GroupRepository groupRepository = repository.getGroupRepository();
		if (!groupRepository.memberExists(txGroupId, senderAddress))
			return false;

		String recipient = chatTransactionData.getRecipient();
		return recipient == null || groupRepository.memberExists(txGroupId, recipient);
	}

	private ValidationResult validatePrivateGroupChatPayload(Repository repository, ChatTransactionData chatTransactionData) throws DataException {
		int txGroupId = chatTransactionData.getTxGroupId();
		if (txGroupId == Group.NO_GROUP || chatTransactionData.getRecipient() != null)
			return ValidationResult.OK;

		GroupData groupData = repository.getGroupRepository().fromGroupId(txGroupId);
		if (groupData == null)
			return ValidationResult.INVALID_TX_GROUP_ID;

		if (groupData.isOpen())
			return ValidationResult.OK;

		if (!chatTransactionData.getIsEncrypted())
			return ValidationResult.INVALID_DATA_LENGTH;

		PrivateGroupChatEnvelope envelope;
		try {
			envelope = PrivateGroupChatEnvelope.fromBytes(chatTransactionData.getData());
		} catch (TransformationException e) {
			return ValidationResult.INVALID_DATA_LENGTH;
		}

		if (envelope.getGroupId() != txGroupId)
			return ValidationResult.INVALID_DATA_LENGTH;

		PrivateGroupChatMembership.MembershipEpoch epoch;
		try {
			epoch = PrivateGroupChatMembership.currentClosedGroupEpoch(repository, txGroupId);
		} catch (IllegalStateException e) {
			return ValidationResult.PUBLIC_KEY_UNKNOWN;
		} catch (IllegalArgumentException e) {
			return ValidationResult.INVALID_TX_GROUP_ID;
		}

		switch (envelope.getType()) {
			case MESSAGE:
				return Arrays.equals(envelope.getEpochId(), epoch.getEpochId())
						? ValidationResult.OK
						: ValidationResult.INVALID_DATA_LENGTH;

			case KEY_ANNOUNCEMENT:
				if (Arrays.equals(envelope.getEpochId(), epoch.getEpochId()))
					return PrivateGroupChatKeyAnnouncement.isValid(epoch, envelope)
							? ValidationResult.OK
							: ValidationResult.INVALID_DATA_LENGTH;

				return PrivateGroupChatKeyAnnouncement.isHistoricallyValid(envelope)
						? ValidationResult.OK
						: ValidationResult.INVALID_DATA_LENGTH;

			case KEY_REQUEST:
				if (!Arrays.equals(envelope.getRequesterPublicKey(), chatTransactionData.getSenderPublicKey()))
					return ValidationResult.INVALID_DATA_LENGTH;

				if (Arrays.equals(envelope.getEpochId(), epoch.getEpochId()))
					return PrivateGroupChatKeyRequest.isValid(epoch, envelope)
							? ValidationResult.OK
							: ValidationResult.INVALID_DATA_LENGTH;

				return envelope.hasRequestedKeyId()
						&& PrivateGroupChatKeyRequest.isHistoricallyValid(envelope)
						? ValidationResult.OK
						: ValidationResult.INVALID_DATA_LENGTH;

			case ROTATION_REQUEST:
				return Arrays.equals(envelope.getEpochId(), epoch.getEpochId())
						&& Arrays.equals(envelope.getRequesterPublicKey(), chatTransactionData.getSenderPublicKey())
						&& PrivateGroupChatRotationRequest.isValid(epoch, envelope)
						&& isOwnerOrAdmin(repository, groupData, envelope.getRequesterPublicKey())
						? ValidationResult.OK
						: ValidationResult.INVALID_DATA_LENGTH;

			default:
				return ValidationResult.INVALID_DATA_LENGTH;
		}
	}

	private static boolean isOwnerOrAdmin(Repository repository, GroupData groupData, byte[] publicKey) throws DataException {
		String address = Crypto.toAddress(publicKey);
		if (address.equals(groupData.getOwner()))
			return true;

		return repository.getGroupRepository().adminExists(groupData.getGroupId(), address);
	}

	private int getPoWDifficulty(Repository repository, byte[] senderPublicKey) throws DataException {
		PublicKeyAccount sender = new PublicKeyAccount(repository, senderPublicKey);
		BlockChain blockChain = BlockChain.getInstance();
		return sender.getConfirmedBalance(Asset.NATIVE) >= ChatTransaction.POW_NATIVE_THRESHOLD
				? blockChain.getChatPowDifficultyAboveNativeThreshold()
				: blockChain.getChatPowDifficultyBelowNativeThreshold();
	}

	private static boolean isUsablePublicKey(byte[] publicKey) {
		return publicKey != null
				&& publicKey.length == Transformer.PUBLIC_KEY_LENGTH
				&& !Arrays.equals(publicKey, PublicKeyAccount.ALL_ZEROS);
	}

}
