package org.qortal.crosschain;

import org.qortal.settings.Settings;
import org.qortal.utils.ByteArray;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class ForeignBlockchainRegistry {

	private static final Supplier<ACCT> BITCOINY_ACCT_SUPPLIER = BitcoinyACCTv3::getInstance;
	private static final Supplier<ACCT> PIRATECHAIN_ACCT_SUPPLIER = PirateChainACCTv3::getInstance;

	private static final Map<ByteArray, Supplier<ACCT>> BITCOINY_ACCT_MAP = Collections.singletonMap(
			ByteArray.wrap(BitcoinyACCTv3.CODE_BYTES_HASH), BITCOINY_ACCT_SUPPLIER);

	private static final Map<ByteArray, Supplier<ACCT>> PIRATECHAIN_ACCT_MAP = Collections.singletonMap(
			ByteArray.wrap(PirateChainACCTv3.CODE_BYTES_HASH), PIRATECHAIN_ACCT_SUPPLIER);

	private static final Map<ByteArray, Supplier<ACCT>> SUPPORTED_ACCTS_BY_CODE_HASH = Map.of(
			ByteArray.wrap(BitcoinyACCTv3.CODE_BYTES_HASH), BITCOINY_ACCT_SUPPLIER,
			ByteArray.wrap(PirateChainACCTv3.CODE_BYTES_HASH), PIRATECHAIN_ACCT_SUPPLIER);

	private static final Map<String, Supplier<ACCT>> SUPPORTED_ACCTS_BY_NAME = Map.of(
			BitcoinyACCTv3.NAME, BITCOINY_ACCT_SUPPLIER,
			PirateChainACCTv3.NAME, PIRATECHAIN_ACCT_SUPPLIER);

	private static final List<Entry> ENTRIES = buildEntries();

	private static final Map<String, Entry> ENTRIES_BY_NAME = buildEntriesByName();

	private static final Map<Integer, Entry> BITCOINY_ENTRIES_BY_ID = ENTRIES.stream()
			.filter(Entry::isBitcoiny)
			.collect(Collectors.toUnmodifiableMap(Entry::getForeignBlockchainId, entry -> entry));

	private ForeignBlockchainRegistry() {
	}

	private static List<Entry> buildEntries() {
		List<Entry> entries = new ArrayList<>();

		for (BitcoinyChainSpec spec : BitcoinyChainSpecs.all())
			entries.add(Entry.bitcoiny(spec));

		entries.add(Entry.foreign(
				"PIRATECHAIN",
				6,
				PirateChain.CURRENCY_CODE,
				PirateChain::getInstance,
				PIRATECHAIN_ACCT_SUPPLIER,
				PirateChain::resetForTesting));

		return Collections.unmodifiableList(entries);
	}

	public static Collection<Entry> entries() {
		return ENTRIES;
	}

	public static EnumSet<SupportedBlockchain> bitcoinyFacades() {
		return ENTRIES.stream()
				.filter(Entry::isBitcoiny)
				.map(Entry::getFacade)
				.filter(Objects::nonNull)
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(SupportedBlockchain.class)));
	}

	public static Entry fromString(String name) {
		if (name == null)
			return null;

		String normalizedName = normalize(name);
		if (normalizedName.isEmpty())
			return null;

		return ENTRIES_BY_NAME.get(normalizedName);
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

	public static Entry fromForeignBlockchainId(int foreignBlockchainId) {
		return BITCOINY_ENTRIES_BY_ID.get(foreignBlockchainId);
	}

	public static Map<ByteArray, Supplier<ACCT>> getAcctMap() {
		return SUPPORTED_ACCTS_BY_CODE_HASH;
	}

	public static Map<ByteArray, Supplier<ACCT>> getFilteredAcctMap(Entry entry) {
		if (entry == null)
			return getAcctMap();

		return entry.isBitcoiny() ? BITCOINY_ACCT_MAP : PIRATECHAIN_ACCT_MAP;
	}

	public static Map<ByteArray, Supplier<ACCT>> getFilteredAcctMap(SupportedBlockchain facade) {
		if (facade == null)
			return getAcctMap();

		return getFilteredAcctMap(fromString(facade.name()));
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

	private static Map<String, Entry> buildEntriesByName() {
		Map<String, Entry> entriesByName = new LinkedHashMap<>();

		for (Entry entry : ENTRIES) {
			entriesByName.put(normalize(entry.name()), entry);
			entriesByName.put(normalize(entry.getCurrencyCode()), entry);
		}

		return Collections.unmodifiableMap(entriesByName);
	}

	private static String normalize(String name) {
		return name.trim().toUpperCase(Locale.ROOT);
	}

	public static final class Entry {
		private final String name;
		private final int foreignBlockchainId;
		private final String currencyCode;
		private final Supplier<? extends ForeignBlockchain> instanceSupplier;
		private final Supplier<ACCT> acctSupplier;
		private final Runnable resetForTesting;
		private final boolean bitcoiny;
		private final BitcoinyChainSpec bitcoinySpec;

		private Entry(String name, int foreignBlockchainId, String currencyCode, Supplier<? extends ForeignBlockchain> instanceSupplier,
				Supplier<ACCT> acctSupplier, Runnable resetForTesting, boolean bitcoiny, BitcoinyChainSpec bitcoinySpec) {
			this.name = name;
			this.foreignBlockchainId = foreignBlockchainId;
			this.currencyCode = currencyCode;
			this.instanceSupplier = instanceSupplier;
			this.acctSupplier = acctSupplier;
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
					spec.getForeignBlockchainId(),
					spec.getCurrencyCode(),
					definition::getInstance,
					BITCOINY_ACCT_SUPPLIER,
					definition::resetForTesting,
					true,
					spec);
		}

		private static Entry foreign(String name, int foreignBlockchainId, String currencyCode, Supplier<? extends ForeignBlockchain> instanceSupplier,
				Supplier<ACCT> acctSupplier, Runnable resetForTesting) {
			return new Entry(name, foreignBlockchainId, currencyCode, instanceSupplier, acctSupplier, resetForTesting, false, null);
		}

		public String name() {
			return this.name;
		}

		public SupportedBlockchain getFacade() {
			try {
				return SupportedBlockchain.valueOf(this.name);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}

		public ForeignBlockchain getInstance() {
			return this.instanceSupplier.get();
		}

		public ACCT getLatestAcct() {
			return this.acctSupplier.get();
		}

		public int getForeignBlockchainId() {
			return this.foreignBlockchainId;
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

		public Bitcoiny getBitcoinyInstance() {
			ForeignBlockchain foreignBlockchain = this.getInstance();
			return foreignBlockchain instanceof Bitcoiny ? (Bitcoiny) foreignBlockchain : null;
		}

		public void resetForTesting() {
			this.resetForTesting.run();
		}
	}

}
