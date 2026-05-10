package org.qortal.test.crosschain.bitcoinyforeign;

import com.google.common.hash.HashCode;
import org.junit.Before;
import org.junit.Test;
import org.qortal.api.model.CrossChainOfferSummary;
import org.qortal.api.model.CrossChainTradeSummary;
import org.qortal.api.model.crosschain.TradeBotCreateRequest;
import org.qortal.controller.tradebot.AcctTradeBot;
import org.qortal.controller.tradebot.BitcoinyForeignForeignTradeBot;
import org.qortal.controller.tradebot.TradeBot;
import org.qortal.crosschain.BitcoinyForeignForeignACCTv1;
import org.qortal.crosschain.ForeignBlockchainRegistry;
import org.qortal.crosschain.TradeDirection;
import org.qortal.crypto.Crypto;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.utils.BitTwiddling;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

public class BitcoinyForeignForeignACCTv1Tests extends Common {

	private static final byte[] MAKER_OFFERED_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("aa00aa11aa22aa33aa44aa55aa66aa77aa88aa99").asBytes();
	private static final byte[] MAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("bb00bb11bb22bb33bb44bb55bb66bb77bb88bb99").asBytes();
	private static final byte[] TAKER_OFFERED_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("cc00cc11cc22cc33cc44cc55cc66cc77cc88cc99").asBytes();
	private static final byte[] TAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("dd00dd11dd22dd33dd44dd55dd66dd77dd88dd99").asBytes();
	private static final byte[] SECRET_A = "This string is exactly 32 bytes!".getBytes(StandardCharsets.UTF_8);
	private static final byte[] HASH_OF_SECRET_A = Crypto.hash160(SECRET_A);
	private static final long OFFERED_FOREIGN_AMOUNT = 100_000L;
	private static final long REQUESTED_FOREIGN_AMOUNT = 250_000L;
	private static final int MAKER_LOCK_TIME = 1_765_010_000;
	private static final int TAKER_LOCK_TIME = 1_765_000_000;
	private static final int TRADE_TIMEOUT = 120;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testMessageBuildersUseFixedLengthsAndPayloadOffsets() {
		byte[] reserveMessage = BitcoinyForeignForeignACCTv1.buildReserveMessage(TAKER_OFFERED_FOREIGN_PUBLIC_KEY_HASH,
				TAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH);
		assertEquals(BitcoinyForeignForeignACCTv1.RESERVE_MESSAGE_LENGTH, reserveMessage.length);
		assertArrayEquals(TAKER_OFFERED_FOREIGN_PUBLIC_KEY_HASH, Arrays.copyOfRange(reserveMessage, 0, 20));
		assertArrayEquals(new byte[12], Arrays.copyOfRange(reserveMessage, 20, 32));
		assertArrayEquals(TAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH, Arrays.copyOfRange(reserveMessage, 32, 52));
		assertArrayEquals(new byte[12], Arrays.copyOfRange(reserveMessage, 52, 64));

		byte[] makerHtlcMessage = BitcoinyForeignForeignACCTv1.buildMakerHtlcMessage(MAKER_LOCK_TIME);
		assertEquals(BitcoinyForeignForeignACCTv1.MAKER_HTLC_MESSAGE_LENGTH, makerHtlcMessage.length);
		assertEquals(MAKER_LOCK_TIME, BitTwiddling.longFromBEBytes(makerHtlcMessage, 0));

		byte[] takerHtlcMessage = BitcoinyForeignForeignACCTv1.buildTakerHtlcMessage(TAKER_LOCK_TIME);
		assertEquals(BitcoinyForeignForeignACCTv1.TAKER_HTLC_MESSAGE_LENGTH, takerHtlcMessage.length);
		assertEquals(TAKER_LOCK_TIME, BitTwiddling.longFromBEBytes(takerHtlcMessage, 0));

		byte[] secretRevealMessage = BitcoinyForeignForeignACCTv1.buildSecretRevealMessage(SECRET_A);
		assertEquals(BitcoinyForeignForeignACCTv1.SECRET_REVEAL_MESSAGE_LENGTH, secretRevealMessage.length);
		assertArrayEquals(SECRET_A, secretRevealMessage);
	}

