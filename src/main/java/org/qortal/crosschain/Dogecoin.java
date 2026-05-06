package org.qortal.crosschain;

import org.qortal.settings.Settings;

public class Dogecoin extends ConfiguredBitcoiny {

	public static final String CURRENCY_CODE = BitcoinyChainSpecs.DOGECOIN_CURRENCY_CODE;

	private static final BitcoinyChainConfig CONFIG = BitcoinyChainSpecs.DOGECOIN.getConfig();
	static final BitcoinyChainDefinition<Dogecoin> DEFINITION = new BitcoinyChainDefinition<>(
			CONFIG,
			() -> Settings.getInstance().getDogecoinNet(),
			Dogecoin::new);

	public enum DogecoinNet implements DelegatingBitcoinyNetwork {
		MAIN(BitcoinyChainSpecs.DOGECOIN.getNetwork(BitcoinyChainSpecs.MAIN)),
		TEST3(BitcoinyChainSpecs.DOGECOIN.getNetwork(BitcoinyChainSpecs.TEST3)),
		REGTEST(BitcoinyChainSpecs.DOGECOIN.getNetwork(BitcoinyChainSpecs.REGTEST));

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
