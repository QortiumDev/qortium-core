package org.qortium.chat.crypto;

import org.qortium.transform.Transformer;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class PrivateGroupChatKeyCache {

	private static final PrivateGroupChatKeyCache INSTANCE = new PrivateGroupChatKeyCache();

	private final Map<CacheKey, CachedEntry> entriesByKey = new HashMap<>();
	private long sequence;

	public PrivateGroupChatKeyCache() {
	}

	public static PrivateGroupChatKeyCache getInstance() {
		return INSTANCE;
	}

	public synchronized Entry putLocal(PrivateGroupChatMembership.MembershipEpoch epoch,
			PrivateGroupChatEnvelope announcementEnvelope, byte[] groupKey) throws GeneralSecurityException {
		validateGroupKey(groupKey);
		validateAnnouncement(epoch, announcementEnvelope);

		byte[] expectedKeyId = PrivateGroupChatCrypto.computeKeyId(announcementEnvelope.getGroupId(),
				announcementEnvelope.getEpochId(), groupKey);
		if (!Arrays.equals(expectedKeyId, announcementEnvelope.getKeyId()))
			throw new GeneralSecurityException("Group key does not match key announcement");

		return put(announcementEnvelope, groupKey, now(), nextSequence());
	}

	public synchronized Entry putFromAnnouncement(PrivateGroupChatMembership.MembershipEpoch epoch,
			PrivateGroupChatEnvelope announcementEnvelope, byte[] recipientPrivateKey) throws GeneralSecurityException {
		byte[] groupKey = PrivateGroupChatKeyAnnouncement.unwrapForRecipient(epoch, announcementEnvelope, recipientPrivateKey);
		return put(announcementEnvelope, groupKey, now(), nextSequence());
	}

	public synchronized Entry putFromHistoricalAnnouncement(PrivateGroupChatEnvelope announcementEnvelope,
			byte[] recipientPrivateKey) throws GeneralSecurityException {
		byte[] groupKey = PrivateGroupChatKeyAnnouncement.unwrapHistoricalForRecipient(announcementEnvelope,
				recipientPrivateKey);
		return put(announcementEnvelope, groupKey, now(), nextSequence());
	}

	public synchronized Entry get(int groupId, byte[] epochId, byte[] keyId) {
		CacheKey cacheKey = new CacheKey(groupId, epochId, keyId);
		CachedEntry cachedEntry = this.entriesByKey.get(cacheKey);
		if (cachedEntry == null)
			return null;

		cachedEntry.touch(now(), nextSequence());
		return cachedEntry.toEntry();
	}

	public synchronized Entry getAny(int groupId, byte[] epochId) {
		validateLength(epochId, PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, "epoch id");

		CachedEntry selectedEntry = null;
		for (CachedEntry cachedEntry : this.entriesByKey.values()) {
			if (cachedEntry.getGroupId() != groupId || !Arrays.equals(cachedEntry.getEpochId(), epochId))
				continue;

			if (selectedEntry == null || cachedEntry.isNewerThan(selectedEntry))
				selectedEntry = cachedEntry;
		}

		if (selectedEntry == null)
			return null;

		selectedEntry.touch(now(), nextSequence());
		return selectedEntry.toEntry();
	}

	public synchronized Entry getNewestCreated(int groupId, byte[] epochId) {
		validateLength(epochId, PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, "epoch id");

		CachedEntry selectedEntry = null;
		for (CachedEntry cachedEntry : this.entriesByKey.values()) {
			if (cachedEntry.getGroupId() != groupId || !Arrays.equals(cachedEntry.getEpochId(), epochId))
				continue;

			if (selectedEntry == null || cachedEntry.wasCreatedAfter(selectedEntry))
				selectedEntry = cachedEntry;
		}

		if (selectedEntry == null)
			return null;

		selectedEntry.touch(now(), nextSequence());
		return selectedEntry.toEntry();
	}

	public synchronized void clear() {
		this.entriesByKey.clear();
	}

	synchronized int size() {
		return this.entriesByKey.size();
	}

	private Entry put(PrivateGroupChatEnvelope announcementEnvelope, byte[] groupKey, long timestamp, long sequence) {
		CacheKey cacheKey = new CacheKey(announcementEnvelope.getGroupId(), announcementEnvelope.getEpochId(),
				announcementEnvelope.getKeyId());
		CachedEntry existingEntry = this.entriesByKey.get(cacheKey);
		long createdTimestamp = existingEntry == null ? timestamp : existingEntry.getCreatedTimestamp();
		long createdSequence = existingEntry == null ? sequence : existingEntry.getCreatedSequence();

		CachedEntry cachedEntry = new CachedEntry(announcementEnvelope.getGroupId(), announcementEnvelope.getEpochId(),
				announcementEnvelope.getKeyId(), groupKey, announcementEnvelope.toBytes(),
				announcementEnvelope.getCreatorPublicKey(), createdTimestamp, timestamp, createdSequence, sequence);
		this.entriesByKey.put(cacheKey, cachedEntry);
		return cachedEntry.toEntry();
	}

	private static void validateAnnouncement(PrivateGroupChatMembership.MembershipEpoch epoch,
			PrivateGroupChatEnvelope announcementEnvelope) throws GeneralSecurityException {
		if (!PrivateGroupChatKeyAnnouncement.isValid(epoch, announcementEnvelope))
			throw new GeneralSecurityException("Key announcement is invalid");
	}

	private static void validateGroupKey(byte[] groupKey) {
		validateLength(groupKey, Transformer.AES256_LENGTH, "group key");
	}

	private static void validateLength(byte[] bytes, int expectedLength, String fieldName) {
		if (bytes == null)
			throw new IllegalArgumentException(fieldName + " is missing");

		if (bytes.length != expectedLength)
			throw new IllegalArgumentException(fieldName + " has invalid length");
	}

	private static long now() {
		return System.currentTimeMillis();
	}

	private long nextSequence() {
		return ++this.sequence;
	}

	private static byte[] copy(byte[] bytes) {
		return Arrays.copyOf(bytes, bytes.length);
	}

	private static class CacheKey {
		private final int groupId;
		private final byte[] epochId;
		private final byte[] keyId;

		private CacheKey(int groupId, byte[] epochId, byte[] keyId) {
			validateLength(epochId, PrivateGroupChatEnvelope.EPOCH_ID_LENGTH, "epoch id");
			validateLength(keyId, PrivateGroupChatEnvelope.KEY_ID_LENGTH, "key id");

			this.groupId = groupId;
			this.epochId = copy(epochId);
			this.keyId = copy(keyId);
		}

		@Override
		public boolean equals(Object other) {
			if (this == other)
				return true;

			if (!(other instanceof CacheKey))
				return false;

			CacheKey otherKey = (CacheKey) other;
			return this.groupId == otherKey.groupId
					&& Arrays.equals(this.epochId, otherKey.epochId)
					&& Arrays.equals(this.keyId, otherKey.keyId);
		}

		@Override
		public int hashCode() {
			int result = Integer.hashCode(this.groupId);
			result = 31 * result + Arrays.hashCode(this.epochId);
			result = 31 * result + Arrays.hashCode(this.keyId);
			return result;
		}
	}

	private static class CachedEntry {
		private final int groupId;
		private final byte[] epochId;
		private final byte[] keyId;
		private final byte[] groupKey;
		private final byte[] announcementBytes;
		private final byte[] creatorPublicKey;
		private final long createdTimestamp;
		private final long createdSequence;
		private long lastUsedTimestamp;
		private long lastUsedSequence;

		private CachedEntry(int groupId, byte[] epochId, byte[] keyId, byte[] groupKey,
				byte[] announcementBytes, byte[] creatorPublicKey, long createdTimestamp, long lastUsedTimestamp,
				long createdSequence, long lastUsedSequence) {
			this.groupId = groupId;
			this.epochId = copy(epochId);
			this.keyId = copy(keyId);
			this.groupKey = copy(groupKey);
			this.announcementBytes = copy(announcementBytes);
			this.creatorPublicKey = copy(creatorPublicKey);
			this.createdTimestamp = createdTimestamp;
			this.createdSequence = createdSequence;
			this.lastUsedTimestamp = lastUsedTimestamp;
			this.lastUsedSequence = lastUsedSequence;
		}

		private int getGroupId() {
			return this.groupId;
		}

		private byte[] getEpochId() {
			return copy(this.epochId);
		}

		private long getCreatedTimestamp() {
			return this.createdTimestamp;
		}

		private long getCreatedSequence() {
			return this.createdSequence;
		}

		private boolean isNewerThan(CachedEntry otherEntry) {
			if (this.lastUsedSequence != otherEntry.lastUsedSequence)
				return this.lastUsedSequence > otherEntry.lastUsedSequence;

			return this.createdSequence > otherEntry.createdSequence;
		}

		private boolean wasCreatedAfter(CachedEntry otherEntry) {
			return this.createdSequence > otherEntry.createdSequence;
		}

		private void touch(long timestamp, long sequence) {
			this.lastUsedTimestamp = timestamp;
			this.lastUsedSequence = sequence;
		}

		private Entry toEntry() {
			return new Entry(this.groupId, this.epochId, this.keyId, this.groupKey, this.announcementBytes,
					this.creatorPublicKey, this.createdTimestamp, this.lastUsedTimestamp);
		}
	}

	public static class Entry {
		private final int groupId;
		private final byte[] epochId;
		private final byte[] keyId;
		private final byte[] groupKey;
		private final byte[] announcementBytes;
		private final byte[] creatorPublicKey;
		private final long createdTimestamp;
		private final long lastUsedTimestamp;

		private Entry(int groupId, byte[] epochId, byte[] keyId, byte[] groupKey, byte[] announcementBytes,
				byte[] creatorPublicKey, long createdTimestamp, long lastUsedTimestamp) {
			this.groupId = groupId;
			this.epochId = copy(epochId);
			this.keyId = copy(keyId);
			this.groupKey = copy(groupKey);
			this.announcementBytes = copy(announcementBytes);
			this.creatorPublicKey = copy(creatorPublicKey);
			this.createdTimestamp = createdTimestamp;
			this.lastUsedTimestamp = lastUsedTimestamp;
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

		public byte[] getGroupKey() {
			return copy(this.groupKey);
		}

		public byte[] getAnnouncementBytes() {
			return copy(this.announcementBytes);
		}

		public byte[] getCreatorPublicKey() {
			return copy(this.creatorPublicKey);
		}

		public long getCreatedTimestamp() {
			return this.createdTimestamp;
		}

		public long getLastUsedTimestamp() {
			return this.lastUsedTimestamp;
		}
	}
}
