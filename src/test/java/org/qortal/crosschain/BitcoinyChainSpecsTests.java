package org.qortal.crosschain;

import org.junit.BeforeClass;
import org.junit.Test;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.NetworkParameters;
import org.libdohj.params.DogecoinMainNetParams;
import org.libdohj.params.DigibyteMainNetParams;
import org.libdohj.params.LitecoinMainNetParams;
import org.libdohj.params.RavencoinMainNetParams;
import org.qortal.repository.DataException;
import org.qortal.settings.Settings;
import org.qortal.test.common.Common;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
			assertEquals(blockchain.getForeignBlockchainId(), spec.getForeignBlockchainId());
			assertSame(blockchain, ForeignBlockchainRegistry.fromForeignBlockchainId(spec.getForeignBlockchainId()));
		}
	}

	@Test
	public void testForeignBlockchainRegistryResolvesNamesCurrencyCodesAndBitcoinyIds() {
		for (ForeignBlockchainRegistry.Entry blockchain : ForeignBlockchainRegistry.entries()) {
			ForeignBlockchainRegistry.Entry byName = ForeignBlockchainRegistry.fromString(blockchain.name().toLowerCase());
			assertNotNull(byName);
			assertEquals(blockchain.name(), byName.name());
			assertSame(blockchain, byName);

			ForeignBlockchainRegistry.Entry byCurrencyCode = ForeignBlockchainRegistry.fromString(blockchain.getCurrencyCode().toLowerCase());
			assertSame(byName, byCurrencyCode);
			assertSame(byName, ForeignBlockchainRegistry.fromStringRequired(blockchain.getCurrencyCode()));

			if (blockchain.isBitcoiny())
				assertSame(byName, ForeignBlockchainRegistry.fromForeignBlockchainId(blockchain.getForeignBlockchainId()));
			else
				assertNull(ForeignBlockchainRegistry.fromForeignBlockchainId(blockchain.getForeignBlockchainId()));
		}

		assertNull(ForeignBlockchainRegistry.fromString("unknown"));
		assertNull(ForeignBlockchainRegistry.fromString("   "));
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
				BitcoinyChainSpecs.DASH
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
	public void testLitecoinUsesSharedStaticMainNetParamsWithLegacyParity() {
		NetworkParameters legacyParams = LitecoinMainNetParams.get();
		NetworkParameters litecoinMainNetParams = BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();

		assertTrue(litecoinMainNetParams instanceof StaticBitcoinyParams);
		assertNetworkParamParity(legacyParams, litecoinMainNetParams);
		assertEquals("12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2", litecoinMainNetParams.getGenesisBlock().getHashAsString());
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
	public void testDigibyteUsesSharedStaticParamsWithLegacyParity() {
		NetworkParameters legacyParams = DigibyteMainNetParams.get();
		NetworkParameters digibyteMainNetParams = BitcoinyChainSpecs.DIGIBYTE.getNetwork(BitcoinyChainSpecs.MAIN).getParams();

		assertTrue(digibyteMainNetParams instanceof StaticBitcoinyParams);
		assertNetworkParamParity(legacyParams, digibyteMainNetParams);
		assertEquals("7497ea1b465eb39f1c8f507bc877078fe016d6fcb6dfad3a64c98dcc6e1e8496", digibyteMainNetParams.getGenesisBlock().getHashAsString());
	}

	@Test
	public void testDogecoinUsesSharedStaticMainNetParamsWithLegacyParity() {
		NetworkParameters legacyParams = DogecoinMainNetParams.get();
		NetworkParameters dogecoinMainNetParams = BitcoinyChainSpecs.DOGECOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();

		assertTrue(dogecoinMainNetParams instanceof StaticBitcoinyParams);
		assertNetworkParamParity(legacyParams, dogecoinMainNetParams);
		assertEquals("1a91e3dace36e2be3bf030a65679fe821aa1d6ef92e7c9902eb318182c355691", dogecoinMainNetParams.getGenesisBlock().getHashAsString());
	}

	@Test
	public void testRavencoinUsesSharedStaticParamsWithLegacyParity() {
		NetworkParameters legacyParams = RavencoinMainNetParams.get();
		NetworkParameters ravencoinMainNetParams = BitcoinyChainSpecs.RAVENCOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();

		assertTrue(ravencoinMainNetParams instanceof StaticBitcoinyParams);
		// Legacy Ravencoin params expose bitcoinj's double-SHA header hash, while the chain uses X16R.
		assertNetworkParamParity(legacyParams, ravencoinMainNetParams, false);
		assertEquals("0000006b444bc2f2ffe627be9d9e7e7a0730000870ef6eb6da46c8eae389df90", ravencoinMainNetParams.getGenesisBlock().getHashAsString());
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
	public void testSettingsResolveRegisteredNetworks() {
		Settings settings = Settings.getInstance();

		assertSame(BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.TEST3), settings.getBitcoinyNetwork(BitcoinyChainSpecs.BITCOIN_CURRENCY_CODE));
		assertSame(BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.TEST4), settings.getBitcoinyNetwork(BitcoinyChainSpecs.LITECOIN_CURRENCY_CODE.toLowerCase()));
		assertSame(BitcoinyChainSpecs.DOGECOIN.getNetwork(BitcoinyChainSpecs.MAIN), settings.getBitcoinyNetwork(BitcoinyChainSpecs.DOGECOIN_CURRENCY_CODE));
		assertSame(BitcoinyChainSpecs.DASH.getNetwork(BitcoinyChainSpecs.MAIN), settings.getBitcoinyNetwork(BitcoinyChainSpecs.DASH_CURRENCY_CODE));
	}

	private static void assertNetworkParamParity(NetworkParameters expected, NetworkParameters actual) {
		assertNetworkParamParity(expected, actual, true);
	}

	private static void assertNetworkParamParity(NetworkParameters expected, NetworkParameters actual, boolean compareGenesisHash) {
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
		assertArrayEquals(expected.getDnsSeeds(), actual.getDnsSeeds());
		assertEquals(expected.getMaxMoney(), actual.getMaxMoney());
		assertEquals(expected.getMinNonDustOutput(), actual.getMinNonDustOutput());
		assertEquals(expected.getMonetaryFormat(), actual.getMonetaryFormat());
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
}
