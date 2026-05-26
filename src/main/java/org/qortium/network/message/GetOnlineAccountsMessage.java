package org.qortium.network.message;

import com.google.common.primitives.Longs;
import org.qortium.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * For requesting online accounts info from remote peer, given our list of online accounts.
 */
public class GetOnlineAccountsMessage extends Message {

	private static final Map<Long, Map<Byte, byte[]>> EMPTY_ONLINE_ACCOUNTS = Collections.emptyMap();
	private Map<Long, Map<Byte, byte[]>> hashesByTimestampThenByte;

	public GetOnlineAccountsMessage(Map<Long, Map<Byte, byte[]>> hashesByTimestampThenByte) {
		super(MessageType.GET_ONLINE_ACCOUNTS);

		// If we don't have ANY online accounts then it's an easier construction...
		if (hashesByTimestampThenByte.isEmpty()) {
			this.dataBytes = EMPTY_DATA_BYTES;
			return;
		}

		// We should know exactly how many bytes to allocate now
		int byteSize = hashesByTimestampThenByte.size() * (Transformer.TIMESTAMP_LENGTH + Transformer.BYTE_LENGTH);

		byteSize += hashesByTimestampThenByte.values()
				.stream()
				.mapToInt(map -> map.size() * Transformer.PUBLIC_KEY_LENGTH)
				.sum();

		ByteArrayOutputStream bytes = new ByteArrayOutputStream(byteSize);

		// Warning: no double-checking/fetching! We must be ConcurrentMap compatible.
		// So no contains() then get() or multiple get()s on the same key/map.
		try {
			for (var outerMapEntry : hashesByTimestampThenByte.entrySet()) {
				bytes.write(Longs.toByteArray(outerMapEntry.getKey()));

				var innerMap = outerMapEntry.getValue();

				// Number of entries: 1 - 256, where 256 is represented by 0
				bytes.write(innerMap.size() & 0xFF);

				for (byte[] hashBytes : innerMap.values()) {
					bytes.write(hashBytes);
				}
			}
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private GetOnlineAccountsMessage(int id, Map<Long, Map<Byte, byte[]>> hashesByTimestampThenByte) {
		super(id, MessageType.GET_ONLINE_ACCOUNTS);

		this.hashesByTimestampThenByte = hashesByTimestampThenByte;
	}

	public Map<Long, Map<Byte, byte[]>> getHashesByTimestampThenByte() {
		return this.hashesByTimestampThenByte;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) {
		// 'empty' case
		if (!bytes.hasRemaining()) {
			return new GetOnlineAccountsMessage(id, EMPTY_ONLINE_ACCOUNTS);
		}

		Map<Long, Map<Byte, byte[]>> hashesByTimestampThenByte = new HashMap<>();

		while (bytes.hasRemaining()) {
			long timestamp = bytes.getLong();

			int hashCount = bytes.get();
			if (hashCount <= 0)
				// 256 is represented by 0.
				// Also converts negative signed value (e.g. -1) to proper positive unsigned value (255)
				hashCount += 256;

			Map<Byte, byte[]> hashesByByte = new HashMap<>();

			for (int i = 0; i < hashCount; ++i) {
				byte[] publicKeyHash = new byte[Transformer.PUBLIC_KEY_LENGTH];
				bytes.get(publicKeyHash);

				hashesByByte.put(publicKeyHash[0], publicKeyHash);
			}

			hashesByTimestampThenByte.put(timestamp, hashesByByte);
		}

		return new GetOnlineAccountsMessage(id, hashesByTimestampThenByte);
	}

}
