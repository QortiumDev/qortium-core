package org.qortal.crosschain;

import org.bitcoinj.core.Transaction;
import org.qortal.settings.Settings;

public class Bitcoin extends ConfiguredBitcoiny {

	public static final String CURRENCY_CODE = BitcoinyChainSpecs.BITCOIN_CURRENCY_CODE;

	private static final BitcoinyChainConfig CONFIG = BitcoinyChainSpecs.BITCOIN.getConfig();
	static final BitcoinyChainDefinition<Bitcoin> DEFINITION = new BitcoinyChainDefinition<>(
			CONFIG,
			() -> Settings.getInstance().getBitcoinNet(),
			Bitcoin::new);

	public enum BitcoinNet implements DelegatingBitcoinyNetwork {
		MAIN(BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.MAIN)),
		TEST3(BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.TEST3)),
		REGTEST(BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.REGTEST));

		private final BitcoinyNetwork delegate;

		BitcoinNet(BitcoinyNetwork delegate) {
			this.delegate = delegate;
		}

		@Override
		public BitcoinyNetwork getDelegate() {
			return this.delegate;
		}
	}

	private Bitcoin(BitcoinyChainConfig config, BitcoinyNetwork network) {
		super(config, network);
	}

	public static synchronized Bitcoin getInstance() {
		return DEFINITION.getInstance();
	}

	// Getters & setters

	public static synchronized void resetForTesting() {
		DEFINITION.resetForTesting();
	}

	// Actual useful methods for use by other classes

	/**
 	* Returns bitcoinj transaction sending <tt>amount</tt> to <tt>recipient</tt> using 20 sat/byte fee.
 	*
 	* @param xprv58 BIP32 private key
 	* @param recipient P2PKH address
 	* @param amount unscaled amount
 	* @return transaction, or null if insufficient funds
 	*/
	@Override
	public Transaction buildSpend(String xprv58, String recipient, long amount) {
		return buildSpend(xprv58, recipient, amount, 20L);
	}

}
