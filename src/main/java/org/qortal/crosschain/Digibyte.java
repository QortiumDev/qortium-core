package org.qortal.crosschain;

import org.bitcoinj.core.Coin;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.libdohj.params.DigibyteMainNetParams;
import org.qortal.settings.Settings;

public class Digibyte extends ConfiguredBitcoiny {

	public static final String CURRENCY_CODE = "DGB";

	private static final Coin DEFAULT_FEE_PER_KB = Coin.valueOf(100000); // 0.001 DGB per 1000 bytes

	private static final long MINIMUM_ORDER_AMOUNT = 1000000; // 0.01 DGB minimum order, to avoid dust errors

	// Temporary values until a dynamic fee system is written.
	private static final long MAINNET_FEE = 10000L;
	private static final long NON_MAINNET_FEE = 10000L; // enough for TESTNET3 and should be OK for REGTEST

	private static final BitcoinyChainConfig CONFIG = new BitcoinyChainConfig("Digibyte", CURRENCY_CODE,
			DEFAULT_FEE_PER_KB, MINIMUM_ORDER_AMOUNT, BitcoinyChainConfig.defaultElectrumXPorts());
	static final BitcoinyChainDefinition<Digibyte> DEFINITION = new BitcoinyChainDefinition<>(
			CONFIG,
			() -> Settings.getInstance().getDigibyteNet(),
			Digibyte::new);

	public enum DigibyteNet implements DelegatingBitcoinyNetwork {
		MAIN(StaticBitcoinyNetwork.mainnet(DigibyteMainNetParams::get,
				BitcoinyServers.digibyteMain(),
				"7497ea1b465eb39f1c8f507bc877078fe016d6fcb6dfad3a64c98dcc6e1e8496", MAINNET_FEE)),
		TEST3(StaticBitcoinyNetwork.nonMainnet("TEST3", TestNet3Params::get,
				BitcoinyServers.digibyteTest3(),
				"308ea0711d5763be2995670dd9ca9872753561285a84da1d58be58acaa822252", MAINNET_FEE, NON_MAINNET_FEE)),
		REGTEST(StaticBitcoinyNetwork.nonMainnet("REGTEST", RegTestParams::get,
				BitcoinyServers.localRegtest(),
				null, MAINNET_FEE, NON_MAINNET_FEE));

		private final BitcoinyNetwork delegate;

		DigibyteNet(BitcoinyNetwork delegate) {
			this.delegate = delegate;
		}

		@Override
		public BitcoinyNetwork getDelegate() {
			return this.delegate;
		}
	}

	private Digibyte(BitcoinyChainConfig config, BitcoinyNetwork network) {
		super(config, network);
	}

	public static synchronized Digibyte getInstance() {
		return DEFINITION.getInstance();
	}

	// Getters & setters

	public static synchronized void resetForTesting() {
		DEFINITION.resetForTesting();
	}

}
