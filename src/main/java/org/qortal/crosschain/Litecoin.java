package org.qortal.crosschain;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.script.Script;
import org.qortal.settings.Settings;

public class Litecoin extends ConfiguredBitcoiny {

	public static final String CURRENCY_CODE = BitcoinyChainSpecs.LITECOIN_CURRENCY_CODE;

	public static final LitecoinMainNetParamsP2ShOverride MAIN_NET_PARAMS_P2SH_OVERRIDE = new LitecoinMainNetParamsP2ShOverride(50);

	private static final BitcoinyChainConfig CONFIG = BitcoinyChainSpecs.LITECOIN.getConfig();
	static final BitcoinyChainDefinition<Litecoin> DEFINITION = new BitcoinyChainDefinition<>(
			CONFIG,
			() -> Settings.getInstance().getLitecoinNet(),
			Litecoin::new);

	public enum LitecoinNet implements DelegatingBitcoinyNetwork {
		MAIN(BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.MAIN)),
		TEST3(BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.TEST3)),
		REGTEST(BitcoinyChainSpecs.LITECOIN.getNetwork(BitcoinyChainSpecs.REGTEST));

		private final BitcoinyNetwork delegate;

		LitecoinNet(BitcoinyNetwork delegate) {
			this.delegate = delegate;
		}

		@Override
		public BitcoinyNetwork getDelegate() {
			return this.delegate;
		}
	}

	private Litecoin(BitcoinyChainConfig config, BitcoinyNetwork network) {
		super(config, network);
	}

	public static synchronized Litecoin getInstance() {
		return DEFINITION.getInstance();
	}

	// Getters & setters

	public static synchronized void resetForTesting() {
		DEFINITION.resetForTesting();
	}

	/**
	 * Is P2SH Address Current?
	 *
	 * Is the address conforming to the current p2sh standard, prefix 'M'?
	 *
	 * @param address the address
	 *
	 * @return true if conforms to the standard, otherwise false
	 */
	public boolean isCurrentP2ShAddress(String address) {
		try {
			Script.ScriptType addressType = Address.fromString(MAIN_NET_PARAMS_P2SH_OVERRIDE, address).getOutputScriptType();

			return addressType == Script.ScriptType.P2SH;
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
			return false;
		}
	}

	@Override
	public String normalizeAddress(String address) {
		return isCurrentP2ShAddress(address) ? convertP2SHAddress(address, MAIN_NET_PARAMS_P2SH_OVERRIDE, this.params) : address;
	}

	/**
	 * Convert P2SH Address
	 *
	 * Convert p2sh address from one network standard to another.
	 *
	 * @param p2shAddress the p2sh address
	 * @param fromParams the existing standard
	 * @param toParams the desired standard
	 *
	 * @return the p2sh conforming to the desired standard
	 */
	private static String convertP2SHAddress(String p2shAddress, NetworkParameters fromParams, NetworkParameters toParams) {
		try {
			// decode the P2SH address
			Address address = LegacyAddress.fromBase58(fromParams, p2shAddress);
			byte[] hash = address.getHash();

			// create a new address with the target network parameters
			Address fromScriptHash = LegacyAddress.fromScriptHash(toParams, hash);

			// return the new address as a string
			return fromScriptHash.toString();
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
			return null;
		}
	}
}
