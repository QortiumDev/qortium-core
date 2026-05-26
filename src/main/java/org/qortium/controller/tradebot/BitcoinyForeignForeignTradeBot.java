package org.qortium.controller.tradebot;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.account.PublicKeyAccount;
import org.qortium.api.model.crosschain.TradeBotCreateRequest;
import org.qortium.asset.Asset;
import org.qortium.crosschain.ACCT;
import org.qortium.crosschain.AcctMode;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinyAddress;
import org.qortium.crosschain.BitcoinyForeignForeignACCTv1;
import org.qortium.crosschain.BitcoinyHTLC;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.TradeDirection;
import org.qortium.crypto.Crypto;
import org.qortium.data.at.ATData;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.data.crosschain.TradeBotData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.DeployAtTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.MessageTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.DeployAtTransactionTransformer;
import org.qortium.utils.NTP;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BitcoinyForeignForeignTradeBot implements AcctTradeBot {

	public static final int MIN_FOREIGN_FOREIGN_TRADE_TIMEOUT_MINUTES = 120;
	static final int FOREIGN_LOCKTIME_SAFETY_MARGIN_MINUTES = BitcoinyForeignForeignACCTv1.REFUND_LOCKTIME_SAFETY_MARGIN_MINUTES;
	static final int RESERVATION_TIMEOUT_MINUTES = 30;
	private static final long RESERVATION_TIMEOUT = RESERVATION_TIMEOUT_MINUTES * 60L * 1000L;
	private static final long MAX_AT_CONFIRMATION_PERIOD = 24 * 60 * 60 * 1000L;

	private static BitcoinyForeignForeignTradeBot instance;
	private Function<String, Bitcoiny> bitcoinyResolver = ForeignBlockchainRegistry::getRegisteredBitcoinyInstance;
	private final BitcoinyHtlcTradeSupport htlcTradeSupport = new BitcoinyHtlcTradeSupport();
	private Long messageFeeOverrideForTesting;
	private MessageSubmitter messageSubmitterForTesting;

	private final List<String> endStates = Arrays.asList(TradeStates.State.MAKER_DONE, TradeStates.State.MAKER_REFUNDED,
			TradeStates.State.TAKER_DONE, TradeStates.State.TAKER_REFUNDED).stream()
			.map(TradeStates.State::name)
			.collect(Collectors.toUnmodifiableList());

	private BitcoinyForeignForeignTradeBot() {
	}

	public static synchronized BitcoinyForeignForeignTradeBot getInstance() {
		if (instance == null)
			instance = new BitcoinyForeignForeignTradeBot();

		return instance;
	}

	public static void validateForeignForeignHtlcAmounts(Bitcoiny offeredBitcoiny, Long offeredForeignAmount,
			Bitcoiny requestedBitcoiny, Long requestedForeignAmount) throws DataException, ForeignBlockchainException {
		validateForeignForeignHtlcAmount(offeredBitcoiny, offeredForeignAmount, "offered");
		validateForeignForeignHtlcAmount(requestedBitcoiny, requestedForeignAmount, "requested");
	}

	private static void validateForeignForeignHtlcAmount(Bitcoiny bitcoiny, Long foreignAmount, String label)
			throws DataException, ForeignBlockchainException {
		if (bitcoiny == null)
			throw new DataException("Missing " + label + " foreign blockchain");

		if (foreignAmount == null || foreignAmount <= 0)
			throw new DataException("Foreign/foreign " + label + " amount must be positive");

		if (foreignAmount < bitcoiny.getMinimumOrderAmount())
			throw new DataException("Foreign/foreign " + label + " amount is below minimum order size");

		long p2shFee = bitcoiny.getP2shFee(NTP.getTime());
		if (p2shFee < 0)
			throw new DataException("Foreign/foreign " + label + " P2SH fee cannot be negative");

		try {
			Math.addExact(foreignAmount, p2shFee);
		} catch (ArithmeticException e) {
			throw new DataException("Foreign/foreign " + label + " HTLC amount is too large after fees", e);
		}
	}

	@Override
	public List<String> getEndStates() {
		return this.endStates;
	}

	void setBitcoinyResolverForTesting(Function<String, Bitcoiny> bitcoinyResolver) {
		this.bitcoinyResolver = bitcoinyResolver != null ? bitcoinyResolver : ForeignBlockchainRegistry::getRegisteredBitcoinyInstance;
	}

	void setHtlcStatusResolverForTesting(BitcoinyHtlcTradeSupport.HtlcStatusResolver htlcStatusResolver) {
		this.htlcTradeSupport.setHtlcStatusResolverForTesting(htlcStatusResolver);
	}

	void setHtlcSecretResolverForTesting(BitcoinyHtlcTradeSupport.HtlcSecretResolver htlcSecretResolver) {
		this.htlcTradeSupport.setHtlcSecretResolverForTesting(htlcSecretResolver);
	}

	void setMessageFeeOverrideForTesting(Long messageFeeOverrideForTesting) {
		this.messageFeeOverrideForTesting = messageFeeOverrideForTesting;
	}

	void setMessageSubmitterForTesting(MessageSubmitter messageSubmitterForTesting) {
		this.messageSubmitterForTesting = messageSubmitterForTesting;
	}

	void resetTestHooks() {
		this.bitcoinyResolver = ForeignBlockchainRegistry::getRegisteredBitcoinyInstance;
		this.htlcTradeSupport.resetTestHooks();
		this.messageFeeOverrideForTesting = null;
		this.messageSubmitterForTesting = null;
	}

	@FunctionalInterface
	interface MessageSubmitter {
		ValidationResult submit(Repository repository, MessageTransaction messageTransaction, PrivateKeyAccount sender) throws DataException;
	}

	@Override
	public byte[] createTrade(Repository repository, TradeBotCreateRequest tradeBotCreateRequest) throws DataException {
		ForeignBlockchainRegistry.Entry offeredForeignBlockchain = tradeBotCreateRequest.resolveOfferedForeignBlockchain();
		ForeignBlockchainRegistry.Entry requestedForeignBlockchain = tradeBotCreateRequest.resolveRequestedForeignBlockchain();
		if (!BitcoinyForeignForeignACCTv1.isSupportedBitcoinyPair(offeredForeignBlockchain, requestedForeignBlockchain))
			throw new DataException("Foreign/foreign trades require two supported Bitcoiny blockchains");

		if (offeredForeignBlockchain.name().equals(requestedForeignBlockchain.name()))
			throw new DataException("Foreign/foreign trades require two different blockchains");

		if (tradeBotCreateRequest.tradeTimeout < MIN_FOREIGN_FOREIGN_TRADE_TIMEOUT_MINUTES)
			throw new DataException(String.format("Foreign/foreign trade timeout must be at least %d minutes",
					MIN_FOREIGN_FOREIGN_TRADE_TIMEOUT_MINUTES));

		if (tradeBotCreateRequest.localAmount != 0 || tradeBotCreateRequest.fundingLocalAmount != 0)
			throw new DataException("Foreign/foreign trades cannot include local asset amounts");

		if (tradeBotCreateRequest.nativeFeeReserve < 0)
			throw new DataException("Native fee reserve cannot be negative");

		Bitcoiny offeredBitcoiny = getBitcoiny(offeredForeignBlockchain);
		Bitcoiny requestedBitcoiny = getBitcoiny(requestedForeignBlockchain);

		try {
			validateForeignForeignHtlcAmounts(offeredBitcoiny, tradeBotCreateRequest.offeredForeignAmount,
					requestedBitcoiny, tradeBotCreateRequest.requestedForeignAmount);
		} catch (ForeignBlockchainException e) {
			throw new DataException("Unable to validate foreign/foreign HTLC amounts", e);
		}

		if (!offeredBitcoiny.isValidWalletKey(tradeBotCreateRequest.offeredForeignKey))
			throw new DataException("Invalid offered foreign wallet key");

		BitcoinyAddress requestedReceivingAddress = parseP2pkhAddress(requestedBitcoiny,
				tradeBotCreateRequest.requestedForeignReceivingAddress, "requested receiving");
		BitcoinyAddress offeredRefundAddress = getUnusedP2pkhReceiveAddress(offeredBitcoiny,
				tradeBotCreateRequest.offeredForeignKey, "offered refund");

		byte[] tradePrivateKey = TradeBot.generateTradePrivateKey();
		byte[] tradeLocalPublicKey = TradeBot.deriveTradeLocalPublicKey(tradePrivateKey);
		byte[] tradeLocalPublicKeyHash = Crypto.hash160(tradeLocalPublicKey);
		String tradeLocalAddress = Crypto.toAddress(tradeLocalPublicKey);
		byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);
		byte[] secretA = TradeBot.generateSecret();
		byte[] hashOfSecretA = Crypto.hash160(secretA);

		PublicKeyAccount creator = new PublicKeyAccount(repository, tradeBotCreateRequest.creatorPublicKey);
		long timestamp = NTP.getTime();
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, creator.getPublicKey(), 0L, null);

		byte[] creationBytes = BitcoinyForeignForeignACCTv1.buildTradeAT(offeredForeignBlockchain, requestedForeignBlockchain,
				tradeLocalAddress, tradeForeignPublicKeyHash, tradeForeignPublicKeyHash, hashOfSecretA,
				tradeBotCreateRequest.offeredForeignAmount, tradeBotCreateRequest.requestedForeignAmount,
				tradeBotCreateRequest.tradeTimeout);

		String offeredCurrencyCode = offeredForeignBlockchain.getCurrencyCode();
		String requestedCurrencyCode = requestedForeignBlockchain.getCurrencyCode();
		DeployAtTransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData,
				String.format("%s/%s foreign ACCT", offeredCurrencyCode, requestedCurrencyCode),
				String.format("%s/%s foreign/foreign cross-chain trade", offeredCurrencyCode, requestedCurrencyCode),
				"ACCT",
				String.format("ACCT foreign-foreign %s %s", offeredCurrencyCode, requestedCurrencyCode),
				creationBytes, 0L, Asset.NATIVE, tradeBotCreateRequest.nativeFeeReserve);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		deployAtTransactionData.setFee(deployAtTransaction.calcRecommendedFee());
		DeployAtTransaction.ensureATAddress(deployAtTransactionData);
		String atAddress = deployAtTransactionData.getAtAddress();

		TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, BitcoinyForeignForeignACCTv1.NAME,
				TradeStates.State.MAKER_WAITING_FOR_AT_CONFIRM.name(), TradeStates.State.MAKER_WAITING_FOR_AT_CONFIRM.value,
				creator.getAddress(), atAddress, timestamp, Asset.NATIVE, 0L,
				tradeLocalPublicKey, tradeLocalPublicKeyHash, tradeLocalAddress, secretA, hashOfSecretA,
				offeredForeignBlockchain.name(), tradeForeignPublicKey, tradeForeignPublicKeyHash,
				tradeBotCreateRequest.offeredForeignAmount, tradeBotCreateRequest.offeredForeignKey,
				null, null, requestedReceivingAddress.getPayload());
		populateForeignForeignFields(tradeBotData, offeredForeignBlockchain, requestedForeignBlockchain,
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				tradeBotCreateRequest.offeredForeignAmount, tradeBotCreateRequest.offeredForeignKey, offeredRefundAddress.getPayload(),
				tradeBotCreateRequest.requestedForeignAmount, null, requestedReceivingAddress.getPayload());

		TradeBot.updateTradeBotState(repository, tradeBotData,
				() -> String.format("Built foreign/foreign AT %s. Waiting for deployment", atAddress));
		TradeBot.backupTradeBotData(repository, null);

		try {
			return DeployAtTransactionTransformer.toBytes(deployAtTransactionData);
		} catch (TransformationException e) {
			throw new DataException("Failed to transform DEPLOY_AT transaction?", e);
		}
	}

	@Override
	public ResponseResult startResponse(Repository repository, ATData atData, ACCT acct,
			CrossChainTradeData crossChainTradeData, String foreignKey, String receivingAddress) throws DataException {
		if (!(acct instanceof BitcoinyForeignForeignACCTv1)
				|| crossChainTradeData == null
				|| crossChainTradeData.tradeDirection != TradeDirection.SELL_FOREIGN_FOR_FOREIGN)
			return ResponseResult.INVALID_CRITERIA;

		return startResponse(repository, atData, crossChainTradeData, foreignKey, receivingAddress);
	}

	public ResponseResult startResponse(Repository repository, ATData atData, CrossChainTradeData tradeData,
			String requestedForeignKey, String offeredForeignReceivingAddress) throws DataException {
		if (atData == null || tradeData == null || !atData.getATAddress().equals(tradeData.atAddress))
			return ResponseResult.INVALID_CRITERIA;

		if (tradeData.mode != AcctMode.OFFERING)
			throw new DataException("Foreign/foreign trade is not open for reservation");

		ForeignBlockchainRegistry.Entry offeredForeignBlockchain = requireBitcoinyEntry(tradeData.offeredForeignBlockchain,
				"offeredForeignBlockchain");
		ForeignBlockchainRegistry.Entry requestedForeignBlockchain = requireBitcoinyEntry(tradeData.requestedForeignBlockchain,
				"requestedForeignBlockchain");
		if (offeredForeignBlockchain.name().equals(requestedForeignBlockchain.name()))
			throw new DataException("Foreign/foreign trades require two different blockchains");

		Bitcoiny offeredBitcoiny = getBitcoiny(offeredForeignBlockchain);
		Bitcoiny requestedBitcoiny = getBitcoiny(requestedForeignBlockchain);

		if (!requestedBitcoiny.isValidWalletKey(requestedForeignKey))
			throw new DataException("Invalid requested foreign wallet key");

		BitcoinyAddress offeredReceivingAddress = parseP2pkhAddress(offeredBitcoiny, offeredForeignReceivingAddress,
				"offered receiving");
		BitcoinyAddress requestedRefundAddress = getUnusedP2pkhReceiveAddress(requestedBitcoiny, requestedForeignKey,
				"requested refund");

		byte[] tradePrivateKey = TradeBot.generateTradePrivateKey();
		byte[] tradeLocalPublicKey = TradeBot.deriveTradeLocalPublicKey(tradePrivateKey);
		byte[] tradeLocalPublicKeyHash = Crypto.hash160(tradeLocalPublicKey);
		String tradeLocalAddress = Crypto.toAddress(tradeLocalPublicKey);
		byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);

		long now = NTP.getTime();
		TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, BitcoinyForeignForeignACCTv1.NAME,
				TradeStates.State.TAKER_WAITING_FOR_FOREIGN_LOCK.name(), TradeStates.State.TAKER_WAITING_FOR_FOREIGN_LOCK.value,
				tradeLocalAddress, tradeData.atAddress, now, Asset.NATIVE, 0L,
				tradeLocalPublicKey, tradeLocalPublicKeyHash, tradeLocalAddress,
				null, tradeData.hashOfSecretA, requestedForeignBlockchain.name(), tradeForeignPublicKey, tradeForeignPublicKeyHash,
				tradeData.requestedForeignAmount, requestedForeignKey, null, null, offeredReceivingAddress.getPayload());
		populateForeignForeignFields(tradeBotData, offeredForeignBlockchain, requestedForeignBlockchain,
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				tradeData.offeredForeignAmount, null, offeredReceivingAddress.getPayload(),
				tradeData.requestedForeignAmount, requestedForeignKey, requestedRefundAddress.getPayload());

		byte[] messageData = BitcoinyForeignForeignACCTv1.buildReserveMessage(tradeForeignPublicKeyHash, tradeForeignPublicKeyHash);
		if (!sendMessage(repository, tradeBotData, tradeData.atAddress, messageData))
			return ResponseResult.NETWORK_ISSUE;

		TradeBot.updateTradeBotState(repository, tradeBotData,
				() -> String.format("Built foreign/foreign reservation for AT %s. Waiting for maker foreign HTLC",
						tradeData.atAddress));
		TradeBot.backupTradeBotData(repository, null);

		return ResponseResult.OK;
	}

	@Override
	public boolean canDelete(Repository repository, TradeBotData tradeBotData) throws DataException {
		TradeStates.State tradeBotState = TradeStates.State.valueOf(tradeBotData.getStateValue());
		return tradeBotState == null || !repository.getATRepository().exists(tradeBotData.getAtAddress())
				|| this.endStates.contains(tradeBotData.getState());
	}

	@Override
	public void progress(Repository repository, TradeBotData tradeBotData) throws DataException, ForeignBlockchainException {
		TradeStates.State tradeBotState = TradeStates.State.valueOf(tradeBotData.getStateValue());
		if (tradeBotState == null)
			return;

		ATData atData = tradeBotState.requiresAtData ? repository.getATRepository().fromATAddress(tradeBotData.getAtAddress()) : null;
		CrossChainTradeData tradeData = null;
		if (atData != null && tradeBotState.requiresTradeData)
			tradeData = BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);

		switch (tradeBotState) {
			case MAKER_WAITING_FOR_AT_CONFIRM:
				handleMakerWaitingForAtConfirm(repository, tradeBotData);
				break;

			case MAKER_WAITING_FOR_TAKER_MESSAGE:
				handleMakerWaitingForReservation(repository, tradeBotData, atData, tradeData);
				break;

			case MAKER_WAITING_FOR_TAKER_HTLC:
				handleMakerWaitingForTakerHtlc(repository, tradeBotData, atData, tradeData);
				break;

			case MAKER_WAITING_FOR_AT_REDEEM:
				handleMakerWaitingForAtRedeem(repository, tradeBotData, atData, tradeData);
				break;

			case TAKER_WAITING_FOR_FOREIGN_LOCK:
				handleTakerWaitingForForeignLock(repository, tradeBotData, atData, tradeData);
				break;

			case TAKER_WAITING_FOR_MAKER_REDEEM:
				handleTakerWaitingForMakerRedeem(repository, tradeBotData, atData, tradeData);
				break;

			default:
				break;
		}
	}

	private void handleMakerWaitingForAtConfirm(Repository repository, TradeBotData tradeBotData) throws DataException {
		if (!repository.getATRepository().exists(tradeBotData.getAtAddress())) {
			if (NTP.getTime() - tradeBotData.getTimestamp() <= MAX_AT_CONFIRMATION_PERIOD)
				return;

			repository.getCrossChainRepository().delete(tradeBotData.getTradePrivateKey());
			repository.saveChanges();
			tradeBotData.setState(TradeStates.State.MAKER_REFUNDED.name());
			tradeBotData.setStateValue(TradeStates.State.MAKER_REFUNDED.value);
			TradeBot.notifyStateChange(tradeBotData);
			return;
		}

		TradeBot.updateTradeBotState(repository, tradeBotData, TradeStates.State.MAKER_WAITING_FOR_TAKER_MESSAGE,
				() -> String.format("Foreign/foreign AT %s confirmed ready. Waiting for taker reservation",
						tradeBotData.getAtAddress()));
	}

	private void handleMakerWaitingForReservation(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		if (atData == null || tradeData == null)
			return;

		if (atData.getIsFinished()) {
			updateMakerFinishedState(repository, tradeBotData, tradeData);
			return;
		}

		if (tradeData.mode == AcctMode.FOREIGN_LOCKED || tradeData.mode == AcctMode.TRADING) {
			TradeBot.updateTradeBotState(repository, tradeBotData, TradeStates.State.MAKER_WAITING_FOR_TAKER_HTLC,
					() -> String.format("Foreign/foreign AT %s has maker HTLC declaration. Waiting for taker HTLC",
							tradeBotData.getAtAddress()));
			return;
		}

		if (tradeData.mode != AcctMode.RESERVED)
			return;

		int lockTimeA = ensureMakerLockTime(repository, tradeBotData, tradeData);
		tradeData.lockTimeA = lockTimeA;

		Bitcoiny offeredBitcoiny = getBitcoiny(requireBitcoinyEntry(tradeBotData.getOfferedForeignBlockchain(),
				"offeredForeignBlockchain"));
		String p2shAddress = deriveMakerOfferedP2shAddress(offeredBitcoiny, tradeData);
		long minimumAmount = BitcoinyHtlcTradeSupport.minimumHtlcAmount(offeredBitcoiny, tradeData.offeredForeignAmount);
		BitcoinyHTLC.Status htlcStatus = determineHtlcStatus(offeredBitcoiny, p2shAddress, minimumAmount);

		long now = NTP.getTime();
		if (!hasSufficientTimeBeforeMakerLock(now, lockTimeA)) {
			sendCancelMessage(repository, tradeBotData);
			return;
		}

		if (hasReservationTimedOut(tradeBotData, now) && htlcStatus != BitcoinyHTLC.Status.FUNDED) {
			sendCancelMessage(repository, tradeBotData);
			return;
		}

		if (htlcStatus == BitcoinyHTLC.Status.UNFUNDED) {
			this.htlcTradeSupport.fundIfUnfunded(offeredBitcoiny, tradeBotData.getOfferedForeignKey(), p2shAddress, minimumAmount);
			return;
		}

		if (htlcStatus != BitcoinyHTLC.Status.FUNDED)
			return;

		byte[] messageData = BitcoinyForeignForeignACCTv1.buildMakerHtlcMessage(lockTimeA);
		if (!sendMessage(repository, tradeBotData, tradeBotData.getAtAddress(), messageData))
			return;

		TradeBot.updateTradeBotState(repository, tradeBotData, TradeStates.State.MAKER_WAITING_FOR_TAKER_HTLC,
				() -> String.format("Funded foreign/foreign offered-chain P2SH %s. Waiting for taker HTLC", p2shAddress));
	}

	private void handleMakerWaitingForTakerHtlc(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		if (atData == null || tradeData == null)
			return;

		if (atData.getIsFinished()) {
			updateMakerFinishedState(repository, tradeBotData, tradeData);
			return;
		}

		if (!isMakerTradeForUs(tradeBotData, tradeData))
			return;

		if (tradeData.mode == AcctMode.FOREIGN_LOCKED) {
			if (!refundMakerOfferedHtlcIfExpired(tradeBotData, tradeData))
				return;

			if (!sendCancelMessage(repository, tradeBotData))
				return;

			TradeBot.updateTradeBotState(repository, tradeBotData, TradeStates.State.MAKER_REFUNDED,
					() -> String.format("Refunded foreign/foreign offered-chain P2SH for AT %s",
							tradeBotData.getAtAddress()));
			return;
		}

		if (tradeData.mode != AcctMode.TRADING)
			return;

		handleMakerRequestedHtlc(repository, tradeBotData, tradeData);
	}

	private void handleMakerWaitingForAtRedeem(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		if (atData == null || tradeData == null)
			return;

		if (atData.getIsFinished()) {
			updateMakerFinishedState(repository, tradeBotData, tradeData);
			return;
		}

		if (!isMakerTradeForUs(tradeBotData, tradeData) || tradeData.mode != AcctMode.TRADING)
			return;

		revealMakerSecretIfRequestedHtlcRedeemed(repository, tradeBotData, tradeData);
	}

	private void handleTakerWaitingForForeignLock(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		if (atData == null || tradeData == null)
			return;

		if (atData.getIsFinished()) {
			updateTakerFinishedState(repository, tradeBotData, tradeData);
			return;
		}

		if (!isReservedForUs(tradeBotData, tradeData))
			return;

		if (tradeData.mode == AcctMode.TRADING) {
			TradeBot.updateTradeBotState(repository, tradeBotData, TradeStates.State.TAKER_WAITING_FOR_MAKER_REDEEM,
					() -> String.format("Foreign/foreign AT %s has taker HTLC declaration. Waiting for maker redeem",
							tradeBotData.getAtAddress()));
			return;
		}

		if (tradeData.mode != AcctMode.FOREIGN_LOCKED)
			return;

		Bitcoiny offeredBitcoiny = getBitcoiny(requireBitcoinyEntry(tradeData.offeredForeignBlockchain,
				"offeredForeignBlockchain"));
		String offeredP2shAddress = deriveMakerOfferedP2shAddress(offeredBitcoiny, tradeData);
		long offeredMinimumAmount = BitcoinyHtlcTradeSupport.minimumHtlcAmount(offeredBitcoiny, tradeData.offeredForeignAmount);
		if (determineHtlcStatus(offeredBitcoiny, offeredP2shAddress, offeredMinimumAmount) != BitcoinyHTLC.Status.FUNDED)
			return;

		if (tradeData.lockTimeA == null)
			return;

		int lockTimeB = tradeBotData.getLockTimeB() != null
				? tradeBotData.getLockTimeB()
				: calcTakerLockTime(tradeData.lockTimeA);
		if (!hasSufficientTimeBeforeTakerLock(NTP.getTime(), lockTimeB)
				|| !BitcoinyHtlcTradeSupport.hasLaterRefundSafetyMargin(lockTimeB, tradeData.lockTimeA,
				FOREIGN_LOCKTIME_SAFETY_MARGIN_MINUTES))
			return;

		lockTimeB = ensureTakerLockTime(repository, tradeBotData, tradeData);
		tradeData.lockTimeB = lockTimeB;

		Bitcoiny requestedBitcoiny = getBitcoiny(requireBitcoinyEntry(tradeData.requestedForeignBlockchain,
				"requestedForeignBlockchain"));
		String requestedP2shAddress = deriveTakerRequestedP2shAddress(requestedBitcoiny, tradeData);
		long requestedMinimumAmount = BitcoinyHtlcTradeSupport.minimumHtlcAmount(requestedBitcoiny, tradeData.requestedForeignAmount);
		BitcoinyHTLC.Status requestedHtlcStatus = determineHtlcStatus(requestedBitcoiny, requestedP2shAddress, requestedMinimumAmount);

		if (requestedHtlcStatus == BitcoinyHTLC.Status.UNFUNDED) {
			this.htlcTradeSupport.fundIfUnfunded(requestedBitcoiny, tradeBotData.getRequestedForeignKey(),
					requestedP2shAddress, requestedMinimumAmount);
			return;
		}

		if (requestedHtlcStatus != BitcoinyHTLC.Status.FUNDED)
			return;

		byte[] messageData = BitcoinyForeignForeignACCTv1.buildTakerHtlcMessage(lockTimeB);
		if (!sendMessage(repository, tradeBotData, tradeBotData.getAtAddress(), messageData))
			return;

		TradeBot.updateTradeBotState(repository, tradeBotData, TradeStates.State.TAKER_WAITING_FOR_MAKER_REDEEM,
				() -> String.format("Funded foreign/foreign requested-chain P2SH %s. Waiting for maker redeem",
						requestedP2shAddress));
	}

	private void handleTakerWaitingForMakerRedeem(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		if (atData == null || tradeData == null)
			return;

		if (!isReservedForUs(tradeBotData, tradeData))
			return;

		byte[] secret = findMakerSecret(repository, tradeData);
		if (secret != null && redeemTakerOfferedHtlcIfReady(tradeBotData, tradeData, secret)) {
			TradeBot.updateTradeBotState(repository, tradeBotData, TradeStates.State.TAKER_DONE,
					() -> String.format("Redeemed foreign/foreign offered-chain P2SH for AT %s",
							tradeBotData.getAtAddress()));
			return;
		}

		if (refundTakerRequestedHtlcIfExpired(tradeBotData, tradeData)) {
			TradeBot.updateTradeBotState(repository, tradeBotData, TradeStates.State.TAKER_REFUNDED,
					() -> String.format("Refunded foreign/foreign requested-chain P2SH for AT %s",
							tradeBotData.getAtAddress()));
			return;
		}

		if (atData.getIsFinished() && tradeData.mode != AcctMode.REDEEMED)
			updateTakerFinishedState(repository, tradeBotData, tradeData);
	}

	private void updateMakerFinishedState(Repository repository, TradeBotData tradeBotData, CrossChainTradeData tradeData)
			throws DataException, ForeignBlockchainException {
		if (tradeData.mode == AcctMode.REDEEMED) {
			TradeBot.updateTradeBotState(repository, tradeBotData, TradeStates.State.MAKER_DONE,
					() -> String.format("Foreign/foreign AT %s redeemed", tradeBotData.getAtAddress()));
			return;
		}

		if (!refundMakerOfferedHtlcIfExpired(tradeBotData, tradeData))
			return;

		TradeBot.updateTradeBotState(repository, tradeBotData, TradeStates.State.MAKER_REFUNDED,
				() -> String.format("Foreign/foreign AT %s finished without redeem", tradeBotData.getAtAddress()));
	}

	private void updateTakerFinishedState(Repository repository, TradeBotData tradeBotData, CrossChainTradeData tradeData)
			throws DataException, ForeignBlockchainException {
		if (tradeData.mode == AcctMode.REDEEMED) {
			TradeBot.updateTradeBotState(repository, tradeBotData, TradeStates.State.TAKER_DONE,
					() -> String.format("Foreign/foreign AT %s finished", tradeBotData.getAtAddress()));
			return;
		}

		if (!refundTakerRequestedHtlcIfExpired(tradeBotData, tradeData))
			return;

		TradeBot.updateTradeBotState(repository, tradeBotData, TradeStates.State.TAKER_REFUNDED,
				() -> String.format("Foreign/foreign AT %s finished", tradeBotData.getAtAddress()));
	}

	private void handleMakerRequestedHtlc(Repository repository, TradeBotData tradeBotData, CrossChainTradeData tradeData)
			throws DataException, ForeignBlockchainException {
		if (tradeData.lockTimeA == null || tradeData.lockTimeB == null)
			return;

		Bitcoiny requestedBitcoiny = getBitcoiny(requireBitcoinyEntry(tradeData.requestedForeignBlockchain,
				"requestedForeignBlockchain"));
		String requestedP2shAddress = deriveTakerRequestedP2shAddress(requestedBitcoiny, tradeData);
		long requestedMinimumAmount = BitcoinyHtlcTradeSupport.minimumHtlcAmount(requestedBitcoiny, tradeData.requestedForeignAmount);
		BitcoinyHTLC.Status requestedHtlcStatus = determineHtlcStatus(requestedBitcoiny, requestedP2shAddress, requestedMinimumAmount);

		switch (requestedHtlcStatus) {
			case FUNDED:
				if (!hasSufficientTimeBeforeTakerLock(NTP.getTime(), tradeData.lockTimeB)
						|| !BitcoinyHtlcTradeSupport.hasLaterRefundSafetyMargin(tradeData.lockTimeB, tradeData.lockTimeA,
						FOREIGN_LOCKTIME_SAFETY_MARGIN_MINUTES))
					return;

				if (!redeemMakerRequestedHtlcIfReady(requestedBitcoiny, requestedP2shAddress, requestedMinimumAmount,
						tradeBotData, tradeData))
					return;

				TradeBot.updateTradeBotState(repository, tradeBotData, TradeStates.State.MAKER_WAITING_FOR_AT_REDEEM,
						() -> String.format("Redeeming foreign/foreign requested-chain P2SH %s. Waiting for confirmed redeem",
								requestedP2shAddress));
				return;

			case REDEEMED:
				if (!revealMakerSecret(repository, tradeBotData))
					return;

				TradeBot.updateTradeBotState(repository, tradeBotData, TradeStates.State.MAKER_WAITING_FOR_AT_REDEEM,
						() -> String.format("Requested-chain P2SH %s redeemed. Revealing secret to AT %s",
								requestedP2shAddress, tradeBotData.getAtAddress()));
				return;

			default:
				return;
		}
	}

	private boolean redeemMakerRequestedHtlcIfReady(Bitcoiny requestedBitcoiny, String requestedP2shAddress,
			long requestedMinimumAmount, TradeBotData tradeBotData, CrossChainTradeData tradeData)
			throws ForeignBlockchainException {
		return this.htlcTradeSupport.redeemIfFunded(requestedBitcoiny, requestedP2shAddress,
				requestedMinimumAmount, tradeData.requestedForeignAmount, tradeBotData.getTradePrivateKey(),
				buildTakerRequestedRedeemScript(tradeData), tradeBotData.getSecret(),
				tradeBotData.getRequestedForeignReceivingAccountInfo());
	}

	private boolean revealMakerSecretIfRequestedHtlcRedeemed(Repository repository, TradeBotData tradeBotData,
			CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		Bitcoiny requestedBitcoiny = getBitcoiny(requireBitcoinyEntry(tradeData.requestedForeignBlockchain,
				"requestedForeignBlockchain"));
		String requestedP2shAddress = deriveTakerRequestedP2shAddress(requestedBitcoiny, tradeData);
		long requestedMinimumAmount = BitcoinyHtlcTradeSupport.minimumHtlcAmount(requestedBitcoiny, tradeData.requestedForeignAmount);
		BitcoinyHTLC.Status requestedHtlcStatus = determineHtlcStatus(requestedBitcoiny, requestedP2shAddress, requestedMinimumAmount);
		if (requestedHtlcStatus != BitcoinyHTLC.Status.REDEEMED)
			return false;

		return revealMakerSecret(repository, tradeBotData);
	}

	private boolean revealMakerSecret(Repository repository, TradeBotData tradeBotData) throws DataException {
		byte[] messageData = BitcoinyForeignForeignACCTv1.buildSecretRevealMessage(tradeBotData.getSecret());
		return sendMessage(repository, tradeBotData, tradeBotData.getAtAddress(), messageData);
	}

	private byte[] findMakerSecret(Repository repository, CrossChainTradeData tradeData)
			throws DataException, ForeignBlockchainException {
		byte[] secret = BitcoinyForeignForeignACCTv1.getInstance().findSecretA(repository, tradeData);
		if (secret != null)
			return secret;

		if (tradeData.lockTimeB == null)
			return null;

		Bitcoiny requestedBitcoiny = getBitcoiny(requireBitcoinyEntry(tradeData.requestedForeignBlockchain,
				"requestedForeignBlockchain"));
		String requestedP2shAddress = deriveTakerRequestedP2shAddress(requestedBitcoiny, tradeData);
		secret = this.htlcTradeSupport.findHtlcSecret(requestedBitcoiny, requestedP2shAddress);
		if (secret == null || !Arrays.equals(Crypto.hash160(secret), tradeData.hashOfSecretA))
			return null;

		return secret;
	}

	private boolean redeemTakerOfferedHtlcIfReady(TradeBotData tradeBotData, CrossChainTradeData tradeData, byte[] secret)
			throws DataException, ForeignBlockchainException {
		if (tradeData.lockTimeA == null)
			return false;

		Bitcoiny offeredBitcoiny = getBitcoiny(requireBitcoinyEntry(tradeData.offeredForeignBlockchain,
				"offeredForeignBlockchain"));
		String offeredP2shAddress = deriveMakerOfferedP2shAddress(offeredBitcoiny, tradeData);
		long offeredMinimumAmount = BitcoinyHtlcTradeSupport.minimumHtlcAmount(offeredBitcoiny, tradeData.offeredForeignAmount);
		return this.htlcTradeSupport.redeemIfFunded(offeredBitcoiny, offeredP2shAddress,
				offeredMinimumAmount, tradeData.offeredForeignAmount, tradeBotData.getTradePrivateKey(),
				buildMakerOfferedRedeemScript(tradeData), secret, tradeBotData.getOfferedForeignReceivingAccountInfo());
	}

	private boolean refundMakerOfferedHtlcIfExpired(TradeBotData tradeBotData, CrossChainTradeData tradeData)
			throws DataException, ForeignBlockchainException {
		Integer lockTimeA = tradeData.lockTimeA != null ? tradeData.lockTimeA : tradeBotData.getLockTimeA();
		if (lockTimeA == null || tradeData.partnerOfferedForeignPKH == null)
			return true;

		tradeData.lockTimeA = lockTimeA;

		Bitcoiny offeredBitcoiny = getBitcoiny(requireBitcoinyEntry(tradeData.offeredForeignBlockchain,
				"offeredForeignBlockchain"));
		String offeredP2shAddress = deriveMakerOfferedP2shAddress(offeredBitcoiny, tradeData);
		long offeredMinimumAmount = BitcoinyHtlcTradeSupport.minimumHtlcAmount(offeredBitcoiny, tradeData.offeredForeignAmount);
		return this.htlcTradeSupport.refundIfExpired(offeredBitcoiny, offeredP2shAddress,
				offeredMinimumAmount, tradeData.offeredForeignAmount, tradeBotData.getTradePrivateKey(),
				buildMakerOfferedRedeemScript(tradeData), lockTimeA,
				tradeBotData.getOfferedForeignReceivingAccountInfo());
	}

	private boolean refundTakerRequestedHtlcIfExpired(TradeBotData tradeBotData, CrossChainTradeData tradeData)
			throws DataException, ForeignBlockchainException {
		Integer lockTimeB = tradeData.lockTimeB != null ? tradeData.lockTimeB : tradeBotData.getLockTimeB();
		if (lockTimeB == null || tradeData.partnerRequestedForeignPKH == null)
			return true;

		tradeData.lockTimeB = lockTimeB;

		Bitcoiny requestedBitcoiny = getBitcoiny(requireBitcoinyEntry(tradeData.requestedForeignBlockchain,
				"requestedForeignBlockchain"));
		String requestedP2shAddress = deriveTakerRequestedP2shAddress(requestedBitcoiny, tradeData);
		long requestedMinimumAmount = BitcoinyHtlcTradeSupport.minimumHtlcAmount(requestedBitcoiny, tradeData.requestedForeignAmount);
		return this.htlcTradeSupport.refundIfExpired(requestedBitcoiny, requestedP2shAddress,
				requestedMinimumAmount, tradeData.requestedForeignAmount, tradeBotData.getTradePrivateKey(),
				buildTakerRequestedRedeemScript(tradeData), lockTimeB,
				tradeBotData.getRequestedForeignReceivingAccountInfo());
	}

	private int ensureMakerLockTime(Repository repository, TradeBotData tradeBotData, CrossChainTradeData tradeData)
			throws DataException {
		if (tradeBotData.getLockTimeA() != null)
			return tradeBotData.getLockTimeA();

		long now = NTP.getTime();
		int lockTimeA = (int) (now / 1000L + tradeData.tradeTimeout * 60L);
		tradeBotData.setLockTimeA(lockTimeA);
		tradeBotData.setTimestamp(now);
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();
		TradeBot.backupTradeBotData(repository, null);
		return lockTimeA;
	}

	private int ensureTakerLockTime(Repository repository, TradeBotData tradeBotData, CrossChainTradeData tradeData)
			throws DataException {
		if (tradeBotData.getLockTimeB() != null)
			return tradeBotData.getLockTimeB();

		if (tradeData.lockTimeA == null)
			throw new DataException("Missing maker locktime for foreign/foreign trade");

		int lockTimeB = calcTakerLockTime(tradeData.lockTimeA);
		tradeBotData.setLockTimeA(tradeData.lockTimeA);
		tradeBotData.setLockTimeB(lockTimeB);
		tradeBotData.setTimestamp(NTP.getTime());
		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();
		TradeBot.backupTradeBotData(repository, null);
		return lockTimeB;
	}

	private void prepareMessageForSigning(MessageTransaction messageTransaction) throws DataException {
		if (this.messageFeeOverrideForTesting != null) {
			messageTransaction.getTransactionData().setFee(this.messageFeeOverrideForTesting);
			return;
		}

		messageTransaction.computeNonce();
	}

	private boolean sendMessage(Repository repository, TradeBotData tradeBotData, String recipient, byte[] messageData) throws DataException {
		if (repository.getMessageRepository().exists(tradeBotData.getTradeLocalPublicKey(), recipient, messageData))
			return true;

		PrivateKeyAccount sender = new PrivateKeyAccount(repository, tradeBotData.getTradePrivateKey());
		MessageTransaction messageTransaction = MessageTransaction.build(repository, sender, Group.NO_GROUP, recipient, messageData, false, false);

		if (this.messageSubmitterForTesting != null)
			return this.messageSubmitterForTesting.submit(repository, messageTransaction, sender) == ValidationResult.OK;

		prepareMessageForSigning(messageTransaction);
		messageTransaction.sign(sender);
		repository.discardChanges();
		return messageTransaction.isSignatureValid() && messageTransaction.importAsUnconfirmed() == ValidationResult.OK;
	}

	private boolean sendCancelMessage(Repository repository, TradeBotData tradeBotData) throws DataException {
		byte[] messageData = BitcoinyForeignForeignACCTv1.getInstance().buildCancelMessage(tradeBotData.getTradeLocalAddress());
		return sendMessage(repository, tradeBotData, tradeBotData.getAtAddress(), messageData);
	}

	private Bitcoiny getBitcoiny(ForeignBlockchainRegistry.Entry foreignBlockchain) throws DataException {
		if (foreignBlockchain == null || !foreignBlockchain.isBitcoiny())
			throw new DataException("Unsupported Bitcoiny blockchain");

		Bitcoiny bitcoiny = this.bitcoinyResolver.apply(foreignBlockchain.name());
		if (bitcoiny != null)
			return bitcoiny;

		bitcoiny = foreignBlockchain.getBitcoinyInstance();
		if (bitcoiny == null)
			throw new DataException("Unsupported Bitcoiny blockchain");

		return bitcoiny;
	}

	private static ForeignBlockchainRegistry.Entry requireBitcoinyEntry(String blockchainName, String fieldName) throws DataException {
		ForeignBlockchainRegistry.Entry entry = ForeignBlockchainRegistry.fromString(blockchainName);
		if (entry == null || !entry.isBitcoiny())
			throw new DataException("Unsupported Bitcoiny blockchain for " + fieldName + ": " + blockchainName);

		return entry;
	}

	private static BitcoinyAddress parseP2pkhAddress(Bitcoiny bitcoiny, String address, String fieldName) throws DataException {
		BitcoinyAddress bitcoinyAddress;
		try {
			bitcoinyAddress = BitcoinyAddress.fromString(bitcoiny.getNetworkParameters(), address);
		} catch (IllegalArgumentException e) {
			throw new DataException(String.format("Unsupported %s %s address: %s",
					bitcoiny.getCurrencyCode(), fieldName, address));
		}

		if (!bitcoinyAddress.isP2PKH())
			throw new DataException(String.format("Unsupported %s %s address: %s",
					bitcoiny.getCurrencyCode(), fieldName, address));

		return bitcoinyAddress;
	}

	private static BitcoinyAddress getUnusedP2pkhReceiveAddress(Bitcoiny bitcoiny, String walletKey, String fieldName)
			throws DataException {
		try {
			return parseP2pkhAddress(bitcoiny, bitcoiny.getUnusedReceiveAddress(walletKey), fieldName);
		} catch (ForeignBlockchainException e) {
			throw new DataException(String.format("Unable to derive %s %s address", bitcoiny.getCurrencyCode(), fieldName), e);
		}
	}

	private BitcoinyHTLC.Status determineHtlcStatus(Bitcoiny bitcoiny, String p2shAddress, long minimumAmount)
			throws ForeignBlockchainException {
		return this.htlcTradeSupport.determineHtlcStatus(bitcoiny, p2shAddress, minimumAmount);
	}

	private static String deriveMakerOfferedP2shAddress(Bitcoiny bitcoiny, CrossChainTradeData tradeData) {
		return BitcoinyHtlcTradeSupport.deriveP2shAddress(bitcoiny, tradeData.creatorOfferedForeignPKH,
				tradeData.lockTimeA, tradeData.partnerOfferedForeignPKH, tradeData.hashOfSecretA);
	}

	private static byte[] buildMakerOfferedRedeemScript(CrossChainTradeData tradeData) {
		return BitcoinyHtlcTradeSupport.buildRedeemScript(tradeData.creatorOfferedForeignPKH,
				tradeData.lockTimeA, tradeData.partnerOfferedForeignPKH, tradeData.hashOfSecretA);
	}

	private static String deriveTakerRequestedP2shAddress(Bitcoiny bitcoiny, CrossChainTradeData tradeData) {
		return BitcoinyHtlcTradeSupport.deriveP2shAddress(bitcoiny, tradeData.partnerRequestedForeignPKH,
				tradeData.lockTimeB, tradeData.creatorRequestedForeignPKH, tradeData.hashOfSecretA);
	}

	private static byte[] buildTakerRequestedRedeemScript(CrossChainTradeData tradeData) {
		return BitcoinyHtlcTradeSupport.buildRedeemScript(tradeData.partnerRequestedForeignPKH,
				tradeData.lockTimeB, tradeData.creatorRequestedForeignPKH, tradeData.hashOfSecretA);
	}

	private static boolean hasSufficientTimeBeforeMakerLock(long now, Integer lockTimeA) {
		return BitcoinyHtlcTradeSupport.hasSufficientTimeBeforeLock(now, 0L,
				FOREIGN_LOCKTIME_SAFETY_MARGIN_MINUTES, lockTimeA);
	}

	private static boolean hasSufficientTimeBeforeTakerLock(long now, Integer lockTimeB) {
		return BitcoinyHtlcTradeSupport.hasSufficientTimeBeforeLock(now, 0L,
				FOREIGN_LOCKTIME_SAFETY_MARGIN_MINUTES, lockTimeB);
	}

	private static boolean hasReservationTimedOut(TradeBotData tradeBotData, long now) {
		return now - tradeBotData.getTimestamp() > RESERVATION_TIMEOUT;
	}

	static int calcTakerLockTime(int makerLockTime) {
		return makerLockTime - FOREIGN_LOCKTIME_SAFETY_MARGIN_MINUTES * 60 - 1;
	}

	private static boolean isReservedForUs(TradeBotData tradeBotData, CrossChainTradeData tradeData) {
		return tradeBotData.getTradeLocalAddress().equals(tradeData.partnerAddress)
				&& Arrays.equals(tradeBotData.getOfferedTradeForeignPublicKeyHash(), tradeData.partnerOfferedForeignPKH)
				&& Arrays.equals(tradeBotData.getRequestedTradeForeignPublicKeyHash(), tradeData.partnerRequestedForeignPKH)
				&& Arrays.equals(tradeBotData.getHashOfSecret(), tradeData.hashOfSecretA);
	}

	private static boolean isMakerTradeForUs(TradeBotData tradeBotData, CrossChainTradeData tradeData) {
		return tradeBotData.getTradeLocalAddress().equals(tradeData.creatorTradeAddress)
				&& Arrays.equals(tradeBotData.getOfferedTradeForeignPublicKeyHash(), tradeData.creatorOfferedForeignPKH)
				&& Arrays.equals(tradeBotData.getRequestedTradeForeignPublicKeyHash(), tradeData.creatorRequestedForeignPKH)
				&& Arrays.equals(tradeBotData.getHashOfSecret(), tradeData.hashOfSecretA);
	}

	private static void populateForeignForeignFields(TradeBotData tradeBotData,
			ForeignBlockchainRegistry.Entry offeredForeignBlockchain, ForeignBlockchainRegistry.Entry requestedForeignBlockchain,
			byte[] tradeForeignPublicKey, byte[] tradeForeignPublicKeyHash,
			long offeredForeignAmount, String offeredForeignKey, byte[] offeredReceivingAccountInfo,
			long requestedForeignAmount, String requestedForeignKey, byte[] requestedReceivingAccountInfo) {
		tradeBotData.setOfferedForeignBlockchain(offeredForeignBlockchain.name());
		tradeBotData.setOfferedTradeForeignPublicKey(tradeForeignPublicKey);
		tradeBotData.setOfferedTradeForeignPublicKeyHash(tradeForeignPublicKeyHash);
		tradeBotData.setOfferedForeignAmount(offeredForeignAmount);
		tradeBotData.setOfferedForeignKey(offeredForeignKey);
		tradeBotData.setOfferedForeignReceivingAccountInfo(offeredReceivingAccountInfo);
		tradeBotData.setRequestedForeignBlockchain(requestedForeignBlockchain.name());
		tradeBotData.setRequestedTradeForeignPublicKey(tradeForeignPublicKey);
		tradeBotData.setRequestedTradeForeignPublicKeyHash(tradeForeignPublicKeyHash);
		tradeBotData.setRequestedForeignAmount(requestedForeignAmount);
		tradeBotData.setRequestedForeignKey(requestedForeignKey);
		tradeBotData.setRequestedForeignReceivingAccountInfo(requestedReceivingAccountInfo);
	}

}
