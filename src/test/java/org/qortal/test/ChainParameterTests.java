package org.qortal.test;

import org.junit.Test;
import org.qortal.block.ChainParameter;
import org.qortal.utils.Amounts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
	public void testMinAccountsToActivateShareBinIntegerDecoding() {
		byte[] value = ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.encodeIntValue(30);

		assertEquals(Integer.valueOf(30), ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.decodeIntegerValue(value));
		assertEquals("30", ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.formatDisplayValue(value));
		assertNull(ChainParameter.MIN_ACCOUNTS_TO_ACTIVATE_SHARE_BIN.decodeAmountValue(value));
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
}
