package org.qortal.controller.tradebot;

import com.google.common.hash.HashCode;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.model.crosschain.TradeBotCreateRequest;
import org.qortal.asset.Asset;
import org.qortal.crosschain.BitcoinyACCTv5;
import org.qortal.crosschain.BitcoinyHTLC;
import org.qortal.crosschain.ForeignBlockchainRegistry;
import org.qortal.crosschain.TradeDirection;
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
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AssetUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.transaction.DeployAtTransaction;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Amounts;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class BitcoinyACCTv5TradeBotTests extends Common {

	private static final String XPRV = "xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg";
	private static final String BTC_RECEIVING_ADDRESS = "1BitcoinEaterAddressDontSendf59kuE";
	private static final byte[] MAKER_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("aa00aa11aa22aa33aa44aa55aa66aa77aa88aa99").asBytes();
	private static final byte[] SECRET_A = "This string is exactly 32 bytes!".getBytes();
	private static final byte[] HASH_OF_SECRET_A = Crypto.hash160(SECRET_A);
	private static final long LOCAL_AMOUNT = 25L * Amounts.MULTIPLIER;
	private static final long FOREIGN_AMOUNT = 100_000L;
	private static final long NATIVE_FEE_RESERVE = 3L * Amounts.MULTIPLIER;
	private static final int TRADE_TIMEOUT = 120;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testCreateTradeRoutesSellForeignToV5() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
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
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount responder = Common.getTestAccount(repository, "dilbert");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "V5-BOT-RESPOND", 100L * Amounts.MULTIPLIER, true);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

			byte[] unsignedMessageBytes = BitcoinyACCTv5TradeBot.getInstance().startResponse(repository, atData, tradeData,
					responder.getPublicKey(), BTC_RECEIVING_ADDRESS);
			TransactionData transactionData = TransactionTransformer.fromBytes(unsignedMessageBytes);

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
}
