package org.qortium.chat;

import org.qortium.account.PublicKeyAccount;
import org.qortium.chat.crypto.PrivateGroupChatEnvelope;
import org.qortium.chat.crypto.PrivateGroupChatMembership;
import org.qortium.controller.Controller;
import org.qortium.crypto.Crypto;
import org.qortium.data.account.AccountData;
import org.qortium.data.group.GroupData;
import org.qortium.data.group.GroupMemberData;
import org.qortium.data.transaction.ChatTransactionData;
import org.qortium.repository.ChatStoreRepository;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transform.TransformationException;
import org.qortium.transform.Transformer;
import org.qortium.transform.transaction.ChatTransactionTransformer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Read-only protocol material used by trusted clients to implement portable QPGC. */
public final class PrivateGroupChatPublicService {

	public static final int DEFAULT_CONTROL_PAGE_SIZE = 25;
	public static final int MAX_CONTROL_PAGE_SIZE = ChatStoreRepository.MAX_PRIVATE_GROUP_PAGE_SIZE;
	public static final int MAX_ENCODED_RESPONSE_BYTES = 1024 * 1024;

	private static final int RESPONSE_PAGE_OVERHEAD = 1024;
	private static final int RESPONSE_RECORD_OVERHEAD = 1024;
	private static final int BLOCKCHAIN_LOCK_TIMEOUT_SECONDS = 60;
	private static final Comparator<byte[]> PUBLIC_KEY_COMPARATOR = (left, right) -> {
		for (int i = 0; i < Math.min(left.length, right.length); ++i) {
			int comparison = Integer.compare(left[i] & 0xff, right[i] & 0xff);
			if (comparison != 0)
				return comparison;
		}
		return Integer.compare(left.length, right.length);
	};

	private static final PrivateGroupChatPublicService INSTANCE = new PrivateGroupChatPublicService();

	public static PrivateGroupChatPublicService getInstance() {
		return INSTANCE;
	}

	private PrivateGroupChatPublicService() {
	}

	public ControlPage listControls(Repository repository, int groupId,
			Set<PrivateGroupChatEnvelope.Type> requestedTypes, byte[] epochId, byte[] keyId,
			PrivateGroupChatControlCursor before, PrivateGroupChatControlCursor after, Integer requestedLimit)
			throws DataException, TransformationException {
		if (repository == null)
			throw new IllegalArgumentException("repository is missing");
		validateClosedGroup(repository, groupId);
		EnumSet<PrivateGroupChatEnvelope.Type> types = normalizeControlTypes(requestedTypes);
		if (before != null && after != null)
			throw new IllegalArgumentException("before and after cursors are mutually exclusive");
		validateIdentifier(epochId, PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, "epoch id");
		validateIdentifier(keyId, PrivateGroupChatEnvelope.KEY_ID_LENGTH, "key id");

		int limit = normalizeLimit(requestedLimit);
		List<ChatTransactionData> rows = repository.getChatStoreRepository().getPrivateGroupEnvelopes(
				groupId, types, epochId, keyId,
				before == null ? null : before.getTimestamp(), before == null ? null : before.getSignature(),
				after == null ? null : after.getTimestamp(), after == null ? null : after.getSignature(), limit);

		List<ControlRecord> records = new ArrayList<>(rows.size());
		long responseBudget = RESPONSE_PAGE_OVERHEAD;
		boolean sizeTruncated = false;
		for (ChatTransactionData row : rows) {
			ControlRecord record = toControlRecord(row, groupId, types);
			if (record == null)
				continue;

			long recordBudget = RESPONSE_RECORD_OVERHEAD + 2L * record.signedTransactionBytes.length;
			if (responseBudget + recordBudget > MAX_ENCODED_RESPONSE_BYTES) {
				sizeTruncated = true;
				break;
			}
			responseBudget += recordBudget;
			records.add(record);
		}

		if (records.isEmpty() && sizeTruncated)
			throw new IllegalArgumentException("one private group control record exceeds the response limit");

		PrivateGroupChatControlCursor nextCursor = records.isEmpty() ? null
				: new PrivateGroupChatControlCursor(records.get(records.size() - 1).timestamp,
					records.get(records.size() - 1).signature);
		return new ControlPage(groupId, records, nextCursor, sizeTruncated || rows.size() == limit);
	}

