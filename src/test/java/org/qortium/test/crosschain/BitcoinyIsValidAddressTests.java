package org.qortium.test.crosschain;

import org.bitcoinj.core.NetworkParameters;
import org.junit.BeforeClass;
import org.junit.Test;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinyChainSpecs;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P-CORE-56 regression coverage: {@link Bitcoiny#isValidAddress(String)} must accept P2WSH
 * addresses, since {@code BitcoinyScript.scriptPubKey} already builds witness-v0 output scripts
 * for them and Home 2 signs P2WSH sends itself - the legacy /crosschain/{coin}/send endpoint
 * must not reject what the wallet can already build and sign. Taproot (witness v1) addresses
 * must remain rejected.
 */
public class BitcoinyIsValidAddressTests {

	// BIP173 test vector: a valid mainnet P2WSH address (witness v0, 32-byte program, 62 chars).
	private static final String VALID_P2WSH_ADDRESS = "bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3";

	// BIP350 test vector: a valid mainnet Taproot address (witness v1, 32-byte program).
	private static final String TAPROOT_ADDRESS = "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr";

	@BeforeClass
	public static void beforeClass() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testP2wshAddressIsAcceptedAndTaprootAddressIsStillRejected() {
		NetworkParameters bitcoinMainNetParams = BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		MockBitcoinyBlockchainProvider blockchainProvider = new MockBitcoinyBlockchainProvider("bitcoin-mainnet-address-validation");
		Bitcoiny bitcoiny = new TestBitcoiny(bitcoinMainNetParams, blockchainProvider, "BTC");

		assertEquals(62, VALID_P2WSH_ADDRESS.length());
		assertTrue("A valid P2WSH bech32 address must be accepted, matching what "
				+ "BitcoinyScript.scriptPubKey already builds", bitcoiny.isValidAddress(VALID_P2WSH_ADDRESS));

		assertFalse("A Taproot (witness v1) address must still be rejected",
				bitcoiny.isValidAddress(TAPROOT_ADDRESS));
	}
}
