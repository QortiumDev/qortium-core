package org.qortium.crosschain;

import com.google.common.hash.HashCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Network;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.Wallet;
import org.qortium.api.model.SimpleForeignTransaction;
import org.qortium.crypto.Crypto;
import org.qortium.settings.Settings;
import org.qortium.utils.Amounts;
import org.qortium.utils.BitTwiddling;
import org.qortium.utils.NTP;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Bitcoin-like (Bitcoin, Litecoin, etc.) support */
public abstract class Bitcoiny implements ForeignBlockchain {

	protected static final Logger LOGGER = LogManager.getLogger(Bitcoiny.class);

	public static final int HASH160_LENGTH = 20;
	private static final int TIMEOUT = 10;
	private static final int RETRIES = 3;

	protected final BitcoinyBlockchainProvider blockchainProvider;
	protected final Context bitcoinjContext;
	protected final String currencyCode;

	protected final NetworkParameters params;

	/** Cache recent transactions to speed up subsequent lookups */
	protected List<SimpleTransaction> transactionsCache;
	protected Long transactionsCacheTimestamp;
	protected String transactionsCacheXpub;
	protected static long TRANSACTIONS_CACHE_TIMEOUT = 2 * 60 * 1000L; // 2 minutes

	/** How many wallet keys to generate in each batch. */
	private static final int WALLET_KEY_LOOKAHEAD_INCREMENT = 3;

	/** Byte offset into raw block headers to block timestamp. */
	protected static final int TIMESTAMP_OFFSET = 4 + 32 + 32;
	private static final int BLOCK_HEADER_LENGTH = 80;

	protected Coin feePerKb;

	/**
	 * Blockchain Cache
	 *
	 * To store blockchain data and reduce redundant RPCs to the ElectrumX servers
	 */
	private final BlockchainCache blockchainCache = new BlockchainCache();

	/**
	 * Executor
	 *
	 * Executor service to manage all Electrum server access.
	 */
	private static ExecutorService EXECUTOR = Executors.newFixedThreadPool(Settings.getInstance().getElectrumThreadCount());

	// Constructors and instance

	protected Bitcoiny(BitcoinyBlockchainProvider blockchainProvider, Context bitcoinjContext, NetworkParameters params, String currencyCode, Coin feePerKb) {
		this.blockchainProvider = blockchainProvider;
		this.bitcoinjContext = bitcoinjContext;
		this.currencyCode = currencyCode;
		this.feePerKb = feePerKb;
		this.params = params;
	}

	// Getters & setters
	public String getPaymentProtocolId() {
		return this.params.getPaymentProtocolId();
	}

	public Block getGenesisBlock() {
		return this.params.getGenesisBlock();
	}

	public BitcoinyBlockchainProvider getBlockchainProvider() {
		return this.blockchainProvider;
	}

	public Context getBitcoinjContext() {
		return this.bitcoinjContext;
	}

	@Override
	public String getCurrencyCode() {
		return this.currencyCode;
	}

	public NetworkParameters getNetworkParameters() {
		return this.params;
	}

	public Coin getMinNonDustOutput() {
		return StaticBitcoinyParams.getMinNonDustOutput(this.params);
	}

	// Interface obligations

	@Override
	public boolean isValidAddress(String address) {
		try {
			BitcoinyAddress bitcoinyAddress = BitcoinyAddress.fromString(this.params, address);

			return bitcoinyAddress.getType() == BitcoinyAddress.Type.P2PKH
					|| bitcoinyAddress.getType() == BitcoinyAddress.Type.P2SH
					|| bitcoinyAddress.getType() == BitcoinyAddress.Type.P2WPKH;
		} catch (IllegalArgumentException e) {
			LOGGER.error(String.format("Unrecognised address format: %s", address));
			return false;
		}
	}

	@Override
	public boolean isValidWalletKey(String walletKey) {
		return this.isValidDeterministicKey(walletKey);
	}

	// Actual useful methods for use by other classes

	public String format(Coin amount) {
		return this.format(amount.value);
	}

	public String format(long amount) {
		return Amounts.prettyAmount(amount) + " " + this.currencyCode;
	}

