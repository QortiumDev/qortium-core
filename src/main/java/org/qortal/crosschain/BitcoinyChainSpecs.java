package org.qortal.crosschain;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script;
import org.libdohj.params.DigibyteMainNetParams;
import org.libdohj.params.DogecoinMainNetParams;
import org.libdohj.params.DogecoinTestNet3Params;
import org.libdohj.params.LitecoinMainNetParams;
import org.libdohj.params.LitecoinRegTestParams;
import org.libdohj.params.LitecoinTestNet3Params;
import org.libdohj.params.RavencoinMainNetParams;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

	public static final BitcoinyChainSpec BITCOIN = new BitcoinyChainSpec(
			1,
			new BitcoinyChainConfig("Bitcoin", BITCOIN_CURRENCY_CODE, Coin.valueOf(5_000), 100_000,
					BitcoinyChainConfig.defaultElectrumXPorts()),
			List.of(
					StaticBitcoinyNetwork.mainnet(MainNetParams::get,
							BitcoinyServers.bitcoinMain(),
							"000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", 1_500L),
					StaticBitcoinyNetwork.nonMainnet(TEST3, TestNet3Params::get,
							BitcoinyServers.bitcoinTest3(),
							"000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943", 1_500L, 1_000L),
					StaticBitcoinyNetwork.nonMainnet(REGTEST, RegTestParams::get,
							BitcoinyServers.localRegtest(),
							null, 1_500L, 1_000L)),
			List.of(
					refresh(MAIN, "btc", "bitcoinMain"),
					refresh(TEST3, "tbtc", "bitcoinTest3")),
			20L,
			null);

	public static final BitcoinyChainSpec LITECOIN = new BitcoinyChainSpec(
			2,
			new BitcoinyChainConfig("Litecoin", LITECOIN_CURRENCY_CODE, Coin.valueOf(10_000), 1_000_000,
					BitcoinyChainConfig.defaultElectrumXPorts()),
			List.of(
					StaticBitcoinyNetwork.mainnet(LitecoinMainNetParams::get,
							BitcoinyServers.litecoinMain(),
							"12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2", 1_000L),
					StaticBitcoinyNetwork.nonMainnet(TEST3, LitecoinTestNet3Params::get,
							BitcoinyServers.litecoinTest3(),
							"4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0", 1_000L, 1_000L),
					StaticBitcoinyNetwork.nonMainnet(REGTEST, LitecoinRegTestParams::get,
							BitcoinyServers.localRegtest(),
							null, 1_000L, 1_000L)),
			List.of(
					refresh(MAIN, "ltc", "litecoinMain"),
					refresh(TEST3, "tltc", "litecoinTest3")),
			null,
			BitcoinyChainSpecs::normalizeLitecoinAddress);

	public static final BitcoinyChainSpec DOGECOIN = new BitcoinyChainSpec(
			3,
			new BitcoinyChainConfig("Dogecoin", DOGECOIN_CURRENCY_CODE, Coin.valueOf(1_000_000), 100_000_000L,
					BitcoinyChainConfig.defaultElectrumXPorts()),
			List.of(
					StaticBitcoinyNetwork.mainnet(DogecoinMainNetParams::get,
							BitcoinyServers.dogecoinMain(),
							"1a91e3dace36e2be3bf030a65679fe821aa1d6ef92e7c9902eb318182c355691", 100_000L),
					StaticBitcoinyNetwork.nonMainnet(TEST3, DogecoinTestNet3Params::get,
							BitcoinyServers.dogecoinTest3(),
							"4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0", 100_000L, 10_000L),
					StaticBitcoinyNetwork.nonMainnet(REGTEST,
							() -> null, // TODO: DogecoinRegTestParams.get();
							BitcoinyServers.localRegtest(),
							null, 100_000L, 10_000L)),
			List.of(refresh(MAIN, "doge", "dogecoinMain")));

	public static final BitcoinyChainSpec DIGIBYTE = new BitcoinyChainSpec(
			4,
			new BitcoinyChainConfig("Digibyte", DIGIBYTE_CURRENCY_CODE, Coin.valueOf(100_000), 1_000_000,
					BitcoinyChainConfig.defaultElectrumXPorts()),
			List.of(
					StaticBitcoinyNetwork.mainnet(DigibyteMainNetParams::get,
							BitcoinyServers.digibyteMain(),
							"7497ea1b465eb39f1c8f507bc877078fe016d6fcb6dfad3a64c98dcc6e1e8496", 10_000L),
					StaticBitcoinyNetwork.nonMainnet(TEST3, TestNet3Params::get,
							BitcoinyServers.digibyteTest3(),
							"308ea0711d5763be2995670dd9ca9872753561285a84da1d58be58acaa822252", 10_000L, 10_000L),
					StaticBitcoinyNetwork.nonMainnet(REGTEST, RegTestParams::get,
							BitcoinyServers.localRegtest(),
							null, 10_000L, 10_000L)),
			List.of(refresh(MAIN, "dgb", "digibyteMain")));

	public static final BitcoinyChainSpec RAVENCOIN = new BitcoinyChainSpec(
			5,
			new BitcoinyChainConfig("Ravencoin", RAVENCOIN_CURRENCY_CODE, Coin.valueOf(1_125_000), 1_000_000,
					BitcoinyChainConfig.defaultElectrumXPorts()),
			List.of(
					StaticBitcoinyNetwork.mainnet(RavencoinMainNetParams::get,
							BitcoinyServers.ravencoinMain(),
							"0000006b444bc2f2ffe627be9d9e7e7a0730000870ef6eb6da46c8eae389df90", 1_000_000L),
					StaticBitcoinyNetwork.nonMainnet(TEST3, TestNet3Params::get,
							BitcoinyServers.ravencoinTest3(),
							"000000ecfc5e6324a079542221d00e10362bdc894d56500c414060eea8a3ad5a", 1_000_000L, 1_000_000L),
					StaticBitcoinyNetwork.nonMainnet(REGTEST, RegTestParams::get,
							BitcoinyServers.localRegtest(),
							null, 1_000_000L, 1_000_000L)),
			List.of(refresh(MAIN, "rvn", "ravencoinMain")));

	private static final List<BitcoinyChainSpec> ALL = List.of(BITCOIN, LITECOIN, DOGECOIN, DIGIBYTE, RAVENCOIN);

	private static final List<String> CURRENCY_CODES = ALL.stream()
			.map(BitcoinyChainSpec::getCurrencyCode)
			.collect(Collectors.toUnmodifiableList());

	private static final Map<String, BitcoinyChainSpec> BY_CURRENCY_CODE = ALL.stream()
			.collect(Collectors.toUnmodifiableMap(BitcoinyChainSpec::getCurrencyCode, spec -> spec));

	private BitcoinyChainSpecs() {
	}

	private static BitcoinyChainSpec.ElectrumServerRefreshConfig refresh(String networkName, String chain1209k, String builtInSourceMarker) {
		return new BitcoinyChainSpec.ElectrumServerRefreshConfig(networkName, chain1209k, builtInSourceMarker);
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
}
