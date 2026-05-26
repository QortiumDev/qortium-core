package org.qortium.repository;

import org.qortium.data.chat.ActiveChats;
import org.qortium.data.chat.ChatMessage;
import org.qortium.data.transaction.ChatTransactionData;

import java.util.List;

import static org.qortium.data.chat.ChatMessage.Encoding;

public interface ChatStoreRepository {

	public void save(ChatTransactionData chatTransactionData) throws DataException;

	public boolean exists(byte[] signature) throws DataException;

	public ChatTransactionData fromSignature(byte[] signature) throws DataException;

	public List<ChatTransactionData> fromSignatures(List<byte[]> signatures) throws DataException;

	public List<byte[]> getSignatures() throws DataException;

	public List<ChatTransactionData> getGroupMessages(int txGroupId) throws DataException;

	public ChatMessage toChatMessage(ChatTransactionData chatTransactionData, Encoding encoding) throws DataException;

	public List<ChatMessage> getMessagesMatchingCriteria(Long before, Long after, Integer txGroupId,
			byte[] chatReferenceBytes, Boolean hasChatReference, List<String> involving, String senderAddress,
			Encoding encoding, Integer limit, Integer offset, Boolean reverse) throws DataException;

	public int countMessagesMatchingCriteria(Long before, Long after, Integer txGroupId,
			byte[] chatReferenceBytes, Boolean hasChatReference, List<String> involving, String senderAddress) throws DataException;

	public ActiveChats getActiveChats(String address, Encoding encoding, Boolean hasChatReference) throws DataException;

	public int deleteOlderThan(long cutoffTimestamp) throws DataException;

	public int countRecentBySender(byte[] senderPublicKey, long cutoffTimestamp) throws DataException;

}
