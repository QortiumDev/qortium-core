package org.qortium.crosschain;

import org.qortium.controller.tradebot.AcctTradeBot;
import org.qortium.controller.tradebot.BitcoinyACCTv3TradeBot;
import org.qortium.controller.tradebot.BitcoinyACCTv4TradeBot;
import org.qortium.controller.tradebot.BitcoinyACCTv5TradeBot;
import org.qortium.controller.tradebot.BitcoinyForeignForeignTradeBot;
import org.qortium.controller.tradebot.PirateChainACCTv3TradeBot;
import org.qortium.utils.ByteArray;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

public final class AcctRegistry {

	private static final Supplier<ACCT> BITCOINY_V3_ACCT_SUPPLIER = BitcoinyACCTv3::getInstance;
	private static final Supplier<ACCT> BITCOINY_ACCT_SUPPLIER = BitcoinyACCTv4::getInstance;
	private static final Supplier<ACCT> BITCOINY_V5_ACCT_SUPPLIER = BitcoinyACCTv5::getInstance;
	private static final Supplier<ACCT> BITCOINY_FOREIGN_FOREIGN_ACCT_SUPPLIER = BitcoinyForeignForeignACCTv1::getInstance;
	private static final Supplier<ACCT> PIRATECHAIN_ACCT_SUPPLIER = PirateChainACCTv3::getInstance;
	private static final Supplier<AcctTradeBot> BITCOINY_V3_TRADE_BOT_SUPPLIER = BitcoinyACCTv3TradeBot::getInstance;
	private static final Supplier<AcctTradeBot> BITCOINY_TRADE_BOT_SUPPLIER = BitcoinyACCTv4TradeBot::getInstance;
	private static final Supplier<AcctTradeBot> BITCOINY_V5_TRADE_BOT_SUPPLIER = BitcoinyACCTv5TradeBot::getInstance;
	private static final Supplier<AcctTradeBot> BITCOINY_FOREIGN_FOREIGN_TRADE_BOT_SUPPLIER = BitcoinyForeignForeignTradeBot::getInstance;
	private static final Supplier<AcctTradeBot> PIRATECHAIN_TRADE_BOT_SUPPLIER = PirateChainACCTv3TradeBot::getInstance;

	private static final ByteArray BITCOINY_V3_ACCT_CODE_HASH = ByteArray.wrap(BitcoinyACCTv3.CODE_BYTES_HASH);
	private static final ByteArray BITCOINY_ACCT_CODE_HASH = ByteArray.wrap(BitcoinyACCTv4.CODE_BYTES_HASH);
	private static final ByteArray BITCOINY_V5_ACCT_CODE_HASH = ByteArray.wrap(BitcoinyACCTv5.CODE_BYTES_HASH);
	private static final ByteArray BITCOINY_FOREIGN_FOREIGN_ACCT_CODE_HASH = ByteArray.wrap(BitcoinyForeignForeignACCTv1.CODE_BYTES_HASH);
	private static final ByteArray PIRATECHAIN_ACCT_CODE_HASH = ByteArray.wrap(PirateChainACCTv3.CODE_BYTES_HASH);

	private static final Map<ByteArray, Supplier<ACCT>> SUPPORTED_ACCTS_BY_CODE_HASH = Map.of(
			BITCOINY_FOREIGN_FOREIGN_ACCT_CODE_HASH, BITCOINY_FOREIGN_FOREIGN_ACCT_SUPPLIER,
			BITCOINY_V5_ACCT_CODE_HASH, BITCOINY_V5_ACCT_SUPPLIER,
			BITCOINY_ACCT_CODE_HASH, BITCOINY_ACCT_SUPPLIER,
			BITCOINY_V3_ACCT_CODE_HASH, BITCOINY_V3_ACCT_SUPPLIER,
			PIRATECHAIN_ACCT_CODE_HASH, PIRATECHAIN_ACCT_SUPPLIER);

	private static final Map<String, Supplier<ACCT>> SUPPORTED_ACCTS_BY_NAME = Map.of(
			BitcoinyForeignForeignACCTv1.NAME, BITCOINY_FOREIGN_FOREIGN_ACCT_SUPPLIER,
			BitcoinyACCTv5.NAME, BITCOINY_V5_ACCT_SUPPLIER,
			BitcoinyACCTv4.NAME, BITCOINY_ACCT_SUPPLIER,
			BitcoinyACCTv3.NAME, BITCOINY_V3_ACCT_SUPPLIER,
			PirateChainACCTv3.NAME, PIRATECHAIN_ACCT_SUPPLIER);

