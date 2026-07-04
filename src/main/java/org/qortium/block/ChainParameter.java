package org.qortium.block;

import org.qortium.account.AccountTrustPolicy;
import org.qortium.data.account.AccountTrustCategoryPoliciesData;
import org.qortium.data.account.AccountTrustStatus;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.utils.Amounts;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

import static java.util.stream.Collectors.toMap;

public enum ChainParameter {
	BLOCK_REWARD(1, Long.BYTES, "AMOUNT",
			"Height-based block reward amount, expressed as a normal decimal amount in the public builder and stored on chain as an 8-byte signed long. After batched rewards start, activation heights must land on the first block covered by a new reward batch.",
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
			"/chain-parameters/account-rating/cooldown/{height}"),
	ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS(7, 5 * Integer.BYTES, "INTEGER_LIST",
			"Trust status vote-weight percentages for SUSPICIOUS, UNVERIFIED, BRONZE, SILVER, and GOLD.",
			"/chain-parameters/account-trust/status-vote-weights/update",
			"/chain-parameters/account-trust/status-vote-weights/{height}"),
	ACCOUNT_TRUST_STARTING_ENERGY(8, Long.BYTES, "LONG",
			"Starting energy distributed across minting-group seed accounts during account trust derivation.",
			"/chain-parameters/account-trust/starting-energy/update",
			"/chain-parameters/account-trust/starting-energy/{height}"),
	ACCOUNT_TRUST_MANAGER_ENERGY_HOPS(9, Integer.BYTES, "INTEGER",
			"Number of manager-rating propagation hops used during account trust derivation.",
			"/chain-parameters/account-trust/manager-energy-hops/update",
			"/chain-parameters/account-trust/manager-energy-hops/{height}"),
	ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT(10, Integer.BYTES, "INTEGER",
			"Minimum number of independent positive trust branches required for positive account trust levels.",
			"/chain-parameters/account-trust/positive-min-branch-count/update",
			"/chain-parameters/account-trust/positive-min-branch-count/{height}"),
	ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT(11, Integer.BYTES, "INTEGER",
			"Minimum number of independent negative raters required for suspicious account trust levels.",
			"/chain-parameters/account-trust/suspicious-min-rater-count/update",
			"/chain-parameters/account-trust/suspicious-min-rater-count/{height}"),
	ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT(12, Integer.BYTES, "INTEGER",
			"Minimum number of independent negative trust branches required for suspicious account trust levels; zero follows the effective suspicious rater count.",
			"/chain-parameters/account-trust/suspicious-min-branch-count/update",
			"/chain-parameters/account-trust/suspicious-min-branch-count/{height}"),
	ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE(13, Integer.BYTES, "INTEGER",
			"Minimum negative rating confidence required to count toward suspicious account trust levels.",
			"/chain-parameters/account-trust/suspicious-min-rating-confidence/update",
			"/chain-parameters/account-trust/suspicious-min-rating-confidence/{height}"),
	ACCOUNT_TRUST_CATEGORY_POLICIES(14, AccountTrustCategoryPolicyCodec.ENCODED_LENGTH,
			"ACCOUNT_TRUST_CATEGORY_POLICIES",
			"Category-specific account trust thresholds and score caps for SUBJECT, PLAYER, TRAINER, and MANAGER trust derivation.",
			"/chain-parameters/account-trust/category-policies/update",
			"/chain-parameters/account-trust/category-policies/{height}");

	public static final int MAX_VALUE_LENGTH = 256;
	public static final String VALUE_TYPE_AMOUNT = "AMOUNT";
	public static final String VALUE_TYPE_LONG = "LONG";
	public static final String VALUE_TYPE_INTEGER = "INTEGER";
	public static final String VALUE_TYPE_INTEGER_LIST = "INTEGER_LIST";
	public static final String VALUE_TYPE_ACCOUNT_TRUST_CATEGORY_POLICIES = "ACCOUNT_TRUST_CATEGORY_POLICIES";

