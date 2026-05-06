package org.qortal.crosschain;

import org.junit.BeforeClass;
import org.junit.Test;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class BitcoinyChainSpecsTests {

	@BeforeClass
	public static void beforeClass() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testSpecRegistryMatchesSupportedBitcoinyBlockchains() {
		Set<String> specCurrencyCodes = BitcoinyChainSpecs.all().stream()
				.map(BitcoinyChainSpec::getCurrencyCode)
				.collect(Collectors.toSet());

		Set<String> supportedCurrencyCodes = SupportedBlockchain.bitcoinyBlockchains().stream()
				.map(SupportedBlockchain::getCurrencyCode)
				.collect(Collectors.toSet());

		assertEquals(supportedCurrencyCodes, specCurrencyCodes);

		for (SupportedBlockchain blockchain : SupportedBlockchain.bitcoinyBlockchains()) {
			BitcoinyChainSpec spec = BitcoinyChainSpecs.fromCurrencyCode(blockchain.getCurrencyCode());
			assertNotNull(spec);
			assertEquals(blockchain.getForeignBlockchainId(), spec.getForeignBlockchainId());
			assertEquals(blockchain, SupportedBlockchain.fromForeignBlockchainId(spec.getForeignBlockchainId()));
		}
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
				assertNotNull(refreshConfig.getBuiltInSourceMarker());
			}
		}
	}

	@Test
	public void testCoinEnumsDelegateToRegisteredNetworks() {
		assertSame(BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.MAIN), Bitcoin.BitcoinNet.MAIN.getDelegate());
		assertSame(BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.TEST3), Bitcoin.BitcoinNet.TEST3.getDelegate());
		assertSame(BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.REGTEST), Bitcoin.BitcoinNet.REGTEST.getDelegate());

		assertSame(BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.MAIN), Litecoin.LitecoinNet.MAIN.getDelegate());
		assertSame(BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.TEST3), Litecoin.LitecoinNet.TEST3.getDelegate());
		assertSame(BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.REGTEST), Litecoin.LitecoinNet.REGTEST.getDelegate());

		assertSame(BitcoinyChainSpecs.DOGECOIN.getNetwork(BitcoinyChainSpecs.MAIN), Dogecoin.DogecoinNet.MAIN.getDelegate());
		assertSame(BitcoinyChainSpecs.DOGECOIN.getNetwork(BitcoinyChainSpecs.TEST3), Dogecoin.DogecoinNet.TEST3.getDelegate());
		assertSame(BitcoinyChainSpecs.DOGECOIN.getNetwork(BitcoinyChainSpecs.REGTEST), Dogecoin.DogecoinNet.REGTEST.getDelegate());

		assertSame(BitcoinyChainSpecs.DIGIBYTE.getNetwork(BitcoinyChainSpecs.MAIN), Digibyte.DigibyteNet.MAIN.getDelegate());
		assertSame(BitcoinyChainSpecs.DIGIBYTE.getNetwork(BitcoinyChainSpecs.TEST3), Digibyte.DigibyteNet.TEST3.getDelegate());
		assertSame(BitcoinyChainSpecs.DIGIBYTE.getNetwork(BitcoinyChainSpecs.REGTEST), Digibyte.DigibyteNet.REGTEST.getDelegate());

		assertSame(BitcoinyChainSpecs.RAVENCOIN.getNetwork(BitcoinyChainSpecs.MAIN), Ravencoin.RavencoinNet.MAIN.getDelegate());
		assertSame(BitcoinyChainSpecs.RAVENCOIN.getNetwork(BitcoinyChainSpecs.TEST3), Ravencoin.RavencoinNet.TEST3.getDelegate());
		assertSame(BitcoinyChainSpecs.RAVENCOIN.getNetwork(BitcoinyChainSpecs.REGTEST), Ravencoin.RavencoinNet.REGTEST.getDelegate());
	}
}
