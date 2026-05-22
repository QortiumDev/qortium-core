package org.qortal.block;

import org.qortal.utils.Amounts;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

import static java.util.stream.Collectors.toMap;

public enum ChainParameter {
	BLOCK_REWARD(1, Long.BYTES, "AMOUNT",
			"Height-based block reward amount, expressed as a normal decimal amount in the public builder and stored on chain as an 8-byte signed long.",
			"/chain-parameters/block-reward/update",
			"/chain-parameters/block-reward/{height}"),
	MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN(2, Integer.BYTES, "INTEGER",
			"Minimum number of online minters required before a reward share bin is considered active.",
			"/chain-parameters/share-bin/min-accounts/update",
			"/chain-parameters/share-bin/min-accounts/{height}");

	public static final int MAX_VALUE_LENGTH = 256;

	private static final Map<Integer, ChainParameter> map = Arrays.stream(ChainParameter.values())
			.collect(toMap(parameter -> parameter.id, parameter -> parameter));

	public final int id;
	public final int valueLength;
	private final String valueType;
	private final String description;
	private final String builderPath;
	private final String effectivePath;

	ChainParameter(int id, int valueLength, String valueType, String description, String builderPath, String effectivePath) {
		this.id = id;
		this.valueLength = valueLength;
		this.valueType = valueType;
		this.description = description;
		this.builderPath = builderPath;
		this.effectivePath = effectivePath;
	}

	public static ChainParameter valueOf(int id) {
		return map.get(id);
	}

	public String getValueType() {
		return this.valueType;
	}

	public String getDescription() {
		return this.description;
	}

	public String getBuilderPath() {
		return this.builderPath;
	}

	public String getEffectivePath() {
		return this.effectivePath;
	}

	public boolean isValidValue(byte[] value) {
		if (value == null || value.length != this.valueLength)
			return false;

		switch (this) {
			case BLOCK_REWARD:
				return decodeLongValue(value) >= 0;

			case MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN:
				return decodeIntValue(value) >= 0;

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

	public int decodeIntValue(byte[] value) {
		if (value == null || value.length != Integer.BYTES)
			throw new IllegalArgumentException("Chain parameter value is not an int");

		return ByteBuffer.wrap(value).getInt();
	}

	public byte[] encodeIntValue(int value) {
		return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
	}

	public Long decodeAmountValue(byte[] value) {
		if (!isValidValue(value))
			return null;

		switch (this) {
			case BLOCK_REWARD:
				return decodeLongValue(value);

			default:
				return null;
		}
	}

	public Integer decodeIntegerValue(byte[] value) {
		if (!isValidValue(value))
			return null;

		switch (this) {
			case MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN:
				return decodeIntValue(value);

			default:
				return null;
		}
	}

	public String formatDisplayValue(byte[] value) {
		if (!isValidValue(value))
			return null;

		switch (this) {
			case BLOCK_REWARD:
				return Amounts.prettyAmount(decodeLongValue(value));

			case MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN:
				return Integer.toString(decodeIntValue(value));

			default:
				return null;
		}
	}
}
