package org.qortal.crosschain;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.qortal.settings.Settings;

public class Bitcoin extends ConfiguredBitcoiny {

	public static final String CURRENCY_CODE = "BTC";

	// Locking fee to lock in native asset for BTC. This is the default value that the user should reset to
	// a value inline with the BTC fee market. This is 5 sats per kB.
	private static final Coin DEFAULT_FEE_PER_KB = Coin.valueOf(5_000); // 0.00005 BTC per 1000 bytes

	private static final long MINIMUM_ORDER_AMOUNT = 100_000; // 0.001 BTC minimum order, due to high fees

	// Default value until user resets fee to compete with the current market. This is a total value for a
	// p2sh transaction, size 300 kB, 5 sats per kB
	private static final long NEW_FEE_AMOUNT = 1_500L;

	private static final long NON_MAINNET_FEE = 1000L; // enough for TESTNET3 and should be OK for REGTEST

	private static final BitcoinyChainConfig CONFIG = new BitcoinyChainConfig("Bitcoin", CURRENCY_CODE,
			DEFAULT_FEE_PER_KB, MINIMUM_ORDER_AMOUNT, BitcoinyChainConfig.defaultElectrumXPorts());
	static final BitcoinyChainDefinition<Bitcoin> DEFINITION = new BitcoinyChainDefinition<>(
			CONFIG,
			() -> Settings.getInstance().getBitcoinNet(),
			Bitcoin::new);

	public enum BitcoinNet implements DelegatingBitcoinyNetwork {
		MAIN(StaticBitcoinyNetwork.mainnet(MainNetParams::get,
				BitcoinyServers.bitcoinMain(),
				"000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", NEW_FEE_AMOUNT)),
		TEST3(StaticBitcoinyNetwork.nonMainnet("TEST3", TestNet3Params::get,
				BitcoinyServers.bitcoinTest3(),
				"000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943", NEW_FEE_AMOUNT, NON_MAINNET_FEE)),
		REGTEST(StaticBitcoinyNetwork.nonMainnet("REGTEST", RegTestParams::get,
				BitcoinyServers.localRegtest(),
				null, NEW_FEE_AMOUNT, NON_MAINNET_FEE));

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
