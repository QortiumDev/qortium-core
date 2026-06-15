package org.qortium.repository.hsqldb;

import org.qortium.chat.crypto.PrivateGroupChatEnvelope;
import org.qortium.crypto.Crypto;
import org.qortium.data.chat.ActiveChats;
import org.qortium.data.chat.ActiveChats.DirectChat;
import org.qortium.data.chat.ActiveChats.GroupChat;
import org.qortium.data.chat.ChatMessage;
import org.qortium.data.group.GroupData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.repository.ChatStoreRepository;
import org.qortium.repository.DataException;
import org.qortium.settings.Settings;
import org.qortium.transform.TransformationException;
import org.qortium.utils.NTP;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.qortium.data.chat.ChatMessage.Encoding;

public class HSQLDBChatStoreRepository implements ChatStoreRepository {

	private static final String PRIMARY_NAMES_TABLE = "PrimaryNames";
	private static final String VISIBLE_PRIVATE_GROUP_ENVELOPE_FILTER =
			"(CM.private_group_envelope_type IS NULL OR CM.private_group_envelope_type = ?)";

	protected HSQLDBRepository repository;

	public HSQLDBChatStoreRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	@Override
	public void save(ChatTransactionData chatTransactionData) throws DataException {
		byte[] signature = chatTransactionData.getSignature();
		if (signature == null)
			throw new DataException("Unable to save chat message without signature");

		if (this.exists(signature))
			throw new DataException("Chat message already exists in repository");

		String sender = chatTransactionData.getSender();
		if (sender == null)
			sender = Crypto.toAddress(chatTransactionData.getSenderPublicKey());

		String sql = "INSERT INTO ChatMessages "
				+ "(signature, created_when, tx_group_id, sender_public_key, sender, nonce, recipient, "
				+ "chat_reference, is_text, is_encrypted, data, private_group_envelope_type) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
		String privateGroupEnvelopeType = classifyPrivateGroupEnvelope(chatTransactionData);

		try {
			this.repository.executeCheckedUpdate(sql,
					signature,
					chatTransactionData.getTimestamp(),
					chatTransactionData.getTxGroupId(),
					chatTransactionData.getSenderPublicKey(),
					sender,
					chatTransactionData.getNonce(),
					chatTransactionData.getRecipient(),
					chatTransactionData.getChatReference(),
					chatTransactionData.getIsText(),
					chatTransactionData.getIsEncrypted(),
					chatTransactionData.getData(),
					privateGroupEnvelopeType);
		} catch (SQLException e) {
			throw new DataException("Unable to save chat message into repository", e);
		}
	}

	@Override
	public boolean exists(byte[] signature) throws DataException {
		try {
			return this.repository.exists("ChatMessages", "signature = ?", signature);
		} catch (SQLException e) {
			throw new DataException("Unable to check for chat message in repository", e);
		}
	}

