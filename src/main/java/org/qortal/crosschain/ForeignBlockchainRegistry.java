package org.qortal.crosschain;

import org.qortal.controller.tradebot.AcctTradeBot;
import org.qortal.controller.tradebot.BitcoinyACCTv3TradeBot;
import org.qortal.controller.tradebot.BitcoinyACCTv4TradeBot;
import org.qortal.controller.tradebot.BitcoinyACCTv5TradeBot;
import org.qortal.controller.tradebot.BitcoinyForeignForeignTradeBot;
import org.qortal.controller.tradebot.PirateChainACCTv3TradeBot;
import org.qortal.settings.Settings;
import org.qortal.utils.ByteArray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class ForeignBlockchainRegistry {

	public static final String PIRATECHAIN_NAME = "PIRATECHAIN";

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

	private static final List<Entry> ENTRIES = buildEntries();

	private static final Map<String, Entry> ENTRIES_BY_NAME = buildEntriesByName();

	private static final Map<String, Entry> BITCOINY_ENTRIES_BY_CHAIN_ID = buildBitcoinyEntriesByChainId();

	private ForeignBlockchainRegistry() {
	}

	private static List<Entry> buildEntries() {
		List<Entry> entries = new ArrayList<>();

		for (BitcoinyChainSpec spec : BitcoinyChainSpecs.all())
			entries.add(Entry.bitcoiny(spec));

		entries.add(Entry.foreign(
				PIRATECHAIN_NAME,
				PirateChain.CURRENCY_CODE,
				PirateChain::getInstance,
				PIRATECHAIN_ACCT_SUPPLIER,
				PIRATECHAIN_ACCT_CODE_HASH,
				PirateChain::resetForTesting));

		return Collections.unmodifiableList(entries);
	}

	public static Collection<Entry> entries() {
		return ENTRIES;
	}

	public static Collection<Entry> bitcoinyEntries() {
		return ENTRIES.stream()
				.filter(Entry::isBitcoiny)
				.collect(Collectors.toUnmodifiableList());
	}

	public static Collection<String> entryNames() {
		return ENTRIES.stream()
				.map(Entry::name)
				.collect(Collectors.toUnmodifiableList());
	}

	public static Entry fromString(String name) {
		if (name == null)
			return null;

		String normalizedName = normalize(name);
		if (normalizedName.isEmpty())
			return null;

		return ENTRIES_BY_NAME.get(normalizedName);
	}

	public static Entry fromStringRequired(String name) {
		Entry entry = fromString(name);
		if (entry == null)
			throw new IllegalArgumentException("Unsupported foreign blockchain: " + name);

		return entry;
	}

	public static Entry fromRegisteredBitcoinyString(String name) {
		Entry entry = fromString(name);
		return entry != null && entry.isBitcoiny() ? entry : null;
	}

	public static Bitcoiny getRegisteredBitcoinyInstance(String name) {
		Entry entry = fromRegisteredBitcoinyString(name);
		return entry == null ? null : entry.getBitcoinyInstance();
	}

	public static Bitcoiny getBitcoinyInstance(String name) {
		Entry entry = fromString(name);
		return entry == null ? null : entry.getBitcoinyInstance();
	}

	public static Entry fromBitcoinyChainId(String chainId) {
		if (chainId == null)
			return null;

		try {
			return BITCOINY_ENTRIES_BY_CHAIN_ID.get(Bip122ChainId.normalize(chainId));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static Entry fromBitcoinyChainIdReference(byte[] chainIdReference) {
		if (chainIdReference == null)
			return null;

		try {
			return fromBitcoinyChainId(Bip122ChainId.fromReferenceBytes(chainIdReference));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static Map<ByteArray, Supplier<ACCT>> getAcctMap() {
		return SUPPORTED_ACCTS_BY_CODE_HASH;
	}

	public static Map<ByteArray, Supplier<ACCT>> getFilteredAcctMap(Entry entry) {
		if (entry == null)
			return getAcctMap();

		if (entry.isBitcoiny())
			return Map.of(
					BITCOINY_FOREIGN_FOREIGN_ACCT_CODE_HASH, BITCOINY_FOREIGN_FOREIGN_ACCT_SUPPLIER,
					BITCOINY_V5_ACCT_CODE_HASH, BITCOINY_V5_ACCT_SUPPLIER,
					BITCOINY_ACCT_CODE_HASH, BITCOINY_ACCT_SUPPLIER,
					BITCOINY_V3_ACCT_CODE_HASH, BITCOINY_V3_ACCT_SUPPLIER);

		return Collections.singletonMap(entry.getAcctCodeHash(), entry.acctSupplier);
	}

	public static Map<ByteArray, Supplier<ACCT>> getFilteredAcctMap(String specificBlockchain) {
		if (specificBlockchain == null)
			return getAcctMap();

		Entry entry = fromString(specificBlockchain);
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

	public static AcctTradeBot getTradeBotForAcct(ACCT acct) {
		if (acct == null)
			return null;

		Supplier<AcctTradeBot> acctTradeBotSupplier = TRADE_BOTS_BY_ACCT_CLASS.get(acct.getClass());
		if (acctTradeBotSupplier == null)
			return null;

		return acctTradeBotSupplier.get();
	}

	private static Map<String, Entry> buildEntriesByName() {
		Map<String, Entry> entriesByName = new LinkedHashMap<>();

		for (Entry entry : ENTRIES) {
			entriesByName.put(normalize(entry.name()), entry);
			entriesByName.put(normalize(entry.getCurrencyCode()), entry);
		}

		return Collections.unmodifiableMap(entriesByName);
	}

	private static Map<String, Entry> buildBitcoinyEntriesByChainId() {
		Map<String, Entry> entriesByChainId = new LinkedHashMap<>();

		for (Entry entry : ENTRIES) {
			if (!entry.isBitcoiny())
				continue;

			for (BitcoinyNetwork network : entry.getBitcoinySpec().getNetworks()) {
				Entry previous = entriesByChainId.put(network.getChainId(), entry);
				if (previous != null)
					throw new IllegalStateException(String.format("Duplicate Bitcoiny chain id %s for %s and %s",
							network.getChainId(), previous.name(), entry.name()));
			}
		}

		return Collections.unmodifiableMap(entriesByChainId);
	}

	private static String normalize(String name) {
		return name.trim().toUpperCase(Locale.ROOT);
	}

	public static final class Entry {
		private final String name;
		private final Integer slip44CoinType;
		private final String currencyCode;
		private final Supplier<? extends ForeignBlockchain> instanceSupplier;
		private final Supplier<ACCT> acctSupplier;
		private final ByteArray acctCodeHash;
		private final Runnable resetForTesting;
		private final boolean bitcoiny;
		private final BitcoinyChainSpec bitcoinySpec;

		private Entry(String name, Integer slip44CoinType, String currencyCode, Supplier<? extends ForeignBlockchain> instanceSupplier,
				Supplier<ACCT> acctSupplier, ByteArray acctCodeHash, Runnable resetForTesting, boolean bitcoiny, BitcoinyChainSpec bitcoinySpec) {
			this.name = name;
			this.slip44CoinType = slip44CoinType;
			this.currencyCode = currencyCode;
			this.instanceSupplier = instanceSupplier;
			this.acctSupplier = acctSupplier;
			this.acctCodeHash = acctCodeHash;
			this.resetForTesting = resetForTesting;
			this.bitcoiny = bitcoiny;
			this.bitcoinySpec = bitcoinySpec;
		}

		private static Entry bitcoiny(BitcoinyChainSpec spec) {
			BitcoinyChainDefinition<RegisteredBitcoiny> definition = new BitcoinyChainDefinition<>(
					spec.getConfig(),
					() -> Settings.getInstance().getBitcoinyNetwork(spec.getCurrencyCode()),
					(config, network) -> new RegisteredBitcoiny(spec, network));

			return new Entry(
					spec.getCanonicalName(),
					spec.getSlip44CoinType(),
					spec.getCurrencyCode(),
					definition::getInstance,
					BITCOINY_ACCT_SUPPLIER,
					BITCOINY_ACCT_CODE_HASH,
					definition::resetForTesting,
					true,
					spec);
		}

		private static Entry foreign(String name, String currencyCode, Supplier<? extends ForeignBlockchain> instanceSupplier,
				Supplier<ACCT> acctSupplier, ByteArray acctCodeHash, Runnable resetForTesting) {
			return new Entry(name, null, currencyCode, instanceSupplier, acctSupplier, acctCodeHash, resetForTesting, false, null);
		}

		public String name() {
			return this.name;
		}

		public ForeignBlockchain getInstance() {
			return this.instanceSupplier.get();
		}

		public ACCT getLatestAcct() {
			return this.acctSupplier.get();
		}

		public ByteArray getAcctCodeHash() {
			return this.acctCodeHash;
		}

		public Integer getSlip44CoinType() {
			return this.slip44CoinType;
		}

		public String getCurrencyCode() {
			return this.currencyCode;
		}

		public boolean isBitcoiny() {
			return this.bitcoiny;
		}

		public BitcoinyChainSpec getBitcoinySpec() {
			return this.bitcoinySpec;
		}

		public String getActiveChainId() {
			if (!this.isBitcoiny())
				return null;

			return Settings.getInstance().getBitcoinyNetwork(this.currencyCode).getChainId();
		}

		public byte[] getActiveChainIdReferenceBytes() {
			String chainId = this.getActiveChainId();
			return chainId == null ? null : Bip122ChainId.toReferenceBytes(chainId);
		}

		public Bitcoiny getBitcoinyInstance() {
			ForeignBlockchain foreignBlockchain = this.getInstance();
			return foreignBlockchain instanceof Bitcoiny ? (Bitcoiny) foreignBlockchain : null;
		}

		public void resetForTesting() {
			this.resetForTesting.run();
		}
	}

}
