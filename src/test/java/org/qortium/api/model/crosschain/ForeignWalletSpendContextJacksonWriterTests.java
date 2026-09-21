package org.qortium.api.model.crosschain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Confirms {@link ForeignWalletSpendContextJacksonWriter} emits {@code previousTransactions} as a
 * plain JSON object keyed by tx hash (what Qortium Home's foreign-wallet-spend-context.ts expects),
 * instead of MOXy's default generic-map shape ({@code {"entry":[{"key":...,"value":...}]}}), while
 * leaving every other field's JSON shape unchanged.
 */
public class ForeignWalletSpendContextJacksonWriterTests {

	private static final String TX_HASH_1 = "aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa1";
	private static final String TX_HASH_2 = "bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb2";
	private static final String TX_RAW_1 = "0100000001abcdef";
	private static final String TX_RAW_2 = "0100000002fedcba";

	private final ForeignWalletSpendContextJacksonWriter writer = new ForeignWalletSpendContextJacksonWriter();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	public void isWriteableAcceptsForeignWalletSpendContext() {
		assertTrue(writer.isWriteable(ForeignWalletSpendContext.class, ForeignWalletSpendContext.class,
				new java.lang.annotation.Annotation[0], null));
	}

	@Test
	public void testPreviousTransactionsSerializeAsPlainObjectKeyedByHash() throws Exception {
		Map<String, String> previousTransactions = new LinkedHashMap<>();
		previousTransactions.put(TX_HASH_1, TX_RAW_1);
		previousTransactions.put(TX_HASH_2, TX_RAW_2);

		ForeignWalletSpendContext spendContext = sampleSpendContext(previousTransactions);

		JsonNode root = writeToJson(spendContext);

		// previousTransactions must be a plain object {"<hash>":"<rawhex>", ...}, NOT MOXy's
		// generic-map shape {"entry":[{"key":...,"value":...}]}.
		JsonNode previousTransactionsNode = root.get("previousTransactions");
		assertTrue("previousTransactions should be a JSON object", previousTransactionsNode.isObject());
		assertFalse("previousTransactions must not have a MOXy-style 'entry' wrapper",
				previousTransactionsNode.has("entry"));
		assertEquals(2, previousTransactionsNode.size());
		assertEquals(TX_RAW_1, previousTransactionsNode.get(TX_HASH_1).asText());
		assertEquals(TX_RAW_2, previousTransactionsNode.get(TX_HASH_2).asText());

		assertOtherFieldsUnchanged(root);
	}

	@Test
	public void testEmptyPreviousTransactionsSerializesAsEmptyObject() throws Exception {
		ForeignWalletSpendContext spendContext = sampleSpendContext(Collections.emptyMap());

		JsonNode root = writeToJson(spendContext);

		JsonNode previousTransactionsNode = root.get("previousTransactions");
		assertTrue("previousTransactions should be a JSON object", previousTransactionsNode.isObject());
		assertFalse("previousTransactions must not have a MOXy-style 'entry' wrapper",
				previousTransactionsNode.has("entry"));
		assertEquals(0, previousTransactionsNode.size());

		assertOtherFieldsUnchanged(root);
	}

	private void assertOtherFieldsUnchanged(JsonNode root) {
		assertEquals(1, root.get("version").asInt());
		assertTrue("version should serialize as a JSON number", root.get("version").isIntegralNumber());
		assertEquals("BITCOIN", root.get("blockchain").asText());
		assertEquals("BTC", root.get("currencyCode").asText());
		assertEquals("MAIN", root.get("activeNetwork").asText());
		assertEquals("000000000019d6689c085ae165831e93", root.get("chainId").asText());
		assertEquals(850000, root.get("tipHeight").asInt());
		assertTrue("confirmedOnly should serialize as a JSON boolean", root.get("confirmedOnly").isBoolean());
		assertTrue(root.get("confirmedOnly").asBoolean());
		assertEquals("LEGACY", root.get("transactionFormat").asText());
		assertEquals(1, root.get("transactionVersion").asInt());
		assertEquals(1, root.get("sighashType").asInt());
		assertTrue("sequence should serialize as a JSON number", root.get("sequence").isIntegralNumber());
		assertEquals(4294967295L, root.get("sequence").asLong());
		assertEquals(0L, root.get("lockTime").asLong());
		assertEquals("546", root.get("minimumNonDustOutput").asText());
		assertTrue("minimumNonDustOutput stays a JSON string", root.get("minimumNonDustOutput").isTextual());
		assertEquals("10", root.get("recommendedFeePerByte").asText());

		JsonNode utxosNode = root.get("utxos");
		assertTrue(utxosNode.isArray());
		assertEquals(1, utxosNode.size());
		JsonNode utxoNode = utxosNode.get(0);
		assertEquals("1BitcoinAddress", utxoNode.get("address").asText());
		assertEquals(1, utxoNode.get("path").size());
		assertEquals("100000", utxoNode.get("value").asText());
	}

	private JsonNode writeToJson(ForeignWalletSpendContext spendContext) throws Exception {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		writer.writeTo(spendContext, ForeignWalletSpendContext.class, ForeignWalletSpendContext.class,
				new java.lang.annotation.Annotation[0], null, null, outputStream);
		return objectMapper.readTree(outputStream.toByteArray());
	}

	private ForeignWalletSpendContext sampleSpendContext(Map<String, String> previousTransactions) {
		ForeignWalletSpendContextUtxo utxo = new ForeignWalletSpendContextUtxo();
		setField(utxo, "address", "1BitcoinAddress");
		setField(utxo, "height", 849000);
		setField(utxo, "path", List.of(0));
		setField(utxo, "pathAsString", "0");
		setField(utxo, "scriptPubKeyHex", "76a914abcdef");
		setField(utxo, "txHash", TX_HASH_1);
		setField(utxo, "outputIndex", 0);
		setField(utxo, "value", "100000");

		return new ForeignWalletSpendContext(
				1,
				"BITCOIN",
				"BTC",
				"MAIN",
				"000000000019d6689c085ae165831e93",
				850000,
				true,
				"LEGACY",
				1,
				1,
				4294967295L,
				0L,
				"546",
				"10",
				previousTransactions,
				List.of(utxo));
	}

	/** {@link ForeignWalletSpendContextUtxo} only exposes a {@code WalletSpendContextUtxo}-arg constructor. */
	private static void setField(Object target, String fieldName, Object value) {
		try {
			java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}
}
