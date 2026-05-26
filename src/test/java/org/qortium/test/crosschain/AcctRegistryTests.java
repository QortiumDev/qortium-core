package org.qortium.test.crosschain;

import org.junit.Test;
import org.qortium.controller.tradebot.AcctTradeBot;
import org.qortium.controller.tradebot.BitcoinyACCTv3TradeBot;
import org.qortium.controller.tradebot.BitcoinyACCTv4TradeBot;
import org.qortium.controller.tradebot.BitcoinyACCTv5TradeBot;
import org.qortium.controller.tradebot.BitcoinyForeignForeignTradeBot;
import org.qortium.controller.tradebot.PirateChainACCTv3TradeBot;
import org.qortium.crosschain.ACCT;
import org.qortium.crosschain.AcctRegistry;
import org.qortium.crosschain.BitcoinyACCTv3;
import org.qortium.crosschain.BitcoinyACCTv4;
import org.qortium.crosschain.BitcoinyACCTv5;
import org.qortium.crosschain.BitcoinyACCTv6;
import org.qortium.crosschain.BitcoinyForeignForeignACCTv1;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.PirateChainACCTv3;
import org.qortium.utils.ByteArray;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AcctRegistryTests {

	@Test
	public void testRegisteredAcctsResolveByCodeHash() {
		assertSame(BitcoinyACCTv3.getInstance(), AcctRegistry.getAcctByCodeHash(BitcoinyACCTv3.CODE_BYTES_HASH));
		assertSame(BitcoinyACCTv4.getInstance(), AcctRegistry.getAcctByCodeHash(BitcoinyACCTv4.CODE_BYTES_HASH));
		assertSame(BitcoinyACCTv5.getInstance(), AcctRegistry.getAcctByCodeHash(BitcoinyACCTv5.CODE_BYTES_HASH));
		assertSame(BitcoinyForeignForeignACCTv1.getInstance(), AcctRegistry.getAcctByCodeHash(BitcoinyForeignForeignACCTv1.CODE_BYTES_HASH));
		assertSame(PirateChainACCTv3.getInstance(), AcctRegistry.getAcctByCodeHash(PirateChainACCTv3.CODE_BYTES_HASH));
	}

	@Test
	public void testRegisteredAcctsResolveByName() {
		assertSame(BitcoinyACCTv3.getInstance(), AcctRegistry.getAcctByName(BitcoinyACCTv3.NAME));
		assertSame(BitcoinyACCTv4.getInstance(), AcctRegistry.getAcctByName(BitcoinyACCTv4.NAME));
		assertSame(BitcoinyACCTv5.getInstance(), AcctRegistry.getAcctByName(BitcoinyACCTv5.NAME));
		assertSame(BitcoinyForeignForeignACCTv1.getInstance(), AcctRegistry.getAcctByName(BitcoinyForeignForeignACCTv1.NAME));
		assertSame(PirateChainACCTv3.getInstance(), AcctRegistry.getAcctByName(PirateChainACCTv3.NAME));
	}

	@Test
	public void testInactiveV6AcctRemainsUnregistered() {
		assertNull(AcctRegistry.getAcctByCodeHash(BitcoinyACCTv6.CODE_BYTES_HASH));
		assertNull(AcctRegistry.getAcctByName(BitcoinyACCTv6.NAME));
		assertNull(AcctRegistry.getTradeBotForAcct(BitcoinyACCTv6.getInstance()));
	}

	@Test
	public void testUnfilteredMapIncludesAllRegisteredAccts() {
		Map<ByteArray, Supplier<ACCT>> filteredAccts = AcctRegistry.getFilteredAcctMap((ForeignBlockchainRegistry.Entry) null);

		assertResolves(filteredAccts, BitcoinyACCTv3.CODE_BYTES_HASH, BitcoinyACCTv3.getInstance());
		assertResolves(filteredAccts, BitcoinyACCTv4.CODE_BYTES_HASH, BitcoinyACCTv4.getInstance());
		assertResolves(filteredAccts, BitcoinyACCTv5.CODE_BYTES_HASH, BitcoinyACCTv5.getInstance());
		assertResolves(filteredAccts, BitcoinyForeignForeignACCTv1.CODE_BYTES_HASH, BitcoinyForeignForeignACCTv1.getInstance());
		assertResolves(filteredAccts, PirateChainACCTv3.CODE_BYTES_HASH, PirateChainACCTv3.getInstance());
	}

	@Test
	public void testUnknownBlockchainFilterReturnsEmptyMap() {
		assertTrue(AcctRegistry.getFilteredAcctMap("UNKNOWN").isEmpty());
	}

	@Test
	public void testBitcoinyFilterIncludesOnlyBitcoinyAccts() {
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BTC");
		assertNotNull(bitcoin);

		Map<ByteArray, Supplier<ACCT>> filteredAccts = AcctRegistry.getFilteredAcctMap(bitcoin);

		assertResolves(filteredAccts, BitcoinyACCTv3.CODE_BYTES_HASH, BitcoinyACCTv3.getInstance());
		assertResolves(filteredAccts, BitcoinyACCTv4.CODE_BYTES_HASH, BitcoinyACCTv4.getInstance());
		assertResolves(filteredAccts, BitcoinyACCTv5.CODE_BYTES_HASH, BitcoinyACCTv5.getInstance());
		assertResolves(filteredAccts, BitcoinyForeignForeignACCTv1.CODE_BYTES_HASH, BitcoinyForeignForeignACCTv1.getInstance());
		assertFalse(filteredAccts.containsKey(ByteArray.wrap(PirateChainACCTv3.CODE_BYTES_HASH)));
	}

	@Test
	public void testPirateChainFilterIncludesOnlyPirateChainAcct() {
		ForeignBlockchainRegistry.Entry pirateChain = ForeignBlockchainRegistry.fromString(ForeignBlockchainRegistry.PIRATECHAIN_NAME);
		assertNotNull(pirateChain);

		Map<ByteArray, Supplier<ACCT>> filteredAccts = AcctRegistry.getFilteredAcctMap(pirateChain);

		assertResolves(filteredAccts, PirateChainACCTv3.CODE_BYTES_HASH, PirateChainACCTv3.getInstance());
		assertFalse(filteredAccts.containsKey(ByteArray.wrap(BitcoinyACCTv4.CODE_BYTES_HASH)));
	}

	@Test
	public void testTradeBotsResolveByAcctClass() {
		assertTradeBot(BitcoinyACCTv3.getInstance(), BitcoinyACCTv3TradeBot.getInstance());
		assertTradeBot(BitcoinyACCTv4.getInstance(), BitcoinyACCTv4TradeBot.getInstance());
		assertTradeBot(BitcoinyACCTv5.getInstance(), BitcoinyACCTv5TradeBot.getInstance());
		assertTradeBot(BitcoinyForeignForeignACCTv1.getInstance(), BitcoinyForeignForeignTradeBot.getInstance());
		assertTradeBot(PirateChainACCTv3.getInstance(), PirateChainACCTv3TradeBot.getInstance());
	}

	private static void assertResolves(Map<ByteArray, Supplier<ACCT>> acctsByCodeHash, byte[] codeHash, ACCT expectedAcct) {
		Supplier<ACCT> acctSupplier = acctsByCodeHash.get(ByteArray.wrap(codeHash));
		assertNotNull(acctSupplier);
		assertSame(expectedAcct, acctSupplier.get());
	}

	private static void assertTradeBot(ACCT acct, AcctTradeBot expectedTradeBot) {
		assertSame(expectedTradeBot, AcctRegistry.getTradeBotForAcct(acct));
	}

}
