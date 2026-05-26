package org.qortium.test.crosschain;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.script.Script;
import org.junit.Test;
import org.qortium.crosschain.BitcoinyAddress;
import org.qortium.crosschain.BitcoinyChainSpecs;
import org.qortium.crosschain.BitcoinyScript;

import java.util.List;

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
	public void testBitcoinCashCashAddrRoundTripAndLegacyDecode() {
		NetworkParameters params = bitcoinCashParams();
		String legacyAddress = "1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu";
		String cashAddress = "bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a";

		BitcoinyAddress legacyDecoded = BitcoinyAddress.fromString(params, legacyAddress);
		BitcoinyAddress cashDecoded = BitcoinyAddress.fromString(params, cashAddress);
		BitcoinyAddress cashDecodedWithoutPrefix = BitcoinyAddress.fromString(params, cashAddress.substring("bitcoincash:".length()));

		assertEquals(BitcoinyAddress.Type.P2PKH, cashDecoded.getType());
		assertArrayEquals(legacyDecoded.getPayload(), cashDecoded.getPayload());
		assertArrayEquals(cashDecoded.getPayload(), cashDecodedWithoutPrefix.getPayload());
		assertEquals(cashAddress, BitcoinyAddress.fromPubKeyHash(params, cashDecoded.getPayload()).toString());
	}

	@Test
	public void testBitcoinCashP2shCashAddrRoundTrip() {
		NetworkParameters params = bitcoinCashParams();

		String address = BitcoinyAddress.fromScriptHash(params, HASH160).toString();
		BitcoinyAddress decoded = BitcoinyAddress.fromString(params, address);

		assertTrue(address.startsWith("bitcoincash:p"));
		assertEquals(BitcoinyAddress.Type.P2SH, decoded.getType());
		assertArrayEquals(HASH160, decoded.getPayload());
		assertArrayEquals(BitcoinyScript.p2shScript(HASH160), BitcoinyScript.scriptPubKey(params, address));
	}

	@Test
	public void testBitcoinCashTest4UsesBchTestCashAddrPrefix() {
		NetworkParameters params = bitcoinCashTest4Params();

		String p2pkhAddress = BitcoinyAddress.fromPubKeyHash(params, HASH160).toString();
		String p2shAddress = BitcoinyAddress.fromScriptHash(params, HASH160).toString();

		assertTrue(p2pkhAddress.startsWith("bchtest:q"));
		assertTrue(p2shAddress.startsWith("bchtest:p"));
		assertArrayEquals(HASH160, BitcoinyAddress.fromString(params, p2pkhAddress).getPayload());
		assertArrayEquals(HASH160, BitcoinyAddress.fromString(params, p2shAddress).getPayload());
		assertArrayEquals(BitcoinyScript.p2pkhScript(HASH160), BitcoinyScript.scriptPubKey(params, p2pkhAddress));
		assertArrayEquals(BitcoinyScript.p2shScript(HASH160), BitcoinyScript.scriptPubKey(params, p2shAddress));
	}

	@Test
	public void testBitcoinCashCashAddrRejectsMixedCase() {
		try {
			BitcoinyAddress.fromString(bitcoinCashParams(), "bitcoincash:Qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a");
			fail("Expected mixed-case CashAddr to be rejected");
		} catch (IllegalArgumentException e) {
			// Expected
		}
	}

	@Test
	public void testBech32P2wpkhDecode() {
		NetworkParameters params = bitcoinTest3Params();
		ECKey key = ECKey.fromPrivate(HashCode.fromString("11".repeat(32)).asBytes());
		String address = Address.fromKey(params, key, ScriptType.P2WPKH).toString();

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

	@Test
	public void testExtractScriptSigChunks() {
		List<byte[]> chunks = BitcoinyScript.extractScriptSigChunks(HashCode.fromString("02aabb4c0311223300").asBytes());

		assertEquals(3, chunks.size());
		assertEquals("aabb", HashCode.fromBytes(chunks.get(0)).toString());
		assertEquals("112233", HashCode.fromBytes(chunks.get(1)).toString());
		assertEquals(0, chunks.get(2).length);
	}

	@Test
	public void testPushDataScripts() {
		assertEquals("00", HashCode.fromBytes(BitcoinyScript.pushData(new byte[0])).toString());
		assertEquals("02aabb", HashCode.fromBytes(BitcoinyScript.pushData(HashCode.fromString("aabb").asBytes())).toString());

		byte[] pushData1Bytes = HashCode.fromString("11".repeat(76)).asBytes();
		assertEquals("4c4c" + "11".repeat(76), HashCode.fromBytes(BitcoinyScript.pushData(pushData1Bytes)).toString());
	}

	@Test
	public void testExtractScriptSigChunksRejectsMalformedPushes() {
		assertTrue(BitcoinyScript.extractScriptSigChunks(HashCode.fromString("4c").asBytes()).isEmpty());
		assertTrue(BitcoinyScript.extractScriptSigChunks(HashCode.fromString("03aabb").asBytes()).isEmpty());
		assertTrue(BitcoinyScript.extractScriptSigChunks(HashCode.fromString("4d0100").asBytes()).isEmpty());
	}

	@Test
	public void testPushDataRejectsTooLargePayloads() {
		try {
			BitcoinyScript.pushData(new byte[256]);
			fail("Expected large push data to be rejected");
		} catch (IllegalArgumentException e) {
			// Expected
		}
	}

	private static NetworkParameters bitcoinTest3Params() {
		return BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.TEST3).getParams();
	}

	private static NetworkParameters bitcoinCashParams() {
		return BitcoinyChainSpecs.BITCOIN_CASH.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
	}

	private static NetworkParameters bitcoinCashTest4Params() {
		return BitcoinyChainSpecs.BITCOIN_CASH.getNetwork(BitcoinyChainSpecs.TEST4).getParams();
	}
}
