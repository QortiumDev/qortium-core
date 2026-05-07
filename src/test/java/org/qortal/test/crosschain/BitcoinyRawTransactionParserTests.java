package org.qortal.test.crosschain;

import com.google.common.hash.HashCode;
import org.junit.Test;
import org.qortal.crosschain.BitcoinyRawTransactionParser;
import org.qortal.crosschain.BitcoinyTransaction;
import org.qortal.crypto.Crypto;

import static org.junit.Assert.*;

public class BitcoinyRawTransactionParserTests {

	private static final String PREVIOUS_TX_HASH_WIRE = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
	private static final String P2PKH_SCRIPT = "76a914" + "22".repeat(20) + "88ac";
	private static final String P2SH_SCRIPT = "a914" + "33".repeat(20) + "87";
	private static final String LEGACY_RAW_HEX = "01000000"
			+ "01"
			+ PREVIOUS_TX_HASH_WIRE
			+ "02000000"
			+ "03aabbcc"
			+ "feffffff"
			+ "02"
			+ "1027000000000000"
			+ "19" + P2PKH_SCRIPT
			+ "0500000000000000"
			+ "17" + P2SH_SCRIPT
			+ "78563412";

	@Test
	public void testParsesLegacyTransaction() {
		byte[] rawTransactionBytes = HashCode.fromString(LEGACY_RAW_HEX).asBytes();
		BitcoinyTransaction transaction = BitcoinyRawTransactionParser.parse("mock-tx", rawTransactionBytes);

		assertEquals("mock-tx", transaction.txHash);
		assertEquals(transactionHash(LEGACY_RAW_HEX), BitcoinyRawTransactionParser.parse(rawTransactionBytes).txHash);
		assertEquals(rawTransactionBytes.length, transaction.size);
		assertEquals(0x12345678, transaction.locktime);

		assertEquals(1, transaction.inputs.size());
		BitcoinyTransaction.Input input = transaction.inputs.get(0);
		assertEquals(reverseHex(PREVIOUS_TX_HASH_WIRE), input.outputTxHash);
		assertEquals(2, input.outputVout);
		assertEquals("aabbcc", input.scriptSig);
		assertEquals(-2, input.sequence);

		assertEquals(2, transaction.outputs.size());
		assertEquals(10_000L, transaction.outputs.get(0).value);
		assertEquals(P2PKH_SCRIPT, transaction.outputs.get(0).scriptPubKey);
		assertEquals(5L, transaction.outputs.get(1).value);
		assertEquals(P2SH_SCRIPT, transaction.outputs.get(1).scriptPubKey);
	}

	@Test
	public void testParsesWitnessTransactionOutputs() {
		String rawHex = "02000000"
				+ "0001"
				+ "01"
				+ PREVIOUS_TX_HASH_WIRE
				+ "00000000"
				+ "00"
				+ "ffffffff"
				+ "01"
				+ "0100000000000000"
				+ "19" + P2PKH_SCRIPT
				+ "02"
				+ "01aa"
				+ "02bbcc"
				+ "00000000";

		String nonWitnessHex = "02000000"
				+ "01"
				+ PREVIOUS_TX_HASH_WIRE
				+ "00000000"
				+ "00"
				+ "ffffffff"
				+ "01"
				+ "0100000000000000"
				+ "19" + P2PKH_SCRIPT
				+ "00000000";

		BitcoinyTransaction transaction = BitcoinyRawTransactionParser.parse(HashCode.fromString(rawHex).asBytes());

		assertEquals(1, transaction.inputs.size());
		assertEquals(1, transaction.outputs.size());
		assertEquals(1L, transaction.outputs.get(0).value);
		assertEquals(P2PKH_SCRIPT, transaction.outputs.get(0).scriptPubKey);
		assertEquals(0, transaction.locktime);
		assertEquals(transactionHash(nonWitnessHex), transaction.txHash);
		assertFalse(transactionHash(rawHex).equals(transaction.txHash));
	}

	@Test
	public void testRejectsTruncatedTransaction() {
		try {
			BitcoinyRawTransactionParser.parse(HashCode.fromString("01000000").asBytes());
			fail("Expected truncated transaction to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("truncated"));
		}
	}

	@Test
	public void testRejectsTrailingBytes() {
		try {
			BitcoinyRawTransactionParser.parse(HashCode.fromString(LEGACY_RAW_HEX + "00").asBytes());
			fail("Expected trailing data to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("trailing"));
		}
	}

	private static String transactionHash(String rawHex) {
		byte[] hashBytes = Crypto.doubleDigest(HashCode.fromString(rawHex).asBytes());
		String hashHex = HashCode.fromBytes(hashBytes).toString();
		return reverseHex(hashHex);
	}

	private static String reverseHex(String hex) {
		StringBuilder builder = new StringBuilder(hex.length());
		for (int offset = hex.length(); offset > 0; offset -= 2)
			builder.append(hex, offset - 2, offset);

		return builder.toString();
	}
}
