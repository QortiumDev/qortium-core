package org.qortal.crosschain;

import org.qortal.settings.Settings;

public class Ravencoin extends ConfiguredBitcoiny {

	public static final String CURRENCY_CODE = BitcoinyChainSpecs.RAVENCOIN_CURRENCY_CODE;

	private static final BitcoinyChainConfig CONFIG = BitcoinyChainSpecs.RAVENCOIN.getConfig();
	static final BitcoinyChainDefinition<Ravencoin> DEFINITION = new BitcoinyChainDefinition<>(
			CONFIG,
			() -> Settings.getInstance().getRavencoinNet(),
			Ravencoin::new);

	public enum RavencoinNet implements DelegatingBitcoinyNetwork {
		MAIN(BitcoinyChainSpecs.RAVENCOIN.getNetwork(BitcoinyChainSpecs.MAIN)),
		TEST3(BitcoinyChainSpecs.RAVENCOIN.getNetwork(BitcoinyChainSpecs.TEST3)),
		REGTEST(BitcoinyChainSpecs.RAVENCOIN.getNetwork(BitcoinyChainSpecs.REGTEST));

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
