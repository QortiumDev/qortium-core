package org.qortal.controller.tradebot;

import org.qortal.account.PublicKeyAccount;
import org.qortal.api.model.crosschain.TradeBotCreateRequest;
import org.qortal.asset.Asset;
import org.qortal.crosschain.ACCT;
import org.qortal.crosschain.AcctMode;
import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.BitcoinyAddress;
import org.qortal.crosschain.BitcoinyForeignForeignACCTv1;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.ForeignBlockchainRegistry;
import org.qortal.crypto.Crypto;
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
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.DeployAtTransactionTransformer;
import org.qortal.transform.transaction.MessageTransactionTransformer;
import org.qortal.utils.NTP;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BitcoinyForeignForeignTradeBot implements AcctTradeBot {

	static final int MIN_FOREIGN_FOREIGN_TRADE_TIMEOUT_MINUTES = 120;

	private static BitcoinyForeignForeignTradeBot instance;
	private Function<String, Bitcoiny> bitcoinyResolver = ForeignBlockchainRegistry::getRegisteredBitcoinyInstance;
	private Long messageFeeOverrideForTesting;

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

	@Override
	public List<String> getEndStates() {
		return this.endStates;
	}

	void setBitcoinyResolverForTesting(Function<String, Bitcoiny> bitcoinyResolver) {
		this.bitcoinyResolver = bitcoinyResolver != null ? bitcoinyResolver : ForeignBlockchainRegistry::getRegisteredBitcoinyInstance;
	}

	void setMessageFeeOverrideForTesting(Long messageFeeOverrideForTesting) {
		this.messageFeeOverrideForTesting = messageFeeOverrideForTesting;
	}

	void resetTestHooks() {
		this.bitcoinyResolver = ForeignBlockchainRegistry::getRegisteredBitcoinyInstance;
		this.messageFeeOverrideForTesting = null;
	}

	@Override
	public byte[] createTrade(Repository repository, TradeBotCreateRequest tradeBotCreateRequest) throws DataException {
		ForeignBlockchainRegistry.Entry offeredForeignBlockchain = tradeBotCreateRequest.resolveOfferedForeignBlockchain();
		ForeignBlockchainRegistry.Entry requestedForeignBlockchain = tradeBotCreateRequest.resolveRequestedForeignBlockchain();
		if (!BitcoinyForeignForeignACCTv1.isSupportedBitcoinyPair(offeredForeignBlockchain, requestedForeignBlockchain))
			throw new DataException("Foreign/foreign trades require two supported Bitcoiny blockchains");

		if (offeredForeignBlockchain.name().equals(requestedForeignBlockchain.name()))
			throw new DataException("Foreign/foreign trades require two different blockchains");

		if (tradeBotCreateRequest.offeredForeignAmount == null || tradeBotCreateRequest.offeredForeignAmount <= 0
				|| tradeBotCreateRequest.requestedForeignAmount == null || tradeBotCreateRequest.requestedForeignAmount <= 0)
			throw new DataException("Foreign/foreign trade amounts must be positive");

		if (tradeBotCreateRequest.tradeTimeout < MIN_FOREIGN_FOREIGN_TRADE_TIMEOUT_MINUTES)
			throw new DataException(String.format("Foreign/foreign trade timeout must be at least %d minutes",
					MIN_FOREIGN_FOREIGN_TRADE_TIMEOUT_MINUTES));

		if (tradeBotCreateRequest.nativeFeeReserve < 0)
			throw new DataException("Native fee reserve cannot be negative");

		Bitcoiny offeredBitcoiny = getBitcoiny(offeredForeignBlockchain);
		Bitcoiny requestedBitcoiny = getBitcoiny(requestedForeignBlockchain);

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
			CrossChainTradeData crossChainTradeData, String foreignKey, String receivingAddress) {
		return ResponseResult.INVALID_CRITERIA;
	}

	public byte[] startResponse(Repository repository, ATData atData, CrossChainTradeData tradeData, byte[] responderPublicKey,
			String requestedForeignKey, String offeredForeignReceivingAddress) throws DataException {
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
		byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);
		String tradeLocalAddress = Crypto.toAddress(responderPublicKey);

		long now = NTP.getTime();
		TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, BitcoinyForeignForeignACCTv1.NAME,
				TradeStates.State.TAKER_WAITING_FOR_FOREIGN_LOCK.name(), TradeStates.State.TAKER_WAITING_FOR_FOREIGN_LOCK.value,
				tradeLocalAddress, tradeData.atAddress, now, Asset.NATIVE, 0L,
				responderPublicKey, Crypto.hash160(responderPublicKey), tradeLocalAddress,
				null, tradeData.hashOfSecretA, requestedForeignBlockchain.name(), tradeForeignPublicKey, tradeForeignPublicKeyHash,
				tradeData.requestedForeignAmount, requestedForeignKey, null, null, offeredReceivingAddress.getPayload());
		populateForeignForeignFields(tradeBotData, offeredForeignBlockchain, requestedForeignBlockchain,
				tradeForeignPublicKey, tradeForeignPublicKeyHash,
				tradeData.offeredForeignAmount, null, offeredReceivingAddress.getPayload(),
				tradeData.requestedForeignAmount, requestedForeignKey, requestedRefundAddress.getPayload());

		TradeBot.updateTradeBotState(repository, tradeBotData,
				() -> String.format("Built foreign/foreign reservation for AT %s. Waiting for maker foreign HTLC",
						tradeData.atAddress));
		TradeBot.backupTradeBotData(repository, Arrays.asList(tradeBotData));

		byte[] messageData = BitcoinyForeignForeignACCTv1.buildReserveMessage(tradeForeignPublicKeyHash, tradeForeignPublicKeyHash);
		return buildUnsignedMessageTransaction(repository, responderPublicKey, tradeData.atAddress, messageData);
	}

	@Override
	public boolean canDelete(Repository repository, TradeBotData tradeBotData) {
		return true;
	}

	@Override
	public void progress(Repository repository, TradeBotData tradeBotData) {
	}

	private byte[] buildUnsignedMessageTransaction(Repository repository, byte[] senderPublicKey, String recipient, byte[] messageData)
			throws DataException {
		long timestamp = NTP.getTime();
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, senderPublicKey, 0L, null);
		TransactionData messageTransactionData = new MessageTransactionData(baseTransactionData,
				Transaction.getVersionByTimestamp(timestamp), 0, recipient, 0L, null, messageData, false, false);
		MessageTransaction messageTransaction = new MessageTransaction(repository, messageTransactionData);
		prepareMessageForSigning(messageTransaction);

		try {
			return MessageTransactionTransformer.toBytes(messageTransactionData);
		} catch (TransformationException e) {
			throw new DataException("Failed to transform MESSAGE transaction?", e);
		}
	}

	private void prepareMessageForSigning(MessageTransaction messageTransaction) throws DataException {
		if (this.messageFeeOverrideForTesting != null) {
			messageTransaction.getTransactionData().setFee(this.messageFeeOverrideForTesting);
			return;
		}

		messageTransaction.computeNonce();
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
