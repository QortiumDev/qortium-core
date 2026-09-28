package org.qortium.api.resource;

import org.eclipse.persistence.jaxb.rs.MOXyJsonProvider;
import org.junit.Test;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Wire-level proof of the send contract's JSON shapes through the real MOXy provider: the request's
 * all-String fields accept both JSON strings and JSON numbers for the amount (so a client that
 * sends {@code 1.5} instead of {@code "1.5"} still reaches the typed amount validation), and the
 * result/contract bodies carry {@code sendProtocolVersion} as a bare number.
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

	@Test
	public void testSendRequestAcceptsStringAndNumberAmountsIntoTheStringField() throws Exception {
		PirateChainSendRequest asString = unmarshal("{\"entropy58\":\"e\",\"receivingAddress\":\"zs1x\","
				+ "\"arrrAmount\":\"1.5\",\"memo\":\"hi\",\"idempotencyKey\":\"k\"}", PirateChainSendRequest.class);
		assertEquals("e", asString.entropy58);
		assertEquals("zs1x", asString.receivingAddress);
		assertEquals("1.5", asString.arrrAmount);
		assertEquals("hi", asString.memo);
		assertEquals("k", asString.idempotencyKey);
		assertNull(asString.feePerByte);

		PirateChainSendRequest asNumber = unmarshal("{\"arrrAmount\":1.5}", PirateChainSendRequest.class);
		assertEquals("1.5", asNumber.arrrAmount);

		PirateChainSendRequest asInteger = unmarshal("{\"arrrAmount\":2}", PirateChainSendRequest.class);
		assertEquals("2", asInteger.arrrAmount);

		// A numeric feePerByte still lands in the String field, so the resource's "any non-null value
		// is rejected" rule sees it.
		PirateChainSendRequest withFee = unmarshal("{\"feePerByte\":100}", PirateChainSendRequest.class);
		assertEquals("100", withFee.feePerByte);

		PirateChainSendRequest empty = unmarshal("{}", PirateChainSendRequest.class);
		assertNull(empty.arrrAmount);
		assertNull(empty.idempotencyKey);
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
