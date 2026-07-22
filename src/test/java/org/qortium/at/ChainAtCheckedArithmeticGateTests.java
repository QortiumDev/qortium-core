package org.qortium.at;

import org.ciyam.at.ExecutionException;
import org.junit.Before;
import org.junit.Test;
import org.qortium.asset.Asset;
import org.qortium.crypto.Crypto;
import org.qortium.data.PaymentData;
import org.qortium.data.at.ATData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.MultiPaymentTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.settings.Settings;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.utils.NTP;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Boundary coverage for {@code atCheckedArithmeticHeight} on every AT-side money-aggregation site:
 * below the trigger each site must be byte-for-byte the historic silently-wrapping arithmetic
 * (wrapped states are reachable on today's chain via the pre-existing unchecked multi-payment
 * validation, so all nodes must keep agreeing on them), and at/after the trigger each site must
 * fail deterministically instead of wrapping.
 *
 * <p>The gate is keyed on the <em>locally-derived</em> execution height — the repository's blockchain
 * height + 1 ({@code getCurrentBlockHeight() + 1}, the block the AT runs in), exactly like the shipped
 * {@code isPayoutSolvencyEnforced} gate — NEVER the {@code blockHeight} threaded in from the block being
 * validated (which, for a network block, is the peer-supplied, non-canonical {@code BlockData.height}).
 * Each test therefore mints the repository to a boundary height; the trigger is at height 8, so a
 * repository at height 6 (this-block height 7) exercises the legacy behaviour and one at height 7
 * (this-block height 8) the checked behaviour — the Previewnet 69,999 -> 70,000 analogue.
 */
public class ChainAtCheckedArithmeticGateTests extends Common {

	private static final int TRIGGER_HEIGHT = 8;
	private static final long FEE_PER_STEP = 10_000L; // "0.0001" in the test chain config, 1e8-scaled

