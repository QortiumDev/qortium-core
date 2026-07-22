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
import org.qortium.data.account.AccountBalanceData;
import org.qortium.data.asset.AssetData;
import org.qortium.data.at.ATData;
import org.qortium.data.block.BlockData;
import org.qortium.data.block.BlockSummaryData;
import org.qortium.data.transaction.*;
import org.qortium.group.Group;
import org.qortium.repository.AccountRepository;
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
	private final int blockHeight;
	private long blockTimestamp;
	private final ATMapExecutionContext mapContext;
	private final CiyamAtSettings ciyamAtSettings;

	/** List of generated AT transactions */
	List<AtTransaction> transactions;

	/** Generated platform-function payouts not tracked by CIYAM's single current-balance field. */
	private final Map<Long, Long> pendingAssetPayouts;

	// Constructors

	public ChainATAPI(Repository repository, ATData atData, long blockTimestamp) {
		this(repository, atData, getNextBlockHeight(repository), blockTimestamp, null);
	}

	public ChainATAPI(Repository repository, ATData atData, int blockHeight, long blockTimestamp,
			ATMapExecutionContext mapContext) {
		this.repository = repository;
		this.atData = atData;
		this.blockHeight = blockHeight;
		this.transactions = new ArrayList<>();
		this.pendingAssetPayouts = new HashMap<>();
		this.blockTimestamp = blockTimestamp;
		this.mapContext = mapContext;

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
	public int getOpCodeSteps(OpCode opcode, short rawFunctionCode, MachineState state) {
		int ordinarySteps = this.getOpCodeSteps(opcode);

		// Hashing built-ins (MD5/RMD160/SHA256/HASH160 families) are priced up once the trigger is
		// active. The pinned AT jar routes every external-function opcode that carries a raw function
		// code — including these hash functions — through this overload before charging steps, so this
		// is the Core-side hook. Below the trigger they keep the flat per-function cost above.
		if (this.isHashingStepCostActive() && isHashingFunctionCode(rawFunctionCode))
			return this.ciyamAtSettings.hashingStepCost;

		if (!this.isMapStorageActive() || opcode != OpCode.EXT_FUN
				|| rawFunctionCode != ChainFunctionCode.SET_MAP_VALUE_KEYS_IN_A.value)
			return ordinarySteps;

		try {
			int maxEntries = BlockChain.getInstance().getMaxMapEntriesPerAt(this.repository, this.blockHeight);
			if (this.mapContext.wouldCreateEntry(this.atData.getATAddress(), this.getA1(state), this.getA2(state),
					this.getA4(state), maxEntries))
				return this.ciyamAtSettings.mapEntryStepCost;

			return ordinarySteps;
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to price persistent map write", e);
		}
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
				MultiPaymentSummary multiPaymentSummary;
				try {
					multiPaymentSummary = this.summarizeMultiPaymentToAt((MultiPaymentTransactionData) transactionData);
				} catch (ExecutionException e) {
					// Post-trigger checked aggregation overflowed. This stock CIYAM API override cannot
					// declare a checked throw, so the unrepresentable total deterministically becomes the
					// same "no single amount" sentinel already returned for mixed-asset multi-payments.
					// Pre-trigger, summarizing never throws (it wraps, byte-for-byte as before).
					return 0xffffffffffffffffL;
				}

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
	public long payAmountToB(long amount, MachineState state) {
		long assetId = this.atData.getAssetId();

		if (this.isPayoutSolvencyEnforced()) {
			// The VM asks us to pay the amount it computed from its own current balance, which does not
			// account for payouts already made this round via PAY_ASSET_AMOUNT_TO_B. Clamp again against
			// what is genuinely still spendable, and round to a whole quantity for an indivisible asset.
			//
			// We return the amount actually emitted, and the VM debits its machine balance by exactly that
			// (see the stock CIYAM pay opcodes in FunctionCode), so the machine balance never diverges from
			// the AT's true remaining balance: a clamped or rounded payout leaves the difference in the AT
			// for onFinished() to refund, and a negative AT-supplied amount emits nothing and debits nothing
			// rather than inflating the balance. Because the machine balance stays exact, every downstream
			// read of it — this clamp on a later payout, the step-fee gate, previousBalance, the finished
			// refund — sees the truth without any separate reconciliation.
			amount = Math.min(amount, this.getSpendableAssetBalance(assetId, state));
			amount = this.roundToAssetPrecision(amount, assetId);

			if (amount <= 0)
				return 0L;
		}

		this.addPaymentToB(amount, assetId, state);

		return amount;
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
		// finalBalance is the VM's machine balance, which payAmountToB keeps exact by debiting only what was
		// really emitted, so whatever is left after subtracting the pending platform payouts is the refund.
		long configuredRefund = Math.max(0L, finalBalance - this.getPendingPayout(configuredAssetId));

		if (configuredRefund > 0)
			this.addPaymentToCreator(configuredRefund, configuredAssetId);

		if (!this.usesNativeWorkingAsset()) {
			long nativeRefund = Math.max(0L, this.getNativeFeeBalance() - this.calcFinalFees(state) - this.getPendingPayout(Asset.NATIVE));

			if (nativeRefund > 0)
				this.addPaymentToCreator(nativeRefund, Asset.NATIVE);
		}

		// From the sweep trigger, also return every OTHER asset the AT still holds with a positive
		// spendable balance to the creator, so a third asset an AT received or was left holding can
		// never be stranded forever. The configured working asset (above, from the exact machine
		// balance) and the native fee balance (above, net of final fees) are already handled, so they
		// are skipped here. Ordering is deterministic (ascending assetId). Indivisible assets move as
		// whole raw balances, which they always are on-chain.
		if (this.blockHeight >= BlockChain.getInstance().getAtSweepAssetsOnFinishHeight())
			this.sweepRemainingAssetsToCreator(configuredAssetId, state);
	}

	/** Sweeps every non-configured, non-native asset with a positive spendable balance to the creator, ascending assetId. */
	private void sweepRemainingAssetsToCreator(long configuredAssetId, MachineState state) {
		List<AccountBalanceData> balances;
		try {
			balances = this.repository.getAccountRepository().getAssetBalances(
					java.util.Collections.singletonList(this.atData.getATAddress()), null,
					AccountRepository.BalanceOrdering.ACCOUNT_ASSET, true, null, null, false);
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to enumerate AT asset balances for finish sweep?", e);
		}

		for (AccountBalanceData balanceData : balances) {
			long assetId = balanceData.getAssetId();

			// Configured working asset and native fee balance are refunded above with their exact,
			// fee-aware spendable amounts; do not double-pay them here.
			if (assetId == configuredAssetId || assetId == Asset.NATIVE)
				continue;

			long spendable = this.getSpendableAssetBalance(assetId, state);
			if (spendable > 0)
				this.addPaymentToCreator(spendable, assetId);
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

		if (chainFunctionCode.isMapFunction() && !this.isMapStorageActive())
			throw new IllegalFunctionCodeException("AT map storage is not active at this block height");

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

	public long getMapValue(MachineState state) {
		try {
			String targetAtAddress = this.resolveMapAddressFromB(state);
			return targetAtAddress == null ? 0L
					: this.mapContext.getValue(targetAtAddress, this.getA1(state), this.getA2(state));
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to read persistent map value", e);
		}
	}

	public void setMapValue(MachineState state) {
		try {
			int maxEntries = BlockChain.getInstance().getMaxMapEntriesPerAt(this.repository, this.blockHeight);
			this.mapContext.setValue(this.atData.getATAddress(), this.getA1(state), this.getA2(state),
					this.getA4(state), maxEntries);
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to write persistent map value", e);
		}
	}

	private boolean isMapStorageActive() {
		return this.mapContext != null && this.blockHeight >= BlockChain.getInstance().getAtMapStorageHeight();
	}

	/** Resolves only the explicit AT-address encoding accepted by map reads; zero means self. */
	private String resolveMapAddressFromB(MachineState state) {
		byte[] bBytes = this.getB(state);
		boolean allZero = true;
		for (byte value : bBytes) {
			if (value != 0) {
				allZero = false;
				break;
			}
		}

		if (allZero)
			return this.atData.getATAddress();

		if (bBytes[0] != Crypto.AT_ADDRESS_VERSION
				|| Arrays.mismatch(bBytes, Account.ADDRESS_LENGTH, bBytes.length,
						ADDRESS_PADDING, 0, ADDRESS_PADDING.length) != -1)
			return null;

		byte[] addressBytes = Arrays.copyOf(bBytes, Account.ADDRESS_LENGTH);
		return Crypto.isValidAddress(addressBytes) ? Base58.encode(addressBytes) : null;
	}

	private static int getNextBlockHeight(Repository repository) {
		try {
			return repository.getBlockRepository().getBlockchainHeight() + 1;
		} catch (DataException e) {
			throw new IllegalStateException("AT API unable to determine execution height", e);
		}
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

	public long getAssetIdFromTransactionInA(MachineState state) throws ExecutionException {
		TransactionData transactionData = this.getTransactionFromA(state);

		switch (transactionData.getType()) {
			case PAYMENT:
				return Asset.NATIVE;

			case TRANSFER_ASSET:
				return ((TransferAssetTransactionData) transactionData).getAssetId();

			case MULTI_PAYMENT:
				// Post-trigger, an overflowing (unrepresentable) aggregate total propagates as a
				// deterministic AT fatal error; pre-trigger, summarizing never throws (it wraps as before).
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

	public long getAmountFromTransactionInAForAsset(long assetId, MachineState state) throws ExecutionException {
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

				// Height-gated accumulation: pre-trigger this wraps byte-for-byte as before; from the
				// trigger, an overflowing per-asset multi-payment sum surfaces as a deterministic AT
				// fatal error instead of wrapping into a nonsensical consensus-valid amount.
				for (PaymentData paymentData : ((MultiPaymentTransactionData) transactionData).getPayments())
					if (atAddress.equals(paymentData.getRecipient()) && paymentData.getAssetId() == assetId)
						amount = this.addAtAmount(amount, paymentData.getAmount());

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

	public long payAssetAmountToB(long assetId, long requestedAmount, MachineState state) throws ExecutionException {
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

		if (this.isPayoutSolvencyEnforced() && assetId == this.atData.getAssetId()) {
			// This platform payout is of the AT's configured working asset, which the VM tracks in its
			// machine balance. Debit it there — exactly as the stock pay opcodes do — so the balance stays
			// exact for the step-fee gate, the serialized previous balance, and the finished refund. Not
			// doing so let a native-working-asset AT pay out its whole balance here yet keep spending against
			// an unchanged machine balance, running (and being charged for) more steps than it funded and
			// stalling the block. Pre-trigger, and for any other asset the machine balance does not track,
			// we instead record a pending payout, which onFinished() and the clamps reconcile as before.
			state.deductFromCurrentBalance(amount);
		} else {
			// Pre-trigger the pending-payout total wraps byte-for-byte as before (and never throws);
			// from the trigger, an overflowing total surfaces as a deterministic AT fatal error rather
			// than wrapping into a smaller apparent payout.
			try {
				this.addPendingPayout(assetId, amount);
			} catch (ArithmeticException e) {
				throw new ExecutionException("AT pending payout aggregation overflowed", e);
			}
		}

		return amount;
	}

	// Utility methods

	public long calcFinalFees(MachineState state) {
		return this.calcStepFees(state.getSteps());
	}

	/**
	 * Height-gated step-fee multiplication. Pre-trigger this is byte-for-byte the historic (wrapping)
	 * product; from the trigger, an overflowing step-fee total fails deterministically (it feeds per-AT
	 * and per-block fee accounting that nodes cross-check) instead of wrapping.
	 */
	long calcStepFees(long steps) {
		if (this.isCheckedArithmeticActive())
			return Math.multiplyExact(steps, this.ciyamAtSettings.feePerStep);

		return steps * this.ciyamAtSettings.feePerStep;
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

	long getPendingPayout(long assetId) {
		return this.pendingAssetPayouts.getOrDefault(assetId, 0L);
	}

	void addPendingPayout(long assetId, long amount) {
		if (this.isCheckedArithmeticActive())
			// Math.addExact as the remapping function throws ArithmeticException on overflow instead of
			// wrapping; callers convert that into a deterministic AT fatal error.
			this.pendingAssetPayouts.merge(assetId, amount, Math::addExact);
		else
			// Pre-trigger: historic silently-wrapping accumulation, byte-for-byte.
			this.pendingAssetPayouts.merge(assetId, amount, Long::sum);
	}

	/**
	 * Checked addition of AT monetary values. Overflow surfaces as a deterministic {@link ExecutionException}
	 * (the AT's fatal-error path in the VM) instead of silently wrapping into a consensus-valid but
	 * nonsensical amount. Wrapped states ARE reachable on today's chain (pre-existing unchecked
	 * multi-payment validation), which is why every caller is height-gated via {@link #addAtAmount}.
	 */
	static long checkedAtSum(long runningTotal, long addend) throws ExecutionException {
		try {
			return Math.addExact(runningTotal, addend);
		} catch (ArithmeticException e) {
			throw new ExecutionException("AT monetary aggregation overflowed", e);
		}
	}

	/**
	 * Height-gated accumulation of AT monetary values. Below {@code atCheckedArithmeticHeight} this is
	 * byte-for-byte the historic silently-wrapping {@code +}, so every node keeps agreeing on states the
	 * pre-existing unchecked multi-payment validation can produce. From the trigger height, overflow
	 * surfaces as a deterministic {@link ExecutionException} — the AT's fatal-error path in the VM.
	 */
	long addAtAmount(long runningTotal, long addend) throws ExecutionException {
		if (this.isCheckedArithmeticActive())
			return checkedAtSum(runningTotal, addend);

		return runningTotal + addend;
	}

	/** Whether this execution height has reached the {@code atCheckedArithmeticHeight} feature trigger. */
	private boolean isCheckedArithmeticActive() {
		// Key the gate off the locally-derived block height (parent height + 1), exactly like
		// isPayoutSolvencyEnforced above, NOT the peer-supplied BlockData.height threaded in through
		// this.blockHeight during block validation. That height field is null in the signed block and is
		// filled separately from the network message, so it is attacker-influenced; selecting checked vs
		// wrapping arithmetic on it would let the same signed block fork nodes on a non-canonical height.
		// During AT execution getCurrentBlockHeight() returns the parent block's height (the block being
		// built is not yet persisted), so + 1 gives this block's true, consensus-derived height.
		return this.getCurrentBlockHeight() + 1 >= BlockChain.getInstance().getAtCheckedArithmeticHeight();
	}

	private boolean isHashingStepCostActive() {
		return this.blockHeight >= BlockChain.getInstance().getAtHashingStepCostHeight();
	}

	/** Whether a raw external-function code is one of the hashing built-ins (0x0200-0x0207). */
	private static boolean isHashingFunctionCode(short rawFunctionCode) {
		return rawFunctionCode >= (short) 0x0200 && rawFunctionCode <= (short) 0x0207;
	}

	MultiPaymentSummary summarizeMultiPaymentToAt(MultiPaymentTransactionData multiPaymentTransactionData) throws ExecutionException {
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

			// Height-gated accumulation: pre-trigger this wraps byte-for-byte as before (never throwing);
			// from the trigger, an overflowing summary fails deterministically as an ExecutionException.
			// Core-function-code callers propagate that to the AT's fatal-error path; the stock
			// getAmountFromTransactionInA override, which cannot declare a checked throw, converts it to
			// its existing "no single amount" sentinel.
			summary.amount = this.addAtAmount(summary.amount, paymentData.getAmount());
		}

		return summary;
	}

	private long getSpendableAssetBalance(long assetId, MachineState state) {
		long balance;

		if (assetId == this.atData.getAssetId()) {
			// payAmountToB keeps the machine balance exact (it debits only what was really emitted), so the
			// VM's current balance minus the pending platform payouts is the genuinely spendable balance.
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

	static class MultiPaymentSummary {
		boolean hasPayment;
		boolean hasMixedAssets;
		long assetId;
		long amount;

		boolean hasSingleAsset() {
			return this.hasPayment && !this.hasMixedAssets;
		}
	}

}
