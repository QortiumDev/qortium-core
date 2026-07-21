package org.qortium.at;

import com.google.common.primitives.Bytes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ciyam.at.*;
import org.qortium.account.Account;
import org.qortium.account.NullAccount;
import org.qortium.account.PublicKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.block.BlockChain;
import org.qortium.block.BlockChain.CiyamAtSettings;
import org.qortium.crypto.Crypto;
import org.qortium.data.PaymentData;
import org.qortium.data.asset.AssetData;
import org.qortium.data.at.ATData;
import org.qortium.data.block.BlockData;
import org.qortium.data.block.BlockSummaryData;
import org.qortium.data.transaction.*;
import org.qortium.group.Group;
import org.qortium.repository.ATRepository;
import org.qortium.repository.ATRepository.NextTransactionInfo;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transaction.AtTransaction;
import org.qortium.transaction.Transaction.TransactionType;
import org.qortium.utils.Amounts;
import org.qortium.utils.Base58;
import org.qortium.utils.BitTwiddling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChainATAPI extends API {

	private static final byte[] ADDRESS_PADDING = new byte[32 - Account.ADDRESS_LENGTH];
	private static final Logger LOGGER = LogManager.getLogger(ChainATAPI.class);

	// Properties
	private Repository repository;
	private ATData atData;
	private long blockTimestamp;
	private final CiyamAtSettings ciyamAtSettings;

	/** List of generated AT transactions */
	List<AtTransaction> transactions;

	/** Generated platform-function payouts not tracked by CIYAM's single current-balance field. */
	private final Map<Long, Long> pendingAssetPayouts;

	// Constructors

	public ChainATAPI(Repository repository, ATData atData, long blockTimestamp) {
		this.repository = repository;
		this.atData = atData;
		this.transactions = new ArrayList<>();
		this.pendingAssetPayouts = new HashMap<>();
		this.blockTimestamp = blockTimestamp;

		this.ciyamAtSettings = BlockChain.getInstance().getCiyamAtSettings();
	}

	// Methods specific to chain AT processing, not inherited

	public Repository getRepository() {
		return this.repository;
	}

	public List<AtTransaction> getTransactions() {
		return this.transactions;
	}

	public boolean willExecute(int blockHeight) throws DataException {
		// Sleep-until-message/height checking
		Long sleepUntilMessageTimestamp = this.atData.getSleepUntilMessageTimestamp();

		if (sleepUntilMessageTimestamp != null) {
			// Quicker to check height, if sleep-until-height also active
			Integer sleepUntilHeight = this.atData.getSleepUntilHeight();

			boolean wakeDueToHeight = sleepUntilHeight != null && sleepUntilHeight != 0 && blockHeight >= sleepUntilHeight;

			boolean wakeDueToMessage = false;
			if (!wakeDueToHeight) {
				// No avoiding asking repository
				Timestamp previousTxTimestamp = new Timestamp(sleepUntilMessageTimestamp);
				NextTransactionInfo nextTransactionInfo = this.repository.getATRepository().findNextTransaction(this.atData.getATAddress(),
						previousTxTimestamp.blockHeight,
						previousTxTimestamp.transactionSequence);

				wakeDueToMessage = nextTransactionInfo != null;
			}

			// Can we skip?
			if (!wakeDueToHeight && !wakeDueToMessage)
				return false;
		}

		return this.hasNativeFeeBalance();
	}

	public void preExecute(MachineState state) {
		// Sleep-until-message/height checking
		Long sleepUntilMessageTimestamp = this.atData.getSleepUntilMessageTimestamp();

		if (sleepUntilMessageTimestamp != null) {
			// We've passed checks, so clear sleep-related flags/values
			this.setIsSleeping(state, false);
			this.setSleepUntilHeight(state, 0);
			this.atData.setSleepUntilMessageTimestamp(null);
		}
	}

	// Inherited methods from CIYAM AT API

	@Override
	public int getMaxStepsPerRound() {
		if (this.usesNativeWorkingAsset() || this.ciyamAtSettings.feePerStep <= 0)
			return this.ciyamAtSettings.maxStepsPerRound;

		long nativeStepBudget = this.getNativeFeeBalance() / this.ciyamAtSettings.feePerStep;
		return (int) Math.min(this.ciyamAtSettings.maxStepsPerRound, Math.max(0L, nativeStepBudget));
	}

	@Override
	public int getOpCodeSteps(OpCode opcode) {
		if (opcode.value >= OpCode.EXT_FUN.value && opcode.value <= OpCode.EXT_FUN_RET_DAT_2.value)
			return this.ciyamAtSettings.stepsPerFunctionCall;

		return 1;
	}

	@Override
	public long getFeePerStep() {
		return this.usesNativeWorkingAsset() ? this.ciyamAtSettings.feePerStep : 0L;
	}

	@Override
	public int getCurrentBlockHeight() {
		try {
			return this.repository.getBlockRepository().getBlockchainHeight();
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch current blockchain height?", e);
		}
	}

	@Override
	public int getATCreationBlockHeight(MachineState state) {
		try {
			return this.repository.getATRepository().getATCreationBlockHeight(this.atData.getATAddress());
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch AT's creation block height?", e);
		}
	}

	@Override
	public void putPreviousBlockHashIntoA(MachineState state) {
		try {
			int previousBlockHeight = this.repository.getBlockRepository().getBlockchainHeight() - 1;

			// We only need signature, so only request a block summary
			List<BlockSummaryData> blockSummaries = this.repository.getBlockRepository().getBlockSummaries(previousBlockHeight, previousBlockHeight);
			if (blockSummaries == null || blockSummaries.size() != 1)
				throw new RuntimeException("AT API unable to fetch previous block hash?");

			// Block's signature is 128 bytes so we need to reduce this to 4 longs (32 bytes)
			// To be able to use hash to look up block, save height (8 bytes) and partial signature (24 bytes)
			this.setA1(state, previousBlockHeight);

			byte[] signature = blockSummaries.get(0).getSignature();
			// Save some of minter's signature and transactions signature, so middle 24 bytes of the full 128 byte signature.
			this.setA2(state, BitTwiddling.longFromBEBytes(signature, 52));
			this.setA3(state, BitTwiddling.longFromBEBytes(signature, 60));
			this.setA4(state, BitTwiddling.longFromBEBytes(signature, 68));
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch previous block?", e);
		}
	}

	@Override
	public void putTransactionAfterTimestampIntoA(Timestamp timestamp, MachineState state) {
		// Recipient is this AT
		String atAddress = this.atData.getATAddress();

		int height = timestamp.blockHeight;
		int sequence = timestamp.transactionSequence;

		ATRepository.NextTransactionInfo nextTransactionInfo;
		try {
			nextTransactionInfo = this.getRepository().getATRepository().findNextTransaction(atAddress, height, sequence);
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch next transaction?", e);
		}

		if (nextTransactionInfo == null) {
			// No more transactions for AT at this time - zero A and exit
			this.zeroA(state);
			return;
		}

		// Found a transaction

		this.setA1(state, new Timestamp(nextTransactionInfo.height, timestamp.blockchainId, nextTransactionInfo.sequence).longValue());

		// Copy transaction's partial signature into the other three A fields for future verification that it's the same transaction
		this.setA2(state, BitTwiddling.longFromBEBytes(nextTransactionInfo.signature, 8));
		this.setA3(state, BitTwiddling.longFromBEBytes(nextTransactionInfo.signature, 16));
		this.setA4(state, BitTwiddling.longFromBEBytes(nextTransactionInfo.signature, 24));
	}

	@Override
	public long getTypeFromTransactionInA(MachineState state) {
		TransactionData transactionData = this.getTransactionFromA(state);

		switch (transactionData.getType()) {
			case PAYMENT:
			case TRANSFER_ASSET:
			case MULTI_PAYMENT:
				return ATTransactionType.PAYMENT.value;

			case MESSAGE:
				return ATTransactionType.MESSAGE.value;

			case AT:
				if (((ATTransactionData) transactionData).getAmount() != null)
					return ATTransactionType.PAYMENT.value;
				else
					return ATTransactionType.MESSAGE.value;

			default:
				return 0xffffffffffffffffL;
		}
	}

	@Override
	public long getAmountFromTransactionInA(MachineState state) {
		TransactionData transactionData = this.getTransactionFromA(state);

		switch (transactionData.getType()) {
			case PAYMENT:
				return ((PaymentTransactionData) transactionData).getAmount();

			case TRANSFER_ASSET:
				return ((TransferAssetTransactionData) transactionData).getAmount();

			case MULTI_PAYMENT:
				MultiPaymentSummary multiPaymentSummary = this.summarizeMultiPaymentToAt((MultiPaymentTransactionData) transactionData);

				if (multiPaymentSummary.hasSingleAsset())
					return multiPaymentSummary.amount;

				return 0xffffffffffffffffL;

			case MESSAGE:
				long messageAmount = ((MessageTransactionData) transactionData).getAmount();

				if (messageAmount != 0L)
					return messageAmount;

				return 0xffffffffffffffffL;

			case AT:
				Long amount = ((ATTransactionData) transactionData).getAmount();

				if (amount != null)
					return amount;

				// fall-through to default

			default:
				return 0xffffffffffffffffL;
		}
	}

	@Override
	public long getTimestampFromTransactionInA(MachineState state) {
		// Transaction's "timestamp" already stored in A1
		Timestamp timestamp = new Timestamp(this.getA1(state));
		return timestamp.longValue();
	}

	@Override
	public long generateRandomUsingTransactionInA(MachineState state) {
		// The plan here is to sleep for a block then use next block's signature
		// and this transaction's signature to generate pseudo-random, but deterministic, value.

		if (!isFirstOpCodeAfterSleeping(state)) {
			// First call

			// Sleep for a block
			this.setIsSleeping(state, true);

			return 0L; // not used
		} else {
			// Second call

			// HASH(A and new block hash)
			TransactionData transactionData = this.getTransactionFromA(state);

			try {
				BlockData blockData = this.repository.getBlockRepository().getLastBlock();

				if (blockData == null)
					throw new RuntimeException("AT API unable to fetch latest block?");

				byte[] input = Bytes.concat(transactionData.getSignature(), blockData.getSignature());

				byte[] hash = Crypto.digest(input);

				return BitTwiddling.longFromBEBytes(hash, 0);
			} catch (DataException e) {
				throw new RuntimeException("AT API unable to fetch latest block from repository?", e);
			}
		}
	}

	@Override
	public void putMessageFromTransactionInAIntoB(MachineState state) {
		// Zero B in case of issues or shorter-than-B message
		this.zeroB(state);

		TransactionData transactionData = this.getTransactionFromA(state);

		byte[] messageData = this.getMessageFromTransaction(transactionData);

		// Pad messageData to fit B
		if (messageData.length < 4 * 8)
			messageData = Bytes.ensureCapacity(messageData, 4 * 8, 0);

		// Endian must be correct here so that (for example) a SHA256 message can be compared to one generated locally
		this.setB(state, messageData);
	}

	@Override
	public void putAddressFromTransactionInAIntoB(MachineState state) {
		TransactionData transactionData = this.getTransactionFromA(state);

		String address;
		if (transactionData.getType() == TransactionType.AT) {
			// Use AT address from transaction data, as transaction's public key will always be fake
			address = ((ATTransactionData) transactionData).getATAddress();
		} else {
			byte[] publicKey = transactionData.getCreatorPublicKey();
			address = Crypto.toAddress(publicKey);
		}

		// Convert to byte form as this only takes 25 bytes,
		// compared to string-form's 34 bytes,
		// and we only have 32 bytes available.
		byte[] addressBytes = Bytes.ensureCapacity(Base58.decode(address), 32, 0); // pad to 32 bytes

		this.setB(state, addressBytes);
	}

	@Override
	public void putCreatorAddressIntoB(MachineState state) {
		byte[] publicKey = atData.getCreatorPublicKey();
		String address = Crypto.toAddress(publicKey);

		// Convert to byte form as this only takes 25 bytes,
		// compared to string-form's 34 bytes,
		// and we only have 32 bytes available.
		byte[] addressBytes = Bytes.ensureCapacity(Base58.decode(address), 32, 0); // pad to 32 bytes

		this.setB(state, addressBytes);
	}

	@Override
	public long getCurrentBalance(MachineState state) {
		try {
			Account atAccount = this.getATAccount();

			return atAccount.getConfirmedBalance(this.atData.getAssetId());
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch AT's current balance?", e);
		}
	}

	@Override
	public void payAmountToB(long amount, MachineState state) {
		long assetId = this.atData.getAssetId();

		if (this.isPayoutSolvencyEnforced()) {
			// The VM clamps against its own current balance, which does not account for payouts already made
			// this round via PAY_ASSET_AMOUNT_TO_B. Clamp again against what is genuinely still spendable.
			// onFinished() subtracts the same pending total from the final balance, so value is conserved.
			//
			// Invariant: getSpendableAssetBalance() == the AT's true remaining balance at every point in a
			// round. This holds only because the machine's currentBalance is touched by exactly the three
			// stock CIYAM pay opcodes (PAY_TO_ADDRESS_IN_B, PAY_ALL_TO_ADDRESS_IN_B, PAY_PREVIOUS_TO_ADDRESS_IN_B),
			// all of which route here, plus PAY_ASSET_AMOUNT_TO_B which records into pendingAssetPayouts.
			// A future CIYAM library change that spends currentBalance by any other path would break this
			// clamp silently — no test asserts the invariant directly, only the payout outcomes.
			amount = Math.min(amount, this.getSpendableAssetBalance(assetId, state));
			amount = this.roundToAssetPrecision(amount, assetId);

			if (amount <= 0)
				return;
		}

		this.addPaymentToB(amount, assetId, state);
	}

	@Override
	public void messageAToB(MachineState state) {
		byte[] message = this.getA(state);
		Account recipient = getAccountFromB(state);
		if (recipient == null)
			throw new IllegalArgumentException("B register does not contain a valid account");

		long timestamp = this.getNextTransactionTimestamp();

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, NullAccount.PUBLIC_KEY, 0L, null);
		ATTransactionData atTransactionData = new ATTransactionData(baseTransactionData, this.atData.getATAddress(),
				recipient.getAddress(), message);
		AtTransaction atTransaction = new AtTransaction(this.repository, atTransactionData);

		// Add to our transactions
		this.transactions.add(atTransaction);
	}

	@Override
	public long addMinutesToTimestamp(Timestamp timestamp, long minutes, MachineState state) {
		int blockHeight = timestamp.blockHeight;

		// At least one block in the future
		long blocksToAdd = Math.max(minutes / this.ciyamAtSettings.minutesPerBlock, 1L);
		long targetBlockHeight = (long) blockHeight + blocksToAdd;
		if (targetBlockHeight > Integer.MAX_VALUE)
			throw new IllegalArgumentException("Timestamp block height exceeds integer range");

		return new Timestamp((int) targetBlockHeight, 0).longValue();
	}

	@Override
	public void onFinished(long finalBalance, MachineState state) {
		long configuredAssetId = this.atData.getAssetId();
		long configuredRefund = Math.max(0L, finalBalance - this.getPendingPayout(configuredAssetId));

		if (configuredRefund > 0)
			this.addPaymentToCreator(configuredRefund, configuredAssetId);

		if (!this.usesNativeWorkingAsset()) {
			long nativeRefund = Math.max(0L, this.getNativeFeeBalance() - this.calcFinalFees(state) - this.getPendingPayout(Asset.NATIVE));

			if (nativeRefund > 0)
				this.addPaymentToCreator(nativeRefund, Asset.NATIVE);
		}
	}

	@Override
	public void onFatalError(MachineState state, ExecutionException e) {
		LOGGER.error("AT " + this.atData.getATAddress() + " suffered fatal error: " + e.getMessage());
	}

	@Override
	public void platformSpecificPreExecuteCheck(int paramCount, boolean returnValueExpected, MachineState state, short rawFunctionCode)
			throws IllegalFunctionCodeException {
		ChainFunctionCode chainFunctionCode = ChainFunctionCode.valueOf(rawFunctionCode);

		if (chainFunctionCode == null)
			throw new IllegalFunctionCodeException("Unknown chain function code 0x" + String.format("%04x", rawFunctionCode) + " encountered");

		chainFunctionCode.preExecuteCheck(paramCount, returnValueExpected, rawFunctionCode);
	}

	@Override
	public void platformSpecificPostCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
		ChainFunctionCode chainFunctionCode = ChainFunctionCode.valueOf(rawFunctionCode);

		if (chainFunctionCode == null)
			throw new IllegalFunctionCodeException("Unknown chain function code 0x" + String.format("%04x", rawFunctionCode) + " encountered");

		chainFunctionCode.execute(functionData, state, rawFunctionCode);
	}

	// Chain-specific asset helpers

	public long getConfiguredAssetId() {
		return this.atData.getAssetId();
	}

	public long getAssetBalance(long assetId, MachineState state) {
		try {
			if (!this.repository.getAssetRepository().assetExists(assetId))
				return -1L;

			return this.getSpendableAssetBalance(assetId, state);
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch asset details?", e);
		}
	}

	public long getAssetIdFromTransactionInA(MachineState state) {
		TransactionData transactionData = this.getTransactionFromA(state);

		switch (transactionData.getType()) {
			case PAYMENT:
				return Asset.NATIVE;

			case TRANSFER_ASSET:
				return ((TransferAssetTransactionData) transactionData).getAssetId();

			case MULTI_PAYMENT:
				MultiPaymentSummary multiPaymentSummary = this.summarizeMultiPaymentToAt((MultiPaymentTransactionData) transactionData);

				if (multiPaymentSummary.hasSingleAsset())
					return multiPaymentSummary.assetId;

				return -1L;

			case MESSAGE:
				Long messageAssetId = ((MessageTransactionData) transactionData).getAssetId();

				if (((MessageTransactionData) transactionData).getAmount() != 0L && messageAssetId != null)
					return messageAssetId;

				return -1L;

			case AT:
				if (((ATTransactionData) transactionData).getAmount() != null)
					return ((ATTransactionData) transactionData).getAssetId();

				return -1L;

			default:
				return -1L;
		}
	}

	public long getAmountFromTransactionInAForAsset(long assetId, MachineState state) {
		try {
			if (!this.repository.getAssetRepository().assetExists(assetId))
				return -1L;
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch asset details?", e);
		}

		TransactionData transactionData = this.getTransactionFromA(state);

		switch (transactionData.getType()) {
			case PAYMENT:
				return assetId == Asset.NATIVE ? ((PaymentTransactionData) transactionData).getAmount() : 0L;

			case TRANSFER_ASSET:
				TransferAssetTransactionData transferAssetTransactionData = (TransferAssetTransactionData) transactionData;
				return transferAssetTransactionData.getAssetId() == assetId ? transferAssetTransactionData.getAmount() : 0L;

			case MULTI_PAYMENT:
				long amount = 0L;
				String atAddress = this.atData.getATAddress();

				for (PaymentData paymentData : ((MultiPaymentTransactionData) transactionData).getPayments())
					if (atAddress.equals(paymentData.getRecipient()) && paymentData.getAssetId() == assetId)
						amount += paymentData.getAmount();

				return amount;

			case MESSAGE:
				MessageTransactionData messageTransactionData = (MessageTransactionData) transactionData;
				Long messageAssetId = messageTransactionData.getAssetId();

				if (messageTransactionData.getAmount() != 0L && messageAssetId != null && messageAssetId == assetId)
					return messageTransactionData.getAmount();

				return 0L;

			case AT:
				ATTransactionData atTransactionData = (ATTransactionData) transactionData;

				if (atTransactionData.getAmount() != null && atTransactionData.getAssetId() != null && atTransactionData.getAssetId() == assetId)
					return atTransactionData.getAmount();

				return 0L;

			default:
				return -1L;
		}
	}

	public long getPaymentCountFromTransactionInA(MachineState state) {
		TransactionData transactionData = this.getTransactionFromA(state);

		switch (transactionData.getType()) {
			case PAYMENT:
			case TRANSFER_ASSET:
				return 1L;

			case MULTI_PAYMENT:
				long paymentCount = 0L;
				String atAddress = this.atData.getATAddress();

				for (PaymentData paymentData : ((MultiPaymentTransactionData) transactionData).getPayments())
					if (atAddress.equals(paymentData.getRecipient()))
						++paymentCount;

				return paymentCount;

			case MESSAGE:
				MessageTransactionData messageTransactionData = (MessageTransactionData) transactionData;
				return messageTransactionData.getAmount() != 0L && messageTransactionData.getAssetId() != null ? 1L : 0L;

			case AT:
				ATTransactionData atTransactionData = (ATTransactionData) transactionData;
				return atTransactionData.getAmount() != null && atTransactionData.getAssetId() != null ? 1L : 0L;

			default:
				return 0L;
		}
	}

	public long payAssetAmountToB(long assetId, long requestedAmount, MachineState state) {
		if (requestedAmount < 0)
			return -1L;

		if (requestedAmount == 0)
			return 0L;

		try {
			AssetData assetData = this.repository.getAssetRepository().fromAssetId(assetId);
			if (assetData == null || assetData.isUnspendable())
				return -1L;

			if (!assetData.isDivisible() && requestedAmount % Amounts.MULTIPLIER != 0)
				return -1L;
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch asset details?", e);
		}

		long amount = Math.min(requestedAmount, this.getSpendableAssetBalance(assetId, state));

		// Clamping to the spendable balance can leave a fractional quantity of an indivisible asset,
		// which the check above cannot catch because it only sees the requested amount.
		if (this.isPayoutSolvencyEnforced())
			amount = this.roundToAssetPrecision(amount, assetId);

		if (amount <= 0)
			return 0L;

		this.addPaymentToB(amount, assetId, state);
		this.addPendingPayout(assetId, amount);

		return amount;
	}

	// Utility methods

	public long calcFinalFees(MachineState state) {
		return state.getSteps() * this.ciyamAtSettings.feePerStep;
	}

	/** Returns partial transaction signature, used to verify we're operating on the same transaction and not naively using block height & sequence. */
	public static byte[] partialSignature(byte[] fullSignature) {
		return Arrays.copyOfRange(fullSignature, 8, 32);
	}

	/** Verify transaction's partial signature matches A2 thru A4 */
	private void verifyTransaction(TransactionData transactionData, MachineState state) {
		// Compare end of transaction's signature against A2 thru A4
		byte[] sig = transactionData.getSignature();

		if (this.getA2(state) != BitTwiddling.longFromBEBytes(sig, 8) || this.getA3(state) != BitTwiddling.longFromBEBytes(sig, 16) || this.getA4(state) != BitTwiddling.longFromBEBytes(sig, 24))
			throw new IllegalStateException("Transaction signature in A no longer matches signature from repository");
	}

	/** Returns transaction data from repository using block height & sequence from A1, checking the transaction signatures match too */
	/* package */ TransactionData getTransactionFromA(MachineState state) {
		Timestamp timestamp = new Timestamp(this.getA1(state));

		try {
			TransactionData transactionData = this.repository.getTransactionRepository().fromHeightAndSequence(timestamp.blockHeight,
					timestamp.transactionSequence);

			if (transactionData == null)
				throw new RuntimeException("AT API unable to fetch transaction?");

			// Check transaction still matches the one from the repository
			verifyTransaction(transactionData, state);

			return transactionData;
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch transaction type?", e);
		}
	}

	/** Returns message data from transaction. */
	/*package*/ byte[] getMessageFromTransaction(TransactionData transactionData) {
		switch (transactionData.getType()) {
			case MESSAGE:
				return ((MessageTransactionData) transactionData).getData();

			case AT:
				return ((ATTransactionData) transactionData).getMessage();

			default:
				return null;
		}
	}

	/*package*/ void sleepUntilMessageOrHeight(MachineState state, long txTimestamp, Long sleepUntilHeight) {
		this.setIsSleeping(state, true);

		this.atData.setSleepUntilMessageTimestamp(txTimestamp);

		if (sleepUntilHeight != null)
			this.setSleepUntilHeight(state, sleepUntilHeight.intValue());
	}

	/** Returns AT's account */
	/* package */ Account getATAccount() {
		return new Account(this.repository, this.atData.getATAddress());
	}

	/** Returns AT's creator's account */
	private PublicKeyAccount getCreator() {
		return new PublicKeyAccount(this.repository, this.atData.getCreatorPublicKey());
	}

	/** Returns the timestamp to use for next AT Transaction */
	private long getNextTransactionTimestamp() {
		/*
		 * AT transactions are generated locally and use hash-derived pseudo-signatures.
		 * Include their same-block generation index in the timestamp so otherwise
		 * identical generated payments still receive unique deterministic signatures.
		 */
		return this.blockTimestamp + this.transactions.size();
	}

	private boolean usesNativeWorkingAsset() {
		return this.atData.getAssetId() == Asset.NATIVE;
	}

	public boolean hasNativeFeeBalance() {
		return this.usesNativeWorkingAsset()
				|| this.ciyamAtSettings.feePerStep <= 0
				|| this.getNativeFeeBalance() >= this.ciyamAtSettings.feePerStep;
	}

	private long getNativeFeeBalance() {
		try {
			Account atAccount = this.getATAccount();
			return atAccount.getConfirmedBalance(Asset.NATIVE);
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch AT's native fee balance?", e);
		}
	}

	/** Whether this AT is executing at or beyond the payout-solvency feature trigger. */
	private boolean isPayoutSolvencyEnforced() {
		// During AT execution getCurrentBlockHeight() returns the parent block's height, since the block
		// being built is not yet persisted. The trigger is expressed in terms of the block the AT runs in,
		// so add 1 to match the deploy-time check in DeployAtTransaction and the other height gates.
		return this.getCurrentBlockHeight() + 1 >= BlockChain.getInstance().getAtPayoutSolvencyHeight();
	}

	/**
	 * Rounds {@code amount} down to a whole quantity for an indivisible asset, leaving divisible assets
	 * untouched. Amounts are raw 1e8-scaled units regardless of divisibility, so an indivisible asset can
	 * only legitimately move in multiples of {@link Amounts#MULTIPLIER}.
	 */
	private long roundToAssetPrecision(long amount, long assetId) {
		if (amount <= 0)
			return amount;

		AssetData assetData;
		try {
			assetData = this.repository.getAssetRepository().fromAssetId(assetId);
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch asset details?", e);
		}

		if (assetData == null || assetData.isDivisible())
			return amount;

		return amount - (amount % Amounts.MULTIPLIER);
	}

	private long getPendingPayout(long assetId) {
		return this.pendingAssetPayouts.getOrDefault(assetId, 0L);
	}

	private void addPendingPayout(long assetId, long amount) {
		this.pendingAssetPayouts.merge(assetId, amount, Long::sum);
	}

	private MultiPaymentSummary summarizeMultiPaymentToAt(MultiPaymentTransactionData multiPaymentTransactionData) {
		MultiPaymentSummary summary = new MultiPaymentSummary();
		String atAddress = this.atData.getATAddress();

		for (PaymentData paymentData : multiPaymentTransactionData.getPayments()) {
			if (!atAddress.equals(paymentData.getRecipient()))
				continue;

			if (!summary.hasPayment) {
				summary.hasPayment = true;
				summary.assetId = paymentData.getAssetId();
			} else if (summary.assetId != paymentData.getAssetId()) {
				summary.hasMixedAssets = true;
			}

			summary.amount += paymentData.getAmount();
		}

		return summary;
	}

	private long getSpendableAssetBalance(long assetId, MachineState state) {
		long balance;

		if (assetId == this.atData.getAssetId()) {
			balance = state.getCurrentBalance();
		} else {
			try {
				Account atAccount = this.getATAccount();
				balance = atAccount.getConfirmedBalance(assetId);
			} catch (DataException e) {
				throw new RuntimeException("AT API unable to fetch AT asset balance?", e);
			}

			if (!this.usesNativeWorkingAsset() && assetId == Asset.NATIVE)
				balance -= this.calcMaxRoundFees();
		}

		balance -= this.getPendingPayout(assetId);

		return Math.max(0L, balance);
	}

	private long calcMaxRoundFees() {
		return this.ciyamAtSettings.maxStepsPerRound * this.ciyamAtSettings.feePerStep;
	}

	private void addPaymentToB(long amount, long assetId, MachineState state) {
		Account recipient = getAccountFromB(state);
		if (recipient == null)
			throw new IllegalArgumentException("B register does not contain a valid account");

		this.addPayment(recipient.getAddress(), amount, assetId);
	}

	private void addPaymentToCreator(long amount, long assetId) {
		Account creator = this.getCreator();
		this.addPayment(creator.getAddress(), amount, assetId);
	}

	private void addPayment(String recipient, long amount, long assetId) {
		// Last line of defence: every AT-generated payment funnels through here, and AT transactions are
		// not validated by the block pipeline, so an invalid amount would reach the repository unchecked.
		if (this.isPayoutSolvencyEnforced()) {
			amount = this.roundToAssetPrecision(amount, assetId);

			if (amount <= 0)
				return;
		}

		long timestamp = this.getNextTransactionTimestamp();

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, NullAccount.PUBLIC_KEY, 0L, null);
		ATTransactionData atTransactionData = new ATTransactionData(baseTransactionData, this.atData.getATAddress(),
				recipient, amount, assetId);
		AtTransaction atTransaction = new AtTransaction(this.repository, atTransactionData);

		// Add to our transactions
		this.transactions.add(atTransaction);
	}

	/**
	 * Returns Account (possibly PublicKeyAccount) based on value in B.
	 * <p>
	 * If first byte in B starts with either address version bytes,<br>
	 * and bytes 26 to 32 are zero, then use as an address, but only if valid.
	 * <p>
	 * Otherwise, assume B is a public key.
	 * <p>
	 * Returns null if B is neither a valid address nor a valid public key.
	 */
	/*package*/ Account getAccountFromB(MachineState state) {
		byte[] bBytes = this.getB(state);

		if ((bBytes[0] == Crypto.ADDRESS_VERSION || bBytes[0] == Crypto.AT_ADDRESS_VERSION)
				&& Arrays.mismatch(bBytes, Account.ADDRESS_LENGTH, 32, ADDRESS_PADDING, 0, ADDRESS_PADDING.length) == -1) {
			// Extract only the bytes containing address
			byte[] addressBytes = Arrays.copyOf(bBytes, Account.ADDRESS_LENGTH);
			// If address (in byte form) is valid...
			if (Crypto.isValidAddress(addressBytes))
				// ...then return an Account using address (converted to Base58
				return new Account(this.repository, Base58.encode(addressBytes));
		}

		try {
			return new PublicKeyAccount(this.repository, bBytes);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/* Convenience methods to allow ChainFunctionCode package-visibility access to A/B-get/set methods. */

	protected byte[] getB(MachineState state) {
		return super.getB(state);
	}

	protected void setB(MachineState state, byte[] bBytes) {
		super.setB(state, bBytes);
	}

	@Override
	protected void zeroB(MachineState state) {
		super.zeroB(state);
	}

	private static class MultiPaymentSummary {
		private boolean hasPayment;
		private boolean hasMixedAssets;
		private long assetId;
		private long amount;

		private boolean hasSingleAsset() {
			return this.hasPayment && !this.hasMixedAssets;
		}
	}

}
