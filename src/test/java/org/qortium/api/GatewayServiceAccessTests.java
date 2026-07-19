package org.qortium.api;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.eclipse.jetty.io.Content;
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

import static org.junit.Assert.assertTrue;

public class GatewayServiceAccessTests extends Common {

	private Settings settings;

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		this.settings = Settings.getInstance();

		// LocalConnector requests originate locally, so remove the trusted API
		// whitelist and exercise the public gateway boundary instead.
		FieldUtils.writeField(this.settings, "apiWhitelistEnabled", true, true);
		FieldUtils.writeField(this.settings, "apiWhitelist", new String[0], true);
		FieldUtils.writeField(this.settings, "publicApiWhitelistEnabled", true, true);
		FieldUtils.writeField(this.settings, "publicApiWhitelist",
				new String[] {"0.0.0.0/0", "::/0"}, true);
		FieldUtils.writeField(this.settings, "publicApiPaths", new String[] {
				"GET /admin/status",
				"GET /arbitrary/*",
				"GET /render/*",
				"GET /apps/*",
				"GET /names/*",
				"GET /chat/*"
		}, true);
	}

	@Test
	public void testGatewayUsesPublicApiAccessHandlerForRequiredReads() throws Exception {
		try (HandlerServer server = new HandlerServer()) {
			assertAllowed(server, "GET /render/APP/Boards HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /render/WEBSITE/Example/index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /apps/q-apps.js HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /apps/q-apps-gateway.js HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /arbitrary/APP/Boards/default HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /admin/status HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /names/search HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /chat/messages HTTP/1.1\r\nHost: localhost\r\n\r\n");
		}
	}

	@Test
	public void testGatewayRejectsUnlistedAndWriteRoutes() throws Exception {
		try (HandlerServer server = new HandlerServer()) {
			assertForbidden(server, "GET /peers HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertForbidden(server, "GET /admin/settings HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertForbidden(server, "GET /wallet/balance HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertForbidden(server, "POST /render/authorize/APP/Boards/default HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n");
			assertForbidden(server, "POST /arbitrary/APP/Boards/base64 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n");
		}
	}

	private static void assertAllowed(HandlerServer server, String request) throws Exception {
		assertTrue(server.request(request).startsWith("HTTP/1.1 204"));
	}

	private static void assertForbidden(HandlerServer server, String request) throws Exception {
		assertTrue(server.request(request).startsWith("HTTP/1.1 403"));
	}

	private static final class HandlerServer implements AutoCloseable {
		private final Server server = new Server();
		private final LocalConnector connector = new LocalConnector(this.server);

		private HandlerServer() throws Exception {
			Handler downstream = new Handler.Abstract() {
				@Override
				public boolean handle(Request request, Response response, Callback callback) {
					response.setStatus(204);
					Content.Sink.write(response, true, "", callback);
					return true;
				}
			};

			this.server.addConnector(this.connector);
			this.server.setHandler(GatewayService.wrapWithPublicApiAccess(downstream));
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
