package org.qortal.test.crosschain;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.script.Script;
import org.junit.Test;
import org.qortal.crosschain.BitcoinyAddress;
import org.qortal.crosschain.BitcoinyChainSpecs;
import org.qortal.crosschain.BitcoinyScript;

import static org.junit.Assert.*;

public class BitcoinyAddressTests {

	private static final byte[] HASH160 = HashCode.fromString("00112233445566778899aabbccddeeff00112233").asBytes();

	@Test
	public void testBase58P2pkhRoundTrip() {
		NetworkParameters params = bitcoinTest3Params();

		String address = BitcoinyAddress.fromPubKeyHash(params, HASH160).toString();
		BitcoinyAddress decoded = BitcoinyAddress.fromString(params, address);

		assertEquals(BitcoinyAddress.Type.P2PKH, decoded.getType());
		assertArrayEquals(HASH160, decoded.getPayload());
		assertEquals(address, BitcoinyAddress.fromPubKeyHash(params, decoded.getPayload()).toString());
	}

	@Test
	public void testBase58P2shRoundTrip() {
		NetworkParameters params = bitcoinTest3Params();

		String address = BitcoinyAddress.fromScriptHash(params, HASH160).toString();
		BitcoinyAddress decoded = BitcoinyAddress.fromString(params, address);

		assertEquals(BitcoinyAddress.Type.P2SH, decoded.getType());
		assertArrayEquals(HASH160, decoded.getPayload());
		assertEquals(address, BitcoinyAddress.fromScriptHash(params, decoded.getPayload()).toString());
	}

	@Test
	public void testBase58RejectsInvalidChecksum() {
		NetworkParameters params = bitcoinTest3Params();
		String validAddress = BitcoinyAddress.fromPubKeyHash(params, HASH160).toString();
		String invalidAddress = validAddress.substring(0, validAddress.length() - 1)
				+ (validAddress.endsWith("1") ? "2" : "1");

		try {
			BitcoinyAddress.fromString(params, invalidAddress);
			fail("Expected invalid checksum to be rejected");
		} catch (IllegalArgumentException e) {
			// Expected
		}
	}

	@Test
	public void testBech32P2wpkhDecode() {
		NetworkParameters params = bitcoinTest3Params();
		ECKey key = ECKey.fromPrivate(HashCode.fromString("11".repeat(32)).asBytes());
		String address = Address.fromKey(params, key, Script.ScriptType.P2WPKH).toString();

		BitcoinyAddress decoded = BitcoinyAddress.fromString(params, address);

		assertEquals(BitcoinyAddress.Type.P2WPKH, decoded.getType());
		assertArrayEquals(key.getPubKeyHash(), decoded.getPayload());
		assertArrayEquals(Bytes.concat(new byte[] { 0x00, 0x14 }, key.getPubKeyHash()), BitcoinyScript.scriptPubKey(params, address));
	}

	@Test
	public void testStandardScriptPubKeys() {
		assertEquals("76a91400112233445566778899aabbccddeeff0011223388ac",
				HashCode.fromBytes(BitcoinyScript.p2pkhScript(HASH160)).toString());

		assertEquals("a91400112233445566778899aabbccddeeff0011223387",
				HashCode.fromBytes(BitcoinyScript.p2shScript(HASH160)).toString());
	}

	private static NetworkParameters bitcoinTest3Params() {
		return BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.TEST3).getParams();
	}
}
