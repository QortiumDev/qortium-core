package org.qortium.api;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.gateway.resource.PublicQdnResource;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

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
				"GET /chat/*",
				"POST /polls/public/vote",
				"POST /transactions/process",
				"POST /arbitrary/public/*"
		}, true);
		FieldUtils.writeField(this.settings, "publicApiWriteMaxBodySize", 16L, true);
		FieldUtils.writeField(this.settings, "publicQdnPublishMaxSize", 16L, true);
	}

	@Test
	public void testGatewayQdnServingRoutesRemainPublic() throws Exception {
		try (HandlerServer server = new HandlerServer()) {
			assertAllowed(server, "GET /WEBSITE/somename HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /somename HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /APP/name/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /somename/nested/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
		}
	}

	@Test
	public void testGatewayAllowlistStillControlsApiRoutes() throws Exception {
		try (HandlerServer server = new HandlerServer()) {
			assertAllowed(server, "GET /render/APP/Boards HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /render/WEBSITE/Example/index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /apps/q-apps.js HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /apps/q-apps-gateway.js HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /arbitrary/APP/Boards/default HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /admin/status HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /names/search HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertAllowed(server, "GET /chat/messages HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertForbidden(server, "GET /peers HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertForbidden(server, "GET /admin/settings HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertForbidden(server, "GET /wallet/balance HTTP/1.1\r\nHost: localhost\r\n\r\n");
			assertForbidden(server, "POST /render/authorize/APP/Boards/default HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n");
			assertForbidden(server, "POST /arbitrary/APP/Boards/base64 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n");
			// Gateway protection runs before Jersey authorization. An oversized route that is not public
			// must still reach the access filter and return 403 rather than consume protection work/return 413.
			assertForbidden(server, "POST /render/authorize/APP/Boards/default HTTP/1.1\r\nHost: localhost\r\nContent-Length: 17\r\n\r\n");
		}
	}

	@Test
	public void testGatewayPublicWritesUseTheSameProtectionBoundary() throws Exception {
		try (HandlerServer server = new HandlerServer()) {
			assertPayloadTooLarge(server, "POST /polls/public/vote HTTP/1.1\r\nHost: localhost\r\nContent-Length: 17\r\n\r\n");
			assertPayloadTooLarge(server, "POST /transactions/process HTTP/1.1\r\nHost: localhost\r\nContent-Length: 17\r\n\r\n");
			assertPayloadTooLarge(server, "POST /arbitrary/public/APP/name/base64 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2000000\r\n\r\n");
			assertAllowed(server, "POST /polls/public/vote HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n");
			assertAllowed(server, "POST /arbitrary/public/APP/name/base64 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n");
			assertAllowed(server, "GET /APP/name/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
		}
	}

	private static void assertAllowed(HandlerServer server, String request) throws Exception {
		assertTrue(server.request(request).startsWith("HTTP/1.1 204"));
	}

	private static void assertForbidden(HandlerServer server, String request) throws Exception {
		assertTrue(server.request(request).startsWith("HTTP/1.1 403"));
	}

	private static void assertPayloadTooLarge(HandlerServer server, String request) throws Exception {
		assertTrue(server.request(request).startsWith("HTTP/1.1 413"));
	}

	private static final class HandlerServer implements AutoCloseable {
		private final Server server = new Server();
		private final LocalConnector connector = new LocalConnector(this.server);

		private HandlerServer() throws Exception {
			ResourceConfig config = new ResourceConfig(TestGatewayResource.class, TestApiResource.class);
			GatewayService.registerPublicApiAccess(config);

			ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
			context.setContextPath("/");
			ServletHolder apiServlet = new ServletHolder(new ServletContainer(config));
			context.addServlet(apiServlet, "/*");

			PublicApiProtectionHandler protection = new PublicApiProtectionHandler(() -> 0L);
			protection.setHandler(context);
			this.server.addConnector(this.connector);
			this.server.setHandler(protection);
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

	@PublicQdnResource
	@Path("/")
	public static class TestGatewayResource {
		@GET
		@Path("{path:.*}")
		public Response getPath() {
			return Response.noContent().build();
		}
	}

	@Path("/")
	public static class TestApiResource {
		@GET
		@Path("render/{path:.*}")
		public Response getRender() {
			return Response.noContent().build();
		}

		@GET
		@Path("apps/{path:.*}")
		public Response getApps() {
			return Response.noContent().build();
		}

		@GET
		@Path("arbitrary/{path:.*}")
		public Response getArbitrary() {
			return Response.noContent().build();
		}

		@GET
		@Path("admin/status")
		public Response getAdminStatus() {
			return Response.noContent().build();
		}

		@GET
		@Path("names/{path:.*}")
		public Response getNames() {
			return Response.noContent().build();
		}

		@GET
		@Path("chat/{path:.*}")
		public Response getChat() {
			return Response.noContent().build();
		}

		@GET
		@Path("peers")
		public Response getPeers() {
			return Response.noContent().build();
		}

		@GET
		@Path("admin/settings")
		public Response getAdminSettings() {
			return Response.noContent().build();
		}

		@GET
		@Path("wallet/balance")
		public Response getWalletBalance() {
			return Response.noContent().build();
		}

		@POST
		@Path("render/{path:.*}")
		public Response postRender() {
			return Response.noContent().build();
		}

		@POST
		@Path("arbitrary/{path:.*}")
		public Response postArbitrary() {
			return Response.noContent().build();
		}

		@POST
		@Path("polls/public/vote")
		public Response postPollVote() {
			return Response.noContent().build();
		}

		@POST
		@Path("transactions/process")
		public Response postTransaction() {
			return Response.noContent().build();
		}
	}
}
