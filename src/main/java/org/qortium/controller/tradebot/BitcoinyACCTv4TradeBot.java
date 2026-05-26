package org.qortium.controller.tradebot;

import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.account.PublicKeyAccount;
import org.qortium.api.model.crosschain.TradeBotCreateRequest;
import org.qortium.controller.tradebot.TradeStates.State;
import org.qortium.crosschain.*;
import org.qortium.crypto.Crypto;
import org.qortium.data.asset.AssetData;
import org.qortium.data.at.ATData;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.data.crosschain.TradeBotData;
import org.qortium.data.crosschain.TradeBotFillData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.DeployAtTransactionData;
import org.qortium.data.transaction.MessageTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.MessageTransaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.DeployAtTransactionTransformer;
import org.qortium.utils.Base58;
import org.qortium.utils.NTP;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class BitcoinyACCTv4TradeBot implements AcctTradeBot {

	private static final long MAX_AT_CONFIRMATION_PERIOD = 24 * 60 * 60 * 1000L;

	static final String FILL_ACTIVE = "ACTIVE";
	private static final String FILL_DONE = "DONE";
	static final String FILL_REFUNDED = "REFUNDED";

	private static BitcoinyACCTv4TradeBot instance;

	private final List<String> endStates = Arrays.asList(State.MAKER_DONE, State.MAKER_REFUNDED, State.TAKER_DONE, State.TAKER_REFUNDING_FOREIGN, State.TAKER_REFUNDED).stream()
			.map(State::name)
			.collect(Collectors.toUnmodifiableList());

	private BitcoinyACCTv4TradeBot() {
	}

	public static synchronized BitcoinyACCTv4TradeBot getInstance() {
		if (instance == null)
			instance = new BitcoinyACCTv4TradeBot();

		return instance;
	}

	@Override
	public List<String> getEndStates() {
		return this.endStates;
	}

	@Override
	public byte[] createTrade(Repository repository, TradeBotCreateRequest tradeBotCreateRequest) throws DataException {
		ForeignBlockchainRegistry.Entry foreignBlockchain = tradeBotCreateRequest.resolveForeignBlockchain();
		Bitcoiny bitcoiny = getBitcoiny(foreignBlockchain);
		String foreignCurrencyCode = bitcoiny.getCurrencyCode();

		long minFillLocalAmount = tradeBotCreateRequest.minFillLocalAmount != null ? tradeBotCreateRequest.minFillLocalAmount : tradeBotCreateRequest.localAmount;
		long maxFillLocalAmount = tradeBotCreateRequest.maxFillLocalAmount != null ? tradeBotCreateRequest.maxFillLocalAmount : tradeBotCreateRequest.localAmount;

		byte[] tradePrivateKey = TradeBot.generateTradePrivateKey();
		byte[] tradeLocalPublicKey = TradeBot.deriveTradeLocalPublicKey(tradePrivateKey);
		byte[] tradeLocalPublicKeyHash = Crypto.hash160(tradeLocalPublicKey);
		String tradeLocalAddress = Crypto.toAddress(tradeLocalPublicKey);
		byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);

		BitcoinyAddress foreignReceivingAddress;
		try {
			foreignReceivingAddress = BitcoinyAddress.fromString(bitcoiny.getNetworkParameters(), tradeBotCreateRequest.receivingAddress);
		} catch (IllegalArgumentException e) {
			throw new DataException(String.format("Unsupported %s receiving address: %s", foreignCurrencyCode, tradeBotCreateRequest.receivingAddress));
		}
		if (!foreignReceivingAddress.isP2PKH())
			throw new DataException(String.format("Unsupported %s receiving address: %s", foreignCurrencyCode, tradeBotCreateRequest.receivingAddress));

		PublicKeyAccount creator = new PublicKeyAccount(repository, tradeBotCreateRequest.creatorPublicKey);
		long timestamp = NTP.getTime();
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, creator.getPublicKey(), 0L, null);

		AssetData localAssetData = repository.getAssetRepository().fromAssetId(tradeBotCreateRequest.localAssetId);
		if (localAssetData == null)
			throw new DataException("Local asset does not exist: " + tradeBotCreateRequest.localAssetId);

		String localAssetLabel = localAssetData.getName() != null ? localAssetData.getName() : "asset-" + tradeBotCreateRequest.localAssetId;
		byte[] creationBytes = BitcoinyACCTv4.buildTradeAT(foreignBlockchain, tradeLocalAddress, tradeForeignPublicKeyHash,
				tradeBotCreateRequest.localAmount, tradeBotCreateRequest.foreignAmount, minFillLocalAmount, maxFillLocalAmount,
				tradeBotCreateRequest.tradeTimeout);

		DeployAtTransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData,
				String.format("%s/%s split ACCT", localAssetLabel, foreignCurrencyCode),
				String.format("%s/%s split-fill cross-chain trade", localAssetLabel, foreignCurrencyCode),
				"ACCT",
				String.format("ACCT split asset-%d %s", tradeBotCreateRequest.localAssetId, foreignCurrencyCode),
				creationBytes, tradeBotCreateRequest.fundingLocalAmount, tradeBotCreateRequest.localAssetId,
				tradeBotCreateRequest.nativeFeeReserve);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		deployAtTransactionData.setFee(deployAtTransaction.calcRecommendedFee());
		DeployAtTransaction.ensureATAddress(deployAtTransactionData);
		String atAddress = deployAtTransactionData.getAtAddress();

		TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, BitcoinyACCTv4.NAME,
				State.MAKER_WAITING_FOR_AT_CONFIRM.name(), State.MAKER_WAITING_FOR_AT_CONFIRM.value,
				creator.getAddress(), atAddress, timestamp, tradeBotCreateRequest.localAssetId, tradeBotCreateRequest.localAmount,
				tradeLocalPublicKey, tradeLocalPublicKeyHash, tradeLocalAddress, null, null, foreignBlockchain.name(),
				tradeForeignPublicKey, tradeForeignPublicKeyHash, tradeBotCreateRequest.foreignAmount, null, null, null,
				foreignReceivingAddress.getPayload());

		TradeBot.updateTradeBotState(repository, tradeBotData, () -> String.format("Built split-fill AT %s. Waiting for deployment", atAddress));
		TradeBot.backupTradeBotData(repository, null);

		try {
			return DeployAtTransactionTransformer.toBytes(deployAtTransactionData);
		} catch (TransformationException e) {
			throw new DataException("Failed to transform DEPLOY_AT transaction?", e);
		}
	}

	@Override
	public ResponseResult startResponse(Repository repository, ATData atData, ACCT acct, CrossChainTradeData crossChainTradeData,
			String foreignKey, String receivingAddress) throws DataException {
		return startResponse(repository, atData, acct, crossChainTradeData, foreignKey, receivingAddress, null);
	}

	public ResponseResult startResponse(Repository repository, ATData atData, ACCT acct, CrossChainTradeData crossChainTradeData,
			String foreignKey, String receivingAddress, Long requestedFillLocalAmount) throws DataException {
		Bitcoiny bitcoiny = getBitcoiny(crossChainTradeData.foreignBlockchain);

		long fillLocalAmount = selectFillLocalAmount(crossChainTradeData, requestedFillLocalAmount);
		if (fillLocalAmount <= 0)
			return ResponseResult.INVALID_CRITERIA;

		long fillForeignAmount = calculateFillForeignAmount(crossChainTradeData, fillLocalAmount);
		if (fillForeignAmount < bitcoiny.getMinimumOrderAmount())
			return ResponseResult.INVALID_CRITERIA;

		byte[] tradePrivateKey = TradeBot.generateTradePrivateKey();
		byte[] secretA = TradeBot.generateSecret();
		byte[] hashOfSecretA = Crypto.hash160(secretA);
		byte[] tradeLocalPublicKey = TradeBot.deriveTradeLocalPublicKey(tradePrivateKey);
		byte[] tradeLocalPublicKeyHash = Crypto.hash160(tradeLocalPublicKey);
		String tradeLocalAddress = Crypto.toAddress(tradeLocalPublicKey);
		byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);
		byte[] receivingAccountInfo = Base58.decode(receivingAddress);

		long now = NTP.getTime();
		int lockTimeA = crossChainTradeData.tradeTimeout * 60 + (int) (now / 1000L);

		TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, BitcoinyACCTv4.NAME,
				State.TAKER_WAITING_FOR_AT_LOCK.name(), State.TAKER_WAITING_FOR_AT_LOCK.value,
				receivingAddress, crossChainTradeData.atAddress, now, crossChainTradeData.localAssetId, fillLocalAmount,
				tradeLocalPublicKey, tradeLocalPublicKeyHash, tradeLocalAddress, secretA, hashOfSecretA,
				crossChainTradeData.foreignBlockchain, tradeForeignPublicKey, tradeForeignPublicKeyHash,
				fillForeignAmount, foreignKey, null, lockTimeA, receivingAccountInfo);

		TradeBot.backupTradeBotData(repository, Arrays.asList(tradeBotData));

		long p2shFee;
		try {
			p2shFee = bitcoiny.getP2shFee(now);
		} catch (ForeignBlockchainException e) {
			return ResponseResult.NETWORK_ISSUE;
		}

		long amountA = fillForeignAmount + p2shFee;
		byte[] redeemScriptBytes = BitcoinyHTLC.buildScript(tradeForeignPublicKeyHash, lockTimeA, crossChainTradeData.creatorForeignPKH, hashOfSecretA);
		String p2shAddress = bitcoiny.deriveP2shAddress(redeemScriptBytes);
		BitcoinySignedTransaction p2shFundingTransaction = bitcoiny.buildSpendTransaction(foreignKey, p2shAddress, amountA);
		if (p2shFundingTransaction == null)
			return ResponseResult.BALANCE_ISSUE;

		try {
			bitcoiny.broadcastTransaction(p2shFundingTransaction);
		} catch (ForeignBlockchainException e) {
			return ResponseResult.NETWORK_ISSUE;
		}

		byte[] messageData = BitcoinyACCTv4.buildOfferMessage(tradeForeignPublicKeyHash, hashOfSecretA, lockTimeA, fillLocalAmount, fillForeignAmount);
		if (!sendMessage(repository, tradeBotData, crossChainTradeData.creatorTradeAddress, messageData))
			return ResponseResult.NETWORK_ISSUE;

		TradeBot.updateTradeBotState(repository, tradeBotData,
				() -> String.format("Funding P2SH-A %s for split fill. Messaged maker. Waiting for AT-lock", p2shAddress));

		return ResponseResult.OK;
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
			tradeData = BitcoinyACCTv4.getInstance().populateTradeData(repository, atData);

		switch (tradeBotState) {
			case MAKER_WAITING_FOR_AT_CONFIRM:
				handleMakerWaitingForAtConfirm(repository, tradeBotData);
				break;

			case MAKER_WAITING_FOR_TAKER_MESSAGE:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, tradeData);
				handleMakerWaitingForTakerMessage(repository, tradeBotData, atData, tradeData);
				break;

			case TAKER_WAITING_FOR_AT_LOCK:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, tradeData);
				handleTakerWaitingForAtLock(repository, tradeBotData, atData, tradeData);
				break;

			case TAKER_REFUNDING_FOREIGN:
				TradeBot.getInstance().updatePresence(repository, tradeBotData, tradeData);
				handleTakerRefundingP2shA(repository, tradeBotData, tradeData);
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
				() -> String.format("Split-fill AT %s confirmed ready. Waiting for fill messages", tradeBotData.getAtAddress()));
	}

	private void handleMakerWaitingForTakerMessage(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException, ForeignBlockchainException {
		List<TradeBotFillData> fillDataList = repository.getCrossChainRepository().getTradeBotFillData(tradeBotData.getAtAddress());
		processMakerFills(repository, tradeBotData, tradeData, fillDataList);
		fillDataList = repository.getCrossChainRepository().getTradeBotFillData(tradeBotData.getAtAddress());

		if (atData.getIsFinished()) {
			TradeBot.updateTradeBotState(repository, tradeBotData,
					tradeData.completedLocalAmount > 0 ? State.MAKER_DONE : State.MAKER_REFUNDED,
					() -> String.format("Split-fill AT %s finished", tradeBotData.getAtAddress()));
			return;
		}

		if (tradeData.mode == AcctMode.CANCELLED || tradeData.remainingLocalAmount <= 0 || tradeData.availableFillSlots <= 0)
			return;

		long pendingLocalAmount = pendingLocalAmount(tradeData, fillDataList);
		if (tradeData.remainingLocalAmount - pendingLocalAmount <= 0 || firstAvailableSlot(tradeData, fillDataList) < 0)
			return;

		Bitcoiny bitcoiny = getBitcoiny(tradeBotData.getForeignBlockchain());
		List<MessageTransactionData> messageTransactionsData = repository.getMessageRepository().getMessagesByParticipants(null,
				tradeBotData.getTradeLocalAddress(), null, null, null);

		for (MessageTransactionData messageTransactionData : messageTransactionsData) {
			if (messageTransactionData.isText())
				continue;

			BitcoinyACCTv4.OfferMessageData offerMessageData = BitcoinyACCTv4.extractOfferMessageData(messageTransactionData.getData());
			if (offerMessageData == null || hasFillRecord(fillDataList, offerMessageData.hashOfSecretA))
				continue;

			if (!isValidFill(tradeData, fillDataList, offerMessageData.fillLocalAmount, offerMessageData.fillForeignAmount))
				continue;

			int slotIndex = firstAvailableSlot(tradeData, fillDataList);
			if (slotIndex < 0)
				return;

			long feeTimestamp = calcFeeTimestamp((int) offerMessageData.lockTimeA, tradeData.tradeTimeout);
			long p2shFee = bitcoiny.getP2shFee(feeTimestamp);
			long minimumAmountA = offerMessageData.fillForeignAmount + p2shFee;
			byte[] redeemScriptA = BitcoinyHTLC.buildScript(offerMessageData.partnerForeignPKH, (int) offerMessageData.lockTimeA,
					tradeBotData.getTradeForeignPublicKeyHash(), offerMessageData.hashOfSecretA);
			String p2shAddressA = bitcoiny.deriveP2shAddress(redeemScriptA);

			BitcoinyHTLC.Status htlcStatusA = BitcoinyHTLC.determineHtlcStatus(bitcoiny, p2shAddressA, minimumAmountA);
			if (htlcStatusA != BitcoinyHTLC.Status.FUNDED)
				continue;

			String takerLocalAddress = Crypto.toAddress(messageTransactionData.getCreatorPublicKey());
			int refundTimeout = BitcoinyACCTv4.calcRefundTimeout(messageTransactionData.getTimestamp(), (int) offerMessageData.lockTimeA);
			byte[] outgoingMessageData = BitcoinyACCTv4.buildTradeMessage(slotIndex, takerLocalAddress, offerMessageData.partnerForeignPKH,
					offerMessageData.hashOfSecretA, (int) offerMessageData.lockTimeA, refundTimeout,
					offerMessageData.fillLocalAmount, offerMessageData.fillForeignAmount);

			if (!sendMessage(repository, tradeBotData, tradeBotData.getAtAddress(), outgoingMessageData))
				return;

			TradeBotFillData fillData = new TradeBotFillData(tradeBotData.getAtAddress(), slotIndex, FILL_ACTIVE, NTP.getTime(),
					takerLocalAddress, offerMessageData.partnerForeignPKH, offerMessageData.hashOfSecretA,
					(int) offerMessageData.lockTimeA, offerMessageData.fillLocalAmount, offerMessageData.fillForeignAmount, p2shAddressA);
			repository.getCrossChainRepository().save(fillData);
			repository.saveChanges();
			TradeBot.backupTradeBotData(repository, null);
			return;
		}
	}

	private void processMakerFills(Repository repository, TradeBotData tradeBotData, CrossChainTradeData tradeData, List<TradeBotFillData> fillDataList)
			throws DataException, ForeignBlockchainException {
		Bitcoiny bitcoiny = getBitcoiny(tradeBotData.getForeignBlockchain());
		for (TradeBotFillData fillData : fillDataList) {
			if (!FILL_ACTIVE.equals(fillData.getState()))
				continue;

			byte[] secretA = BitcoinyACCTv4.findSecretA(repository, fillData.getAtAddress(), fillData.getSlotIndex(),
					fillData.getPartnerAddress(), fillData.getHashOfSecret());
			if (secretA == null) {
				if (refundExpiredPendingFill(repository, tradeData, fillData, NTP.getTime())) {
					repository.saveChanges();
					TradeBot.backupTradeBotData(repository, null);
				}

				continue;
			}

			long feeTimestamp = calcFeeTimestamp(fillData.getLockTimeA(), tradeData.tradeTimeout);
			long p2shFee = bitcoiny.getP2shFee(feeTimestamp);
			long minimumAmountA = fillData.getForeignAmount() + p2shFee;
			BitcoinyHTLC.Status htlcStatusA = BitcoinyHTLC.determineHtlcStatus(bitcoiny, fillData.getP2shAddress(), minimumAmountA);

			switch (htlcStatusA) {
				case UNFUNDED:
				case FUNDING_IN_PROGRESS:
				case REFUND_IN_PROGRESS:
				case REFUNDED:
					continue;

				case REDEEM_IN_PROGRESS:
				case REDEEMED:
					break;

				case FUNDED: {
					byte[] redeemScriptA = BitcoinyHTLC.buildScript(fillData.getPartnerForeignPublicKeyHash(), fillData.getLockTimeA(),
							tradeBotData.getTradeForeignPublicKeyHash(), fillData.getHashOfSecret());
					BitcoinySignedTransaction p2shRedeemTransaction = bitcoiny.buildHtlcRedeemTransaction(Coin.valueOf(fillData.getForeignAmount()),
							ECKey.fromPrivate(tradeBotData.getTradePrivateKey()), bitcoiny.getUnspentOutputs(fillData.getP2shAddress(), false),
							redeemScriptA, secretA, tradeBotData.getReceivingAccountInfo());
					bitcoiny.broadcastTransaction(p2shRedeemTransaction);
					break;
				}
			}

			fillData.setState(FILL_DONE);
			fillData.setTimestamp(NTP.getTime());
			repository.getCrossChainRepository().save(fillData);
			repository.saveChanges();
			TradeBot.backupTradeBotData(repository, null);
		}
	}

	private void handleTakerWaitingForAtLock(Repository repository, TradeBotData tradeBotData, ATData atData,
			CrossChainTradeData tradeData) throws DataException {
		if (NTP.getTime() >= tradeBotData.getLockTimeA() * 1000L || atData.getIsFinished()) {
			TradeBot.updateTradeBotState(repository, tradeBotData, State.TAKER_REFUNDING_FOREIGN,
					() -> String.format("Split-fill AT %s did not lock in time. Refunding P2SH-A", tradeBotData.getAtAddress()));
			return;
		}

		Optional<CrossChainTradeData.Fill> fillOptional = tradeData.fills.stream()
				.filter(fill -> tradeBotData.getTradeLocalAddress().equals(fill.partnerAddress))
				.filter(fill -> Arrays.equals(tradeBotData.getHashOfSecret(), fill.hashOfSecretA))
				.findFirst();
		if (fillOptional.isEmpty())
			return;

		CrossChainTradeData.Fill fill = fillOptional.get();
		if (fill.localAmount != tradeBotData.getLocalAmount() || fill.expectedForeignAmount != tradeBotData.getForeignAmount()
				|| fill.lockTimeA != tradeBotData.getLockTimeA())
			return;

		tradeBotData.setFillSlotIndex(fill.slotIndex);
		String receivingAddress = Base58.encode(tradeBotData.getReceivingAccountInfo());
		byte[] messageData = BitcoinyACCTv4.buildRedeemMessage(fill.slotIndex, tradeBotData.getSecret(), receivingAddress);
		if (!sendMessage(repository, tradeBotData, tradeBotData.getAtAddress(), messageData))
			return;

		TradeBot.updateTradeBotState(repository, tradeBotData, State.TAKER_DONE,
				() -> String.format("Redeeming split-fill slot %d at AT %s. Funds should arrive at %s",
						fill.slotIndex, tradeBotData.getAtAddress(), receivingAddress));
	}

	private void handleTakerRefundingP2shA(Repository repository, TradeBotData tradeBotData, CrossChainTradeData tradeData)
			throws DataException, ForeignBlockchainException {
		int lockTimeA = tradeBotData.getLockTimeA();
		if (NTP.getTime() <= lockTimeA * 1000L)
			return;

		Bitcoiny bitcoiny = getBitcoiny(tradeBotData.getForeignBlockchain());
		if (bitcoiny.getMedianBlockTime() <= lockTimeA)
			return;

		byte[] redeemScriptA = BitcoinyHTLC.buildScript(tradeBotData.getTradeForeignPublicKeyHash(), lockTimeA,
				tradeData.creatorForeignPKH, tradeBotData.getHashOfSecret());
		String p2shAddressA = bitcoiny.deriveP2shAddress(redeemScriptA);
		long p2shFee = bitcoiny.getP2shFee(calcFeeTimestamp(lockTimeA, tradeData.tradeTimeout));
		BitcoinyHTLC.Status htlcStatusA = BitcoinyHTLC.determineHtlcStatus(bitcoiny, p2shAddressA, tradeBotData.getForeignAmount() + p2shFee);
		if (htlcStatusA == BitcoinyHTLC.Status.FUNDED) {
			String receiveAddress = bitcoiny.getUnusedReceiveAddress(tradeBotData.getForeignKey());
			BitcoinyAddress receiving = BitcoinyAddress.fromString(bitcoiny.getNetworkParameters(), receiveAddress);
			BitcoinySignedTransaction p2shRefundTransaction = bitcoiny.buildHtlcRefundTransaction(Coin.valueOf(tradeBotData.getForeignAmount()),
					ECKey.fromPrivate(tradeBotData.getTradePrivateKey()), bitcoiny.getUnspentOutputs(p2shAddressA, false),
					redeemScriptA, lockTimeA, receiving.getPayload());
			bitcoiny.broadcastTransaction(p2shRefundTransaction);
		}

		TradeBot.updateTradeBotState(repository, tradeBotData, State.TAKER_REFUNDED,
				() -> String.format("LockTime-A reached. Refunded split-fill P2SH-A %s. Trade aborted", p2shAddressA));
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

	static boolean refundExpiredPendingFill(Repository repository, CrossChainTradeData tradeData, TradeBotFillData fillData, long now)
			throws DataException {
		if (!isExpiredPendingFill(tradeData, fillData, now))
			return false;

		fillData.setState(FILL_REFUNDED);
		fillData.setTimestamp(now);
		repository.getCrossChainRepository().save(fillData);
		return true;
	}

	static boolean isExpiredPendingFill(CrossChainTradeData tradeData, TradeBotFillData fillData, long now) {
		return isPendingFill(tradeData, fillData) && now > fillData.getTimestamp() + tradeData.tradeTimeout * 60_000L;
	}

	private static long selectFillLocalAmount(CrossChainTradeData tradeData, Long requestedFillLocalAmount) {
		long maxFill = Math.min(tradeData.maxFillLocalAmount, tradeData.remainingLocalAmount);
		long fillLocalAmount = requestedFillLocalAmount != null ? requestedFillLocalAmount : maxFill;
		if (requestedFillLocalAmount == null && !leavesUsableRemainder(tradeData, fillLocalAmount, tradeData.remainingLocalAmount))
			fillLocalAmount = tradeData.remainingLocalAmount - tradeData.minFillLocalAmount;

		if (fillLocalAmount > maxFill || !tradeData.isFillableAmount(fillLocalAmount))
			return -1L;

		return fillLocalAmount;
	}

	private static boolean isValidFill(CrossChainTradeData tradeData, List<TradeBotFillData> fillDataList,
				long fillLocalAmount, long fillForeignAmount) {
		long availableLocalAmount = tradeData.remainingLocalAmount - pendingLocalAmount(tradeData, fillDataList);

		return fillLocalAmount >= tradeData.minFillLocalAmount
				&& fillLocalAmount <= tradeData.maxFillLocalAmount
				&& fillLocalAmount <= availableLocalAmount
				&& leavesUsableRemainder(tradeData, fillLocalAmount, availableLocalAmount)
				&& fillForeignAmount == calculateFillForeignAmount(tradeData, fillLocalAmount);
	}

	private static boolean leavesUsableRemainder(CrossChainTradeData tradeData, long fillLocalAmount, long availableLocalAmount) {
		long remainingAfterFill = availableLocalAmount - fillLocalAmount;
		return remainingAfterFill == 0 || remainingAfterFill >= tradeData.minFillLocalAmount;
	}

	private static long calculateFillForeignAmount(CrossChainTradeData tradeData, long fillLocalAmount) {
		return BigInteger.valueOf(fillLocalAmount)
				.multiply(BigInteger.valueOf(tradeData.expectedForeignAmount))
				.add(BigInteger.valueOf(tradeData.totalLocalAmount).subtract(BigInteger.ONE))
				.divide(BigInteger.valueOf(tradeData.totalLocalAmount))
				.longValue();
	}

	private static int firstAvailableSlot(CrossChainTradeData tradeData, List<TradeBotFillData> fillDataList) {
		for (int slot = 0; slot < BitcoinyACCTv4.SLOT_COUNT; ++slot) {
			final int slotIndex = slot;
			if (tradeData.fills.stream().noneMatch(fill -> fill.slotIndex == slotIndex)) {
				if (fillDataList.stream().noneMatch(fillData -> FILL_ACTIVE.equals(fillData.getState()) && fillData.getSlotIndex() == slotIndex)) {
					return slot;
				}
			}
		}

		return -1;
	}

	private static boolean hasFillRecord(List<TradeBotFillData> fillDataList, byte[] hashOfSecretA) {
		return fillDataList.stream().anyMatch(fillData -> Arrays.equals(fillData.getHashOfSecret(), hashOfSecretA));
	}

	static boolean isPendingFill(CrossChainTradeData tradeData, TradeBotFillData fillData) {
		return FILL_ACTIVE.equals(fillData.getState())
				&& tradeData.fills.stream().noneMatch(fill -> Arrays.equals(fill.hashOfSecretA, fillData.getHashOfSecret()));
	}

	static long pendingLocalAmount(CrossChainTradeData tradeData, List<TradeBotFillData> fillDataList) {
		return fillDataList.stream()
				.filter(fillData -> isPendingFill(tradeData, fillData))
				.mapToLong(TradeBotFillData::getLocalAmount)
				.sum();
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

	private long calcFeeTimestamp(int lockTimeA, int tradeTimeout) {
		return (lockTimeA - tradeTimeout * 60) * 1000L;
	}
}
