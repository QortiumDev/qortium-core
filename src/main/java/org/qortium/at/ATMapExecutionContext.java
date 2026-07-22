package org.qortium.at;

import com.google.common.primitives.Longs;
import org.qortium.crypto.Crypto;
import org.qortium.data.at.ATMapChangeData;
import org.qortium.data.at.ATMapEntryData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Block-scoped view of persistent AT map state.
 *
 * <p>AT execution happens while a block is being built or validated, before that block is allowed to
 * change the repository. This context therefore keeps every map write in memory. Successful writes are
 * visible to later ATs in the block, while {@link #getChanges()} is persisted only when the block itself
 * is processed.</p>
 *
 * <p>Only one AT executes at a time. A round snapshot lets a discarded round (for example an oversized
 * runtime state) restore its map without leaking writes to ATs that execute later in the same block.</p>
 */
public final class ATMapExecutionContext {

	private static final byte[] EMPTY_MAP_ROOT = Crypto.digest(new byte[0]);

	private final Repository repository;
	private final Map<String, NavigableMap<MapKey, Long>> originalEntriesByAt = new HashMap<>();
	private final Map<String, NavigableMap<MapKey, Long>> entriesByAt = new HashMap<>();
	private final Set<AddressedMapKey> committedTouchedKeys = new LinkedHashSet<>();

	private String activeAtAddress;
	private NavigableMap<MapKey, Long> activeRoundSnapshot;
	private final Set<MapKey> activeRoundTouchedKeys = new LinkedHashSet<>();

	public ATMapExecutionContext(Repository repository) {
		this.repository = repository;
	}

	public void beginRound(String atAddress) throws DataException {
		if (this.activeAtAddress != null)
			throw new IllegalStateException("An AT map round is already active");

		NavigableMap<MapKey, Long> entries = this.loadEntries(atAddress);
		this.activeAtAddress = atAddress;
		this.activeRoundSnapshot = new TreeMap<>(entries);
		this.activeRoundTouchedKeys.clear();
	}

	public void commitRound() {
		this.requireActiveRound();

		for (MapKey key : this.activeRoundTouchedKeys)
			this.committedTouchedKeys.add(new AddressedMapKey(this.activeAtAddress, key));

		this.clearActiveRound();
	}

	public void rollbackRound() {
		this.requireActiveRound();
		this.entriesByAt.put(this.activeAtAddress, new TreeMap<>(this.activeRoundSnapshot));
		this.clearActiveRound();
	}

	public long getValue(String atAddress, long key1, long key2) throws DataException {
		Long value = this.loadEntries(atAddress).get(new MapKey(key1, key2));
		return value == null ? 0L : value;
	}

	public boolean wouldCreateEntry(String atAddress, long key1, long key2, long value, int maxEntries)
			throws DataException {
		if (value == 0L)
			return false;

		NavigableMap<MapKey, Long> entries = this.loadEntries(atAddress);
		return !entries.containsKey(new MapKey(key1, key2)) && entries.size() < maxEntries;
	}

	/**
	 * Applies a write to the active AT's own map.
	 *
	 * @return true if current map contents changed, false for a logical no-op or a cap-rejected create
	 */
	public boolean setValue(String atAddress, long key1, long key2, long value, int maxEntries) throws DataException {
		this.requireActiveRound();
		if (!this.activeAtAddress.equals(atAddress))
			throw new IllegalArgumentException("An AT can only write its own map");

		NavigableMap<MapKey, Long> entries = this.loadEntries(atAddress);
		MapKey key = new MapKey(key1, key2);
		Long previousValue = entries.get(key);

		if (value == 0L) {
			if (previousValue == null)
				return false;

			entries.remove(key);
		} else {
			if (previousValue == null && entries.size() >= maxEntries)
				return false;

			if (previousValue != null && previousValue == value)
				return false;

			entries.put(key, value);
		}

		this.activeRoundTouchedKeys.add(key);
		return true;
	}

	/** Returns the canonical root of the AT's current block-scoped map contents. */
	public byte[] getMapRoot(String atAddress) throws DataException {
		return calculateMapRoot(this.loadEntries(atAddress));
	}

	/** Computes the canonical root for repository entries already ordered or unordered. */
	public static byte[] calculateMapRoot(List<ATMapEntryData> entries) {
		NavigableMap<MapKey, Long> sortedEntries = new TreeMap<>();
		for (ATMapEntryData entry : entries)
			sortedEntries.put(new MapKey(entry.getKey1(), entry.getKey2()), entry.getValue());
		return calculateMapRoot(sortedEntries);
	}

	private static byte[] calculateMapRoot(NavigableMap<MapKey, Long> entries) {
		if (entries.isEmpty())
			return EMPTY_MAP_ROOT.clone();

		ByteArrayOutputStream bytes = new ByteArrayOutputStream(entries.size() * 3 * Long.BYTES);
		try {
			for (Map.Entry<MapKey, Long> entry : entries.entrySet()) {
				bytes.write(Longs.toByteArray(entry.getKey().key1));
				bytes.write(Longs.toByteArray(entry.getKey().key2));
				bytes.write(Longs.toByteArray(entry.getValue()));
			}
		} catch (IOException e) {
			throw new IllegalStateException("Unable to hash in-memory AT map entries", e);
		}

		return Crypto.digest(bytes.toByteArray());
	}

	/** Returns the final, collapsed repository changes made by successful AT rounds in this block. */
	public List<ATMapChangeData> getChanges() {
		if (this.activeAtAddress != null)
			throw new IllegalStateException("Cannot persist AT map changes while a round is active");

		List<ATMapChangeData> changes = new ArrayList<>();
		for (AddressedMapKey addressedKey : this.committedTouchedKeys) {
			NavigableMap<MapKey, Long> originalEntries = this.originalEntriesByAt.get(addressedKey.atAddress);
			NavigableMap<MapKey, Long> currentEntries = this.entriesByAt.get(addressedKey.atAddress);
			Long previousValue = originalEntries.get(addressedKey.key);
			Long newValue = currentEntries.get(addressedKey.key);

			if (java.util.Objects.equals(previousValue, newValue))
				continue;

			changes.add(new ATMapChangeData(addressedKey.atAddress, addressedKey.key.key1,
					addressedKey.key.key2, previousValue, newValue));
		}

		return changes;
	}

	public static byte[] emptyMapRoot() {
		return EMPTY_MAP_ROOT.clone();
	}

	private NavigableMap<MapKey, Long> loadEntries(String atAddress) throws DataException {
		NavigableMap<MapKey, Long> entries = this.entriesByAt.get(atAddress);
		if (entries != null)
			return entries;

		entries = new TreeMap<>();
		for (ATMapEntryData entry : this.repository.getATRepository().getATMapEntries(atAddress))
			entries.put(new MapKey(entry.getKey1(), entry.getKey2()), entry.getValue());

		this.originalEntriesByAt.put(atAddress, new TreeMap<>(entries));
		this.entriesByAt.put(atAddress, entries);
		return entries;
	}

	private void requireActiveRound() {
		if (this.activeAtAddress == null)
			throw new IllegalStateException("No AT map round is active");
	}

	private void clearActiveRound() {
		this.activeAtAddress = null;
		this.activeRoundSnapshot = null;
		this.activeRoundTouchedKeys.clear();
	}

	private static final class MapKey implements Comparable<MapKey> {
		private final long key1;
		private final long key2;

		private MapKey(long key1, long key2) {
			this.key1 = key1;
			this.key2 = key2;
		}

		@Override
		public int compareTo(MapKey other) {
			int key1Comparison = Long.compare(this.key1, other.key1);
			return key1Comparison != 0 ? key1Comparison : Long.compare(this.key2, other.key2);
		}

		@Override
		public boolean equals(Object object) {
			if (this == object)
				return true;
			if (!(object instanceof MapKey))
				return false;

			MapKey other = (MapKey) object;
			return this.key1 == other.key1 && this.key2 == other.key2;
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(new long[] { this.key1, this.key2 });
		}
	}

	private static final class AddressedMapKey {
		private final String atAddress;
		private final MapKey key;

		private AddressedMapKey(String atAddress, MapKey key) {
			this.atAddress = atAddress;
			this.key = key;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object)
				return true;
			if (!(object instanceof AddressedMapKey))
				return false;

			AddressedMapKey other = (AddressedMapKey) object;
			return this.atAddress.equals(other.atAddress) && this.key.equals(other.key);
		}

		@Override
		public int hashCode() {
			return 31 * this.atAddress.hashCode() + this.key.hashCode();
		}
	}
}
