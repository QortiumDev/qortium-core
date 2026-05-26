package org.qortium.controller.tradebot;

import org.qortium.account.PrivateKeyAccount;
import org.qortium.account.PublicKeyAccount;
import org.qortium.api.model.crosschain.TradeBotCreateRequest;
import org.qortium.controller.tradebot.TradeStates.State;
import org.qortium.crosschain.ACCT;
import org.qortium.crosschain.AcctMode;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinyACCTv5;
import org.qortium.crosschain.BitcoinyAddress;
import org.qortium.crosschain.BitcoinyHTLC;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crypto.Crypto;
import org.qortium.data.asset.AssetData;
import org.qortium.data.at.ATData;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.data.crosschain.TradeBotData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.DeployAtTransactionData;
import org.qortium.data.transaction.MessageTransactionData;
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
import org.qortium.transform.transaction.MessageTransactionTransformer;
import org.qortium.utils.Base58;
import org.qortium.utils.NTP;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BitcoinyACCTv5TradeBot implements AcctTradeBot {

	private static final long MAX_AT_CONFIRMATION_PERIOD = 24 * 60 * 60 * 1000L;
	static final int MIN_REVERSE_TRADE_TIMEOUT_MINUTES = 120;
	static final int FOREIGN_LOCKTIME_SAFETY_MARGIN_MINUTES = 30;
	static final int RESERVATION_TIMEOUT_MINUTES = 30;
	private static final long RESERVATION_TIMEOUT = RESERVATION_TIMEOUT_MINUTES * 60L * 1000L;

	private static BitcoinyACCTv5TradeBot instance;
	private Function<String, Bitcoiny> bitcoinyResolver = ForeignBlockchainRegistry::getRegisteredBitcoinyInstance;
	private final BitcoinyHtlcTradeSupport htlcTradeSupport = new BitcoinyHtlcTradeSupport();
	private Long messageFeeOverrideForTesting;
	private MessageSubmitter messageSubmitterForTesting;

	private final List<String> endStates = Arrays.asList(State.MAKER_DONE, State.MAKER_REFUNDED, State.TAKER_DONE, State.TAKER_REFUNDED).stream()
			.map(State::name)
			.collect(Collectors.toUnmodifiableList());

	private BitcoinyACCTv5TradeBot() {
	}

	public static synchronized BitcoinyACCTv5TradeBot getInstance() {
		if (instance == null)
			instance = new BitcoinyACCTv5TradeBot();

		return instance;
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
		ForeignBlockchainRegistry.Entry foreignBlockchain = tradeBotCreateRequest.resolveForeignBlockchain();
		Bitcoiny bitcoiny = getBitcoiny(foreignBlockchain);
		String foreignCurrencyCode = bitcoiny.getCurrencyCode();

		if (!bitcoiny.isValidWalletKey(tradeBotCreateRequest.foreignKey))
			throw new DataException("Invalid foreign wallet key");

		if (tradeBotCreateRequest.tradeTimeout < MIN_REVERSE_TRADE_TIMEOUT_MINUTES)
			throw new DataException(String.format("Reverse trade timeout must be at least %d minutes", MIN_REVERSE_TRADE_TIMEOUT_MINUTES));

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

		AssetData localAssetData = repository.getAssetRepository().fromAssetId(tradeBotCreateRequest.localAssetId);
		if (localAssetData == null)
			throw new DataException("Local asset does not exist: " + tradeBotCreateRequest.localAssetId);

		String localAssetLabel = localAssetData.getName() != null ? localAssetData.getName() : "asset-" + tradeBotCreateRequest.localAssetId;
		byte[] creationBytes = BitcoinyACCTv5.buildTradeAT(foreignBlockchain, tradeLocalAddress, tradeForeignPublicKeyHash,
				hashOfSecretA, tradeBotCreateRequest.localAmount, tradeBotCreateRequest.foreignAmount, tradeBotCreateRequest.tradeTimeout);

		DeployAtTransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData,
				String.format("%s/%s reverse ACCT", foreignCurrencyCode, localAssetLabel),
				String.format("%s/%s reverse cross-chain trade", foreignCurrencyCode, localAssetLabel),
				"ACCT",
				String.format("ACCT reverse asset-%d %s", tradeBotCreateRequest.localAssetId, foreignCurrencyCode),
				creationBytes, 0L, tradeBotCreateRequest.localAssetId, tradeBotCreateRequest.nativeFeeReserve);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		deployAtTransactionData.setFee(deployAtTransaction.calcRecommendedFee());
		DeployAtTransaction.ensureATAddress(deployAtTransactionData);
		String atAddress = deployAtTransactionData.getAtAddress();

		TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, BitcoinyACCTv5.NAME,
				State.MAKER_WAITING_FOR_AT_CONFIRM.name(), State.MAKER_WAITING_FOR_AT_CONFIRM.value,
				creator.getAddress(), atAddress, timestamp, tradeBotCreateRequest.localAssetId, tradeBotCreateRequest.localAmount,
				tradeLocalPublicKey, tradeLocalPublicKeyHash, tradeLocalAddress, secretA, hashOfSecretA, foreignBlockchain.name(),
				tradeForeignPublicKey, tradeForeignPublicKeyHash, tradeBotCreateRequest.foreignAmount,
				tradeBotCreateRequest.foreignKey, null, null, Base58.decode(tradeBotCreateRequest.receivingAddress));

		TradeBot.updateTradeBotState(repository, tradeBotData, () -> String.format("Built reverse AT %s. Waiting for deployment", atAddress));
		TradeBot.backupTradeBotData(repository, null);

		try {
			return DeployAtTransactionTransformer.toBytes(deployAtTransactionData);
		} catch (TransformationException e) {
			throw new DataException("Failed to transform DEPLOY_AT transaction?", e);
		}
	}

	@Override
	public ResponseResult startResponse(Repository repository, ATData atData, ACCT acct, CrossChainTradeData crossChainTradeData,
			String foreignKey, String receivingAddress) {
		return ResponseResult.INVALID_CRITERIA;
	}

	public byte[] startResponse(Repository repository, ATData atData, CrossChainTradeData tradeData, byte[] responderPublicKey,
			String foreignReceivingAddress) throws DataException {
		Bitcoiny bitcoiny = getBitcoiny(tradeData.foreignBlockchain);

		BitcoinyAddress receivingAddress;
		try {
			receivingAddress = BitcoinyAddress.fromString(bitcoiny.getNetworkParameters(), foreignReceivingAddress);
		} catch (IllegalArgumentException e) {
			throw new DataException(String.format("Unsupported %s receiving address: %s", bitcoiny.getCurrencyCode(), foreignReceivingAddress));
		}
		if (!receivingAddress.isP2PKH())
			throw new DataException(String.format("Unsupported %s receiving address: %s", bitcoiny.getCurrencyCode(), foreignReceivingAddress));

		byte[] tradePrivateKey = TradeBot.generateTradePrivateKey();
		byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);
		String tradeLocalAddress = Crypto.toAddress(responderPublicKey);

		long now = NTP.getTime();
		TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, BitcoinyACCTv5.NAME,
				State.TAKER_WAITING_FOR_FOREIGN_LOCK.name(), State.TAKER_WAITING_FOR_FOREIGN_LOCK.value,
				tradeLocalAddress, tradeData.atAddress, now, tradeData.localAssetId, tradeData.localAmount,
				responderPublicKey, Crypto.hash160(responderPublicKey), tradeLocalAddress, null, tradeData.hashOfSecretA,
				tradeData.foreignBlockchain, tradeForeignPublicKey, tradeForeignPublicKeyHash,
				tradeData.expectedForeignAmount, null, null, null, receivingAddress.getPayload());

		TradeBot.updateTradeBotState(repository, tradeBotData,
				() -> String.format("Built reverse reservation for AT %s. Waiting for maker foreign HTLC", tradeData.atAddress));
		TradeBot.backupTradeBotData(repository, Arrays.asList(tradeBotData));

		byte[] messageData = BitcoinyACCTv5.buildReserveMessage(tradeForeignPublicKeyHash);
		return buildUnsignedMessageTransaction(repository, responderPublicKey, tradeData.atAddress, 0L, null, messageData);
	}

	public byte[] buildLocalLockTransaction(Repository repository, ATData atData, CrossChainTradeData tradeData,
			byte[] responderPublicKey) throws DataException, ForeignBlockchainException {
		if (tradeData.mode != AcctMode.FOREIGN_LOCKED)
			throw new DataException("Reverse trade is not ready for local lock");

		TradeBotData tradeBotData = findTakerTradeBotData(repository, tradeData, responderPublicKey)
				.orElseThrow(() -> new DataException("No matching reverse taker trade-bot entry"));

		if (!isReservedForUs(tradeBotData, tradeData))
			throw new DataException("Reverse trade is reserved for a different taker");

		if (!hasSufficientTimeForLocalLock(tradeData, NTP.getTime()))
			throw new DataException("Reverse trade foreign HTLC timeout is too close");

		Bitcoiny bitcoiny = getBitcoiny(tradeData.foreignBlockchain);
		String p2shAddress = deriveP2shAddress(bitcoiny, tradeData);
		long minimumAmount = BitcoinyHtlcTradeSupport.minimumHtlcAmount(bitcoiny, tradeData.expectedForeignAmount);
		BitcoinyHTLC.Status htlcStatus = determineHtlcStatus(bitcoiny, p2shAddress, minimumAmount);
		if (htlcStatus != BitcoinyHTLC.Status.FUNDED)
			throw new DataException("Reverse trade foreign HTLC is not funded");

		byte[] unsignedBytes = buildUnsignedMessageTransaction(repository, responderPublicKey, tradeData.atAddress, tradeData.localAmount,
				tradeData.localAssetId, BitcoinyACCTv5.buildLocalLockMessage());

		tradeBotData.setLockTimeA(tradeData.lockTimeA);
		TradeBot.updateTradeBotState(repository, tradeBotData, State.TAKER_WAITING_FOR_AT_LOCK,
				() -> String.format("Reverse P2SH %s funded. Built local lock transaction for AT %s", p2shAddress, tradeData.atAddress));

		return unsignedBytes;
	}

	@Override
	public boolean canDelete(Repository repository, TradeBotData tradeBotData) throws DataException {
		State tradeBotState = State.valueOf(tradeBotData.getStateValue());
		return tradeBotState == null || !repository.getATRepository().exists(tradeBotData.getAtAddress())
				|| this.endStates.contains(tradeBotData.getState());
	}

	@Override
	public void progress(Repository repository, TradeBotData tradeBotData) throws DataException, ForeignBlockchainException {
		State tradeBotState = State.valueOf(tradeBotData.getStateValue());
		if (tradeBotState == null)
			return;

		ATData atData = tradeBotState.requiresAtData ? repository.getATRepository().fromATAddress(tradeBotData.getAtAddress()) : null;
		CrossChainTradeData tradeData = null;
		if (atData != null && tradeBotState.requiresTradeData)
			tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

		switch (tradeBotState) {
			case MAKER_WAITING_FOR_AT_CONFIRM:
				handleMakerWaitingForAtConfirm(repository, tradeBotData);
				break;

			case MAKER_WAITING_FOR_TAKER_MESSAGE:
				handleMakerWaitingForReservation(repository, tradeBotData, atData, tradeData);
				break;

			case MAKER_WAITING_FOR_LOCAL_LOCK:
				handleMakerWaitingForLocalLock(repository, tradeBotData, atData, tradeData);
				break;

			case MAKER_WAITING_FOR_AT_REDEEM:
				handleMakerWaitingForAtRedeem(repository, tradeBotData, atData, tradeData);
				break;

			case TAKER_WAITING_FOR_FOREIGN_LOCK:
				handleTakerWaitingForForeignLock(repository, tradeBotData, atData, tradeData);
				break;

			case TAKER_WAITING_FOR_AT_LOCK:
				handleTakerWaitingForAtLock(repository, tradeBotData, atData, tradeData);
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
			tradeBotData.setState(State.MAKER_REFUNDED.name());
			tradeBotData.setStateValue(State.MAKER_REFUNDED.value);
			TradeBot.notifyStateChange(tradeBotData);
			return;
		}

		TradeBot.updateTradeBotState(repository, tradeBotData, State.MAKER_WAITING_FOR_TAKER_MESSAGE,
				() -> String.format("Reverse AT %s confirmed ready. Waiting for taker reservation", tradeBotData.getAtAddress()));
	}

	private void handleMakerWaitingForReservation(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		if (atData.getIsFinished()) {
			updateMakerFinishedState(repository, tradeBotData, tradeData);
			return;
		}

		if (tradeData.mode == AcctMode.FOREIGN_LOCKED) {
			if (!hasSufficientTimeForLocalLock(tradeData, NTP.getTime())) {
				sendCancelMessage(repository, tradeBotData);
				return;
			}

			TradeBot.updateTradeBotState(repository, tradeBotData, State.MAKER_WAITING_FOR_LOCAL_LOCK,
					() -> String.format("Reverse AT %s has foreign HTLC declaration. Waiting for taker local lock", tradeBotData.getAtAddress()));
			return;
		}

		if (tradeData.mode == AcctMode.TRADING) {
			TradeBot.updateTradeBotState(repository, tradeBotData, State.MAKER_WAITING_FOR_AT_REDEEM,
					() -> String.format("Reverse AT %s has taker local lock. Waiting to redeem", tradeBotData.getAtAddress()));
			return;
		}

		if (tradeData.mode != AcctMode.RESERVED)
			return;

		int lockTimeA = ensureLockTimeA(repository, tradeBotData, tradeData);
		tradeData.lockTimeA = lockTimeA;

		Bitcoiny bitcoiny = getBitcoiny(tradeBotData.getForeignBlockchain());
		String p2shAddress = deriveP2shAddress(bitcoiny, tradeData);
		long minimumAmount = BitcoinyHtlcTradeSupport.minimumHtlcAmount(bitcoiny, tradeData.expectedForeignAmount);
		BitcoinyHTLC.Status htlcStatus = determineHtlcStatus(bitcoiny, p2shAddress, minimumAmount);

		long now = NTP.getTime();
		if (!hasSufficientTimeForLocalLock(tradeData, now)) {
			sendCancelMessage(repository, tradeBotData);
			return;
		}

		if (hasReservationTimedOut(tradeBotData, now) && htlcStatus != BitcoinyHTLC.Status.FUNDED) {
			sendCancelMessage(repository, tradeBotData);
			return;
		}

		if (htlcStatus == BitcoinyHTLC.Status.UNFUNDED) {
			this.htlcTradeSupport.fundIfUnfunded(bitcoiny, tradeBotData.getForeignKey(), p2shAddress, minimumAmount);
			return;
		}

		if (htlcStatus != BitcoinyHTLC.Status.FUNDED)
			return;

		byte[] messageData = BitcoinyACCTv5.buildForeignLockMessage(lockTimeA);
		if (!sendMessage(repository, tradeBotData, tradeBotData.getAtAddress(), messageData))
			return;

		TradeBot.updateTradeBotState(repository, tradeBotData, State.MAKER_WAITING_FOR_LOCAL_LOCK,
				() -> String.format("Funded reverse P2SH %s. Waiting for taker local lock", p2shAddress));
	}

	private void handleMakerWaitingForLocalLock(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		if (atData.getIsFinished()) {
			updateMakerFinishedState(repository, tradeBotData, tradeData);
			return;
		}

		if (tradeData.mode == AcctMode.FOREIGN_LOCKED && !hasSufficientTimeForLocalLock(tradeData, NTP.getTime())) {
			sendCancelMessage(repository, tradeBotData);
			return;
		}

		if (tradeData.mode != AcctMode.TRADING)
			return;

		String receivingAddress = Base58.encode(tradeBotData.getReceivingAccountInfo());
		byte[] messageData = BitcoinyACCTv5.buildRedeemMessage(tradeBotData.getSecret(), receivingAddress);
		if (!sendMessage(repository, tradeBotData, tradeBotData.getAtAddress(), messageData))
			return;

		TradeBot.updateTradeBotState(repository, tradeBotData, State.MAKER_WAITING_FOR_AT_REDEEM,
				() -> String.format("Redeeming reverse AT %s. Secret should unlock taker foreign claim", tradeBotData.getAtAddress()));
	}

	private void handleMakerWaitingForAtRedeem(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		if (atData.getIsFinished()) {
			updateMakerFinishedState(repository, tradeBotData, tradeData);
			return;
		}

		if (tradeData.mode != AcctMode.TRADING)
			return;

		String receivingAddress = Base58.encode(tradeBotData.getReceivingAccountInfo());
		byte[] messageData = BitcoinyACCTv5.buildRedeemMessage(tradeBotData.getSecret(), receivingAddress);
		sendMessage(repository, tradeBotData, tradeBotData.getAtAddress(), messageData);
	}

	private void updateMakerFinishedState(Repository repository, TradeBotData tradeBotData, CrossChainTradeData tradeData)
			throws DataException, ForeignBlockchainException {
		if (tradeData.mode == AcctMode.REDEEMED) {
			TradeBot.updateTradeBotState(repository, tradeBotData, State.MAKER_DONE,
					() -> String.format("Reverse AT %s redeemed", tradeBotData.getAtAddress()));
			return;
		}

		if (!refundMakerForeignHtlcIfExpired(tradeBotData, tradeData))
			return;

		TradeBot.updateTradeBotState(repository, tradeBotData, State.MAKER_REFUNDED,
				() -> String.format("Reverse AT %s finished without redeem", tradeBotData.getAtAddress()));
	}

	private void handleTakerWaitingForForeignLock(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		if (atData.getIsFinished()) {
			updateTakerFinishedState(repository, tradeBotData, tradeData);
			return;
		}

		if (!isReservedForUs(tradeBotData, tradeData))
			return;

		if (tradeData.mode == AcctMode.TRADING) {
			TradeBot.updateTradeBotState(repository, tradeBotData, State.TAKER_WAITING_FOR_MAKER_REDEEM,
					() -> String.format("Reverse AT %s has local lock. Waiting for maker redeem secret", tradeBotData.getAtAddress()));
			return;
		}

		if (tradeData.mode != AcctMode.FOREIGN_LOCKED)
			return;

		if (!hasSufficientTimeForLocalLock(tradeData, NTP.getTime()))
			return;

		Bitcoiny bitcoiny = getBitcoiny(tradeBotData.getForeignBlockchain());
		String p2shAddress = deriveP2shAddress(bitcoiny, tradeData);
		long minimumAmount = BitcoinyHtlcTradeSupport.minimumHtlcAmount(bitcoiny, tradeData.expectedForeignAmount);
		BitcoinyHTLC.Status htlcStatus = determineHtlcStatus(bitcoiny, p2shAddress, minimumAmount);
		if (htlcStatus != BitcoinyHTLC.Status.FUNDED)
			return;

		tradeBotData.setLockTimeA(tradeData.lockTimeA);
		TradeBot.updateTradeBotState(repository, tradeBotData, State.TAKER_WAITING_FOR_AT_LOCK,
				() -> String.format("Reverse P2SH %s funded. Waiting for local lock transaction", p2shAddress));
	}

	private void handleTakerWaitingForAtLock(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException {
		if (atData.getIsFinished()) {
			updateTakerFinishedState(repository, tradeBotData, tradeData);
			return;
		}

		if (tradeData.mode != AcctMode.TRADING || !isReservedForUs(tradeBotData, tradeData))
			return;

		TradeBot.updateTradeBotState(repository, tradeBotData, State.TAKER_WAITING_FOR_MAKER_REDEEM,
				() -> String.format("Reverse AT %s locked local asset. Waiting for maker redeem secret", tradeBotData.getAtAddress()));
	}

	private void handleTakerWaitingForMakerRedeem(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		byte[] secretA = BitcoinyACCTv5.getInstance().findSecretA(repository, tradeData);
		if (secretA != null && redeemTakerForeignHtlc(tradeBotData, tradeData, secretA)) {
			TradeBot.updateTradeBotState(repository, tradeBotData, State.TAKER_DONE,
					() -> String.format("Redeemed reverse P2SH for AT %s", tradeBotData.getAtAddress()));
			return;
		}

		if (atData.getIsFinished())
			updateTakerFinishedState(repository, tradeBotData, tradeData);
	}

	private void updateTakerFinishedState(Repository repository, TradeBotData tradeBotData, CrossChainTradeData tradeData) throws DataException {
		TradeBot.updateTradeBotState(repository, tradeBotData,
				tradeData.mode == AcctMode.REDEEMED ? State.TAKER_DONE : State.TAKER_REFUNDED,
				() -> String.format("Reverse AT %s finished", tradeBotData.getAtAddress()));
	}

	private boolean redeemTakerForeignHtlc(TradeBotData tradeBotData, CrossChainTradeData tradeData, byte[] secretA)
			throws DataException, ForeignBlockchainException {
		Bitcoiny bitcoiny = getBitcoiny(tradeBotData.getForeignBlockchain());
		String p2shAddress = deriveP2shAddress(bitcoiny, tradeData);
		long minimumAmount = BitcoinyHtlcTradeSupport.minimumHtlcAmount(bitcoiny, tradeData.expectedForeignAmount);
		return this.htlcTradeSupport.redeemIfFunded(bitcoiny, p2shAddress, minimumAmount, tradeData.expectedForeignAmount,
				tradeBotData.getTradePrivateKey(), buildRedeemScript(tradeData), secretA, tradeBotData.getReceivingAccountInfo());
	}

	private boolean refundMakerForeignHtlcIfExpired(TradeBotData tradeBotData, CrossChainTradeData tradeData)
			throws DataException, ForeignBlockchainException {
		if (tradeBotData.getLockTimeA() == null || tradeData.partnerForeignPKH == null)
			return true;

		tradeData.lockTimeA = tradeBotData.getLockTimeA();

		Bitcoiny bitcoiny = getBitcoiny(tradeBotData.getForeignBlockchain());
		String p2shAddress = deriveP2shAddress(bitcoiny, tradeData);
		long minimumAmount = BitcoinyHtlcTradeSupport.minimumHtlcAmount(bitcoiny, tradeData.expectedForeignAmount);
		String refundAddress = bitcoiny.getUnusedReceiveAddress(tradeBotData.getForeignKey());
		BitcoinyAddress refund = BitcoinyAddress.fromString(bitcoiny.getNetworkParameters(), refundAddress);
		return this.htlcTradeSupport.refundIfExpired(bitcoiny, p2shAddress, minimumAmount, tradeData.expectedForeignAmount,
				tradeBotData.getTradePrivateKey(), buildRedeemScript(tradeData), tradeData.lockTimeA, refund.getPayload());
	}

	private int ensureLockTimeA(Repository repository, TradeBotData tradeBotData, CrossChainTradeData tradeData) throws DataException {
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

	private Optional<TradeBotData> findTakerTradeBotData(Repository repository, CrossChainTradeData tradeData,
			byte[] responderPublicKey) throws DataException {
		return repository.getCrossChainRepository().getAllTradeBotData().stream()
				.filter(tradeBotData -> BitcoinyACCTv5.NAME.equals(tradeBotData.getAcctName()))
				.filter(tradeBotData -> tradeData.atAddress.equals(tradeBotData.getAtAddress()))
				.filter(tradeBotData -> Arrays.equals(responderPublicKey, tradeBotData.getTradeLocalPublicKey()))
				.filter(tradeBotData -> !this.endStates.contains(tradeBotData.getState()))
				.filter(tradeBotData -> isReservedForUs(tradeBotData, tradeData))
				.findFirst();
	}

	private static boolean isReservedForUs(TradeBotData tradeBotData, CrossChainTradeData tradeData) {
		return tradeBotData.getTradeLocalAddress().equals(tradeData.partnerAddress)
				&& Arrays.equals(tradeBotData.getTradeForeignPublicKeyHash(), tradeData.partnerForeignPKH)
				&& Arrays.equals(tradeBotData.getHashOfSecret(), tradeData.hashOfSecretA);
	}

	private static boolean hasReservationTimedOut(TradeBotData tradeBotData, long now) {
		return now - tradeBotData.getTimestamp() > RESERVATION_TIMEOUT;
	}

	static boolean hasSufficientTimeForLocalLock(CrossChainTradeData tradeData, long now) {
		if (tradeData.lockTimeA == null)
			return false;

		long localRefundSeconds = BitcoinyACCTv5.calcLocalRefundTimeout(tradeData.tradeTimeout) * 60L;
		return BitcoinyHtlcTradeSupport.hasSufficientTimeBeforeLock(now, localRefundSeconds,
				FOREIGN_LOCKTIME_SAFETY_MARGIN_MINUTES, tradeData.lockTimeA);
	}

	private byte[] buildUnsignedMessageTransaction(Repository repository, byte[] senderPublicKey, String recipient, long amount,
			Long assetId, byte[] messageData) throws DataException {
		long timestamp = NTP.getTime();
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, senderPublicKey, 0L, null);
		TransactionData messageTransactionData = new MessageTransactionData(baseTransactionData, Transaction.getVersionByTimestamp(timestamp),
				0, recipient, amount, assetId, messageData, false, false);
		MessageTransaction messageTransaction = new MessageTransaction(repository, messageTransactionData);
		prepareMessageForSigning(messageTransaction);

		try {
			return MessageTransactionTransformer.toBytes(messageTransactionData);
		} catch (TransformationException e) {
			throw new DataException("Failed to transform MESSAGE transaction?", e);
		}
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

	private void prepareMessageForSigning(MessageTransaction messageTransaction) throws DataException {
		if (this.messageFeeOverrideForTesting != null) {
			messageTransaction.getTransactionData().setFee(this.messageFeeOverrideForTesting);
			return;
		}

		messageTransaction.computeNonce();
	}

	private boolean sendCancelMessage(Repository repository, TradeBotData tradeBotData) throws DataException {
		byte[] messageData = BitcoinyACCTv5.getInstance().buildCancelMessage(tradeBotData.getTradeLocalAddress());
		return sendMessage(repository, tradeBotData, tradeBotData.getAtAddress(), messageData);
	}

	private static String deriveP2shAddress(Bitcoiny bitcoiny, CrossChainTradeData tradeData) {
		return BitcoinyHtlcTradeSupport.deriveP2shAddress(bitcoiny, tradeData);
	}

	static byte[] buildRedeemScript(CrossChainTradeData tradeData) {
		return BitcoinyHtlcTradeSupport.buildRedeemScript(tradeData);
	}

	private Bitcoiny getBitcoiny(String foreignBlockchain) throws DataException {
		Bitcoiny bitcoiny = this.bitcoinyResolver.apply(foreignBlockchain);
		if (bitcoiny == null)
			throw new DataException("Unsupported Bitcoiny blockchain");
		return bitcoiny;
	}

	private BitcoinyHTLC.Status determineHtlcStatus(Bitcoiny bitcoiny, String p2shAddress, long minimumAmount) throws ForeignBlockchainException {
		return this.htlcTradeSupport.determineHtlcStatus(bitcoiny, p2shAddress, minimumAmount);
	}

	private Bitcoiny getBitcoiny(ForeignBlockchainRegistry.Entry foreignBlockchain) throws DataException {
		if (foreignBlockchain == null || !foreignBlockchain.isBitcoiny() || foreignBlockchain.getBitcoinyInstance() == null)
			throw new DataException("Unsupported Bitcoiny blockchain");

		Bitcoiny bitcoiny = this.bitcoinyResolver.apply(foreignBlockchain.name());
		return bitcoiny != null ? bitcoiny : foreignBlockchain.getBitcoinyInstance();
	}
}
