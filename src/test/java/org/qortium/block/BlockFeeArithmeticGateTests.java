package org.qortium.block;

import org.junit.Before;
import org.junit.Test;
import org.qortium.data.at.ATStateData;
import org.qortium.data.block.BlockData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.PaymentTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Boundary coverage for {@code atCheckedArithmeticHeight} on the block fee-reconstruction sites:
 * both network-block constructors and the shared {@link Block#addBlockFees} helper (also used by
 * {@code executeATs()} for per-block AT fees). Below the trigger the totals must WRAP byte-for-byte
 * as on the base branch — wrapped balances/fees are reachable on today's chain via the pre-existing
 * unchecked multi-payment validation, so all nodes must keep computing identical wrapped totals.
 * At/after the trigger an overflow must fail deterministically ({@link ArithmeticException}, block
 * invalid on every node) instead of wrapping.
 *
 * <p>The test chain sets the trigger at height 8, so a block claiming height 7 exercises the legacy
 * behaviour and one at height 8 the checked behaviour — the Previewnet 69,999 -> 70,000 analogue.
 */
public class BlockFeeArithmeticGateTests extends Common {

	private static final int TRIGGER_HEIGHT = 8;

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings("test-settings-v2-at-checked-arithmetic.json");
	}

	// Shared helper — covers all three call sites, including executeATs' per-block AT-fee total

	@Test
	public void testAddBlockFeesWrapsExactlyLikePlainAdditionBelowTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Block belowTrigger = new Block(repository, blockDataAtHeight(TRIGGER_HEIGHT - 1, 0L));

			// Non-overflowing sums are plain sums.
			assertEquals(1_500L, belowTrigger.addBlockFees(1_000L, 500L));

			// Overflowing sums must WRAP byte-for-byte as on the base branch, never throw.
			assertEquals(Long.MAX_VALUE + 100L, belowTrigger.addBlockFees(Long.MAX_VALUE, 100L));
			assertEquals(Long.MIN_VALUE, belowTrigger.addBlockFees(Long.MAX_VALUE, 1L));
		}
	}

	@Test
	public void testAddBlockFeesThrowsDeterministicallyAtOrAfterTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Block atTrigger = new Block(repository, blockDataAtHeight(TRIGGER_HEIGHT, 0L));

			// Non-overflowing sums are unchanged.
			assertEquals(1_500L, atTrigger.addBlockFees(1_000L, 500L));

			try {
				atTrigger.addBlockFees(Long.MAX_VALUE, 100L);
				fail("post-trigger overflowing block-fee total must throw, not wrap");
			} catch (ArithmeticException e) {
				// expected: deterministic block-invalid path
			}
		}
	}

	// Network-block constructor taking full AT states

	@Test
	public void testAtStatesConstructorWrapsTotalFeesExactlyBelowTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<TransactionData> transactions = Arrays.asList(
					paymentWithFee(Long.MAX_VALUE), paymentWithFee(1_000_000_000_000L));
			List<ATStateData> atStates = Collections.singletonList(atStateWithFees(500_000_000_000L));

			Block block = new Block(repository, blockDataAtHeight(TRIGGER_HEIGHT - 1, 0L), transactions, atStates);

			// Byte-for-byte the historic wrapped total.
			assertEquals(Long.MAX_VALUE + 1_000_000_000_000L + 500_000_000_000L,
					block.getBlockData().getTotalFees());
		}
	}

	@Test
	public void testAtStatesConstructorThrowsDeterministicallyAtOrAfterTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<TransactionData> transactions = Arrays.asList(
					paymentWithFee(Long.MAX_VALUE), paymentWithFee(1_000_000_000_000L));
			List<ATStateData> atStates = Collections.singletonList(atStateWithFees(500_000_000_000L));

			try {
				new Block(repository, blockDataAtHeight(TRIGGER_HEIGHT, 0L), transactions, atStates);
				fail("post-trigger overflowing block-fee reconstruction must throw, not wrap");
			} catch (ArithmeticException e) {
				// expected: deterministic block-invalid path
			}
		}
	}

	// Network-block constructor taking an AT-states hash plus the block's claimed AT fees

	@Test
	public void testAtStatesHashConstructorWrapsTotalFeesExactlyBelowTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<TransactionData> transactions = Collections.singletonList(paymentWithFee(Long.MAX_VALUE));

			Block block = new Block(repository, blockDataAtHeight(TRIGGER_HEIGHT - 1, 500_000_000_000L),
					transactions, new byte[32]);

			// Byte-for-byte the historic wrapped total (transaction fee + claimed AT fees).
			assertEquals(Long.MAX_VALUE + 500_000_000_000L, block.getBlockData().getTotalFees());
		}
	}

	@Test
	public void testAtStatesHashConstructorThrowsDeterministicallyAtOrAfterTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			List<TransactionData> transactions = Collections.singletonList(paymentWithFee(Long.MAX_VALUE));

			try {
				new Block(repository, blockDataAtHeight(TRIGGER_HEIGHT, 500_000_000_000L), transactions, new byte[32]);
				fail("post-trigger overflowing block-fee reconstruction must throw, not wrap");
			} catch (ArithmeticException e) {
				// expected: deterministic block-invalid path
			}
		}
	}

	// Support

	/** Minimal block data claiming the given height, as a deserialized network block would. */
	private static BlockData blockDataAtHeight(int height, long atFees) {
		return new BlockData(2, new byte[64], 0, 0L, null, height, 0L,
				new byte[32], new byte[64], 0, atFees);
	}

	private static TransactionData paymentWithFee(long fee) {
		BaseTransactionData baseTransactionData =
				new BaseTransactionData(0L, Group.NO_GROUP, new byte[32], fee, null);
		return new PaymentTransactionData(baseTransactionData, "recipient", 1L);
	}

	private static ATStateData atStateWithFees(long fees) {
		return new ATStateData("AT-address", new byte[32], fees);
	}
}
