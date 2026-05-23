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
			"/chain-parameters/share-bin/min-accounts/{height}"),
	UNIT_FEE(3, Long.BYTES, "AMOUNT",
			"Normal transaction unit fee, expressed as a normal decimal amount in the public builder and stored on chain as an 8-byte signed long.",
			"/chain-parameters/unit-fee/update",
			"/chain-parameters/unit-fee/{height}"),
	NAME_REGISTRATION_UNIT_FEE(4, Long.BYTES, "AMOUNT",
			"Name-registration transaction unit fee, expressed as a normal decimal amount in the public builder and stored on chain as an 8-byte signed long.",
			"/chain-parameters/name-registration-unit-fee/update",
			"/chain-parameters/name-registration-unit-fee/{height}"),
	REWARD_SHARE_WEIGHTS(5, 10 * Integer.BYTES, "INTEGER_LIST",
			"Reward share weights for levels 1 through 10, stored as 10 signed integers and normalized into level shares at activation height.",
			"/chain-parameters/reward-share-weights/update",
			"/chain-parameters/reward-share-weights/{height}"),
	ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS(6, Integer.BYTES, "INTEGER",
			"Number of blocks before the same rater can change or remove a rating for the same target and category edge.",
			"/chain-parameters/account-rating/cooldown/update",
			"/chain-parameters/account-rating/cooldown/{height}");

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
			case UNIT_FEE:
			case NAME_REGISTRATION_UNIT_FEE:
				return decodeLongValue(value) >= 0;

			case MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN:
			case ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS:
				return decodeIntValue(value) >= 0;

			case REWARD_SHARE_WEIGHTS:
				int[] weights = decodeIntArrayValue(value);
				long totalWeight = 0;
				for (int weight : weights) {
					if (weight < 0)
						return false;

					totalWeight += weight;
				}

				return totalWeight > 0;

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

	public int[] decodeIntArrayValue(byte[] value) {
		if (value == null || value.length % Integer.BYTES != 0)
			throw new IllegalArgumentException("Chain parameter value is not an int array");

		ByteBuffer byteBuffer = ByteBuffer.wrap(value);
		int[] values = new int[value.length / Integer.BYTES];
		for (int i = 0; i < values.length; ++i)
			values[i] = byteBuffer.getInt();

		return values;
	}

	public byte[] encodeIntArrayValue(int[] values) {
		if (values == null)
			return null;

		ByteBuffer byteBuffer = ByteBuffer.allocate(values.length * Integer.BYTES);
		for (int value : values)
			byteBuffer.putInt(value);

		return byteBuffer.array();
	}

	public Long decodeAmountValue(byte[] value) {
		if (!isValidValue(value))
			return null;

		switch (this) {
			case BLOCK_REWARD:
			case UNIT_FEE:
			case NAME_REGISTRATION_UNIT_FEE:
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
			case ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS:
				return decodeIntValue(value);

			default:
				return null;
		}
	}

	public int[] decodeIntegerListValue(byte[] value) {
		if (!isValidValue(value))
			return null;

		switch (this) {
			case REWARD_SHARE_WEIGHTS:
				return decodeIntArrayValue(value);

			default:
				return null;
		}
	}

	public String formatDisplayValue(byte[] value) {
		if (!isValidValue(value))
			return null;

		switch (this) {
			case BLOCK_REWARD:
			case UNIT_FEE:
			case NAME_REGISTRATION_UNIT_FEE:
				return Amounts.prettyAmount(decodeLongValue(value));

			case MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN:
			case ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS:
				return Integer.toString(decodeIntValue(value));

			case REWARD_SHARE_WEIGHTS:
				return Arrays.toString(decodeIntArrayValue(value));

			default:
				return null;
		}
	}
}
