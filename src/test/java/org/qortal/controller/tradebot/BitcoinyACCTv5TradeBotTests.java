package org.qortal.controller.tradebot;

import com.google.common.hash.HashCode;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.model.crosschain.TradeBotCreateRequest;
import org.qortal.asset.Asset;
import org.qortal.crosschain.BitcoinyACCTv4;
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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class BitcoinyACCTv5TradeBotTests extends Common {

	private static final String XPRV = "xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg";
	private static final String OTHER_XPRV = "xprv9yYd7nZUWZgrnKBz6bJQmG7upUD5gn8J9HyUFztvyAoG1jJMzsp3eSE4z39dnuy3A8sacVtZfVxXVmYzKxq4gypkXVTLVWQDykU5uQx4AYr";
	private static final String BTC_RECEIVING_ADDRESS = "1BitcoinEaterAddressDontSendf59kuE";
	private static final byte[] MAKER_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("aa00aa11aa22aa33aa44aa55aa66aa77aa88aa99").asBytes();
	private static final long LOCAL_AMOUNT = 25L * Amounts.MULTIPLIER;
	private static final long FOREIGN_AMOUNT = 100_000L;
	private static final long NATIVE_FEE_RESERVE = 3L * Amounts.MULTIPLIER;
	private static final int TRADE_TIMEOUT = 120;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testReverseMakerReservationCountsOnlyOpenMakerOffers() {
		long p2shFee = 1_000L;
		List<TradeBotData> tradeBotDataList = Arrays.asList(
				tradeBotData(BitcoinyACCTv5.NAME, TradeStates.State.MAKER_WAITING_FOR_AT_CONFIRM, "BITCOIN", XPRV, 100_000L),
				tradeBotData(BitcoinyACCTv5.NAME, TradeStates.State.MAKER_WAITING_FOR_TAKER_MESSAGE, "BITCOIN", XPRV, 200_000L),
				tradeBotData(BitcoinyACCTv5.NAME, TradeStates.State.MAKER_WAITING_FOR_AT_REDEEM, "BITCOIN", XPRV, 300_000L),
				tradeBotData(BitcoinyACCTv5.NAME, TradeStates.State.MAKER_DONE, "BITCOIN", XPRV, 400_000L),
				tradeBotData(BitcoinyACCTv5.NAME, TradeStates.State.TAKER_WAITING_FOR_AT_LOCK, "BITCOIN", XPRV, 500_000L),
				tradeBotData(BitcoinyACCTv5.NAME, TradeStates.State.MAKER_WAITING_FOR_TAKER_MESSAGE, "BITCOIN", OTHER_XPRV, 600_000L),
				tradeBotData(BitcoinyACCTv5.NAME, TradeStates.State.MAKER_WAITING_FOR_TAKER_MESSAGE, "LITECOIN", XPRV, 700_000L),
				tradeBotData(BitcoinyACCTv4.NAME, TradeStates.State.MAKER_WAITING_FOR_TAKER_MESSAGE, "BITCOIN", XPRV, 800_000L));

		long reservedAmount = BitcoinyACCTv5TradeBot.calculateReservedForeignAmount(tradeBotDataList, "BITCOIN", XPRV, p2shFee);

		assertEquals(302_000L, reservedAmount);
	}

	@Test
	public void testReverseMakerReservationBalanceCheck() {
		long p2shFee = 1_000L;
		long reservedAmount = BitcoinyACCTv5TradeBot.calculateReservedForeignAmount(Arrays.asList(
				tradeBotData(BitcoinyACCTv5.NAME, TradeStates.State.MAKER_WAITING_FOR_TAKER_MESSAGE, "BITCOIN", XPRV, 100_000L),
				tradeBotData(BitcoinyACCTv5.NAME, TradeStates.State.MAKER_WAITING_FOR_AT_CONFIRM, "BITCOIN", XPRV, 200_000L)),
				"BITCOIN", XPRV, p2shFee);
		long newOfferRequiredAmount = 300_000L + p2shFee;

		assertTrue(BitcoinyACCTv5TradeBot.hasSufficientForeignBalance(604_000L, reservedAmount, newOfferRequiredAmount));
		assertFalse(BitcoinyACCTv5TradeBot.hasSufficientForeignBalance(603_999L, reservedAmount, newOfferRequiredAmount));
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
			assertEquals(BitcoinyACCTv5.NAME, allTradeBotData.get(0).getAcctName());
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_AT_CONFIRM.name(), allTradeBotData.get(0).getState());
			assertEquals(localAssetId, allTradeBotData.get(0).getLocalAssetId());
			assertEquals(LOCAL_AMOUNT, allTradeBotData.get(0).getLocalAmount());
			assertEquals(FOREIGN_AMOUNT, allTradeBotData.get(0).getForeignAmount());
		}
	}

	@Test
	public void testReverseResponseReturnsUnsignedLocalEscrowMessage() throws DataException, TransformationException {
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
			assertEquals(LOCAL_AMOUNT, messageData.getAmount());
			assertEquals(Long.valueOf(localAssetId), messageData.getAssetId());
			assertEquals(BitcoinyACCTv5.LOCK_MESSAGE_LENGTH, messageData.getData().length);

			byte[] lockMessageData = messageData.getData();
			byte[] takerForeignPublicKeyHash = Arrays.copyOfRange(lockMessageData, 0, 20);
			byte[] hashOfSecretA = Arrays.copyOfRange(lockMessageData, 32, 52);
			int lockTimeA = (int) ByteBuffer.wrap(lockMessageData, 64, 8).getLong();
			tradeData.partnerForeignPKH = takerForeignPublicKeyHash;
			tradeData.hashOfSecretA = hashOfSecretA;
			tradeData.lockTimeA = lockTimeA;
			assertArrayEquals(BitcoinyHTLC.buildScript(MAKER_FOREIGN_PUBLIC_KEY_HASH, lockTimeA, takerForeignPublicKeyHash, hashOfSecretA),
					BitcoinyACCTv5TradeBot.buildRedeemScript(tradeData));

			List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();
			assertEquals(1, allTradeBotData.size());
			assertEquals(BitcoinyACCTv5.NAME, allTradeBotData.get(0).getAcctName());
			assertEquals(TradeStates.State.TAKER_WAITING_FOR_AT_LOCK.name(), allTradeBotData.get(0).getState());
			assertEquals(atAddress, allTradeBotData.get(0).getAtAddress());
			assertEquals(Crypto.toAddress(responder.getPublicKey()), allTradeBotData.get(0).getTradeLocalAddress());
		}
	}

	private DeployAtTransaction deploy(Repository repository, PrivateKeyAccount deployer, String tradeAddress, long localAssetId) throws DataException {
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");
		byte[] creationBytes = BitcoinyACCTv5.buildTradeAT(bitcoin, tradeAddress, MAKER_FOREIGN_PUBLIC_KEY_HASH,
				LOCAL_AMOUNT, FOREIGN_AMOUNT, TRADE_TIMEOUT);

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

	private TradeBotData tradeBotData(String acctName, TradeStates.State state, String foreignBlockchain, String foreignKey, long foreignAmount) {
		return new TradeBotData(new byte[32], acctName,
				state.name(), state.value,
				"Qcreator", "ATaddress", System.currentTimeMillis(), Asset.NATIVE, 0L,
				null, null, "Qtrade",
				null, null,
				foreignBlockchain, null, null,
				foreignAmount, foreignKey, null, null, null);
	}
}
