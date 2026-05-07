package org.qortal.crosschain;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.utils.MonetaryFormat;
import org.qortal.crosschain.ChainableServer.ConnectionType;
import org.qortal.crosschain.ElectrumX.Server;

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
	public static final String TEST4 = "TEST4";
	public static final String REGTEST = "REGTEST";
	public static final String BITCOIN_CURRENCY_CODE = "BTC";
	public static final String LITECOIN_CURRENCY_CODE = "LTC";
	public static final String DOGECOIN_CURRENCY_CODE = "DOGE";
	public static final String DIGIBYTE_CURRENCY_CODE = "DGB";
	public static final String RAVENCOIN_CURRENCY_CODE = "RVN";
	public static final String DASH_CURRENCY_CODE = "DASH";

	private static final String BITCOIN_GENESIS_COINBASE_SCRIPT = "04ffff001d0104455468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73";
	private static final String BITCOIN_GENESIS_MERKLE_ROOT = "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b";
	private static final String BITCOIN_GENESIS_OUTPUT_SCRIPT = "04678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5f";
	private static final String LITECOIN_GENESIS_COINBASE_SCRIPT = "04ffff001d0104404e592054696d65732030352f4f63742f32303131205374657665204a6f62732c204170706c65e280997320566973696f6e6172792c2044696573206174203536";
	private static final String LITECOIN_GENESIS_MERKLE_ROOT = "97ddfbbae6be97fd6cdf3e7ca13232a3afff2353e29badfab7f73011edd4ced9";
	private static final String LITECOIN_GENESIS_OUTPUT_SCRIPT = "040184710fa689ad5023690c80f3a49c8f13f8d45b8c857fbcbc8bc4a8e4d3eb4b10f4d4604fa08dce601aaf0f470216fe1b51850b4acf21b179c45070ac7b03a9";
	private static final List<Server> NO_SERVERS = List.of();
	private static final List<Server> LOCAL_REGTEST_SERVERS = List.of(new Server("localhost", ConnectionType.SSL, 50002));
	private static final NetworkParameters BITCOIN_MAIN_NET_PARAMS = bitcoinParams("org.bitcoin.production", NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET, "bc")
			.genesis(1231006505L, 2083236893L, 0x1d00ffffL, "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f")
			.genesisHeader(1L, BITCOIN_GENESIS_MERKLE_ROOT)
			.genesisTransaction(BITCOIN_GENESIS_COINBASE_SCRIPT, Coin.COIN.multiply(50L), BITCOIN_GENESIS_OUTPUT_SCRIPT)
			.port(8333)
			.packetMagic(0xf9beb4d9L)
			.addressHeaders(0, 5, 128)
			.segwitAddressHrp("bc")
			.coinbaseAndSubsidy(100, 210_000)
			.bip32Headers(0x0488B21E, 0x0488ADE4)
			.bip32SegwitHeaders(0x04b24746, 0x04b2430c)
			.majorityWindow(750, 950, 1000)
			.dnsSeeds("seed.bitcoin.sipa.be", "dnsseed.bluematt.me", "dnsseed.bitcoin.dashjr.org",
					"seed.bitcoinstats.com", "seed.bitcoin.jonasschnelli.ch", "seed.btc.petertodd.net",
					"seed.bitcoin.sprovoost.nl", "dnsseed.emzy.de", "seed.bitcoin.wiz.biz")
			.build();
	private static final NetworkParameters BITCOIN_TEST_NET_PARAMS = bitcoinParams("org.bitcoin.test", NetworkParameters.PAYMENT_PROTOCOL_ID_TESTNET, "tb")
			.genesis(1296688602L, 414098458L, 0x1d00ffffL, "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943")
			.genesisHeader(1L, BITCOIN_GENESIS_MERKLE_ROOT)
			.genesisTransaction(BITCOIN_GENESIS_COINBASE_SCRIPT, Coin.COIN.multiply(50L), BITCOIN_GENESIS_OUTPUT_SCRIPT)
			.port(18333)
			.packetMagic(0x0b110907L)
			.addressHeaders(111, 196, 239)
			.segwitAddressHrp("tb")
			.coinbaseAndSubsidy(100, 210_000)
			.bip32Headers(0x043587cf, 0x04358394)
			.bip32SegwitHeaders(0x045f1cf6, 0x045f18bc)
			.majorityWindow(51, 75, 100)
			.dnsSeeds("testnet-seed.bitcoin.jonasschnelli.ch", "seed.tbtc.petertodd.net",
					"seed.testnet.bitcoin.sprovoost.nl", "testnet-seed.bluematt.me")
			.build();
	private static final NetworkParameters BITCOIN_REG_TEST_PARAMS = bitcoinParams("org.bitcoin.regtest", NetworkParameters.PAYMENT_PROTOCOL_ID_REGTEST, "bcrt")
			.genesis(1296688602L, 2L, 0x207fffffL, "0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206")
			.genesisHeader(1L, BITCOIN_GENESIS_MERKLE_ROOT)
			.genesisTransaction(BITCOIN_GENESIS_COINBASE_SCRIPT, Coin.COIN.multiply(50L), BITCOIN_GENESIS_OUTPUT_SCRIPT)
			.maxTarget(0x207fffffL)
			.interval(Integer.MAX_VALUE)
			.port(18444)
			.packetMagic(0xfabfb5daL)
			.addressHeaders(111, 196, 239)
			.segwitAddressHrp("bcrt")
			.coinbaseAndSubsidy(100, 150)
			.bip32Headers(0x043587cf, 0x04358394)
			.bip32SegwitHeaders(0x045f1cf6, 0x045f18bc)
			.majorityWindow(750, 950, 1000)
			.build();
	private static final NetworkParameters LITECOIN_MAIN_NET_PARAMS = litecoinParams("org.litecoin.production", "org.litecoin.production", "ltc")
			.genesis(1317972665L, 2084524493L, 0x1e0ffff0L, "12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2")
			.genesisHeader(1L, LITECOIN_GENESIS_MERKLE_ROOT)
			.genesisTransaction(LITECOIN_GENESIS_COINBASE_SCRIPT, Coin.COIN.multiply(50L), LITECOIN_GENESIS_OUTPUT_SCRIPT)
			.port(9333)
			.packetMagic(0xfbc0b6dbL)
			.addressHeaders(48, 5, 176)
			.segwitAddressHrp("ltc")
			.coinbaseAndSubsidy(100, 840_000)
			.bip32Headers(0x0488B21E, 0x0488ADE4)
			.majorityWindow(750, 950, 1000)
			.dnsSeeds("dnsseed.litecointools.com", "dnsseed.litecoinpool.org", "dnsseed.ltc.xurious.com",
					"dnsseed.koin-project.com", "dnsseed.weminemnc.com")
			.build();
	private static final NetworkParameters LITECOIN_CURRENT_P2SH_MAIN_NET_PARAMS = litecoinParams("org.litecoin.production", "org.litecoin.production", "ltc")
			.genesis(1317972665L, 2084524493L, 0x1e0ffff0L, "12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2")
			.genesisHeader(1L, LITECOIN_GENESIS_MERKLE_ROOT)
			.genesisTransaction(LITECOIN_GENESIS_COINBASE_SCRIPT, Coin.COIN.multiply(50L), LITECOIN_GENESIS_OUTPUT_SCRIPT)
			.port(9333)
			.packetMagic(0xfbc0b6dbL)
			.addressHeaders(48, 50, 176)
			.segwitAddressHrp("ltc")
			.coinbaseAndSubsidy(100, 840_000)
			.bip32Headers(0x0488B21E, 0x0488ADE4)
			.majorityWindow(750, 950, 1000)
			.dnsSeeds("dnsseed.litecointools.com", "dnsseed.litecoinpool.org", "dnsseed.ltc.xurious.com",
					"dnsseed.koin-project.com", "dnsseed.weminemnc.com")
			.build();
	private static final NetworkParameters LITECOIN_TEST_NET_PARAMS = litecoinParams("org.litecoin.test", "org.litecoin.test", "tltc")
			.genesis(1486949366L, 293345L, 0x1e0ffff0L, "4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0")
			.genesisHeader(1L, LITECOIN_GENESIS_MERKLE_ROOT)
			.genesisTransaction(LITECOIN_GENESIS_COINBASE_SCRIPT, Coin.COIN.multiply(50L), LITECOIN_GENESIS_OUTPUT_SCRIPT)
			.port(19333)
			.packetMagic(0xfcc1b7dcL)
			.addressHeaders(111, 196, 239)
			.segwitAddressHrp("tltc")
			.coinbaseAndSubsidy(30, 100_000)
			.bip32Headers(0x043587cf, 0x04358394)
			.majorityWindow(51, 75, 100)
			.dnsSeeds("testnet-seed.litecointools.com", "testnet-seed.ltc.xurious.com", "dnsseed.wemine-testnet.com")
			.build();
	private static final NetworkParameters LITECOIN_REG_TEST_PARAMS = litecoinParams("regtest", "regtest", "rltc")
			.genesis(1296688602L, 0L, 0x207fffffL, "530827f38f93b43ed12af0b3ad25a288dc02ed74d6d7857862df51fc56c416f9")
			.genesisHeader(1L, LITECOIN_GENESIS_MERKLE_ROOT)
			.genesisTransaction(LITECOIN_GENESIS_COINBASE_SCRIPT, Coin.COIN.multiply(50L), LITECOIN_GENESIS_OUTPUT_SCRIPT)
			.maxTarget(0x207fffffL)
			.interval(Integer.MAX_VALUE)
			.port(19444)
			.packetMagic(0xfabfb5daL)
			.addressHeaders(111, 196, 239)
			.segwitAddressHrp("rltc")
			.coinbaseAndSubsidy(30, 150)
			.bip32Headers(0x043587cf, 0x04358394)
			.majorityWindow(51, 75, 100)
			.dnsSeeds("testnet-seed.litecointools.com", "testnet-seed.ltc.xurious.com", "dnsseed.wemine-testnet.com")
			.build();
	private static final NetworkParameters DOGECOIN_MAIN_NET_PARAMS = dogecoinParams("org.dogecoin.production", "org.dogecoin.production")
			.genesis(1386325540L, 99943L, 0x1e0ffff0L, "1a91e3dace36e2be3bf030a65679fe821aa1d6ef92e7c9902eb318182c355691")
			.genesisHeader(1L, "5b2a3f53f605d62c53e62932dac6925e3d74afa5a4b459745c36d42d0ed26a69")
			.genesisTransaction(
					"04ffff001d0104084e696e746f6e646f",
					Coin.COIN.multiply(88L),
					"040184710fa689ad5023690c80f3a49c8f13f8d45b8c857fbcbc8bc4a8e4d3eb4b10f4d4604fa08dce601aaf0f470216fe1b51850b4acf21b179c45070ac7b03a9")
			.port(22556)
			.packetMagic(0xc0c0c0c0L)
			.addressHeaders(30, 22, 158)
			.coinbaseAndSubsidy(100, 100_000)
			.bip32Headers(0x02facafd, 0x02fac398)
			.majorityWindow(1500, 1900, 2000)
			.dnsSeeds("seed.dogecoin.com", "seed.multidoge.org", "seed2.multidoge.org", "seed.doger.dogecoin.com")
			.build();
	private static final NetworkParameters DIGIBYTE_MAIN_NET_PARAMS = digibyteParams("main", NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET)
			.genesis(1389388394L, 2447652L, 0x1e0ffff0L, "7497ea1b465eb39f1c8f507bc877078fe016d6fcb6dfad3a64c98dcc6e1e8496")
			.genesisHeader(1L, "72ddd9496b004221ed0557358846d9248ecd4c440ebd28ed901efc18757d0fad")
			.genesisTransaction(
					"04ffff001d01044555534120546f6461793a2031302f4a616e2f323031342c205461726765743a20446174612073746f6c656e2066726f6d20757020746f203131304d20637573746f6d657273",
					Coin.valueOf(8_000, 0),
					"00")
			.port(12024)
			.packetMagic(0xfac3b6daL)
			.addressHeaders(30, 63, 128)
			.segwitAddressHrp("dgb")
			.coinbaseAndSubsidy(100, 210_000)
			.bip32Headers(0x0488B21E, 0x0488ADE4)
			.bip32SegwitHeaders(0x04b24746, 0x04b2430c)
			.majorityWindow(750, 950, 1000)
			.dnsSeeds("seed1.digibyte.io", "seed2.digibyte.io", "seed3.digibyte.io", "seed.digibyte.io",
					"digihash.co", "digiexplorer.info", "seed.digibyteprojects.com")
			.build();
	private static final NetworkParameters RAVENCOIN_MAIN_NET_PARAMS = ravencoinParams("main", NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET)
			.genesis(1514999494L, 25023712L, 0x1e00ffffL, "0000006b444bc2f2ffe627be9d9e7e7a0730000870ef6eb6da46c8eae389df90")
			.genesisHeader(4L, "28ff00a867739a352523808d301f504bc4547699398d70faf2266a8bae5f3516")
			.genesisTransaction(
					"0004ffff001d01044c4d5468652054696d65732030332f4a616e2f3230313820426974636f696e206973206e616d65206f66207468652067616d6520666f72206e65772067656e65726174696f6e206f66206669726d73",
					Coin.valueOf(5_000, 0),
					"04678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5f")
			.port(8767)
			.packetMagic(0x5241564eL)
			.addressHeaders(60, 122, 128)
			.coinbaseAndSubsidy(100, 2_100_000)
			.bip32Headers(0x0488B21E, 0x0488ADE4)
			.majorityWindow(750, 950, 1000)
			.dnsSeeds("seed-raven.bitactivate.com", "seed-raven.ravencoin.com", "seed-raven.ravencoin.org")
			.build();
	private static final NetworkParameters DASH_MAIN_NET_PARAMS = dashParams("org.dash.production", NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET)
			.genesis(1390095618L, 28917698L, 0x1e0ffff0L, "00000ffd590b1485b3caadc19b22e6379c733355108f107a430458cdf3407ab6")
			.port(9999)
			.packetMagic(0xbf0c6bbdL)
			.addressHeaders(76, 16, 204)
			.coinbaseAndSubsidy(100, 210240)
			.bip32Headers(0x0488B21E, 0x0488ADE4)
			.majorityWindow(1815, 1900, 2016)
			.dnsSeeds("dnsseed.dash.org")
			.build();
	public static final BitcoinyChainSpec BITCOIN = spec("BITCOIN", 1, "Bitcoin", BITCOIN_CURRENCY_CODE, Coin.valueOf(5_000), 100_000)
			.mainnet(() -> BITCOIN_MAIN_NET_PARAMS, "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", 1_500L, "btc")
			.test3(() -> BITCOIN_TEST_NET_PARAMS, "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943", 1_500L, 1_000L, "tbtc")
			.regtest(() -> BITCOIN_REG_TEST_PARAMS, 1_500L, 1_000L)
			.defaultSpendFeePerByte(20L)
			.build();

	public static final BitcoinyChainSpec LITECOIN = spec("LITECOIN", 2, "Litecoin", LITECOIN_CURRENCY_CODE, Coin.valueOf(10_000), 1_000_000)
			.mainnet(() -> LITECOIN_MAIN_NET_PARAMS, "12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2", 1_000L, "ltc")
			.test4(() -> LITECOIN_TEST_NET_PARAMS, "4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0", 1_000L, 1_000L, "tltc")
			.regtest(() -> LITECOIN_REG_TEST_PARAMS, 1_000L, 1_000L)
			.addressNormalizer(BitcoinyChainSpecs::normalizeLitecoinAddress)
			.build();

	public static final BitcoinyChainSpec DOGECOIN = spec("DOGECOIN", 3, "Dogecoin", DOGECOIN_CURRENCY_CODE, Coin.valueOf(1_000_000), 100_000_000L)
			.mainnet(() -> DOGECOIN_MAIN_NET_PARAMS, "1a91e3dace36e2be3bf030a65679fe821aa1d6ef92e7c9902eb318182c355691", 100_000L, "doge")
			.build();

	public static final BitcoinyChainSpec DIGIBYTE = spec("DIGIBYTE", 4, "Digibyte", DIGIBYTE_CURRENCY_CODE, Coin.valueOf(100_000), 1_000_000)
			.mainnet(() -> DIGIBYTE_MAIN_NET_PARAMS, "7497ea1b465eb39f1c8f507bc877078fe016d6fcb6dfad3a64c98dcc6e1e8496", 10_000L, "dgb")
			.build();

	public static final BitcoinyChainSpec RAVENCOIN = spec("RAVENCOIN", 5, "Ravencoin", RAVENCOIN_CURRENCY_CODE, Coin.valueOf(1_125_000), 1_000_000)
			.mainnet(() -> RAVENCOIN_MAIN_NET_PARAMS, "0000006b444bc2f2ffe627be9d9e7e7a0730000870ef6eb6da46c8eae389df90", 1_000_000L, "rvn")
			.build();

	public static final BitcoinyChainSpec DASH = spec("DASH", 7, "Dash", DASH_CURRENCY_CODE, Coin.valueOf(10_000), 1_000_000)
			.mainnet(() -> DASH_MAIN_NET_PARAMS, "00000ffd590b1485b3caadc19b22e6379c733355108f107a430458cdf3407ab6", 10_000L, "dash")
			.build();

	private static final List<BitcoinyChainSpec> ALL = List.of(BITCOIN, LITECOIN, DOGECOIN, DIGIBYTE, RAVENCOIN, DASH);

	private static final List<String> CURRENCY_CODES = ALL.stream()
			.map(BitcoinyChainSpec::getCurrencyCode)
			.collect(Collectors.toUnmodifiableList());

	private static final Map<String, BitcoinyChainSpec> BY_CURRENCY_CODE = ALL.stream()
			.collect(Collectors.toUnmodifiableMap(BitcoinyChainSpec::getCurrencyCode, spec -> spec));

	private BitcoinyChainSpecs() {
	}

	private static SpecBuilder spec(String canonicalName, int foreignBlockchainId, String displayName, String currencyCode, Coin defaultFeePerKb, long minimumOrderAmount) {
		return new SpecBuilder(canonicalName, foreignBlockchainId, displayName, currencyCode, defaultFeePerKb, minimumOrderAmount);
	}

	private static StaticBitcoinyParams.Builder bitcoinParams(String id, String paymentProtocolId, String segwitAddressHrp) {
		return StaticBitcoinyParams.builder(id, paymentProtocolId, "bitcoin")
				.maxTarget(0x1d00ffffL)
				.segwitAddressHrp(segwitAddressHrp)
				.maxMoney(Coin.COIN.multiply(21_000_000L))
				.minNonDustOutput(Coin.valueOf(546L))
				.monetaryFormat(new MonetaryFormat())
				.difficultyValidationFailure("Bitcoin difficulty verification is not implemented for Electrum-backed parameters");
	}

	static NetworkParameters litecoinTestNetParams() {
		return LITECOIN_TEST_NET_PARAMS;
	}

	static NetworkParameters litecoinRegTestParams() {
		return LITECOIN_REG_TEST_PARAMS;
	}

	private static StaticBitcoinyParams.Builder litecoinParams(String id, String paymentProtocolId, String segwitAddressHrp) {
		return StaticBitcoinyParams.builder(id, paymentProtocolId, "litecoin")
				.maxTarget(0x1e0fffffL)
				.targetTimespan(302_400)
				.interval(2016)
				.segwitAddressHrp(segwitAddressHrp)
				.maxMoney(Coin.COIN.multiply(21_000_000L))
				.minNonDustOutput(Coin.valueOf(100_000L))
				.monetaryFormat(MonetaryFormat.BTC.noCode()
						.code(0, "LITE")
						.code(3, "mLITE")
						.code(7, "Liteoshi"))
				.difficultyValidationFailure("Litecoin difficulty verification is not implemented for Electrum-backed parameters");
	}

	private static StaticBitcoinyParams.Builder dogecoinParams(String id, String paymentProtocolId) {
		return StaticBitcoinyParams.builder(id, paymentProtocolId, "dogecoin")
				.maxTarget(0x1e0fffffL)
				.targetTimespan(14_400)
				.interval(240)
				.minNonDustOutput(Coin.COIN)
				.monetaryFormat(MonetaryFormat.BTC.noCode()
						.code(0, "DOGE")
						.code(3, "mDOGE")
						.code(7, "Koinu"))
				.hasMaxMoney(false)
				.difficultyValidationFailure("Dogecoin difficulty verification is not implemented for Electrum-backed parameters");
	}

	private static StaticBitcoinyParams.Builder digibyteParams(String id, String paymentProtocolId) {
		return StaticBitcoinyParams.builder(id, paymentProtocolId, "digibyte")
				.maxTarget(0x1e0fffffL)
				.targetTimespan(8_640)
				.interval(2016)
				.maxMoney(Coin.COIN.multiply(21_000_000L))
				.minNonDustOutput(Coin.valueOf(546L))
				.monetaryFormat(MonetaryFormat.BTC.noCode()
						.code(0, "DGB")
						.code(3, "mDGB")
						.code(7, "Digioshi"))
				.difficultyValidationFailure("DigiByte difficulty verification is not implemented for Electrum-backed parameters");
	}

	private static StaticBitcoinyParams.Builder ravencoinParams(String id, String paymentProtocolId) {
		return StaticBitcoinyParams.builder(id, paymentProtocolId, "ravencoin")
				.maxTarget(0x1e00ffffL)
				.targetTimespan(120_960)
				.interval(2016)
				.maxMoney(Coin.COIN.multiply(21_000_000L))
				.minNonDustOutput(Coin.valueOf(2_730L))
				.monetaryFormat(MonetaryFormat.BTC.noCode()
						.code(0, "RVN")
						.code(3, "mRVN")
						.code(7, "Ravenoshi"))
				.difficultyValidationFailure("Ravencoin difficulty verification is not implemented for Electrum-backed parameters");
	}

	private static StaticBitcoinyParams.Builder dashParams(String id, String paymentProtocolId) {
		return StaticBitcoinyParams.builder(id, paymentProtocolId, "dash")
				.maxTarget(0x1e0fffffL)
				.targetTimespan(24 * 60 * 60)
				.difficultyValidationFailure("Dash difficulty verification is not implemented for Electrum-backed parameters");
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
			BitcoinyAddress bitcoinyAddress = BitcoinyAddress.fromString(LITECOIN_CURRENT_P2SH_MAIN_NET_PARAMS, address);
			return bitcoinyAddress.getType() == BitcoinyAddress.Type.P2SH;
		} catch (Exception e) {
			return false;
		}
	}

	private static String convertLitecoinP2shAddress(String p2shAddress, NetworkParameters targetParams) {
		try {
			BitcoinyAddress address = BitcoinyAddress.fromString(LITECOIN_CURRENT_P2SH_MAIN_NET_PARAMS, p2shAddress);
			return BitcoinyAddress.fromScriptHash(targetParams, address.getPayload()).toString();
		} catch (Exception e) {
			return null;
		}
	}

	private static final class SpecBuilder {
		private final String canonicalName;
		private final int foreignBlockchainId;
		private final BitcoinyChainConfig config;
		private final List<BitcoinyNetwork> networks = new ArrayList<>();
		private final List<BitcoinyChainSpec.ElectrumServerRefreshConfig> refreshConfigs = new ArrayList<>();
		private Long defaultSpendFeePerByte;
		private BitcoinyChainSpec.AddressNormalizer addressNormalizer;

		private SpecBuilder(String canonicalName, int foreignBlockchainId, String displayName, String currencyCode, Coin defaultFeePerKb, long minimumOrderAmount) {
			this.canonicalName = canonicalName;
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

		private SpecBuilder test4(Supplier<NetworkParameters> paramsSupplier, String genesisHash, long feeRequired, long p2shFee, String chain1209k) {
			this.networks.add(StaticBitcoinyNetwork.nonMainnet(TEST4, paramsSupplier, NO_SERVERS, genesisHash, feeRequired, p2shFee));
			this.addRefresh(TEST4, chain1209k);
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
			return new BitcoinyChainSpec(this.canonicalName, this.foreignBlockchainId, this.config, this.networks, this.refreshConfigs,
					this.defaultSpendFeePerByte, this.addressNormalizer);
		}

		private void addRefresh(String networkName, String chain1209k) {
			if (chain1209k != null)
				this.refreshConfigs.add(refresh(networkName, chain1209k));
		}
	}
}
