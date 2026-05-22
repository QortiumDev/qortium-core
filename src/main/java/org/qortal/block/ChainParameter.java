package org.qortal.block;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

import static java.util.stream.Collectors.toMap;

public enum ChainParameter {
	BLOCK_REWARD(1, Long.BYTES);

	public static final int MAX_VALUE_LENGTH = 256;

	private static final Map<Integer, ChainParameter> map = Arrays.stream(ChainParameter.values())
			.collect(toMap(parameter -> parameter.id, parameter -> parameter));

	public final int id;
	public final int valueLength;

	ChainParameter(int id, int valueLength) {
		this.id = id;
		this.valueLength = valueLength;
	}

	public static ChainParameter valueOf(int id) {
		return map.get(id);
	}

	public boolean isValidValue(byte[] value) {
		if (value == null || value.length != this.valueLength)
			return false;

		switch (this) {
			case BLOCK_REWARD:
				return decodeLongValue(value) >= 0;

			default:
				return false;
		}
	}

	public long decodeLongValue(byte[] value) {
		if (value == null || value.length != Long.BYTES)
			throw new IllegalArgumentException("Chain parameter value is not a long");

		return ByteBuffer.wrap(value).getLong();
	}

	public byte[] encodeLongValue(long value) {
		return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
	}
}
