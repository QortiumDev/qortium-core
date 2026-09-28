package org.qortium.api.resource;

import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.model.crosschain.PirateChainSendRequest;
import org.qortium.test.common.Common;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Proves the strict reader is the one Jersey actually uses for {@link PirateChainSendRequest}
 * bodies: it is discovered by the same package scan ApiService performs, and it wins over the
 * auto-discovered generic JSON providers (MOXy/Jackson) for this declared type, so the coercions
 * those providers perform can no longer reach the resource.
 */
public class PirateChainSendRequestReaderJerseyTests {

	@Before
	public void before() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testReaderIsRegisteredByPackageScanning() {
		ResourceConfig config = new ResourceConfig();
		config.packages("org.qortium.api.resource");
		assertTrue(config.getClasses().contains(PirateChainSendRequestReader.class));
	}

	@Test
	public void testStrictReaderWinsOverGenericJsonBindingForSendRequests() throws Exception {
		try (HandlerServer server = new HandlerServer()) {
			// The generic binding would have bound "2" here; the strict reader rejects the body.
			String array = server.post("/send-binding/echo", "{\"arrrAmount\":[1,2]}");
			assertStatus(400, array);
			assertTrue(array, array.contains("MALFORMED_BODY"));
			assertFalse(array, array.contains("\"arrrAmount\":\"2\""));

			String memoArray = server.post("/send-binding/echo", "{\"memo\":[\"first\",\"last\"]}");
			assertStatus(400, memoArray);

			String feeArray = server.post("/send-binding/echo", "{\"feePerByte\":[]}");
			assertStatus(400, feeArray);

			// The generic binding would have bound "1"; the strict reader keeps the lexical "1e0".
			String exponent = server.post("/send-binding/echo", "{\"arrrAmount\":1e0}");
			assertStatus(200, exponent);
			assertTrue(exponent, exponent.endsWith("amount=1e0|memo=null|fee=null"));

			String ordinary = server.post("/send-binding/echo", "{\"arrrAmount\":\"1.5\",\"memo\":\"hi\",\"feePerByte\":100}");
			assertStatus(200, ordinary);
			assertTrue(ordinary, ordinary.endsWith("amount=1.5|memo=hi|fee=100"));

			String empty = server.post("/send-binding/echo", "");
			assertStatus(200, empty);
			assertTrue(empty, empty.endsWith("null-request"));
		}
	}

	private static void assertStatus(int expectedStatus, String response) {
		String statusLine = response.substring(0, response.indexOf("\r\n"));
		assertEquals("unexpected status line: " + response, "HTTP/1.1 " + expectedStatus,
				statusLine.substring(0, Math.min(statusLine.length(), "HTTP/1.1 ".length() + 3)));
	}

	private static final class HandlerServer implements AutoCloseable {
		private final Server server = new Server();
		private final LocalConnector connector = new LocalConnector(this.server);

		private HandlerServer() throws Exception {
			ResourceConfig config = new ResourceConfig(SendBindingResource.class);
			config.registerClasses(ApiExceptionMapper.class, ApiRequestBodyInterceptor.class,
					PirateChainSendRequestReader.class);

			ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
			context.setContextPath("/");
			context.addServlet(new ServletHolder(new ServletContainer(config)), "/*");

			this.server.addConnector(this.connector);
			this.server.setHandler(context);
			this.server.start();
		}

		private String post(String path, String body) throws Exception {
			byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
			String request = "POST " + path + " HTTP/1.1\r\n"
					+ "Host: localhost\r\n"
					+ "Content-Type: application/json\r\n"
					+ "Content-Length: " + bodyBytes.length + "\r\n"
					+ "Connection: close\r\n"
					+ "\r\n"
					+ body;
			return this.connector.getResponse(request);
		}

		@Override
		public void close() throws Exception {
			this.server.stop();
		}
	}

	/** Same declared body type as CrossChainPirateChainResource.sendPirateChain, echoing what bound. */
	@Path("/send-binding")
	public static class SendBindingResource {
		@POST
		@Path("/echo")
		@Consumes(MediaType.APPLICATION_JSON)
		@Produces(MediaType.TEXT_PLAIN)
		public String echo(PirateChainSendRequest request) {
			if (request == null)
				return "null-request";
			return "amount=" + request.arrrAmount + "|memo=" + request.memo + "|fee=" + request.feePerByte;
		}
	}
}