	public boolean isValidDeterministicKey(String key58) {
		try {
			BitcoinyDeterministicKey.fromBase58(this.params, key58);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	public String normalizeAddress(String address) {
		return address;
	}

	/** Returns P2PKH address using passed public key hash. */
	public String pkhToAddress(byte[] publicKeyHash) {
		return BitcoinyAddress.fromPubKeyHash(this.params, publicKeyHash).toString();
	}

	/** Returns P2SH address using passed redeem script. */
	public String deriveP2shAddress(byte[] redeemScriptBytes) {
		byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);
		return BitcoinyAddress.fromScriptHash(this.params, redeemScriptHash).toString();
	}

	/**
	 * Returns median timestamp from latest 11 blocks, in seconds.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public int getMedianBlockTime() throws ForeignBlockchainException {
		int height = this.blockchainProvider.getCurrentHeight();

		// Grab latest 11 blocks
		List<byte[]> blockHeaders = this.blockchainProvider.getRawBlockHeaders(height - 11, 11);
		if (blockHeaders.size() < 11)
			throw new ForeignBlockchainException("Not enough blocks to determine median block time");

		int timestampOffset = getBlockHeaderTimestampOffset();
		List<Integer> blockTimestamps = blockHeaders.stream().map(blockHeader -> BitTwiddling.intFromLEBytes(blockHeader, timestampOffset)).collect(Collectors.toList());

		// Descending order
		blockTimestamps.sort((a, b) -> Integer.compare(b, a));

		// Pick median
		return blockTimestamps.get(5);
	}

	/**
	 * Returns height from latest block.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public int getBlockchainHeight() throws ForeignBlockchainException {
		int height = this.blockchainProvider.getCurrentHeight();
		return height;
	}

	/** Returns fee per transaction KB. To be overridden for testnet/regtest. */
	public Coin getFeePerKb() {
		return this.feePerKb;
	}

	public void setFeePerKb(Coin feePerKb) {
		this.feePerKb = feePerKb;
	}

	/** Returns minimum order size in sats. To be overridden for coins that need to restrict order size. */
	public long getMinimumOrderAmount() {
		return 0L;
	}

	/**
	 * Returns fixed P2SH spending fee, in sats per 1000bytes, optionally for historic timestamp.
	 *
	 * @param timestamp optional milliseconds since epoch, or null for 'now'
	 * @return sats per 1000bytes
	 * @throws ForeignBlockchainException if something went wrong
	 */
	public abstract long getP2shFee(Long timestamp) throws ForeignBlockchainException;

	public int getBlockHeaderTimestampOffset() {
		return TIMESTAMP_OFFSET;
	}

	protected Integer getSpendTransactionVersion() {
		return null;
	}

	protected int getHtlcTransactionVersion() {
		return 2;
	}

	private void configureSpendTransaction(Transaction transaction) {
		Integer transactionVersion = getSpendTransactionVersion();
		if (transactionVersion != null)
			transaction.setVersion(transactionVersion);
	}

	public List<byte[]> splitRawBlockHeaders(byte[] rawBlockHeaders, int count) throws ForeignBlockchainException {
		List<byte[]> blockHeaders = new ArrayList<>((int) count);

		if (rawBlockHeaders.length == count * BLOCK_HEADER_LENGTH) {
			for (int i = 0; i < count; ++i)
				blockHeaders.add(Arrays.copyOfRange(rawBlockHeaders, i * BLOCK_HEADER_LENGTH, (i + 1) * BLOCK_HEADER_LENGTH));

			return blockHeaders;
		}

		if (rawBlockHeaders.length > count * BLOCK_HEADER_LENGTH) {
			int referenceVersion = BitTwiddling.intFromLEBytes(rawBlockHeaders, 0);
			for (int i = 0; i <= rawBlockHeaders.length - BLOCK_HEADER_LENGTH; ++i) {
				if (BitTwiddling.intFromLEBytes(rawBlockHeaders, i) == referenceVersion)
					blockHeaders.add(Arrays.copyOfRange(rawBlockHeaders, i, i + BLOCK_HEADER_LENGTH));
			}

			if (blockHeaders.size() == count)
				return blockHeaders;
		}

		throw new ForeignBlockchainException.NetworkException("Unexpected raw header contents in ElectrumX blockchain.block.headers RPC");
	}

	/**
	 * Returns confirmed balance, based on passed payment script.
	 * <p>
	 * @return confirmed balance, or zero if script unknown
	 * @throws ForeignBlockchainException if there was an error
	 */
	public long getConfirmedBalance(String base58Address) throws ForeignBlockchainException {
		if (hasSpendableOutputScriptFilter())
			return summingUnspentOutputs(base58Address);

		return this.blockchainProvider.getConfirmedBalance(addressToScriptPubKey(base58Address));
	}

	/**
	 * Returns list of unspent outputs pertaining to passed address.
	 * <p>
	 * @return list of unspent outputs, or empty list if address unknown
	 * @throws ForeignBlockchainException if there was an error.
	 */
	public List<UnspentOutput> getUnspentOutputs(String base58Address, boolean includeUnconfirmed) throws ForeignBlockchainException {

		List<UnspentOutput> unspentOutputs = this.blockchainProvider.getUnspentOutputs(addressToScriptPubKey(base58Address), includeUnconfirmed);

		List<Optional<UnspentOutput>> resolvedUnspentOutputs = new ArrayList<>();
		for (UnspentOutput unspentOutput : unspentOutputs) {
			resolvedUnspentOutputs.add(resolveUnspentOutput(unspentOutput));
		}

		long missingCount = resolvedUnspentOutputs.stream().filter(Optional::isEmpty).count();
		if (missingCount > 0) {
			throw new ForeignBlockchainException(String.format(
					"Failed to resolve %d/%d unspent outputs for %s",
					missingCount, unspentOutputs.size(), base58Address));
		}

		return resolvedUnspentOutputs.stream().filter(Optional::isPresent)
				.map(Optional::get)
				.filter(this::isSpendableOutput)
				.collect(Collectors.toList());
	}

	/**
	 * Resolve unspent output
	 *
	 * Resolve script metadata for an unspent output without exposing bitcoinj TransactionOutput to callers.
	 *
	 * @param unspentOutput the unspent output
	 *
	 * @return the resolved unspent output
	 */
	private Optional<UnspentOutput> resolveUnspentOutput(UnspentOutput unspentOutput)  {
		try {
			if (unspentOutput.script != null)
				return Optional.of(unspentOutput);

			List<BitcoinyTransaction.Output> outputs = this.getOutputs(unspentOutput.hash);
			if (unspentOutput.index < 0 || unspentOutput.index >= outputs.size()) {
				LOGGER.error("Output index {} out of range for transaction {} ({} outputs)",
						unspentOutput.index, HashCode.fromBytes(unspentOutput.hash), outputs.size());
				return Optional.empty();
			}

			BitcoinyTransaction.Output transactionOutput = outputs.get(unspentOutput.index);

			// Sanity-check provider UTXO metadata against raw tx decode so we fail early on inconsistencies.
			if (transactionOutput.value != unspentOutput.value) {
				LOGGER.error("UTXO value mismatch for {}:{} (provider={}, rawTx={})",
						HashCode.fromBytes(unspentOutput.hash), unspentOutput.index,
						unspentOutput.value, transactionOutput.value);
				return Optional.empty();
			}

			String outputAddress = unspentOutput.address;
			if (outputAddress == null && transactionOutput.addresses != null && !transactionOutput.addresses.isEmpty())
				outputAddress = transactionOutput.addresses.get(0);

			return Optional.of(new UnspentOutput(unspentOutput.hash, unspentOutput.index, unspentOutput.height,
					unspentOutput.value, HashCode.fromString(transactionOutput.scriptPubKey).asBytes(), outputAddress));
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
			return Optional.empty();
		}
	}

	/**
	 * Returns scriptPubKey for an unspent output.
	 * <p>
	 * Uses script data directly if available. Otherwise tries raw tx deserialization, and if that fails,
	 * falls back to provider transaction metadata (e.g. Electrum verbose transaction output script).
	 */
	private Script getScriptPubKey(UnspentOutput unspentOutput) throws ForeignBlockchainException {
		if (unspentOutput.script != null)
			return new Script(unspentOutput.script);

		try {
			List<BitcoinyTransaction.Output> transactionOutputs = this.getOutputs(unspentOutput.hash);
			if (unspentOutput.index < 0 || unspentOutput.index >= transactionOutputs.size()) {
				throw new ForeignBlockchainException(String.format("Output index %d out of range for transaction %s",
						unspentOutput.index, HashCode.fromBytes(unspentOutput.hash)));
			}

			String scriptPubKeyHex = transactionOutputs.get(unspentOutput.index).scriptPubKey;
			if (scriptPubKeyHex == null || scriptPubKeyHex.isEmpty()) {
				throw new ForeignBlockchainException(String.format("Missing scriptPubKey for output %d of transaction %s",
						unspentOutput.index, HashCode.fromBytes(unspentOutput.hash)));
			}

			return new Script(HashCode.fromString(scriptPubKeyHex).asBytes());
		} catch (ForeignBlockchainException | RuntimeException e) {
			String txHash = HashCode.fromBytes(unspentOutput.hash).toString();
			LOGGER.debug("Raw transaction decode failed for {}. Falling back to provider metadata: {}", txHash, e.getMessage());

			BitcoinyTransaction transaction = this.blockchainProvider.getTransaction(txHash);
			if (transaction.outputs == null || unspentOutput.index < 0 || unspentOutput.index >= transaction.outputs.size()) {
				throw new ForeignBlockchainException(String.format("Output index %d out of range for transaction %s",
						unspentOutput.index, txHash));
			}

			String scriptPubKeyHex = transaction.outputs.get(unspentOutput.index).scriptPubKey;
			if (scriptPubKeyHex == null || scriptPubKeyHex.isEmpty()) {
				throw new ForeignBlockchainException(String.format("Missing scriptPubKey for output %d of transaction %s",
						unspentOutput.index, txHash));
			}

			try {
				return new Script(HashCode.fromString(scriptPubKeyHex).asBytes());
			} catch (IllegalArgumentException e2) {
				throw new ForeignBlockchainException(String.format("Invalid scriptPubKey for output %d of transaction %s: %s",
						unspentOutput.index, txHash, e2.getMessage()));
			}
		}
	}

	/**
	 * Returns list of outputs pertaining to passed transaction hash.
	 * <p>
	 * @return list of outputs, or empty list if transaction unknown
	 * @throws ForeignBlockchainException if there was an error.
	 */
	public List<BitcoinyTransaction.Output> getOutputs(byte[] txHash) throws ForeignBlockchainException {
		Exception lastException = null;

		for (int retry = 0; retry <= RETRIES; retry++) {
			try {
				byte[] rawTransactionBytes = this.blockchainProvider.getRawTransaction(txHash);

				BitcoinyTransaction transaction = deserializeRawTransaction(HashCode.fromBytes(txHash).toString(), rawTransactionBytes);
				return transaction.outputs;
			} catch (ForeignBlockchainException | RuntimeException e) {
				lastException = e;
			}
		}

		String message = String.format("Unable to deserialize raw transaction %s: %s",
				HashCode.fromBytes(txHash),
				lastException == null ? "unknown error" : lastException.getMessage());
		throw new ForeignBlockchainException(message);
	}

	/**
	 * Returns transactions for passed script
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public List<TransactionHash> getAddressTransactions(byte[] scriptPubKey, boolean includeUnconfirmed) throws ForeignBlockchainException {
		int retries = 0;
		ForeignBlockchainException e2 = null;
		while (retries <= RETRIES) {
			try {
				return this.blockchainProvider.getAddressTransactions(scriptPubKey, includeUnconfirmed);
			} catch (ForeignBlockchainException e) {
				e2 = e;
				retries++;
			}
		}
		throw(e2);
	}

	/**
	 * Returns list of transaction hashes pertaining to passed address.
	 * <p>
	 * @return list of unspent outputs, or empty list if script unknown
	 * @throws ForeignBlockchainException if there was an error.
	 */
	public List<TransactionHash> getAddressTransactions(String base58Address, boolean includeUnconfirmed) throws ForeignBlockchainException {
		return this.blockchainProvider.getAddressTransactions(addressToScriptPubKey(base58Address), includeUnconfirmed);
	}

	/**
	 * Returns list of raw, confirmed transactions involving given address.
	 * <p>
	 * @throws ForeignBlockchainException if there was an error
	 */
	public List<byte[]> getAddressTransactions(String base58Address) throws ForeignBlockchainException {
		List<TransactionHash> transactionHashes = this.blockchainProvider.getAddressTransactions(addressToScriptPubKey(base58Address), false);

		List<byte[]> rawTransactions = new ArrayList<>();
		for (TransactionHash transactionInfo : transactionHashes) {
			byte[] rawTransaction = this.blockchainProvider.getRawTransaction(HashCode.fromString(transactionInfo.txHash).asBytes());
			rawTransactions.add(rawTransaction);
		}

		return rawTransactions;
	}

	/**
	 * Returns transaction info for passed transaction hash.
	 * <p>
	 * @throws ForeignBlockchainException.NotFoundException if transaction unknown
	 * @throws ForeignBlockchainException if error occurs
	 */
	public BitcoinyTransaction getTransaction(String txHash) throws ForeignBlockchainException {
		int retries = 0;
		ForeignBlockchainException e2 = null;
		while (retries <= RETRIES) {
			try {
				return this.blockchainProvider.getTransaction(txHash);
			} catch (ForeignBlockchainException e) {
				e2 = e;
				retries++;
			}
		}
		throw(e2);
	}

	/**
	 * Broadcasts raw transaction to network.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public void broadcastTransaction(Transaction transaction) throws ForeignBlockchainException {
		this.blockchainProvider.broadcastTransaction(transaction.bitcoinSerialize());
	}

	public void broadcastTransaction(BitcoinySignedTransaction transaction) throws ForeignBlockchainException {
		this.blockchainProvider.broadcastTransaction(transaction.getRawTransaction(), transaction.getTxHash());
	}

	public long getSpendFeePerByte(Long feePerByte) {
		return feePerByte != null ? feePerByte : Math.max(1L, getFeePerKb().value / 1000L);
	}

	public BitcoinySignedTransaction buildSpendTransaction(String xprv58, String recipient, long amount, Long feePerByte) {
		return LegacyTransactionBuilder.buildSpend(this, xprv58, recipient, amount, feePerByte);
	}

	public BitcoinySignedTransaction buildSpendTransaction(String xprv58, String recipient, long amount) {
		return buildSpendTransaction(xprv58, recipient, amount, null);
	}

	public BitcoinySignedTransaction buildSpendMultipleTransaction(String xprv58, Map<String, Long> amountByRecipient, Long feePerByte) {
		return LegacyTransactionBuilder.buildSpend(this, xprv58, amountByRecipient, feePerByte);
	}

	public BitcoinySignedTransaction buildSpendMaxTransaction(String xprv58, String recipient, Long feePerByte) {
		return LegacyTransactionBuilder.buildSpendMax(this, xprv58, recipient, feePerByte);
	}

	public BitcoinySpendPreview buildSpendPreview(String xprv58, String recipient, long amount, Long feePerByte) throws ForeignBlockchainException {
		long resolvedFeePerByte = getSpendFeePerByte(feePerByte);
		BitcoinySignedTransaction signedTransaction = buildSpendTransaction(xprv58, recipient, amount, resolvedFeePerByte);
		return buildSpendPreview(signedTransaction, amount, false, resolvedFeePerByte);
	}

	public BitcoinySpendPreview buildSpendMaxPreview(String xprv58, String recipient, Long feePerByte) throws ForeignBlockchainException {
		long resolvedFeePerByte = getSpendFeePerByte(feePerByte);
		BitcoinySignedTransaction signedTransaction = buildSpendMaxTransaction(xprv58, recipient, resolvedFeePerByte);
		return buildSpendPreview(signedTransaction, null, true, resolvedFeePerByte);
	}

	private BitcoinySpendPreview buildSpendPreview(BitcoinySignedTransaction signedTransaction, Long amount, boolean sendMax,
			long resolvedFeePerByte) throws ForeignBlockchainException {
		if (signedTransaction == null)
			return null;

		byte[] rawTransaction = signedTransaction.getRawTransaction();
		BitcoinyTransaction transaction = deserializeRawTransaction(signedTransaction.getTxHash(), rawTransaction);
		long inputAmount = signedTransaction.getInputAmount() != null
				? signedTransaction.getInputAmount()
				: resolveInputAmount(transaction);
		long outputAmount = signedTransaction.getOutputAmount() != null
				? signedTransaction.getOutputAmount()
				: transaction.totalAmount;
		long fee = inputAmount - outputAmount;

		if (fee < 0)
			throw new ForeignBlockchainException("Prepared transaction has negative fee");

		int inputCount = signedTransaction.getInputCount() != null ? signedTransaction.getInputCount() : transaction.inputs.size();
		int outputCount = signedTransaction.getOutputCount() != null ? signedTransaction.getOutputCount() : transaction.outputs.size();
		long previewAmount = amount != null ? amount : outputAmount;

		return new BitcoinySpendPreview(previewAmount, sendMax, resolvedFeePerByte, fee, inputAmount, outputAmount, transaction.size,
				inputCount, outputCount, signedTransaction.getTxHash(), rawTransaction);
	}

	public String broadcastRawTransaction(byte[] rawTransaction) throws ForeignBlockchainException {
		BitcoinyTransaction transaction = deserializeRawTransaction(rawTransaction);
		this.blockchainProvider.broadcastTransaction(rawTransaction, transaction.txHash);
		return transaction.txHash;
	}

	private long resolveInputAmount(BitcoinyTransaction transaction) throws ForeignBlockchainException {
		long inputAmount = 0L;

		for (BitcoinyTransaction.Input input : transaction.inputs) {
			List<BitcoinyTransaction.Output> outputs = getOutputs(HashCode.fromString(input.outputTxHash).asBytes());
			if (input.outputVout < 0 || input.outputVout >= outputs.size())
				throw new ForeignBlockchainException(String.format("Unable to resolve input %s:%d", input.outputTxHash, input.outputVout));

			inputAmount = Math.addExact(inputAmount, outputs.get(input.outputVout).value);
		}

		return inputAmount;
	}

	public BitcoinySignedTransaction buildHtlcRedeemTransaction(Coin redeemAmount, ECKey redeemKey,
			List<UnspentOutput> fundingOutputs, byte[] redeemScriptBytes, byte[] secret, byte[] receivingAccountInfo) throws ForeignBlockchainException {
		return BitcoinySignedTransaction.fromBitcoinj(BitcoinyHTLC.buildRedeemTransaction(this.params, redeemAmount, redeemKey,
				fundingOutputs, redeemScriptBytes, secret, receivingAccountInfo, getHtlcTransactionVersion()));
	}

	public BitcoinySignedTransaction buildHtlcRefundTransaction(Coin refundAmount, ECKey refundKey,
			List<UnspentOutput> fundingOutputs, byte[] redeemScriptBytes, long lockTime, byte[] receivingAccountInfo) throws ForeignBlockchainException {
		return BitcoinySignedTransaction.fromBitcoinj(BitcoinyHTLC.buildRefundTransaction(this.params, refundAmount, refundKey,
				fundingOutputs, redeemScriptBytes, lockTime, receivingAccountInfo, getHtlcTransactionVersion()));
	}

	public BitcoinyTransaction deserializeRawTransaction(byte[] rawTransaction) throws ForeignBlockchainException {
		return deserializeRawTransaction(null, rawTransaction);
	}

	public BitcoinyTransaction deserializeRawTransaction(String txHash, byte[] rawTransaction) throws ForeignBlockchainException {
		try {
			return BitcoinyRawTransactionParser.parse(txHash, rawTransaction);
		} catch (RuntimeException e) {
			throw new ForeignBlockchainException(String.format("Unable to deserialize raw transaction: %s", e.getMessage()));
		}
	}

	/**
	 * Returns bitcoinj transaction sending <tt>amount</tt> to <tt>recipient</tt>.
	 *
	 * @param xprv58 BIP32 private key
	 * @param recipient P2PKH address
	 * @param amount unscaled amount
	 * @param feePerByte unscaled fee per byte, or null to use default fees
	 * @return transaction, or null if insufficient funds
	 */
	public Transaction buildSpend(String xprv58, String recipient, long amount, Long feePerByte) {
		BitcoinySignedTransaction transaction = buildSpendTransaction(xprv58, recipient, amount, feePerByte);
		return transaction == null ? null : toBitcoinjTransaction(transaction);
	}

	/**
	 * Returns bitcoinj transaction sending the recipient's amount to each recipient given.
	 *
	 *
	 * @param xprv58 the private master key
	 * @param amountByRecipient each amount to send indexed by the recipient to send to
	 * @param feePerByte the satoshis per byte
	 *
	 * @return the completed transaction, ready to broadcast
	 */
	public Transaction buildSpendMultiple(String xprv58, Map<String, Long> amountByRecipient, Long feePerByte) {
		BitcoinySignedTransaction transaction = buildSpendMultipleTransaction(xprv58, amountByRecipient, feePerByte);
		return transaction == null ? null : toBitcoinjTransaction(transaction);
	}

	private Transaction toBitcoinjTransaction(BitcoinySignedTransaction transaction) {
		try {
			return Transaction.read(ByteBuffer.wrap(transaction.getRawTransaction()));
		} catch (ProtocolException e) {
			throw new IllegalStateException("Unable to parse built legacy transaction", e);
		}
	}

	/**
	 * Get Spending Candidate Addresses
	 *
	 * @param key58 public master key
	 * @return the addresses this instance will look at when building a spend
	 * @throws ForeignBlockchainException
	 */
	public List<String> getSpendingCandidateAddresses(String key58) throws ForeignBlockchainException {
		BitcoinyDeterministicKeyChain keyChain = BitcoinyDeterministicKeyChain.fromBase58(this.params, key58);
		List<BitcoinyDeterministicKey> spendingKeys = keyChain.getInitialLeafKeys(Bitcoiny.WALLET_KEY_LOOKAHEAD_INCREMENT);

		List<String> spendingCandidateAddresses
				= spendingKeys.stream()
					.map(spendingKey -> pkhToAddress(spendingKey.getPublicKeyHash()))
					.collect(Collectors.toList());

		return spendingCandidateAddresses;
	}

	/**
	 * Returns bitcoinj transaction sending <tt>amount</tt> to <tt>recipient</tt> using default fees.
	 *
	 * @param xprv58 BIP32 private key
	 * @param recipient P2PKH address
	 * @param amount unscaled amount
	 * @return transaction, or null if insufficient funds
	 */
	public Transaction buildSpend(String xprv58, String recipient, long amount) {
		return buildSpend(xprv58, recipient, amount, null);
	}

	/**
	 * Returns unspent foreign-chain balance given 'm' BIP32 key.
	 *
	 * @param key58 BIP32/HD extended private/public key
	 * @return unspent foreign-chain balance, or null if unable to determine balance
	 */
	public Long getWalletBalance(String key58) throws ForeignBlockchainException {
		Long balance = 0L;

		// Get all wallet addresses (via recursive gap-limit logic)
		Set<String> walletAddresses = this.getWalletAddressesWithExecutor(key58, EXECUTOR);

		try {
			List<Supplier<Optional<Long>>> suppliers = new ArrayList<>();

			for (String address : walletAddresses) {
				suppliers.add(() -> getUnspentValueFromAddress(address));
			}

			// Parallel fetch of unspent values per address
			balance += getUnspentValueFromSuppliers(suppliers, EXECUTOR, RETRIES);
		} catch (Exception e) {
			LOGGER.error("Unexpected error in getWalletBalance: {}", e.getMessage(), e);
			return null;
		}

		return balance;
	}

	private static long getUnspentValueFromSuppliers(
			List<Supplier<Optional<Long>>> suppliers,
			ExecutorService executor,
			int retries) throws ForeignBlockchainException, ExecutionException, InterruptedException {

		long totalValue = 0L;

		// for recursion if necessary
		List<Supplier<Optional<Long>>> suppliersToRetry = new ArrayList<>(suppliers.size());

		Map<Integer, Supplier<Optional<Long>>> supplierMap = new HashMap<>(suppliers.size());
		Map<Integer, Future<Optional<Long>>> futureMap = new HashMap<>(suppliers.size());

		int index = 0;

		for (Supplier<Optional<Long>> supplier : suppliers) {

			Future<Optional<Long>> future = executor.submit(() -> supplier.get());

			supplierMap.put(index, supplier);
			futureMap.put(index, future);

			index++;
		}

		final int count = index;

		for( index = 0; index < count; index++) {
			Future<Optional<Long>> future = futureMap.get(index);

			try {
				Optional<Long> value = future.get(TIMEOUT, TimeUnit.SECONDS);

				if (value.isPresent()) {
					totalValue += value.get();
				} else {
					suppliersToRetry.add(supplierMap.get(index));
				}
			} catch (TimeoutException e) {
				suppliersToRetry.add(supplierMap.get(index));
			}
		}

		for( Future<Optional<Long>> future: futureMap.values()) {
			future.cancel(true);
		}

		if( !suppliersToRetry.isEmpty() ) {

			if( retries > 0 ) {
				totalValue += getUnspentValueFromSuppliers(suppliersToRetry, executor, retries - 1);
			}
			else {
				throw new ForeignBlockchainException("can't get all address infos");
			}
		}

		return totalValue;
	}

	private Optional<Long> getUnspentValueFromAddress(String address) {
		try {
			return Optional.of(summingUnspentOutputs(address));
		} catch (Exception e) {
			LOGGER.warn("Failed to fetch unspent value for address {}: {}", address, e.getMessage());
			return Optional.empty();
		}
	}

public List<SimpleTransaction> getWalletTransactions(String key58) throws ForeignBlockchainException {
	try {
		// Serve from cache if valid
		if (Objects.equals(transactionsCacheXpub, key58)) {
			if (transactionsCache != null && transactionsCacheTimestamp != null) {
				Long now = NTP.getTime();
				boolean isCacheStale = (now != null && now - transactionsCacheTimestamp >= TRANSACTIONS_CACHE_TIMEOUT);
				if (!isCacheStale) {
					return transactionsCache;
				}
			}
		}

		BitcoinyDeterministicKeyChain keyChain = BitcoinyDeterministicKeyChain.fromBase58(this.params, key58);
		List<BitcoinyDeterministicKey> keys = keyChain.getInitialLeafKeys(Bitcoiny.WALLET_KEY_LOOKAHEAD_INCREMENT);

		// Use thread-safe list for futures
		List<Supplier<Optional<BitcoinyTransaction>>> suppliers = Collections.synchronizedList(new ArrayList<>());

		// Fetch keys with transaction checks
		Set<String> keySet =  processKeysWithTransactionFuturesIterative(EXECUTOR, keys, keyChain, suppliers);

		Set<BitcoinyTransaction> walletTransactions = getBitcoinyTransactionsFromSuppliers(suppliers, EXECUTOR, RETRIES);

		Comparator<SimpleTransaction> newestTimestampFirstComparator =
			Comparator.comparingLong(SimpleTransaction::getTimestamp).reversed();

		// Convert to simplified form
		List<SimpleTransaction> simpleTransactions = walletTransactions.parallelStream()
			.map(t -> convertToSimpleTransaction(t, keySet))
			.collect(Collectors.toList());

		// Unconfirmed transactions (null timestamp)
		transactionsCache = simpleTransactions.stream()
			.filter(t -> t.getTimestamp() == null)
			.collect(Collectors.toList());

		// Add confirmed transactions sorted by timestamp
		transactionsCache.addAll(
			simpleTransactions.stream()
				.filter(t -> t.getTimestamp() != null)
				.sorted(newestTimestampFirstComparator)
				.collect(Collectors.toList())
		);

		// Update cache metadata
		transactionsCacheTimestamp = NTP.getTime();
		transactionsCacheXpub = key58;

		return transactionsCache;
	} catch (ForeignBlockchainException e) {
		LOGGER.error(e.getMessage(), e);
		throw e;
	} catch (ExecutionException | InterruptedException e) {
		LOGGER.error(e.getMessage(), e);
		throw new ForeignBlockchainException("Execution or interruption exception when calling foreign chain");
	} catch (Exception e) {
		LOGGER.error(e.getMessage(), e);
		return new ArrayList<>(0);
	}
}

	/**
	 * Get Bitcoiny Transactions From Suppliers
	 *
	 * @param suppliers the suppliers, when the suppliers return empty, that provokes a retry
	 * @param executor the executor
	 * @param retries the number of retries to allow
	 * @return
	 * @throws ExecutionException
	 * @throws InterruptedException
	 * @throws ForeignBlockchainException
	 */
	private Set<BitcoinyTransaction> getBitcoinyTransactionsFromSuppliers(
			List<Supplier<Optional<BitcoinyTransaction>>> suppliers,
			ExecutorService executor,
			int retries) throws ExecutionException, InterruptedException, ForeignBlockchainException {

		// for recursion if necessary
		List<Supplier<Optional<BitcoinyTransaction>>> suppliersToRetry = new ArrayList<>(suppliers.size());

		Map<Integer, Supplier<Optional<BitcoinyTransaction>>> supplierMap = new HashMap<>(suppliers.size());
		Map<Integer, Future<Optional<BitcoinyTransaction>>> futureMap = new HashMap<>(suppliers.size());

		int index = 0;

		for (Supplier<Optional<BitcoinyTransaction>> supplier : suppliers) {

			Future<Optional<BitcoinyTransaction>> future = executor.submit(() -> supplier.get());

			supplierMap.put(index, supplier);
			futureMap.put(index, future);

			index++;
		}

		final int count = index;

		// Collect transactions from futures
		Set<BitcoinyTransaction> walletTransactions = Collections.synchronizedSet(new HashSet<>());

		for( index = 0; index < count; index++) {
			Future<Optional<BitcoinyTransaction>> future = futureMap.get(index);

			try {
				Optional<BitcoinyTransaction> transactionOptional = future.get(TIMEOUT, TimeUnit.SECONDS);

				if (transactionOptional.isPresent()) {
					BitcoinyTransaction transaction = transactionOptional.get();
					walletTransactions.add(transaction);

					// Cache confirmed transactions
					if (transaction.timestamp != null) {
						this.blockchainCache.addTransactionByHash(transaction.txHash, transaction);
					}
				}
				else {
					suppliersToRetry.add(supplierMap.get(index));
				}
			} catch (TimeoutException e) {
				suppliersToRetry.add(supplierMap.get(index));
			}
		}

		for( Future<Optional<BitcoinyTransaction>> future: futureMap.values()) {
			future.cancel(true);
		}

		if( !suppliersToRetry.isEmpty() ) {

			if( retries > 0 ) {
				walletTransactions.addAll(getBitcoinyTransactionsFromSuppliers(suppliersToRetry, executor, retries - 1));
			}
			else {
				throw new ForeignBlockchainException("can't get all wallet transactions");
			}
		}

		return walletTransactions;
	}

	private Set<String> processKeysWithTransactionFuturesIterative(
	ExecutorService executor,
	List<BitcoinyDeterministicKey> initialKeys,
	BitcoinyDeterministicKeyChain keyChain,
	List<Supplier<Optional<BitcoinyTransaction>>> futures
) throws ForeignBlockchainException {

	Set<String> keySet = new HashSet<>();
	int unusedCounter = 0;

	List<BitcoinyDeterministicKey> keysToProcess = new ArrayList<>(initialKeys);
	int processedKeyCount = 0;

	while (!keysToProcess.isEmpty()) {
		List<Future<Boolean>> transactionChecks = new ArrayList<>(keysToProcess.size());
		boolean foundTransaction = false;

		for (BitcoinyDeterministicKey dKey : keysToProcess) {
			String address = pkhToAddress(dKey.getPublicKeyHash());
			keySet.add(address);

			// Schedule transaction check
			transactionChecks.add(executor.submit(() -> getTransactions(BitcoinyScript.p2pkhScript(dKey.getPublicKeyHash()), futures, executor)));
		}
		processedKeyCount += keysToProcess.size();

		// Wait for transaction check results
		for (Future<Boolean> check : transactionChecks) {
			try {
				if (check.get()) {
					foundTransaction = true;
				}
			} catch (Exception e) {
				LOGGER.warn("Failed to check transaction for key", e);
			}
		}

		if (foundTransaction) {
			unusedCounter = 0;
		} else {
			unusedCounter += WALLET_KEY_LOOKAHEAD_INCREMENT;
		}

		if (unusedCounter >= Settings.getInstance().getGapLimit()) {
			LOGGER.debug("Reached gap limit of " + unusedCounter + ", stopping key discovery.");
			break;
		}

		// Generate next batch of keys
		keysToProcess = generateMoreKeys(keyChain, processedKeyCount);
	}

	return keySet;
}


	/**
	 * Get Bitcoiny Transaction
	 *
	 * Get the transaction object stored in memory if available
	 *
	 * @param transactionHash the hash identifying the transaction
	 *
	 * @return the transaction is available, otherwise empty
	 */
	private Optional<BitcoinyTransaction> getBitcoinyTransaction(TransactionHash transactionHash) {
		try {
			BitcoinyTransaction transaction = getTransaction(transactionHash.txHash);
			return Optional.of(transaction);
		} catch (ForeignBlockchainException e) {
			LOGGER.error(e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Get Wallet Infos
	 *
	 * Get information for each address in the wallet.
	 *
	 * @param key58 the master key to determine key generation for the addresses
	 *
	 * @return the info for each address
	 *
	 * @throws ForeignBlockchainException
	 */
	public List<AddressInfo> getWalletAddressInfos(String key58) throws ForeignBlockchainException {

		// generate keys asynchronously
		Set<BitcoinyDeterministicKey> walletKeys = getWalletKeysWithExecutor(key58, EXECUTOR);

		// collect all address info build tasks
		List<Supplier<Optional<AddressInfo>>> suppliers = new ArrayList<>(walletKeys.size());

		// build info for each key, one address per key
		for(BitcoinyDeterministicKey key : walletKeys) {
			suppliers.add(() -> buildAddressInfo(key));
		}

		List<AddressInfo> infos = getAddressInfosFromSuppliers(suppliers, EXECUTOR, RETRIES);

		return infos.stream()
				.sorted(new PathComparator(1))
				.collect(Collectors.toList());
	}

	/**
	 * Get Address Infos From Suppliers
	 *
	 * @param suppliers the suppliers, if a supplier returns empty then a retry is provoked
	 * @param executor the executor
	 * @param retries the number of retries allowed
	 *
	 * @return the address infos
	 *
	 * @throws ForeignBlockchainException
	 */
	private static List<AddressInfo> getAddressInfosFromSuppliers(
			List<Supplier<Optional<AddressInfo>>> suppliers,
			ExecutorService executor,
			int retries) throws ForeignBlockchainException {

		try {
			// return list
			List<AddressInfo> infos = new ArrayList<>();

			// for recursion if necessary
			List<Supplier<Optional<AddressInfo>>> suppliersToRetry = new ArrayList<>(suppliers.size());

			Map<Integer, Supplier<Optional<AddressInfo>>> supplierMap = new HashMap<>(suppliers.size());
			Map<Integer, Future<Optional<AddressInfo>>> futureMap = new HashMap<>(suppliers.size());

			int index = 0;

			for (Supplier<Optional<AddressInfo>> supplier : suppliers) {

				Future<Optional<AddressInfo>> future = executor.submit(() -> supplier.get());

				supplierMap.put(index, supplier);
				futureMap.put(index, future);

				index++;
			}

			final int count = index;

			for( index = 0; index < count; index++) {
				Future<Optional<AddressInfo>> future = futureMap.get(index);

				try {
					Optional<AddressInfo> info = future.get(TIMEOUT, TimeUnit.SECONDS);

					if (info.isPresent()) {
						infos.add(info.get());
					} else {
						suppliersToRetry.add(supplierMap.get(index));
					}
				} catch (TimeoutException e) {
					suppliersToRetry.add(supplierMap.get(index));
				}
			}

			for( Future<Optional<AddressInfo>> future: futureMap.values()) {
				future.cancel(true);
			}

			if( !suppliersToRetry.isEmpty() ) {

				if( retries > 0 ) {
					infos.addAll(getAddressInfosFromSuppliers(suppliersToRetry, executor, retries - 1));
				}
				else {
					throw new ForeignBlockchainException("can't get all address infos");
				}
			}

			return infos;
		} catch (Exception e) {
			throw new ForeignBlockchainException(e.getMessage());
		}
	}

	/**
	 * Build Address Info
	 *
	 * @param key the key for generating the address
	 *
	 * @return the info for the address generated, empty if there is a connection problem
	 */
	public Optional<AddressInfo> buildAddressInfo(BitcoinyDeterministicKey key)  {

		String address = pkhToAddress(key.getPublicKeyHash());

		try {
			int transactionCount = getAddressTransactions(BitcoinyScript.p2pkhScript(key.getPublicKeyHash()), true).size();

			return Optional.of(
				new AddressInfo(
					address,
					key.getPath(),
					summingUnspentOutputs(address),
					key.getPathAsString(),
					transactionCount,
					true)
			);
		} catch (ForeignBlockchainException e) {
			return Optional.empty();
		}
	}

	public Set<String> getWalletAddresses(String key58) throws ForeignBlockchainException {
		// generate keys asynchronously and get the addresses, return value
		Set<String> addresses = getWalletAddressesWithExecutor(key58, EXECUTOR);

		return addresses;
	}

	/**
	 * Get Wallet Addresses With Executor
	 *
	 * Get wallet addresses asynchronously.
	 *
	 * @param key58 the master key
	 * @param executor the executor for asynchronous processing
	 *
	 * @return the addresses
	 *
	 * @throws ForeignBlockchainException
	 */
	public Set<String> getWalletAddressesWithExecutor(String key58, ExecutorService executor) throws ForeignBlockchainException {
		Set<BitcoinyDeterministicKey> walletKeys = getWalletKeysWithExecutor(key58, executor);

		return
			walletKeys.stream()
				.map(key -> pkhToAddress(key.getPublicKeyHash()))
				.collect(Collectors.toSet());
	}

	/**
	 * Get Wallet Keys With Executor
	 *
	 * Get wallet keys asynchronously
	 *
	 * @param key58 the master key to determine kday generation
	 * @param executor the executor for asychronous processing
	 *
	 * @return the keys
	 *
	 * @throws ForeignBlockchainException
	 */
	public Set<BitcoinyDeterministicKey> getWalletKeysWithExecutor(String key58, ExecutorService executor) throws ForeignBlockchainException {
		BitcoinyDeterministicKeyChain keyChain = BitcoinyDeterministicKeyChain.fromBase58(this.params, key58);

		// the return value
		Set<BitcoinyDeterministicKey> keySet = processKeysOnly(executor, keyChain.getInitialLeafKeys(Bitcoiny.WALLET_KEY_LOOKAHEAD_INCREMENT),
				keyChain, 0, 0);

		return keySet;
	}

	Set<BitcoinyDeterministicKey> getWalletKeys(String key58) throws ForeignBlockchainException {
		return getWalletKeysWithExecutor(key58, EXECUTOR);
	}

	/**
	 * Process Keys Only
	 *
	 * Generate keys asynchronously, no addresses are generated
	 *
	 * @param executor for asynchronou processing
	 * @param keys the generated keys
	 * @param keyChain for determining keys to generate
	 * @param unusedCounter start at zero, increases from recursion
	 *
	 * @return the generated keys
	 *
	 * @throws ForeignBlockchainException
	 */
	private Set<BitcoinyDeterministicKey> processKeysOnly(ExecutorService executor, List<BitcoinyDeterministicKey> keys,
			BitcoinyDeterministicKeyChain keyChain, int unusedCounter, int existingLeafKeyCount) throws ForeignBlockchainException {

		Set<BitcoinyDeterministicKey> keySet = new HashSet<>();

		boolean needToProcessAdditionalKeys = false;

		List<Supplier<Boolean>> transactionChecks = new ArrayList<>(keys.size());

		for (BitcoinyDeterministicKey dKey : keys) {

			keySet.add(dKey);

			// if the key already has a verified transaction history
			if( this.blockchainCache.keyHasHistory( dKey.getCacheKey() ) ){
				needToProcessAdditionalKeys = true;
			}
			// if the key does not have a verified transaction history
			else {
				transactionChecks.add( () -> checkForTransactions(dKey, BitcoinyScript.p2pkhScript(dKey.getPublicKeyHash())) );
			}
		}

		int processedLeafKeyCount = existingLeafKeyCount + keys.size();

		if( needToProcessAdditionalKeys || anyTrue( executor, transactionChecks, RETRIES )) {
			keySet.addAll(processKeysOnly(executor, generateMoreKeys(keyChain, processedLeafKeyCount), keyChain, 0, processedLeafKeyCount));
		}
		// if no additional keys were already processed and the if the gap limit held, then process additional keys
		else if ( unusedCounter < Settings.getInstance().getGapLimit()) {

			keySet.addAll(processKeysOnly(executor, generateMoreKeys(keyChain, processedLeafKeyCount), keyChain,
					unusedCounter + WALLET_KEY_LOOKAHEAD_INCREMENT, processedLeafKeyCount));
		}

		return keySet;
	}

	/**
	 * Any True?
	 *
	 * Are any of the future tasks returning true?
	 *
	 * @param suppliers the future task suppliers
	 *
	 * @return true if any task returns true, false if all tasks return false
	 */
	public static boolean anyTrue(ExecutorService executor, List<Supplier<Boolean>> suppliers, int retries) throws ForeignBlockchainException {

		// return value
		boolean anyTrueYet = false;

		// for recursion if necessary
		List<Supplier<Boolean>> suppliersToRetry = new ArrayList<>(suppliers.size());

		try {
			Map<Integer, Supplier<Boolean>> supplierMap = new HashMap<>( suppliers.size() );
			Map<Integer, Future<Boolean>> futureMap = new HashMap<>( suppliers.size() );

			int index = 0;

			for( Supplier<Boolean> supplier : suppliers ) {

				Future<Boolean> future = executor.submit(() -> supplier.get());

				supplierMap.put( index, supplier);
				futureMap.put( index, future );

				index++;
			}

			final int count = index;

			for( index = 0; index < count; index++) {

				Future<Boolean> future = futureMap.get(index);

				try {
					if( future.get(TIMEOUT, TimeUnit.SECONDS) ) {
						anyTrueYet = true;
						break;
					}
				} catch (TimeoutException e) {
					suppliersToRetry.add(supplierMap.get(index));
				}
			}

			for( Future<Boolean> future: futureMap.values()) {
				future.cancel(true);
			}
		} catch (Exception e) {
			throw new ForeignBlockchainException(e.getMessage());
		}

		if( retries > 0 && !anyTrueYet && !suppliersToRetry.isEmpty() ) {
			return anyTrue(executor, suppliersToRetry, retries - 1);
		}
		else {
			return anyTrueYet;
		}
	}

	/**
	 * Any Transactions?
	 *
	 * Any transactions for this address?
	 *
	 * @param dKey the key that generated this address
	 * @param script the address script
	 *
	 * @return true if there are any transactions for this address, false if there are no transactions
	 *
	 * @throws ForeignBlockchainException
	 */
	private boolean checkForTransactions(BitcoinyDeterministicKey dKey, byte[] script) {
		return checkForTransactions(dKey.getCacheKey(), script);
	}

	private boolean checkForTransactions(String keyCacheKey, byte[] script) {
		try {
			// Ask for transaction history - if it's empty then key has never been used
			List<TransactionHash> historicTransactionHashes = this.getAddressTransactions(script, true);

			// if the key has history, then it should be processing additional keys
			if (!historicTransactionHashes.isEmpty()) {
				this.blockchainCache.addKeyWithHistory(keyCacheKey);
				return true;
			}
		} catch (ForeignBlockchainException e) {
			if ("Interrupted while waiting for ElectrumX connection".equals(e.getMessage())) {
				LOGGER.debug(e.getMessage());
			} else {
				LOGGER.warn(e.getMessage());
			}
		}

		return false;
	}

	/**
	 * Get Transactions
	 *
	 * Get all the transactions for an address, asynchronously.
	 *
	 * @param script the address script
	 * @param futures where the transaction fetch tasks get collected
	 * @param executor for asychronous processing
	 *
	 * @return true if the adddress has any transactions, false for no transactions
	 *
	 * @throws ForeignBlockchainException
	 */
	private boolean getTransactions(byte[] script, List<Supplier<Optional<BitcoinyTransaction>>> futures, ExecutorService executor) throws ForeignBlockchainException {

		// return value
		boolean processAdditionalKeys = false;

		// Ask for transaction history - if it's empty then key has never been used
		List<TransactionHash> historicTransactionHashes = this.getAddressTransactions(script, true);

		// if the key has history, then it should be processing additional keys
		if (!historicTransactionHashes.isEmpty()) {

			processAdditionalKeys = true;

			// get the transactions from the hashes
			for (TransactionHash transactionHash : historicTransactionHashes) {

				Optional<BitcoinyTransaction> walletTransaction
						= this.blockchainCache.getTransactionByHash( transactionHash.txHash );

				// if the wallet transaction is already cached
				if(walletTransaction.isPresent() ) {
					futures.add( () -> walletTransaction );
				}
				// otherwise get the transaction from the blockchain server
				else {
					futures.add( () -> getBitcoinyTransaction(transactionHash) );
				}
			}
		}

		return processAdditionalKeys;
	}

	protected SimpleTransaction convertToSimpleTransaction(BitcoinyTransaction t, Set<String> keySet) {
		long amount = 0;
		long total = 0L;
		long totalInputAmount = 0L;
		long totalOutputAmount = 0L;
		List<SimpleTransaction.Input> inputs = new ArrayList<>();
		List<SimpleTransaction.Output> outputs = new ArrayList<>();

		boolean anyOutputAddressInWallet = false;
		boolean transactionInvolvesExternalWallet = false;

		for (BitcoinyTransaction.Input input : t.inputs) {
			try {
				BitcoinyTransaction t2 = getTransaction(input.outputTxHash);
				List<String> senders = t2.outputs.get(input.outputVout).addresses;
				long inputAmount = t2.outputs.get(input.outputVout).value;
				totalInputAmount += inputAmount;
				if (senders != null) {
					for (String sender : senders) {
						boolean addressInWallet = false;
						if (keySet.contains(sender)) {
							total += inputAmount;
							addressInWallet = true;
						}
						else {
							transactionInvolvesExternalWallet = true;
						}
						inputs.add(new SimpleTransaction.Input(sender, inputAmount, addressInWallet));
					}
				}
			} catch (ForeignBlockchainException e) {
				LOGGER.warn("Failed to retrieve transaction information {}", input.outputTxHash);
			}
		}

		// Group by sender and sum values
		Map<String, Long> totalSumBySender
			= inputs.stream()
				.collect(Collectors.groupingBy(
						SimpleTransaction.Input::getAddress,
						Collectors.reducing(
								0L,
								SimpleTransaction.Input::getAmount,
								Long::sum
						)
				));

		// Create new objects with summed values
		List<SimpleTransaction.Input> groupedInputs
			= totalSumBySender.entrySet().stream()
				.map(entry -> new SimpleTransaction.Input(entry.getKey(), entry.getValue(), keySet.contains(entry.getKey())))
				.collect(Collectors.toList());

		inputs.clear();
		inputs.addAll(groupedInputs);

		if (t.outputs != null && !t.outputs.isEmpty()) {
			for (BitcoinyTransaction.Output output : t.outputs) {
				if (output.addresses != null) {
					for (String address : output.addresses) {
						boolean addressInWallet = false;
						if (keySet.contains(address)) {
							if (total > 0L) { // Change returned from sent amount
								amount -= (total - output.value);
							} else { // Amount received
								amount += output.value;
							}
							addressInWallet = true;
							anyOutputAddressInWallet = true;
						}
						else {
							transactionInvolvesExternalWallet = true;
						}
						outputs.add(new SimpleTransaction.Output(address, output.value, addressInWallet));
					}
				}
				totalOutputAmount += output.value;
			}
		}

		// Group by address and sum values
		Map<String, Long> totalSumByAddress
				= outputs.stream()
				.collect(Collectors.groupingBy(
						SimpleTransaction.Output::getAddress,
						Collectors.reducing(
								0L,
								SimpleTransaction.Output::getAmount,
								Long::sum
						)
				));

		// Create new objects with summed values
		List<SimpleTransaction.Output> groupedOutputs
				= totalSumByAddress.entrySet().stream()
				.map(entry -> new SimpleTransaction.Output(entry.getKey(), entry.getValue(), keySet.contains(entry.getKey())))
				.collect(Collectors.toList());

		outputs.clear();
		outputs.addAll(groupedOutputs);

		long fee = totalInputAmount - totalOutputAmount;

		if (!anyOutputAddressInWallet) {
			// No outputs relate to this wallet - check if any inputs did (which is signified by a positive total)
			if (total > 0) {
				amount = total * -1;
			}
		}
		else if (!transactionInvolvesExternalWallet) {
			// All inputs and outputs relate to this wallet, so the balance should be unaffected
			amount = 0;
		}
		Long timestampMillis;

		if( t.timestamp != null )
			timestampMillis = t.timestamp * 1000L;
		else
			timestampMillis = null;

		return new SimpleTransaction(t.txHash, timestampMillis, amount, fee, inputs, outputs, null);
	}

	/**
	 * Returns first unused receive address given a BIP32 key.
	 *
	 * @param key58 BIP32/HD extended private/public key
	 * @return P2PKH address
	 * @throws ForeignBlockchainException if something went wrong
	 */
	public String getUnusedReceiveAddress(String key58) throws ForeignBlockchainException {
		BitcoinyDeterministicKeyChain keyChain = BitcoinyDeterministicKeyChain.fromBase58(this.params, key58);
		int receiveKeyIndex = 0;

		do {
			// the next receive funds address
			BitcoinyDeterministicKey key = keyChain.getReceiveKey(receiveKeyIndex++);
			String address = pkhToAddress(key.getPublicKeyHash());

			// if zero transactions, return address
			if(getAddressTransactions(BitcoinyScript.p2pkhScript(key.getPublicKeyHash()), true).isEmpty())
				return address;

			// else try the next receive funds address
		} while (true);
	}

	public abstract long getFeeRequired();

	public abstract void setFeeRequired(long fee);

	private SpendableOutputs getSpendableOutputs(String key58, boolean includeUnconfirmed) {
		try {
			Set<BitcoinyDeterministicKey> walletKeys = getWalletKeysWithExecutor(key58, EXECUTOR);
			List<UnspentOutput> spendableOutputs = new ArrayList<>();
			int highestLeafIndex = 0;

			for (BitcoinyDeterministicKey key : walletKeys) {
				highestLeafIndex = Math.max(highestLeafIndex, getLeafIndex(key));

				byte[] script = BitcoinyScript.p2pkhScript(key.getPublicKeyHash());
				List<UnspentOutput> unspentOutputs = this.blockchainProvider.getUnspentOutputs(script, includeUnconfirmed);
				for (UnspentOutput unspentOutput : unspentOutputs) {
					Optional<UnspentOutput> resolvedUnspentOutput = resolveUnspentOutput(unspentOutput);
					if (resolvedUnspentOutput.isEmpty())
						throw new ForeignBlockchainException(String.format("Unable to resolve spendable output %s:%d",
								HashCode.fromBytes(unspentOutput.hash), unspentOutput.index));

					if (isSpendableOutput(resolvedUnspentOutput.get()))
						spendableOutputs.add(resolvedUnspentOutput.get());
				}
			}

			int walletLookaheadSize = Math.max(WALLET_KEY_LOOKAHEAD_INCREMENT, highestLeafIndex + 1);
			return new SpendableOutputs(spendableOutputs, walletLookaheadSize);
		} catch (ForeignBlockchainException e) {
			LOGGER.warn("Unable to collect spendable {} outputs: {}", this.currencyCode, e.getMessage());
			return SpendableOutputs.empty();
		}
	}

	private static int getLeafIndex(BitcoinyDeterministicKey key) {
		List<Integer> path = key.getPath();
		if (path.isEmpty())
			return 0;

		return path.get(path.size() - 1) & Integer.MAX_VALUE;
	}

	protected boolean hasSpendableOutputScriptFilter() {
		return false;
	}

	protected boolean isSpendableOutputScript(byte[] scriptPubKey) {
		return true;
	}

	private boolean isSpendableOutput(UnspentOutput unspentOutput) {
		return unspentOutput.script != null && isSpendableOutputScript(unspentOutput.script);
	}

	private static void primeWalletForSpend(Wallet wallet, SpendableOutputs spendableOutputs) {
		wallet.getActiveKeyChain().setLookaheadSize(spendableOutputs.walletLookaheadSize);
		wallet.getActiveKeyChain().setLookaheadThreshold(0);
		wallet.getActiveKeyChain().maybeLookAhead();
	}

	private UTXO toBitcoinjUTXO(UnspentOutput unspentOutput) throws ForeignBlockchainException {
		Script scriptPubKey = this.getScriptPubKey(unspentOutput);
		return new UTXO(Sha256Hash.wrap(unspentOutput.hash), unspentOutput.index,
				Coin.valueOf(unspentOutput.value), unspentOutput.height, false, scriptPubKey);
	}

	static class PrecomputedUTXOProvider implements UTXOProvider {
		private final Bitcoiny bitcoiny;
		private final List<UnspentOutput> spendableOutputs;

		public PrecomputedUTXOProvider(Bitcoiny bitcoiny, List<UnspentOutput> spendableOutputs) {
			this.bitcoiny = bitcoiny;
			this.spendableOutputs = new ArrayList<>(spendableOutputs);
		}

		@Override
		public List<UTXO> getOpenTransactionOutputs(List<ECKey> keys) throws UTXOProviderException {
			try {
				List<UTXO> utxos = new ArrayList<>(this.spendableOutputs.size());
				for (UnspentOutput spendableOutput : this.spendableOutputs)
					utxos.add(this.bitcoiny.toBitcoinjUTXO(spendableOutput));
				return utxos;
			} catch (Exception e) {
				throw new UTXOProviderException(e.getMessage());
			}
		}

		@Override
		public int getChainHeadHeight() throws UTXOProviderException {
			try {
				return this.bitcoiny.blockchainProvider.getCurrentHeight();
			} catch (ForeignBlockchainException e) {
				throw new UTXOProviderException("Unable to determine Bitcoiny chain height");
			}
		}

		@Override
		public Network network() {
			return this.bitcoiny.params.network();
		}
	}

	private Long summingUnspentOutputs(String walletAddress) throws ForeignBlockchainException {
		List<UnspentOutput> unspentOutputs = hasSpendableOutputScriptFilter()
				? getUnspentOutputs(walletAddress, true)
				: this.blockchainProvider.getUnspentOutputs(walletAddress, true);
		return unspentOutputs.stream()
				.mapToLong(unspentOutput -> unspentOutput.value)
				.sum();
	}

	// Utility methods for others

	public static List<SimpleForeignTransaction> simplifyWalletTransactions(List<BitcoinyTransaction> transactions) {
		// Sort by oldest timestamp first
		transactions.sort(Comparator.comparingInt(t -> t.timestamp));

		// Manual 2nd-level sort same-timestamp transactions so that a transaction's input comes first
		int fromIndex = 0;
		do {
			int timestamp = transactions.get(fromIndex).timestamp;

			int toIndex;
			for (toIndex = fromIndex + 1; toIndex < transactions.size(); ++toIndex)
				if (transactions.get(toIndex).timestamp != timestamp)
					break;

			// Process same-timestamp sub-list
			List<BitcoinyTransaction> subList = transactions.subList(fromIndex, toIndex);

			// Only if necessary
			if (subList.size() > 1) {
				// Quick index lookup
				Map<String, Integer> indexByTxHash = subList.stream().collect(Collectors.toMap(t -> t.txHash, t -> t.timestamp));

				int restartIndex = 0;
				boolean isSorted;
				do {
					isSorted = true;

					for (int ourIndex = restartIndex; ourIndex < subList.size(); ++ourIndex) {
						BitcoinyTransaction ourTx = subList.get(ourIndex);

						for (BitcoinyTransaction.Input input : ourTx.inputs) {
							Integer inputIndex = indexByTxHash.get(input.outputTxHash);

							if (inputIndex != null && inputIndex > ourIndex) {
								// Input tx is currently after current tx, so swap
								BitcoinyTransaction tmpTx = subList.get(inputIndex);
								subList.set(inputIndex, ourTx);
								subList.set(ourIndex, tmpTx);

								// Update index lookup too
								indexByTxHash.put(ourTx.txHash, inputIndex);
								indexByTxHash.put(tmpTx.txHash, ourIndex);

								if (isSorted)
									restartIndex = Math.max(restartIndex, ourIndex);

								isSorted = false;
								break;
							}
						}
					}
				} while (!isSorted);
			}

			fromIndex = toIndex;
		} while (fromIndex < transactions.size());

		// Simplify
		List<SimpleForeignTransaction> simpleTransactions = new ArrayList<>();

		// Quick lookup of txs in our wallet
		Set<String> walletTxHashes = transactions.stream().map(t -> t.txHash).collect(Collectors.toSet());

		for (BitcoinyTransaction transaction : transactions) {
			SimpleForeignTransaction.Builder builder = new SimpleForeignTransaction.Builder();
			builder.txHash(transaction.txHash);
			builder.timestamp(transaction.timestamp);

			builder.isSentNotReceived(false);

			for (BitcoinyTransaction.Input input : transaction.inputs) {
				// TODO: add input via builder

				if (walletTxHashes.contains(input.outputTxHash))
					builder.isSentNotReceived(true);
			}

			for (BitcoinyTransaction.Output output : transaction.outputs)
				builder.output(output.addresses, output.value);

			simpleTransactions.add(builder.build());
		}

		return simpleTransactions;
	}

	// Utility methods for us

	protected static List<BitcoinyDeterministicKey> generateMoreKeys(BitcoinyDeterministicKeyChain keyChain, int existingLeafKeyCount) {
		return keyChain.getMoreLeafKeys(existingLeafKeyCount, Bitcoiny.WALLET_KEY_LOOKAHEAD_INCREMENT);
	}

	protected byte[] addressToScriptPubKey(String base58Address) {
		return BitcoinyScript.scriptPubKey(this.params, base58Address);
	}

	private static class SpendableOutputs {
		private final List<UnspentOutput> outputs;
		private final int walletLookaheadSize;

		private SpendableOutputs(List<UnspentOutput> outputs, int walletLookaheadSize) {
			this.outputs = outputs;
			this.walletLookaheadSize = walletLookaheadSize;
		}

		private static SpendableOutputs empty() {
			return new SpendableOutputs(Collections.emptyList(), WALLET_KEY_LOOKAHEAD_INCREMENT);
		}
	}
}
