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
 * Source-of-height coverage for the three chain-query opcode activation gates.
 *
 * <p>The block height carried in a network block is not part of the signed block bytes. Activation must
 * therefore follow the locally-derived execution height (repository parent height plus one), never the
 * height passed into {@link ChainATAPI}'s constructor. The test chain activates all three queries at
 * height 8, allowing both hostile mismatch directions to be exercised at the boundary.</p>
 */
public class ChainAtQueryGateHeightTests extends Common {

	private static final int TRIGGER_HEIGHT = 8;
	private static final int BELOW_TRIGGER_TIP = TRIGGER_HEIGHT - 2;
	private static final int AT_TRIGGER_TIP = TRIGGER_HEIGHT - 1;

	private static final ChainFunctionCode[] QUERY_FUNCTIONS = {
			ChainFunctionCode.GET_TRUST_STATUS_FROM_ACCOUNT_IN_B,
			ChainFunctionCode.GET_BALANCE_FROM_ACCOUNT_IN_B,
			ChainFunctionCode.CHECK_CODE_HASH_OF_AT_IN_B
	};

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings("test-settings-v2-at-map-storage.json");
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
	}

	/** True height is active, so a maliciously low claimed height must not disable any query. */
	@Test
	public void testQueriesFollowTrueHeightAtTriggerDespiteLowClaimedHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI api = apiAtRepoHeight(repository, AT_TRIGGER_TIP, TRIGGER_HEIGHT - 1);
			for (ChainFunctionCode functionCode : QUERY_FUNCTIONS)
				assertQueryActive(api, functionCode);
		}
	}

	/** True height is inactive, so a maliciously high claimed height must not enable any query. */
	@Test
	public void testQueriesFollowTrueHeightBelowTriggerDespiteHighClaimedHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI api = apiAtRepoHeight(repository, BELOW_TRIGGER_TIP, TRIGGER_HEIGHT);
			for (ChainFunctionCode functionCode : QUERY_FUNCTIONS)
				assertQueryInactive(api, functionCode);
		}
	}

	private static void assertQueryActive(ChainATAPI api, ChainFunctionCode functionCode) {
		try {
			api.platformSpecificPreExecuteCheck(functionCode.paramCount, functionCode.returnsValue, null,
					functionCode.value);
		} catch (IllegalFunctionCodeException e) {
			fail(functionCode.name() + " must follow the active local height: " + e.getMessage());
		}
	}

	private static void assertQueryInactive(ChainATAPI api, ChainFunctionCode functionCode) {
		try {
			api.platformSpecificPreExecuteCheck(functionCode.paramCount, functionCode.returnsValue, null,
					functionCode.value);
			fail(functionCode.name() + " must remain inactive at the local pre-trigger height");
		} catch (IllegalFunctionCodeException e) {
			assertTrue("rejection must come from the activation gate: " + e.getMessage(),
					e.getMessage().contains(functionCode.name()) && e.getMessage().contains("not active"));
		}
	}

	private static ChainATAPI apiAtRepoHeight(Repository repository, int repoTipHeight, int claimedBlockHeight)
			throws DataException {
		TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
		repository.saveChanges();

		int filler = repoTipHeight - repository.getBlockRepository().getBlockchainHeight();
		if (filler > 0)
			BlockUtils.mintBlocks(repository, filler);
		assertEquals(repoTipHeight, repository.getBlockRepository().getBlockchainHeight());

		ATData atData = new ATData(Crypto.toATAddress(new byte[32]), new byte[32], 0L, Asset.NATIVE);
		return new ChainATAPI(repository, atData, claimedBlockHeight, 0L, null);
	}

}
