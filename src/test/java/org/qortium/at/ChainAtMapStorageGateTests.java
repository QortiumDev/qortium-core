package org.qortium.at;

import org.ciyam.at.IllegalFunctionCodeException;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Source-of-height coverage for the {@code atMapStorageHeight} activation gate
 * ({@link ChainATAPI#isMapStorageActive()}, exercised here through
 * {@link ChainATAPI#platformSpecificPreExecuteCheck}, which rejects a map function code before the
 * feature is active). The gate is keyed on the <em>locally-derived</em> execution height — the
 * repository's blockchain height + 1 ({@code getCurrentBlockHeight() + 1}, the block the AT runs in) —
 * exactly like the shipped {@code isPayoutSolvencyEnforced} gate, NEVER the {@code blockHeight} threaded
 * in from the block being validated (which, for a network block, is the peer-supplied, non-canonical
 * {@code BlockData.height}).
 *
 * <p>The map-storage test chain sets the trigger at height 8, so a repository tip of 6 (this-block
 * height 7) is pre-activation and one at tip 7 (this-block height 8) is activation — the Previewnet
 * 69,999 -> 70,000 analogue. For an honest block the passed height equals the true height, so honest
 * behaviour (covered by ATMapActivationTests / ATMapStorageTests) is byte-identical.
 */
public class ChainAtMapStorageGateTests extends Common {

	private static final int TRIGGER_HEIGHT = 8;
	private static final short GET_MAP_VALUE_KEYS_IN_A = (short) 0x0600; // paramCount 0, returns value

	// Repository tip heights whose "next block" straddles the trigger.
	private static final int BELOW_TRIGGER_TIP = TRIGGER_HEIGHT - 2; // this-block height 7
	private static final int AT_TRIGGER_TIP = TRIGGER_HEIGHT - 1;     // this-block height 8

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings("test-settings-v2-at-map-storage.json");
		// useSettings (unlike useDefaultSettings) does not prime NTP; minting needs a non-null clock.
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
	}

	@Test
	public void testMapStorageInactiveBelowTriggerAndActiveAtOrAfter() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			assertMapStorageInactive(apiAtRepoHeight(repository, BELOW_TRIGGER_TIP));
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			assertMapStorageActive(apiAtRepoHeight(repository, AT_TRIGGER_TIP));
		}
	}

	/**
	 * Source-of-height proof: the true position is AT the trigger (tip 7 -> block 8) but the API is handed a
	 * pre-trigger claimed height; the gate must still treat map storage as active.
	 */
	@Test
	public void testGateFollowsTrueHeightAtTriggerDespiteLowPassedBlockHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			assertMapStorageActive(apiAtRepoHeight(repository, AT_TRIGGER_TIP, /* claimed */ 1));
		}
	}

	/**
	 * Source-of-height proof, other side: the true position is BELOW the trigger (tip 6 -> block 7) but the
	 * API is handed the trigger height; the gate must still treat map storage as inactive.
	 */
	@Test
	public void testGateFollowsTrueHeightBelowTriggerDespiteHighPassedBlockHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			assertMapStorageInactive(apiAtRepoHeight(repository, BELOW_TRIGGER_TIP, /* claimed */ TRIGGER_HEIGHT));
		}
	}

	// Support

	/** Passing a correctly-signed map function code must NOT throw the "not active" gate rejection. */
	private static void assertMapStorageActive(ChainATAPI api) {
		try {
			api.platformSpecificPreExecuteCheck(0, true, null, GET_MAP_VALUE_KEYS_IN_A);
		} catch (IllegalFunctionCodeException e) {
			fail("map storage must be active at/after the trigger, but the gate rejected the map function: "
					+ e.getMessage());
		}
	}

	/** Below the trigger the same correctly-signed map function code must be rejected as not-yet-active. */
	private static void assertMapStorageInactive(ChainATAPI api) {
		try {
			api.platformSpecificPreExecuteCheck(0, true, null, GET_MAP_VALUE_KEYS_IN_A);
			fail("map storage must be inactive below the trigger, but the map function was accepted");
		} catch (IllegalFunctionCodeException e) {
			assertTrue("rejection must be the map-storage activation gate, not a signature error: " + e.getMessage(),
					e.getMessage().contains("not active"));
		}
	}

	private static String atAddress() {
		return Crypto.toATAddress(new byte[32]);
	}

	/** API whose locally-derived execution height is {@code repoTipHeight + 1}, with an honest passed blockHeight. */
	private static ChainATAPI apiAtRepoHeight(Repository repository, int repoTipHeight) throws DataException {
		return apiAtRepoHeight(repository, repoTipHeight, repoTipHeight + 1);
	}

	/**
	 * API executing on top of a repository minted to {@code repoTipHeight}, with an arbitrary (possibly
	 * mismatched) {@code passedBlockHeight} and a live map overlay. The map-storage activation gate must
	 * ignore {@code passedBlockHeight} and follow {@code repoTipHeight + 1}.
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
		// A non-null map overlay is required for isMapStorageActive to be reachable; the height gate is the
		// behaviour under test.
		return new ChainATAPI(repository, atData, passedBlockHeight, 0L, new ATMapExecutionContext(repository));
	}
}
