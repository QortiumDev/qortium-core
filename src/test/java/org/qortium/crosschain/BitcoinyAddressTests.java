package org.qortium.crosschain;

import com.google.common.hash.HashCode;
import org.bitcoinj.base.Bech32;
import org.bitcoinj.core.NetworkParameters;
import org.junit.BeforeClass;
import org.junit.Test;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BitcoinyAddressTests {

	@BeforeClass
	public static void beforeClass() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testZcashTwoByteBase58PrefixesRoundTrip() {
		NetworkParameters zcashParams = BitcoinyChainSpecs.ZCASH.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		byte[] publicKeyHash = HashCode.fromString("11".repeat(Bitcoiny.HASH160_LENGTH)).asBytes();
		byte[] scriptHash = HashCode.fromString("22".repeat(Bitcoiny.HASH160_LENGTH)).asBytes();

		BitcoinyAddress p2pkhAddress = BitcoinyAddress.fromPubKeyHash(zcashParams, publicKeyHash);
		assertEquals(BitcoinyAddress.Type.P2PKH, p2pkhAddress.getType());
		assertTrue(p2pkhAddress.toString().startsWith("t1"));
		assertArrayEquals(publicKeyHash, p2pkhAddress.getPayload());

		BitcoinyAddress decodedP2pkhAddress = BitcoinyAddress.fromString(zcashParams, p2pkhAddress.toString());
		assertEquals(BitcoinyAddress.Type.P2PKH, decodedP2pkhAddress.getType());
		assertArrayEquals(publicKeyHash, decodedP2pkhAddress.getPayload());

		BitcoinyAddress p2shAddress = BitcoinyAddress.fromScriptHash(zcashParams, scriptHash);
		assertEquals(BitcoinyAddress.Type.P2SH, p2shAddress.getType());
		assertTrue(p2shAddress.toString().startsWith("t3"));
		assertArrayEquals(scriptHash, p2shAddress.getPayload());

		BitcoinyAddress decodedP2shAddress = BitcoinyAddress.fromString(zcashParams, p2shAddress.toString());
		assertEquals(BitcoinyAddress.Type.P2SH, decodedP2shAddress.getType());
		assertArrayEquals(scriptHash, decodedP2shAddress.getPayload());
		assertEquals("a914222222222222222222222222222222222222222287",
				HashCode.fromBytes(BitcoinyScript.scriptPubKey(zcashParams, p2shAddress.toString())).toString());
	}

	@Test
	public void testSingleByteBase58PrefixesStillRoundTrip() {
		NetworkParameters bitcoinParams = BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		byte[] publicKeyHash = HashCode.fromString("11".repeat(Bitcoiny.HASH160_LENGTH)).asBytes();
		byte[] scriptHash = HashCode.fromString("22".repeat(Bitcoiny.HASH160_LENGTH)).asBytes();

		BitcoinyAddress p2pkhAddress = BitcoinyAddress.fromPubKeyHash(bitcoinParams, publicKeyHash);
		assertEquals(BitcoinyAddress.Type.P2PKH, p2pkhAddress.getType());
		assertTrue(p2pkhAddress.toString().startsWith("1"));
		assertArrayEquals(publicKeyHash, BitcoinyAddress.fromString(bitcoinParams, p2pkhAddress.toString()).getPayload());

		BitcoinyAddress p2shAddress = BitcoinyAddress.fromScriptHash(bitcoinParams, scriptHash);
		assertEquals(BitcoinyAddress.Type.P2SH, p2shAddress.getType());
		assertTrue(p2shAddress.toString().startsWith("3"));
		assertArrayEquals(scriptHash, BitcoinyAddress.fromString(bitcoinParams, p2shAddress.toString()).getPayload());
	}

	@Test
	public void testBech32RawValuesRoundTrip() {
		byte[] values = new byte[] {
				0, 1, 2, 3, 4, 5, 6, 7,
				8, 9, 10, 11, 12, 13, 14, 15,
				16, 17, 18, 19, 20, 21, 22, 23,
				24, 25, 26, 27, 28, 29, 30, 31
		};

		String encoded = BitcoinyAddress.encodeBech32Values("ZS", values);
		Bech32.Bech32Data decoded = Bech32.decode(encoded);

		assertTrue(encoded.startsWith("zs1"));
		assertEquals("zs", decoded.hrp);
		assertArrayEquals(values, decoded.bytes());
	}

	@Test
	public void testZcashAddressIsRejectedByBitcoinNetwork() {
		NetworkParameters zcashParams = BitcoinyChainSpecs.ZCASH.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		NetworkParameters bitcoinParams = BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		String zcashAddress = BitcoinyAddress.fromPubKeyHash(zcashParams,
				HashCode.fromString("11".repeat(Bitcoiny.HASH160_LENGTH)).asBytes()).toString();

		try {
			BitcoinyAddress.fromString(bitcoinParams, zcashAddress);
			fail("Expected Zcash address to be rejected by Bitcoin params");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("network") || e.getMessage().contains("Bech32"));
		}
	}
}