	@Test
	public void testBuildsDualForeignTradeDataAndSummaries() {
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");
		ForeignBlockchainRegistry.Entry litecoin = ForeignBlockchainRegistry.fromString("LITECOIN");

		CrossChainTradeData tradeData = BitcoinyForeignForeignACCTv1.buildSkeletonTradeData(bitcoin, litecoin,
				MAKER_OFFERED_FOREIGN_PUBLIC_KEY_HASH, MAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH, HASH_OF_SECRET_A,
				OFFERED_FOREIGN_AMOUNT, REQUESTED_FOREIGN_AMOUNT, TRADE_TIMEOUT);

		assertEquals(BitcoinyForeignForeignACCTv1.NAME, tradeData.acctName);
		assertEquals(TradeDirection.SELL_FOREIGN_FOR_FOREIGN, tradeData.tradeDirection);
		assertEquals("BITCOIN", tradeData.offeredForeignBlockchain);
		assertEquals(OFFERED_FOREIGN_AMOUNT, tradeData.offeredForeignAmount);
		assertEquals("LITECOIN", tradeData.requestedForeignBlockchain);
		assertEquals(REQUESTED_FOREIGN_AMOUNT, tradeData.requestedForeignAmount);
		assertArrayEquals(MAKER_OFFERED_FOREIGN_PUBLIC_KEY_HASH, tradeData.creatorOfferedForeignPKH);
		assertArrayEquals(MAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH, tradeData.creatorRequestedForeignPKH);
		assertArrayEquals(HASH_OF_SECRET_A, tradeData.hashOfSecretA);
		assertNull(tradeData.foreignBlockchain);
		assertEquals(0L, tradeData.expectedForeignAmount);

		CrossChainOfferSummary offerSummary = new CrossChainOfferSummary(tradeData, 123L);
		assertEquals(TradeDirection.SELL_FOREIGN_FOR_FOREIGN, offerSummary.getTradeDirection());
		assertEquals("BITCOIN", offerSummary.getOfferedForeignBlockchain());
		assertEquals(OFFERED_FOREIGN_AMOUNT, offerSummary.getOfferedForeignAmount());
		assertEquals("LITECOIN", offerSummary.getRequestedForeignBlockchain());
		assertEquals(REQUESTED_FOREIGN_AMOUNT, offerSummary.getRequestedForeignAmount());

		CrossChainTradeSummary tradeSummary = new CrossChainTradeSummary(tradeData, 456L);
		assertEquals(TradeDirection.SELL_FOREIGN_FOR_FOREIGN, tradeSummary.getTradeDirection());
		assertEquals("BITCOIN", tradeSummary.getOfferedForeignBlockchain());
		assertEquals(OFFERED_FOREIGN_AMOUNT, tradeSummary.getOfferedForeignAmount());
		assertEquals("LITECOIN", tradeSummary.getRequestedForeignBlockchain());
		assertEquals(REQUESTED_FOREIGN_AMOUNT, tradeSummary.getRequestedForeignAmount());
	}

	@Test
	public void testRequiresSupportedBitcoinyPair() {
		ForeignBlockchainRegistry.Entry bitcoin = BitcoinyForeignForeignACCTv1.requireBitcoinyEntry("BTC", "offeredForeignBlockchain");
		ForeignBlockchainRegistry.Entry litecoin = BitcoinyForeignForeignACCTv1.requireBitcoinyEntry("LITECOIN", "requestedForeignBlockchain");
		ForeignBlockchainRegistry.Entry pirateChain = ForeignBlockchainRegistry.fromString("PIRATECHAIN");

		assertTrue(BitcoinyForeignForeignACCTv1.isSupportedBitcoinyPair(bitcoin, litecoin));
		assertFalse(BitcoinyForeignForeignACCTv1.isSupportedBitcoinyPair(bitcoin, null));
		assertFalse(BitcoinyForeignForeignACCTv1.isSupportedBitcoinyPair(pirateChain, litecoin));

		try {
			BitcoinyForeignForeignACCTv1.requireBitcoinyEntry("PIRATECHAIN", "offeredForeignBlockchain");
			fail("PirateChain should not be accepted as a Bitcoiny foreign/foreign chain");
		} catch (IllegalArgumentException e) {
			// Expected
		}
	}

	@Test
	public void testSkeletonIsNotRegisteredOrUserRoutable() throws DataException {
		assertNull(ForeignBlockchainRegistry.getAcctByName(BitcoinyForeignForeignACCTv1.NAME));
		assertNull(ForeignBlockchainRegistry.getAcctByCodeHash(BitcoinyForeignForeignACCTv1.CODE_BYTES_HASH));

		BitcoinyForeignForeignTradeBot tradeBot = BitcoinyForeignForeignTradeBot.getInstance();
		assertNull(tradeBot.createTrade(null, new TradeBotCreateRequest()));
		assertEquals(AcctTradeBot.ResponseResult.INVALID_CRITERIA, tradeBot.startResponse(null, null, null, null, null, null));

		try (final Repository repository = RepositoryManager.getRepository()) {
			TradeBotCreateRequest request = new TradeBotCreateRequest();
			request.creatorPublicKey = Common.getTestAccount(repository, "alice").getPublicKey();
			request.tradeDirection = TradeDirection.SELL_FOREIGN_FOR_FOREIGN;
			request.offeredForeignBlockchain = "BITCOIN";
			request.offeredForeignAmount = OFFERED_FOREIGN_AMOUNT;
			request.requestedForeignBlockchain = "LITECOIN";
			request.requestedForeignAmount = REQUESTED_FOREIGN_AMOUNT;
			request.tradeTimeout = TRADE_TIMEOUT;

			assertNull(TradeBot.getInstance().createTrade(repository, request));
			assertTrue(repository.getCrossChainRepository().getAllTradeBotData().isEmpty());
		}
	}

}
