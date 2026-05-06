package org.qortal.crosschain;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script;
import org.qortal.crosschain.ChainableServer.ConnectionType;
import org.qortal.crosschain.ElectrumX.Server;
import org.libdohj.params.DigibyteMainNetParams;
import org.libdohj.params.DogecoinMainNetParams;
import org.libdohj.params.DogecoinTestNet3Params;
import org.libdohj.params.LitecoinMainNetParams;
import org.libdohj.params.LitecoinRegTestParams;
import org.libdohj.params.LitecoinTestNet3Params;
import org.libdohj.params.RavencoinMainNetParams;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class BitcoinyChainSpecs {

	public static final String MAIN = "MAIN";
	public static final String TEST3 = "TEST3";
	public static final String REGTEST = "REGTEST";
	public static final String BITCOIN_CURRENCY_CODE = "BTC";
	public static final String LITECOIN_CURRENCY_CODE = "LTC";
	public static final String DOGECOIN_CURRENCY_CODE = "DOGE";
	public static final String DIGIBYTE_CURRENCY_CODE = "DGB";
	public static final String RAVENCOIN_CURRENCY_CODE = "RVN";

	private static final LitecoinMainNetParamsP2ShOverride LITECOIN_MAIN_NET_PARAMS_P2SH_OVERRIDE = new LitecoinMainNetParamsP2ShOverride(50);
	private static final List<Server> NO_SERVERS = List.of();
	private static final List<Server> LOCAL_REGTEST_SERVERS = List.of(new Server("localhost", ConnectionType.SSL, 50002));

	public static final BitcoinyChainSpec BITCOIN = spec(1, "Bitcoin", BITCOIN_CURRENCY_CODE, Coin.valueOf(5_000), 100_000)
			.mainnet(MainNetParams::get, "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", 1_500L, "btc")
			.test3(TestNet3Params::get, "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943", 1_500L, 1_000L, "tbtc")
			.regtest(RegTestParams::get, 1_500L, 1_000L)
			.defaultSpendFeePerByte(20L)
			.build();

	public static final BitcoinyChainSpec LITECOIN = spec(2, "Litecoin", LITECOIN_CURRENCY_CODE, Coin.valueOf(10_000), 1_000_000)
			.mainnet(LitecoinMainNetParams::get, "12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2", 1_000L, "ltc")
			.test3(LitecoinTestNet3Params::get, "4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0", 1_000L, 1_000L, "tltc")
			.regtest(LitecoinRegTestParams::get, 1_000L, 1_000L)
			.addressNormalizer(BitcoinyChainSpecs::normalizeLitecoinAddress)
			.build();

	public static final BitcoinyChainSpec DOGECOIN = spec(3, "Dogecoin", DOGECOIN_CURRENCY_CODE, Coin.valueOf(1_000_000), 100_000_000L)
			.mainnet(DogecoinMainNetParams::get, "1a91e3dace36e2be3bf030a65679fe821aa1d6ef92e7c9902eb318182c355691", 100_000L, "doge")
			.test3(DogecoinTestNet3Params::get, "4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0", 100_000L, 10_000L, null)
			.regtest(() -> null, 100_000L, 10_000L)
			.build();

	public static final BitcoinyChainSpec DIGIBYTE = spec(4, "Digibyte", DIGIBYTE_CURRENCY_CODE, Coin.valueOf(100_000), 1_000_000)
			.mainnet(DigibyteMainNetParams::get, "7497ea1b465eb39f1c8f507bc877078fe016d6fcb6dfad3a64c98dcc6e1e8496", 10_000L, "dgb")
			.test3(TestNet3Params::get, "308ea0711d5763be2995670dd9ca9872753561285a84da1d58be58acaa822252", 10_000L, 10_000L, null)
			.regtest(RegTestParams::get, 10_000L, 10_000L)
			.build();

	public static final BitcoinyChainSpec RAVENCOIN = spec(5, "Ravencoin", RAVENCOIN_CURRENCY_CODE, Coin.valueOf(1_125_000), 1_000_000)
			.mainnet(RavencoinMainNetParams::get, "0000006b444bc2f2ffe627be9d9e7e7a0730000870ef6eb6da46c8eae389df90", 1_000_000L, "rvn")
			.test3(TestNet3Params::get, "000000ecfc5e6324a079542221d00e10362bdc894d56500c414060eea8a3ad5a", 1_000_000L, 1_000_000L, null)
			.regtest(RegTestParams::get, 1_000_000L, 1_000_000L)
			.build();

	private static final List<BitcoinyChainSpec> ALL = List.of(BITCOIN, LITECOIN, DOGECOIN, DIGIBYTE, RAVENCOIN);

	private static final List<String> CURRENCY_CODES = ALL.stream()
			.map(BitcoinyChainSpec::getCurrencyCode)
			.collect(Collectors.toUnmodifiableList());

	private static final Map<String, BitcoinyChainSpec> BY_CURRENCY_CODE = ALL.stream()
			.collect(Collectors.toUnmodifiableMap(BitcoinyChainSpec::getCurrencyCode, spec -> spec));

	private BitcoinyChainSpecs() {
	}

	private static SpecBuilder spec(int foreignBlockchainId, String displayName, String currencyCode, Coin defaultFeePerKb, long minimumOrderAmount) {
		return new SpecBuilder(foreignBlockchainId, displayName, currencyCode, defaultFeePerKb, minimumOrderAmount);
	}

	private static BitcoinyChainSpec.ElectrumServerRefreshConfig refresh(String networkName, String chain1209k) {
		return new BitcoinyChainSpec.ElectrumServerRefreshConfig(networkName, chain1209k);
	}

	public static List<BitcoinyChainSpec> all() {
		return ALL;
	}

	public static Collection<String> currencyCodes() {
		return CURRENCY_CODES;
	}

	public static BitcoinyChainSpec fromCurrencyCode(String currencyCode) {
		if (currencyCode == null)
			return null;

		return BY_CURRENCY_CODE.get(currencyCode.toUpperCase(Locale.ROOT));
	}

	private static String normalizeLitecoinAddress(String address, NetworkParameters targetParams) {
		if (!isCurrentLitecoinP2shAddress(address))
			return address;

		return convertLitecoinP2shAddress(address, targetParams);
	}

	private static boolean isCurrentLitecoinP2shAddress(String address) {
		try {
			Script.ScriptType addressType = Address.fromString(LITECOIN_MAIN_NET_PARAMS_P2SH_OVERRIDE, address).getOutputScriptType();
			return addressType == Script.ScriptType.P2SH;
		} catch (Exception e) {
			return false;
		}
	}

	private static String convertLitecoinP2shAddress(String p2shAddress, NetworkParameters targetParams) {
		try {
			Address address = LegacyAddress.fromBase58(LITECOIN_MAIN_NET_PARAMS_P2SH_OVERRIDE, p2shAddress);
			return LegacyAddress.fromScriptHash(targetParams, address.getHash()).toString();
		} catch (Exception e) {
			return null;
		}
	}

	private static final class SpecBuilder {
		private final int foreignBlockchainId;
		private final BitcoinyChainConfig config;
		private final List<BitcoinyNetwork> networks = new ArrayList<>();
		private final List<BitcoinyChainSpec.ElectrumServerRefreshConfig> refreshConfigs = new ArrayList<>();
		private Long defaultSpendFeePerByte;
		private BitcoinyChainSpec.AddressNormalizer addressNormalizer;

		private SpecBuilder(int foreignBlockchainId, String displayName, String currencyCode, Coin defaultFeePerKb, long minimumOrderAmount) {
			this.foreignBlockchainId = foreignBlockchainId;
			this.config = new BitcoinyChainConfig(displayName, currencyCode, defaultFeePerKb, minimumOrderAmount,
					BitcoinyChainConfig.defaultElectrumXPorts());
		}

		private SpecBuilder mainnet(Supplier<NetworkParameters> paramsSupplier, String genesisHash, long feeRequired, String chain1209k) {
			this.networks.add(StaticBitcoinyNetwork.mainnet(paramsSupplier, NO_SERVERS, genesisHash, feeRequired));
			this.addRefresh(MAIN, chain1209k);
			return this;
		}

		private SpecBuilder test3(Supplier<NetworkParameters> paramsSupplier, String genesisHash, long feeRequired, long p2shFee, String chain1209k) {
			this.networks.add(StaticBitcoinyNetwork.nonMainnet(TEST3, paramsSupplier, NO_SERVERS, genesisHash, feeRequired, p2shFee));
			this.addRefresh(TEST3, chain1209k);
			return this;
		}

		private SpecBuilder regtest(Supplier<NetworkParameters> paramsSupplier, long feeRequired, long p2shFee) {
			this.networks.add(StaticBitcoinyNetwork.nonMainnet(REGTEST, paramsSupplier, LOCAL_REGTEST_SERVERS, null, feeRequired, p2shFee));
			return this;
		}

		private SpecBuilder defaultSpendFeePerByte(long defaultSpendFeePerByte) {
			this.defaultSpendFeePerByte = defaultSpendFeePerByte;
			return this;
		}

		private SpecBuilder addressNormalizer(BitcoinyChainSpec.AddressNormalizer addressNormalizer) {
			this.addressNormalizer = addressNormalizer;
			return this;
		}

		private BitcoinyChainSpec build() {
			return new BitcoinyChainSpec(this.foreignBlockchainId, this.config, this.networks, this.refreshConfigs,
					this.defaultSpendFeePerByte, this.addressNormalizer);
		}

		private void addRefresh(String networkName, String chain1209k) {
			if (chain1209k != null)
				this.refreshConfigs.add(refresh(networkName, chain1209k));
		}
	}
}
