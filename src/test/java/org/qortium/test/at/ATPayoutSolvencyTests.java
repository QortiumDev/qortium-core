package org.qortium.test.at;

import com.google.common.primitives.Bytes;
import org.ciyam.at.CompilationException;
import org.ciyam.at.FunctionCode;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.at.ChainFunctionCode;
import org.qortium.block.BlockChain;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.DeployAtTransactionData;
import org.qortium.data.transaction.IssueAssetTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.TransferAssetTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AssetUtils;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TestChainBootstrapUtils;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.utils.Amounts;
import org.qortium.utils.Base58;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Solvency and divisibility invariants for AT payouts.
 *
 * <p>These cover the gap between the two payout paths available to an AT: the Qortium asset
 * functions ({@link ChainFunctionCode#PAY_ASSET_AMOUNT_TO_B}) and the stock CIYAM
 * {@code PAY_*} opcodes. Existing coverage in {@link ATAssetSupportTests} exercises each
 * path alone, and only ever with a divisible asset.
 */
public class ATPayoutSolvencyTests extends Common {

	private static final long PREFUND_AMOUNT = 25L * Amounts.MULTIPLIER;
	private static final long PARTIAL_PAYOUT = 15L * Amounts.MULTIPLIER;
	private static final long NATIVE_FEE_RESERVE = 2L * Amounts.MULTIPLIER;

	/** One raw sub-unit: an amount that is not a whole quantity of an indivisible asset. */
	private static final long SUB_UNIT_AMOUNT = 1L;

	/** Whole platform-function payout used alongside {@link #INDIVISIBLE_STOCK_PAYOUT_REQUEST}. */
	private static final long INDIVISIBLE_PLATFORM_PAYOUT = 5L * Amounts.MULTIPLIER;

	/**
	 * A stock-opcode payout request that is NOT a whole quantity of an indivisible asset.
	 *
	 * <p>Unlike {@code PAY_ASSET_AMOUNT_TO_B} ({@link org.qortium.at.ChainATAPI#payAssetAmountToB}), the
	 * stock CIYAM {@code PAY_*} opcodes take their amount straight from AT data with no divisibility check
	 * on the request itself -- a script can set this to anything, whole or not. It must still come out
	 * rounded to a whole quantity, and enough of it (12 whole units) must survive the rounding that a fix
	 * which simply zeroed every indivisible-asset payout could not pass this test.
	 */
	private static final long INDIVISIBLE_STOCK_PAYOUT_REQUEST = 12L * Amounts.MULTIPLIER + 33L;

	/** Whole platform-function payout used alongside {@link #NEGATIVE_STOCK_PAYOUT_REQUEST}. */
	private static final long PLATFORM_PAYOUT_AMOUNT = 5L * Amounts.MULTIPLIER;

	/**
	 * A stock-opcode payout request that is negative. {@code PAY_TO_ADDRESS_IN_B}'s amount is an
	 * AT-controlled data cell, and the VM's clamp is {@code min(currentBalance, value1)}, which passes a
	 * negative value straight through -- the VM then debits it, INFLATING the machine balance. Any AT
	 * deployer can supply this.
	 */
	private static final long NEGATIVE_STOCK_PAYOUT_REQUEST = -(5L * Amounts.MULTIPLIER);

	/** Native (QORT) working balance an AT is deployed with for the native-asset fee-halt tests. */
	private static final long NATIVE_WORKING_BALANCE = 25L * Amounts.MULTIPLIER;

	/** A platform-payout request larger than any balance under test, so it pays out everything spendable. */
	private static final long DRAIN_EVERYTHING_REQUEST = 1_000L * Amounts.MULTIPLIER;

	/**
	 * Deploy-time worst-case bound for {@link #buildMinimalAT}, derived purely from {@link MachineState}
	 * constants so it tracks the production serialization format instead of a hard-coded byte count.
	 *
	 * <p>An unstarted machine's {@code toBytes()} serialization (see {@code MachineState#toBytes}) is:
	 * <pre>
	 *   HEADER_LENGTH                                     (version + reserved + 4 page/stack counts + min-activation)
	 * + numDataPages * MachineState.VALUE_SIZE              (DATA_PAGE_SIZE == VALUE_SIZE for v2 ATs)
	 * + 4  call-stack length prefix (stack itself is empty; nothing has run yet)
	 * + 4  user-stack length prefix (stack itself is empty; nothing has run yet)
	 * + 4  programCounter
	 * + 4  onStopAddress
	 * + 8  previousBalance
	 * + 4  flags word
	 * </pre>
	 * with the optional onErrorAddress/sleepUntilHeight/frozenBalance/A/B fields all omitted, since none
	 * of those are set on a freshly-constructed machine.
	 */
	private static final int MINIMAL_AT_DATA_PAGES = 1;
	private static final int MINIMAL_AT_EMPTY_STATE_LENGTH = MachineState.HEADER_LENGTH
			+ MINIMAL_AT_DATA_PAGES * MachineState.VALUE_SIZE
			+ 4 // call-stack length prefix
			+ 4 // user-stack length prefix
			+ 4 // programCounter
			+ 4 // onStopAddress
			+ 8 // previousBalance
			+ 4; // flags word

	/**
	 * Mirrors the fixed additions in {@code DeployAtTransaction#worstCaseStateLength}, excluding the
	 * onErrorAddress term under test: both A and B registers, a sleep-until height and a frozen balance.
	 */
	private static final int WORST_CASE_FIXED_OVERHEAD_EXCLUDING_ERROR_ADDRESS =
			2 * MachineState.AB_REGISTER_SIZE + MachineState.ADDRESS_SIZE + MachineState.VALUE_SIZE;

	/**
	 * Number of call-stack pages (4 bytes each, {@link MachineState#ADDRESS_SIZE}) that puts
	 * {@link #buildMinimalAT}'s worst-case state length at exactly
	 * {@link DeployAtTransaction#MAX_AT_STATE_LENGTH} when the onErrorAddress term is NOT counted. So
	 * counting it (the fix) pushes the worst case 4 bytes over the limit and deploy is rejected, while
	 * omitting it (the bug) leaves the worst case sitting exactly at the limit, which is accepted.
	 */
	private static final short NUM_CALL_STACK_PAGES_AT_ERROR_ADDRESS_BOUNDARY = (short) (
			(DeployAtTransaction.MAX_AT_STATE_LENGTH - MINIMAL_AT_EMPTY_STATE_LENGTH - WORST_CASE_FIXED_OVERHEAD_EXCLUDING_ERROR_ADDRESS)
					/ MachineState.ADDRESS_SIZE);

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	/**
	 * An AT must not pay out more of its configured asset than it holds.
	 *
	 * <p>{@code PAY_ASSET_AMOUNT_TO_B} records payouts in {@code pendingAssetPayouts} without
	 * decrementing the machine's current balance, while {@code PAY_ALL_TO_ADDRESS_IN_B} spends
	 * {@code state.getCurrentBalance()} without consulting that map. Calling the platform
	 * function first therefore lets the same balance be spent twice.
	 */
	@Test
	public void testAtCannotOverspendConfiguredAssetAcrossPayoutPaths() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "bob");
			long assetId = AssetUtils.issueAsset(repository, "alice", "AT-OVERSPEND", 100L * Amounts.MULTIPLIER, true);

			byte[] creationBytes = buildDoublePayoutAT(recipient.getAddress(), assetId, PARTIAL_PAYOUT);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, 0L, assetId, NATIVE_FEE_RESERVE);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			long recipientInitialBalance = recipient.getConfirmedBalance(assetId);

			// Funds the AT and supplies the transaction that triggers execution.
			transferAsset(repository, deployer, atAddress, assetId, PREFUND_AMOUNT);

			BlockUtils.mintBlock(repository);

			long totalPaidOut = recipient.getConfirmedBalance(assetId) - recipientInitialBalance;
			long atFinalBalance = atAccount.getConfirmedBalance(assetId);

			assertTrue(String.format("AT balance must never go negative, was %d", atFinalBalance),
					atFinalBalance >= 0L);
			assertEquals("AT must not pay out more than it was funded with", PREFUND_AMOUNT, totalPaidOut);
		}
	}

	/**
	 * An AT must not put a fractional quantity of an indivisible asset into circulation.
	 *
	 * <p>{@code PAY_ASSET_AMOUNT_TO_B} checks divisibility against the <em>requested</em> amount
	 * before clamping to the spendable balance, and the stock {@code PAY_*} opcodes route through
	 * {@code payAmountToB}, which does not check at all.
	 */
	@Test
	public void testAtCannotPayFractionalAmountOfIndivisibleAsset() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "bob");
			long assetId = issueAsset(repository, deployer, "AT-INDIVISIBLE", 100L * Amounts.MULTIPLIER, false);

			byte[] creationBytes = buildNativePayoutAT(recipient.getAddress(), SUB_UNIT_AMOUNT);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, 0L, assetId, NATIVE_FEE_RESERVE);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			long recipientInitialBalance = recipient.getConfirmedBalance(assetId);

			transferAsset(repository, deployer, atAddress, assetId, 5L * Amounts.MULTIPLIER);

			BlockUtils.mintBlock(repository);

			long totalPaidOut = recipient.getConfirmedBalance(assetId) - recipientInitialBalance;

			assertEquals(String.format("indivisible asset payout must be a whole quantity, was %d raw units", totalPaidOut),
					0L, totalPaidOut % Amounts.MULTIPLIER);
		}
	}

	/**
	 * An AT declaring stacks deep enough to outgrow the state limit at runtime must be rejected at deploy.
	 *
	 * <p>The deploy-time check serializes an unstarted machine, whose stacks are empty, so a small AT can
	 * declare very deep stacks and only exceed the limit once it has pushed to them.
	 */
	@Test
	public void testDeployRejectsAtWhoseWorstCaseStateExceedsLimit() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long assetId = AssetUtils.issueAsset(repository, "alice", "AT-DEEP-STACK", 100L * Amounts.MULTIPLIER, true);

			// 300 user stack pages * 8 bytes = 2400 bytes of stack alone, over the 2048-byte state limit,
			// while the unstarted machine serializes to well under it.
			byte[] creationBytes = buildMinimalAT((short) 0, (short) 300);

			assertEquals(ValidationResult.INVALID_CREATION_BYTES,
					validateDeployAt(repository, deployer, creationBytes, assetId));
		}
	}

	/**
	 * An AT executing at exactly the {@code atPayoutSolvencyHeight} trigger height must already have the
	 * overspend clamp enforced.
	 *
	 * <p>{@code isPayoutSolvencyEnforced()} compares against {@code getCurrentBlockHeight() + 1}, because
	 * during AT execution {@code getCurrentBlockHeight()} still returns the parent block's height (the
	 * block being built has not been persisted yet). Without the {@code + 1}, the clamp only switches on
	 * one block after the configured trigger height, so an AT executing in the trigger block itself could
	 * still double-spend. {@code test-chain-v2.json}'s default {@code atPayoutSolvencyHeight} of 0 can
	 * never exercise this boundary since the clamp is then permanently enforced from genesis, so this test
	 * switches to {@code test-chain-v2-at-payout-solvency.json}, whose only difference is a non-zero
	 * trigger height.
	 */
	@Test
	public void testAtDoesNotOverspendConfiguredAssetAtExactPayoutSolvencyTriggerHeight() throws DataException {
		Common.useSettings("test-settings-v2-at-payout-solvency.json");

		try (final Repository repository = RepositoryManager.getRepository()) {
			// This chain config's file name does not end in "test-chain-v2.json", so Common's own
			// bootstrap-on-reset skips setting up alice as a minting-group member with a reward share;
			// do it explicitly here, as other tests using non-default chain variants also do.
			TestChainBootstrapUtils.ensureDefaultTestChainBootstrap(repository);
			repository.saveChanges();

			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "bob");
			long assetId = AssetUtils.issueAsset(repository, "alice", "AT-BOUNDARY", 100L * Amounts.MULTIPLIER, true);

			byte[] creationBytes = buildDoublePayoutAT(recipient.getAddress(), assetId, PARTIAL_PAYOUT);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, 0L, assetId, NATIVE_FEE_RESERVE);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			int triggerHeight = (int) BlockChain.getInstance().getAtPayoutSolvencyHeight();
			assertTrue("test chain config must set a positive atPayoutSolvencyHeight for this test to be meaningful",
					triggerHeight > 0);

			int heightAfterDeploy = repository.getBlockRepository().getBlockchainHeight();

			// AT-generated transactions only become visible to the AT one block after they were sent (AT
			// execution for a block only sees transactions already persisted from earlier blocks), so
			// funding the AT in (triggerHeight - 1) makes it execute its payout in exactly triggerHeight.
			int fundingBlockHeight = triggerHeight - 1;
			int fillerBlockCount = fundingBlockHeight - 1 - heightAfterDeploy;
			assertTrue("trigger height is too small relative to deploy height for this test's block-height arithmetic",
					fillerBlockCount >= 0);
			if (fillerBlockCount > 0)
				BlockUtils.mintBlocks(repository, fillerBlockCount);

			long recipientInitialBalance = recipient.getConfirmedBalance(assetId);

			// Funds the AT and supplies the transaction that triggers execution; mints the funding block.
			transferAsset(repository, deployer, atAddress, assetId, PREFUND_AMOUNT);
			assertEquals("test setup did not land the funding transaction in the expected block",
					fundingBlockHeight, repository.getBlockRepository().getBlockchainHeight());

			// The AT reacts to the funding transaction and executes its double payout here, in the block
			// at exactly triggerHeight. Under the pre-fix code (no "+1"), the clamp is not yet enforced at
			// this height, the double payout overspends, the AT's balance goes negative, and mintBlock()
			// throws a DataException from the repository's CHECKBALANCENOTNEGATIVE constraint.
			BlockUtils.mintBlock(repository);
			assertEquals("test setup did not land AT execution in the expected trigger block",
					triggerHeight, repository.getBlockRepository().getBlockchainHeight());

			long totalPaidOut = recipient.getConfirmedBalance(assetId) - recipientInitialBalance;
			long atFinalBalance = atAccount.getConfirmedBalance(assetId);

			assertTrue(String.format("AT balance must never go negative, was %d", atFinalBalance),
					atFinalBalance >= 0L);
			assertEquals("AT must not pay out more than it was funded with", PREFUND_AMOUNT, totalPaidOut);
		}
	}

	/**
	 * An indivisible asset's payout paths must round a genuinely fractional request down to a whole
	 * quantity, not merely handle the trivial case where the requested amount rounds all the way to zero.
	 *
	 * <p>{@link #testAtCannotPayFractionalAmountOfIndivisibleAsset} only ever pays
	 * {@link #SUB_UNIT_AMOUNT} (1 raw unit), which rounds to zero -- a check that would also pass if the
	 * fix instead zeroed every payout of an indivisible asset outright. This test instead combines both
	 * payout paths (as {@link #testAtCannotOverspendConfiguredAssetAcrossPayoutPaths} does for a divisible
	 * asset): {@code PAY_ASSET_AMOUNT_TO_B} pays a whole partial amount, and the stock
	 * {@code PAY_TO_ADDRESS_IN_B} then requests {@link #INDIVISIBLE_STOCK_PAYOUT_REQUEST}, a large,
	 * data-supplied amount that is not a whole quantity -- the stock opcode does not validate divisibility
	 * of its own request the way {@code PAY_ASSET_AMOUNT_TO_B} does, so this is the realistic route by
	 * which a fractional amount reaches the "last line of defence" rounding in
	 * {@code ChainATAPI#addPayment}. Both payouts combined are large enough that a naive "zero it out" fix
	 * could not pass this test.
	 *
	 * <p>The assertions are exact, not merely "whole and positive". If the VM debited its machine balance by
	 * the full pre-rounding request instead of by what was actually emitted, the difference would resurface
	 * in {@code onFinished}, whose refund of the too-small machine balance is itself rounded down again --
	 * permanently stranding a whole unit in the finished AT. Because {@code payAmountToB} returns the emitted
	 * amount and the VM debits exactly that, the machine balance stays whole and conservation is exact.
	 * Asserting the exact payout, the exact refund and a zero final AT balance is what distinguishes true
	 * conservation from that silent stranding.
	 */
	@Test
	public void testAtRoundsFractionalIndivisibleAssetPayoutAcrossBothPayoutPaths() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "bob");
			long assetId = issueAsset(repository, deployer, "AT-INDIVISIBLE-CLAMP", 100L * Amounts.MULTIPLIER, false);

			byte[] creationBytes = buildDoublePayoutWithFractionalTailAT(recipient.getAddress(), assetId,
					INDIVISIBLE_PLATFORM_PAYOUT, INDIVISIBLE_STOCK_PAYOUT_REQUEST);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, 0L, assetId, NATIVE_FEE_RESERVE);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			long recipientInitialBalance = recipient.getConfirmedBalance(assetId);

			// Ample funding: neither payout needs to be clamped against the AT's balance, isolating the
			// divisibility rounding itself as the thing under test.
			transferAsset(repository, deployer, atAddress, assetId, PREFUND_AMOUNT);

			// Capture after the funding transfer so the delta below is purely the AT's refund.
			long deployerBalanceBeforeRun = deployer.getConfirmedBalance(assetId);

			BlockUtils.mintBlock(repository);

			long totalPaidOut = recipient.getConfirmedBalance(assetId) - recipientInitialBalance;
			long refund = deployer.getConfirmedBalance(assetId) - deployerBalanceBeforeRun;
			long atFinalBalance = atAccount.getConfirmedBalance(assetId);

			long expectedStockPayout = INDIVISIBLE_STOCK_PAYOUT_REQUEST
					- (INDIVISIBLE_STOCK_PAYOUT_REQUEST % Amounts.MULTIPLIER);
			long expectedPaidOut = INDIVISIBLE_PLATFORM_PAYOUT + expectedStockPayout;

			assertEquals("payout must be exactly the platform amount plus the rounded-down stock amount",
					expectedPaidOut, totalPaidOut);
			assertEquals("everything not paid out must come back to the creator, exactly",
					PREFUND_AMOUNT - expectedPaidOut, refund);
			assertEquals("nothing may be left stranded in the finished AT", 0L, atFinalBalance);
		}
	}

	/**
	 * A negative stock-opcode payout request must not inflate the AT's balance or halt the chain.
	 *
	 * <p>The VM's {@code min(currentBalance, value1)} clamp passes a negative {@code value1} straight
	 * through. If the VM then debited the machine balance by that request it would INFLATE while nothing is
	 * emitted, and {@code onFinished} would refund against the inflated figure, over-paying the AT's real
	 * asset balance -- the repository's {@code CHECKBALANCENOTNEGATIVE} constraint then surfacing as an
	 * unhandled {@code DataException} that makes the block unprocessable on every node: the same class of
	 * chain halt the solvency fix targets, reachable by anyone who can deploy an AT. Because
	 * {@code payAmountToB} returns 0 for the suppressed negative payout and the VM debits only what was
	 * returned, the machine balance never inflates.
	 */
	@Test
	public void testNegativeStockPayoutMustNotInflateBalanceOrHaltBlock() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "bob");
			long assetId = AssetUtils.issueAsset(repository, "alice", "AT-NEGATIVE-PAY", 100L * Amounts.MULTIPLIER, true);

			byte[] creationBytes = buildDoublePayoutWithFractionalTailAT(recipient.getAddress(), assetId,
					PLATFORM_PAYOUT_AMOUNT, NEGATIVE_STOCK_PAYOUT_REQUEST);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, 0L, assetId, NATIVE_FEE_RESERVE);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			long recipientInitialBalance = recipient.getConfirmedBalance(assetId);

			transferAsset(repository, deployer, atAddress, assetId, PREFUND_AMOUNT);

			long deployerBalanceBeforeRun = deployer.getConfirmedBalance(assetId);

			// Under the unreconciled code this throws: the refund over-pays the inflated machine balance
			// and the repository's balance constraint aborts block processing.
			BlockUtils.mintBlock(repository);

			long totalPaidOut = recipient.getConfirmedBalance(assetId) - recipientInitialBalance;
			long refund = deployer.getConfirmedBalance(assetId) - deployerBalanceBeforeRun;
			long atFinalBalance = atAccount.getConfirmedBalance(assetId);

			assertEquals("only the platform payout may be emitted; the negative stock request must pay nothing",
					PLATFORM_PAYOUT_AMOUNT, totalPaidOut);
			assertEquals("the refund must ignore the VM's inflated machine balance",
					PREFUND_AMOUNT - PLATFORM_PAYOUT_AMOUNT, refund);
			assertEquals("nothing may be left stranded in the finished AT", 0L, atFinalBalance);
		}
	}

	/**
	 * A negative stock payout's balance inflation must not let a LATER payout in the same round overspend.
	 *
	 * <p>This is the mid-round half of the negative-payout defect, distinct from the refund half above:
	 * if the negative request inflated the machine balance, a following {@code PAY_ALL_TO_ADDRESS_IN_B}
	 * would pass that inflated balance as its amount and the solvency clamp -- reading the same machine
	 * balance via {@code getSpendableAssetBalance} -- would overestimate and let the AT overspend. Because
	 * the negative payout is debited as 0, the machine balance stays exact and {@code PAY_ALL} pays only the
	 * true remaining balance.
	 */
	@Test
	public void testNegativeStockPayoutMustNotLetLaterPayoutOverspend() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "bob");
			long assetId = AssetUtils.issueAsset(repository, "alice", "AT-NEGATIVE-PAYALL", 100L * Amounts.MULTIPLIER, true);

			byte[] creationBytes = buildNegativeThenPayAllAT(recipient.getAddress(), assetId,
					PLATFORM_PAYOUT_AMOUNT, NEGATIVE_STOCK_PAYOUT_REQUEST);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, 0L, assetId, NATIVE_FEE_RESERVE);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			long recipientInitialBalance = recipient.getConfirmedBalance(assetId);

			transferAsset(repository, deployer, atAddress, assetId, PREFUND_AMOUNT);

			long deployerBalanceBeforeRun = deployer.getConfirmedBalance(assetId);

			// Under a clamp that trusts the inflated machine balance, PAY_ALL emits more than the AT holds
			// and the repository's balance constraint aborts block processing.
			BlockUtils.mintBlock(repository);

			long totalPaidOut = recipient.getConfirmedBalance(assetId) - recipientInitialBalance;
			long refund = deployer.getConfirmedBalance(assetId) - deployerBalanceBeforeRun;
			long atFinalBalance = atAccount.getConfirmedBalance(assetId);

			assertEquals("PAY_ALL must pay exactly the true remaining balance, not the inflated machine balance",
					PREFUND_AMOUNT, totalPaidOut);
			assertEquals("nothing remains after PAY_ALL, so the refund must be exactly zero", 0L, refund);
			assertEquals("nothing may be left stranded in the finished AT", 0L, atFinalBalance);
		}
	}

	/**
	 * A negative stock payout must not halt a block through step fees when the AT's working asset is the
	 * native coin -- the case where the payout pool and the fee pool are the same balance.
	 *
	 * <p>The VM deducts each opcode's step fee from its machine balance and gates further execution on that
	 * balance. If a negative payout inflated the machine balance, the VM would run (and charge) more steps
	 * than the AT's real native balance funds; block processing later debits those fees from the real
	 * account ({@code Block.processAtFeesAndStates}) and trips {@code CHECKBALANCENOTNEGATIVE}. This path is
	 * invisible to the non-native tests above, whose fees come from a separate native reserve. Because
	 * {@code payAmountToB} returns 0 for the negative payout and the VM debits only what was returned, the
	 * machine balance stays exact, the fee gate sees the truth, and the block processes. A subsequent
	 * {@code PAY_ALL} then pays out only the genuinely remaining balance.
	 */
	@Test
	public void testNegativeNativePayoutMustNotHaltBlockViaStepFees() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "bob");

			byte[] creationBytes = buildNativeNegativeThenPayAllAT(recipient.getAddress(), NEGATIVE_STOCK_PAYOUT_REQUEST);

			// Native working asset: fund the AT's native balance at deploy; fees come out of that same balance.
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, NATIVE_WORKING_BALANCE);
			Account atAccount = deployAtTransaction.getATAccount();

			long recipientInitialBalance = recipient.getConfirmedBalance(Asset.NATIVE);

			// The AT runs on the next minted block. Under a VM that debited the negative request, its machine
			// balance would inflate, extra steps would run, and this mintBlock would throw from the balance
			// constraint. It must process cleanly instead.
			BlockUtils.mintBlock(repository);

			long paidToRecipient = recipient.getConfirmedBalance(Asset.NATIVE) - recipientInitialBalance;
			long atFinalBalance = atAccount.getConfirmedBalance(Asset.NATIVE);

			assertTrue("AT native balance must never go negative, was " + atFinalBalance, atFinalBalance >= 0L);
			assertTrue("the negative payout must be suppressed, so PAY_ALL pays a positive amount", paidToRecipient > 0L);
			assertTrue(String.format("AT must not pay out more native than it was funded with (paid %d, funded %d)",
					paidToRecipient, NATIVE_WORKING_BALANCE), paidToRecipient <= NATIVE_WORKING_BALANCE);
			assertEquals("PAY_ALL must leave the AT empty", 0L, atFinalBalance);
		}
	}

	/**
	 * A platform-function payout ({@code PAY_ASSET_AMOUNT_TO_B}) of the AT's native working asset must not
	 * halt a block through step fees either -- the same fee-halt class as
	 * {@link #testNegativeNativePayoutMustNotHaltBlockViaStepFees}, but reached through the Qortium platform
	 * payout path instead of a negative stock request.
	 *
	 * <p>The platform payout emits the payment but, unlike the stock pay opcodes, the VM does not
	 * subtract it from the machine balance. For a native working asset (payout pool == fee pool), if that
	 * payout did not reduce the machine balance the fee-affordability gate would still see the pre-payout
	 * balance, admit and charge further opcodes the AT can no longer fund, and block processing would trip
	 * {@code CHECKBALANCENOTNEGATIVE}. The fix has {@code payAssetAmountToB} debit the machine balance for a
	 * configured-asset payout, so the AT here drains its balance, then freezes at the next opcode (which it
	 * can no longer afford) rather than overspending, and the block processes.
	 */
	@Test
	public void testNativePlatformPayoutMustNotHaltBlockViaStepFees() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "bob");

			// Working asset is native (Asset.NATIVE), paid out through the platform function.
			byte[] creationBytes = buildNativePlatformDrainThenSpendAT(recipient.getAddress(), DRAIN_EVERYTHING_REQUEST);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, NATIVE_WORKING_BALANCE);
			Account atAccount = deployAtTransaction.getATAccount();

			long recipientInitialBalance = recipient.getConfirmedBalance(Asset.NATIVE);

			// Under a platform payout that leaves the machine balance untouched, the trailing opcode is
			// wrongly admitted and its fee overspends the drained account, throwing here.
			BlockUtils.mintBlock(repository);

			long paidToRecipient = recipient.getConfirmedBalance(Asset.NATIVE) - recipientInitialBalance;
			long atFinalBalance = atAccount.getConfirmedBalance(Asset.NATIVE);

			assertTrue("AT native balance must never go negative, was " + atFinalBalance, atFinalBalance >= 0L);
			assertTrue("the platform payout must have paid out a positive amount", paidToRecipient > 0L);
			assertTrue(String.format("AT must not pay out more native than it was funded with (paid %d, funded %d)",
					paidToRecipient, NATIVE_WORKING_BALANCE), paidToRecipient <= NATIVE_WORKING_BALANCE);
		}
	}

	/**
	 * An AT declaring an error handler (settable at runtime via {@code ERR_ADR}) must be rejected at
	 * deploy if that pushes its worst-case state over the limit, even though the unstarted machine
	 * serialized at deploy time has no error handler set yet.
	 *
	 * <p>{@link #NUM_CALL_STACK_PAGES_AT_ERROR_ADDRESS_BOUNDARY} is chosen so this AT's worst case sits at
	 * exactly {@link DeployAtTransaction#MAX_AT_STATE_LENGTH} when the onErrorAddress term is not counted,
	 * and 4 bytes over when it is -- straddling the exact boundary FIX 2 addresses.
	 */
	@Test
	public void testDeployRejectsAtWhoseErrorHandlerPushesWorstCaseStateOverLimit() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long assetId = AssetUtils.issueAsset(repository, "alice", "AT-ERR-ADR", 100L * Amounts.MULTIPLIER, true);

			byte[] creationBytes = buildMinimalAT(NUM_CALL_STACK_PAGES_AT_ERROR_ADDRESS_BOUNDARY, (short) 0);

			assertEquals(ValidationResult.INVALID_CREATION_BYTES,
					validateDeployAt(repository, deployer, creationBytes, assetId));
		}
	}

	private static ValidationResult validateDeployAt(Repository repository, PrivateKeyAccount deployer, byte[] creationBytes, long assetId) throws DataException {
		long txTimestamp = System.currentTimeMillis();

		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, deployer.getPublicKey(), null, null);
		TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, "Deep stack AT", "Deep stack AT",
				"Test", "TEST", creationBytes, 0L, assetId, NATIVE_FEE_RESERVE);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		deployAtTransactionData.setFee(deployAtTransaction.calcRecommendedFee());

		return deployAtTransaction.isValid();
	}

	/**
	 * Smallest AT that declares an error handler and the given stack depths, then stops.
	 *
	 * <p>{@code ERR_ADR} declares an error handler ({@link MachineState#setOnErrorAddress}), the field
	 * FIX 2's {@code worstCaseStateLength} accounts for. It is never actually invoked here -- deploy
	 * validation never executes the AT -- it just needs to be present so the deployed code plausibly
	 * matches what {@code worstCaseStateLength} is bounding for, rather than being purely synthetic.
	 */
	private static byte[] buildMinimalAT(short numCallStackPages, short numUserStackPages) {
		ByteBuffer dataByteBuffer = ByteBuffer.allocate(MachineState.VALUE_SIZE);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(8);
		try {
			codeByteBuffer.put(OpCode.ERR_ADR.compile(0));
			codeByteBuffer.put(OpCode.STP_IMD.compile());
		} catch (CompilationException e) {
			throw new IllegalStateException("Unable to compile AT?", e);
		}

		codeByteBuffer.flip();
		byte[] codeBytes = new byte[codeByteBuffer.limit()];
		codeByteBuffer.get(codeBytes);

		return MachineState.toCreationBytes((short) 2, codeBytes, dataByteBuffer.array(),
				numCallStackPages, numUserStackPages, 0L);
	}

	/**
	 * Waits for a transaction, then pays {@code amount} via the Qortium asset function followed by
	 * a stock {@code PAY_ALL_TO_ADDRESS_IN_B}. Both target the same recipient held in B.
	 */
	private static byte[] buildDoublePayoutAT(String recipient, long assetId, long amount) {
		int addrCounter = 0;
		final int addrResult = addrCounter++;
		final int addrAssetId = addrCounter++;
		final int addrAmount = addrCounter++;
		final int addrLastTxTimestamp = addrCounter++;
		final int addrNoTransaction = addrCounter++;
		final int addrRecipientBytes = addrCounter;
		addrCounter += 4;
		final int addrRecipientBytesPointer = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.putLong(addrResult * MachineState.VALUE_SIZE, 0L);
		dataByteBuffer.putLong(addrAssetId * MachineState.VALUE_SIZE, assetId);
		dataByteBuffer.putLong(addrAmount * MachineState.VALUE_SIZE, amount);
		dataByteBuffer.position(addrRecipientBytes * MachineState.VALUE_SIZE);
		dataByteBuffer.put(Bytes.ensureCapacity(Base58.decode(recipient), 32, 0));
		dataByteBuffer.putLong(addrRecipientBytesPointer * MachineState.VALUE_SIZE, addrRecipientBytes);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);
		Integer labelPayout = null;

		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrNoTransaction));
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrNoTransaction, OpCode.calcOffset(codeByteBuffer, labelPayout)));
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				labelPayout = codeByteBuffer.position();

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrRecipientBytesPointer));

				// Platform payout: records a pending payout, leaves the machine balance untouched.
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(ChainFunctionCode.PAY_ASSET_AMOUNT_TO_B.value, addrResult, addrAssetId, addrAmount));

				// VM payout: spends the machine balance, unaware of the payout above.
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PAY_ALL_TO_ADDRESS_IN_B));

				codeByteBuffer.put(OpCode.FIN_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile AT?", e);
			}
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	/**
	 * Waits for a transaction, then pays {@code platformAmount} via the Qortium asset function followed
	 * by a stock {@code PAY_TO_ADDRESS_IN_B} of a fixed {@code stockAmount}. Both target the recipient
	 * held in B.
	 *
	 * <p>Unlike {@link #buildDoublePayoutAT}, the second payout is a data-supplied amount rather than the
	 * machine's entire balance, so {@code stockAmount} can be set to a value that is not a whole quantity
	 * of an indivisible asset -- the stock opcode does not validate that the way
	 * {@code PAY_ASSET_AMOUNT_TO_B} validates {@code platformAmount}.
	 */
	private static byte[] buildDoublePayoutWithFractionalTailAT(String recipient, long assetId, long platformAmount, long stockAmount) {
		int addrCounter = 0;
		final int addrResult = addrCounter++;
		final int addrAssetId = addrCounter++;
		final int addrPlatformAmount = addrCounter++;
		final int addrStockAmount = addrCounter++;
		final int addrLastTxTimestamp = addrCounter++;
		final int addrNoTransaction = addrCounter++;
		final int addrRecipientBytes = addrCounter;
		addrCounter += 4;
		final int addrRecipientBytesPointer = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.putLong(addrResult * MachineState.VALUE_SIZE, 0L);
		dataByteBuffer.putLong(addrAssetId * MachineState.VALUE_SIZE, assetId);
		dataByteBuffer.putLong(addrPlatformAmount * MachineState.VALUE_SIZE, platformAmount);
		dataByteBuffer.putLong(addrStockAmount * MachineState.VALUE_SIZE, stockAmount);
		dataByteBuffer.position(addrRecipientBytes * MachineState.VALUE_SIZE);
		dataByteBuffer.put(Bytes.ensureCapacity(Base58.decode(recipient), 32, 0));
		dataByteBuffer.putLong(addrRecipientBytesPointer * MachineState.VALUE_SIZE, addrRecipientBytes);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);
		Integer labelPayout = null;

		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrNoTransaction));
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrNoTransaction, OpCode.calcOffset(codeByteBuffer, labelPayout)));
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				labelPayout = codeByteBuffer.position();

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrRecipientBytesPointer));

				// Platform payout: whole quantity, recorded as pending without touching the machine balance.
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(ChainFunctionCode.PAY_ASSET_AMOUNT_TO_B.value, addrResult, addrAssetId, addrPlatformAmount));

				// VM payout: a fixed, data-supplied amount that need not be a whole quantity.
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PAY_TO_ADDRESS_IN_B, addrStockAmount));

				codeByteBuffer.put(OpCode.FIN_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile AT?", e);
			}
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	/**
	 * Waits for a transaction, then pays {@code platformAmount} via the Qortium asset function, requests a
	 * stock {@code PAY_TO_ADDRESS_IN_B} of {@code stockAmount} (intended to be negative, inflating the VM's
	 * machine balance), and finally issues a stock {@code PAY_ALL_TO_ADDRESS_IN_B}. All target the
	 * recipient held in B.
	 *
	 * <p>The trailing {@code PAY_ALL} is the point: it spends whatever the VM believes its balance to be,
	 * so it converts the negative request's balance inflation into an actual overspend attempt within the
	 * same round.
	 */
	private static byte[] buildNegativeThenPayAllAT(String recipient, long assetId, long platformAmount, long stockAmount) {
		int addrCounter = 0;
		final int addrResult = addrCounter++;
		final int addrAssetId = addrCounter++;
		final int addrPlatformAmount = addrCounter++;
		final int addrStockAmount = addrCounter++;
		final int addrLastTxTimestamp = addrCounter++;
		final int addrNoTransaction = addrCounter++;
		final int addrRecipientBytes = addrCounter;
		addrCounter += 4;
		final int addrRecipientBytesPointer = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.putLong(addrResult * MachineState.VALUE_SIZE, 0L);
		dataByteBuffer.putLong(addrAssetId * MachineState.VALUE_SIZE, assetId);
		dataByteBuffer.putLong(addrPlatformAmount * MachineState.VALUE_SIZE, platformAmount);
		dataByteBuffer.putLong(addrStockAmount * MachineState.VALUE_SIZE, stockAmount);
		dataByteBuffer.position(addrRecipientBytes * MachineState.VALUE_SIZE);
		dataByteBuffer.put(Bytes.ensureCapacity(Base58.decode(recipient), 32, 0));
		dataByteBuffer.putLong(addrRecipientBytesPointer * MachineState.VALUE_SIZE, addrRecipientBytes);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);
		Integer labelPayout = null;

		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrNoTransaction));
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrNoTransaction, OpCode.calcOffset(codeByteBuffer, labelPayout)));
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				labelPayout = codeByteBuffer.position();

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrRecipientBytesPointer));

				// Platform payout: whole quantity, recorded as pending without touching the machine balance.
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(ChainFunctionCode.PAY_ASSET_AMOUNT_TO_B.value, addrResult, addrAssetId, addrPlatformAmount));

				// VM payout: a data-supplied negative amount, inflating the VM's machine balance.
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PAY_TO_ADDRESS_IN_B, addrStockAmount));

				// VM payout: spends the (now inflated) machine balance in full.
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PAY_ALL_TO_ADDRESS_IN_B));

				codeByteBuffer.put(OpCode.FIN_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile AT?", e);
			}
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	/**
	 * Runs on first execution (no transaction wait): sets B to the recipient, requests a stock
	 * {@code PAY_TO_ADDRESS_IN_B} of {@code stockAmount} (intended negative, which would inflate the VM's
	 * machine balance), then issues {@code PAY_ALL_TO_ADDRESS_IN_B} and finishes.
	 *
	 * <p>Used with a native working asset, so the machine balance is also the AT's step-fee balance: this
	 * is the configuration where a balance inflation turns into extra charged steps and a block halt.
	 */
	private static byte[] buildNativeNegativeThenPayAllAT(String recipient, long stockAmount) {
		int addrCounter = 0;
		final int addrStockAmount = addrCounter++;
		final int addrRecipientBytes = addrCounter;
		addrCounter += 4;
		final int addrRecipientBytesPointer = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.putLong(addrStockAmount * MachineState.VALUE_SIZE, stockAmount);
		dataByteBuffer.position(addrRecipientBytes * MachineState.VALUE_SIZE);
		dataByteBuffer.put(Bytes.ensureCapacity(Base58.decode(recipient), 32, 0));
		dataByteBuffer.putLong(addrRecipientBytesPointer * MachineState.VALUE_SIZE, addrRecipientBytes);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);

		try {
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrRecipientBytesPointer));

			// VM payout: a data-supplied negative amount that would inflate the machine (and fee) balance.
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PAY_TO_ADDRESS_IN_B, addrStockAmount));

			// VM payout: pays out whatever the VM believes remains.
			codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PAY_ALL_TO_ADDRESS_IN_B));

			codeByteBuffer.put(OpCode.FIN_IMD.compile());
		} catch (CompilationException e) {
			throw new IllegalStateException("Unable to compile AT?", e);
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	/**
	 * Runs on first execution: sets B to the recipient, pays {@code drainRequest} of the native working
	 * asset out through the platform function {@code PAY_ASSET_AMOUNT_TO_B} (clamped to everything
	 * spendable), then attempts a further fee-costing opcode and finishes.
	 *
	 * <p>The trailing opcode is the point: with the machine balance correctly reduced by the platform
	 * payout it can no longer be afforded and the AT freezes; without that reduction it is wrongly admitted
	 * and its fee overspends the drained account.
	 */
	private static byte[] buildNativePlatformDrainThenSpendAT(String recipient, long drainRequest) {
		int addrCounter = 0;
		final int addrResult = addrCounter++;
		final int addrAssetId = addrCounter++;
		final int addrDrainAmount = addrCounter++;
		final int addrRecipientBytes = addrCounter;
		addrCounter += 4;
		final int addrRecipientBytesPointer = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.putLong(addrResult * MachineState.VALUE_SIZE, 0L);
		dataByteBuffer.putLong(addrAssetId * MachineState.VALUE_SIZE, Asset.NATIVE);
		dataByteBuffer.putLong(addrDrainAmount * MachineState.VALUE_SIZE, drainRequest);
		dataByteBuffer.position(addrRecipientBytes * MachineState.VALUE_SIZE);
		dataByteBuffer.put(Bytes.ensureCapacity(Base58.decode(recipient), 32, 0));
		dataByteBuffer.putLong(addrRecipientBytesPointer * MachineState.VALUE_SIZE, addrRecipientBytes);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);

		try {
			codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrRecipientBytesPointer));

			// Platform payout of the native working asset: emits the payment; the fix debits the machine balance.
			codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(ChainFunctionCode.PAY_ASSET_AMOUNT_TO_B.value, addrResult, addrAssetId, addrDrainAmount));

			// A further fee-costing opcode, only affordable if the payout did NOT reduce the machine balance.
			codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrResult));

			codeByteBuffer.put(OpCode.FIN_IMD.compile());
		} catch (CompilationException e) {
			throw new IllegalStateException("Unable to compile AT?", e);
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	/** Waits for a transaction, then pays {@code amount} of the configured asset via the stock opcode. */
	private static byte[] buildNativePayoutAT(String recipient, long amount) {
		int addrCounter = 0;
		final int addrAmount = addrCounter++;
		final int addrLastTxTimestamp = addrCounter++;
		final int addrNoTransaction = addrCounter++;
		final int addrRecipientBytes = addrCounter;
		addrCounter += 4;
		final int addrRecipientBytesPointer = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.putLong(addrAmount * MachineState.VALUE_SIZE, amount);
		dataByteBuffer.position(addrRecipientBytes * MachineState.VALUE_SIZE);
		dataByteBuffer.put(Bytes.ensureCapacity(Base58.decode(recipient), 32, 0));
		dataByteBuffer.putLong(addrRecipientBytesPointer * MachineState.VALUE_SIZE, addrRecipientBytes);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);
		Integer labelPayout = null;

		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrNoTransaction));
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrNoTransaction, OpCode.calcOffset(codeByteBuffer, labelPayout)));
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				labelPayout = codeByteBuffer.position();

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrRecipientBytesPointer));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PAY_TO_ADDRESS_IN_B, addrAmount));
				codeByteBuffer.put(OpCode.FIN_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile AT?", e);
			}
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	private static byte[] toCreationBytes(ByteBuffer codeByteBuffer, ByteBuffer dataByteBuffer) {
		codeByteBuffer.flip();

		byte[] codeBytes = new byte[codeByteBuffer.limit()];
		codeByteBuffer.get(codeBytes);

		final short ciyamAtVersion = 2;
		final short numCallStackPages = 0;
		final short numUserStackPages = 0;
		final long minActivationAmount = 0L;

		return MachineState.toCreationBytes(ciyamAtVersion, codeBytes, dataByteBuffer.array(), numCallStackPages, numUserStackPages, minActivationAmount);
	}

	private static long issueAsset(Repository repository, PrivateKeyAccount issuer, String assetName, long quantity, boolean isDivisible) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, issuer.getPublicKey(), AssetUtils.fee, null);
		TransactionData transactionData = new IssueAssetTransactionData(baseTransactionData, assetName, "desc", quantity, isDivisible, "{}", false);

		TransactionUtils.signAndMint(repository, transactionData, issuer);

		return repository.getAssetRepository().fromAssetName(assetName).getAssetId();
	}

	private static void transferAsset(Repository repository, PrivateKeyAccount sender, String recipient, long assetId, long amount) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, sender.getPublicKey(), AssetUtils.fee, null);
		TransactionData transactionData = new TransferAssetTransactionData(baseTransactionData, recipient, amount, assetId);

		assertEquals(ValidationResult.OK, TransactionUtils.signAndImport(repository, transactionData, sender));
		BlockUtils.mintBlock(repository);
	}
}
