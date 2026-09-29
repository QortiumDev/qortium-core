package org.qortium.crosschain;

import cash.z.wallet.sdk.rpc.CompactFormats;
import org.bitcoinj.base.Base58;
import org.bitcoinj.base.Bech32;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.*;
import org.bitcoinj.base.utils.MonetaryFormat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.qortium.api.model.crosschain.PirateChainBalance;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryRequest;
import org.qortium.api.model.crosschain.PirateChainVerifiedRecoveryResult;
import org.qortium.controller.PirateChainWalletController;
import org.qortium.controller.ZcashFamilyWalletController;
import org.qortium.crosschain.PirateLightClient.Server;
import org.qortium.crosschain.ChainableServer.ConnectionType;
import org.qortium.crypto.Crypto;
import org.qortium.settings.Settings;
import org.qortium.transform.TransformationException;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class PirateChain extends Bitcoiny {

	public static final String CURRENCY_CODE = "ARRR";
	public static final ZcashFamilyWalletConfig WALLET_CONFIG = new ZcashFamilyWalletConfig(
			"Pirate Chain",
			CURRENCY_CODE,
			"PirateChain",
			"EsfUw54perxkEtfoUoL7Z97XPrNsZRZXePVZPz3cwRm9qyEPSofD5KmgVpDqVitQp7LhnZRmL6z2V9hEe1YS45T",
			"ARRRWalletEncryption",
			"zs",
			() -> Settings.getInstance().getArrrDefaultBirthday(),
			PirateChain::getInstance,
			() -> Settings.getInstance().isPirateChainWalletUnified(),
			() -> Settings.getInstance().getPirateChainWalletQdnSignature(),
			() -> Settings.getInstance().isPirateChainWalletDebugLogging());

	private static final Coin DEFAULT_FEE_PER_KB = Coin.valueOf(10000); // 0.0001 ARRR per 1000 bytes

	private static final long MINIMUM_ORDER_AMOUNT = 10000; // 0.0001 ARRR minimum order, to avoid dust errors // TODO: increase this

	// Temporary values until a dynamic fee system is written.
	static final long MAINNET_FEE = 10000L; // 0.0001 ARRR
	private static final long NON_MAINNET_FEE = 10000L; // 0.0001 ARRR
	static final String SEND_COMMAND = "send";
	static final String FUND_P2SH_COMMAND = "sendp2sh";
	static final String REDEEM_P2SH_COMMAND = "redeemp2sh";

	private static final String MAINNET_GENESIS_HASH = "027e3758c3a65b12aa1046462b486d0a63bfa1beae327897f56c5cfb7daaae71";

	private static final NetworkParameters MAINNET_PARAMS = StaticBitcoinyParams.builder("main", "main", "pirate")
			.genesis(1231006505L, 11L, 0x200f0f0fL, MAINNET_GENESIS_HASH)
			.genesisHeader(4L, "31e71120c25cd57fd138dfeba98799f2e314bad9ece0b0632fd2a779c9ebb4c2")
			.maxTarget(0x200f0f0fL)
			.targetTimespan(302_400)
			.interval(5040)
			.port(7770)
			.packetMagic(0xf9beb4d9L)
			.addressHeaders(60, 85, 188)
			.segwitAddressHrp("zs")
			.coinbaseAndSubsidy(100, 210_000)
			.bip32Headers(0x0488B21E, 0x0488ADE4)
			.majorityWindow(750, 950, 1000)
			.dnsSeeds("pirate1.cryptoforge.cc", "pirate2.cryptoforge.cc", "pirate3.cryptoforge.cc",
					"explorer.cryptoforge.cc", "explorer.pirate.black", "mseed.dexstats.info",
					"seed.komodostats.com", "bootstrap.arrr.black")
			.maxMoney(Coin.COIN.multiply(200_000_000L))
			.minNonDustOutput(Coin.valueOf(100_000L))
			.monetaryFormat(MonetaryFormat.BTC.noCode()
					.code(0, "PIRATE")
					.code(3, "mPIRATE")
					.code(7, "zatoshi"))
			.difficultyValidationFailure("PirateChain difficulty verification is not implemented for light-client parameters")
			.build();

	private static final Map<ConnectionType, Integer> DEFAULT_LITEWALLET_PORTS = new EnumMap<>(ConnectionType.class);
	static {
		DEFAULT_LITEWALLET_PORTS.put(ConnectionType.TCP, 9067);
		DEFAULT_LITEWALLET_PORTS.put(ConnectionType.SSL, 443);
	}

	public enum PirateChainNet {
		MAIN {
			@Override
			public NetworkParameters getParams() {
				return MAINNET_PARAMS;
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList(
						// Servers chosen on NO BASIS WHATSOEVER from various sources!
						new Server("lightd.pirate.black", Server.ConnectionType.SSL, 443),
						new Server("arrr.qortal.link", Server.ConnectionType.SSL, 443),
						new Server("arrr2.qortal.link", Server.ConnectionType.SSL, 443),
						new Server("arrr3.qortal.link", Server.ConnectionType.SSL, 443)
				);

			}

			@Override
			public String getGenesisHash() {
				return MAINNET_GENESIS_HASH;
			}

			@Override
			public String getLightdChainName() {
				return "main";
			}

			@Override
			public long getP2shFee(Long timestamp) {
				return this.getFeeRequired();
			}
		},
		TEST3 {
			@Override
			public NetworkParameters getParams() {
				return BitcoinyChainSpecs.litecoinTestNetParams();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList();
			}

			@Override
			public String getGenesisHash() {
				return "4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0";
			}

			@Override
			public String getLightdChainName() {
				return "test";
			}

			@Override
			public long getP2shFee(Long timestamp) {
				return NON_MAINNET_FEE;
			}
		},
		REGTEST {
			@Override
			public NetworkParameters getParams() {
				return BitcoinyChainSpecs.litecoinRegTestParams();
			}

			@Override
			public Collection<Server> getServers() {
				return Arrays.asList(
					new Server("127.0.0.1", Server.ConnectionType.TCP, 9067),
					new Server("127.0.0.1", Server.ConnectionType.TCP, 9068),
					new Server("127.0.0.1", Server.ConnectionType.SSL, 443)
				);
			}

			@Override
			public String getGenesisHash() {
				// This is unique to each regtest instance.
				return null;
			}

			@Override
			public String getLightdChainName() {
				return "regtest";
			}

			@Override
			public long getP2shFee(Long timestamp) {
				return NON_MAINNET_FEE;
			}
		};

		private AtomicLong feeRequired = new AtomicLong(MAINNET_FEE);

		public long getFeeRequired() {
			return feeRequired.get();
		}

		public void setFeeRequired(long feeRequired) {
			this.feeRequired.set(feeRequired);
		}

		public abstract NetworkParameters getParams();
		public abstract Collection<Server> getServers();
		public abstract String getGenesisHash();
		public abstract String getLightdChainName();
		public abstract long getP2shFee(Long timestamp) throws ForeignBlockchainException;
	}

	private static PirateChain instance;

	private final PirateChainNet pirateChainNet;

	// Scheduled executor service to check connection to Pirate Chain server
	private final ScheduledExecutorService pirateChainCheckScheduler = Executors.newScheduledThreadPool(1);

	// Constructors and instance

	private PirateChain(PirateChainNet pirateChainNet, BitcoinyBlockchainProvider blockchain, Context bitcoinjContext, String currencyCode) {
		super(blockchain, bitcoinjContext, pirateChainNet.getParams(), currencyCode, DEFAULT_FEE_PER_KB);
		this.pirateChainNet = pirateChainNet;

		pirateChainCheckScheduler.scheduleWithFixedDelay(this::establishConnection, 30, 300, TimeUnit.SECONDS);

		LOGGER.info(() -> String.format("Starting Pirate Chain support using %s", this.pirateChainNet.name()));
	}

	public static synchronized PirateChain getInstance() {
		if (instance == null && Settings.getInstance().isWalletEnabled("ARRR")) {
			PirateChainNet pirateChainNet = Settings.getInstance().getPirateChainNet();

			BitcoinyBlockchainProvider pirateLightClient = new PirateLightClient("PirateChain-" + pirateChainNet.name(),
					pirateChainNet.getLightdChainName(), pirateChainNet.getServers(), DEFAULT_LITEWALLET_PORTS);
			Context bitcoinjContext = new Context(pirateChainNet.getParams());

			instance = new PirateChain(pirateChainNet, pirateLightClient, bitcoinjContext, CURRENCY_CODE);

			pirateLightClient.setBlockchain(instance);
		}

		return instance;
	}

	// Getters & setters

	public static synchronized void resetForTesting() {
		instance = null;
	}

	// Actual useful methods for use by other classes

	@Override
	public long getMinimumOrderAmount() {
		return MINIMUM_ORDER_AMOUNT;
	}

	/**
	 * Returns estimated LTC fee, in sats per 1000bytes, optionally for historic timestamp.
	 * 
	 * @param timestamp optional milliseconds since epoch, or null for 'now'
	 * @return sats per 1000bytes, or throws ForeignBlockchainException if something went wrong
	 */
	@Override
	public long getP2shFee(Long timestamp) throws ForeignBlockchainException {
		return this.pirateChainNet.getP2shFee(timestamp);
	}

	@Override
	public long getFeeRequired() {
		return this.pirateChainNet.getFeeRequired();
	}

	@Override
	public void setFeeRequired(long fee) {

		this.pirateChainNet.setFeeRequired( fee );
	}
	/**
	 * Returns confirmed balance, based on passed payment script.
	 * <p>
	 * @return confirmed balance, or zero if balance unknown
	 * @throws ForeignBlockchainException if there was an error
	 */
	public long getConfirmedBalance(String base58Address) throws ForeignBlockchainException {
		return this.blockchainProvider.getConfirmedAddressBalance(base58Address);
	}

	/**
	 * Returns median timestamp from latest 11 blocks, in seconds.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	@Override
	public int getMedianBlockTime() throws ForeignBlockchainException {
		int height = this.blockchainProvider.getCurrentHeight();

		// Grab latest 11 blocks
		List<Long> blockTimestamps = this.blockchainProvider.getBlockTimestamps(height - 11, 11);
		if (blockTimestamps.size() < 11)
			throw new ForeignBlockchainException("Not enough blocks to determine median block time");

		// Descending order
		blockTimestamps.sort((a, b) -> Long.compare(b, a));

		// Pick median
		return Math.toIntExact(blockTimestamps.get(5));
	}

	/**
	 * Returns list of compact blocks
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public List<CompactFormats.CompactBlock> getCompactBlocks(int startHeight, int count) throws ForeignBlockchainException {
		return this.blockchainProvider.getCompactBlocks(startHeight, count);
	}


	/** Mainnet Sapling human-readable part. */
	static final String SAPLING_HRP = "zs";
	/** Bech32 length of a Sapling payment address: hrp(2) + separator(1) + 69 data + 6 checksum. */
	static final int SAPLING_ADDRESS_LENGTH = 78;
	/** Decoded Sapling payment address: 11-byte diversifier + 32-byte pk_d. */
	static final int SAPLING_PAYLOAD_BYTES = 43;

	/**
	 * Strict check for a canonical mainnet Sapling payment address as the send contract accepts it:
	 * lowercase only (a Bech32 decoder accepts uppercase and mixed case; a spend must not), exactly
	 * {@value #SAPLING_ADDRESS_LENGTH} characters, {@value #SAPLING_HRP} human-readable part, plain
	 * Bech32 (not Bech32m) checksum, valid zero padding, and a {@value #SAPLING_PAYLOAD_BYTES}-byte
	 * payload. Never throws.
	 */
	public static boolean isCanonicalSaplingAddress(String address) {
		if (address == null || address.length() != SAPLING_ADDRESS_LENGTH)
			return false;
		if (!address.equals(address.toLowerCase(Locale.ROOT)))
			return false;
		if (!address.startsWith(SAPLING_HRP + "1"))
			return false;

		try {
			byte[] payload = Bech32.decodeBytes(address, SAPLING_HRP, Bech32.Encoding.BECH32);
			return payload != null && payload.length == SAPLING_PAYLOAD_BYTES;
		} catch (IllegalArgumentException e) { // AddressFormatException is an IllegalArgumentException
			// Wrong hrp, bad checksum, bech32m, invalid characters, bad padding, ...
			return false;
		}
	}

	/**
	 * Tightened (since the send contract) to {@link #isCanonicalSaplingAddress}: lowercase canonical
	 * Sapling only. This is also consulted by the trade paths (trade-bot create/respond receiving
	 * addresses), which previously tolerated uppercase and Bech32m-encoded addresses that no wallet
	 * produces and that the native wallet would refuse to pay anyway.
	 */
	@Override
	public boolean isValidAddress(String address) {
		return isCanonicalSaplingAddress(address);
	}

	/** Maximum memo size in UTF-8 encoded bytes (ZIP 302 memo field). */
	public static final int MAX_MEMO_BYTES = 512;

	/**
	 * Validates an optional send memo without ever altering it (no trimming, no truncation).
	 *
	 * @return null when the memo is acceptable (including absent/empty), else a stable reason token:
	 *         {@code MEMO_LONE_SURROGATE} (malformed UTF-16 that cannot encode to UTF-8),
	 *         {@code MEMO_CONTROL_CHARACTER} (any control character other than tab, CR and LF,
	 *         including DEL and the C1 range) or {@code MEMO_TOO_LONG} (over {@value #MAX_MEMO_BYTES}
	 *         UTF-8 bytes)
	 */
	public static String validateMemo(String memo) {
		if (memo == null || memo.isEmpty())
			return null;

		for (int i = 0; i < memo.length(); ) {
			char ch = memo.charAt(i);
			if (Character.isHighSurrogate(ch)) {
				if (i + 1 >= memo.length() || !Character.isLowSurrogate(memo.charAt(i + 1)))
					return "MEMO_LONE_SURROGATE";
				i += 2;
				continue;
			}
			if (Character.isLowSurrogate(ch))
				return "MEMO_LONE_SURROGATE";
			if (ch != '\t' && ch != '\r' && ch != '\n' && Character.getType(ch) == Character.CONTROL)
				return "MEMO_CONTROL_CHARACTER";
			i++;
		}

		if (memo.getBytes(StandardCharsets.UTF_8).length > MAX_MEMO_BYTES)
			return "MEMO_TOO_LONG";

		return null;
	}

	@Override
	public boolean isValidWalletKey(String walletKey) {
		// For Pirate Chain, we only care that the key is a random string
		// 32 characters in length, as it is used as entropy for the seed.
		return walletKey != null && Base58.decode(walletKey).length == 32;
	}

	/** Returns 't3' prefixed P2SH address using passed redeem script. */
	public String deriveP2shAddress(byte[] redeemScriptBytes) {
		Context.propagate(bitcoinjContext);
		byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);
		return LegacyZcashAddress.fromScriptHash(this.params, redeemScriptHash).toString();
	}

	/** Returns 'b' prefixed P2SH address using passed redeem script. */
	public String deriveP2shAddressBPrefix(byte[] redeemScriptBytes) {
		byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);
		return BitcoinyAddress.fromScriptHash(this.params, redeemScriptHash).toString();
	}

	public Long getWalletBalance(String entropy58) throws ForeignBlockchainException {
		return this.getWalletBalances(entropy58).zbalance;
	}

	public PirateChainBalance getWalletBalances(String entropy58) throws ForeignBlockchainException {
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		if (walletController == null)
			throw new ForeignBlockchainException("Pirate Chain wallet is disabled");

		return walletController.withEntropyWallet(entropy58, true,
				(wallet, nativeAdapter) -> wallet.getWalletBalances(nativeAdapter));
	}

	/**
	 * Imports one externally derived spending key through the upstream verified recovery request.
	 * <p>
	 * The operation runs as a single coordinator-owned wallet operation bound to the requested
	 * entropy wallet and requires the persistent Unified storage backend. A first import still
	 * requires a fully synchronized wallet. An already-pending recovery may re-enter the native
	 * verified-import operation so an exact retry can receive the native idempotency verdict;
	 * this callback itself runs on the serialized native lane, so no sync/rescan is active while
	 * the retry executes. Balance, history, and send operations retain the ordinary synchronized
	 * gate.
	 */
	public PirateChainVerifiedRecoveryResult importVerifiedSpendingKey(
			PirateChainVerifiedRecoveryRequest recoveryRequest) throws ForeignBlockchainException {
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		if (walletController == null)
			throw new ForeignBlockchainException("Pirate Chain wallet is disabled");

		return walletController.withEntropyWallet(recoveryRequest.entropy58, false, (wallet, nativeAdapter) -> {
			if (!wallet.usesPersistentUnifiedStorage())
				throw new ForeignBlockchainException("Verified recovery requires the persistent Unified Pirate wallet");
			if (!wallet.isReadyForVerifiedRecoveryImport(nativeAdapter))
				throw new ForeignBlockchainException("Wallet isn't synchronized yet");
			return wallet.importVerifiedSpendingKey(nativeAdapter, recoveryRequest);
		});
	}

	static PirateChainBalance parseWalletBalances(String response) throws ForeignBlockchainException {
		try {
			JSONObject json = new JSONObject(response);
			if (!json.has("zbalance"))
				throw new ForeignBlockchainException("Unable to determine total balance");

			long totalBalance = json.getLong("zbalance");
			boolean verifiedKnown = hasNumericValue(json, "verified_zbalance");
			// The legacy backend can omit or malform verified_zbalance. Falling back to the total
			// balance here would silently claim funds are spendable when that is not known to be
			// true, so the placeholder is flagged unknown rather than trusted by callers.
			long verifiedBalance = verifiedKnown ? json.getLong("verified_zbalance") : totalBalance;
			return new PirateChainBalance(totalBalance, verifiedBalance, verifiedKnown);
		} catch (JSONException e) {
			throw new ForeignBlockchainException("Unable to determine balance");
		}
	}

	private static boolean hasNumericValue(JSONObject json, String key) {
		if (!json.has(key) || json.isNull(key))
			return false;
		try {
			json.getLong(key);
			return true;
		} catch (JSONException e) {
			return false;
		}
	}

	/**
	 * Establish Connection
	 *
	 * Some methods in this class need to establish a connection before proceeding and this is the best way
	 * to do it as far as I know.
	 */
	private void establishConnection() {
		try {

			LOGGER.info("Checking Pirate Chain Connection ... ");

			int height;
			synchronized( this ) {
				height = this.blockchainProvider.getCurrentHeight();
			}

			LOGGER.info("Checked Pirate Chain Connection: height = " + height);
		} catch (ForeignBlockchainException e) {
			LOGGER.error(e.getMessage(), e);
		}
	}

	public List<SimpleTransaction> getWalletTransactions(String entropy58) throws ForeignBlockchainException {
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		return walletController.withEntropyWallet(entropy58, true, (wallet, nativeAdapter) -> {
			return wallet.getTransactionHistory(nativeAdapter);
		});
	}

	public String getWalletAddress(String entropy58) throws ForeignBlockchainException {
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		return walletController.withEntropyWallet(entropy58, false,
				(wallet, nativeAdapter) -> wallet.getWalletAddress());
	}

	public String getPrivateKey(String entropy58) throws ForeignBlockchainException {
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		return walletController.withEntropyWallet(entropy58, false, (wallet, nativeAdapter) -> {
			wallet.unlock();
			return wallet.getPrivateKey();
		});
	}

	public String getWalletSeed(String entropy58) throws ForeignBlockchainException {
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		return walletController.withEntropyWallet(entropy58, false, (wallet, nativeAdapter) -> {
			wallet.unlock();
			return wallet.getWalletSeed(entropy58);
		});
	}

	public String getUnusedReceiveAddress(String key58) throws ForeignBlockchainException {
		// For now, return the main wallet address
		// FUTURE: generate an unused one
		return this.getWalletAddress(key58);
	}

	/** Stable reasons carried by {@link ForeignBlockchainException.WalletNotReadyException} from sends. */
	public static final String WALLET_DISABLED_REASON = "ARRR_WALLET_DISABLED";
	public static final String WALLET_MODE_UNSUPPORTED_REASON = "ARRR_WALLET_MODE_UNSUPPORTED";
	public static final String VERIFIED_BALANCE_UNKNOWN_REASON = "ARRR_VERIFIED_BALANCE_UNKNOWN";
	public static final String RECIPIENT_VALIDATION_UNAVAILABLE_REASON = "ARRR_RECIPIENT_VALIDATION_UNAVAILABLE";
	/** Stable reason carried by {@link ForeignBlockchainException.InsufficientFundsException} from sends. */
	public static final String INSUFFICIENT_VERIFIED_FUNDS_REASON = "ARRR_INSUFFICIENT_VERIFIED_FUNDS";
	/** Stable reason carried by {@link ForeignBlockchainException.InvalidRecipientException}. */
	public static final String RECIPIENT_INVALID_REASON = "ARRR_RECIPIENT_INVALID";
	/**
	 * Stable reason carried by {@link ForeignBlockchainException.SendOutcomeUnknownException}: the
	 * native send was started and Core cannot prove it did not broadcast.
	 */
	public static final String SEND_OUTCOME_UNKNOWN_REASON = "ARRR_SEND_OUTCOME_UNKNOWN";

	/** Native JSON method (Stashi v1.2.4+) that validates an address without any wallet or spend. */
	static final String VALIDATE_ADDRESS_METHOD = "validate_address";

	/** Fixed fee every send pays, in atomic units (send contract: feePolicy FIXED). */
	public static long getSendFeeAtomic() {
		return MAINNET_FEE;
	}

	/**
	 * Sends {@code amountAtomic} atomic units to an already-validated canonical Sapling recipient
	 * (send protocol version 1: synchronous, returns the txid the native wallet reports after
	 * broadcast).
	 * <p>
	 * Inputs are assumed validated by the caller ({@link #isCanonicalSaplingAddress},
	 * {@link PirateChainAmountAdapter#parseAtomic}, {@link #validateMemo}); nothing here re-validates
	 * or logs them. Only the persistent Unified backend is supported. Inside the native lane, after
	 * the ordinary synchronized gate: the recipient is semantically validated by the native wallet's
	 * non-spending {@code validate_address} method (curve point, diversifier, network), then the
	 * wallet's verified (spendable) balance is read and the send is refused unless it is known and
	 * covers amount plus fee, so the native wallet is never asked to spend funds Core cannot see.
	 * Native reply text is neither returned nor logged: it may echo recipient and memo.
	 * <p>
	 * Outcome honesty: once native {@code send} has been invoked, every failure and every reply
	 * without a valid transaction ID is reported as
	 * {@link ForeignBlockchainException.SendOutcomeUnknownException}, because
	 * the native wallet broadcasts before it answers and the payment may already be on the network.
	 *
	 * @throws ForeignBlockchainException.WalletNotReadyException wallet disabled, legacy backend, verified balance unknown, or recipient validation unavailable
	 * @throws ForeignBlockchainException.InvalidRecipientException the native wallet rejects the recipient
	 * @throws ForeignBlockchainException.InsufficientFundsException verified balance below amount + fee
	 * @throws ForeignBlockchainException.WalletBusyException another wallet holds the native lane
	 * @throws ForeignBlockchainException.SendOutcomeUnknownException the send started but its outcome is unknown
	 * @throws ForeignBlockchainException any other pre-send failure (not synchronized, endpoint not validated, ...)
	 */
	public String sendCoins(String entropy58, String receivingAddress, long amountAtomic, String memo)
			throws ForeignBlockchainException {
		if (!Settings.getInstance().isPirateChainWalletUnified())
			throw new ForeignBlockchainException.WalletNotReadyException(WALLET_MODE_UNSUPPORTED_REASON);

		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		if (walletController == null)
			throw new ForeignBlockchainException.WalletNotReadyException(WALLET_DISABLED_REASON);

		// A returned worker response does not prove the waiting caller received it.
		AtomicBoolean sendStarted = new AtomicBoolean(false);

		try {
			return walletController.withEntropyWallet(entropy58, true, (wallet, nativeAdapter) -> {
				if (!wallet.usesPersistentUnifiedStorage())
					throw new ForeignBlockchainException.WalletNotReadyException(WALLET_MODE_UNSUPPORTED_REASON);

				validateRecipientNative(nativeAdapter, receivingAddress);

				final PirateChainBalance balances;
				try {
					balances = wallet.getWalletBalances(nativeAdapter);
				} catch (ForeignBlockchainException.BalanceUnavailableException e) {
					throw new ForeignBlockchainException.WalletNotReadyException(VERIFIED_BALANCE_UNKNOWN_REASON);
				}
				assertSufficientVerifiedFunds(balances, amountAtomic);

				wallet.unlock(nativeAdapter);

				// The input address identifies the wallet-owned key group the native wallet spends from.
				// The Unified export follows the active pool (Sapling before Ironwood activation, the
				// matching Ironwood address afterwards); the native wallet accepts either as input for
				// the same key group. Read it through the leased adapter: this already runs on the lane.
				String inputAddress = wallet.getWalletAddress(nativeAdapter);
				if (inputAddress == null || inputAddress.isBlank())
					throw new ForeignBlockchainException.WalletNotReadyException("ARRR_WALLET_ADDRESS_UNKNOWN");

				JSONObject txn = buildSendPayload(inputAddress, receivingAddress, amountAtomic, memo);

				final String response;
				sendStarted.set(true);
				try {
					response = nativeAdapter.execute(SEND_COMMAND, txn.toString());
				} catch (RuntimeException | UnsatisfiedLinkError e) {
					if (Settings.getInstance().isPirateChainWalletDebugLogging())
						LOGGER.debug("Native ARRR send threw", e);
					throw new ForeignBlockchainException.SendOutcomeUnknownException(SEND_OUTCOME_UNKNOWN_REASON);
				}
				return parseSendResponse(response);
			});

		} catch (ForeignBlockchainException e) {
			// A generic failure raised while the native send was in flight (the coordinator timed out
			// and degraded the lane, the worker was interrupted, ...) is NOT a proven failure: the
			// native wallet may have broadcast before the lane gave up.
			if (sendStarted.get())
				throw new ForeignBlockchainException.SendOutcomeUnknownException(SEND_OUTCOME_UNKNOWN_REASON);
			throw e;
		}
	}

	/**
	 * Asks the native wallet (in-lane, no wallet state, no spend) whether the recipient is a valid
	 * Sapling payment address: Bech32 encoding alone does not prove the diversifier maps to a valid
	 * group hash or that pk_d is a prime-order, non-identity Jubjub point.
	 *
	 * @throws ForeignBlockchainException.InvalidRecipientException the native wallet rejects it
	 * @throws ForeignBlockchainException.WalletNotReadyException the native wallet cannot answer
	 */
	static void validateRecipientNative(ZcashFamilyNativeAdapter nativeAdapter, String receivingAddress)
			throws ForeignBlockchainException {
		final String response;
		try {
			JSONObject request = new JSONObject().put("method", VALIDATE_ADDRESS_METHOD).put("address", receivingAddress);
			response = nativeAdapter.invokeJson(request.toString(), false);
		} catch (RuntimeException | UnsatisfiedLinkError e) {
			throw new ForeignBlockchainException.WalletNotReadyException(RECIPIENT_VALIDATION_UNAVAILABLE_REASON);
		}
		if (!parseValidateAddressResponse(response))
			throw new ForeignBlockchainException.InvalidRecipientException(RECIPIENT_INVALID_REASON);
	}

	/**
	 * Interprets a native {@code validate_address} reply
	 * ({@code {"ok":true,"result":{"address_type":"Sapling","is_valid":true,"reason":null}}}).
	 *
	 * @return true only for an explicit {@code ok:true}, {@code is_valid:true}, Sapling answer
	 * @throws ForeignBlockchainException.WalletNotReadyException the reply is not a native answer at all
	 */
	static boolean parseValidateAddressResponse(String response) throws ForeignBlockchainException {
		final JSONObject json;
		try {
			json = new JSONObject(response == null ? "" : response);
		} catch (JSONException e) {
			throw new ForeignBlockchainException.WalletNotReadyException(RECIPIENT_VALIDATION_UNAVAILABLE_REASON);
		}
		if (!(json.opt("ok") instanceof Boolean ok) || !ok)
			throw new ForeignBlockchainException.WalletNotReadyException(RECIPIENT_VALIDATION_UNAVAILABLE_REASON);
		JSONObject result = json.optJSONObject("result");
		if (result == null)
			throw new ForeignBlockchainException.WalletNotReadyException(RECIPIENT_VALIDATION_UNAVAILABLE_REASON);

		boolean valid = result.opt("is_valid") instanceof Boolean isValid && isValid;
		String addressType = result.isNull("address_type") ? null : result.optString("address_type", null);
		return valid && addressType != null && "sapling".equalsIgnoreCase(addressType);
	}

	/**
	 * In-lane funds check shared by the send path: the verified (spendable) balance must be known,
	 * and must cover amount plus the fixed fee without overflow.
	 */
	static void assertSufficientVerifiedFunds(PirateChainBalance balances, long amountAtomic)
			throws ForeignBlockchainException {
		if (balances == null || !balances.verifiedBalanceKnown)
			throw new ForeignBlockchainException.WalletNotReadyException(VERIFIED_BALANCE_UNKNOWN_REASON);

		final long required;
		try {
			required = Math.addExact(amountAtomic, MAINNET_FEE);
		} catch (ArithmeticException e) {
			throw new ForeignBlockchainException.InsufficientFundsException(INSUFFICIENT_VERIFIED_FUNDS_REASON);
		}

		if (amountAtomic <= 0 || required > balances.verified_zbalance)
			throw new ForeignBlockchainException.InsufficientFundsException(INSUFFICIENT_VERIFIED_FUNDS_REASON);
	}

	/** Native errors lack broadcast provenance: only a valid txid proves submission. */
	static String parseSendResponse(String response) throws ForeignBlockchainException {
		try {
			JSONObject json = new JSONObject(response == null ? "" : response);
			Object txid = json.opt("txid");
			if (txid instanceof String value && value.matches("[0-9a-fA-F]{64}")
					&& (!json.has("error") || json.isNull("error")))
				return value.toLowerCase(Locale.ROOT);
		} catch (JSONException e) {
			// No trustworthy txid was delivered. Do not expose native reply text.
		}
		throw new ForeignBlockchainException.SendOutcomeUnknownException(SEND_OUTCOME_UNKNOWN_REASON);
	}

	public String fundP2SH(String entropy58, String receivingAddress, long amount,
						   String redeemScript58) throws ForeignBlockchainException {

		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		return walletController.withEntropyWallet(entropy58, true, (wallet, nativeAdapter) -> {
			wallet.unlock();

			JSONObject txn = buildFundP2shPayload(wallet.getWalletAddress(), receivingAddress, amount,
					redeemScript58);

			String response = nativeAdapter.execute(FUND_P2SH_COMMAND, txn.toString());
			JSONObject json = new JSONObject(response);
			try {
				if (json.has("txid"))
					return json.getString("txid");
				if (json.has("error"))
					throw new ForeignBlockchainException(json.getString("error"));
			} catch (JSONException e) {
				throw new ForeignBlockchainException(e.getMessage());
			}
			throw new ForeignBlockchainException("Something went wrong");
		});
	}

	public String redeemP2sh(String p2shAddress, String receivingAddress, long amount, String redeemScript58,
							 String fundingTxid58, String secret58, String privateKey58) throws ForeignBlockchainException {

		// Use null seed wallet since we may not have the entropy bytes for a real wallet's seed
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		return walletController.withNullSeedWallet((wallet, nativeAdapter) -> {
			wallet.unlock();

			JSONObject txn = buildRedeemP2shPayload(p2shAddress, receivingAddress, amount, redeemScript58,
					fundingTxid58, 0, secret58, privateKey58);

			String response = nativeAdapter.execute(REDEEM_P2SH_COMMAND, txn.toString());
			JSONObject json = new JSONObject(response);
			try {
				if (json.has("txid"))
					return json.getString("txid");
				if (json.has("error"))
					throw new ForeignBlockchainException(json.getString("error"));
			} catch (JSONException e) {
				throw new ForeignBlockchainException(e.getMessage());
			}
			throw new ForeignBlockchainException("Something went wrong");
		});
	}

	public String refundP2sh(String p2shAddress, String receivingAddress, long amount, String redeemScript58,
							 String fundingTxid58, int lockTime, String privateKey58) throws ForeignBlockchainException {

		// Use null seed wallet since we may not have the entropy bytes for a real wallet's seed
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		return walletController.withNullSeedWallet((wallet, nativeAdapter) -> {
			wallet.unlock();

			JSONObject txn = buildRedeemP2shPayload(p2shAddress, receivingAddress, amount, redeemScript58,
					fundingTxid58, lockTime, "", privateKey58);

			String response = nativeAdapter.execute(REDEEM_P2SH_COMMAND, txn.toString());
			JSONObject json = new JSONObject(response);
			try {
				if (json.has("txid"))
					return json.getString("txid");
				if (json.has("error"))
					throw new ForeignBlockchainException(json.getString("error"));
			} catch (JSONException e) {
				throw new ForeignBlockchainException(e.getMessage());
			}
			throw new ForeignBlockchainException("Something went wrong");
		});
	}

	static JSONObject buildSendPayload(String inputAddress, String receivingAddress, long amountAtomic, String memo) {
		JSONObject transaction = buildPaymentPayload(inputAddress, receivingAddress, amountAtomic);
		// JSONObject.put(key, null) removes the key: an absent memo stays absent from the payload.
		transaction.getJSONArray("output").getJSONObject(0).put("memo", memo);
		return transaction;
	}

	static JSONObject buildFundP2shPayload(String inputAddress, String receivingAddress, long amount,
			String redeemScript58) {
		return buildPaymentPayload(inputAddress, receivingAddress, amount).put("script", redeemScript58);
	}

	static JSONObject buildRedeemP2shPayload(String p2shAddress, String receivingAddress, long amount,
			String redeemScript58, String fundingTxid58, int lockTime, String secret58, String privateKey58) {
		return buildPaymentPayload(p2shAddress, receivingAddress, amount)
				.put("script", redeemScript58)
				.put("txid", fundingTxid58)
				.put("locktime", lockTime)
				.put("secret", secret58)
				.put("privkey", privateKey58);
	}

	private static JSONObject buildPaymentPayload(String inputAddress, String receivingAddress, long amount) {
		JSONObject output = new JSONObject()
				.put("address", receivingAddress)
				.put("amount", amount);
		return new JSONObject()
				.put("input", inputAddress)
				.put("fee", MAINNET_FEE)
				.put("output", new JSONArray().put(output));
	}

	public String getSyncStatus(String entropy58) throws ForeignBlockchainException {
		return this.getSyncStatusDetails(entropy58).getMessage();
	}

	public ZcashFamilyWalletController.WalletSyncStatus getSyncStatusDetails(String entropy58)
			throws ForeignBlockchainException {
		PirateChainWalletController walletController = PirateChainWalletController.getInstance();
		if (walletController == null)
			return ZcashFamilyWalletController.WalletSyncStatus.disabled("Pirate Chain wallet is disabled");

		return walletController.getSyncStatusDetails(entropy58);
	}

	public static BitcoinyTransaction deserializeRawTransaction(String rawTransactionHex) throws TransformationException {
		return ZcashFamilyTransactionParser.deserializeRawTransaction(rawTransactionHex);
	}

}
