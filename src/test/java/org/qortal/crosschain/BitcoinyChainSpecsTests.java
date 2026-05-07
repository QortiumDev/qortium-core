package org.qortal.crosschain;

import org.junit.BeforeClass;
import org.junit.Test;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.qortal.repository.DataException;
import org.qortal.settings.Settings;
import org.qortal.test.common.Common;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BitcoinyChainSpecsTests {

	@BeforeClass
	public static void beforeClass() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testRegisteredBitcoinyChainManifest() {
		for (ChainManifest expected : List.of(
				new ChainManifest("BITCOIN", "BTC", BitcoinyChainSpecs.BITCOIN_SLIP44_COIN_TYPE, "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", Set.of(BitcoinyChainSpecs.MAIN, BitcoinyChainSpecs.TEST3, BitcoinyChainSpecs.REGTEST), 0, 5, 128, 0x0488B21E, 0x0488ADE4),
				new ChainManifest("LITECOIN", "LTC", BitcoinyChainSpecs.LITECOIN_SLIP44_COIN_TYPE, "12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2", Set.of(BitcoinyChainSpecs.MAIN, BitcoinyChainSpecs.TEST4, BitcoinyChainSpecs.REGTEST), 48, 5, 176, 0x0488B21E, 0x0488ADE4),
				new ChainManifest("DOGECOIN", "DOGE", BitcoinyChainSpecs.DOGECOIN_SLIP44_COIN_TYPE, "1a91e3dace36e2be3bf030a65679fe821aa1d6ef92e7c9902eb318182c355691", Set.of(BitcoinyChainSpecs.MAIN), 30, 22, 158, 0x02facafd, 0x02fac398),
				new ChainManifest("DIGIBYTE", "DGB", BitcoinyChainSpecs.DIGIBYTE_SLIP44_COIN_TYPE, "7497ea1b465eb39f1c8f507bc877078fe016d6fcb6dfad3a64c98dcc6e1e8496", Set.of(BitcoinyChainSpecs.MAIN), 30, 63, 128, 0x0488B21E, 0x0488ADE4),
				new ChainManifest("RAVENCOIN", "RVN", BitcoinyChainSpecs.RAVENCOIN_SLIP44_COIN_TYPE, "0000006b444bc2f2ffe627be9d9e7e7a0730000870ef6eb6da46c8eae389df90", Set.of(BitcoinyChainSpecs.MAIN), 60, 122, 128, 0x0488B21E, 0x0488ADE4),
				new ChainManifest("DASH", "DASH", BitcoinyChainSpecs.DASH_SLIP44_COIN_TYPE, "00000ffd590b1485b3caadc19b22e6379c733355108f107a430458cdf3407ab6", Set.of(BitcoinyChainSpecs.MAIN), 76, 16, 204, 0x0488B21E, 0x0488ADE4),
				new ChainManifest("NAMECOIN", "NMC", BitcoinyChainSpecs.NAMECOIN_SLIP44_COIN_TYPE, "000000000062b72c5e2ceb45fbc8587e807c155b0da735e6483dfba2f0a9c770", Set.of(BitcoinyChainSpecs.MAIN), 52, 13, 180, 0x0488B21E, 0x0488ADE4),
				new ChainManifest("FIRO", "FIRO", BitcoinyChainSpecs.FIRO_SLIP44_COIN_TYPE, "4381deb85b1b2c9843c222944b616d997516dcbd6a964e1eaf0def0830695233", Set.of(BitcoinyChainSpecs.MAIN), 82, 7, 210, 0x0488B21E, 0x0488ADE4))) {
			BitcoinyChainSpec spec = BitcoinyChainSpecs.fromCurrencyCode(expected.currencyCode);
			assertNotNull(expected.currencyCode, spec);
			assertEquals(expected.canonicalName, spec.getCanonicalName());
			assertEquals(expected.slip44CoinType, spec.getSlip44CoinType());

			ForeignBlockchainRegistry.Entry entry = ForeignBlockchainRegistry.fromStringRequired(expected.canonicalName);
			assertSame(spec, entry.getBitcoinySpec());
			assertEquals(Integer.valueOf(expected.slip44CoinType), entry.getSlip44CoinType());

			Set<String> supportedNetworkNames = spec.getNetworks().stream()
					.map(BitcoinyNetwork::name)
					.collect(Collectors.toSet());
			assertEquals(expected.supportedNetworkNames, supportedNetworkNames);

			BitcoinyNetwork mainnet = spec.getNetwork(BitcoinyChainSpecs.MAIN);
			assertNotNull(mainnet);
			assertEquals(expected.mainnetGenesisHash, mainnet.getGenesisHash());
			assertEquals(Bip122ChainId.fromBlockHash(expected.mainnetGenesisHash), mainnet.getChainId());
			assertSame(entry, ForeignBlockchainRegistry.fromBitcoinyChainId(mainnet.getChainId()));
			assertSame(entry, ForeignBlockchainRegistry.fromBitcoinyChainIdReference(Bip122ChainId.toReferenceBytes(mainnet.getChainId())));

			NetworkParameters mainnetParams = mainnet.getParams();
			assertEquals(expected.addressHeader, mainnetParams.getAddressHeader());
			assertEquals(expected.p2shHeader, mainnetParams.getP2SHHeader());
			assertEquals(expected.dumpedPrivateKeyHeader, mainnetParams.getDumpedPrivateKeyHeader());
			assertEquals(expected.bip32PublicHeader, mainnetParams.getBip32HeaderP2PKHpub());
			assertEquals(expected.bip32PrivateHeader, mainnetParams.getBip32HeaderP2PKHpriv());
		}

		ForeignBlockchainRegistry.Entry pirateChain = ForeignBlockchainRegistry.fromStringRequired(ForeignBlockchainRegistry.PIRATECHAIN_NAME);
		assertNull(pirateChain.getSlip44CoinType());
		assertNull(pirateChain.getActiveChainId());
	}

	@Test
	public void testSpecRegistryMatchesRegisteredBitcoinyBlockchains() {
		Set<String> specCanonicalNames = BitcoinyChainSpecs.all().stream()
				.map(BitcoinyChainSpec::getCanonicalName)
				.collect(Collectors.toSet());

		Set<String> specCurrencyCodes = BitcoinyChainSpecs.all().stream()
				.map(BitcoinyChainSpec::getCurrencyCode)
				.collect(Collectors.toSet());

		Set<String> registeredNames = ForeignBlockchainRegistry.bitcoinyEntries().stream()
				.map(ForeignBlockchainRegistry.Entry::name)
				.collect(Collectors.toSet());

		Set<String> registeredCurrencyCodes = ForeignBlockchainRegistry.bitcoinyEntries().stream()
				.map(ForeignBlockchainRegistry.Entry::getCurrencyCode)
				.collect(Collectors.toSet());

		Set<String> entryNames = ForeignBlockchainRegistry.entryNames().stream()
				.collect(Collectors.toSet());

		assertEquals(specCanonicalNames, registeredNames);
		assertEquals(specCurrencyCodes, registeredCurrencyCodes);
		assertTrue(entryNames.containsAll(specCanonicalNames));
		assertTrue(entryNames.contains(ForeignBlockchainRegistry.PIRATECHAIN_NAME));

		for (ForeignBlockchainRegistry.Entry blockchain : ForeignBlockchainRegistry.bitcoinyEntries()) {
			BitcoinyChainSpec spec = BitcoinyChainSpecs.fromCurrencyCode(blockchain.getCurrencyCode());
			assertNotNull(spec);
			assertEquals(blockchain.name(), spec.getCanonicalName());
			assertSame(spec, blockchain.getBitcoinySpec());
			assertEquals(blockchain.getSlip44CoinType(), Integer.valueOf(spec.getSlip44CoinType()));

			for (BitcoinyNetwork network : spec.getNetworks())
				assertSame(blockchain, ForeignBlockchainRegistry.fromBitcoinyChainId(network.getChainId()));
		}
	}

	@Test
	public void testForeignBlockchainRegistryResolvesNamesCurrencyCodesAndBitcoinyChainIds() {
		for (ForeignBlockchainRegistry.Entry blockchain : ForeignBlockchainRegistry.entries()) {
			ForeignBlockchainRegistry.Entry byName = ForeignBlockchainRegistry.fromString(blockchain.name().toLowerCase());
			assertNotNull(byName);
			assertEquals(blockchain.name(), byName.name());
			assertSame(blockchain, byName);

			ForeignBlockchainRegistry.Entry byCurrencyCode = ForeignBlockchainRegistry.fromString(blockchain.getCurrencyCode().toLowerCase());
			assertSame(byName, byCurrencyCode);
			assertSame(byName, ForeignBlockchainRegistry.fromStringRequired(blockchain.getCurrencyCode()));

			if (blockchain.isBitcoiny()) {
				for (BitcoinyNetwork network : blockchain.getBitcoinySpec().getNetworks())
					assertSame(byName, ForeignBlockchainRegistry.fromBitcoinyChainId(network.getChainId()));
			} else {
				assertNull(blockchain.getActiveChainId());
			}
		}

		assertNull(ForeignBlockchainRegistry.fromString("unknown"));
		assertNull(ForeignBlockchainRegistry.fromString("   "));
		assertNull(ForeignBlockchainRegistry.fromBitcoinyChainId("unknown"));
		assertNull(ForeignBlockchainRegistry.fromBitcoinyChainIdReference(new byte[1]));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testForeignBlockchainRegistryRejectsUnknownRequiredName() {
		ForeignBlockchainRegistry.fromStringRequired("unknown");
	}

	@Test
	public void testSpecsDeclareSupportedNetworks() {
		for (BitcoinyChainSpec spec : BitcoinyChainSpecs.all()) {
			assertNotNull(spec.getNetwork(BitcoinyChainSpecs.MAIN));
			assertEquals(BitcoinyChainSpecs.MAIN, spec.getNetwork(BitcoinyChainSpecs.MAIN).name());
		}

		assertNotNull(BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.TEST3));
		assertNotNull(BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.REGTEST));
		assertNotNull(BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.TEST4));
		assertNotNull(BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.REGTEST));
	}

	@Test
	public void testRefreshConfigsReferenceKnownNetworks() {
		for (BitcoinyChainSpec spec : BitcoinyChainSpecs.all()) {
			for (BitcoinyChainSpec.ElectrumServerRefreshConfig refreshConfig : spec.getElectrumServerRefreshConfigs()) {
				assertNotNull(spec.getNetwork(refreshConfig.getNetworkName()));
				assertNotNull(refreshConfig.getChain1209k());
			}
		}
	}

	@Test
	public void testUnsupportedNonMainnetNetworksAreNotAdvertised() {
		assertNull(BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.TEST4));
		assertNull(BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.TEST3));

		for (BitcoinyChainSpec spec : new BitcoinyChainSpec[] {
				BitcoinyChainSpecs.DOGECOIN,
				BitcoinyChainSpecs.DIGIBYTE,
				BitcoinyChainSpecs.RAVENCOIN,
				BitcoinyChainSpecs.DASH,
				BitcoinyChainSpecs.NAMECOIN,
				BitcoinyChainSpecs.FIRO
		}) {
			assertNull(spec.getNetwork(BitcoinyChainSpecs.TEST3));
			assertNull(spec.getNetwork(BitcoinyChainSpecs.TEST4));
			assertNull(spec.getNetwork(BitcoinyChainSpecs.REGTEST));
		}
	}

	@Test(expected = RuntimeException.class)
	public void testSettingsRejectUnsupportedBitcoinyNetworks() {
		URL testSettingsUrl = BitcoinyChainSpecsTests.class.getClassLoader().getResource("test-settings-v2-unsupported-bitcoiny-network.json");
		assertNotNull("Test settings JSON file not found", testSettingsUrl);

		Settings.fileInstance(testSettingsUrl.getPath());
	}

	@Test
	public void testBitcoinUsesSharedStaticMainNetParamsWithLegacyParity() {
		NetworkParameters legacyParams = MainNetParams.get();
		NetworkParameters bitcoinMainNetParams = BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();

		assertTrue(bitcoinMainNetParams instanceof StaticBitcoinyParams);
		assertNetworkParamParity(legacyParams, bitcoinMainNetParams);
		assertEquals("000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", bitcoinMainNetParams.getGenesisBlock().getHashAsString());
	}

	@Test
	public void testBitcoinUsesSharedStaticTestNet3ParamsWithLegacyParity() {
		BitcoinyNetwork bitcoinTest3 = BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.TEST3);
		NetworkParameters legacyParams = TestNet3Params.get();

		assertTrue(bitcoinTest3.getParams() instanceof StaticBitcoinyParams);
		assertEquals(BitcoinyChainSpecs.TEST3, bitcoinTest3.name());
		assertNetworkParamParity(legacyParams, bitcoinTest3.getParams());
		assertEquals("000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943", bitcoinTest3.getGenesisHash());
	}

	@Test
	public void testBitcoinUsesSharedStaticRegTestParamsWithLegacyParity() {
		NetworkParameters legacyParams = RegTestParams.get();
		NetworkParameters bitcoinRegTestParams = BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.REGTEST).getParams();
		Block genesisBlock = bitcoinRegTestParams.getGenesisBlock();

		assertTrue(bitcoinRegTestParams instanceof StaticBitcoinyParams);
		assertNetworkParamParity(legacyParams, bitcoinRegTestParams, true, false);
		assertEquals("0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206", genesisBlock.getHashAsString());
		assertEquals(Integer.MAX_VALUE, bitcoinRegTestParams.getInterval());
		assertEquals("bcrt", bitcoinRegTestParams.getSegwitAddressHrp());
	}

	@Test
	public void testLitecoinUsesSharedStaticMainNetParams() {
		NetworkParameters litecoinMainNetParams = BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		Block genesisBlock = litecoinMainNetParams.getGenesisBlock();

		assertTrue(litecoinMainNetParams instanceof StaticBitcoinyParams);
		assertEquals("org.litecoin.production", litecoinMainNetParams.getId());
		assertEquals("org.litecoin.production", litecoinMainNetParams.getPaymentProtocolId());
		assertEquals("litecoin", litecoinMainNetParams.getUriScheme());
		assertEquals("12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2", genesisBlock.getHashAsString());
		assertEquals("97ddfbbae6be97fd6cdf3e7ca13232a3afff2353e29badfab7f73011edd4ced9", genesisBlock.getMerkleRoot().toString());
		assertEquals(1317972665L, genesisBlock.getTimeSeconds());
		assertEquals(0x1e0ffff0L, genesisBlock.getDifficultyTarget());
		assertEquals(2084524493L, genesisBlock.getNonce());
		assertEquals(302_400, litecoinMainNetParams.getTargetTimespan());
		assertEquals(2016, litecoinMainNetParams.getInterval());
		assertEquals(9333, litecoinMainNetParams.getPort());
		assertEquals(0xfbc0b6dbL, litecoinMainNetParams.getPacketMagic() & 0xffffffffL);
		assertEquals(48, litecoinMainNetParams.getAddressHeader());
		assertEquals(5, litecoinMainNetParams.getP2SHHeader());
		assertEquals(176, litecoinMainNetParams.getDumpedPrivateKeyHeader());
		assertEquals("ltc", litecoinMainNetParams.getSegwitAddressHrp());
		assertEquals(100, litecoinMainNetParams.getSpendableCoinbaseDepth());
		assertEquals(840_000, litecoinMainNetParams.getSubsidyDecreaseBlockCount());
		assertEquals(0x0488B21E, litecoinMainNetParams.getBip32HeaderP2PKHpub());
		assertEquals(0x0488ADE4, litecoinMainNetParams.getBip32HeaderP2PKHpriv());
	}

	@Test
	public void testLitecoinUsesSharedStaticTestNet4Params() {
		BitcoinyNetwork litecoinTest4 = BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.TEST4);
		NetworkParameters litecoinTest4Params = litecoinTest4.getParams();
		Block genesisBlock = litecoinTest4Params.getGenesisBlock();

		assertTrue(litecoinTest4Params instanceof StaticBitcoinyParams);
		assertEquals(BitcoinyChainSpecs.TEST4, litecoinTest4.name());
		assertEquals("4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0", litecoinTest4.getGenesisHash());
		assertEquals(litecoinTest4.getGenesisHash(), genesisBlock.getHashAsString());
		assertEquals("97ddfbbae6be97fd6cdf3e7ca13232a3afff2353e29badfab7f73011edd4ced9", genesisBlock.getMerkleRoot().toString());
		assertEquals(1486949366L, genesisBlock.getTimeSeconds());
		assertEquals(0x1e0ffff0L, genesisBlock.getDifficultyTarget());
		assertEquals(293345L, genesisBlock.getNonce());
		assertEquals(111, litecoinTest4Params.getAddressHeader());
		assertEquals(196, litecoinTest4Params.getP2SHHeader());
		assertEquals(239, litecoinTest4Params.getDumpedPrivateKeyHeader());
		assertEquals("tltc", litecoinTest4Params.getSegwitAddressHrp());
		assertEquals(0x043587cf, litecoinTest4Params.getBip32HeaderP2PKHpub());
		assertEquals(0x04358394, litecoinTest4Params.getBip32HeaderP2PKHpriv());
	}

	@Test
	public void testLitecoinUsesSharedStaticRegTestParams() {
		NetworkParameters litecoinRegTestParams = BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.REGTEST).getParams();
		Block genesisBlock = litecoinRegTestParams.getGenesisBlock();

		assertTrue(litecoinRegTestParams instanceof StaticBitcoinyParams);
		assertEquals("regtest", litecoinRegTestParams.getId());
		assertEquals("regtest", litecoinRegTestParams.getPaymentProtocolId());
		assertEquals("530827f38f93b43ed12af0b3ad25a288dc02ed74d6d7857862df51fc56c416f9", genesisBlock.getHashAsString());
		assertEquals(1296688602L, genesisBlock.getTimeSeconds());
		assertEquals(0x207fffffL, genesisBlock.getDifficultyTarget());
		assertEquals(0L, genesisBlock.getNonce());
		assertEquals(Integer.MAX_VALUE, litecoinRegTestParams.getInterval());
		assertEquals(19444, litecoinRegTestParams.getPort());
		assertEquals("rltc", litecoinRegTestParams.getSegwitAddressHrp());
	}

	@Test
	public void testLitecoinCurrentP2shAddressNormalizerUsesSharedStaticParams() {
		NetworkParameters litecoinMainNetParams = BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();

		assertEquals("31nM1WuowNDzocNxPPW9NQWJEtwWpjfcLj",
				BitcoinyChainSpecs.LITECOIN.normalizeAddress("M7zVKQKmtV5Rc7erVGVVC3khZbXxsS5HEX", litecoinMainNetParams));
	}

	@Test
	public void testDigibyteUsesSharedStaticParams() {
		NetworkParameters digibyteMainNetParams = BitcoinyChainSpecs.DIGIBYTE.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		Block genesisBlock = digibyteMainNetParams.getGenesisBlock();

		assertTrue(digibyteMainNetParams instanceof StaticBitcoinyParams);
		assertEquals("main", digibyteMainNetParams.getId());
		assertEquals(NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET, digibyteMainNetParams.getPaymentProtocolId());
		assertEquals("digibyte", digibyteMainNetParams.getUriScheme());
		assertEquals("7497ea1b465eb39f1c8f507bc877078fe016d6fcb6dfad3a64c98dcc6e1e8496", genesisBlock.getHashAsString());
		assertEquals("72ddd9496b004221ed0557358846d9248ecd4c440ebd28ed901efc18757d0fad", genesisBlock.getMerkleRoot().toString());
		assertEquals(1389388394L, genesisBlock.getTimeSeconds());
		assertEquals(0x1e0ffff0L, genesisBlock.getDifficultyTarget());
		assertEquals(2447652L, genesisBlock.getNonce());
		assertEquals(8_640, digibyteMainNetParams.getTargetTimespan());
		assertEquals(2016, digibyteMainNetParams.getInterval());
		assertEquals(12024, digibyteMainNetParams.getPort());
		assertEquals(0xfac3b6daL, digibyteMainNetParams.getPacketMagic() & 0xffffffffL);
		assertEquals(30, digibyteMainNetParams.getAddressHeader());
		assertEquals(63, digibyteMainNetParams.getP2SHHeader());
		assertEquals(128, digibyteMainNetParams.getDumpedPrivateKeyHeader());
		assertEquals("dgb", digibyteMainNetParams.getSegwitAddressHrp());
		assertEquals(0x0488B21E, digibyteMainNetParams.getBip32HeaderP2PKHpub());
		assertEquals(0x0488ADE4, digibyteMainNetParams.getBip32HeaderP2PKHpriv());
		assertEquals(0x04b24746, digibyteMainNetParams.getBip32HeaderP2WPKHpub());
		assertEquals(0x04b2430c, digibyteMainNetParams.getBip32HeaderP2WPKHpriv());
	}

	@Test
	public void testDogecoinUsesSharedStaticMainNetParams() {
		NetworkParameters dogecoinMainNetParams = BitcoinyChainSpecs.DOGECOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		Block genesisBlock = dogecoinMainNetParams.getGenesisBlock();

		assertTrue(dogecoinMainNetParams instanceof StaticBitcoinyParams);
		assertEquals("org.dogecoin.production", dogecoinMainNetParams.getId());
		assertEquals("org.dogecoin.production", dogecoinMainNetParams.getPaymentProtocolId());
		assertEquals("dogecoin", dogecoinMainNetParams.getUriScheme());
		assertEquals("1a91e3dace36e2be3bf030a65679fe821aa1d6ef92e7c9902eb318182c355691", genesisBlock.getHashAsString());
		assertEquals("5b2a3f53f605d62c53e62932dac6925e3d74afa5a4b459745c36d42d0ed26a69", genesisBlock.getMerkleRoot().toString());
		assertEquals(1386325540L, genesisBlock.getTimeSeconds());
		assertEquals(0x1e0ffff0L, genesisBlock.getDifficultyTarget());
		assertEquals(99943L, genesisBlock.getNonce());
		assertEquals(14_400, dogecoinMainNetParams.getTargetTimespan());
		assertEquals(240, dogecoinMainNetParams.getInterval());
		assertEquals(22556, dogecoinMainNetParams.getPort());
		assertEquals(0xc0c0c0c0L, dogecoinMainNetParams.getPacketMagic() & 0xffffffffL);
		assertEquals(30, dogecoinMainNetParams.getAddressHeader());
		assertEquals(22, dogecoinMainNetParams.getP2SHHeader());
		assertEquals(158, dogecoinMainNetParams.getDumpedPrivateKeyHeader());
		assertNull(dogecoinMainNetParams.getSegwitAddressHrp());
		assertEquals(0x02facafd, dogecoinMainNetParams.getBip32HeaderP2PKHpub());
		assertEquals(0x02fac398, dogecoinMainNetParams.getBip32HeaderP2PKHpriv());
		assertEquals(Coin.COIN, dogecoinMainNetParams.getMinNonDustOutput());
		assertEquals(false, dogecoinMainNetParams.hasMaxMoney());
	}

	@Test
	public void testRavencoinUsesSharedStaticParams() {
		NetworkParameters ravencoinMainNetParams = BitcoinyChainSpecs.RAVENCOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		Block genesisBlock = ravencoinMainNetParams.getGenesisBlock();

		assertTrue(ravencoinMainNetParams instanceof StaticBitcoinyParams);
		assertEquals("main", ravencoinMainNetParams.getId());
		assertEquals(NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET, ravencoinMainNetParams.getPaymentProtocolId());
		assertEquals("ravencoin", ravencoinMainNetParams.getUriScheme());
		assertEquals("0000006b444bc2f2ffe627be9d9e7e7a0730000870ef6eb6da46c8eae389df90", genesisBlock.getHashAsString());
		assertEquals(4L, genesisBlock.getVersion());
		assertEquals("28ff00a867739a352523808d301f504bc4547699398d70faf2266a8bae5f3516", genesisBlock.getMerkleRoot().toString());
		assertEquals(1514999494L, genesisBlock.getTimeSeconds());
		assertEquals(0x1e00ffffL, genesisBlock.getDifficultyTarget());
		assertEquals(25023712L, genesisBlock.getNonce());
		assertEquals(120_960, ravencoinMainNetParams.getTargetTimespan());
		assertEquals(2016, ravencoinMainNetParams.getInterval());
		assertEquals(8767, ravencoinMainNetParams.getPort());
		assertEquals(0x5241564eL, ravencoinMainNetParams.getPacketMagic() & 0xffffffffL);
		assertEquals(60, ravencoinMainNetParams.getAddressHeader());
		assertEquals(122, ravencoinMainNetParams.getP2SHHeader());
		assertEquals(128, ravencoinMainNetParams.getDumpedPrivateKeyHeader());
		assertNull(ravencoinMainNetParams.getSegwitAddressHrp());
		assertEquals(2_100_000, ravencoinMainNetParams.getSubsidyDecreaseBlockCount());
		assertEquals(Coin.valueOf(2_730L), ravencoinMainNetParams.getMinNonDustOutput());
	}

	@Test
	public void testDashUsesSharedStaticParams() {
		BitcoinyNetwork dashMainNet = BitcoinyChainSpecs.DASH.getNetwork(BitcoinyChainSpecs.MAIN);

		assertTrue(dashMainNet.getParams() instanceof StaticBitcoinyParams);
		assertEquals(76, dashMainNet.getParams().getAddressHeader());
		assertEquals(16, dashMainNet.getParams().getP2SHHeader());
		assertEquals(0x0488B21E, dashMainNet.getParams().getBip32HeaderP2PKHpub());
		assertEquals(0x0488ADE4, dashMainNet.getParams().getBip32HeaderP2PKHpriv());
		assertEquals("00000ffd590b1485b3caadc19b22e6379c733355108f107a430458cdf3407ab6", dashMainNet.getParams().getGenesisBlock().getHashAsString());
	}

	@Test
	public void testNamecoinUsesSharedStaticParams() {
		BitcoinyNetwork namecoinMainNet = BitcoinyChainSpecs.NAMECOIN.getNetwork(BitcoinyChainSpecs.MAIN);
		NetworkParameters namecoinMainNetParams = namecoinMainNet.getParams();
		Block genesisBlock = namecoinMainNetParams.getGenesisBlock();

		assertTrue(namecoinMainNetParams instanceof StaticBitcoinyParams);
		assertEquals("org.namecoin.production", namecoinMainNetParams.getId());
		assertEquals(NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET, namecoinMainNetParams.getPaymentProtocolId());
		assertEquals("namecoin", namecoinMainNetParams.getUriScheme());
		assertEquals("000000000062b72c5e2ceb45fbc8587e807c155b0da735e6483dfba2f0a9c770", genesisBlock.getHashAsString());
		assertEquals("41c62dbd9068c89a449525e3cd5ac61b20ece28c3c38b3f35b2161f0e6d3cb0d", genesisBlock.getMerkleRoot().toString());
		assertEquals(1303000001L, genesisBlock.getTimeSeconds());
		assertEquals(0x1c007fffL, genesisBlock.getDifficultyTarget());
		assertEquals(0xa21ea192L, genesisBlock.getNonce());
		assertEquals(NetworkParameters.TARGET_TIMESPAN, namecoinMainNetParams.getTargetTimespan());
		assertEquals(NetworkParameters.INTERVAL, namecoinMainNetParams.getInterval());
		assertEquals(8334, namecoinMainNetParams.getPort());
		assertEquals(0xf9beb4feL, namecoinMainNetParams.getPacketMagic() & 0xffffffffL);
		assertEquals(52, namecoinMainNetParams.getAddressHeader());
		assertEquals(13, namecoinMainNetParams.getP2SHHeader());
		assertEquals(180, namecoinMainNetParams.getDumpedPrivateKeyHeader());
		assertEquals("nc", namecoinMainNetParams.getSegwitAddressHrp());
		assertEquals(0x0488B21E, namecoinMainNetParams.getBip32HeaderP2PKHpub());
		assertEquals(0x0488ADE4, namecoinMainNetParams.getBip32HeaderP2PKHpriv());
		assertTrue(BitcoinyChainSpecs.NAMECOIN.hasSpendableOutputScriptFilter());
		assertTrue(BitcoinyChainSpecs.NAMECOIN.isSpendableOutputScript(BitcoinyScript.p2pkhScript(new byte[20])));
		assertFalse(BitcoinyChainSpecs.NAMECOIN.isSpendableOutputScript(buildNameNewScript(BitcoinyScript.p2pkhScript(new byte[20]))));
		assertFalse(BitcoinyChainSpecs.NAMECOIN.isSpendableOutputScript(buildNameFirstUpdateScript(BitcoinyScript.p2pkhScript(new byte[20]))));
		assertFalse(BitcoinyChainSpecs.NAMECOIN.isSpendableOutputScript(buildNameUpdateScript(BitcoinyScript.p2pkhScript(new byte[20]))));
	}

	@Test
	public void testFiroUsesSharedStaticParams() {
		BitcoinyNetwork firoMainNet = BitcoinyChainSpecs.FIRO.getNetwork(BitcoinyChainSpecs.MAIN);
		NetworkParameters firoMainNetParams = firoMainNet.getParams();
		Block genesisBlock = firoMainNetParams.getGenesisBlock();

		assertTrue(firoMainNetParams instanceof StaticBitcoinyParams);
		assertEquals("org.firo.production", firoMainNetParams.getId());
		assertEquals(NetworkParameters.PAYMENT_PROTOCOL_ID_MAINNET, firoMainNetParams.getPaymentProtocolId());
		assertEquals("firo", firoMainNetParams.getUriScheme());
		assertEquals("4381deb85b1b2c9843c222944b616d997516dcbd6a964e1eaf0def0830695233", genesisBlock.getHashAsString());
		assertEquals(2L, genesisBlock.getVersion());
		assertEquals("365d2aa75d061370c9aefdabac3985716b1e3b4bb7c4af4ed54f25e5aaa42783", genesisBlock.getMerkleRoot().toString());
		assertEquals(1414776286L, genesisBlock.getTimeSeconds());
		assertEquals(0x1e0ffff0L, genesisBlock.getDifficultyTarget());
		assertEquals(142392L, genesisBlock.getNonce());
		assertEquals(new BigInteger("00ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16), firoMainNetParams.getMaxTarget());
		assertEquals(60 * 60, firoMainNetParams.getTargetTimespan());
		assertEquals(6, firoMainNetParams.getInterval());
		assertEquals(8168, firoMainNetParams.getPort());
		assertEquals(0xe3d9fef1L, firoMainNetParams.getPacketMagic() & 0xffffffffL);
		assertEquals(82, firoMainNetParams.getAddressHeader());
		assertEquals(7, firoMainNetParams.getP2SHHeader());
		assertEquals(210, firoMainNetParams.getDumpedPrivateKeyHeader());
		assertNull(firoMainNetParams.getSegwitAddressHrp());
		assertEquals(0x0488B21E, firoMainNetParams.getBip32HeaderP2PKHpub());
		assertEquals(0x0488ADE4, firoMainNetParams.getBip32HeaderP2PKHpriv());
		assertEquals(Coin.valueOf(1000L), firoMainNetParams.getMinNonDustOutput());
		assertFalse(firoMainNetParams.hasMaxMoney());
		assertEquals("FIRO 1.00", firoMainNetParams.getMonetaryFormat().format(Coin.COIN).toString());
	}

	@Test
	public void testPirateChainUsesSharedStaticMainNetParams() {
		NetworkParameters pirateMainNetParams = PirateChain.PirateChainNet.MAIN.getParams();
		Block genesisBlock = pirateMainNetParams.getGenesisBlock();

		assertTrue(pirateMainNetParams instanceof StaticBitcoinyParams);
		assertEquals("main", pirateMainNetParams.getId());
		assertEquals("main", pirateMainNetParams.getPaymentProtocolId());
		assertEquals("pirate", pirateMainNetParams.getUriScheme());
		assertEquals(PirateChain.PirateChainNet.MAIN.getGenesisHash(), genesisBlock.getHashAsString());
		assertEquals(4L, genesisBlock.getVersion());
		assertEquals("31e71120c25cd57fd138dfeba98799f2e314bad9ece0b0632fd2a779c9ebb4c2", genesisBlock.getMerkleRoot().toString());
		assertEquals(1231006505L, genesisBlock.getTimeSeconds());
		assertEquals(0x200f0f0fL, genesisBlock.getDifficultyTarget());
		assertEquals(11L, genesisBlock.getNonce());
		assertEquals(302_400, pirateMainNetParams.getTargetTimespan());
		assertEquals(5040, pirateMainNetParams.getInterval());
		assertEquals(7770, pirateMainNetParams.getPort());
		assertEquals(0xf9beb4d9L, pirateMainNetParams.getPacketMagic() & 0xffffffffL);
		assertEquals(60, pirateMainNetParams.getAddressHeader());
		assertEquals(85, pirateMainNetParams.getP2SHHeader());
		assertEquals(188, pirateMainNetParams.getDumpedPrivateKeyHeader());
		assertEquals("zs", pirateMainNetParams.getSegwitAddressHrp());
		assertEquals(100, pirateMainNetParams.getSpendableCoinbaseDepth());
		assertEquals(210_000, pirateMainNetParams.getSubsidyDecreaseBlockCount());
		assertEquals(0x0488B21E, pirateMainNetParams.getBip32HeaderP2PKHpub());
		assertEquals(0x0488ADE4, pirateMainNetParams.getBip32HeaderP2PKHpriv());
		assertEquals(Coin.COIN.multiply(200_000_000L), pirateMainNetParams.getMaxMoney());
		assertEquals(Coin.valueOf(100_000L), pirateMainNetParams.getMinNonDustOutput());
		assertEquals("PIRATE 1.00", pirateMainNetParams.getMonetaryFormat().format(Coin.COIN).toString());
	}

	@Test
	public void testSettingsResolveRegisteredNetworks() {
		Settings settings = Settings.getInstance();

		assertSame(BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.TEST3), settings.getBitcoinyNetwork(BitcoinyChainSpecs.BITCOIN_CURRENCY_CODE));
		assertSame(BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.TEST4), settings.getBitcoinyNetwork(BitcoinyChainSpecs.LITECOIN_CURRENCY_CODE.toLowerCase()));
		assertSame(BitcoinyChainSpecs.DOGECOIN.getNetwork(BitcoinyChainSpecs.MAIN), settings.getBitcoinyNetwork(BitcoinyChainSpecs.DOGECOIN_CURRENCY_CODE));
		assertSame(BitcoinyChainSpecs.DASH.getNetwork(BitcoinyChainSpecs.MAIN), settings.getBitcoinyNetwork(BitcoinyChainSpecs.DASH_CURRENCY_CODE));
		assertSame(BitcoinyChainSpecs.NAMECOIN.getNetwork(BitcoinyChainSpecs.MAIN), settings.getBitcoinyNetwork(BitcoinyChainSpecs.NAMECOIN_CURRENCY_CODE));
		assertSame(BitcoinyChainSpecs.FIRO.getNetwork(BitcoinyChainSpecs.MAIN), settings.getBitcoinyNetwork(BitcoinyChainSpecs.FIRO_CURRENCY_CODE));
	}

	private static byte[] buildNameNewScript(byte[] lockScript) {
		return concat(
				new byte[] { 0x51 },
				BitcoinyScript.pushData("commitment".getBytes(StandardCharsets.UTF_8)),
				new byte[] { 0x6d },
				lockScript);
	}

	private static byte[] buildNameFirstUpdateScript(byte[] lockScript) {
		return concat(
				new byte[] { 0x52 },
				BitcoinyScript.pushData("d/qortium".getBytes(StandardCharsets.UTF_8)),
				BitcoinyScript.pushData("salt".getBytes(StandardCharsets.UTF_8)),
				BitcoinyScript.pushData("value".getBytes(StandardCharsets.UTF_8)),
				new byte[] { 0x6d, 0x6d },
				lockScript);
	}

	private static byte[] buildNameUpdateScript(byte[] lockScript) {
		return concat(
				new byte[] { 0x53 },
				BitcoinyScript.pushData("d/qortium".getBytes(StandardCharsets.UTF_8)),
				BitcoinyScript.pushData("value".getBytes(StandardCharsets.UTF_8)),
				new byte[] { 0x6d, 0x75 },
				lockScript);
	}

	private static byte[] concat(byte[]... arrays) {
		int length = 0;
		for (byte[] array : arrays)
			length += array.length;

		byte[] result = new byte[length];
		int offset = 0;
		for (byte[] array : arrays) {
			System.arraycopy(array, 0, result, offset, array.length);
			offset += array.length;
		}

		return result;
	}

	private static void assertNetworkParamParity(NetworkParameters expected, NetworkParameters actual) {
		assertNetworkParamParity(expected, actual, true);
	}

	private static void assertNetworkParamParity(NetworkParameters expected, NetworkParameters actual, boolean compareGenesisHash) {
		assertNetworkParamParity(expected, actual, compareGenesisHash, true);
	}

	private static void assertNetworkParamParity(NetworkParameters expected, NetworkParameters actual, boolean compareGenesisHash, boolean compareDnsSeeds) {
		Block expectedGenesisBlock = getGenesisBlock(expected);
		Block actualGenesisBlock = getGenesisBlock(actual);

		assertEquals(expected.getId(), actual.getId());
		assertEquals(expected.getPaymentProtocolId(), actual.getPaymentProtocolId());
		assertEquals(expected.getUriScheme(), actual.getUriScheme());
		if (compareGenesisHash)
			assertEquals(expectedGenesisBlock.getHash(), actualGenesisBlock.getHash());
		assertEquals(expectedGenesisBlock.getVersion(), actualGenesisBlock.getVersion());
		assertEquals(expectedGenesisBlock.getMerkleRoot(), actualGenesisBlock.getMerkleRoot());
		assertEquals(expectedGenesisBlock.getTimeSeconds(), actualGenesisBlock.getTimeSeconds());
		assertEquals(expectedGenesisBlock.getDifficultyTarget(), actualGenesisBlock.getDifficultyTarget());
		assertEquals(expectedGenesisBlock.getNonce(), actualGenesisBlock.getNonce());
		assertEquals(expectedGenesisBlock.hasTransactions(), actualGenesisBlock.hasTransactions());
		assertEquals(expectedGenesisBlock.getTransactions().size(), actualGenesisBlock.getTransactions().size());
		for (int index = 0; index < expectedGenesisBlock.getTransactions().size(); index++)
			assertEquals(expectedGenesisBlock.getTransactions().get(index).getHash(), actualGenesisBlock.getTransactions().get(index).getHash());
		assertEquals(expected.getMaxTarget(), actual.getMaxTarget());
		assertEquals(expected.getTargetTimespan(), actual.getTargetTimespan());
		assertEquals(expected.getInterval(), actual.getInterval());
		assertEquals(expected.getPort(), actual.getPort());
		assertEquals(expected.getPacketMagic() & 0xffffffffL, actual.getPacketMagic() & 0xffffffffL);
		assertEquals(expected.getAddressHeader(), actual.getAddressHeader());
		assertEquals(expected.getP2SHHeader(), actual.getP2SHHeader());
		assertEquals(expected.getDumpedPrivateKeyHeader(), actual.getDumpedPrivateKeyHeader());
		assertEquals(expected.getSegwitAddressHrp(), actual.getSegwitAddressHrp());
		assertEquals(expected.getSpendableCoinbaseDepth(), actual.getSpendableCoinbaseDepth());
		assertEquals(expected.getSubsidyDecreaseBlockCount(), actual.getSubsidyDecreaseBlockCount());
		assertEquals(expected.getBip32HeaderP2PKHpub(), actual.getBip32HeaderP2PKHpub());
		assertEquals(expected.getBip32HeaderP2PKHpriv(), actual.getBip32HeaderP2PKHpriv());
		assertEquals(expected.getBip32HeaderP2WPKHpub(), actual.getBip32HeaderP2WPKHpub());
		assertEquals(expected.getBip32HeaderP2WPKHpriv(), actual.getBip32HeaderP2WPKHpriv());
		assertEquals(expected.getMajorityEnforceBlockUpgrade(), actual.getMajorityEnforceBlockUpgrade());
		assertEquals(expected.getMajorityRejectBlockOutdated(), actual.getMajorityRejectBlockOutdated());
		assertEquals(expected.getMajorityWindow(), actual.getMajorityWindow());
		if (compareDnsSeeds)
			assertArrayEquals(expected.getDnsSeeds(), actual.getDnsSeeds());
		assertEquals(expected.getMaxMoney(), actual.getMaxMoney());
		assertEquals(expected.getMinNonDustOutput(), actual.getMinNonDustOutput());
		assertEquals(expected.getMonetaryFormat().format(Coin.COIN).toString(), actual.getMonetaryFormat().format(Coin.COIN).toString());
		assertEquals(expected.getMonetaryFormat().format(Coin.valueOf(123456789L)).toString(), actual.getMonetaryFormat().format(Coin.valueOf(123456789L)).toString());
		assertEquals(expected.hasMaxMoney(), actual.hasMaxMoney());
	}

	private static Block getGenesisBlock(NetworkParameters params) {
		try {
			return params.getGenesisBlock();
		} catch (AbstractMethodError e) {
			return getLegacyGenesisBlock(params);
		}
	}

	private static Block getLegacyGenesisBlock(NetworkParameters params) {
		Class<?> paramsClass = params.getClass();

		while (paramsClass != null) {
			try {
				Field genesisBlockField = paramsClass.getDeclaredField("genesisBlock");
				genesisBlockField.setAccessible(true);
				return (Block) genesisBlockField.get(params);
			} catch (NoSuchFieldException e) {
				paramsClass = paramsClass.getSuperclass();
			} catch (ReflectiveOperationException e) {
				throw new AssertionError("Unable to read legacy genesis block for " + params.getId(), e);
			}
		}

		throw new AssertionError("No legacy genesis block field found for " + params.getId());
	}

	private static class ChainManifest {
		private final String canonicalName;
		private final String currencyCode;
		private final int slip44CoinType;
		private final String mainnetGenesisHash;
		private final Set<String> supportedNetworkNames;
		private final int addressHeader;
		private final int p2shHeader;
		private final int dumpedPrivateKeyHeader;
		private final int bip32PublicHeader;
		private final int bip32PrivateHeader;

		private ChainManifest(String canonicalName, String currencyCode, int slip44CoinType, String mainnetGenesisHash,
				Set<String> supportedNetworkNames, int addressHeader, int p2shHeader, int dumpedPrivateKeyHeader,
				int bip32PublicHeader, int bip32PrivateHeader) {
			this.canonicalName = canonicalName;
			this.currencyCode = currencyCode;
			this.slip44CoinType = slip44CoinType;
			this.mainnetGenesisHash = mainnetGenesisHash;
			this.supportedNetworkNames = supportedNetworkNames;
			this.addressHeader = addressHeader;
			this.p2shHeader = p2shHeader;
			this.dumpedPrivateKeyHeader = dumpedPrivateKeyHeader;
			this.bip32PublicHeader = bip32PublicHeader;
			this.bip32PrivateHeader = bip32PrivateHeader;
		}
	}
}
