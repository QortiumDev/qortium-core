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
import org.qortium.settings.Settings;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.utils.NTP;

import static org.junit.Assert.assertEquals;

/**
 * Exact step-delta coverage for {@code atHashingStepCostHeight}. The pinned AT jar routes every
 * external-function opcode that carries a raw function code — including the hashing built-ins
 * (0x0200-0x0207) — through {@link ChainATAPI#getOpCodeSteps(OpCode, short, org.ciyam.at.MachineState)}
 * before charging steps. Below the trigger those functions keep the flat per-function cost; at/after
 * the trigger they cost {@code hashingStepCost} (20). Non-hashing functions are unaffected on both sides.
 *
 * <p>The gate is keyed on the <em>locally-derived</em> execution height — the repository's blockchain
 * height + 1 ({@code getCurrentBlockHeight() + 1}, the block the AT runs in), exactly like the shipped
 * {@code isPayoutSolvencyEnforced} and {@code isCheckedArithmeticActive} gates — NEVER the
 * {@code blockHeight} threaded in from the block being validated (which, for a network block, is the
 * peer-supplied, non-canonical {@code BlockData.height}). Each test therefore mints the repository to a
 * boundary height; the trigger is at height 8, so a repository tip of 6 (this-block height 7) exercises
 * the flat cost and one at tip 7 (this-block height 8) the raised cost — the Previewnet 69,999 -> 70,000
 * analogue.
 */
public class ChainAtHashingStepCostTests extends Common {

	private static final int TRIGGER_HEIGHT = 8;

	private static final short SHA256_INTO_B = (short) 0x0204;
	private static final short CHECK_HASH160_WITH_B = (short) 0x0207;
	private static final short MD5_INTO_B = (short) 0x0200;
	private static final short NON_HASHING_FUNCTION = ChainFunctionCode.GET_ASSET_BALANCE.value; // 0x0531

	private static final int FLAT_COST = 10;   // stepsPerFunctionCall in the test chain config
	private static final int RAISED_COST = 20; // ciyamAtSettings.hashingStepCost (Java default)

	// Repository tip heights whose "next block" straddles the trigger.
	private static final int BELOW_TRIGGER_TIP = TRIGGER_HEIGHT - 2; // this-block height 7
	private static final int AT_TRIGGER_TIP = TRIGGER_HEIGHT - 1;     // this-block height 8

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings("test-settings-v2-at-hashing-cost.json");
		// useSettings (unlike useDefaultSettings) does not prime NTP; minting needs a non-null clock.
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
	}

	@Test
	public void testHashingFunctionsCostFlatBelowTriggerAndRaisedAtOrAfter() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI belowTrigger = apiAtRepoHeight(repository, BELOW_TRIGGER_TIP);

			// Below the trigger: hashing built-ins keep the flat per-function cost.
			assertEquals(FLAT_COST, belowTrigger.getOpCodeSteps(OpCode.EXT_FUN_DAT_2, SHA256_INTO_B, null));
			assertEquals(FLAT_COST, belowTrigger.getOpCodeSteps(OpCode.EXT_FUN_DAT_2, MD5_INTO_B, null));
			assertEquals(FLAT_COST, belowTrigger.getOpCodeSteps(OpCode.EXT_FUN_RET_DAT_2, CHECK_HASH160_WITH_B, null));

			// Non-hashing functions are untouched below the trigger.
			assertEquals(FLAT_COST, belowTrigger.getOpCodeSteps(OpCode.EXT_FUN_RET_DAT, NON_HASHING_FUNCTION, null));
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI atTrigger = apiAtRepoHeight(repository, AT_TRIGGER_TIP);

			// At/after the trigger: hashing built-ins cost the raised amount, an exact +10 per call.
			assertEquals(RAISED_COST, atTrigger.getOpCodeSteps(OpCode.EXT_FUN_DAT_2, SHA256_INTO_B, null));
			assertEquals(RAISED_COST, atTrigger.getOpCodeSteps(OpCode.EXT_FUN_DAT_2, MD5_INTO_B, null));
			assertEquals(RAISED_COST, atTrigger.getOpCodeSteps(OpCode.EXT_FUN_RET_DAT_2, CHECK_HASH160_WITH_B, null));

			// Non-hashing functions are untouched at/after the trigger.
			assertEquals(FLAT_COST, atTrigger.getOpCodeSteps(OpCode.EXT_FUN_RET_DAT, NON_HASHING_FUNCTION, null));
		}
	}

	/**
	 * Source-of-height proof: the gate must follow the repository-derived height, NOT the {@code blockHeight}
	 * passed into the API (the peer-supplied {@code BlockData.height} analogue). Here the true position is AT
	 * the trigger (tip 7 -> block 8) but the API is handed a pre-trigger claimed height; the gate must still
	 * raise the hashing cost.
	 */
	@Test
	public void testGateFollowsTrueHeightAtTriggerDespiteLowPassedBlockHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI atTrigger = apiAtRepoHeight(repository, AT_TRIGGER_TIP, /* claimed */ 1);

			assertEquals("gate must follow the true chain position (8), not the claimed blockHeight (1)",
					RAISED_COST, atTrigger.getOpCodeSteps(OpCode.EXT_FUN_DAT_2, SHA256_INTO_B, null));
		}
	}

	/**
	 * Source-of-height proof, other side: the true position is BELOW the trigger (tip 6 -> block 7) but the
	 * API is handed the trigger height; the gate must follow the true position and keep the flat cost.
	 */
	@Test
	public void testGateFollowsTrueHeightBelowTriggerDespiteHighPassedBlockHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI belowTrigger = apiAtRepoHeight(repository, BELOW_TRIGGER_TIP, /* claimed */ TRIGGER_HEIGHT);

			assertEquals("gate must follow the true chain position (7), not the claimed blockHeight (8)",
					FLAT_COST, belowTrigger.getOpCodeSteps(OpCode.EXT_FUN_DAT_2, SHA256_INTO_B, null));
		}
	}

	// Support

	private static String atAddress() {
		return Crypto.toATAddress(new byte[32]);
	}

	/** API whose locally-derived execution height is {@code repoTipHeight + 1}, with an honest passed blockHeight. */
	private static ChainATAPI apiAtRepoHeight(Repository repository, int repoTipHeight) throws DataException {
		return apiAtRepoHeight(repository, repoTipHeight, repoTipHeight + 1);
	}

	/**
	 * API executing on top of a repository minted to {@code repoTipHeight}, with an arbitrary (possibly
	 * mismatched) {@code passedBlockHeight}. The hashing-step-cost gate must ignore {@code passedBlockHeight}
	 * and follow {@code repoTipHeight + 1}.
	 */
	private static ChainATAPI apiAtRepoHeight(Repository repository, int repoTipHeight, int passedBlockHeight)
			throws DataException {
		TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
		repository.saveChanges();

		int filler = repoTipHeight - repository.getBlockRepository().getBlockchainHeight();
		if (filler > 0)
			BlockUtils.mintBlocks(repository, filler);
		assertEquals(repoTipHeight, repository.getBlockRepository().getBlockchainHeight());

		ATData atData = new ATData(atAddress(), new byte[32], 0L, Asset.NATIVE);
		return new ChainATAPI(repository, atData, passedBlockHeight, 0L, null);
	}
}