	private static final String[] REWARD_SHARE_WEIGHT_LABELS = buildRewardShareWeightLabels();
	private static final String[] TRUST_STATUS_VOTE_WEIGHT_LABELS = Arrays.stream(AccountTrustStatus.values())
			.map(AccountTrustStatus::name)
			.toArray(String[]::new);

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

	public Long getMinimumLongValue() {
		switch (this) {
			case BLOCK_REWARD:
			case UNIT_FEE:
			case NAME_REGISTRATION_UNIT_FEE:
				return 0L;

			case ACCOUNT_TRUST_STARTING_ENERGY:
				return 1L;

			default:
				return null;
		}
	}

	public Integer getMinimumIntegerValue() {
		switch (this) {
			case MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN:
			case ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS:
			case ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT:
				return 0;

			case ACCOUNT_TRUST_MANAGER_ENERGY_HOPS:
			case ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT:
			case ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT:
			case ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE:
				return 1;

			default:
				return null;
		}
	}

	public Integer getIntegerListLength() {
		switch (this) {
			case REWARD_SHARE_WEIGHTS:
			case ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS:
				return this.valueLength / Integer.BYTES;

			default:
				return null;
		}
	}

	public Integer getMaximumIntegerValue() {
		switch (this) {
			case ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE:
				return 4;

			default:
				return null;
		}
	}

	public Integer getMinimumIntegerListValue() {
		switch (this) {
			case REWARD_SHARE_WEIGHTS:
			case ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS:
				return 0;

			default:
				return null;
		}
	}

	public Integer getMaximumIntegerListValue() {
		switch (this) {
			case ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS:
				return 100;

			default:
				return null;
		}
	}

	public String[] getIntegerListLabels() {
		switch (this) {
			case REWARD_SHARE_WEIGHTS:
				return REWARD_SHARE_WEIGHT_LABELS.clone();

			case ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS:
				return TRUST_STATUS_VOTE_WEIGHT_LABELS.clone();

			default:
				return null;
		}
	}

	public boolean requiresPositiveTotal() {
		return this == REWARD_SHARE_WEIGHTS;
	}

	public boolean requiresPositiveFirstValue() {
		return this == REWARD_SHARE_WEIGHTS;
	}

	public boolean requiresAnyPositiveValue() {
		return this == ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS;
	}

	public boolean affectsTrustSnapshots() {
		return this == ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS || this == ACCOUNT_TRUST_STARTING_ENERGY
				|| this == ACCOUNT_TRUST_MANAGER_ENERGY_HOPS
				|| this == ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT
				|| this == ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT
				|| this == ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT
				|| this == ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE
				|| this == ACCOUNT_TRUST_CATEGORY_POLICIES;
	}

	public boolean isValidValue(byte[] value) {
		if (value == null || value.length != this.valueLength)
			return false;

		switch (this) {
			case BLOCK_REWARD:
			case UNIT_FEE:
			case NAME_REGISTRATION_UNIT_FEE:
				return decodeLongValue(value) >= 0;

			case ACCOUNT_TRUST_STARTING_ENERGY:
				return decodeLongValue(value) > 0;

			case MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN:
			case ACCOUNT_RATING_CHANGE_COOLDOWN_BLOCKS:
				return decodeIntValue(value) >= 0;

			case ACCOUNT_TRUST_SUSPICIOUS_MIN_BRANCH_COUNT:
				return decodeIntValue(value) >= 0;

			case ACCOUNT_TRUST_MANAGER_ENERGY_HOPS:
			case ACCOUNT_TRUST_POSITIVE_MIN_BRANCH_COUNT:
			case ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT:
				return decodeIntValue(value) > 0;

			case ACCOUNT_TRUST_SUSPICIOUS_MIN_RATING_CONFIDENCE:
				int confidence = decodeIntValue(value);
				return confidence >= 1 && confidence <= 4;

			case REWARD_SHARE_WEIGHTS:
				int[] weights = decodeIntArrayValue(value);
				long totalWeight = 0;
				for (int weight : weights) {
					if (weight < 0)
						return false;

					totalWeight += weight;
				}

				return weights[0] > 0 && totalWeight > 0;

			case ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS:
				int[] voteWeightPercents = decodeIntArrayValue(value);
				boolean hasPositiveVoteWeight = false;
				for (int voteWeightPercent : voteWeightPercents) {
					if (voteWeightPercent < 0 || voteWeightPercent > 100)
						return false;

					if (voteWeightPercent > 0)
						hasPositiveVoteWeight = true;
				}

				return hasPositiveVoteWeight;

			case ACCOUNT_TRUST_CATEGORY_POLICIES:
				return true;

			default:
				return false;
		}
	}

