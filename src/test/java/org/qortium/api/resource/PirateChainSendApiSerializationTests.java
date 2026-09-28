package org.qortium.api.resource;

import org.eclipse.persistence.jaxb.rs.MOXyJsonProvider;
import org.junit.Test;
import org.qortium.api.ApiError;
import org.qortium.api.ApiException;
import org.qortium.api.model.crosschain.PirateChainSendContract;
import org.qortium.api.model.crosschain.PirateChainSendRequest;
import org.qortium.api.model.crosschain.PirateChainSendResult;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Wire-level proof of the send contract's JSON shapes. The REQUEST is read by the strict
 * {@link PirateChainSendRequestReader} (never by the generic MOXy binding, which coerces arrays,
 * exponents and nested values into plausible-looking scalars); the result/contract bodies are
 * written by MOXy and carry {@code sendProtocolVersion} as a bare number.
 */
public class PirateChainSendApiSerializationTests {

	private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];
	private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;

	private static MOXyJsonProvider provider() {
		MOXyJsonProvider provider = new MOXyJsonProvider();
		provider.setIncludeRoot(false);
		return provider;
	}

	private static String marshal(Object model) throws Exception {
		MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		provider().writeTo(model, model.getClass(), model.getClass(), NO_ANNOTATIONS, JSON, headers, output);
		return output.toString(StandardCharsets.UTF_8);
	}

	@SuppressWarnings("unchecked")
	private static <T> T unmarshal(String json, Class<T> type) throws Exception {
		MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
		ByteArrayInputStream input = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
		return (T) provider().readFrom((Class<Object>) type, type, NO_ANNOTATIONS, JSON, headers, input);
	}

	private static PirateChainSendRequest read(String json) throws Exception {
		MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
		ByteArrayInputStream input = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
		return new PirateChainSendRequestReader().readFrom(PirateChainSendRequest.class, PirateChainSendRequest.class,
				NO_ANNOTATIONS, JSON, headers, input);
	}

	private static String rejected(String json) {
		ApiException exception = assertThrows("expected rejection for " + json, ApiException.class, () -> read(json));
		assertEquals(400, exception.status);
		assertEquals(ApiError.INVALID_DATA.getCode(), exception.error);
		assertTrue(exception.message, exception.message.startsWith(PirateChainSendRequestReader.REJECTION_TOKEN + ": "));
		return exception.message;
	}

	@Test
	public void testStrictReaderAcceptsStringsAndKeepsNumberLexicalText() throws Exception {
		PirateChainSendRequest asString = read("{\"entropy58\":\"e\",\"receivingAddress\":\"zs1x\","
				+ "\"arrrAmount\":\"1.5\",\"memo\":\"hi\",\"idempotencyKey\":\"k\"}");
		assertEquals("e", asString.entropy58);
		assertEquals("zs1x", asString.receivingAddress);
		assertEquals("1.5", asString.arrrAmount);
		assertEquals("hi", asString.memo);
		assertEquals("k", asString.idempotencyKey);
		assertNull(asString.feePerByte);

		// Plain JSON numbers stay supported for the amount, but as the ORIGINAL text.
		assertEquals("1.5", read("{\"arrrAmount\":1.5}").arrrAmount);
		assertEquals("2", read("{\"arrrAmount\":2}").arrrAmount);
		assertEquals("1.50", read("{\"arrrAmount\":1.50}").arrrAmount);
		assertEquals("1e0", read("{\"arrrAmount\":1e0}").arrrAmount);
		assertEquals("1E8", read("{\"arrrAmount\":1E8}").arrrAmount);
		assertEquals("0.000000001", read("{\"arrrAmount\":0.000000001}").arrrAmount);
		assertEquals("99999999999999999999", read("{\"arrrAmount\":99999999999999999999}").arrrAmount);
		assertEquals("100", read("{\"feePerByte\":100}").feePerByte);
		assertEquals("0", read("{\"feePerByte\":0}").feePerByte);

		// JSON null is "absent"; an empty object binds nothing; an empty body is no request at all.
		PirateChainSendRequest nulls = read("{\"arrrAmount\":null,\"memo\":null,\"feePerByte\":null}");
		assertNull(nulls.arrrAmount);
		assertNull(nulls.memo);
		assertNull(nulls.feePerByte);
		assertNull(read("{}").arrrAmount);
		assertNull(read(""));
		assertNull(read("   \n"));

		// Memo text survives escapes and supplementary characters untouched.
		assertEquals("a\"b\\c\nd \uD83D\uDE00", read("{\"memo\":\"a\\\"b\\\\c\\nd \uD83D\uDE00\"}").memo);
	}

	@Test
	public void testStrictReaderRejectsEveryCoercionTheGenericBindingWouldPerform() {
		// The exact vectors the generic MOXy binding coerced into plausible scalars.
		assertTrue(rejected("{\"arrrAmount\":[1,2]}").contains("string or number"));
		assertTrue(rejected("{\"feePerByte\":[]}").contains("string or number"));
		assertTrue(rejected("{\"memo\":[\"first\",\"last\"]}").contains("JSON string"));
		// 1e0 is now bound as its lexical text and therefore rejected by the amount parser below.
		PirateChainSendRequest exponent = assertDoesNotThrow("{\"arrrAmount\":1e0}");
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL",
				CrossChainPirateChainResource.validateSendRequest(withValidFieldsExcept(exponent)).reason);

		// Everything else that is not a flat object of scalars.
		rejected("{\"arrrAmount\":{\"value\":1}}");
		rejected("{\"arrrAmount\":true}");
		rejected("{\"memo\":123}");
		rejected("{\"memo\":false}");
		rejected("{\"entropy58\":[\"e\"]}");
		rejected("{\"receivingAddress\":{}}");
		rejected("{\"idempotencyKey\":1}");
		rejected("{\"feePerByte\":{}}");
		assertTrue(rejected("{\"arrrAmount\":\"1\",\"arrrAmount\":\"2\"}").contains("duplicate"));
		assertTrue(rejected("{\"arrramount\":\"1\"}").contains("unknown field"));
		assertTrue(rejected("{\"extra\":1}").contains("unknown field"));
		assertTrue(rejected("[]").contains("JSON object"));
		assertTrue(rejected("\"text\"").contains("JSON object"));
		assertTrue(rejected("1").contains("JSON object"));
		assertTrue(rejected("null").contains("JSON object"));
		assertTrue(rejected("{\"arrrAmount\":\"1\"} {}").contains("trailing"));
		rejected("{\"arrrAmount\":\"1\"}]"); // Jackson reports the stray bracket as invalid JSON
		assertTrue(rejected("{\"arrrAmount\":\"1\"").contains("invalid JSON"));
		assertTrue(rejected("{arrrAmount:\"1\"}").contains("invalid JSON"));
		assertTrue(rejected("{\"arrrAmount\":'1'}").contains("invalid JSON"));
		assertTrue(rejected("{\"arrrAmount\":NaN}").contains("invalid JSON"));
		assertTrue(rejected("{\"arrrAmount\":+1}").contains("invalid JSON"));
		assertTrue(rejected("{\"arrrAmount\":01}").contains("invalid JSON"));
		assertTrue(rejected("{\"arrrAmount\":\"1\",}").contains("invalid JSON"));
		assertTrue(rejected("{\"memo\":\"" + "x".repeat(PirateChainSendRequestReader.MAX_BODY_BYTES) + "\"}")
				.contains("exceeds"));

		// Rejection messages never echo the body.
		String echo = rejected("{\"memo\":[\"SECRET-MEMO-CONTENT\"]}");
		assertFalse(echo.contains("SECRET-MEMO-CONTENT"));
	}

	@Test
	public void testReviewerVectorsEndToEndThroughReaderAndValidation() throws Exception {
		// Complete request bodies, each carrying one malformed field, driven through the real reader
		// and then the real validator: none may reach the wallet with a selected value.
		String valid = "\"entropy58\":\"5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV\","
				+ "\"receivingAddress\":\"" + VALID_SAPLING + "\",\"idempotencyKey\":\"123e4567-e89b-12d3-a456-426614174000\"";

		assertNull(CrossChainPirateChainResource.validateSendRequest(read("{" + valid + ",\"arrrAmount\":\"1.5\",\"memo\":\"hi\"}")));
		assertNull(CrossChainPirateChainResource.validateSendRequest(read("{" + valid + ",\"arrrAmount\":1.5}")));

		rejected("{" + valid + ",\"arrrAmount\":[1,2]}");
		rejected("{" + valid + ",\"arrrAmount\":\"1.5\",\"feePerByte\":[]}");
		rejected("{" + valid + ",\"arrrAmount\":\"1.5\",\"memo\":[\"first\",\"last\"]}");

		CrossChainPirateChainResource.SendRequestRejection exponent =
				CrossChainPirateChainResource.validateSendRequest(read("{" + valid + ",\"arrrAmount\":1e0}"));
		assertNotNull(exponent);
		assertEquals(ApiError.INVALID_CRITERIA, exponent.error);
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", exponent.reason);

		CrossChainPirateChainResource.SendRequestRejection fee =
				CrossChainPirateChainResource.validateSendRequest(read("{" + valid + ",\"arrrAmount\":\"1.5\",\"feePerByte\":0}"));
		assertNotNull(fee);
		assertEquals("FEE_PER_BYTE_UNSUPPORTED", fee.reason);

		CrossChainPirateChainResource.SendRequestRejection precision =
				CrossChainPirateChainResource.validateSendRequest(read("{" + valid + ",\"arrrAmount\":1.000000001}"));
		assertNotNull(precision);
		assertEquals("AMOUNT_NOT_PLAIN_DECIMAL", precision.reason);

		CrossChainPirateChainResource.SendRequestRejection missing =
				CrossChainPirateChainResource.validateSendRequest(read(""));
		assertNotNull(missing);
		assertEquals("MISSING_BODY", missing.reason);
	}

	private static final String VALID_SAPLING;
	static {
		byte[] payload = new byte[43];
		for (int i = 0; i < payload.length; i++)
			payload[i] = (byte) (i * 11 + 5);
		VALID_SAPLING = org.bitcoinj.base.Bech32.encodeBytes(org.bitcoinj.base.Bech32.Encoding.BECH32, "zs", payload);
	}

	private static PirateChainSendRequest assertDoesNotThrow(String json) {
		try {
			return read(json);
		} catch (Exception e) {
			throw new AssertionError("unexpected rejection for " + json + ": " + e.getMessage(), e);
		}
	}

	private static PirateChainSendRequest withValidFieldsExcept(PirateChainSendRequest partial) {
		if (partial.entropy58 == null) partial.entropy58 = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV";
		if (partial.receivingAddress == null) partial.receivingAddress = VALID_SAPLING;
		if (partial.idempotencyKey == null) partial.idempotencyKey = "123e4567-e89b-12d3-a456-426614174000";
		return partial;
	}

	@Test
	public void testSendResultWireShape() throws Exception {
		String json = marshal(new PirateChainSendResult("deadbeef", "10000", "FIXED", 1));
		assertTrue(json, json.contains("\"txid\":\"deadbeef\""));
		assertTrue(json, json.contains("\"feeAtomic\":\"10000\""));
		assertTrue(json, json.contains("\"feePolicy\":\"FIXED\""));
		assertTrue("sendProtocolVersion must be a bare number: " + json, json.contains("\"sendProtocolVersion\":1"));
		assertFalse(json, json.contains("\"sendProtocolVersion\":\""));
	}

	@Test
	public void testSendContractWireShape() throws Exception {
		String json = marshal(new PirateChainSendContract(10_000L));
		assertTrue(json, json.contains("\"sendProtocolVersion\":1"));
		assertTrue(json, json.contains("\"feePolicy\":\"FIXED\""));
		assertTrue(json, json.contains("\"feeAtomic\":\"10000\""));
		assertTrue(json, json.contains("\"amountDecimals\":8"));
		assertTrue(json, json.contains("\"maxMemoBytes\":512"));
		assertTrue(json, json.contains("\"recipientAddressTypes\":[\"sapling\"]"));
	}
}
