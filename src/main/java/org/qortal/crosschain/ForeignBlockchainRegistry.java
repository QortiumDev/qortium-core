package org.qortal.crosschain;

import org.qortal.utils.ByteArray;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

	private static final List<Entry> ENTRIES = Arrays.stream(SupportedBlockchain.values())
			.map(Entry::new)
			.collect(Collectors.toUnmodifiableList());

	private static final Map<String, Entry> ENTRIES_BY_NAME = buildEntriesByName();

	private static final Map<Integer, Entry> BITCOINY_ENTRIES_BY_ID = ENTRIES.stream()
			.filter(Entry::isBitcoiny)
			.collect(Collectors.toUnmodifiableMap(Entry::getForeignBlockchainId, entry -> entry));

	private ForeignBlockchainRegistry() {
	}

	public static Collection<Entry> entries() {
		return ENTRIES;
	}

	public static EnumSet<SupportedBlockchain> bitcoinyFacades() {
		return ENTRIES.stream()
				.filter(Entry::isBitcoiny)
				.map(Entry::getFacade)
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
		private final SupportedBlockchain facade;

		private Entry(SupportedBlockchain facade) {
			this.facade = facade;
		}

		public String name() {
			return this.facade.name();
		}

		public SupportedBlockchain getFacade() {
			return this.facade;
		}

		public ForeignBlockchain getInstance() {
			return this.facade.getInstance();
		}

		public ACCT getLatestAcct() {
			return this.facade.getLatestAcct();
		}

		public int getForeignBlockchainId() {
			return this.facade.getForeignBlockchainId();
		}

		public String getCurrencyCode() {
			return this.facade.getCurrencyCode();
		}

		public boolean isBitcoiny() {
			return this.facade.isBitcoiny();
		}

		public BitcoinyChainSpec getBitcoinySpec() {
			return this.facade.getBitcoinySpec();
		}

		public Bitcoiny getBitcoinyInstance() {
			return this.facade.getBitcoinyInstance();
		}

		public void resetForTesting() {
			this.facade.resetForTesting();
		}
	}

}
