package org.qortium.crosschain;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

public class ElectrumXPushClientTests {

	@Test
	@SuppressWarnings("unchecked")
	public void testScripthashNotificationIsDispatched() {
		AtomicReference<String> receivedMethod = new AtomicReference<>();
		AtomicReference<List<?>> receivedParams = new AtomicReference<>();
		ElectrumXPushClient client = new ElectrumXPushClient("BTC", List::of, new ElectrumXPushClient.Listener() {
			@Override
			public void onConnected() {
			}

			@Override
			public void onNotification(String method, List<?> params) {
				receivedMethod.set(method);
				receivedParams.set(params);
			}

			@Override
			public void onDisconnected() {
			}
		});

		JSONArray params = new JSONArray();
		params.add("scripthash");
		params.add("checkpoint");
		JSONObject message = new JSONObject();
		message.put("method", "blockchain.scripthash.subscribe");
		message.put("params", params);

		client.handleMessage(message);

		assertEquals("blockchain.scripthash.subscribe", receivedMethod.get());
		assertEquals(List.of("scripthash", "checkpoint"), receivedParams.get());
	}

	@Test
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void testResponseIsCorrelatedByRequestId() throws Exception {
		ElectrumXPushClient client = new ElectrumXPushClient("BTC", List::of, noOpListener());
		Field pendingRequestsField = ElectrumXPushClient.class.getDeclaredField("pendingRequests");
		pendingRequestsField.setAccessible(true);
		Map<Long, CompletableFuture<Object>> pendingRequests =
				(Map<Long, CompletableFuture<Object>>) pendingRequestsField.get(client);
		CompletableFuture<Object> response = new CompletableFuture<>();
		pendingRequests.put(42L, response);

		JSONObject message = new JSONObject();
		message.put("id", 42L);
		message.put("result", "matched");

		client.handleMessage(message);

		assertEquals("matched", response.get());
	}

	@Test
	public void testBoundedReaderAcceptsLineAtLimit() throws Exception {
		assertEquals("test", ElectrumXPushClient.readBoundedLine(new StringReader("test\n"), 4));
	}

	@Test(expected = IOException.class)
	public void testBoundedReaderRejectsOversizedLine() throws Exception {
		ElectrumXPushClient.readBoundedLine(new StringReader("oversized\n"), 8);
	}

	@Test(expected = IOException.class)
	public void testJsonNestingLimitRejectsMessageBeforeParsing() throws Exception {
		String json = "[".repeat(ElectrumXPushClient.MAX_JSON_NESTING_DEPTH + 1)
				+ "]".repeat(ElectrumXPushClient.MAX_JSON_NESTING_DEPTH + 1);
		ElectrumXPushClient.validateJsonStructure(json);
	}

	private static ElectrumXPushClient.Listener noOpListener() {
		return new ElectrumXPushClient.Listener() {
			@Override
			public void onConnected() {
			}

			@Override
			public void onNotification(String method, List<?> params) {
			}

			@Override
			public void onDisconnected() {
			}
		};
	}
}