	// Repository tip heights whose "next block" straddles the trigger.
	private static final int BELOW_TRIGGER_TIP = TRIGGER_HEIGHT - 2; // this-block height 7
	private static final int AT_TRIGGER_TIP = TRIGGER_HEIGHT - 1;     // this-block height 8

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings("test-settings-v2-at-checked-arithmetic.json");
		// useSettings (unlike useDefaultSettings) does not prime NTP; minting needs a non-null clock.
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
	}

	// addAtAmount — shared by per-asset multi-payment reads and the multi-payment summary

	@Test
	public void testAddAtAmountWrapsExactlyLikePlainAdditionBelowTrigger() throws DataException, ExecutionException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI belowTrigger = apiAtRepoHeight(repository, BELOW_TRIGGER_TIP);

			// Non-overflowing sums are plain sums.
			assertEquals(300L, belowTrigger.addAtAmount(100L, 200L));

			// Overflowing sums must WRAP byte-for-byte as on the base branch, never throw.
			assertEquals(Long.MAX_VALUE + 100L, belowTrigger.addAtAmount(Long.MAX_VALUE, 100L));
			assertEquals(Long.MIN_VALUE, belowTrigger.addAtAmount(Long.MAX_VALUE, 1L));
		}
	}

	@Test
	public void testAddAtAmountThrowsDeterministicallyAtOrAfterTrigger() throws DataException, ExecutionException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI atTrigger = apiAtRepoHeight(repository, AT_TRIGGER_TIP);

			// Non-overflowing sums are unchanged.
			assertEquals(300L, atTrigger.addAtAmount(100L, 200L));

			try {
				atTrigger.addAtAmount(Long.MAX_VALUE, 100L);
				fail("post-trigger overflowing AT aggregation must throw, not wrap");
			} catch (ExecutionException e) {
				// expected: deterministic AT fatal-error path
			}
		}
	}

	/**
	 * Source-of-height proof: the gate must follow the repository-derived height, NOT the {@code blockHeight}
	 * passed into the API (the peer-supplied {@code BlockData.height} analogue). Here the true position is AT
	 * the trigger (tip 7 -> block 8) but the API is handed a pre-trigger claimed height; the gate must reject.
	 */
	@Test
	public void testGateFollowsTrueHeightAtTriggerDespiteLowPassedBlockHeight()
			throws DataException, ExecutionException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI atTrigger = apiAtRepoHeight(repository, AT_TRIGGER_TIP, /* claimed */ 1);
			try {
				atTrigger.addAtAmount(Long.MAX_VALUE, 100L);
				fail("gate must follow the true chain position (8), not the claimed blockHeight (1)");
			} catch (ExecutionException e) {
				// expected
			}
		}
	}

	/**
	 * Source-of-height proof, other side: the true position is BELOW the trigger (tip 6 -> block 7) but the
	 * API is handed the trigger height; the gate must follow the true position and wrap, not throw.
	 */
	@Test
	public void testGateFollowsTrueHeightBelowTriggerDespiteHighPassedBlockHeight()
			throws DataException, ExecutionException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI belowTrigger = apiAtRepoHeight(repository, BELOW_TRIGGER_TIP, /* claimed */ TRIGGER_HEIGHT);
			assertEquals("gate must follow the true chain position (7), not the claimed blockHeight (8)",
					Long.MIN_VALUE, belowTrigger.addAtAmount(Long.MAX_VALUE, 1L));
		}
	}

	// summarizeMultiPaymentToAt — single-asset multi-payment summary used by amount/assetId reads

	@Test
	public void testMultiPaymentSummaryWrapsExactlyBelowTrigger() throws DataException, ExecutionException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI belowTrigger = apiAtRepoHeight(repository, BELOW_TRIGGER_TIP);

			ChainATAPI.MultiPaymentSummary summary =
					belowTrigger.summarizeMultiPaymentToAt(overflowingMultiPaymentTo(atAddress()));

			// Byte-for-byte the historic wrapped total.
			assertEquals(Long.MAX_VALUE + 100L, summary.amount);
			assertEquals(Asset.NATIVE, summary.assetId);
		}
	}

	@Test
	public void testMultiPaymentSummaryThrowsDeterministicallyAtOrAfterTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI atTrigger = apiAtRepoHeight(repository, AT_TRIGGER_TIP);

			try {
				atTrigger.summarizeMultiPaymentToAt(overflowingMultiPaymentTo(atAddress()));
				fail("post-trigger overflowing multi-payment summary must throw, not wrap");
			} catch (ExecutionException e) {
				// expected: deterministic AT fatal-error path
			}
		}
	}

	// addPendingPayout — platform-payout accumulation per asset

	@Test
	public void testPendingPayoutWrapsExactlyBelowTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI belowTrigger = apiAtRepoHeight(repository, BELOW_TRIGGER_TIP);

			belowTrigger.addPendingPayout(Asset.NATIVE, Long.MAX_VALUE);
			belowTrigger.addPendingPayout(Asset.NATIVE, 100L); // must NOT throw below the trigger

			// Byte-for-byte the historic wrapped total.
			assertEquals(Long.MAX_VALUE + 100L, belowTrigger.getPendingPayout(Asset.NATIVE));
		}
	}

	@Test
	public void testPendingPayoutThrowsDeterministicallyAtOrAfterTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI atTrigger = apiAtRepoHeight(repository, AT_TRIGGER_TIP);

			atTrigger.addPendingPayout(Asset.NATIVE, Long.MAX_VALUE);

			try {
				atTrigger.addPendingPayout(Asset.NATIVE, 100L);
				fail("post-trigger overflowing pending payout must throw, not wrap");
			} catch (ArithmeticException e) {
				// expected: payAssetAmountToB converts this into a deterministic AT fatal error
			}
		}
	}

	// calcStepFees — final step-fee multiplication

	@Test
	public void testStepFeesWrapExactlyBelowTriggerAndMatchOnBothSidesForRealisticSteps() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI belowTrigger = apiAtRepoHeight(repository, BELOW_TRIGGER_TIP);

			// Realistic step counts are identical on both sides of the trigger.
			assertEquals(458L * FEE_PER_STEP, belowTrigger.calcStepFees(458L));

			// An overflowing product must WRAP byte-for-byte below the trigger, never throw.
			long hugeSteps = Long.MAX_VALUE / 2L;
			assertEquals(hugeSteps * FEE_PER_STEP, belowTrigger.calcStepFees(hugeSteps));
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI atTrigger = apiAtRepoHeight(repository, AT_TRIGGER_TIP);
			assertEquals(458L * FEE_PER_STEP, atTrigger.calcStepFees(458L));
		}
	}

	@Test
	public void testStepFeesThrowDeterministicallyAtOrAfterTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI atTrigger = apiAtRepoHeight(repository, AT_TRIGGER_TIP);

			try {
				atTrigger.calcStepFees(Long.MAX_VALUE / 2L);
				fail("post-trigger overflowing step-fee product must throw, not wrap");
			} catch (ArithmeticException e) {
				// expected: deterministic block-invalid path
			}
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
	 * mismatched) {@code passedBlockHeight}. The checked-arithmetic gate must ignore {@code passedBlockHeight}
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

	/** Two same-asset payments to the AT whose amounts sum past {@code Long.MAX_VALUE}. */
	private static MultiPaymentTransactionData overflowingMultiPaymentTo(String atAddress) {
		BaseTransactionData baseTransactionData =
				new BaseTransactionData(0L, Group.NO_GROUP, new byte[32], 0L, null);

		return new MultiPaymentTransactionData(baseTransactionData, Arrays.asList(
				new PaymentData(atAddress, Asset.NATIVE, Long.MAX_VALUE),
				new PaymentData(atAddress, Asset.NATIVE, 100L)));
	}
}
