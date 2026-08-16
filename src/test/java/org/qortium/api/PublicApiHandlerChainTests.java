package org.qortium.api;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.Before;
import org.junit.Test;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PublicApiHandlerChainTests extends Common {

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		Settings settings = Settings.getInstance();
		FieldUtils.writeField(settings, "apiWhitelistEnabled", true, true);
		FieldUtils.writeField(settings, "apiWhitelist", new String[0], true);
		FieldUtils.writeField(settings, "publicApiWhitelistEnabled", true, true);
		FieldUtils.writeField(settings, "publicApiWhitelist", new String[] {"0.0.0.0/0", "::/0"}, true);
		FieldUtils.writeField(settings, "publicApiWriteMaxBodySize", 16L, true);
		FieldUtils.writeField(settings, "publicQdnPublishMaxSize", 16L, true);
		FieldUtils.writeField(settings, "publicApiPaths", new String[] {
				"POST /polls/public/vote",
				"POST /transactions/process",
				"POST /arbitrary/public/*",
				"POST /future/*"
		}, true);
	}

	@Test
	public void testAccessAndProtectionUseOneRawRoutePolicy() throws Exception {
		try (HandlerServer server = new HandlerServer()) {
			assertStatus(server, request("POST", "/polls/public/vote", 17), 413);
			assertStatus(server, request("POST", "/transactions/process", 17), 413);
			assertStatus(server, request("POST", "/arbitrary/public/APP/name/base64", 2_000_000), 413);
			assertStatus(server, request("POST", "/future/build", 17), 413);

			assertStatus(server, request("POST", "/polls/public/vote", 0), 204);
			assertStatus(server, request("POST", "/arbitrary/public", 0), 204);
			assertStatus(server, request("POST", "/polls/public/vote/", 0), 403);
			// Jetty rejects ambiguous repeated separators before the route reaches either handler.
			assertStatus(server, request("POST", "/polls/public//vote", 17), 400);
			assertStatus(server, request("DELETE", "/polls/public/vote", 0), 403);

			String encodedResponse = server.request(request("POST", "/polls/public%2Fvote", 0));
			assertFalse("Encoded path must never reach the terminal handler", encodedResponse.startsWith("HTTP/1.1 204"));
		}
	}

	private static String request(String method, String path, long contentLength) {
		return method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
				+ contentLength + "\r\n\r\n";
	}

	private static void assertStatus(HandlerServer server, String request, int expectedStatus) throws Exception {
		String response = server.request(request);
		assertTrue("Expected HTTP " + expectedStatus + " but received:\n" + response,
				response.startsWith("HTTP/1.1 " + expectedStatus));
	}

	private static final class HandlerServer implements AutoCloseable {
		private final Server server = new Server();
		private final LocalConnector connector = new LocalConnector(this.server);

		private HandlerServer() throws Exception {
			PublicApiAccessHandler access = new PublicApiAccessHandler();
			PublicApiProtectionHandler protection = new PublicApiProtectionHandler(() -> 0L);
			access.setHandler(protection);
			protection.setHandler(new Handler.Abstract() {
				@Override
				public boolean handle(Request request, Response response, Callback callback) {
					response.setStatus(204);
					callback.succeeded();
					return true;
				}
			});
			this.server.addConnector(this.connector);
			this.server.setHandler(access);
			this.server.start();
		}

		private String request(String rawRequest) throws Exception {
			return this.connector.getResponse(rawRequest);
		}

		@Override
		public void close() throws Exception {
			this.server.stop();
		}
	}
}
