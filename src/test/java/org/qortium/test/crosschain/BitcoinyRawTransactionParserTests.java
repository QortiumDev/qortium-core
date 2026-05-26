package org.qortium.test.crosschain;

import com.google.common.hash.HashCode;
import org.junit.Test;
import org.qortium.crosschain.BitcoinyRawTransactionParser;
import org.qortium.crosschain.BitcoinyTransaction;
import org.qortium.crosschain.BitcoinyTransactionData;
import org.qortium.crosschain.BitcoinyTransactionFormat;
import org.qortium.crypto.Crypto;

import java.util.Collections;
import java.util.List;

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
	private static final String PEERCOIN_PRE_VERSION3_RAW_HEX = "02000000"
			+ "88776655"
			+ LEGACY_RAW_HEX.substring(8);
	private static final String PEERCOIN_VERSION3_RAW_HEX = "03000000"
			+ LEGACY_RAW_HEX.substring(8);
	private static final String TIMESTAMPED_LEGACY_RAW_HEX = "01000000"
			+ "44332211"
			+ LEGACY_RAW_HEX.substring(8);

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
	public void testParsesPeercoinPreVersion3TransactionWithTimestamp() {
		byte[] rawTransactionBytes = HashCode.fromString(PEERCOIN_PRE_VERSION3_RAW_HEX).asBytes();
		BitcoinyTransaction transaction = BitcoinyRawTransactionParser.parse(BitcoinyTransactionFormat.PEERCOIN, rawTransactionBytes);

		assertEquals(transactionHash(PEERCOIN_PRE_VERSION3_RAW_HEX), transaction.txHash);
		assertEquals(rawTransactionBytes.length, transaction.size);
		assertEquals(0x12345678, transaction.locktime);
		assertEquals(1, transaction.inputs.size());
		assertEquals(2, transaction.outputs.size());
		assertEquals(10_000L, transaction.outputs.get(0).value);
		assertEquals(P2PKH_SCRIPT, transaction.outputs.get(0).scriptPubKey);
	}

	@Test
	public void testParsesPeercoinVersion3TransactionWithoutTimestamp() {
		BitcoinyTransaction transaction = BitcoinyRawTransactionParser.parse(BitcoinyTransactionFormat.PEERCOIN,
				HashCode.fromString(PEERCOIN_VERSION3_RAW_HEX).asBytes());

		assertEquals(transactionHash(PEERCOIN_VERSION3_RAW_HEX), transaction.txHash);
		assertEquals(0x12345678, transaction.locktime);
		assertEquals(1, transaction.inputs.size());
		assertEquals(2, transaction.outputs.size());
	}

	@Test
	public void testParsesTimestampedLegacyTransaction() {
		byte[] rawTransactionBytes = HashCode.fromString(TIMESTAMPED_LEGACY_RAW_HEX).asBytes();
		BitcoinyTransaction transaction = BitcoinyRawTransactionParser.parse(BitcoinyTransactionFormat.TIMESTAMPED_LEGACY, rawTransactionBytes);

		assertEquals(transactionHash(TIMESTAMPED_LEGACY_RAW_HEX), transaction.txHash);
		assertEquals(rawTransactionBytes.length, transaction.size);
		assertEquals(0x12345678, transaction.locktime);
		assertEquals(1, transaction.inputs.size());
		assertEquals(2, transaction.outputs.size());
		assertEquals(10_000L, transaction.outputs.get(0).value);
		assertEquals(P2PKH_SCRIPT, transaction.outputs.get(0).scriptPubKey);
	}

	@Test
	public void testSerializesLegacyTransaction() {
		BitcoinyTransactionData transaction = new BitcoinyTransactionData(1,
				Collections.singletonList(new BitcoinyTransactionData.Input(reverseHex(PREVIOUS_TX_HASH_WIRE), 2,
						HashCode.fromString("aabbcc").asBytes(), 0xfffffffeL)),
				List.of(
						new BitcoinyTransactionData.Output(10_000L, HashCode.fromString(P2PKH_SCRIPT).asBytes()),
						new BitcoinyTransactionData.Output(5L, HashCode.fromString(P2SH_SCRIPT).asBytes())),
				0x12345678L);

		assertEquals(LEGACY_RAW_HEX, HashCode.fromBytes(transaction.serialize()).toString());
		assertEquals(transactionHash(LEGACY_RAW_HEX), transaction.txHash());
	}

	@Test
	public void testSerializesTimestampedLegacyTransaction() {
		BitcoinyTransactionData transaction = new BitcoinyTransactionData(1, 0x11223344L,
				Collections.singletonList(new BitcoinyTransactionData.Input(reverseHex(PREVIOUS_TX_HASH_WIRE), 2,
						HashCode.fromString("aabbcc").asBytes(), 0xfffffffeL)),
				List.of(
						new BitcoinyTransactionData.Output(10_000L, HashCode.fromString(P2PKH_SCRIPT).asBytes()),
						new BitcoinyTransactionData.Output(5L, HashCode.fromString(P2SH_SCRIPT).asBytes())),
				0x12345678L);

		assertEquals(TIMESTAMPED_LEGACY_RAW_HEX, HashCode.fromBytes(transaction.serialize()).toString());
		assertEquals(transactionHash(TIMESTAMPED_LEGACY_RAW_HEX), transaction.txHash());
	}

	@Test
	public void testAdaptsParsedLegacyTransaction() {
		BitcoinyTransaction parsedTransaction = BitcoinyRawTransactionParser.parse(HashCode.fromString(LEGACY_RAW_HEX).asBytes());
		BitcoinyTransactionData transaction = BitcoinyTransactionData.fromParsedLegacy(1, parsedTransaction);

		assertEquals(LEGACY_RAW_HEX, HashCode.fromBytes(transaction.serialize()).toString());
		assertEquals(parsedTransaction.txHash, transaction.txHash());
	}

	@Test
	public void testSerializesLargeVariableLengthFields() {
		String largeInputScript = "11".repeat(253);
		String largeOutputScript = "22".repeat(253);
		BitcoinyTransactionData transaction = new BitcoinyTransactionData(2,
				Collections.singletonList(new BitcoinyTransactionData.Input(reverseHex(PREVIOUS_TX_HASH_WIRE), 1,
						HashCode.fromString(largeInputScript).asBytes(), BitcoinyTransactionData.NO_LOCKTIME_SEQUENCE)),
				Collections.singletonList(new BitcoinyTransactionData.Output(1L, HashCode.fromString(largeOutputScript).asBytes())),
				0L);

		String rawHex = HashCode.fromBytes(transaction.serialize()).toString();
		assertTrue(rawHex.contains("fdfd00" + largeInputScript));
		assertTrue(rawHex.contains("fdfd00" + largeOutputScript));

		BitcoinyTransaction parsedTransaction = BitcoinyRawTransactionParser.parse(transaction.serialize());
		assertEquals(largeInputScript, parsedTransaction.inputs.get(0).scriptSig);
		assertEquals(largeOutputScript, parsedTransaction.outputs.get(0).scriptPubKey);
		assertEquals(transaction.txHash(), parsedTransaction.txHash);
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

	@Test
	public void testRejectsInvalidTransactionData() {
		try {
			new BitcoinyTransactionData.Input("00", 0, new byte[0], BitcoinyTransactionData.NO_LOCKTIME_SEQUENCE);
			fail("Expected short transaction hash to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("previous transaction hash"));
		}

		try {
			new BitcoinyTransactionData.Input("xx".repeat(32), 0, new byte[0], BitcoinyTransactionData.NO_LOCKTIME_SEQUENCE);
			fail("Expected non-hex transaction hash to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("hexadecimal previous transaction hash"));
		}

		try {
			new BitcoinyTransactionData.Output(-1L, new byte[0]);
			fail("Expected negative output value to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("Negative output value"));
		}

		try {
			new BitcoinyTransactionData(1, Collections.emptyList(),
					Collections.singletonList(new BitcoinyTransactionData.Output(0L, new byte[0])), 0L);
			fail("Expected empty inputs to be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("no inputs"));
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