	public boolean isValidValue(Repository repository, int activationHeight, byte[] value) throws DataException {
		if (!isValidValue(value))
			return false;

		switch (this) {
			case ACCOUNT_TRUST_SUSPICIOUS_MIN_RATER_COUNT:
				int suspiciousMinRaterCount = decodeIntValue(value);
				return AccountTrustPolicy.getCategoryPolicySettings(repository, activationHeight)
						.canReachSuspiciousThresholds(suspiciousMinRaterCount);

			case ACCOUNT_TRUST_CATEGORY_POLICIES:
				int effectiveSuspiciousMinRaterCount = BlockChain.getInstance()
						.getAccountTrustSuspiciousMinRaterCount(repository, activationHeight);
				return AccountTrustCategoryPolicyCodec.isValid(value, effectiveSuspiciousMinRaterCount);

			default:
				return true;
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
		if (!isValidValue(value) || !VALUE_TYPE_AMOUNT.equals(this.valueType))
			return null;

		return decodeLongValue(value);
	}

	public Long decodeLongParameterValue(byte[] value) {
		if (!isValidValue(value) || !VALUE_TYPE_LONG.equals(this.valueType))
			return null;

		return decodeLongValue(value);
	}

	public static Long decodeLongParameterValue(String valueType, int valueLength, byte[] value) {
		if (!VALUE_TYPE_LONG.equals(valueType) || valueLength != Long.BYTES || value == null
				|| value.length != Long.BYTES)
			return null;

		return ByteBuffer.wrap(value).getLong();
	}

	public static String formatLongParameterDisplayValue(String valueType, int valueLength, byte[] value) {
		Long longValue = decodeLongParameterValue(valueType, valueLength, value);
		return longValue == null ? null : Long.toString(longValue);
	}

	public Integer decodeIntegerValue(byte[] value) {
		if (!isValidValue(value) || !VALUE_TYPE_INTEGER.equals(this.valueType))
			return null;

		return decodeIntValue(value);
	}

	public int[] decodeIntegerListValue(byte[] value) {
		if (!isValidValue(value) || !VALUE_TYPE_INTEGER_LIST.equals(this.valueType))
			return null;

		return decodeIntArrayValue(value);
	}

	public AccountTrustCategoryPoliciesData decodeAccountTrustCategoryPoliciesValue(byte[] value) {
		if (!isValidValue(value) || !VALUE_TYPE_ACCOUNT_TRUST_CATEGORY_POLICIES.equals(this.valueType))
			return null;

		return AccountTrustCategoryPolicyCodec.decode(value);
	}

	public String formatDisplayValue(byte[] value) {
		if (!isValidValue(value))
			return null;

		switch (this.valueType) {
			case VALUE_TYPE_AMOUNT:
				return Amounts.prettyAmount(decodeLongValue(value));

			case VALUE_TYPE_LONG:
				return Long.toString(decodeLongValue(value));

			case VALUE_TYPE_INTEGER:
				return Integer.toString(decodeIntValue(value));

			case VALUE_TYPE_INTEGER_LIST:
				return Arrays.toString(decodeIntArrayValue(value));

			default:
				return null;
		}
	}

	private static String[] buildRewardShareWeightLabels() {
		String[] labels = new String[BlockChain.REWARD_SHARE_LEVEL_COUNT];
		for (int i = 0; i < labels.length; ++i)
			labels[i] = "Level " + (i + 1);

		return labels;
	}
}
