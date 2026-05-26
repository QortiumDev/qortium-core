package org.qortium.crosschain;

import org.bitcoinj.core.NetworkParameters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BitcoinyChainSpec {

	private final String canonicalName;
	private final int slip44CoinType;
	private final BitcoinyChainConfig config;
	private final Map<String, BitcoinyNetwork> networksByName;
	private final List<ElectrumServerRefreshConfig> electrumServerRefreshConfigs;
	private final Long defaultSpendFeePerByte;
	private final AddressNormalizer addressNormalizer;
	private final SpendableOutputScriptFilter spendableOutputScriptFilter;
	private final BitcoinyTransactionFormat transactionFormat;
	private final boolean supportsForeignForeignTrades;

	public BitcoinyChainSpec(int slip44CoinType, BitcoinyChainConfig config, Collection<? extends BitcoinyNetwork> networks,
			Collection<ElectrumServerRefreshConfig> electrumServerRefreshConfigs) {
		this(config.getCurrencyCode(), slip44CoinType, config, networks, electrumServerRefreshConfigs, null, null, null);
	}

	public BitcoinyChainSpec(int slip44CoinType, BitcoinyChainConfig config, Collection<? extends BitcoinyNetwork> networks,
			Collection<ElectrumServerRefreshConfig> electrumServerRefreshConfigs, Long defaultSpendFeePerByte, AddressNormalizer addressNormalizer) {
		this(config.getCurrencyCode(), slip44CoinType, config, networks, electrumServerRefreshConfigs, defaultSpendFeePerByte, addressNormalizer, null);
	}

	public BitcoinyChainSpec(String canonicalName, int slip44CoinType, BitcoinyChainConfig config, Collection<? extends BitcoinyNetwork> networks,
			Collection<ElectrumServerRefreshConfig> electrumServerRefreshConfigs, Long defaultSpendFeePerByte, AddressNormalizer addressNormalizer) {
		this(canonicalName, slip44CoinType, config, networks, electrumServerRefreshConfigs, defaultSpendFeePerByte, addressNormalizer, null);
	}

	public BitcoinyChainSpec(String canonicalName, int slip44CoinType, BitcoinyChainConfig config, Collection<? extends BitcoinyNetwork> networks,
			Collection<ElectrumServerRefreshConfig> electrumServerRefreshConfigs, Long defaultSpendFeePerByte, AddressNormalizer addressNormalizer,
			SpendableOutputScriptFilter spendableOutputScriptFilter) {
		this(canonicalName, slip44CoinType, config, networks, electrumServerRefreshConfigs, defaultSpendFeePerByte, addressNormalizer,
				spendableOutputScriptFilter, BitcoinyTransactionFormat.LEGACY);
	}

	public BitcoinyChainSpec(String canonicalName, int slip44CoinType, BitcoinyChainConfig config, Collection<? extends BitcoinyNetwork> networks,
			Collection<ElectrumServerRefreshConfig> electrumServerRefreshConfigs, Long defaultSpendFeePerByte, AddressNormalizer addressNormalizer,
			SpendableOutputScriptFilter spendableOutputScriptFilter, BitcoinyTransactionFormat transactionFormat) {
		this(canonicalName, slip44CoinType, config, networks, electrumServerRefreshConfigs, defaultSpendFeePerByte, addressNormalizer,
				spendableOutputScriptFilter, transactionFormat, false);
	}

	public BitcoinyChainSpec(String canonicalName, int slip44CoinType, BitcoinyChainConfig config, Collection<? extends BitcoinyNetwork> networks,
			Collection<ElectrumServerRefreshConfig> electrumServerRefreshConfigs, Long defaultSpendFeePerByte, AddressNormalizer addressNormalizer,
			SpendableOutputScriptFilter spendableOutputScriptFilter, BitcoinyTransactionFormat transactionFormat,
			boolean supportsForeignForeignTrades) {
		this.canonicalName = canonicalName;
		this.slip44CoinType = slip44CoinType;
		this.config = config;
		this.networksByName = toNetworksByName(networks);
		this.electrumServerRefreshConfigs = Collections.unmodifiableList(new ArrayList<>(electrumServerRefreshConfigs));
		this.defaultSpendFeePerByte = defaultSpendFeePerByte;
		this.addressNormalizer = addressNormalizer;
		this.spendableOutputScriptFilter = spendableOutputScriptFilter;
		this.transactionFormat = transactionFormat;
		this.supportsForeignForeignTrades = supportsForeignForeignTrades;
	}

	private static Map<String, BitcoinyNetwork> toNetworksByName(Collection<? extends BitcoinyNetwork> networks) {
		Map<String, BitcoinyNetwork> networksByName = new LinkedHashMap<>();
		for (BitcoinyNetwork network : networks) {
			if (networksByName.put(network.name(), network) != null)
				throw new IllegalArgumentException("Duplicate Bitcoiny network name: " + network.name());
		}

		return Collections.unmodifiableMap(networksByName);
	}

	public String getCanonicalName() {
		return this.canonicalName;
	}

	public int getSlip44CoinType() {
		return this.slip44CoinType;
	}

	public BitcoinyChainConfig getConfig() {
		return this.config;
	}

	public String getCurrencyCode() {
		return this.config.getCurrencyCode();
	}

	public BitcoinyNetwork getNetwork(String networkName) {
		return this.networksByName.get(networkName);
	}

	public Collection<BitcoinyNetwork> getNetworks() {
		return this.networksByName.values();
	}

	public List<ElectrumServerRefreshConfig> getElectrumServerRefreshConfigs() {
		return this.electrumServerRefreshConfigs;
	}

	public Long getDefaultSpendFeePerByte() {
		return this.defaultSpendFeePerByte;
	}

	public String normalizeAddress(String address, NetworkParameters networkParameters) {
		if (this.addressNormalizer == null)
			return address;

		return this.addressNormalizer.normalize(address, networkParameters);
	}

	public boolean hasSpendableOutputScriptFilter() {
		return this.spendableOutputScriptFilter != null;
	}

	public boolean isSpendableOutputScript(byte[] scriptPubKey) {
		return this.spendableOutputScriptFilter == null || this.spendableOutputScriptFilter.isSpendable(scriptPubKey);
	}

	public BitcoinyTransactionFormat getTransactionFormat() {
		return this.transactionFormat;
	}

	public boolean supportsForeignForeignTrades() {
		return this.supportsForeignForeignTrades;
	}

	@FunctionalInterface
	public interface AddressNormalizer {
		String normalize(String address, NetworkParameters networkParameters);
	}

	@FunctionalInterface
	public interface SpendableOutputScriptFilter {
		boolean isSpendable(byte[] scriptPubKey);
	}

	public static final class ElectrumServerRefreshConfig {
		private final String networkName;
		private final String chain1209k;

		public ElectrumServerRefreshConfig(String networkName, String chain1209k) {
			this.networkName = networkName;
			this.chain1209k = chain1209k;
		}

		public String getNetworkName() {
			return this.networkName;
		}

		public String getChain1209k() {
			return this.chain1209k;
		}
	}
}
