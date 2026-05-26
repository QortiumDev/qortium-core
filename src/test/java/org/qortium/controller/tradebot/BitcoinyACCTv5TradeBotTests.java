package org.qortium.controller.tradebot;

import cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock;
import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.model.crosschain.TradeBotCreateRequest;
import org.qortium.asset.Asset;
import org.qortium.crosschain.AcctMode;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinyACCTv5;
import org.qortium.crosschain.BitcoinyAddress;
import org.qortium.crosschain.BitcoinyBlockchainProvider;
import org.qortium.crosschain.BitcoinyHTLC;
import org.qortium.crosschain.BitcoinySignedTransaction;
import org.qortium.crosschain.BitcoinyTransaction;
import org.qortium.crosschain.ChainableServer;
import org.qortium.crosschain.ChainableServerConnection;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.TradeDirection;
import org.qortium.crosschain.TransactionHash;
import org.qortium.crosschain.UnspentOutput;
import org.qortium.crypto.Crypto;
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
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AccountUtils;
import org.qortium.test.common.AssetUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.MessageTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;
import org.qortium.utils.Amounts;
import org.qortium.utils.Base58;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;

public class BitcoinyACCTv5TradeBotTests extends Common {

	private static final String XPRV = "xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg";
	private static final byte[] MAKER_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("aa00aa11aa22aa33aa44aa55aa66aa77aa88aa99").asBytes();
	private static final byte[] BTC_RECEIVING_PUBLIC_KEY_HASH = HashCode.fromString("cc00cc11cc22cc33cc44cc55cc66cc77cc88cc99").asBytes();
	private static final byte[] SECRET_A = "This string is exactly 32 bytes!".getBytes();
	private static final byte[] HASH_OF_SECRET_A = Crypto.hash160(SECRET_A);
	private static final long LOCAL_AMOUNT = 25L * Amounts.MULTIPLIER;
	private static final long FOREIGN_AMOUNT = 100_000L;
	private static final long NATIVE_FEE_RESERVE = 3L * Amounts.MULTIPLIER;
	private static final long TEST_MESSAGE_FEE = Amounts.MULTIPLIER / 100L;
	private static final int TRADE_TIMEOUT = 120;
	private static final int SHORT_TRADE_TIMEOUT = 6;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		BitcoinyACCTv5TradeBot.getInstance().resetTestHooks();
		BitcoinyACCTv5TradeBot.getInstance().setMessageFeeOverrideForTesting(TEST_MESSAGE_FEE);
		BitcoinyACCTv5TradeBot.getInstance().setMessageSubmitterForTesting((repository, messageTransaction, sender) -> {
			messageTransaction.getTransactionData().setFee(TEST_MESSAGE_FEE);
			TransactionUtils.signAndMint(repository, messageTransaction.getTransactionData(), sender);
			return Transaction.ValidationResult.OK;
		});
	}

	@After
	public void afterTest() {
		BitcoinyACCTv5TradeBot.getInstance().resetTestHooks();
	}

	@Test
	public void testCreateTradeRoutesSellForeignToV5() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			installMockBitcoiny(BitcoinyHTLC.Status.UNFUNDED);

			PrivateKeyAccount creator = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount localReceiving = Common.getTestAccount(repository, "bob");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "V5-BOT-CREATE", 100L * Amounts.MULTIPLIER, true);

			TradeBotCreateRequest request = new TradeBotCreateRequest();
			request.creatorPublicKey = creator.getPublicKey();
			request.tradeDirection = TradeDirection.SELL_FOREIGN;
			request.localAssetId = localAssetId;
			request.localAmount = LOCAL_AMOUNT;
			request.fundingLocalAmount = 0L;
			request.nativeFeeReserve = NATIVE_FEE_RESERVE;
			request.foreignBlockchain = "BITCOIN";
			request.foreignAmount = FOREIGN_AMOUNT;
			request.tradeTimeout = TRADE_TIMEOUT;
			request.receivingAddress = localReceiving.getAddress();
			request.foreignKey = XPRV;

			byte[] unsignedDeployBytes = TradeBot.getInstance().createTrade(repository, request);

			assertNotNull(unsignedDeployBytes);
			List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();
			assertEquals(1, allTradeBotData.size());

			TradeBotData tradeBotData = allTradeBotData.get(0);
			assertEquals(BitcoinyACCTv5.NAME, tradeBotData.getAcctName());
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_AT_CONFIRM.name(), tradeBotData.getState());
			assertEquals(localAssetId, tradeBotData.getLocalAssetId());
			assertEquals(LOCAL_AMOUNT, tradeBotData.getLocalAmount());
			assertEquals(FOREIGN_AMOUNT, tradeBotData.getForeignAmount());
			assertNotNull(tradeBotData.getSecret());
			assertArrayEquals(Crypto.hash160(tradeBotData.getSecret()), tradeBotData.getHashOfSecret());
		}
	}

	@Test
	public void testReverseResponseReturnsUnsignedReservationMessage() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			installMockBitcoiny(BitcoinyHTLC.Status.UNFUNDED);

			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount responder = Common.getTestAccount(repository, "dilbert");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "V5-BOT-RESPOND", 100L * Amounts.MULTIPLIER, true);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

			byte[] unsignedMessageBytes = BitcoinyACCTv5TradeBot.getInstance().startResponse(repository, atData, tradeData,
					responder.getPublicKey(), btcReceivingAddress());
			TransactionData transactionData = fromUnsignedBytes(unsignedMessageBytes);

			assertTrue(transactionData instanceof MessageTransactionData);
			MessageTransactionData messageData = (MessageTransactionData) transactionData;
			assertArrayEquals(responder.getPublicKey(), messageData.getSenderPublicKey());
			assertEquals(atAddress, messageData.getRecipient());
			assertEquals(0L, messageData.getAmount());
			assertNull(messageData.getAssetId());
			assertEquals(BitcoinyACCTv5.RESERVE_MESSAGE_LENGTH, messageData.getData().length);

			byte[] reserveMessageData = messageData.getData();
			byte[] takerForeignPublicKeyHash = Arrays.copyOfRange(reserveMessageData, 0, 20);
			tradeData.partnerForeignPKH = takerForeignPublicKeyHash;
			tradeData.lockTimeA = (int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L);
			assertArrayEquals(BitcoinyHTLC.buildScript(MAKER_FOREIGN_PUBLIC_KEY_HASH, tradeData.lockTimeA, takerForeignPublicKeyHash, HASH_OF_SECRET_A),
					BitcoinyACCTv5TradeBot.buildRedeemScript(tradeData));

			List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();
			assertEquals(1, allTradeBotData.size());
			assertEquals(BitcoinyACCTv5.NAME, allTradeBotData.get(0).getAcctName());
			assertEquals(TradeStates.State.TAKER_WAITING_FOR_FOREIGN_LOCK.name(), allTradeBotData.get(0).getState());
			assertEquals(atAddress, allTradeBotData.get(0).getAtAddress());
			assertEquals(Crypto.toAddress(responder.getPublicKey()), allTradeBotData.get(0).getTradeLocalAddress());
			assertArrayEquals(HASH_OF_SECRET_A, allTradeBotData.get(0).getHashOfSecret());
		}
	}

	@Test
	public void testLocalLockRequiresForeignLocktimeSafetyMargin() {
		CrossChainTradeData tradeData = new CrossChainTradeData();
		tradeData.tradeTimeout = TRADE_TIMEOUT;

		long now = 1_765_000_000_000L;
		long localRefundSeconds = BitcoinyACCTv5.calcLocalRefundTimeout(TRADE_TIMEOUT) * 60L;
		long safetyMarginSeconds = BitcoinyACCTv5TradeBot.FOREIGN_LOCKTIME_SAFETY_MARGIN_MINUTES * 60L;

		tradeData.lockTimeA = null;
		assertFalse(BitcoinyACCTv5TradeBot.hasSufficientTimeForLocalLock(tradeData, now));

		tradeData.lockTimeA = (int) (now / 1000L + localRefundSeconds + safetyMarginSeconds);
		assertFalse(BitcoinyACCTv5TradeBot.hasSufficientTimeForLocalLock(tradeData, now));

		tradeData.lockTimeA = (int) (now / 1000L + localRefundSeconds + safetyMarginSeconds + 1L);
		assertTrue(BitcoinyACCTv5TradeBot.hasSufficientTimeForLocalLock(tradeData, now));
	}

	@Test
	public void testMakerProgressFundsAndDeclaresForeignHtlc() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ReverseTradeSetup setup = setupReservedMakerTrade(repository, "V5-BOT-MAKER-FUND");
			MockBitcoiny bitcoiny = installMockBitcoiny(BitcoinyHTLC.Status.UNFUNDED);

			BitcoinyACCTv5TradeBot.getInstance().progress(repository, setup.makerTradeBotData);

			assertEquals(1, bitcoiny.spendTransactionCount);
			assertEquals(1, bitcoiny.broadcastTransactions.size());
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_TAKER_MESSAGE.name(), setup.makerTradeBotData.getState());
			assertNotNull(setup.makerTradeBotData.getLockTimeA());

			setMockHtlcStatus(BitcoinyHTLC.Status.FUNDED);
			BitcoinyACCTv5TradeBot.getInstance().progress(repository, setup.makerTradeBotData);
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_LOCAL_LOCK.name(), setup.makerTradeBotData.getState());

			BlockUtils.mintBlock(repository);

			ATData atData = repository.getATRepository().fromATAddress(setup.atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);
			assertEquals(AcctMode.FOREIGN_LOCKED, tradeData.mode);
			assertEquals(setup.makerTradeBotData.getLockTimeA(), tradeData.lockTimeA);
		}
	}

	@Test
	public void testMakerProgressCancelsStaleUnfundedReservation() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ReverseTradeSetup setup = setupReservedMakerTrade(repository, "V5-BOT-MAKER-STALE");
			int lockTimeA = (int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L);
			setup.makerTradeBotData.setLockTimeA(lockTimeA);
			setup.makerTradeBotData.setTimestamp(System.currentTimeMillis() - (BitcoinyACCTv5TradeBot.RESERVATION_TIMEOUT_MINUTES + 1L) * 60L * 1000L);
			repository.getCrossChainRepository().save(setup.makerTradeBotData);
			repository.saveChanges();

			installMockBitcoiny(BitcoinyHTLC.Status.UNFUNDED);

			BitcoinyACCTv5TradeBot.getInstance().progress(repository, setup.makerTradeBotData);
			BlockUtils.mintBlock(repository);

			ATData atData = repository.getATRepository().fromATAddress(setup.atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);
			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.CANCELLED, tradeData.mode);

			BitcoinyACCTv5TradeBot.getInstance().progress(repository, setup.makerTradeBotData);
			assertEquals(TradeStates.State.MAKER_REFUNDED.name(), setup.makerTradeBotData.getState());
		}
	}

	@Test
	public void testMakerProgressCancelsUnsafeForeignLocktime() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ReverseTradeSetup setup = setupReservedMakerTrade(repository, "V5-BOT-MAKER-UNSAFE");
			long localRefundSeconds = BitcoinyACCTv5.calcLocalRefundTimeout(TRADE_TIMEOUT) * 60L;
			long safetyMarginSeconds = BitcoinyACCTv5TradeBot.FOREIGN_LOCKTIME_SAFETY_MARGIN_MINUTES * 60L;
			int unsafeLockTimeA = (int) (System.currentTimeMillis() / 1000L + localRefundSeconds + safetyMarginSeconds);
			declareForeignLock(repository, setup.makerTradeAccount, setup.atAddress, unsafeLockTimeA);

			setup.makerTradeBotData.setLockTimeA(unsafeLockTimeA);
			TradeBot.updateTradeBotState(repository, setup.makerTradeBotData, TradeStates.State.MAKER_WAITING_FOR_LOCAL_LOCK,
					() -> "Waiting for local lock in unsafe-locktime test");
			installMockBitcoiny(BitcoinyHTLC.Status.FUNDED);

			BitcoinyACCTv5TradeBot.getInstance().progress(repository, setup.makerTradeBotData);
			BlockUtils.mintBlock(repository);

			ATData atData = repository.getATRepository().fromATAddress(setup.atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);
			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.CANCELLED, tradeData.mode);
		}
	}

	@Test
	public void testTakerBuildLocalLockTransactionAfterFundedForeignHtlc() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ReverseTradeSetup setup = setupReservedMakerTrade(repository, "V5-BOT-TAKER-LOCK");
			TradeBotData takerTradeBotData = saveTakerTradeBotData(repository, setup);
			int lockTimeA = (int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L);
			declareForeignLock(repository, setup.makerTradeAccount, setup.atAddress, lockTimeA);

			installMockBitcoiny(BitcoinyHTLC.Status.FUNDED);

			ATData atData = repository.getATRepository().fromATAddress(setup.atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);
			byte[] unsignedMessageBytes = BitcoinyACCTv5TradeBot.getInstance().buildLocalLockTransaction(repository, atData, tradeData,
					setup.taker.getPublicKey());
			TransactionData transactionData = fromUnsignedBytes(unsignedMessageBytes);

			assertTrue(transactionData instanceof MessageTransactionData);
			MessageTransactionData messageData = (MessageTransactionData) transactionData;
			assertEquals(setup.atAddress, messageData.getRecipient());
			assertEquals(LOCAL_AMOUNT, messageData.getAmount());
			assertEquals(Long.valueOf(setup.localAssetId), messageData.getAssetId());
			assertArrayEquals(BitcoinyACCTv5.buildLocalLockMessage(), messageData.getData());

			takerTradeBotData = repository.getCrossChainRepository().getTradeBotData(takerTradeBotData.getTradePrivateKey());
			assertEquals(TradeStates.State.TAKER_WAITING_FOR_AT_LOCK.name(), takerTradeBotData.getState());
			assertEquals(Integer.valueOf(lockTimeA), takerTradeBotData.getLockTimeA());
		}
	}

	@Test
	public void testMakerProgressRedeemsLocalAssetAndRevealsSecret() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ReverseTradeSetup setup = setupTradingMakerTrade(repository, "V5-BOT-MAKER-REDEEM");
			installMockBitcoiny(BitcoinyHTLC.Status.FUNDED);

			BitcoinyACCTv5TradeBot.getInstance().progress(repository, setup.makerTradeBotData);
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_AT_REDEEM.name(), setup.makerTradeBotData.getState());
			BlockUtils.mintBlock(repository);

			ATData atData = repository.getATRepository().fromATAddress(setup.atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);
			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.REDEEMED, tradeData.mode);
			assertArrayEquals(SECRET_A, BitcoinyACCTv5.getInstance().findSecretA(repository, tradeData));

			BitcoinyACCTv5TradeBot.getInstance().progress(repository, setup.makerTradeBotData);
			assertEquals(TradeStates.State.MAKER_DONE.name(), setup.makerTradeBotData.getState());
		}
	}

	@Test
	public void testTakerProgressRedeemsForeignHtlcAfterMakerSecretReveal() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ReverseTradeSetup setup = setupTradingMakerTrade(repository, "V5-BOT-TAKER-REDEEM");
			TradeBotData takerTradeBotData = saveTakerTradeBotData(repository, setup);
			MockBitcoiny bitcoiny = installMockBitcoiny(BitcoinyHTLC.Status.FUNDED);

			byte[] redeemMessageData = BitcoinyACCTv5.buildRedeemMessage(SECRET_A, setup.makerReceiving.getAddress());
			sendMessage(repository, setup.makerTradeAccount, redeemMessageData, setup.atAddress);
			BlockUtils.mintBlock(repository);

			ATData atData = repository.getATRepository().fromATAddress(setup.atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);
			assertEquals(AcctMode.REDEEMED, tradeData.mode);

			TradeBot.updateTradeBotState(repository, takerTradeBotData, TradeStates.State.TAKER_WAITING_FOR_MAKER_REDEEM,
					() -> "Waiting for maker redeem in taker foreign HTLC test");
			BitcoinyACCTv5TradeBot.getInstance().progress(repository, takerTradeBotData);

			assertEquals(1, bitcoiny.redeemTransactionCount);
			assertEquals(1, bitcoiny.broadcastTransactions.size());
			assertEquals(TradeStates.State.TAKER_DONE.name(), takerTradeBotData.getState());
		}
	}

	@Test
	public void testMakerProgressRefundsForeignHtlcAfterLocalAssetRefund() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			ReverseTradeSetup setup = setupTradingMakerTrade(repository, "V5-BOT-MAKER-REFUND", SHORT_TRADE_TIMEOUT);
			MockBitcoiny bitcoiny = installMockBitcoiny(BitcoinyHTLC.Status.FUNDED);

			BlockUtils.mintBlocks(repository, BitcoinyACCTv5.calcLocalRefundTimeout(SHORT_TRADE_TIMEOUT) + 2);

			ATData atData = repository.getATRepository().fromATAddress(setup.atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);
			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.REFUNDED, tradeData.mode);

			int expiredLockTimeA = (int) (System.currentTimeMillis() / 1000L - 60L);
			setup.makerTradeBotData.setLockTimeA(expiredLockTimeA);
			repository.getCrossChainRepository().save(setup.makerTradeBotData);
			repository.saveChanges();
			bitcoiny.medianBlockTime = expiredLockTimeA + 1;

			BitcoinyACCTv5TradeBot.getInstance().progress(repository, setup.makerTradeBotData);

			assertEquals(1, bitcoiny.refundTransactionCount);
			assertEquals(1, bitcoiny.broadcastTransactions.size());
			assertEquals(TradeStates.State.MAKER_REFUNDED.name(), setup.makerTradeBotData.getState());
		}
	}

	private DeployAtTransaction deploy(Repository repository, PrivateKeyAccount deployer, String tradeAddress, long localAssetId) throws DataException {
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");
		byte[] creationBytes = BitcoinyACCTv5.buildTradeAT(bitcoin, tradeAddress, MAKER_FOREIGN_PUBLIC_KEY_HASH,
				HASH_OF_SECRET_A, LOCAL_AMOUNT, FOREIGN_AMOUNT, TRADE_TIMEOUT);

		long txTimestamp = System.currentTimeMillis();
		Long fee = null;
		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, deployer.getPublicKey(), fee, null);
		TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData,
				"BTC-asset reverse cross-chain trade", "Bitcoin-local asset reverse cross-chain trade", "ACCT",
				"BTC-asset reverse ACCT", creationBytes, 0L, localAssetId, NATIVE_FEE_RESERVE);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		deployAtTransactionData.setFee(deployAtTransaction.calcRecommendedFee());
		TransactionUtils.signAndMint(repository, deployAtTransactionData, deployer);

		return deployAtTransaction;
	}

	private ReverseTradeSetup setupReservedMakerTrade(Repository repository, String assetName) throws DataException {
		return setupReservedMakerTrade(repository, assetName, TRADE_TIMEOUT);
	}

	private ReverseTradeSetup setupReservedMakerTrade(Repository repository, String assetName, int tradeTimeout) throws DataException {
		PrivateKeyAccount funder = Common.getTestAccount(repository, "alice");
		PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
		PrivateKeyAccount makerReceiving = Common.getTestAccount(repository, "bob");
		PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
		PrivateKeyAccount makerTradeAccount = new PrivateKeyAccount(repository, TradeBot.generateTradePrivateKey());
		AccountUtils.pay(repository, funder, makerTradeAccount.getAddress(), 10L * Amounts.MULTIPLIER);

		long localAssetId = AssetUtils.issueAsset(repository, "alice", assetName, 100L * Amounts.MULTIPLIER, true);
		DeployAtTransaction deployAtTransaction = deploy(repository, deployer, makerTradeAccount, localAssetId, tradeTimeout);
		String atAddress = deployAtTransaction.getATAccount().getAddress();

		byte[] takerTradePrivateKey = TradeBot.generateTradePrivateKey();
		byte[] takerForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(takerTradePrivateKey);
		byte[] takerForeignPublicKeyHash = Crypto.hash160(takerForeignPublicKey);
		reserveTrade(repository, taker, atAddress, takerForeignPublicKeyHash);

		TradeBotData makerTradeBotData = saveMakerTradeBotData(repository, deployer, makerTradeAccount, makerReceiving, atAddress,
				localAssetId, TradeStates.State.MAKER_WAITING_FOR_TAKER_MESSAGE, null);

		ReverseTradeSetup setup = new ReverseTradeSetup();
		setup.makerTradeAccount = makerTradeAccount;
		setup.makerReceiving = makerReceiving;
		setup.taker = taker;
		setup.takerTradePrivateKey = takerTradePrivateKey;
		setup.takerForeignPublicKey = takerForeignPublicKey;
		setup.takerForeignPublicKeyHash = takerForeignPublicKeyHash;
		setup.localAssetId = localAssetId;
		setup.atAddress = atAddress;
		setup.makerTradeBotData = makerTradeBotData;
		return setup;
	}

	private ReverseTradeSetup setupTradingMakerTrade(Repository repository, String assetName) throws DataException {
		return setupTradingMakerTrade(repository, assetName, TRADE_TIMEOUT);
	}

	private ReverseTradeSetup setupTradingMakerTrade(Repository repository, String assetName, int tradeTimeout) throws DataException {
		ReverseTradeSetup setup = setupReservedMakerTrade(repository, assetName, tradeTimeout);
		int lockTimeA = (int) (System.currentTimeMillis() / 1000L + tradeTimeout * 60L);
		declareForeignLock(repository, setup.makerTradeAccount, setup.atAddress, lockTimeA);
		AssetUtils.transferAsset(repository, "alice", "dilbert", setup.localAssetId, LOCAL_AMOUNT);
		sendPaymentMessage(repository, setup.taker, BitcoinyACCTv5.buildLocalLockMessage(), setup.atAddress, LOCAL_AMOUNT, setup.localAssetId);
		BlockUtils.mintBlock(repository);

		setup.makerTradeBotData.setLockTimeA(lockTimeA);
		TradeBot.updateTradeBotState(repository, setup.makerTradeBotData, TradeStates.State.MAKER_WAITING_FOR_LOCAL_LOCK,
				() -> "Waiting for local lock in v5 tradebot setup");
		return setup;
	}

	private DeployAtTransaction deploy(Repository repository, PrivateKeyAccount deployer, PrivateKeyAccount makerTradeAccount,
			long localAssetId, int tradeTimeout) throws DataException {
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");
		byte[] makerForeignPublicKeyHash = Crypto.hash160(TradeBot.deriveTradeForeignPublicKey(makerTradeAccount.getPrivateKey()));
		byte[] creationBytes = BitcoinyACCTv5.buildTradeAT(bitcoin, makerTradeAccount.getAddress(), makerForeignPublicKeyHash,
				HASH_OF_SECRET_A, LOCAL_AMOUNT, FOREIGN_AMOUNT, tradeTimeout);

		long txTimestamp = System.currentTimeMillis();
		Long fee = null;
		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, deployer.getPublicKey(), fee, null);
		TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData,
				"BTC-asset reverse cross-chain trade", "Bitcoin-local asset reverse cross-chain trade", "ACCT",
				"BTC-asset reverse ACCT", creationBytes, 0L, localAssetId, NATIVE_FEE_RESERVE);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		deployAtTransactionData.setFee(deployAtTransaction.calcRecommendedFee());
		TransactionUtils.signAndMint(repository, deployAtTransactionData, deployer);

		return deployAtTransaction;
	}

	private TradeBotData saveMakerTradeBotData(Repository repository, PrivateKeyAccount deployer, PrivateKeyAccount makerTradeAccount,
			PrivateKeyAccount makerReceiving, String atAddress, long localAssetId, TradeStates.State state, Integer lockTimeA) throws DataException {
		byte[] tradePrivateKey = makerTradeAccount.getPrivateKey();
		byte[] tradeLocalPublicKey = TradeBot.deriveTradeLocalPublicKey(tradePrivateKey);
		byte[] tradeLocalPublicKeyHash = Crypto.hash160(tradeLocalPublicKey);
		byte[] tradeForeignPublicKey = TradeBot.deriveTradeForeignPublicKey(tradePrivateKey);
		byte[] tradeForeignPublicKeyHash = Crypto.hash160(tradeForeignPublicKey);

		TradeBotData tradeBotData = new TradeBotData(tradePrivateKey, BitcoinyACCTv5.NAME, state.name(), state.value,
				deployer.getAddress(), atAddress, System.currentTimeMillis(), localAssetId, LOCAL_AMOUNT,
				tradeLocalPublicKey, tradeLocalPublicKeyHash, makerTradeAccount.getAddress(),
				SECRET_A, HASH_OF_SECRET_A, "BITCOIN", tradeForeignPublicKey, tradeForeignPublicKeyHash,
				FOREIGN_AMOUNT, XPRV, null, lockTimeA, Base58.decode(makerReceiving.getAddress()));

		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();
		return tradeBotData;
	}

	private TradeBotData saveTakerTradeBotData(Repository repository, ReverseTradeSetup setup) throws DataException {
		TradeBotData tradeBotData = new TradeBotData(setup.takerTradePrivateKey, BitcoinyACCTv5.NAME,
				TradeStates.State.TAKER_WAITING_FOR_FOREIGN_LOCK.name(), TradeStates.State.TAKER_WAITING_FOR_FOREIGN_LOCK.value,
				setup.taker.getAddress(), setup.atAddress, System.currentTimeMillis(), setup.localAssetId, LOCAL_AMOUNT,
				setup.taker.getPublicKey(), Crypto.hash160(setup.taker.getPublicKey()), setup.taker.getAddress(),
				null, HASH_OF_SECRET_A, "BITCOIN", setup.takerForeignPublicKey, setup.takerForeignPublicKeyHash,
				FOREIGN_AMOUNT, null, null, null, BitcoinyAddress.fromString(getBitcoinNetworkParameters(), btcReceivingAddress()).getPayload());

		repository.getCrossChainRepository().save(tradeBotData);
		repository.saveChanges();
		return tradeBotData;
	}

	private MockBitcoiny installMockBitcoiny(BitcoinyHTLC.Status initialStatus) {
		MockBitcoiny bitcoiny = new MockBitcoiny(getBitcoinNetworkParameters());
		BitcoinyACCTv5TradeBot.getInstance().setBitcoinyResolverForTesting(blockchain -> bitcoiny);
		setMockHtlcStatus(initialStatus);
		return bitcoiny;
	}

	private void setMockHtlcStatus(BitcoinyHTLC.Status status) {
		BitcoinyACCTv5TradeBot.getInstance().setHtlcStatusResolverForTesting((bitcoiny, p2shAddress, minimumAmount) -> status);
	}

	private static NetworkParameters getBitcoinNetworkParameters() {
		return ForeignBlockchainRegistry.fromString("BITCOIN").getBitcoinyInstance().getNetworkParameters();
	}

	private static String btcReceivingAddress() {
		return BitcoinyAddress.fromPubKeyHash(getBitcoinNetworkParameters(), BTC_RECEIVING_PUBLIC_KEY_HASH).toString();
	}

	private static TransactionData fromUnsignedBytes(byte[] unsignedBytes) throws TransformationException {
		return TransactionTransformer.fromBytes(Bytes.concat(unsignedBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]));
	}

	private static void reserveTrade(Repository repository, PrivateKeyAccount taker, String atAddress, byte[] takerForeignPublicKeyHash)
			throws DataException {
		sendMessage(repository, taker, BitcoinyACCTv5.buildReserveMessage(takerForeignPublicKeyHash), atAddress);
		BlockUtils.mintBlock(repository);
	}

	private static void declareForeignLock(Repository repository, PrivateKeyAccount makerTradeAccount, String atAddress, int lockTimeA)
			throws DataException {
		sendMessage(repository, makerTradeAccount, BitcoinyACCTv5.buildForeignLockMessage(lockTimeA), atAddress);
		BlockUtils.mintBlock(repository);
	}

	private static MessageTransaction sendMessage(Repository repository, PrivateKeyAccount sender, byte[] data, String recipient)
			throws DataException {
		return sendPaymentMessage(repository, sender, data, recipient, 0L, null);
	}

	private static MessageTransaction sendPaymentMessage(Repository repository, PrivateKeyAccount sender, byte[] data, String recipient,
			long amount, Long assetId) throws DataException {
		long txTimestamp = System.currentTimeMillis();
		Long fee = null;
		int version = Transaction.getVersionByTimestamp(txTimestamp);

		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, sender.getPublicKey(), fee, null);
		TransactionData messageTransactionData = new MessageTransactionData(baseTransactionData, version, 0, recipient,
				amount, assetId, data, false, false);
		MessageTransaction messageTransaction = new MessageTransaction(repository, messageTransactionData);

		messageTransactionData.setFee(messageTransaction.calcRecommendedFee());
		TransactionUtils.signAndMint(repository, messageTransactionData, sender);

		return messageTransaction;
	}

	private static class ReverseTradeSetup {
		PrivateKeyAccount makerTradeAccount;
		PrivateKeyAccount makerReceiving;
		PrivateKeyAccount taker;
		byte[] takerTradePrivateKey;
		byte[] takerForeignPublicKey;
		byte[] takerForeignPublicKeyHash;
		long localAssetId;
		String atAddress;
		TradeBotData makerTradeBotData;
	}

	private static class MockBitcoiny extends Bitcoiny {
		private static final byte[] DUMMY_RAW_TRANSACTION = new byte[] {1, 2, 3, 4};

		private long feeRequired = 1_000L;
		private int transactionCounter;
		private int spendTransactionCount;
		private int redeemTransactionCount;
		private int refundTransactionCount;
		private int medianBlockTime = Integer.MAX_VALUE;
		private final List<BitcoinySignedTransaction> broadcastTransactions = new ArrayList<>();

		private MockBitcoiny(NetworkParameters params) {
			this(params, new MockProvider());
		}

		private MockBitcoiny(NetworkParameters params, MockProvider provider) {
			super(provider, new Context(params), params, "BTC", Coin.valueOf(1_000L));
			provider.setBlockchain(this);
		}

		@Override
		public long getP2shFee(Long timestamp) {
			return this.feeRequired;
		}

		@Override
		public long getFeeRequired() {
			return this.feeRequired;
		}

		@Override
		public boolean isValidWalletKey(String walletKey) {
			return true;
		}

		@Override
		public void setFeeRequired(long fee) {
			this.feeRequired = fee;
		}

		@Override
		public int getMedianBlockTime() {
			return this.medianBlockTime;
		}

		@Override
		public BitcoinySignedTransaction buildSpendTransaction(String xprv58, String recipient, long amount, Long feePerByte) {
			++this.spendTransactionCount;
			return fakeTransaction("fund");
		}

		@Override
		public BitcoinySignedTransaction buildHtlcRedeemTransaction(Coin redeemAmount, ECKey redeemKey,
				List<UnspentOutput> fundingOutputs, byte[] redeemScriptBytes, byte[] secret, byte[] receivingAccountInfo) {
			++this.redeemTransactionCount;
			return fakeTransaction("redeem");
		}

		@Override
		public BitcoinySignedTransaction buildHtlcRefundTransaction(Coin refundAmount, ECKey refundKey,
				List<UnspentOutput> fundingOutputs, byte[] redeemScriptBytes, long lockTime, byte[] receivingAccountInfo) {
			++this.refundTransactionCount;
			return fakeTransaction("refund");
		}

		@Override
		public List<UnspentOutput> getUnspentOutputs(String base58Address, boolean includeUnconfirmed) {
			return Collections.singletonList(new UnspentOutput(new byte[32], 0, 1, FOREIGN_AMOUNT + this.feeRequired));
		}

		@Override
		public String getUnusedReceiveAddress(String key58) {
			return btcReceivingAddress();
		}

		@Override
		public void broadcastTransaction(BitcoinySignedTransaction transaction) {
			this.broadcastTransactions.add(transaction);
		}

		private BitcoinySignedTransaction fakeTransaction(String type) {
			return BitcoinySignedTransaction.fromRawWithTxHash(DUMMY_RAW_TRANSACTION, type + "-" + ++this.transactionCounter);
		}
	}

	private static class MockProvider extends BitcoinyBlockchainProvider {
		@Override
		public void setBlockchain(Bitcoiny blockchain) {
		}

		@Override
		public String getNetId() {
			return "v5-tradebot-mock";
		}

		@Override
		public int getCurrentHeight() {
			return 100;
		}

		@Override
		public List<CompactBlock> getCompactBlocks(int startHeight, int count) {
			return Collections.emptyList();
		}

		@Override
		public List<byte[]> getRawBlockHeaders(int startHeight, int count) {
			return Collections.emptyList();
		}

		@Override
		public List<Long> getBlockTimestamps(int startHeight, int count) {
			return Collections.emptyList();
		}

		@Override
		public long getConfirmedBalance(byte[] scriptPubKey) {
			return 0;
		}

		@Override
		public long getConfirmedAddressBalance(String base58Address) {
			return 0;
		}

		@Override
		public byte[] getRawTransaction(String txHash) throws ForeignBlockchainException {
			throw new ForeignBlockchainException.NotFoundException("mock raw transaction not found");
		}

		@Override
		public byte[] getRawTransaction(byte[] txHash) throws ForeignBlockchainException {
			throw new ForeignBlockchainException.NotFoundException("mock raw transaction not found");
		}

		@Override
		public BitcoinyTransaction getTransaction(String txHash) throws ForeignBlockchainException {
			throw new ForeignBlockchainException.NotFoundException("mock transaction not found");
		}

		@Override
		public List<TransactionHash> getAddressTransactions(byte[] scriptPubKey, boolean includeUnconfirmed) {
			return Collections.emptyList();
		}

		@Override
		public List<BitcoinyTransaction> getAddressBitcoinyTransactions(String address, boolean includeUnconfirmed) {
			return Collections.emptyList();
		}

		@Override
		public List<UnspentOutput> getUnspentOutputs(String address, boolean includeUnconfirmed) {
			return Collections.emptyList();
		}

		@Override
		public List<UnspentOutput> getUnspentOutputs(byte[] scriptPubKey, boolean includeUnconfirmed) {
			return Collections.emptyList();
		}

		@Override
		public void broadcastTransaction(byte[] rawTransaction) {
		}

		@Override
		public Set<ChainableServer> getServers() {
			return Collections.emptySet();
		}

		@Override
		public Set<ChainableServer> getUselessServers() {
			return Collections.emptySet();
		}

		@Override
		public ChainableServer getCurrentServer() {
			return null;
		}

		@Override
		public boolean addServer(ChainableServer server) {
			return false;
		}

		@Override
		public boolean removeServer(ChainableServer server) {
			return false;
		}

		@Override
		public Optional<ChainableServerConnection> setCurrentServer(ChainableServer server, String requestedBy) {
			return Optional.empty();
		}

		@Override
		public List<ChainableServerConnection> getServerConnections() {
			return Collections.emptyList();
		}

		@Override
		public ChainableServer getServer(String hostName, ChainableServer.ConnectionType type, int port) {
			return null;
		}
	}
}
