package org.qortium.controller.tradebot;

import com.google.common.hash.HashCode;
import org.junit.Before;
import org.junit.Test;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.data.crosschain.TradeBotFillData;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.Common;

import java.util.Arrays;

import static org.junit.Assert.*;

public class BitcoinyACCTv4TradeBotTests extends Common {

	private static final String AT_ADDRESS = "AT_ADDRESS";
	private static final String PARTNER_ADDRESS = "PARTNER_ADDRESS";
	private static final byte[] PARTNER_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("bb00bb11bb22bb33bb44bb55bb66bb77bb88bb99").asBytes();
	private static final byte[] PENDING_HASH_OF_SECRET = HashCode.fromString("daf59884b4d1aec8c1b17102530909ee43c0151a").asBytes();
	private static final byte[] ACTIVE_HASH_OF_SECRET = HashCode.fromString("aa00aa11aa22aa33aa44aa55aa66aa77aa88aa99").asBytes();
	private static final int TRADE_TIMEOUT = 10;
	private static final long FILL_TIMESTAMP = 1_000L;
	private static final long FILL_LOCAL_AMOUNT = 10_00000000L;
	private static final long FILL_FOREIGN_AMOUNT = 123456L;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testExpiredPendingFillIsMarkedRefunded() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			CrossChainTradeData tradeData = new CrossChainTradeData();
			tradeData.tradeTimeout = TRADE_TIMEOUT;

			TradeBotFillData fillData = newFill(PENDING_HASH_OF_SECRET, BitcoinyACCTv4TradeBot.FILL_ACTIVE, FILL_TIMESTAMP);
			repository.getCrossChainRepository().save(fillData);
			repository.saveChanges();

			long expiryTimestamp = FILL_TIMESTAMP + TRADE_TIMEOUT * 60_000L;
			assertTrue(BitcoinyACCTv4TradeBot.isPendingFill(tradeData, fillData));
			assertFalse(BitcoinyACCTv4TradeBot.refundExpiredPendingFill(repository, tradeData, fillData, expiryTimestamp));
			assertEquals(BitcoinyACCTv4TradeBot.FILL_ACTIVE,
					repository.getCrossChainRepository().getTradeBotFillData(AT_ADDRESS, PENDING_HASH_OF_SECRET).getState());

			long expiredTimestamp = expiryTimestamp + 1L;
			assertTrue(BitcoinyACCTv4TradeBot.refundExpiredPendingFill(repository, tradeData, fillData, expiredTimestamp));
			repository.saveChanges();

			TradeBotFillData repositoryFillData = repository.getCrossChainRepository().getTradeBotFillData(AT_ADDRESS, PENDING_HASH_OF_SECRET);
			assertNotNull(repositoryFillData);
			assertEquals(BitcoinyACCTv4TradeBot.FILL_REFUNDED, repositoryFillData.getState());
			assertEquals(expiredTimestamp, repositoryFillData.getTimestamp());
			assertFalse(BitcoinyACCTv4TradeBot.isPendingFill(tradeData, repositoryFillData));
		}
	}

	@Test
	public void testOnChainFillRecordIsNotPendingOrExpired() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			CrossChainTradeData tradeData = new CrossChainTradeData();
			tradeData.tradeTimeout = TRADE_TIMEOUT;

			CrossChainTradeData.Fill activeFill = new CrossChainTradeData.Fill();
			activeFill.hashOfSecretA = ACTIVE_HASH_OF_SECRET;
			tradeData.fills.add(activeFill);

			TradeBotFillData fillData = newFill(ACTIVE_HASH_OF_SECRET, BitcoinyACCTv4TradeBot.FILL_ACTIVE, FILL_TIMESTAMP);
			long expiredTimestamp = FILL_TIMESTAMP + TRADE_TIMEOUT * 60_000L + 1L;

			assertFalse(BitcoinyACCTv4TradeBot.isPendingFill(tradeData, fillData));
			assertFalse(BitcoinyACCTv4TradeBot.isExpiredPendingFill(tradeData, fillData, expiredTimestamp));
			assertFalse(BitcoinyACCTv4TradeBot.refundExpiredPendingFill(repository, tradeData, fillData, expiredTimestamp));
		}
	}

	@Test
	public void testPendingLocalAmountIgnoresOnChainAndInactiveFills() {
		CrossChainTradeData tradeData = new CrossChainTradeData();
		CrossChainTradeData.Fill activeFill = new CrossChainTradeData.Fill();
		activeFill.hashOfSecretA = ACTIVE_HASH_OF_SECRET;
		tradeData.fills.add(activeFill);

		TradeBotFillData pendingFill = newFill(PENDING_HASH_OF_SECRET, BitcoinyACCTv4TradeBot.FILL_ACTIVE, FILL_TIMESTAMP);
		TradeBotFillData onChainFill = newFill(ACTIVE_HASH_OF_SECRET, BitcoinyACCTv4TradeBot.FILL_ACTIVE, FILL_TIMESTAMP);
		TradeBotFillData refundedFill = newFill(HashCode.fromString("cc00cc11cc22cc33cc44cc55cc66cc77cc88cc99").asBytes(),
				BitcoinyACCTv4TradeBot.FILL_REFUNDED, FILL_TIMESTAMP);

		assertEquals(FILL_LOCAL_AMOUNT, BitcoinyACCTv4TradeBot.pendingLocalAmount(tradeData,
				Arrays.asList(pendingFill, onChainFill, refundedFill)));
	}

	private static TradeBotFillData newFill(byte[] hashOfSecret, String state, long timestamp) {
		return new TradeBotFillData(AT_ADDRESS, 2, state, timestamp, PARTNER_ADDRESS,
				PARTNER_FOREIGN_PUBLIC_KEY_HASH, hashOfSecret, 1_777_777_777, FILL_LOCAL_AMOUNT,
				FILL_FOREIGN_AMOUNT, "p2sh-address");
	}

}