	public GroupState getGroupState(Repository repository, int groupId)
			throws DataException, StateBusyException {
		if (repository == null)
			throw new IllegalArgumentException("repository is missing");
		if (groupId <= 0)
			throw new IllegalArgumentException("group id must be positive");

		ReentrantLock blockchainLock = Controller.getInstance().getBlockchainLock();
		boolean locked;
		try {
			locked = blockchainLock.tryLock(BLOCKCHAIN_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new StateBusyException("interrupted while waiting for an atomic group state", e);
		}
		if (!locked)
			throw new StateBusyException("timed out waiting for an atomic group state");

		try {
			return buildGroupState(repository, groupId);
		} finally {
			blockchainLock.unlock();
		}
	}

	private static GroupState buildGroupState(Repository repository, int groupId) throws DataException {
		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
		if (groupData == null)
			return GroupState.unavailable(groupId, false, null, 0, false, List.of(), List.of(),
					UnavailableReason.GROUP_NOT_FOUND);
		if (groupData.isOpen())
			return GroupState.unavailable(groupId, true, true, 0, false, List.of(), List.of(),
					UnavailableReason.GROUP_IS_OPEN);

		List<GroupMemberData> members = repository.getGroupRepository().getGroupMembers(groupId);
		if (members == null || members.isEmpty())
			return GroupState.unavailable(groupId, true, false, 0, false, List.of(), List.of(),
					UnavailableReason.NO_MEMBERS);

		List<byte[]> publicKeys = new ArrayList<>(members.size());
		List<String> missingAddresses = new ArrayList<>();
		for (GroupMemberData member : members) {
			String address = member.getMember();
			AccountData accountData = repository.getAccountRepository().getAccount(address);
			byte[] publicKey = accountData == null ? null : accountData.getPublicKey();
			if (!isUsablePublicKey(publicKey) || !address.equals(Crypto.toAddress(publicKey))) {
				missingAddresses.add(address);
				continue;
			}
			publicKeys.add(Arrays.copyOf(publicKey, publicKey.length));
		}
		publicKeys.sort(PUBLIC_KEY_COMPARATOR);
		missingAddresses.sort(String::compareTo);

		boolean allPublicKeysKnown = missingAddresses.isEmpty() && publicKeys.size() == members.size();
		UnavailableReason unavailableReason = null;
		if (members.size() > PrivateGroupChatMembership.MAX_V1_MEMBERS)
			unavailableReason = UnavailableReason.MEMBER_LIMIT_EXCEEDED;
		else if (!allPublicKeysKnown)
			unavailableReason = UnavailableReason.MEMBER_PUBLIC_KEY_UNKNOWN;

		byte[] epochId = unavailableReason == null
				? PrivateGroupChatMembership.computeEpochId(groupId, publicKeys)
				: null;
		return new GroupState(groupId, true, false, epochId, members.size(), allPublicKeysKnown,
				publicKeys, missingAddresses, unavailableReason == null, unavailableReason);
	}

	private static ControlRecord toControlRecord(ChatTransactionData row, int groupId,
			Set<PrivateGroupChatEnvelope.Type> requestedTypes) throws TransformationException {
		if (row == null || row.getTxGroupId() != groupId || row.getRecipient() != null || !row.getIsEncrypted()
				|| row.getSignature() == null || row.getSignature().length != Transformer.SIGNATURE_LENGTH)
			return null;

		PrivateGroupChatEnvelope envelope = PrivateGroupChatEnvelope.fromBytes(row.getData());
		if (envelope.getGroupId() != groupId || !requestedTypes.contains(envelope.getType())
				|| envelope.getType() == PrivateGroupChatEnvelope.Type.MESSAGE)
			return null;

		byte[] signedBytes = ChatTransactionTransformer.toBytes(row);
		return new ControlRecord(row.getTimestamp(), groupId, envelope.getType(), envelope.getEpochId(),
				envelope.getKeyId(), row.getSender(), row.getChatReference(), row.getSignature(), signedBytes);
	}

	private static EnumSet<PrivateGroupChatEnvelope.Type> normalizeControlTypes(
			Set<PrivateGroupChatEnvelope.Type> requestedTypes) {
		if (requestedTypes == null || requestedTypes.isEmpty())
			throw new IllegalArgumentException("at least one private group control type is required");
		EnumSet<PrivateGroupChatEnvelope.Type> types = EnumSet.copyOf(requestedTypes);
		if (types.contains(PrivateGroupChatEnvelope.Type.MESSAGE))
			throw new IllegalArgumentException("private group user messages are not control envelopes");
		return types;
	}

	private static int normalizeLimit(Integer requestedLimit) {
		if (requestedLimit == null)
			return DEFAULT_CONTROL_PAGE_SIZE;
		if (requestedLimit <= 0 || requestedLimit > MAX_CONTROL_PAGE_SIZE)
			throw new IllegalArgumentException("private group control page size must be between 1 and "
					+ MAX_CONTROL_PAGE_SIZE);
		return requestedLimit;
	}

	private static void validateIdentifier(byte[] value, int expectedLength, String label) {
		if (value != null && value.length != expectedLength)
			throw new IllegalArgumentException("private group " + label + " is invalid");
	}

	private static void validateClosedGroup(Repository repository, int groupId) throws DataException {
		if (groupId <= 0)
			throw new IllegalArgumentException("group id must be positive");
		GroupData groupData = repository.getGroupRepository().fromGroupId(groupId);
		if (groupData == null)
			throw new IllegalArgumentException("group does not exist");
		if (groupData.isOpen())
			throw new IllegalArgumentException("group is not closed");
	}

	private static boolean isUsablePublicKey(byte[] publicKey) {
		return publicKey != null && publicKey.length == Transformer.PUBLIC_KEY_LENGTH
				&& !Arrays.equals(publicKey, PublicKeyAccount.ALL_ZEROS);
	}

	public enum UnavailableReason {
		GROUP_NOT_FOUND,
		GROUP_IS_OPEN,
		NO_MEMBERS,
		MEMBER_PUBLIC_KEY_UNKNOWN,
		MEMBER_LIMIT_EXCEEDED
	}

	public static final class ControlRecord {
		private final long timestamp;
		private final int groupId;
		private final PrivateGroupChatEnvelope.Type type;
		private final byte[] epochId;
		private final byte[] keyId;
		private final String sender;
		private final byte[] chatReference;
		private final byte[] signature;
		private final byte[] signedTransactionBytes;

		private ControlRecord(long timestamp, int groupId, PrivateGroupChatEnvelope.Type type, byte[] epochId,
				byte[] keyId, String sender, byte[] chatReference, byte[] signature, byte[] signedTransactionBytes) {
			this.timestamp = timestamp;
			this.groupId = groupId;
			this.type = type;
			this.epochId = copy(epochId);
			this.keyId = copy(keyId);
			this.sender = sender;
			this.chatReference = copy(chatReference);
			this.signature = copy(signature);
			this.signedTransactionBytes = copy(signedTransactionBytes);
		}

		public long getTimestamp() { return this.timestamp; }
		public int getGroupId() { return this.groupId; }
		public PrivateGroupChatEnvelope.Type getType() { return this.type; }
		public byte[] getEpochId() { return copy(this.epochId); }
		public byte[] getKeyId() { return copy(this.keyId); }
		public String getSender() { return this.sender; }
		public byte[] getChatReference() { return copy(this.chatReference); }
		public byte[] getSignature() { return copy(this.signature); }
		public byte[] getSignedTransactionBytes() { return copy(this.signedTransactionBytes); }
	}

	public static final class ControlPage {
		private final int groupId;
		private final List<ControlRecord> records;
		private final PrivateGroupChatControlCursor nextCursor;
		private final boolean hasMore;

		private ControlPage(int groupId, List<ControlRecord> records, PrivateGroupChatControlCursor nextCursor,
				boolean hasMore) {
			this.groupId = groupId;
			this.records = Collections.unmodifiableList(new ArrayList<>(records));
			this.nextCursor = nextCursor;
			this.hasMore = hasMore;
		}

		public int getGroupId() { return this.groupId; }
		public List<ControlRecord> getRecords() { return this.records; }
		public PrivateGroupChatControlCursor getNextCursor() { return this.nextCursor; }
		public boolean hasMore() { return this.hasMore; }
	}

	public static final class GroupState {
		private final int groupId;
		private final boolean exists;
		private final Boolean open;
		private final byte[] epochId;
		private final int memberCount;
		private final boolean allPublicKeysKnown;
		private final List<byte[]> memberPublicKeys;
		private final List<String> missingPublicKeyAddresses;
		private final boolean available;
		private final UnavailableReason unavailableReason;

		private GroupState(int groupId, boolean exists, Boolean open, byte[] epochId, int memberCount,
				boolean allPublicKeysKnown, List<byte[]> memberPublicKeys, List<String> missingPublicKeyAddresses,
				boolean available, UnavailableReason unavailableReason) {
			this.groupId = groupId;
			this.exists = exists;
			this.open = open;
			this.epochId = copy(epochId);
			this.memberCount = memberCount;
			this.allPublicKeysKnown = allPublicKeysKnown;
			this.memberPublicKeys = copyKeys(memberPublicKeys);
			this.missingPublicKeyAddresses = List.copyOf(missingPublicKeyAddresses);
			this.available = available;
			this.unavailableReason = unavailableReason;
		}

		private static GroupState unavailable(int groupId, boolean exists, Boolean open, int memberCount,
				boolean allPublicKeysKnown, List<byte[]> memberPublicKeys, List<String> missingAddresses,
				UnavailableReason unavailableReason) {
			return new GroupState(groupId, exists, open, null, memberCount, allPublicKeysKnown,
					memberPublicKeys, missingAddresses, false, unavailableReason);
		}

		public int getGroupId() { return this.groupId; }
		public boolean exists() { return this.exists; }
		public Boolean isOpen() { return this.open; }
		public byte[] getEpochId() { return copy(this.epochId); }
		public int getMemberCount() { return this.memberCount; }
		public boolean areAllPublicKeysKnown() { return this.allPublicKeysKnown; }
		public List<byte[]> getMemberPublicKeys() { return copyKeys(this.memberPublicKeys); }
		public List<String> getMissingPublicKeyAddresses() { return this.missingPublicKeyAddresses; }
		public boolean isAvailable() { return this.available; }
		public UnavailableReason getUnavailableReason() { return this.unavailableReason; }
	}

	public static final class StateBusyException extends Exception {
		private StateBusyException(String message) { super(message); }
		private StateBusyException(String message, Throwable cause) { super(message, cause); }
	}

	private static byte[] copy(byte[] bytes) {
		return bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
	}

	private static List<byte[]> copyKeys(List<byte[]> keys) {
		List<byte[]> copies = new ArrayList<>(keys.size());
		for (byte[] key : keys)
			copies.add(copy(key));
		return Collections.unmodifiableList(copies);
	}
}