	@Override
	public ChatTransactionData fromSignature(byte[] signature) throws DataException {
		String sql = "SELECT created_when, tx_group_id, sender_public_key, sender, nonce, recipient, "
				+ "chat_reference, is_text, is_encrypted, data, signature "
				+ "FROM ChatMessages WHERE signature = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, signature)) {
			if (resultSet == null)
				return null;

			return this.toChatTransactionData(resultSet);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch chat message from repository", e);
		}
	}

	@Override
	public List<ChatTransactionData> fromSignatures(List<byte[]> signatures) throws DataException {
		List<ChatTransactionData> chatTransactionData = new ArrayList<>();
		if (signatures == null || signatures.isEmpty())
			return chatTransactionData;

		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT created_when, tx_group_id, sender_public_key, sender, nonce, recipient, ");
		sql.append("chat_reference, is_text, is_encrypted, data, signature ");
		sql.append("FROM ChatMessages WHERE signature IN (");
		sql.append(String.join(", ", java.util.Collections.nCopies(signatures.size(), "?")));
		sql.append(")");

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), (Object[]) signatures.toArray(new byte[0][]))) {
			if (resultSet == null)
				return chatTransactionData;

			do {
				chatTransactionData.add(this.toChatTransactionData(resultSet));
			} while (resultSet.next());

			return chatTransactionData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch chat messages from repository", e);
		}
	}

	@Override
	public List<byte[]> getSignatures() throws DataException {
		String sql = "SELECT signature FROM ChatMessages ORDER BY created_when DESC, signature DESC";

		List<byte[]> signatures = new ArrayList<>();
		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return signatures;

			do {
				signatures.add(resultSet.getBytes(1));
			} while (resultSet.next());

			return signatures;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch chat message signatures from repository", e);
		}
	}

	@Override
	public List<ChatTransactionData> getGroupMessages(int txGroupId) throws DataException {
		String sql = "SELECT created_when, tx_group_id, sender_public_key, sender, nonce, recipient, "
				+ "chat_reference, is_text, is_encrypted, data, signature "
				+ "FROM ChatMessages WHERE tx_group_id = ? AND recipient IS NULL "
				+ "ORDER BY created_when DESC, signature DESC";

		List<ChatTransactionData> chatTransactionData = new ArrayList<>();
		try (ResultSet resultSet = this.repository.checkedExecute(sql, txGroupId)) {
			if (resultSet == null)
				return chatTransactionData;

			do {
				chatTransactionData.add(this.toChatTransactionData(resultSet));
			} while (resultSet.next());

			return chatTransactionData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group chat messages from repository", e);
		}
	}

	@Override
	public ChatMessage toChatMessage(ChatTransactionData chatTransactionData, Encoding encoding) throws DataException {
		String sender = chatTransactionData.getSender();
		if (sender == null)
			sender = Crypto.toAddress(chatTransactionData.getSenderPublicKey());

		String sql = "SELECT "
				+ "(SELECT name FROM " + PRIMARY_NAMES_TABLE + " WHERE owner = ?), "
				+ "(SELECT name FROM " + PRIMARY_NAMES_TABLE + " WHERE owner = ?)";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, sender, chatTransactionData.getRecipient())) {
			String senderName = null;
			String recipientName = null;

			if (resultSet != null) {
				senderName = resultSet.getString(1);
				recipientName = resultSet.getString(2);
			}

			return new ChatMessage(
					chatTransactionData.getTimestamp(),
					chatTransactionData.getTxGroupId(),
					chatTransactionData.getSenderPublicKey(),
					sender,
					senderName,
					chatTransactionData.getRecipient(),
					recipientName,
					chatTransactionData.getChatReference(),
					encoding,
					chatTransactionData.getData(),
					chatTransactionData.getIsText(),
					chatTransactionData.getIsEncrypted(),
					chatTransactionData.getSignature());
		} catch (SQLException e) {
			throw new DataException("Unable to convert chat message from repository", e);
		}
	}

	@Override
	public List<ChatMessage> getMessagesMatchingCriteria(Long before, Long after, Integer txGroupId,
			byte[] chatReferenceBytes, Boolean hasChatReference, List<String> involving, String senderAddress,
			Encoding encoding, Integer limit, Integer offset, Boolean reverse) throws DataException {
		MessageCriteria criteria = buildMessageCriteria(before, after, txGroupId, chatReferenceBytes,
				hasChatReference, involving, senderAddress);

		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT CM.created_when, CM.tx_group_id, CM.sender_public_key, "
				+ "CM.sender, SenderNames.name, CM.recipient, RecipientNames.name, "
				+ "CM.chat_reference, CM.data, CM.is_text, CM.is_encrypted, CM.signature "
				+ "FROM ChatMessages CM "
				+ "LEFT OUTER JOIN " + PRIMARY_NAMES_TABLE + " AS SenderNames ON SenderNames.owner = CM.sender "
				+ "LEFT OUTER JOIN " + PRIMARY_NAMES_TABLE + " AS RecipientNames ON RecipientNames.owner = CM.recipient ");
		sql.append(criteria.sql);
		sql.append(" ORDER BY CM.created_when");
		sql.append((reverse == null || !reverse) ? " ASC" : " DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<ChatMessage> chatMessages = new ArrayList<>();
		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), criteria.bindParams.toArray())) {
			if (resultSet == null)
				return chatMessages;

			do {
				chatMessages.add(this.toChatMessage(resultSet, encoding));
			} while (resultSet.next());

			return chatMessages;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching chat messages from repository", e);
		}
	}

	@Override
	public List<ChatTransactionData> getDirectMessagesMatchingCriteria(Long before, Long after,
			byte[] chatReferenceBytes, Boolean hasChatReference, List<String> involving, String senderAddress,
			Integer limit, Integer offset, Boolean reverse) throws DataException {
		MessageCriteria criteria = buildMessageCriteria(before, after, null, chatReferenceBytes,
				hasChatReference, involving, senderAddress);

		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT CM.created_when, CM.tx_group_id, CM.sender_public_key, CM.sender, CM.nonce, ");
		sql.append("CM.recipient, CM.chat_reference, CM.is_text, CM.is_encrypted, CM.data, CM.signature ");
		sql.append("FROM ChatMessages CM ");
		sql.append(criteria.sql);
		sql.append(" ORDER BY CM.created_when");
		sql.append((reverse == null || !reverse) ? " ASC" : " DESC");
		sql.append(", CM.signature");
		sql.append((reverse == null || !reverse) ? " ASC" : " DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<ChatTransactionData> chatMessages = new ArrayList<>();
		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), criteria.bindParams.toArray())) {
			if (resultSet == null)
				return chatMessages;

			do {
				chatMessages.add(this.toChatTransactionData(resultSet));
			} while (resultSet.next());

			return chatMessages;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch matching direct chat messages from repository", e);
		}
	}

	@Override
	public int countMessagesMatchingCriteria(Long before, Long after, Integer txGroupId,
			byte[] chatReferenceBytes, Boolean hasChatReference, List<String> involving, String senderAddress) throws DataException {
		MessageCriteria criteria = buildMessageCriteria(before, after, txGroupId, chatReferenceBytes,
				hasChatReference, involving, senderAddress);

		String sql = "SELECT COUNT(*) FROM ChatMessages CM " + criteria.sql;

		try (ResultSet resultSet = this.repository.checkedExecute(sql, criteria.bindParams.toArray())) {
			if (resultSet == null)
				return 0;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to count matching chat messages in repository", e);
		}
	}

	@Override
	public ActiveChats getActiveChats(String address, Encoding encoding, Boolean hasChatReference) throws DataException {
		List<GroupChat> groupChats = getActiveGroupChats(address, encoding, hasChatReference);
		List<DirectChat> directChats = getActiveDirectChats(address, hasChatReference);

		return new ActiveChats(groupChats, directChats);
	}

	@Override
	public List<ChatTransactionData> getLatestDirectMessages(String address, Boolean hasChatReference) throws DataException {
		Long now = NTP.getTime();
		if (now == null)
			return new ArrayList<>();

		long cutoffTimestamp = now - Settings.getInstance().getChatMessageRetentionPeriod();

		String directSql = "SELECT LatestMessages.created_when, LatestMessages.tx_group_id, "
				+ "LatestMessages.sender_public_key, LatestMessages.sender, LatestMessages.nonce, "
				+ "LatestMessages.recipient, LatestMessages.chat_reference, LatestMessages.is_text, "
				+ "LatestMessages.is_encrypted, LatestMessages.data, LatestMessages.signature "
				+ "FROM ("
					+ "SELECT recipient FROM ChatMessages "
					+ "WHERE sender = ? AND recipient IS NOT NULL AND created_when >= ? "
					+ "UNION "
					+ "SELECT sender FROM ChatMessages "
					+ "WHERE recipient = ? AND created_when >= ?"
				+ ") AS OtherParties (other_address) "
				+ "CROSS JOIN LATERAL("
					+ "SELECT CM.created_when, CM.tx_group_id, CM.sender_public_key, CM.sender, CM.nonce, "
					+ "CM.recipient, CM.chat_reference, CM.is_text, CM.is_encrypted, CM.data, CM.signature "
					+ "FROM ChatMessages CM "
					+ "WHERE ((CM.sender = other_address AND CM.recipient = ?) "
					+ "OR (CM.sender = ? AND CM.recipient = other_address)) "
					+ "AND CM.created_when >= ? ";

		if (hasChatReference != null) {
			if (hasChatReference)
				directSql += "AND CM.chat_reference IS NOT NULL ";
			else
				directSql += "AND CM.chat_reference IS NULL ";
		}

		directSql += "ORDER BY CM.created_when DESC, CM.signature DESC "
				+ "LIMIT 1"
				+ ") AS LatestMessages "
				+ "ORDER BY LatestMessages.created_when DESC, LatestMessages.signature DESC";

		Object[] bindParams = new Object[] { address, cutoffTimestamp, address, cutoffTimestamp, address, address, cutoffTimestamp };

		List<ChatTransactionData> directMessages = new ArrayList<>();
		try (ResultSet resultSet = this.repository.checkedExecute(directSql, bindParams)) {
			if (resultSet == null)
				return directMessages;

			do {
				directMessages.add(this.toChatTransactionData(resultSet));
			} while (resultSet.next());
		} catch (SQLException e) {
			throw new DataException("Unable to fetch latest direct chat messages from repository", e);
		}

		return directMessages;
	}

	@Override
	public int deleteOlderThan(long cutoffTimestamp) throws DataException {
		try {
			return this.repository.delete("ChatMessages", "created_when < ?", cutoffTimestamp);
		} catch (SQLException e) {
			throw new DataException("Unable to delete old chat messages from repository", e);
		}
	}

	@Override
	public int countRecentBySender(byte[] senderPublicKey, long cutoffTimestamp) throws DataException {
		String sql = "SELECT COUNT(*) FROM ChatMessages WHERE sender_public_key = ? AND created_when >= ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, senderPublicKey, cutoffTimestamp)) {
			if (resultSet == null)
				return 0;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to count recent chat messages in repository", e);
		}
	}

	private MessageCriteria buildMessageCriteria(Long before, Long after, Integer txGroupId,
			byte[] chatReferenceBytes, Boolean hasChatReference, List<String> involving, String senderAddress) throws DataException {
		if ((txGroupId != null && involving != null && !involving.isEmpty())
				|| (txGroupId == null && (involving == null || involving.size() != 2)))
			throw new DataException("Invalid criteria for fetching chat messages from repository");

		List<String> whereClauses = new ArrayList<>();
		List<Object> bindParams = new ArrayList<>();
		whereClauses.add(VISIBLE_PRIVATE_GROUP_ENVELOPE_FILTER);
		bindParams.add(PrivateGroupChatEnvelope.Type.MESSAGE.name());

		if (before != null) {
			whereClauses.add("CM.created_when < ?");
			bindParams.add(before);
		}

		if (after != null) {
			whereClauses.add("CM.created_when > ?");
			bindParams.add(after);
		}

		if (chatReferenceBytes != null) {
			whereClauses.add("CM.chat_reference = ?");
			bindParams.add(chatReferenceBytes);
		}

		if (hasChatReference != null && hasChatReference) {
			whereClauses.add("CM.chat_reference IS NOT NULL");
		} else if (hasChatReference != null && !hasChatReference) {
			whereClauses.add("CM.chat_reference IS NULL");
		}

		if (senderAddress != null) {
			whereClauses.add("CM.sender = ?");
			bindParams.add(senderAddress);
		}

		if (txGroupId != null) {
			whereClauses.add("CM.tx_group_id = ?");
			bindParams.add(txGroupId);
			whereClauses.add("CM.recipient IS NULL");
		} else {
			String firstAddress = involving.get(0);
			String secondAddress = involving.get(1);

			whereClauses.add("((CM.sender = ? AND CM.recipient = ?) OR (CM.sender = ? AND CM.recipient = ?))");
			bindParams.add(firstAddress);
			bindParams.add(secondAddress);
			bindParams.add(secondAddress);
			bindParams.add(firstAddress);
		}

		StringBuilder sql = new StringBuilder();
		if (!whereClauses.isEmpty()) {
			sql.append(" WHERE ");

			for (int wci = 0; wci < whereClauses.size(); ++wci) {
				if (wci != 0)
					sql.append(" AND ");

				sql.append(whereClauses.get(wci));
			}
		}

		return new MessageCriteria(sql.toString(), bindParams);
	}

	private List<GroupChat> getActiveGroupChats(String address, Encoding encoding, Boolean hasChatReference) throws DataException {
		Long now = NTP.getTime();
		if (now == null)
			return new ArrayList<>();

		long cutoffTimestamp = now - Settings.getInstance().getChatMessageRetentionPeriod();

		String memberSql = "SELECT group_id, group_name FROM GroupMembers JOIN Groups USING (group_id) WHERE address = ?";

		Map<Integer, String> userGroups = new LinkedHashMap<>();
		try (ResultSet resultSet = this.repository.checkedExecute(memberSql, address)) {
			if (resultSet != null) {
				do {
					userGroups.put(resultSet.getInt(1), resultSet.getString(2));
				} while (resultSet.next());
			}
		} catch (SQLException e) {
			throw new DataException("Unable to fetch group memberships from repository", e);
		}

		String latestSql = "SELECT CM.tx_group_id, CM.created_when, CM.sender, SenderNames.name, CM.signature, CM.data "
				+ "FROM ChatMessages CM "
				+ "LEFT OUTER JOIN " + PRIMARY_NAMES_TABLE + " AS SenderNames ON SenderNames.owner = CM.sender "
				+ "WHERE CM.created_when >= ? "
				+ "AND CM.recipient IS NULL "
				+ "AND CM.tx_group_id IN (SELECT group_id FROM GroupMembers WHERE address = ?) "
				+ "AND " + VISIBLE_PRIVATE_GROUP_ENVELOPE_FILTER + " ";

		if (hasChatReference != null) {
			if (hasChatReference)
				latestSql += "AND CM.chat_reference IS NOT NULL ";
			else
				latestSql += "AND CM.chat_reference IS NULL ";
		}

		latestSql += "ORDER BY CM.created_when DESC";

		Map<Integer, Object[]> latestPerGroup = new HashMap<>();
		try (ResultSet resultSet = this.repository.checkedExecute(latestSql, cutoffTimestamp, address,
				PrivateGroupChatEnvelope.Type.MESSAGE.name())) {
			if (resultSet != null) {
				do {
					int groupId = resultSet.getInt(1);
					if (!latestPerGroup.containsKey(groupId)) {
						latestPerGroup.put(groupId, new Object[] {
								resultSet.getLong(2),
								resultSet.getString(3),
								resultSet.getString(4),
								resultSet.getBytes(5),
								resultSet.getBytes(6)
						});
					}
				} while (resultSet.next());
			}
		} catch (SQLException e) {
			throw new DataException("Unable to fetch latest group chat messages from repository", e);
		}

		List<GroupChat> groupChats = new ArrayList<>();
		for (Map.Entry<Integer, String> entry : userGroups.entrySet()) {
			int groupId = entry.getKey();
			String groupName = entry.getValue();
			Object[] message = latestPerGroup.get(groupId);

			if (message != null) {
				groupChats.add(new GroupChat(groupId, groupName, (Long) message[0], (String) message[1],
						(String) message[2], (byte[]) message[3], encoding, (byte[]) message[4]));
			} else {
				groupChats.add(new GroupChat(groupId, groupName, null, null, null, null, encoding, null));
			}
		}

		String grouplessSql = "SELECT CM.created_when, CM.sender, SenderNames.name, CM.signature, CM.data "
				+ "FROM ChatMessages CM "
				+ "LEFT OUTER JOIN " + PRIMARY_NAMES_TABLE + " AS SenderNames ON SenderNames.owner = CM.sender "
				+ "WHERE CM.tx_group_id = 0 "
				+ "AND CM.created_when >= ? "
				+ "AND CM.recipient IS NULL ";

		if (hasChatReference != null) {
			if (hasChatReference)
				grouplessSql += "AND CM.chat_reference IS NOT NULL ";
			else
				grouplessSql += "AND CM.chat_reference IS NULL ";
		}

		grouplessSql += "ORDER BY CM.created_when DESC LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(grouplessSql, cutoffTimestamp)) {
			Long timestamp = null;
			String sender = null;
			String senderName = null;
			byte[] signature = null;
			byte[] data = null;

			if (resultSet != null) {
				timestamp = resultSet.getLong(1);
				sender = resultSet.getString(2);
				senderName = resultSet.getString(3);
				signature = resultSet.getBytes(4);
				data = resultSet.getBytes(5);
			}

			groupChats.add(new GroupChat(0, null, timestamp, sender, senderName, signature, encoding, data));
		} catch (SQLException e) {
			throw new DataException("Unable to fetch active groupless chat from repository", e);
		}

		return groupChats;
	}

	private List<DirectChat> getActiveDirectChats(String address, Boolean hasChatReference) throws DataException {
		Long now = NTP.getTime();
		if (now == null)
			return new ArrayList<>();

		long cutoffTimestamp = now - Settings.getInstance().getChatMessageRetentionPeriod();

		String directSql = "SELECT other_address, name, latest_timestamp, latest_sender, sender_name "
				+ "FROM ("
					+ "SELECT recipient FROM ChatMessages "
					+ "WHERE sender = ? AND recipient IS NOT NULL AND created_when >= ? "
					+ "UNION "
					+ "SELECT sender FROM ChatMessages "
					+ "WHERE recipient = ? AND created_when >= ?"
				+ ") AS OtherParties (other_address) "
				+ "CROSS JOIN LATERAL("
					+ "SELECT CM.created_when AS latest_timestamp, CM.sender AS latest_sender, SenderNames.name AS sender_name "
					+ "FROM ChatMessages CM "
					+ "LEFT OUTER JOIN " + PRIMARY_NAMES_TABLE + " AS SenderNames ON SenderNames.owner = CM.sender "
					+ "WHERE ((CM.sender = other_address AND CM.recipient = ?) "
					+ "OR (CM.sender = ? AND CM.recipient = other_address)) "
					+ "AND CM.created_when >= ? ";

		if (hasChatReference != null) {
			if (hasChatReference)
				directSql += "AND CM.chat_reference IS NOT NULL ";
			else
				directSql += "AND CM.chat_reference IS NULL ";
		}

		directSql += "ORDER BY CM.created_when DESC "
				+ "LIMIT 1"
				+ ") AS LatestMessages "
				+ "LEFT OUTER JOIN " + PRIMARY_NAMES_TABLE + " ON owner = other_address";

		Object[] bindParams = new Object[] { address, cutoffTimestamp, address, cutoffTimestamp, address, address, cutoffTimestamp };

		List<DirectChat> directChats = new ArrayList<>();
		try (ResultSet resultSet = this.repository.checkedExecute(directSql, bindParams)) {
			if (resultSet == null)
				return directChats;

			do {
				String otherAddress = resultSet.getString(1);
				String name = resultSet.getString(2);
				long timestamp = resultSet.getLong(3);
				String sender = resultSet.getString(4);
				String senderName = resultSet.getString(5);

				directChats.add(new DirectChat(otherAddress, name, timestamp, sender, senderName));
			} while (resultSet.next());
		} catch (SQLException e) {
			throw new DataException("Unable to fetch active direct chats from repository", e);
		}

		return directChats;
	}

	private ChatTransactionData toChatTransactionData(ResultSet resultSet) throws SQLException {
		long timestamp = resultSet.getLong(1);
		int groupId = resultSet.getInt(2);
		byte[] senderPublicKey = resultSet.getBytes(3);
		String sender = resultSet.getString(4);
		int nonce = resultSet.getInt(5);
		String recipient = resultSet.getString(6);
		byte[] chatReference = resultSet.getBytes(7);
		boolean isText = resultSet.getBoolean(8);
		boolean isEncrypted = resultSet.getBoolean(9);
		byte[] data = resultSet.getBytes(10);
		byte[] signature = resultSet.getBytes(11);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, groupId, senderPublicKey, 0L, nonce, signature);
		return new ChatTransactionData(baseTransactionData, sender, nonce, recipient, chatReference, data, isText, isEncrypted);
	}

	private ChatMessage toChatMessage(ResultSet resultSet, Encoding encoding) throws SQLException {
		long timestamp = resultSet.getLong(1);
		int groupId = resultSet.getInt(2);
		byte[] senderPublicKey = resultSet.getBytes(3);
		String sender = resultSet.getString(4);
		String senderName = resultSet.getString(5);
		String recipient = resultSet.getString(6);
		String recipientName = resultSet.getString(7);
		byte[] chatReference = resultSet.getBytes(8);
		byte[] data = resultSet.getBytes(9);
		boolean isText = resultSet.getBoolean(10);
		boolean isEncrypted = resultSet.getBoolean(11);
		byte[] signature = resultSet.getBytes(12);

		return new ChatMessage(timestamp, groupId, senderPublicKey, sender, senderName, recipient,
				recipientName, chatReference, encoding, data, isText, isEncrypted, signature);
	}

	private String classifyPrivateGroupEnvelope(ChatTransactionData chatTransactionData) throws DataException {
		if (chatTransactionData.getRecipient() != null
				|| !chatTransactionData.getIsEncrypted()
				|| chatTransactionData.getTxGroupId() <= 0)
			return null;

		GroupData groupData = this.repository.getGroupRepository().fromGroupId(chatTransactionData.getTxGroupId());
		if (groupData == null || groupData.isOpen())
			return null;

		try {
			PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.fromBytes(chatTransactionData.getData());
			if (envelope.getGroupId() != chatTransactionData.getTxGroupId())
				return null;

			return envelope.getType().name();
		} catch (TransformationException e) {
			return null;
		}
	}

	private static class MessageCriteria {
		private final String sql;
		private final List<Object> bindParams;

		private MessageCriteria(String sql, List<Object> bindParams) {
			this.sql = sql;
			this.bindParams = bindParams;
		}
	}

}
