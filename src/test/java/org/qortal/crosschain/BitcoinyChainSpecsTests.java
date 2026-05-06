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
