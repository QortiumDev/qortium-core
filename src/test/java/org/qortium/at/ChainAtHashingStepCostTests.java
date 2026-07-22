package org.qortium.at;

import org.ciyam.at.OpCode;
import org.junit.Before;
import org.junit.Test;
import org.qortium.asset.Asset;
import org.qortium.crypto.Crypto;
import org.qortium.data.at.ATData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertEquals;

/**
 * Exact step-delta coverage for {@code atHashingStepCostHeight}. The pinned AT jar routes every
 * external-function opcode that carries a raw function code — including the hashing built-ins
 * (0x0200-0x0207) — through {@link ChainATAPI#getOpCodeSteps(OpCode, short, org.ciyam.at.MachineState)}
 * before charging steps. Below the trigger those functions keep the flat per-function cost; at/after
 * the trigger they cost {@code hashingStepCost} (20). Non-hashing functions are unaffected on both sides.
 *
 * <p>The test chain sets the trigger at height 8, so an AT running at height 7 sees the flat cost and
 * one at height 8 sees the raised cost, without minting to those heights.
 */
public class ChainAtHashingStepCostTests extends Common {

	private static final short SHA256_INTO_B = (short) 0x0204;
	private static final short CHECK_HASH160_WITH_B = (short) 0x0207;
	private static final short MD5_INTO_B = (short) 0x0200;
	private static final short NON_HASHING_FUNCTION = ChainFunctionCode.GET_ASSET_BALANCE.value; // 0x0531

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings("test-settings-v2-at-hashing-cost.json");
	}

	@Test
	public void testHashingFunctionsCostFlatBelowTriggerAndRaisedAtOrAfter() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int triggerHeight = 8;
			int flatCost = 10;   // stepsPerFunctionCall in the test chain config
			int raisedCost = 20; // ciyamAtSettings.hashingStepCost (Java default)

			ChainATAPI belowTrigger = apiAtHeight(repository, triggerHeight - 1);
			ChainATAPI atTrigger = apiAtHeight(repository, triggerHeight);

			// Below the trigger: hashing built-ins keep the flat per-function cost.
			assertEquals(flatCost, belowTrigger.getOpCodeSteps(OpCode.EXT_FUN_DAT_2, SHA256_INTO_B, null));
			assertEquals(flatCost, belowTrigger.getOpCodeSteps(OpCode.EXT_FUN_DAT_2, MD5_INTO_B, null));
			assertEquals(flatCost, belowTrigger.getOpCodeSteps(OpCode.EXT_FUN_RET_DAT_2, CHECK_HASH160_WITH_B, null));

			// At/after the trigger: hashing built-ins cost the raised amount, an exact +10 per call.
			assertEquals(raisedCost, atTrigger.getOpCodeSteps(OpCode.EXT_FUN_DAT_2, SHA256_INTO_B, null));
			assertEquals(raisedCost, atTrigger.getOpCodeSteps(OpCode.EXT_FUN_DAT_2, MD5_INTO_B, null));
			assertEquals(raisedCost, atTrigger.getOpCodeSteps(OpCode.EXT_FUN_RET_DAT_2, CHECK_HASH160_WITH_B, null));

			// Non-hashing functions are untouched on both sides of the trigger.
			assertEquals(flatCost, belowTrigger.getOpCodeSteps(OpCode.EXT_FUN_RET_DAT, NON_HASHING_FUNCTION, null));
			assertEquals(flatCost, atTrigger.getOpCodeSteps(OpCode.EXT_FUN_RET_DAT, NON_HASHING_FUNCTION, null));
		}
	}

	private static ChainATAPI apiAtHeight(Repository repository, int blockHeight) {
		byte[] creatorPublicKey = new byte[32];
		String atAddress = Crypto.toATAddress(new byte[32]);
		ATData atData = new ATData(atAddress, creatorPublicKey, 0L, Asset.NATIVE);

		return new ChainATAPI(repository, atData, blockHeight, 0L, null);
	}
}
