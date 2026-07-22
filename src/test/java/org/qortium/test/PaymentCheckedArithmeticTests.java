package org.qortium.test;

import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.crypto.Crypto;
import org.qortium.data.PaymentData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.MultiPaymentTransactionData;
import org.qortium.group.Group;
import org.qortium.payment.Payment;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.transaction.MultiPaymentTransaction;
import org.qortium.transaction.Transaction.ValidationResult;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Boundary coverage for the {@code atCheckedArithmeticHeight} root fix in {@link Payment} validation.
 *
 * <p>The base chain has a pre-existing overflow: multi-payment validation accumulates per-asset totals
 * with plain (wrapping) {@code long} addition, so a crafted MULTI_PAYMENT whose amounts sum past
 * {@code Long.MAX_VALUE} wraps the required total negative, slips past the balance check, and confirms —
 * pushing the sender's balance far below zero. Because such states are reachable on today's chain, the
 * wrapping behaviour must be preserved byte-for-byte BELOW the trigger (this is exactly what an
 * un-upgraded node computes), while AT/AFTER the trigger the same transaction must be rejected as
 * invalid within the normal validation-result contract.
 *
 * <p>The scenario is the consensus review's: a sender holding {@code 1e12} raw native units and three
 * payments summing to {@code Long.MAX_VALUE + 5e11}. The test chain sets the trigger at height 8.
 */
public class PaymentCheckedArithmeticTests extends Common {

	private static final int TRIGGER_HEIGHT = 8;

	private static final long SENDER_BALANCE = 1_000_000_000_000L; // 1e12 raw
	private static final long PAYMENT_1 = Long.MAX_VALUE - 1_000_000_000_000L;
	private static final long PAYMENT_2 = 1_000_000_000_000L;
	private static final long PAYMENT_3 = 500_000_000_000L; // total: Long.MAX_VALUE + 5e11

	@Before
	public void beforeTest() throws DataException {
		// useDefaultSettings first so the fixed NTP offset is installed; the per-test useSettings switch
		// to the checked-arithmetic boundary chain keeps that synced clock.
		Common.useDefaultSettings();
	}

	/**
	 * Below the trigger, the overflowing MULTI_PAYMENT must behave exactly as on base main ba1346f8a:
	 * the wrapped (negative) required total slips past the balance check so validation ACCEPTS the
	 * transaction, and processing then fails on the repository's {@code CHECKBALANCENOTNEGATIVE}
	 * constraint (the first payment alone empties the sender past zero) — identically on every node,
	 * upgraded or not. This byte-identity is what keeps nodes agreeing before the flag day.
	 */
	@Test
	public void testOverflowingMultiPaymentStillWrapsAndValidatesBelowTrigger() throws DataException {
		Common.useSettings("test-settings-v2-at-checked-arithmetic.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
			repository.saveChanges();

			assertEquals(TRIGGER_HEIGHT, (int) BlockChain.getInstance().getAtCheckedArithmeticHeight());
			assertTrue("test must run below the trigger height",
					repository.getBlockRepository().getBlockchainHeight() + 1 < TRIGGER_HEIGHT);

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			// Fresh, zero-balance recipient so its post-payment balance stays within range.
			String freshRecipient = Crypto.toAddress(new byte[32]);

			alice.setConfirmedBalance(Asset.NATIVE, SENDER_BALANCE);
			repository.saveChanges();

			List<PaymentData> payments = overflowingPayments(freshRecipient, bob.getAddress());

			// The wrapped accumulator really does go negative — that is what defeats the balance check.
			long wrappedTotal = PAYMENT_1 + PAYMENT_2 + PAYMENT_3; // wraps, on purpose
			assertTrue("scenario must actually wrap the required total negative", wrappedTotal < 0);

			// Validation: the wrapped total passes the balance check — exactly as on base main
			// ba1346f8a, where this accumulation is a plain wrapping +.
			assertEquals(ValidationResult.OK, buildMultiPayment(repository, alice, payments).isValid());
			assertEquals(ValidationResult.OK,
					new Payment(repository).isValid(alice.getPublicKey(), payments, 0L));

			// Processing: the schema-level non-negative balance constraint rejects the first payment
			// (this processing path is untouched by the branch, so this too is identical to base main).
			try {
				new Payment(repository).process(alice.getPublicKey(), payments);
				fail("processing must trip the repository's non-negative balance constraint");
			} catch (DataException e) {
				repository.discardChanges();
			}

			assertEquals(SENDER_BALANCE, alice.getConfirmedBalance(Asset.NATIVE));
			assertEquals(0L, new Account(repository, freshRecipient).getConfirmedBalance(Asset.NATIVE));
		}
	}

	/** At/after the trigger, the same overflowing MULTI_PAYMENT is rejected as invalid — the root fix. */
	@Test
	public void testOverflowingMultiPaymentIsRejectedAtOrAfterTrigger() throws DataException {
		Common.useSettings("test-settings-v2-at-checked-arithmetic.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
			repository.saveChanges();

			// Advance so the next block height is at/after the trigger.
			int fillerBlocks = TRIGGER_HEIGHT - 1 - repository.getBlockRepository().getBlockchainHeight();
			if (fillerBlocks > 0)
				BlockUtils.mintBlocks(repository, fillerBlocks);
			assertTrue("test must run at/after the trigger height",
					repository.getBlockRepository().getBlockchainHeight() + 1 >= TRIGGER_HEIGHT);

			PrivateKeyAccount alice = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount bob = Common.getTestAccount(repository, "bob");
			String freshRecipient = Crypto.toAddress(new byte[32]);

			List<PaymentData> payments = overflowingPayments(freshRecipient, bob.getAddress());

			// The unrepresentable total is rejected deterministically within the validation contract.
			assertEquals(ValidationResult.NO_BALANCE, buildMultiPayment(repository, alice, payments).isValid());
			assertEquals(ValidationResult.NO_BALANCE,
					new Payment(repository).isValid(alice.getPublicKey(), payments, 0L));

			// Ordinary, non-overflowing multi-payments still validate after the flag day.
			List<PaymentData> ordinaryPayments = Arrays.asList(
					new PaymentData(bob.getAddress(), Asset.NATIVE, 1_000L),
					new PaymentData(freshRecipient, Asset.NATIVE, 2_000L));
			assertEquals(ValidationResult.OK, buildMultiPayment(repository, alice, ordinaryPayments).isValid());
		}
	}

	// Support

	/** The review's forge scenario: three payments summing to {@code Long.MAX_VALUE + 5e11}. */
	private static List<PaymentData> overflowingPayments(String freshRecipient, String bobAddress) {
		return Arrays.asList(
				new PaymentData(freshRecipient, Asset.NATIVE, PAYMENT_1),
				new PaymentData(bobAddress, Asset.NATIVE, PAYMENT_2),
				new PaymentData(bobAddress, Asset.NATIVE, PAYMENT_3));
	}

	private static MultiPaymentTransaction buildMultiPayment(Repository repository, PrivateKeyAccount sender,
			List<PaymentData> payments) {
		BaseTransactionData baseTransactionData =
				new BaseTransactionData(System.currentTimeMillis(), Group.NO_GROUP, sender.getPublicKey(), 0L, null);
		MultiPaymentTransactionData transactionData = new MultiPaymentTransactionData(baseTransactionData, payments);

		return new MultiPaymentTransaction(repository, transactionData);
	}
}
