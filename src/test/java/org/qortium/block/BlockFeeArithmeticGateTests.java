package org.qortium.block;

import org.junit.Before;
import org.junit.Test;
import org.qortium.block.Block.ValidationResult;
import org.qortium.data.block.BlockData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.PaymentTransactionData;
import org.qortium.data.transaction.TransactionData;
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
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Boundary coverage for {@code atCheckedArithmeticHeight} on the block total-fee reconstruction.
 *
 * <p>The reconstructed total fees are validated in {@link Block#isValid()}, gated on the block's
 * <em>locally-derived</em> height — its parent's stored height + 1 — never the peer-supplied,
 * non-canonical {@link BlockData#getHeight()} field (which is absent from the signed block and filled
 * separately from the network message, so it is attacker-influenced). Below the trigger an overflowing
 * reconstruction wraps byte-for-byte as on the base branch; at/after the trigger it is rejected with
 * {@link ValidationResult#BLOCK_FEE_OVERFLOW} on every node.
 *
 * <p>The test chain sets the trigger at height 8. Each block below is built to reference the real chain
 * tip (so its true position is fixed by the repository) while its {@code BlockData.height} field is set
 * to a <em>disagreeing</em> value; the gate must follow the true position, not the claimed field — the
 * Previewnet 69,999 -> 70,000 forge analogue.
 */
public class BlockFeeArithmeticGateTests extends Common {

	private static final int TRIGGER_HEIGHT = 8;

	@Before
	public void beforeTest() throws DataException {
		Common.useSettings("test-settings-v2-at-checked-arithmetic.json");
		// useSettings (unlike useDefaultSettings) does not prime NTP; minting needs a non-null clock.
		NTP.setFixedOffset(Settings.getInstance().getTestNtpOffset());
	}

	/**
	 * True position AT the trigger (parent at height 7 -> this block is height 8), but the block claims a
	 * pre-trigger height in its BlockData.height field. The gate must follow the true position and reject.
	 */
	@Test
	public void testGateFollowsTrueHeightAtTriggerDespiteLowClaimedHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] tipSignature = mintTo(repository, TRIGGER_HEIGHT - 1);

			// Overflowing fee reconstruction; claimed height 1 would be "pre-trigger" under a naive gate.
			Block block = overflowingBlock(repository, tipSignature, 1);

			assertEquals("gate must follow the true chain position (8), not the claimed height (1)",
					ValidationResult.BLOCK_FEE_OVERFLOW, block.isValid());
		}
	}

	/**
	 * True position BELOW the trigger (parent at height 6 -> this block is height 7), but the block claims
	 * the trigger height. The gate must follow the true position and NOT trip the fee-overflow rule.
	 */
	@Test
	public void testGateFollowsTrueHeightBelowTriggerDespiteHighClaimedHeight() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] tipSignature = mintTo(repository, TRIGGER_HEIGHT - 2);

			// Overflowing fee reconstruction; claimed height 8 would be "at-trigger" under a naive gate.
			Block block = overflowingBlock(repository, tipSignature, TRIGGER_HEIGHT);

			// Below the trigger the wrap is tolerated: the block fails validation for an unrelated reason
			// (its dummy timestamp), but MUST NOT be rejected as a fee overflow.
			assertNotEquals("gate must follow the true chain position (7), not the claimed height (8)",
					ValidationResult.BLOCK_FEE_OVERFLOW, block.isValid());
		}
	}

	/** Non-overflowing fees are accepted by the fee gate at/after the trigger (no false positive). */
	@Test
	public void testNonOverflowingFeesPassGateAtTrigger() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			byte[] tipSignature = mintTo(repository, TRIGGER_HEIGHT - 1);

			List<TransactionData> transactions = Collections.singletonList(paymentWithFee(1_000_000L));
			Block block = blockReferencing(repository, tipSignature, TRIGGER_HEIGHT, transactions);

			assertNotEquals(ValidationResult.BLOCK_FEE_OVERFLOW, block.isValid());
		}
	}

	// Support

	/** Bootstraps the test chain and mints up to {@code targetHeight}, returning the tip's signature. */
	private static byte[] mintTo(Repository repository, int targetHeight) throws DataException {
		TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
		repository.saveChanges();

		int filler = targetHeight - repository.getBlockRepository().getBlockchainHeight();
		if (filler > 0)
			BlockUtils.mintBlocks(repository, filler);

		assertEquals(targetHeight, repository.getBlockRepository().getBlockchainHeight());
		return repository.getBlockRepository().getLastBlock().getSignature();
	}

	/** A block referencing {@code tipSignature} whose two transaction fees sum past {@code Long.MAX_VALUE}. */
	private static Block overflowingBlock(Repository repository, byte[] tipSignature, int claimedHeight) {
		List<TransactionData> transactions = Arrays.asList(
				paymentWithFee(Long.MAX_VALUE), paymentWithFee(1_000_000_000_000L));
		return blockReferencing(repository, tipSignature, claimedHeight, transactions);
	}

	private static Block blockReferencing(Repository repository, byte[] tipSignature, int claimedHeight,
			List<TransactionData> transactions) {
		// timestamp 0 keeps the block from ever passing full validation for an unrelated reason, isolating
		// the fee-arithmetic gate (which runs before the timestamp check).
		BlockData blockData = new BlockData(2, tipSignature, transactions.size(), 0L, new byte[64],
				claimedHeight, 0L, new byte[32], new byte[64], 0, 0L);
		return new Block(repository, blockData, transactions, new byte[32]);
	}

	private static TransactionData paymentWithFee(long fee) {
		BaseTransactionData baseTransactionData =
				new BaseTransactionData(0L, Group.NO_GROUP, new byte[32], fee, null);
		return new PaymentTransactionData(baseTransactionData, "recipient", 1L);
	}
}
