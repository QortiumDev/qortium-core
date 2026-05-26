package org.qortium.crosschain;

import org.bitcoinj.base.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.base.utils.MonetaryFormat;
import org.qortium.crosschain.ChainableServer.ConnectionType;
import org.qortium.crosschain.ElectrumX.Server;

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
	public static final String BITCOIN_CASH_CURRENCY_CODE = "BCH";
	public static final String LITECOIN_CURRENCY_CODE = "LTC";
	public static final String DOGECOIN_CURRENCY_CODE = "DOGE";
	public static final String DIGIBYTE_CURRENCY_CODE = "DGB";
	public static final String RAVENCOIN_CURRENCY_CODE = "RVN";
	public static final String DASH_CURRENCY_CODE = "DASH";
	public static final String PEERCOIN_CURRENCY_CODE = "PPC";
	public static final String NAMECOIN_CURRENCY_CODE = "NMC";
	public static final String FIRO_CURRENCY_CODE = "FIRO";
	public static final String KOMODO_CURRENCY_CODE = "KMD";
	public static final String VERUS_CURRENCY_CODE = "VRSC";
	public static final String ZCASH_CURRENCY_CODE = "ZEC";
	public static final String LBRY_CREDITS_CURRENCY_CODE = "LBC";
	public static final String VERGE_CURRENCY_CODE = "XVG";
	public static final int BITCOIN_SLIP44_COIN_TYPE = 0;
	public static final int BITCOIN_CASH_SLIP44_COIN_TYPE = 145;
	public static final int LITECOIN_SLIP44_COIN_TYPE = 2;
	public static final int DOGECOIN_SLIP44_COIN_TYPE = 3;
	public static final int DASH_SLIP44_COIN_TYPE = 5;
	public static final int PEERCOIN_SLIP44_COIN_TYPE = 6;
	public static final int NAMECOIN_SLIP44_COIN_TYPE = 7;
	public static final int DIGIBYTE_SLIP44_COIN_TYPE = 20;
	public static final int FIRO_SLIP44_COIN_TYPE = 136;
	public static final int KOMODO_SLIP44_COIN_TYPE = 141;
	public static final int VERUS_SLIP44_COIN_TYPE = 133;
	public static final int ZCASH_SLIP44_COIN_TYPE = 133;
	public static final int LBRY_CREDITS_SLIP44_COIN_TYPE = 140;
	public static final int RAVENCOIN_SLIP44_COIN_TYPE = 175;
	public static final int VERGE_SLIP44_COIN_TYPE = 77;

	private static final String BITCOIN_GENESIS_COINBASE_SCRIPT = "04ffff001d0104455468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73";
	private static final String BITCOIN_GENESIS_MERKLE_ROOT = "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b";
	private static final String BITCOIN_GENESIS_OUTPUT_SCRIPT = "04678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5f";
	private static final String BITCOIN_TEST4_GENESIS_COINBASE_SCRIPT = "04ffff001d01044c4c30332f4d61792f323032342030303030303030303030303030303030303030303165626435386332343439373062336161396437383362623030313031316662653865613865393865303065";
	private static final String BITCOIN_TEST4_GENESIS_MERKLE_ROOT = "7aa0a7ae1e223414cb807e40cd57e667b718e42aaf9306db9102fe28912b7b4e";
	private static final String BITCOIN_TEST4_GENESIS_OUTPUT_SCRIPT = "000000000000000000000000000000000000000000000000000000000000000000";
	private static final String LITECOIN_GENESIS_COINBASE_SCRIPT = "04ffff001d0104404e592054696d65732030352f4f63742f32303131205374657665204a6f62732c204170706c65e280997320566973696f6e6172792c2044696573206174203536";
	private static final String LITECOIN_GENESIS_MERKLE_ROOT = "97ddfbbae6be97fd6cdf3e7ca13232a3afff2353e29badfab7f73011edd4ced9";
	private static final String LITECOIN_GENESIS_OUTPUT_SCRIPT = "040184710fa689ad5023690c80f3a49c8f13f8d45b8c857fbcbc8bc4a8e4d3eb4b10f4d4604fa08dce601aaf0f470216fe1b51850b4acf21b179c45070ac7b03a9";
	private static final String NAMECOIN_GENESIS_COINBASE_SCRIPT = "04ff7f001c020a024a2e2e2e2063686f6f7365207768617420636f6d6573206e6578742e20204c69766573206f6620796f7572206f776e2c206f7220612072657475726e20746f20636861696e732e202d2d2056";
	private static final String NAMECOIN_GENESIS_MERKLE_ROOT = "41c62dbd9068c89a449525e3cd5ac61b20ece28c3c38b3f35b2161f0e6d3cb0d";
	private static final String NAMECOIN_GENESIS_OUTPUT_SCRIPT = "04b620369050cd899ffbbc4e8ee51e8c4534a855bb463439d63d235d4779685d8b6f4870a238cf365ac94fa13ef9a2a22cd99d0d5ee86dcabcafce36c7acf43ce5";
	private static final String FIRO_GENESIS_MERKLE_ROOT = "365d2aa75d061370c9aefdabac3985716b1e3b4bb7c4af4ed54f25e5aaa42783";
	private static final String KOMODO_GENESIS_MERKLE_ROOT = "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b";
	private static final String ZCASH_GENESIS_COINBASE_SCRIPT = "04ffff071f0104455a6361736830623963346565663862376363343137656535303031653335303039383462366665613335363833613763616331343161303433633432303634383335643334";
	private static final String ZCASH_GENESIS_MERKLE_ROOT = "c4eaa58879081de3c24a7b117ed2b28300e7ec4c4c1dff1d3f1268b7857a4ddb";
	private static final String ZCASH_GENESIS_OUTPUT_SCRIPT = "04678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5f";
	private static final String BITCOIN_CASH_MAINNET_CHAIN_ID = "bip122:000000000000000000651ef99cb9fcbe";
	private static final String VERUS_MAINNET_CHAIN_ID = "bip122:ac2cd7d37177140ea4991cf630c0b9c7";
	private static final String LBRY_CREDITS_GENESIS_MERKLE_ROOT = "b8211c82c3d15bcd78bba57005b86fed515149a53a425eb592c07af99fe559cc";
	private static final String PEERCOIN_GENESIS_MERKLE_ROOT = "3c2d8f85fab4d17aac558cc648a1a58acff0de6deb890c29985690052c5993c2";
	private static final String VERGE_GENESIS_MERKLE_ROOT = "1c83275d9151711eec3aec37d829837cc3c2730b2bdfd00ec5e8e5dce675fd00";
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
			.dnsSeeds("seed.bitcoin.sipa.be", "dnsseed.bluematt.me", "dnsseed.bitcoin.dashjr-list-of-p2p-nodes.us",
					"seed.bitcoin.jonasschnelli.ch", "seed.btc.petertodd.net", "seed.bitcoin.sprovoost.nl",
					"dnsseed.emzy.de", "seed.bitcoin.wiz.biz", "seed.mainnet.achownodes.xyz")
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
					"seed.testnet.bitcoin.sprovoost.nl", "testnet-seed.bluematt.me",
					"seed.testnet.achownodes.xyz")
			.build();
	private static final NetworkParameters BITCOIN_TEST4_NET_PARAMS = bitcoinParams("org.bitcoin.test4", NetworkParameters.PAYMENT_PROTOCOL_ID_TESTNET, "tb")
			.genesis(1714777860L, 393743547L, 0x1d00ffffL, "00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043")
			.genesisHeader(1L, BITCOIN_TEST4_GENESIS_MERKLE_ROOT)
			.genesisTransaction(BITCOIN_TEST4_GENESIS_COINBASE_SCRIPT, Coin.COIN.multiply(50L), BITCOIN_TEST4_GENESIS_OUTPUT_SCRIPT)
			.port(48333)
			.packetMagic(0x1c163f28L)
			.addressHeaders(111, 196, 239)
			.segwitAddressHrp("tb")
			.coinbaseAndSubsidy(100, 210_000)
			.bip32Headers(0x043587cf, 0x04358394)
			.bip32SegwitHeaders(0x045f1cf6, 0x045f18bc)
			.majorityWindow(51, 75, 100)
			.dnsSeeds("seed.testnet4.bitcoin.sprovoost.nl", "seed.testnet4.wiz.biz")
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
	private static final NetworkParameters BITCOIN_CASH_MAIN_NET_PARAMS = bitcoinCashParams("org.bitcoincash.production", NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET)
			.genesis(1231006505L, 2083236893L, 0x1d00ffffL, "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f")
			.genesisHeader(1L, BITCOIN_GENESIS_MERKLE_ROOT)
			.genesisTransaction(BITCOIN_GENESIS_COINBASE_SCRIPT, Coin.COIN.multiply(50L), BITCOIN_GENESIS_OUTPUT_SCRIPT)
			.port(8333)
			.packetMagic(0xe3e1f3e8L)
			.addressHeaders(0, 5, 128)
			.cashAddressPrefix("bitcoincash")
			.coinbaseAndSubsidy(100, 210_000)
			.bip32Headers(0x0488B21E, 0x0488ADE4)
			.majorityWindow(750, 950, 1000)
			.dnsSeeds("seed.flowee.cash", "seed-bch.bitcoinforks.org", "btccash-seeder.bitcoinunlimited.info",
					"seed.bchd.cash", "seed.bch.loping.net", "dnsseed.electroncash.de", "bchseed.c3-soft.com",
					"bch.bitjson.com")
			.build();
	private static final NetworkParameters BITCOIN_CASH_TEST4_NET_PARAMS = bitcoinCashParams("org.bitcoincash.test4", NetworkParameters.PAYMENT_PROTOCOL_ID_TESTNET)
			.genesis(1597811185L, 114152193L, 0x1d00ffffL, "000000001dd410c49a788668ce26751718cc797474d3152a5fc073dd44fd9f7b")
			.genesisHeader(1L, BITCOIN_GENESIS_MERKLE_ROOT)
			.genesisTransaction(BITCOIN_GENESIS_COINBASE_SCRIPT, Coin.COIN.multiply(50L), BITCOIN_GENESIS_OUTPUT_SCRIPT)
			.port(28333)
			.packetMagic(0xe2b7daafL)
			.addressHeaders(111, 196, 239)
			.cashAddressPrefix("bchtest")
			.coinbaseAndSubsidy(100, 210_000)
			.bip32Headers(0x043587cf, 0x04358394)
			.majorityWindow(750, 950, 1000)
			.dnsSeeds("testnet4-seed-bch.toom.im", "seed.tbch4.loping.net", "testnet4-seed.flowee.cash",
					"testnet4.bitjson.com")
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
	private static final NetworkParameters NAMECOIN_MAIN_NET_PARAMS = namecoinParams("org.namecoin.production", NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET, "nc")
			.genesis(1303000001L, 0xa21ea192L, 0x1c007fffL, "000000000062b72c5e2ceb45fbc8587e807c155b0da735e6483dfba2f0a9c770")
			.genesisHeader(1L, NAMECOIN_GENESIS_MERKLE_ROOT)
			.genesisTransaction(NAMECOIN_GENESIS_COINBASE_SCRIPT, Coin.COIN.multiply(50L), NAMECOIN_GENESIS_OUTPUT_SCRIPT)
			.port(8334)
			.packetMagic(0xf9beb4feL)
			.addressHeaders(52, 13, 180)
			.segwitAddressHrp("nc")
			.coinbaseAndSubsidy(100, 210_000)
			.bip32Headers(0x0488B21E, 0x0488ADE4)
			.majorityWindow(1815, 1916, 2016)
			.dnsSeeds("nmc.seed.quisquis.de", "seed.nmc.markasoftware.com", "dnsseed1.nmc.dotbit.zone",
					"dnsseed2.nmc.dotbit.zone", "dnsseed.nmc.testls.space", "namecoin.seed.cypherstack.com")
			.build();
	private static final NetworkParameters PEERCOIN_MAIN_NET_PARAMS = peercoinParams("org.peercoin.production", NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET, "peercoin")
			.genesis(1345084287L, 2179302059L, 0x1d00ffffL, "0000000032fe677166d54963b62a4677d8957e87c508eaa4fd7eb1c880cd27e3")
			.genesisHeader(1L, PEERCOIN_GENESIS_MERKLE_ROOT)
			.port(9901)
			.packetMagic(0xe6e8e9e5L)
			.addressHeaders(55, 117, 183)
			.segwitAddressHrp("pc")
			.coinbaseAndSubsidy(500, 210_000)
			.bip32Headers(0x0488B21E, 0x0488ADE4)
			.majorityWindow(1815, 1900, 2016)
			.dnsSeeds("seed.peercoin.net", "seed2.peercoin.net", "seed.peercoin-library.org", "seed.ppcoin.info")
			.build();
	private static final NetworkParameters FIRO_MAIN_NET_PARAMS = firoParams("org.firo.production", NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET)
			.genesis(1414776286L, 142392L, 0x1e0ffff0L, "4381deb85b1b2c9843c222944b616d997516dcbd6a964e1eaf0def0830695233")
			.genesisHeader(2L, FIRO_GENESIS_MERKLE_ROOT)
			.port(8168)
			.packetMagic(0xe3d9fef1L)
			.addressHeaders(82, 7, 210)
			.coinbaseAndSubsidy(100, 840_000)
			.bip32Headers(0x0488B21E, 0x0488ADE4)
			.majorityWindow(750, 950, 1000)
			.dnsSeeds("amsterdam.firo.org", "australia.firo.org", "chicago.firo.org", "london.firo.org",
					"frankfurt.firo.org", "newjersey.firo.org", "sanfrancisco.firo.org", "tokyo.firo.org",
					"singapore.firo.org")
			.build();
	private static final NetworkParameters KOMODO_MAIN_NET_PARAMS = komodoParams("org.komodo.production", NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET)
			.genesis(1231006505L, 11L, 0x200f0f0fL, "027e3758c3a65b12aa1046462b486d0a63bfa1beae327897f56c5cfb7daaae71")
			.genesisHeader(1L, KOMODO_GENESIS_MERKLE_ROOT)
			.port(7770)
			.packetMagic(0xf9eee48dL)
			.addressHeaders(60, 85, 188)
			.coinbaseAndSubsidy(100, 840_000)
			.bip32Headers(0x0488B21E, 0x0488ADE4)
			.majorityWindow(3000, 3800, 4000)
			.dnsSeeds("kmd.komodoseeds.org", "seeds1.kmd.sh", "kmdseed.cipig.net",
					"kmdseeds.lordofthechains.com", "kmd.komodoseeds.com", "dynamic.komodoseeds.com")
			.build();
	private static final NetworkParameters VERUS_MAIN_NET_PARAMS = verusParams("org.verus.production", NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET)
			.genesis(1231006505L, 11L, 0x200f0f0fL, "027e3758c3a65b12aa1046462b486d0a63bfa1beae327897f56c5cfb7daaae71")
			.genesisHeader(1L, KOMODO_GENESIS_MERKLE_ROOT)
			.port(7770)
			.packetMagic(0xf9eee48dL)
			.addressHeaders(60, 85, 188)
			.coinbaseAndSubsidy(100, 840_000)
			.bip32Headers(0x0488B21E, 0x0488ADE4)
			.majorityWindow(750, 950, 4000)
			.dnsSeeds("seeds.verus.io")
			.build();
	private static final NetworkParameters ZCASH_MAIN_NET_PARAMS = zcashParams("org.zcash.production", NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET)
			.genesis(1477641360L, 4695L, 0x1f07ffffL, "00040fe8ec8471911baa1db1266ea15dd06b4a8a5c453883c000b031973dce08")
			.genesisHeader(4L, ZCASH_GENESIS_MERKLE_ROOT)
			.genesisTransaction(ZCASH_GENESIS_COINBASE_SCRIPT, Coin.ZERO, ZCASH_GENESIS_OUTPUT_SCRIPT)
			.port(8233)
			.packetMagic(0x24e92764L)
			.addressHeaders(0x1cb8, 0x1cbd, 128)
			.coinbaseAndSubsidy(100, 1_680_000)
			.bip32Headers(0x0488B21E, 0x0488ADE4)
			.majorityWindow(750, 950, 4000)
			.dnsSeeds("dnsseed.z.cash", "dnsseed.str4d.xyz", "mainnet.seeder.zfnd.org", "mainnet.is.yolo.money")
			.build();
	private static final NetworkParameters LBRY_CREDITS_MAIN_NET_PARAMS = lbryCreditsParams("main", NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET)
			.genesis(1446058291L, 1287L, 0x1f00ffffL, "9c89283ba0f3227f6c03b70216b9f665f0118d5e0fa729cedf4fb34d6a34f463")
			.genesisHeader(1L, LBRY_CREDITS_GENESIS_MERKLE_ROOT)
			.port(9246)
			.packetMagic(0xfae4aaf1L)
			.addressHeaders(0x55, 0x7a, 0x1c)
			.segwitAddressHrp("lbc")
			.coinbaseAndSubsidy(100, 32)
			.bip32Headers(0x0488B21E, 0x0488ADE4)
			.majorityWindow(750, 950, 1000)
			.dnsSeeds("dnsseed1.lbry.io", "dnsseed2.lbry.io", "dnsseed3.lbry.io")
			.build();
	private static final NetworkParameters VERGE_MAIN_NET_PARAMS = vergeParams("org.verge.production", NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET)
			.genesis(1412878964L, 1473191L, 0x1e0fffffL, "00000fc63692467faeb20cdb3b53200dc601d75bdfa1001463304cc790d77278")
			.genesisHeader(1L, VERGE_GENESIS_MERKLE_ROOT)
			.port(21102)
			.packetMagic(0xf7a77effL)
			.addressHeaders(30, 33, 158)
			.segwitAddressHrp("vg")
			.coinbaseAndSubsidy(120, 500_000)
			.bip32Headers(0x022D2533, 0x0221312B)
			.majorityWindow(100, 100, 200)
			.dnsSeeds("seed1.verge-blockchain.com", "seed2.verge-blockchain.com", "seed3.verge-blockchain.com",
					"xvg.nownodes.io")
			.build();
	public static final BitcoinyChainSpec BITCOIN = spec("BITCOIN", BITCOIN_SLIP44_COIN_TYPE, "Bitcoin", BITCOIN_CURRENCY_CODE, Coin.valueOf(5_000), 100_000)
			.mainnet(() -> BITCOIN_MAIN_NET_PARAMS, "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", 1_500L, "btc")
			.test3(() -> BITCOIN_TEST_NET_PARAMS, "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943", 1_500L, 1_000L, "tbtc")
			.test4(() -> BITCOIN_TEST4_NET_PARAMS, "00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043", 1_500L, 1_000L, "tbtc4")
			.regtest(() -> BITCOIN_REG_TEST_PARAMS, 1_500L, 1_000L)
			.defaultSpendFeePerByte(20L)
			.supportsForeignForeignTrades()
			.build();

	public static final BitcoinyChainSpec BITCOIN_CASH = spec("BITCOINCASH", BITCOIN_CASH_SLIP44_COIN_TYPE, "Bitcoin Cash", BITCOIN_CASH_CURRENCY_CODE,
			Coin.valueOf(1_000), 100_000)
			.mainnet(() -> BITCOIN_CASH_MAIN_NET_PARAMS, "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", BITCOIN_CASH_MAINNET_CHAIN_ID, 1_000L, "bch")
			.test4(() -> BITCOIN_CASH_TEST4_NET_PARAMS, "000000001dd410c49a788668ce26751718cc797474d3152a5fc073dd44fd9f7b", 1_000L, 1_000L, "tbch4")
			.addressNormalizer(BitcoinyChainSpecs::normalizeCashAddress)
			.spendableOutputScriptFilter(scriptPubKey -> !BitcoinyScript.isBitcoinCashTokenOutput(scriptPubKey))
			.transactionFormat(BitcoinyTransactionFormat.BITCOIN_CASH)
			.build();

	public static final BitcoinyChainSpec LITECOIN = spec("LITECOIN", LITECOIN_SLIP44_COIN_TYPE, "Litecoin", LITECOIN_CURRENCY_CODE, Coin.valueOf(10_000), 1_000_000)
			.mainnet(() -> LITECOIN_MAIN_NET_PARAMS, "12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2", 1_000L, "ltc")
			.test4(() -> LITECOIN_TEST_NET_PARAMS, "4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0", 1_000L, 1_000L, "tltc")
			.regtest(() -> LITECOIN_REG_TEST_PARAMS, 1_000L, 1_000L)
			.addressNormalizer(BitcoinyChainSpecs::normalizeLitecoinAddress)
			.supportsForeignForeignTrades()
			.build();

	public static final BitcoinyChainSpec DOGECOIN = spec("DOGECOIN", DOGECOIN_SLIP44_COIN_TYPE, "Dogecoin", DOGECOIN_CURRENCY_CODE, Coin.valueOf(1_000_000), 100_000_000L)
			.mainnet(() -> DOGECOIN_MAIN_NET_PARAMS, "1a91e3dace36e2be3bf030a65679fe821aa1d6ef92e7c9902eb318182c355691", 100_000L, "doge")
			.build();

	public static final BitcoinyChainSpec DIGIBYTE = spec("DIGIBYTE", DIGIBYTE_SLIP44_COIN_TYPE, "Digibyte", DIGIBYTE_CURRENCY_CODE, Coin.valueOf(100_000), 1_000_000)
			.mainnet(() -> DIGIBYTE_MAIN_NET_PARAMS, "7497ea1b465eb39f1c8f507bc877078fe016d6fcb6dfad3a64c98dcc6e1e8496", 10_000L, "dgb")
			.build();

	public static final BitcoinyChainSpec RAVENCOIN = spec("RAVENCOIN", RAVENCOIN_SLIP44_COIN_TYPE, "Ravencoin", RAVENCOIN_CURRENCY_CODE, Coin.valueOf(1_125_000), 1_000_000)
			.mainnet(() -> RAVENCOIN_MAIN_NET_PARAMS, "0000006b444bc2f2ffe627be9d9e7e7a0730000870ef6eb6da46c8eae389df90", 1_000_000L, "rvn")
			.build();

	public static final BitcoinyChainSpec DASH = spec("DASH", DASH_SLIP44_COIN_TYPE, "Dash", DASH_CURRENCY_CODE, Coin.valueOf(10_000), 1_000_000)
			.mainnet(() -> DASH_MAIN_NET_PARAMS, "00000ffd590b1485b3caadc19b22e6379c733355108f107a430458cdf3407ab6", 10_000L, "dash")
			.build();

	public static final BitcoinyChainSpec NAMECOIN = spec("NAMECOIN", NAMECOIN_SLIP44_COIN_TYPE, "Namecoin", NAMECOIN_CURRENCY_CODE, Coin.valueOf(10_000), 1_000_000)
			.mainnet(() -> NAMECOIN_MAIN_NET_PARAMS, "000000000062b72c5e2ceb45fbc8587e807c155b0da735e6483dfba2f0a9c770", 10_000L, "nmc")
			.spendableOutputScriptFilter(scriptPubKey -> !BitcoinyScript.isNamecoinNameOutputScript(scriptPubKey))
			.build();

	public static final BitcoinyChainSpec PEERCOIN = spec("PEERCOIN", PEERCOIN_SLIP44_COIN_TYPE, "Peercoin", PEERCOIN_CURRENCY_CODE,
			Coin.valueOf(10_000), 1_000_000, 6)
			.mainnet(() -> PEERCOIN_MAIN_NET_PARAMS, "0000000032fe677166d54963b62a4677d8957e87c508eaa4fd7eb1c880cd27e3", 10_000L, "ppc")
			.transactionFormat(BitcoinyTransactionFormat.PEERCOIN)
			.build();

	public static final BitcoinyChainSpec FIRO = spec("FIRO", FIRO_SLIP44_COIN_TYPE, "Firo", FIRO_CURRENCY_CODE, Coin.valueOf(10_000), 1_000_000)
			.mainnet(() -> FIRO_MAIN_NET_PARAMS, "4381deb85b1b2c9843c222944b616d997516dcbd6a964e1eaf0def0830695233", 10_000L, "firo")
			.build();

	public static final BitcoinyChainSpec KOMODO = spec("KOMODO", KOMODO_SLIP44_COIN_TYPE, "Komodo", KOMODO_CURRENCY_CODE, Coin.valueOf(10_000), 1_000_000)
			.mainnet(() -> KOMODO_MAIN_NET_PARAMS, "027e3758c3a65b12aa1046462b486d0a63bfa1beae327897f56c5cfb7daaae71", 10_000L, "kmd")
			.transactionFormat(BitcoinyTransactionFormat.SAPLING_TRANSPARENT)
			.build();

	public static final BitcoinyChainSpec VERUS = spec("VERUSCOIN", VERUS_SLIP44_COIN_TYPE, "VerusCoin", VERUS_CURRENCY_CODE, Coin.valueOf(10_000), 1_000_000)
			.mainnet(() -> VERUS_MAIN_NET_PARAMS, "027e3758c3a65b12aa1046462b486d0a63bfa1beae327897f56c5cfb7daaae71", VERUS_MAINNET_CHAIN_ID, 10_000L, "vrsc")
			.transactionFormat(BitcoinyTransactionFormat.SAPLING_TRANSPARENT)
			.build();

	public static final BitcoinyChainSpec ZCASH = spec("ZCASH", ZCASH_SLIP44_COIN_TYPE, "Zcash", ZCASH_CURRENCY_CODE, Coin.valueOf(10_000), 1_000_000)
			.mainnet(() -> ZCASH_MAIN_NET_PARAMS, "00040fe8ec8471911baa1db1266ea15dd06b4a8a5c453883c000b031973dce08", 10_000L, "zec")
			.transactionFormat(BitcoinyTransactionFormat.ZCASH_TRANSPARENT)
			.build();

	public static final BitcoinyChainSpec LBRY_CREDITS = spec("LBRYCREDITS", LBRY_CREDITS_SLIP44_COIN_TYPE, "LBRY Credits", LBRY_CREDITS_CURRENCY_CODE, Coin.valueOf(10_000), 1_000_000)
			.mainnet(() -> LBRY_CREDITS_MAIN_NET_PARAMS, "9c89283ba0f3227f6c03b70216b9f665f0118d5e0fa729cedf4fb34d6a34f463", 10_000L, "lbc")
			.spendableOutputScriptFilter(scriptPubKey -> !BitcoinyScript.isLbryClaimOutputScript(scriptPubKey))
			.build();

	public static final BitcoinyChainSpec VERGE = spec("VERGE", VERGE_SLIP44_COIN_TYPE, "Verge", VERGE_CURRENCY_CODE,
			Coin.valueOf(10_000), 1_000_000, 6)
			.mainnet(() -> VERGE_MAIN_NET_PARAMS, "00000fc63692467faeb20cdb3b53200dc601d75bdfa1001463304cc790d77278", 10_000L, "xvg")
			.transactionFormat(BitcoinyTransactionFormat.TIMESTAMPED_LEGACY)
			.build();

	private static final List<BitcoinyChainSpec> ALL = List.of(BITCOIN, BITCOIN_CASH, LITECOIN, DOGECOIN, DIGIBYTE, RAVENCOIN, DASH, PEERCOIN, NAMECOIN, FIRO, KOMODO, VERUS, ZCASH, LBRY_CREDITS, VERGE);

	private static final List<String> CURRENCY_CODES = ALL.stream()
			.map(BitcoinyChainSpec::getCurrencyCode)
			.collect(Collectors.toUnmodifiableList());

	private static final Map<String, BitcoinyChainSpec> BY_CURRENCY_CODE = ALL.stream()
			.collect(Collectors.toUnmodifiableMap(BitcoinyChainSpec::getCurrencyCode, spec -> spec));

	private BitcoinyChainSpecs() {
	}

	private static SpecBuilder spec(String canonicalName, int slip44CoinType, String displayName, String currencyCode, Coin defaultFeePerKb, long minimumOrderAmount) {
		return spec(canonicalName, slip44CoinType, displayName, currencyCode, defaultFeePerKb, minimumOrderAmount, 8);
	}

	private static SpecBuilder spec(String canonicalName, int slip44CoinType, String displayName, String currencyCode, Coin defaultFeePerKb,
			long minimumOrderAmount, int decimalPlaces) {
		return new SpecBuilder(canonicalName, slip44CoinType, displayName, currencyCode, defaultFeePerKb, minimumOrderAmount, decimalPlaces);
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

	private static StaticBitcoinyParams.Builder bitcoinCashParams(String id, String paymentProtocolId) {
		return StaticBitcoinyParams.builder(id, paymentProtocolId, "bitcoincash")
				.maxTarget(0x1d00ffffL)
				.maxMoney(Coin.COIN.multiply(21_000_000L))
				.minNonDustOutput(Coin.valueOf(546L))
				.monetaryFormat(MonetaryFormat.BTC.noCode()
						.code(0, BITCOIN_CASH_CURRENCY_CODE)
						.code(3, "mBCH")
						.code(7, "sat"))
				.difficultyValidationFailure("Bitcoin Cash difficulty verification is not implemented for Electrum-backed parameters");
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

	private static StaticBitcoinyParams.Builder namecoinParams(String id, String paymentProtocolId, String segwitAddressHrp) {
		return StaticBitcoinyParams.builder(id, paymentProtocolId, "namecoin")
				.maxTarget(0x1d00ffffL)
				.targetTimespan(NetworkParameters.TARGET_TIMESPAN)
				.interval(NetworkParameters.INTERVAL)
				.segwitAddressHrp(segwitAddressHrp)
				.maxMoney(Coin.COIN.multiply(21_000_000L))
				.minNonDustOutput(Coin.valueOf(546L))
				.monetaryFormat(MonetaryFormat.BTC.noCode()
						.code(0, "NMC")
						.code(3, "mNMC")
						.code(7, "Nameoshi"))
				.difficultyValidationFailure("Namecoin difficulty verification is not implemented for Electrum-backed parameters");
	}

	private static StaticBitcoinyParams.Builder peercoinParams(String id, String paymentProtocolId, String uriScheme) {
		return StaticBitcoinyParams.builder(id, paymentProtocolId, uriScheme)
				.maxTarget(0x1d00ffffL)
				.targetTimespan(7 * 24 * 60 * 60)
				.interval(1008)
				.maxMoney(Coin.valueOf(21_000_000L * 1_000_000L))
				.minNonDustOutput(Coin.valueOf(10_000L))
				.monetaryFormat(new MonetaryFormat().noCode()
						.code(2, PEERCOIN_CURRENCY_CODE)
						.shift(2)
						.minDecimals(2)
						.repeatOptionalDecimals(1, 4))
				.difficultyValidationFailure("Peercoin difficulty verification is not implemented for Electrum-backed parameters");
	}

	private static StaticBitcoinyParams.Builder firoParams(String id, String paymentProtocolId) {
		return StaticBitcoinyParams.builder(id, paymentProtocolId, "firo")
				.maxTarget("00ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
				.targetTimespan(60 * 60)
				.interval(6)
				.hasMaxMoney(false)
				.minNonDustOutput(Coin.valueOf(1000L))
				.monetaryFormat(MonetaryFormat.BTC.noCode()
						.code(0, FIRO_CURRENCY_CODE)
						.code(3, "mFIRO")
						.code(7, "Firoshi"))
				.difficultyValidationFailure("Firo difficulty verification is not implemented for Electrum-backed parameters");
	}

	private static StaticBitcoinyParams.Builder komodoParams(String id, String paymentProtocolId) {
		return StaticBitcoinyParams.builder(id, paymentProtocolId, "komodo")
				.maxTarget("0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f")
				.targetTimespan(17 * 60)
				.interval(17)
				.hasMaxMoney(false)
				.minNonDustOutput(Coin.valueOf(1000L))
				.monetaryFormat(MonetaryFormat.BTC.noCode()
						.code(0, KOMODO_CURRENCY_CODE)
						.code(3, "mKMD")
						.code(7, "Komodoshi"))
				.difficultyValidationFailure("Komodo difficulty verification is not implemented for Electrum-backed parameters");
	}

	private static StaticBitcoinyParams.Builder verusParams(String id, String paymentProtocolId) {
		return StaticBitcoinyParams.builder(id, paymentProtocolId, "verus")
				.maxTarget("0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f")
				.targetTimespan(17 * 60)
				.interval(17)
				.hasMaxMoney(false)
				.minNonDustOutput(Coin.valueOf(1000L))
				.monetaryFormat(MonetaryFormat.BTC.noCode()
						.code(0, VERUS_CURRENCY_CODE)
						.code(3, "mVRSC")
						.code(7, "Verusoshi"))
				.difficultyValidationFailure("VerusCoin difficulty verification is not implemented for Electrum-backed parameters");
	}

	private static StaticBitcoinyParams.Builder zcashParams(String id, String paymentProtocolId) {
		return StaticBitcoinyParams.builder(id, paymentProtocolId, "zcash")
				.maxTarget("0007ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
				.targetTimespan(17 * 75)
				.interval(17)
				.maxMoney(Coin.COIN.multiply(21_000_000L))
				.minNonDustOutput(Coin.valueOf(1000L))
				.monetaryFormat(MonetaryFormat.BTC.noCode()
						.code(0, ZCASH_CURRENCY_CODE)
						.code(3, "mZEC")
						.code(7, "Zatoshi"))
				.difficultyValidationFailure("Zcash difficulty verification is not implemented for Electrum-backed parameters");
	}

	private static StaticBitcoinyParams.Builder lbryCreditsParams(String id, String paymentProtocolId) {
		return StaticBitcoinyParams.builder(id, paymentProtocolId, "lbry")
				.maxTarget("0000ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
				.targetTimespan(150)
				.interval(1)
				.hasMaxMoney(false)
				.minNonDustOutput(Coin.valueOf(546L))
				.monetaryFormat(MonetaryFormat.BTC.noCode()
						.code(0, LBRY_CREDITS_CURRENCY_CODE)
						.code(3, "mLBC")
						.code(7, "Dewey"))
				.difficultyValidationFailure("LBRY Credits difficulty verification is not implemented for Electrum-backed parameters");
	}

	private static StaticBitcoinyParams.Builder vergeParams(String id, String paymentProtocolId) {
		return StaticBitcoinyParams.builder(id, paymentProtocolId, "verge")
				.maxTarget("00000fffff000000000000000000000000000000000000000000000000000000")
				.targetTimespan(30)
				.interval(1)
				.maxMoney(Coin.valueOf(16_555_000_000L * 1_000_000L))
				.minNonDustOutput(Coin.valueOf(10_000L))
				.monetaryFormat(new MonetaryFormat().noCode()
						.code(2, VERGE_CURRENCY_CODE)
						.shift(2)
						.minDecimals(2)
						.repeatOptionalDecimals(1, 4))
				.difficultyValidationFailure("Verge difficulty verification is not implemented for Electrum-backed parameters");
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

	private static String normalizeCashAddress(String address, NetworkParameters targetParams) {
		try {
			return BitcoinyAddress.fromString(targetParams, address).toString();
		} catch (Exception e) {
			return null;
		}
	}

	private static final class SpecBuilder {
		private final String canonicalName;
		private final int slip44CoinType;
		private final BitcoinyChainConfig config;
		private final List<BitcoinyNetwork> networks = new ArrayList<>();
		private final List<BitcoinyChainSpec.ElectrumServerRefreshConfig> refreshConfigs = new ArrayList<>();
		private Long defaultSpendFeePerByte;
		private BitcoinyChainSpec.AddressNormalizer addressNormalizer;
		private BitcoinyChainSpec.SpendableOutputScriptFilter spendableOutputScriptFilter;
		private BitcoinyTransactionFormat transactionFormat = BitcoinyTransactionFormat.LEGACY;
		private boolean supportsForeignForeignTrades;

		private SpecBuilder(String canonicalName, int slip44CoinType, String displayName, String currencyCode, Coin defaultFeePerKb,
				long minimumOrderAmount, int decimalPlaces) {
			this.canonicalName = canonicalName;
			this.slip44CoinType = slip44CoinType;
			this.config = new BitcoinyChainConfig(displayName, currencyCode, defaultFeePerKb, minimumOrderAmount, decimalPlaces,
					BitcoinyChainConfig.defaultElectrumXPorts());
		}

		private SpecBuilder mainnet(Supplier<NetworkParameters> paramsSupplier, String genesisHash, long feeRequired, String chain1209k) {
			this.networks.add(StaticBitcoinyNetwork.mainnet(paramsSupplier, NO_SERVERS, genesisHash, feeRequired));
			this.addRefresh(MAIN, chain1209k);
			return this;
		}

		private SpecBuilder mainnet(Supplier<NetworkParameters> paramsSupplier, String genesisHash, String chainId, long feeRequired, String chain1209k) {
			this.networks.add(StaticBitcoinyNetwork.mainnet(paramsSupplier, NO_SERVERS, genesisHash, chainId, feeRequired));
			this.addRefresh(MAIN, chain1209k);
			return this;
		}

		private SpecBuilder test3(Supplier<NetworkParameters> paramsSupplier, String genesisHash, long feeRequired, long p2shFee, String chain1209k) {
			this.networks.add(StaticBitcoinyNetwork.nonMainnet(TEST3, paramsSupplier, NO_SERVERS, genesisHash, feeRequired, p2shFee));
			this.addRefresh(TEST3, chain1209k);
			return this;
		}

		private SpecBuilder test3(Supplier<NetworkParameters> paramsSupplier, String genesisHash, String chainId, long feeRequired, long p2shFee, String chain1209k) {
			this.networks.add(StaticBitcoinyNetwork.nonMainnet(TEST3, paramsSupplier, NO_SERVERS, genesisHash, chainId, feeRequired, p2shFee));
			this.addRefresh(TEST3, chain1209k);
			return this;
		}

		private SpecBuilder test4(Supplier<NetworkParameters> paramsSupplier, String genesisHash, long feeRequired, long p2shFee, String chain1209k) {
			this.networks.add(StaticBitcoinyNetwork.nonMainnet(TEST4, paramsSupplier, NO_SERVERS, genesisHash, feeRequired, p2shFee));
			this.addRefresh(TEST4, chain1209k);
			return this;
		}

		private SpecBuilder test4(Supplier<NetworkParameters> paramsSupplier, String genesisHash, String chainId, long feeRequired, long p2shFee, String chain1209k) {
			this.networks.add(StaticBitcoinyNetwork.nonMainnet(TEST4, paramsSupplier, NO_SERVERS, genesisHash, chainId, feeRequired, p2shFee));
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

		private SpecBuilder spendableOutputScriptFilter(BitcoinyChainSpec.SpendableOutputScriptFilter spendableOutputScriptFilter) {
			this.spendableOutputScriptFilter = spendableOutputScriptFilter;
			return this;
		}

		private SpecBuilder transactionFormat(BitcoinyTransactionFormat transactionFormat) {
			this.transactionFormat = transactionFormat;
			return this;
		}

		private SpecBuilder supportsForeignForeignTrades() {
			this.supportsForeignForeignTrades = true;
			return this;
		}

		private BitcoinyChainSpec build() {
			return new BitcoinyChainSpec(this.canonicalName, this.slip44CoinType, this.config, this.networks, this.refreshConfigs,
					this.defaultSpendFeePerByte, this.addressNormalizer, this.spendableOutputScriptFilter, this.transactionFormat,
					this.supportsForeignForeignTrades);
		}

		private void addRefresh(String networkName, String chain1209k) {
			if (chain1209k != null)
				this.refreshConfigs.add(refresh(networkName, chain1209k));
		}
	}
}
