package org.qortal.crosschain;

import org.bitcoinj.core.Coin;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.libdohj.params.RavencoinMainNetParams;
import org.qortal.settings.Settings;

public class Ravencoin extends ConfiguredBitcoiny {

	public static final String CURRENCY_CODE = "RVN";

	private static final Coin DEFAULT_FEE_PER_KB = Coin.valueOf(1125000); // 0.01125 RVN per 1000 bytes

	private static final long MINIMUM_ORDER_AMOUNT = 1000000; // 0.01 RVN minimum order, to avoid dust errors

	// Temporary values until a dynamic fee system is written.
	private static final long MAINNET_FEE = 1000000L;
	private static final long NON_MAINNET_FEE = 1000000L; // enough for TESTNET3 and should be OK for REGTEST

	private static final BitcoinyChainConfig CONFIG = new BitcoinyChainConfig("Ravencoin", CURRENCY_CODE,
			DEFAULT_FEE_PER_KB, MINIMUM_ORDER_AMOUNT, BitcoinyChainConfig.defaultElectrumXPorts());
	static final BitcoinyChainDefinition<Ravencoin> DEFINITION = new BitcoinyChainDefinition<>(
			CONFIG,
			() -> Settings.getInstance().getRavencoinNet(),
			Ravencoin::new);

	public enum RavencoinNet implements DelegatingBitcoinyNetwork {
		MAIN(StaticBitcoinyNetwork.mainnet(RavencoinMainNetParams::get,
				BitcoinyServers.ravencoinMain(),
				"0000006b444bc2f2ffe627be9d9e7e7a0730000870ef6eb6da46c8eae389df90", MAINNET_FEE)),
		TEST3(StaticBitcoinyNetwork.nonMainnet("TEST3", TestNet3Params::get,
				BitcoinyServers.ravencoinTest3(),
				"000000ecfc5e6324a079542221d00e10362bdc894d56500c414060eea8a3ad5a", MAINNET_FEE, NON_MAINNET_FEE)),
		REGTEST(StaticBitcoinyNetwork.nonMainnet("REGTEST", RegTestParams::get,
				BitcoinyServers.localRegtest(),
				null, MAINNET_FEE, NON_MAINNET_FEE));

		private final BitcoinyNetwork delegate;

		RavencoinNet(BitcoinyNetwork delegate) {
			this.delegate = delegate;
		}

		@Override
		public BitcoinyNetwork getDelegate() {
			return this.delegate;
		}
	}

	private Ravencoin(BitcoinyChainConfig config, BitcoinyNetwork network) {
		super(config, network);
	}

	public static synchronized Ravencoin getInstance() {
		return DEFINITION.getInstance();
	}

	// Getters & setters

	public static synchronized void resetForTesting() {
		DEFINITION.resetForTesting();
	}

}