	private static final Map<Class<? extends ACCT>, Supplier<AcctTradeBot>> TRADE_BOTS_BY_ACCT_CLASS = Map.of(
			BitcoinyForeignForeignACCTv1.class, BITCOINY_FOREIGN_FOREIGN_TRADE_BOT_SUPPLIER,
			BitcoinyACCTv5.class, BITCOINY_V5_TRADE_BOT_SUPPLIER,
			BitcoinyACCTv4.class, BITCOINY_TRADE_BOT_SUPPLIER,
			BitcoinyACCTv3.class, BITCOINY_V3_TRADE_BOT_SUPPLIER,
			PirateChainACCTv3.class, PIRATECHAIN_TRADE_BOT_SUPPLIER);

	private AcctRegistry() {
	}

	public static Map<ByteArray, Supplier<ACCT>> getAcctMap() {
		return SUPPORTED_ACCTS_BY_CODE_HASH;
	}

	public static Map<ByteArray, Supplier<ACCT>> getFilteredAcctMap(ForeignBlockchainRegistry.Entry entry) {
		if (entry == null)
			return getAcctMap();

		if (entry.isBitcoiny())
			return Map.of(
					BITCOINY_FOREIGN_FOREIGN_ACCT_CODE_HASH, BITCOINY_FOREIGN_FOREIGN_ACCT_SUPPLIER,
					BITCOINY_V5_ACCT_CODE_HASH, BITCOINY_V5_ACCT_SUPPLIER,
					BITCOINY_ACCT_CODE_HASH, BITCOINY_ACCT_SUPPLIER,
					BITCOINY_V3_ACCT_CODE_HASH, BITCOINY_V3_ACCT_SUPPLIER);

		if (ForeignBlockchainRegistry.PIRATECHAIN_NAME.equals(entry.name()))
			return Collections.singletonMap(PIRATECHAIN_ACCT_CODE_HASH, PIRATECHAIN_ACCT_SUPPLIER);

		return Collections.emptyMap();
	}

	public static Map<ByteArray, Supplier<ACCT>> getFilteredAcctMap(String specificBlockchain) {
		if (specificBlockchain == null)
			return getAcctMap();

		ForeignBlockchainRegistry.Entry entry = ForeignBlockchainRegistry.fromString(specificBlockchain);
		if (entry == null)
			return Collections.emptyMap();

		return getFilteredAcctMap(entry);
	}

	public static ACCT getAcctByCodeHash(byte[] codeHash) {
		ByteArray wrappedCodeHash = ByteArray.wrap(codeHash);

		Supplier<ACCT> acctInstanceSupplier = SUPPORTED_ACCTS_BY_CODE_HASH.get(wrappedCodeHash);
		if (acctInstanceSupplier == null)
			return null;

		return acctInstanceSupplier.get();
	}

	public static ACCT getAcctByName(String acctName) {
		Supplier<ACCT> acctInstanceSupplier = SUPPORTED_ACCTS_BY_NAME.get(acctName);
		if (acctInstanceSupplier == null)
			return null;

		return acctInstanceSupplier.get();
	}

	public static ACCT getLatestAcct(ForeignBlockchainRegistry.Entry entry) {
		if (entry == null)
			return null;

		if (entry.isBitcoiny())
			return BITCOINY_ACCT_SUPPLIER.get();

		if (ForeignBlockchainRegistry.PIRATECHAIN_NAME.equals(entry.name()))
			return PIRATECHAIN_ACCT_SUPPLIER.get();

		return null;
	}

	public static ByteArray getLatestAcctCodeHash(ForeignBlockchainRegistry.Entry entry) {
		if (entry == null)
			return null;

		if (entry.isBitcoiny())
			return BITCOINY_ACCT_CODE_HASH;

		if (ForeignBlockchainRegistry.PIRATECHAIN_NAME.equals(entry.name()))
			return PIRATECHAIN_ACCT_CODE_HASH;

		return null;
	}

	public static AcctTradeBot getTradeBotForAcct(ACCT acct) {
		if (acct == null)
			return null;

		Supplier<AcctTradeBot> acctTradeBotSupplier = TRADE_BOTS_BY_ACCT_CLASS.get(acct.getClass());
		if (acctTradeBotSupplier == null)
			return null;

		return acctTradeBotSupplier.get();
	}

}
