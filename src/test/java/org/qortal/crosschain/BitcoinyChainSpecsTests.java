package org.qortal.crosschain;

import org.junit.BeforeClass;
import org.junit.Test;
import org.qortal.repository.DataException;
import org.qortal.settings.Settings;
import org.qortal.test.common.Common;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class BitcoinyChainSpecsTests {

	@BeforeClass
	public static void beforeClass() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testSpecRegistryMatchesSupportedBitcoinyBlockchains() {
		Set<String> specCanonicalNames = BitcoinyChainSpecs.all().stream()
				.map(BitcoinyChainSpec::getCanonicalName)
				.collect(Collectors.toSet());

		Set<String> specCurrencyCodes = BitcoinyChainSpecs.all().stream()
				.map(BitcoinyChainSpec::getCurrencyCode)
				.collect(Collectors.toSet());

		Set<String> supportedNames = SupportedBlockchain.bitcoinyBlockchains().stream()
				.map(SupportedBlockchain::name)
				.collect(Collectors.toSet());

		Set<String> supportedCurrencyCodes = SupportedBlockchain.bitcoinyBlockchains().stream()
				.map(SupportedBlockchain::getCurrencyCode)
				.collect(Collectors.toSet());

		Set<String> registeredNames = ForeignBlockchainRegistry.entries().stream()
				.filter(ForeignBlockchainRegistry.Entry::isBitcoiny)
				.map(ForeignBlockchainRegistry.Entry::name)
				.collect(Collectors.toSet());

		Set<String> registeredCurrencyCodes = ForeignBlockchainRegistry.entries().stream()
				.filter(ForeignBlockchainRegistry.Entry::isBitcoiny)
				.map(ForeignBlockchainRegistry.Entry::getCurrencyCode)
				.collect(Collectors.toSet());

		assertEquals(specCanonicalNames, supportedNames);
		assertEquals(specCanonicalNames, registeredNames);
		assertEquals(supportedCurrencyCodes, specCurrencyCodes);
		assertEquals(specCurrencyCodes, registeredCurrencyCodes);

		for (SupportedBlockchain blockchain : SupportedBlockchain.bitcoinyBlockchains()) {
			BitcoinyChainSpec spec = BitcoinyChainSpecs.fromCurrencyCode(blockchain.getCurrencyCode());
			assertNotNull(spec);
			assertEquals(blockchain.name(), spec.getCanonicalName());
			assertSame(spec, blockchain.getBitcoinySpec());
			assertEquals(blockchain.getForeignBlockchainId(), spec.getForeignBlockchainId());
			assertEquals(blockchain, SupportedBlockchain.fromForeignBlockchainId(spec.getForeignBlockchainId()));
		}
	}

	@Test
	public void testForeignBlockchainRegistryResolvesNamesCurrencyCodesAndBitcoinyIds() {
		for (SupportedBlockchain blockchain : SupportedBlockchain.values()) {
			ForeignBlockchainRegistry.Entry byName = ForeignBlockchainRegistry.fromString(blockchain.name().toLowerCase());
			assertNotNull(byName);
			assertEquals(blockchain.name(), byName.name());
			assertSame(blockchain, byName.getFacade());

			ForeignBlockchainRegistry.Entry byCurrencyCode = ForeignBlockchainRegistry.fromString(blockchain.getCurrencyCode().toLowerCase());
			assertSame(byName, byCurrencyCode);

			assertSame(blockchain, SupportedBlockchain.fromString(blockchain.getCurrencyCode()));

			if (blockchain.isBitcoiny())
				assertSame(byName, ForeignBlockchainRegistry.fromForeignBlockchainId(blockchain.getForeignBlockchainId()));
		}

		assertNull(ForeignBlockchainRegistry.fromString("unknown"));
		assertNull(ForeignBlockchainRegistry.fromString("   "));
	}

	@Test
	public void testSpecsContainStandardNetworks() {
		for (BitcoinyChainSpec spec : BitcoinyChainSpecs.all()) {
			assertNotNull(spec.getNetwork(BitcoinyChainSpecs.MAIN));
			assertNotNull(spec.getNetwork(BitcoinyChainSpecs.TEST3));
			assertNotNull(spec.getNetwork(BitcoinyChainSpecs.REGTEST));

			assertEquals(BitcoinyChainSpecs.MAIN, spec.getNetwork(BitcoinyChainSpecs.MAIN).name());
			assertEquals(BitcoinyChainSpecs.TEST3, spec.getNetwork(BitcoinyChainSpecs.TEST3).name());
			assertEquals(BitcoinyChainSpecs.REGTEST, spec.getNetwork(BitcoinyChainSpecs.REGTEST).name());
		}
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
	public void testSettingsResolveRegisteredNetworks() {
		Settings settings = Settings.getInstance();

		assertSame(BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.TEST3), settings.getBitcoinyNetwork(BitcoinyChainSpecs.BITCOIN_CURRENCY_CODE));
		assertSame(BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.TEST3), settings.getBitcoinyNetwork(BitcoinyChainSpecs.LITECOIN_CURRENCY_CODE.toLowerCase()));
		assertSame(BitcoinyChainSpecs.DOGECOIN.getNetwork(BitcoinyChainSpecs.MAIN), settings.getBitcoinyNetwork(BitcoinyChainSpecs.DOGECOIN_CURRENCY_CODE));
	}
}
