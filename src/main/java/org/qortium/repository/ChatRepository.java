package org.qortium.repository;

import org.qortium.data.chat.ActiveChats;
import org.qortium.data.chat.ChatMessage;
import org.qortium.data.transaction.ChatTransactionData;

import java.util.List;

import static org.qortium.data.chat.ChatMessage.Encoding;

public interface ChatRepository {

	/**
	 * Returns CHAT messages matching criteria.
	 * <p>
	 * Expects EITHER non-null txGroupID OR non-null sender and recipient addresses.
	 */
	public List<ChatMessage> getMessagesMatchingCriteria(Long before, Long after,
			Integer txGroupId, byte[] chatReferenceBytes, Boolean hasChatReference,
			List<String> involving, String senderAddress, Encoding encoding,
			Integer limit, Integer offset, Boolean reverse) throws DataException;

	public ChatMessage toChatMessage(ChatTransactionData chatTransactionData, Encoding encoding) throws DataException;

	public ActiveChats getActiveChats(String address, Encoding encoding, Boolean hasChatReference) throws DataException;

}
