package org.qortal.test;

import org.junit.Test;
import org.qortal.block.AccountTrustCategoryPolicyCodec;
import org.qortal.block.ChainParameter;
import org.qortal.utils.Amounts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChainParameterTests {

	@Test
	public void testBlockRewardMetadataAccessors() {
		ChainParameter parameter = ChainParameter.BLOCK_REWARD;

		assertEquals(1, parameter.id);
		assertEquals(Long.BYTES, parameter.valueLength);
		assertEquals("AMOUNT", parameter.getValueType());
		assertNotNull(parameter.getDescription());
		assertEquals("/chain-parameters/block-reward/update", parameter.getBuilderPath());
		assertEquals("/chain-parameters/block-reward/{height}", parameter.getEffectivePath());
	}

	@Test
	public void testMinAccountsToActivateShareBinMetadataAccessors() {
		ChainParameter parameter = ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN;

		assertEquals(2, parameter.id);
		assertEquals(Integer.BYTES, parameter.valueLength);
		assertEquals("INTEGER", parameter.getValueType());
		assertNotNull(parameter.getDescription());
		assertEquals("/chain-parameters/share-bin/min-accounts/update", parameter.getBuilderPath());
		assertEquals("/chain-parameters/share-bin/min-accounts/{height}", parameter.getEffectivePath());
	}

	@Test
	public void testUnitFeeMetadataAccessors() {
		ChainParameter parameter = ChainParameter.UNIT_FEE;

		assertEquals(3, parameter.id);
		assertEquals(Long.BYTES, parameter.valueLength);
		assertEquals("AMOUNT", parameter.getValueType());
		assertNotNull(parameter.getDescription());
		assertEquals("/chain-parameters/unit-fee/update", parameter.getBuilderPath());
		assertEquals("/chain-parameters/unit-fee/{height}", parameter.getEffectivePath());
	}

	@Test
	public void testNameRegistrationUnitFeeMetadataAccessors() {
		ChainParameter parameter = ChainParameter.NAME_REGISTRATION_UNIT_FEE;

		assertEquals(4, parameter.id);
		assertEquals(Long.BYTES, parameter.valueLength);
		assertEquals("AMOUNT", parameter.getValueType());
		assertNotNull(parameter.getDescription());
		assertEquals("/chain-parameters/name-registration-unit-fee/update", parameter.getBuilderPath());
		assertEquals("/chain-parameters/name-registration-unit-fee/{height}", parameter.getEffectivePath());
	}

	@Test
	public void testAccountTrustStartingEnergyMetadataAccessors() {
		ChainParameter parameter = ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY;

		assertEquals(8, parameter.id);
		assertEquals(Long.BYTES, parameter.valueLength);
		assertEquals("LONG", parameter.getValueType());
		assertEquals(Long.valueOf(1L), parameter.getMinimumLongValue());
		assertTrue(parameter.affectsTrustSnapshots());
		assertNotNull(parameter.getDescription());
		assertEquals("/chain-parameters/account-trust/starting-energy/update", parameter.getBuilderPath());
		assertEquals("/chain-parameters/account-trust/starting-energy/{height}", parameter.getEffectivePath());
	}

	@Test
	public void testAccountTrustManagerEnergyHopsMetadataAccessors() {
		ChainParameter parameter = ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS;

		assertEquals(9, parameter.id);
		assertEquals(Integer.BYTES, parameter.valueLength);
		assertEquals("INTEGER", parameter.getValueType());
		assertEquals(Integer.valueOf(1), parameter.getMinimumIntegerValue());
		assertTrue(parameter.affectsTrustSnapshots());
		assertNotNull(parameter.getDescription());
		assertEquals("/chain-parameters/account-trust/manager-energy-hops/update", parameter.getBuilderPath());
		assertEquals("/chain-parameters/account-trust/manager-energy-hops/{height}", parameter.getEffectivePath());
	}

	@Test
	public void testAccountTrustCategoryPoliciesMetadataAccessors() {
		ChainParameter parameter = ChainParameter.ACCOUNT_TRUST_CATEGORY_POLICIES;

		assertEquals(14, parameter.id);
		assertEquals(AccountTrustCategoryPolicyCodec.ENCODED_LENGTH, parameter.valueLength);
		assertEquals(ChainParameter.VALUE_TYPE_ACCOUNT_TRUST_CATEGORY_POLICIES, parameter.getValueType());
		assertTrue(parameter.affectsTrustSnapshots());
		assertNotNull(parameter.getDescription());
		assertEquals("/chain-parameters/account-trust/category-policies/update", parameter.getBuilderPath());
		assertEquals("/chain-parameters/account-trust/category-policies/{height}", parameter.getEffectivePath());
	}

	@Test
	public void testBlockRewardAmountDecoding() {
		long reward = 12L * Amounts.MULTIPLIER + 34_000_000L;
		byte[] value = ChainParameter.BLOCK_REWARD.encodeLongValue(reward);

		assertEquals(Long.valueOf(reward), ChainParameter.BLOCK_REWARD.decodeAmountValue(value));
		assertEquals("12.34000000", ChainParameter.BLOCK_REWARD.formatDisplayValue(value));
	}

	@Test
	public void testUnitFeeAmountDecoding() {
		long unitFee = 1_234_567L;
		byte[] value = ChainParameter.UNIT_FEE.encodeLongValue(unitFee);

		assertEquals(Long.valueOf(unitFee), ChainParameter.UNIT_FEE.decodeAmountValue(value));
		assertEquals("0.01234567", ChainParameter.UNIT_FEE.formatDisplayValue(value));
	}

	@Test
	public void testNameRegistrationUnitFeeAmountDecoding() {
		long nameRegistrationUnitFee = 125L * Amounts.MULTIPLIER;
		byte[] value = ChainParameter.NAME_REGISTRATION_UNIT_FEE.encodeLongValue(nameRegistrationUnitFee);

		assertEquals(Long.valueOf(nameRegistrationUnitFee), ChainParameter.NAME_REGISTRATION_UNIT_FEE.decodeAmountValue(value));
		assertEquals("125.00000000", ChainParameter.NAME_REGISTRATION_UNIT_FEE.formatDisplayValue(value));
	}

	@Test
	public void testPlainLongValueDecoding() {
		long plainLong = 1_234_567_890L;
		byte[] value = ChainParameter.BLOCK_REWARD.encodeLongValue(plainLong);

		assertEquals(Long.valueOf(plainLong), ChainParameter.decodeLongParameterValue(
				ChainParameter.VALUE_TYPE_LONG, Long.BYTES, value));
		assertEquals("1234567890", ChainParameter.formatLongParameterDisplayValue(
				ChainParameter.VALUE_TYPE_LONG, Long.BYTES, value));

		assertNull(ChainParameter.decodeLongParameterValue(ChainParameter.VALUE_TYPE_AMOUNT, Long.BYTES, value));
		assertNull(ChainParameter.decodeLongParameterValue(ChainParameter.VALUE_TYPE_LONG, Integer.BYTES, value));
		assertNull(ChainParameter.decodeLongParameterValue(ChainParameter.VALUE_TYPE_LONG, Long.BYTES, new byte[0]));
		assertNull(ChainParameter.formatLongParameterDisplayValue(
				ChainParameter.VALUE_TYPE_LONG, Long.BYTES, new byte[0]));
	}

	@Test
	public void testAmountValuesDoNotDecodeAsPlainLongs() {
		long reward = 12L * Amounts.MULTIPLIER;
		byte[] value = ChainParameter.BLOCK_REWARD.encodeLongValue(reward);

		assertEquals(Long.valueOf(reward), ChainParameter.BLOCK_REWARD.decodeAmountValue(value));
		assertNull(ChainParameter.BLOCK_REWARD.decodeLongParameterValue(value));
	}

	@Test
	public void testAccountTrustStartingEnergyLongDecoding() {
		long startingEnergy = 1_234_567L;
		byte[] value = ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.encodeLongValue(startingEnergy);

		assertEquals(Long.valueOf(startingEnergy),
				ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.decodeLongParameterValue(value));
		assertEquals("1234567", ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.formatDisplayValue(value));
		assertNull(ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.decodeAmountValue(value));
		assertNull(ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.decodeIntegerValue(value));
		assertNull(ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.decodeIntegerListValue(value));
	}

	@Test
	public void testMinAccountsToActivateShareBinIntegerDecoding() {
		byte[] value = ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.encodeIntValue(30);

		assertEquals(Integer.valueOf(30), ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.decodeIntegerValue(value));
		assertEquals("30", ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.formatDisplayValue(value));
		assertNull(ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.decodeAmountValue(value));
	}

	@Test
	public void testAccountTrustManagerEnergyHopsIntegerDecoding() {
		byte[] value = ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.encodeIntValue(4);

		assertEquals(Integer.valueOf(4), ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.decodeIntegerValue(value));
		assertEquals("4", ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.formatDisplayValue(value));
		assertNull(ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.decodeAmountValue(value));
		assertNull(ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.decodeLongParameterValue(value));
		assertNull(ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.decodeIntegerListValue(value));
	}

	@Test
	public void testInvalidBlockRewardValueDoesNotDecodeForDisplay() {
		assertNull(ChainParameter.BLOCK_REWARD.decodeAmountValue(null));
		assertNull(ChainParameter.BLOCK_REWARD.formatDisplayValue(null));
		assertNull(ChainParameter.BLOCK_REWARD.decodeAmountValue(new byte[0]));
		assertNull(ChainParameter.BLOCK_REWARD.formatDisplayValue(new byte[0]));

		byte[] negativeValue = ChainParameter.BLOCK_REWARD.encodeLongValue(-1L);
		assertNull(ChainParameter.BLOCK_REWARD.decodeAmountValue(negativeValue));
		assertNull(ChainParameter.BLOCK_REWARD.formatDisplayValue(negativeValue));

		negativeValue = ChainParameter.UNIT_FEE.encodeLongValue(-1L);
		assertNull(ChainParameter.UNIT_FEE.decodeAmountValue(negativeValue));
		assertNull(ChainParameter.UNIT_FEE.formatDisplayValue(negativeValue));

		negativeValue = ChainParameter.NAME_REGISTRATION_UNIT_FEE.encodeLongValue(-1L);
		assertNull(ChainParameter.NAME_REGISTRATION_UNIT_FEE.decodeAmountValue(negativeValue));
		assertNull(ChainParameter.NAME_REGISTRATION_UNIT_FEE.formatDisplayValue(negativeValue));
	}

	@Test
	public void testInvalidIntegerValueDoesNotDecodeForDisplay() {
		assertNull(ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.decodeIntegerValue(null));
		assertNull(ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.formatDisplayValue(null));
		assertNull(ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.decodeIntegerValue(new byte[0]));
		assertNull(ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.formatDisplayValue(new byte[0]));

		byte[] negativeValue = ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.encodeIntValue(-1);
		assertNull(ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.decodeIntegerValue(negativeValue));
		assertNull(ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.formatDisplayValue(negativeValue));
	}

	@Test
	public void testRewardShareWeightsRequirePositiveLevelOneWeight() {
		assertTrue(ChainParameter.REWARD_SHARE_WEIGHTS.isValidValue(
				ChainParameter.REWARD_SHARE_WEIGHTS.encodeIntArrayValue(
						new int[] { 1, 0, 0, 0, 0, 0, 0, 0, 0, 0 })));

		assertFalse(ChainParameter.REWARD_SHARE_WEIGHTS.isValidValue(
				ChainParameter.REWARD_SHARE_WEIGHTS.encodeIntArrayValue(
						new int[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 })));
		assertFalse(ChainParameter.REWARD_SHARE_WEIGHTS.isValidValue(
				ChainParameter.REWARD_SHARE_WEIGHTS.encodeIntArrayValue(
						new int[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 })));
	}

	@Test
	public void testTrustStatusVoteWeightsRequireAtLeastOnePositiveWeight() {
		assertTrue(ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.isValidValue(
				ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.encodeIntArrayValue(new int[] { 0, 0, 0, 0, 100 })));

		assertFalse(ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.isValidValue(
				ChainParameter.ACCOUNT_TRUST_STATUS_VOTE_WEIGHTS.encodeIntArrayValue(new int[] { 0, 0, 0, 0, 0 })));
	}

	@Test
	public void testAccountTrustStartingEnergyRequiresPositiveValue() {
		assertTrue(ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.isValidValue(
				ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.encodeLongValue(1L)));
		assertFalse(ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.isValidValue(
				ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.encodeLongValue(0L)));
		assertFalse(ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.isValidValue(
				ChainParameter.ACCOUNT_TRUST_STARTING_ENERGY.encodeLongValue(-1L)));
	}

	@Test
	public void testAccountTrustManagerEnergyHopsRequiresPositiveValue() {
		assertTrue(ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.isValidValue(
				ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.encodeIntValue(1)));
		assertFalse(ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.isValidValue(
				ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.encodeIntValue(0)));
		assertFalse(ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.isValidValue(
				ChainParameter.ACCOUNT_TRUST_MANAGER_ENERGY_HOPS.encodeIntValue(-1)));
	}
}
