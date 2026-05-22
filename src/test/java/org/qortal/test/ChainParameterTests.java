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
	public void testBlockRewardAmountDecoding() {
		long reward = 12L * Amounts.MULTIPLIER + 34_000_000L;
		byte[] value = ChainParameter.BLOCK_REWARD.encodeLongValue(reward);

		assertEquals(Long.valueOf(reward), ChainParameter.BLOCK_REWARD.decodeAmountValue(value));
		assertEquals("12.34000000", ChainParameter.BLOCK_REWARD.formatDisplayValue(value));
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
	}
}
