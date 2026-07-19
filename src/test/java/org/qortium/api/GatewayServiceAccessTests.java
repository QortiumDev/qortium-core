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
				"GET /chat/*"
		}, true);
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
			ResourceConfig config = new ResourceConfig(TestGatewayResource.class, TestApiResource.class);
			GatewayService.registerPublicApiAccess(config);

			ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
			context.setContextPath("/");
			ServletHolder apiServlet = new ServletHolder(new ServletContainer(config));
			context.addServlet(apiServlet, "/*");

			this.server.addConnector(this.connector);
			this.server.setHandler(context);
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
	}
}
