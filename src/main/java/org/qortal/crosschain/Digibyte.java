package org.qortal.crosschain;

import org.qortal.settings.Settings;

public class Digibyte extends ConfiguredBitcoiny {

	public static final String CURRENCY_CODE = BitcoinyChainSpecs.DIGIBYTE_CURRENCY_CODE;

	private static final BitcoinyChainConfig CONFIG = BitcoinyChainSpecs.DIGIBYTE.getConfig();
	static final BitcoinyChainDefinition<Digibyte> DEFINITION = new BitcoinyChainDefinition<>(
			CONFIG,
			() -> Settings.getInstance().getDigibyteNet(),
			Digibyte::new);

	public enum DigibyteNet implements DelegatingBitcoinyNetwork {
		MAIN(BitcoinyChainSpecs.DIGIBYTE.getNetwork(BitcoinyChainSpecs.MAIN)),
		TEST3(BitcoinyChainSpecs.DIGIBYTE.getNetwork(BitcoinyChainSpecs.TEST3)),
		REGTEST(BitcoinyChainSpecs.DIGIBYTE.getNetwork(BitcoinyChainSpecs.REGTEST));

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
