package org.qortal.controller.tradebot;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.account.PublicKeyAccount;
import org.qortal.api.model.crosschain.TradeBotCreateRequest;
import org.qortal.controller.tradebot.TradeStates.State;
import org.qortal.crosschain.ACCT;
import org.qortal.crosschain.AcctMode;
import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.BitcoinyACCTv5;
import org.qortal.crosschain.BitcoinyAddress;
import org.qortal.crosschain.BitcoinyHTLC;
import org.qortal.crosschain.BitcoinySignedTransaction;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.ForeignBlockchainRegistry;
import org.qortal.crypto.Crypto;
import org.qortal.data.asset.AssetData;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transaction.DeployAtTransaction;
import org.qortal.transaction.MessageTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ValidationResult;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.DeployAtTransactionTransformer;
import org.qortal.transform.transaction.MessageTransactionTransformer;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class BitcoinyACCTv5TradeBot implements AcctTradeBot {

	private static final long MAX_AT_CONFIRMATION_PERIOD = 24 * 60 * 60 * 1000L;
	private static final Set<String> MAKER_RESERVATION_STATES = Set.of(
			State.MAKER_WAITING_FOR_AT_CONFIRM.name(),
			State.MAKER_WAITING_FOR_TAKER_MESSAGE.name());

	private static BitcoinyACCTv5TradeBot instance;

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

	public boolean hasSufficientForeignBalanceForReverseOffer(Repository repository, Bitcoiny bitcoiny,
			String foreignBlockchain, String foreignKey, long foreignAmount) throws DataException, ForeignBlockchainException {
		long p2shFee = bitcoiny.getP2shFee(NTP.getTime());
		long reservedAmount = calculateReservedForeignAmount(repository.getCrossChainRepository().getAllTradeBotData(),
				foreignBlockchain, foreignKey, p2shFee);
		long newOfferRequiredAmount = foreignAmountWithFundingFee(foreignAmount, p2shFee);
		Long walletBalance = bitcoiny.getWalletBalance(foreignKey);

		if (walletBalance == null)
			throw new ForeignBlockchainException("Unable to determine foreign wallet balance");

		return hasSufficientForeignBalance(walletBalance, reservedAmount, newOfferRequiredAmount);
	}

	static long calculateReservedForeignAmount(List<TradeBotData> tradeBotDataList, String foreignBlockchain,
			String foreignKey, long p2shFee) {
		return tradeBotDataList.stream()
				.filter(tradeBotData -> isReverseMakerReservation(tradeBotData, foreignBlockchain, foreignKey))
				.mapToLong(tradeBotData -> foreignAmountWithFundingFee(tradeBotData.getForeignAmount(), p2shFee))
				.reduce(0L, BitcoinyACCTv5TradeBot::cappedAdd);
	}

	static boolean hasSufficientForeignBalance(long walletBalance, long reservedAmount, long newOfferRequiredAmount) {
		return walletBalance >= reservedAmount && walletBalance - reservedAmount >= newOfferRequiredAmount;
	}

	private static boolean isReverseMakerReservation(TradeBotData tradeBotData, String foreignBlockchain, String foreignKey) {
		return BitcoinyACCTv5.NAME.equals(tradeBotData.getAcctName())
				&& MAKER_RESERVATION_STATES.contains(tradeBotData.getState())
				&& Objects.equals(foreignBlockchain, tradeBotData.getForeignBlockchain())
				&& Objects.equals(foreignKey, tradeBotData.getForeignKey());
	}

	private static long foreignAmountWithFundingFee(long foreignAmount, long p2shFee) {
		if (Long.MAX_VALUE - foreignAmount < p2shFee)
			return Long.MAX_VALUE;

		return foreignAmount + p2shFee;
	}

	private static long cappedAdd(long lhs, long rhs) {
		if (Long.MAX_VALUE - lhs < rhs)
			return Long.MAX_VALUE;

		return lhs + rhs;
	}

	@Override
	public byte[] createTrade(Repository repository, TradeBotCreateRequest tradeBotCreateRequest) throws DataException {
		ForeignBlockchainRegistry.Entry foreignBlockchain = tradeBotCreateRequest.resolveForeignBlockchain();
		Bitcoiny bitcoiny = getBitcoiny(foreignBlockchain);
		String foreignCurrencyCode = bitcoiny.getCurrencyCode();

		if (!bitcoiny.isValidWalletKey(tradeBotCreateRequest.foreignKey))
			throw new DataException("Invalid foreign wallet key");

		byte[] tradePrivateKey = TradeBot.generateTradePrivateKey();
		byte[] tradeLocalPublicKey = TradeBot.deriveTradeLocalPublicKey(tradePrivateKey);
		byte[] tradeLocalPublicKeyHash = Crypto.hash160(tradeLocalPublicKey);
		String tradeLocalAddress = Crypto.toAddress(tradeLocalPublicKey);
		byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);

		PublicKeyAccount creator = new PublicKeyAccount(repository, tradeBotCreateRequest.creatorPublicKey);
		long timestamp = NTP.getTime();
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, creator.getPublicKey(), 0L, null);

		AssetData localAssetData = repository.getAssetRepository().fromAssetId(tradeBotCreateRequest.localAssetId);
		if (localAssetData == null)
			throw new DataException("Local asset does not exist: " + tradeBotCreateRequest.localAssetId);

		String localAssetLabel = localAssetData.getName() != null ? localAssetData.getName() : "asset-" + tradeBotCreateRequest.localAssetId;
		byte[] creationBytes = BitcoinyACCTv5.buildTradeAT(foreignBlockchain, tradeLocalAddress, tradeForeignPublicKeyHash,
				tradeBotCreateRequest.localAmount, tradeBotCreateRequest.foreignAmount, tradeBotCreateRequest.tradeTimeout);

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
				tradeLocalPublicKey, tradeLocalPublicKeyHash, tradeLocalAddress, null, null, foreignBlockchain.name(),
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
		byte[] secretA = TradeBot.generateSecret();
		byte[] hashOfSecretA = Crypto.hash160(secretA);
		byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);
		String tradeLocalAddress = Crypto.toAddress(responderPublicKey);

		long now = NTP.getTime();
		int lockTimeA = (int) (now / 1000L + Math.max(1, tradeData.tradeTimeout / 2) * 60L);
		int refundTimeout = tradeData.tradeTimeout;

		TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, BitcoinyACCTv5.NAME,
				State.TAKER_WAITING_FOR_AT_LOCK.name(), State.TAKER_WAITING_FOR_AT_LOCK.value,
				tradeLocalAddress, tradeData.atAddress, now, tradeData.localAssetId, tradeData.localAmount,
				responderPublicKey, Crypto.hash160(responderPublicKey), tradeLocalAddress, secretA, hashOfSecretA,
				tradeData.foreignBlockchain, tradeForeignPublicKey, tradeForeignPublicKeyHash,
				tradeData.expectedForeignAmount, null, null, lockTimeA, receivingAddress.getPayload());

		TradeBot.updateTradeBotState(repository, tradeBotData,
				() -> String.format("Built reverse response for AT %s. Waiting for local escrow transaction", tradeData.atAddress));
		TradeBot.backupTradeBotData(repository, Arrays.asList(tradeBotData));

		byte[] messageData = BitcoinyACCTv5.buildLockMessage(tradeForeignPublicKeyHash, hashOfSecretA, lockTimeA, refundTimeout);
		long timestamp = NTP.getTime();
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, responderPublicKey, 0L, null);
		TransactionData messageTransactionData = new MessageTransactionData(baseTransactionData, Transaction.getVersionByTimestamp(timestamp),
				0, tradeData.atAddress, tradeData.localAmount, tradeData.localAssetId, messageData, false, false);
		MessageTransaction messageTransaction = new MessageTransaction(repository, messageTransactionData);
		messageTransaction.computeNonce();

		try {
			return MessageTransactionTransformer.toBytes(messageTransactionData);
		} catch (TransformationException e) {
			throw new DataException("Failed to transform MESSAGE transaction?", e);
		}
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
				handleMakerWaitingForLocalEscrow(repository, tradeBotData, atData, tradeData);
				break;

			case MAKER_WAITING_FOR_AT_REDEEM:
				handleMakerWaitingForAtRedeem(repository, tradeBotData, atData, tradeData);
				break;

			case TAKER_WAITING_FOR_AT_LOCK:
				handleTakerWaitingForAtLock(repository, tradeBotData, atData, tradeData);
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
				() -> String.format("Reverse AT %s confirmed ready. Waiting for taker escrow", tradeBotData.getAtAddress()));
	}

	private void handleMakerWaitingForLocalEscrow(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		if (atData.getIsFinished()) {
			TradeBot.updateTradeBotState(repository, tradeBotData, State.MAKER_REFUNDED,
					() -> String.format("Reverse AT %s finished before taker escrow", tradeBotData.getAtAddress()));
			return;
		}

		if (tradeData.mode != AcctMode.TRADING)
			return;

		Bitcoiny bitcoiny = getBitcoiny(tradeBotData.getForeignBlockchain());
		String p2shAddress = deriveP2shAddress(bitcoiny, tradeData);
		long minimumAmount = tradeData.expectedForeignAmount + bitcoiny.getP2shFee(NTP.getTime());
		BitcoinyHTLC.Status htlcStatus = BitcoinyHTLC.determineHtlcStatus(bitcoiny, p2shAddress, minimumAmount);
		if (htlcStatus == BitcoinyHTLC.Status.UNFUNDED) {
			BitcoinySignedTransaction fundingTransaction = bitcoiny.buildSpendTransaction(tradeBotData.getForeignKey(), p2shAddress, minimumAmount);
			if (fundingTransaction == null)
				return;

			bitcoiny.broadcastTransaction(fundingTransaction);
		}

		TradeBot.updateTradeBotState(repository, tradeBotData, State.MAKER_WAITING_FOR_AT_REDEEM,
				() -> String.format("Funded reverse P2SH %s. Waiting for taker redeem secret", p2shAddress));
	}

	private void handleMakerWaitingForAtRedeem(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		if (atData.getIsFinished()) {
			TradeBot.updateTradeBotState(repository, tradeBotData,
					tradeData.mode == AcctMode.REDEEMED ? State.MAKER_DONE : State.MAKER_REFUNDED,
					() -> String.format("Reverse AT %s finished", tradeBotData.getAtAddress()));
			return;
		}

		Bitcoiny bitcoiny = getBitcoiny(tradeBotData.getForeignBlockchain());
		String p2shAddress = deriveP2shAddress(bitcoiny, tradeData);
		byte[] secretA = BitcoinyHTLC.findHtlcSecret(bitcoiny, p2shAddress);
		if (secretA != null) {
			String receivingAddress = Base58.encode(tradeBotData.getReceivingAccountInfo());
			byte[] messageData = BitcoinyACCTv5.buildRedeemMessage(secretA, receivingAddress);
			sendMessage(repository, tradeBotData, tradeBotData.getAtAddress(), messageData);
			return;
		}

		if (NTP.getTime() <= tradeData.lockTimeA * 1000L || bitcoiny.getMedianBlockTime() <= tradeData.lockTimeA)
			return;

		long minimumAmount = tradeData.expectedForeignAmount + bitcoiny.getP2shFee(NTP.getTime());
		BitcoinyHTLC.Status htlcStatus = BitcoinyHTLC.determineHtlcStatus(bitcoiny, p2shAddress, minimumAmount);
		if (htlcStatus == BitcoinyHTLC.Status.FUNDED) {
			byte[] redeemScript = buildRedeemScript(tradeData);
			String refundAddress = bitcoiny.getUnusedReceiveAddress(tradeBotData.getForeignKey());
			BitcoinyAddress refund = BitcoinyAddress.fromString(bitcoiny.getNetworkParameters(), refundAddress);
			BitcoinySignedTransaction refundTransaction = bitcoiny.buildHtlcRefundTransaction(Coin.valueOf(tradeData.expectedForeignAmount),
					ECKey.fromPrivate(tradeBotData.getTradePrivateKey()), bitcoiny.getUnspentOutputs(p2shAddress, false),
					redeemScript, tradeData.lockTimeA, refund.getPayload());
			bitcoiny.broadcastTransaction(refundTransaction);
		}
	}

	private void handleTakerWaitingForAtLock(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		if (atData.getIsFinished()) {
			TradeBot.updateTradeBotState(repository, tradeBotData,
					tradeData.mode == AcctMode.REFUNDED ? State.TAKER_REFUNDED : State.TAKER_DONE,
					() -> String.format("Reverse AT %s finished", tradeBotData.getAtAddress()));
			return;
		}

		if (tradeData.mode != AcctMode.TRADING || !tradeBotData.getTradeLocalAddress().equals(tradeData.partnerAddress)
				|| !Arrays.equals(tradeBotData.getHashOfSecret(), tradeData.hashOfSecretA))
			return;

		Bitcoiny bitcoiny = getBitcoiny(tradeBotData.getForeignBlockchain());
		String p2shAddress = deriveP2shAddress(bitcoiny, tradeData);
		long minimumAmount = tradeData.expectedForeignAmount + bitcoiny.getP2shFee(NTP.getTime());
		BitcoinyHTLC.Status htlcStatus = BitcoinyHTLC.determineHtlcStatus(bitcoiny, p2shAddress, minimumAmount);

		if (htlcStatus != BitcoinyHTLC.Status.FUNDED)
			return;

		byte[] redeemScript = buildRedeemScript(tradeData);
		BitcoinySignedTransaction redeemTransaction = bitcoiny.buildHtlcRedeemTransaction(Coin.valueOf(tradeData.expectedForeignAmount),
				ECKey.fromPrivate(tradeBotData.getTradePrivateKey()), bitcoiny.getUnspentOutputs(p2shAddress, false),
				redeemScript, tradeBotData.getSecret(), tradeBotData.getReceivingAccountInfo());
		bitcoiny.broadcastTransaction(redeemTransaction);

		TradeBot.updateTradeBotState(repository, tradeBotData, State.TAKER_DONE,
				() -> String.format("Redeemed reverse P2SH %s. Waiting for foreign funds", p2shAddress));
	}

	private boolean sendMessage(Repository repository, TradeBotData tradeBotData, String recipient, byte[] messageData) throws DataException {
		if (repository.getMessageRepository().exists(tradeBotData.getTradeLocalPublicKey(), recipient, messageData))
			return true;

		PrivateKeyAccount sender = new PrivateKeyAccount(repository, tradeBotData.getTradePrivateKey());
		MessageTransaction messageTransaction = MessageTransaction.build(repository, sender, Group.NO_GROUP, recipient, messageData, false, false);
		messageTransaction.computeNonce();
		messageTransaction.sign(sender);
		repository.discardChanges();
		return messageTransaction.isSignatureValid() && messageTransaction.importAsUnconfirmed() == ValidationResult.OK;
	}

	private static String deriveP2shAddress(Bitcoiny bitcoiny, CrossChainTradeData tradeData) {
		return bitcoiny.deriveP2shAddress(buildRedeemScript(tradeData));
	}

	static byte[] buildRedeemScript(CrossChainTradeData tradeData) {
		return BitcoinyHTLC.buildScript(tradeData.creatorForeignPKH, tradeData.lockTimeA,
				tradeData.partnerForeignPKH, tradeData.hashOfSecretA);
	}

	private Bitcoiny getBitcoiny(String foreignBlockchain) throws DataException {
		Bitcoiny bitcoiny = ForeignBlockchainRegistry.getRegisteredBitcoinyInstance(foreignBlockchain);
		if (bitcoiny == null)
			throw new DataException("Unsupported Bitcoiny blockchain");
		return bitcoiny;
	}

	private Bitcoiny getBitcoiny(ForeignBlockchainRegistry.Entry foreignBlockchain) throws DataException {
		if (foreignBlockchain == null || !foreignBlockchain.isBitcoiny() || foreignBlockchain.getBitcoinyInstance() == null)
			throw new DataException("Unsupported Bitcoiny blockchain");
		return foreignBlockchain.getBitcoinyInstance();
	}
}
