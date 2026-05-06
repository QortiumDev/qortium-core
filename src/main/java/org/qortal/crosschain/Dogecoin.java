package org.qortal.crosschain;

import org.bitcoinj.core.Coin;
import org.libdohj.params.DogecoinMainNetParams;
import org.libdohj.params.DogecoinTestNet3Params;
import org.qortal.settings.Settings;

public class Dogecoin extends ConfiguredBitcoiny {

	public static final String CURRENCY_CODE = "DOGE";

	private static final Coin DEFAULT_FEE_PER_KB = Coin.valueOf(1000000); // 0.01 DOGE per 1000 bytes

	private static final long MINIMUM_ORDER_AMOUNT = 100000000L; // 1 DOGE minimum order. See recommendations:
	// https://github.com/dogecoin/dogecoin/blob/master/doc/fee-recommendation.md

	// Temporary values until a dynamic fee system is written.
	private static final long MAINNET_FEE = 100000L;
	private static final long NON_MAINNET_FEE = 10000L; // TODO: calibrate this

	private static final BitcoinyChainConfig CONFIG = new BitcoinyChainConfig("Dogecoin", CURRENCY_CODE,
			DEFAULT_FEE_PER_KB, MINIMUM_ORDER_AMOUNT, BitcoinyChainConfig.defaultElectrumXPorts());
	static final BitcoinyChainDefinition<Dogecoin> DEFINITION = new BitcoinyChainDefinition<>(
			CONFIG,
			() -> Settings.getInstance().getDogecoinNet(),
			Dogecoin::new);

	public enum DogecoinNet implements DelegatingBitcoinyNetwork {
		MAIN(StaticBitcoinyNetwork.mainnet(DogecoinMainNetParams::get,
				BitcoinyServers.dogecoinMain(),
				"1a91e3dace36e2be3bf030a65679fe821aa1d6ef92e7c9902eb318182c355691", MAINNET_FEE)),
		TEST3(StaticBitcoinyNetwork.nonMainnet("TEST3", DogecoinTestNet3Params::get,
				BitcoinyServers.dogecoinTest3(),
				"4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0", MAINNET_FEE, NON_MAINNET_FEE)),
		REGTEST(StaticBitcoinyNetwork.nonMainnet("REGTEST",
				() -> null, // TODO: DogecoinRegTestParams.get();
				BitcoinyServers.localRegtest(),
				null, MAINNET_FEE, NON_MAINNET_FEE));

		private final BitcoinyNetwork delegate;

		DogecoinNet(BitcoinyNetwork delegate) {
			this.delegate = delegate;
		}

		@Override
		public BitcoinyNetwork getDelegate() {
			return this.delegate;
		}
	}

	private Dogecoin(BitcoinyChainConfig config, BitcoinyNetwork network) {
		super(config, network);
	}

	public static synchronized Dogecoin getInstance() {
		return DEFINITION.getInstance();
	}

	// Getters & setters

	public static synchronized void resetForTesting() {
		DEFINITION.resetForTesting();
	}

}
