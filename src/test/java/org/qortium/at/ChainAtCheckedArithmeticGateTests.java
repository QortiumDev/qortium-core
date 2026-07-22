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
import org.qortium.test.common.Common;

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
 * <p>The test chain sets the trigger at height 8, so an API instance at height 7 exercises the
 * legacy behaviour and one at height 8 the checked behaviour — the Previewnet 69,999 -> 70,000
 * analogue.
 */
public class ChainAtCheckedArithmeticGateTests extends Common {

	private static final int TRIGGER_HEIGHT = 8;
	private static final long FEE_PER_STEP = 10_000L; // "0.0001" in the test chain config, 1e8-scaled

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings("test-settings-v2-at-checked-arithmetic.json");
	}

	// addAtAmount — shared by per-asset multi-payment reads and the multi-payment summary

	@Test
	public void testAddAtAmountWrapsExactlyLikePlainAdditionBelowTrigger() throws DataException, ExecutionException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI belowTrigger = apiAtHeight(repository, TRIGGER_HEIGHT - 1);

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
			ChainATAPI atTrigger = apiAtHeight(repository, TRIGGER_HEIGHT);

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

	// summarizeMultiPaymentToAt — single-asset multi-payment summary used by amount/assetId reads

	@Test
	public void testMultiPaymentSummaryWrapsExactlyBelowTrigger() throws DataException, ExecutionException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI belowTrigger = apiAtHeight(repository, TRIGGER_HEIGHT - 1);

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
			ChainATAPI atTrigger = apiAtHeight(repository, TRIGGER_HEIGHT);

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
			ChainATAPI belowTrigger = apiAtHeight(repository, TRIGGER_HEIGHT - 1);

			belowTrigger.addPendingPayout(Asset.NATIVE, Long.MAX_VALUE);
			belowTrigger.addPendingPayout(Asset.NATIVE, 100L); // must NOT throw below the trigger

			// Byte-for-byte the historic wrapped total.
			assertEquals(Long.MAX_VALUE + 100L, belowTrigger.getPendingPayout(Asset.NATIVE));
		}
	}

	@Test
	public void testPendingPayoutThrowsDeterministicallyAtOrAfterTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI atTrigger = apiAtHeight(repository, TRIGGER_HEIGHT);

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
			ChainATAPI belowTrigger = apiAtHeight(repository, TRIGGER_HEIGHT - 1);
			ChainATAPI atTrigger = apiAtHeight(repository, TRIGGER_HEIGHT);

			// Realistic step counts are identical on both sides of the trigger.
			assertEquals(458L * FEE_PER_STEP, belowTrigger.calcStepFees(458L));
			assertEquals(458L * FEE_PER_STEP, atTrigger.calcStepFees(458L));

			// An overflowing product must WRAP byte-for-byte below the trigger, never throw.
			long hugeSteps = Long.MAX_VALUE / 2L;
			assertEquals(hugeSteps * FEE_PER_STEP, belowTrigger.calcStepFees(hugeSteps));
		}
	}

	@Test
	public void testStepFeesThrowDeterministicallyAtOrAfterTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ChainATAPI atTrigger = apiAtHeight(repository, TRIGGER_HEIGHT);

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

	private static ChainATAPI apiAtHeight(Repository repository, int blockHeight) {
		byte[] creatorPublicKey = new byte[32];
		ATData atData = new ATData(atAddress(), creatorPublicKey, 0L, Asset.NATIVE);

		return new ChainATAPI(repository, atData, blockHeight, 0L, null);
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
