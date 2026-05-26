package org.qortium.crosschain;

import org.qortium.settings.Settings;
import org.qortium.utils.ByteArray;

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
		private final Runnable resetForTesting;
		private final boolean bitcoiny;
		private final BitcoinyChainSpec bitcoinySpec;

		private Entry(String name, Integer slip44CoinType, String currencyCode, Supplier<? extends ForeignBlockchain> instanceSupplier,
				Runnable resetForTesting, boolean bitcoiny, BitcoinyChainSpec bitcoinySpec) {
			this.name = name;
			this.slip44CoinType = slip44CoinType;
			this.currencyCode = currencyCode;
			this.instanceSupplier = instanceSupplier;
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
					definition::resetForTesting,
					true,
					spec);
		}

		private static Entry foreign(String name, String currencyCode, Supplier<? extends ForeignBlockchain> instanceSupplier,
				Runnable resetForTesting) {
			return new Entry(name, null, currencyCode, instanceSupplier, resetForTesting, false, null);
		}

		public String name() {
			return this.name;
		}

		public ForeignBlockchain getInstance() {
			return this.instanceSupplier.get();
		}

		public ACCT getLatestAcct() {
			return AcctRegistry.getLatestAcct(this);
		}

		public ByteArray getAcctCodeHash() {
			return AcctRegistry.getLatestAcctCodeHash(this);
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
