package org.qortium.repository;

import org.qortium.chat.crypto.PrivateGroupChatEnvelope;
import org.qortium.data.chat.ActiveChats;
import org.qortium.data.chat.ChatMessage;
import org.qortium.data.transaction.ChatTransactionData;

import java.util.List;

import static org.qortium.data.chat.ChatMessage.Encoding;

public interface ChatStoreRepository {

	public static final int MAX_PRIVATE_GROUP_PAGE_SIZE = 100;

	public void save(ChatTransactionData chatTransactionData) throws DataException;

	public boolean exists(byte[] signature) throws DataException;

	/**
	 * Returns whether a retained group-chat row is visible through normal message history.
	 * Private key-management and rotation envelopes remain stored but are not user messages.
	 */
	public boolean isGroupMessageVisible(byte[] signature) throws DataException;

	public ChatTransactionData fromSignature(byte[] signature) throws DataException;

	public List<ChatTransactionData> fromSignatures(List<byte[]> signatures) throws DataException;

	public List<byte[]> getSignatures() throws DataException;

	public List<ChatTransactionData> getGroupMessages(int txGroupId) throws DataException;

	/**
	 * Returns one bounded newest-first page of indexed QPGC envelopes.
	 * A signature cursor disambiguates envelopes that share the same timestamp.
	 */
	public List<ChatTransactionData> getPrivateGroupEnvelopes(int txGroupId,
			PrivateGroupChatEnvelope.Type envelopeType, byte[] epochId, byte[] keyId,
			Long beforeTimestamp, byte[] beforeSignature, int limit) throws DataException;

	public List<ChatTransactionData> getPrivateGroupMessagesMatchingCriteria(int txGroupId,
			Long before, Long after, byte[] chatReferenceBytes, Boolean hasChatReference,
			String senderAddress, int limit, Integer offset, Boolean reverse) throws DataException;

	public int countPrivateGroupMessagesMatchingCriteria(int txGroupId, Long before, Long after,
			byte[] chatReferenceBytes, Boolean hasChatReference, String senderAddress) throws DataException;

	public ChatMessage toChatMessage(ChatTransactionData chatTransactionData, Encoding encoding) throws DataException;

	public List<ChatMessage> getMessagesMatchingCriteria(Long before, Long after, Integer txGroupId,
			byte[] chatReferenceBytes, Boolean hasChatReference, List<String> involving, String senderAddress,
			Encoding encoding, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public List<ChatTransactionData> getDirectMessagesMatchingCriteria(Long before, Long after,
			byte[] chatReferenceBytes, Boolean hasChatReference, List<String> involving, String senderAddress,
			Integer limit, Integer offset, Boolean reverse) throws DataException;

	public int countMessagesMatchingCriteria(Long before, Long after, Integer txGroupId,
			byte[] chatReferenceBytes, Boolean hasChatReference, List<String> involving, String senderAddress) throws DataException;

	public ActiveChats getActiveChats(String address, Encoding encoding, Boolean hasChatReference) throws DataException;

	public List<ChatTransactionData> getLatestDirectMessages(String address, Boolean hasChatReference) throws DataException;

	public int deleteOlderThan(long cutoffTimestamp) throws DataException;

	public int countRecentBySender(byte[] senderPublicKey, long cutoffTimestamp) throws DataException;

}
