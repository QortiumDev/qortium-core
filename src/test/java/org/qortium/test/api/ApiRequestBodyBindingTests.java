package org.qortium.test.api;

import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.persistence.exceptions.DatabaseException;
import org.eclipse.persistence.exceptions.DescriptorException;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.junit.Before;
import org.junit.Test;
import org.qortium.api.resource.ApiExceptionMapper;
import org.qortium.api.resource.ApiRequestBodyInterceptor;
import org.qortium.data.transaction.PaymentTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.test.common.Common;

import javax.annotation.Priority;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.ReaderInterceptor;
import javax.ws.rs.ext.ReaderInterceptorContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end checks that a malformed POST body produces a plain 400 rather than Jetty's bare HTML 500.
 * <p>
 * These drive a real Jersey/MOXy stack over a Jetty {@link LocalConnector}, because the failures being
 * guarded here happen inside Jersey's request-binding machinery and cannot be reproduced by calling a
 * resource method directly.
 */
public class ApiRequestBodyBindingTests extends Common {

	private static final String VALID_PAYMENT_BODY =
			"{\"type\":\"PAYMENT\",\"timestamp\":1,\"txGroupId\":0,\"recipient\":\"QgV4s3xnzLhVBEJxcYui4u4q11yhUHsd9v\"}";

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testInterceptorIsRegisteredByPackageScanning() {
		// ApiService/GatewayService/DomainMapService/DevProxyService all register providers by scanning
		// this package, so landing the class in it is the whole wiring. Prove that rather than assume it.
		ResourceConfig config = new ResourceConfig();
		config.packages("org.qortium.api.resource");

		assertTrue("ApiRequestBodyInterceptor must be discovered by package scanning",
				config.getClasses().contains(ApiRequestBodyInterceptor.class));
		assertTrue("ApiExceptionMapper is the reference registration for this package",
				config.getClasses().contains(ApiExceptionMapper.class));
	}

	/** Case (b): MOXy throws a bare DescriptorException with no cause chain. */
	@Test
	public void testUnknownTransactionTypeReturnsBadRequest() throws Exception {
		try (HandlerServer server = new HandlerServer()) {
			String response = server.post("/test-binding/transaction",
					"{\"type\":\"NOT_A_REAL_TRANSACTION_TYPE\",\"timestamp\":1}");

			assertBadRequestBody(response);
		}
	}

	/** Case (c): the body binds cleanly to a sibling subclass, and Jersey used to blow up at dispatch. */
	@Test
	public void testWrongButRealTransactionTypeReturnsBadRequest() throws Exception {
		try (HandlerServer server = new HandlerServer()) {
			String response = server.post("/test-binding/payment",
					"{\"type\":\"REGISTER_NAME\",\"timestamp\":1,\"name\":\"a-name\"}");

			assertBadRequestBody(response);
		}
	}

	@Test
	public void testJsonArrayBodyReturnsBadRequest() throws Exception {
		try (HandlerServer server = new HandlerServer()) {
			String response = server.post("/test-binding/transaction", "[1,2,3]");

			assertBadRequestBody(response);
		}
	}

	/** Already worked before this fix, via ApiExceptionMapper's JAXB branch. Guard against regressing it. */
	@Test
	public void testInvalidJsonStillReturnsBadRequest() throws Exception {
		try (HandlerServer server = new HandlerServer()) {
			String response = server.post("/test-binding/transaction", "{ this is not json");

			assertStatus(400, response);
			assertTrue("expected the existing invalid-body message, got:\n" + response,
					response.contains("Invalid request body"));
		}
	}

	@Test
	public void testValidBodyStillSucceeds() throws Exception {
		try (HandlerServer server = new HandlerServer()) {
			String abstractResponse = server.post("/test-binding/transaction", VALID_PAYMENT_BODY);
			assertStatus(200, abstractResponse);
			assertTrue("valid body must reach the resource method, got:\n" + abstractResponse,
					abstractResponse.contains(PaymentTransactionData.class.getSimpleName()));

			String concreteResponse = server.post("/test-binding/payment", VALID_PAYMENT_BODY);
			assertStatus(200, concreteResponse);
			assertTrue("valid body must reach the resource method, got:\n" + concreteResponse,
					concreteResponse.contains(PaymentTransactionData.class.getSimpleName()));
		}
	}

	/**
	 * The regression this fix must never cause: the interceptor catches EclipseLink failures only around
	 * unmarshalling, so a server fault of the very same type raised by resource code must still be a 500.
	 * If this ever reports 400, a genuine server fault is being blamed on the caller's request body.
	 */
	@Test
	public void testServerSideEclipseLinkFaultsRemainServerErrors() throws Exception {
		try (HandlerServer server = new HandlerServer()) {
			assertStatus(500, server.post("/test-binding/descriptor-fault", VALID_PAYMENT_BODY));
			assertStatus(500, server.post("/test-binding/database-fault", VALID_PAYMENT_BODY));
		}
	}

	/** A primitive entity parameter must not be mistaken for a wrongly-bound body. */
	@Test
	public void testPrimitiveEntityParamIsNotRejected() throws Exception {
		try (HandlerServer server = new HandlerServer()) {
			String response = server.postText("/test-binding/primitive", "42");

			assertStatus(200, response);
			assertTrue("primitive body must reach the resource method, got:\n" + response,
					response.contains("got 42"));
		}
	}

	/**
	 * The catch around {@code proceed()} must stay as narrow as the class's own safety argument claims.
	 * <p>
	 * {@link #testServerSideEclipseLinkFaultsRemainServerErrors} cannot cover this: those faults are raised
	 * from a resource method body, which is outside {@code proceed()} altogether, so widening the catch to
	 * {@code RuntimeException} leaves that test green. Without this check nothing distinguishes catching
	 * {@link org.eclipse.persistence.exceptions.EclipseLinkException} from catching every runtime failure,
	 * and a later edit could quietly start blaming the caller's body for an unrelated server fault raised
	 * while reading it.
	 */
	@Test
	public void testNonBindingRuntimeFaultDuringReadIsNotBlamedOnTheBody() throws Exception {
		try (HandlerServer server = new HandlerServer(NonBindingFaultInterceptor.class)) {
			assertStatus(500, server.post("/test-binding/payment", VALID_PAYMENT_BODY));
		}
	}

	/**
	 * Runs <i>inside</i> {@link ApiRequestBodyInterceptor}'s {@code proceed()} - a higher priority value
	 * means later in the reader-interceptor chain - and fails the way a non-binding defect would.
	 */
	@Priority(6000)
	public static class NonBindingFaultInterceptor implements ReaderInterceptor {
		@Override
		public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
			throw new IllegalStateException("not a binding failure");
		}
	}

	private static void assertBadRequestBody(String response) {
		assertStatus(400, response);
		assertTrue("expected \"Invalid request body\", got:\n" + response,
				response.contains("Invalid request body"));
	}

	private static void assertStatus(int expectedStatus, String response) {
		String statusLine = response.substring(0, response.indexOf("\r\n"));
		assertEquals("unexpected status line", "HTTP/1.1 " + expectedStatus,
				statusLine.substring(0, Math.min(statusLine.length(), "HTTP/1.1 ".length() + 3)));
	}

	private static final class HandlerServer implements AutoCloseable {
		private final Server server = new Server();
		private final LocalConnector connector = new LocalConnector(this.server);

		private HandlerServer(Class<?>... extraProviders) throws Exception {
			// Registers the same two providers ApiService picks up by scanning org.qortium.api.resource
			// (see testInterceptorIsRegisteredByPackageScanning), without dragging in every real resource.
			ResourceConfig config = new ResourceConfig(TestBindingResource.class);
			config.registerClasses(ApiExceptionMapper.class, ApiRequestBodyInterceptor.class);

			for (Class<?> extraProvider : extraProviders)
				config.register(extraProvider);

			ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
			context.setContextPath("/");
			context.addServlet(new ServletHolder(new ServletContainer(config)), "/*");

			this.server.addConnector(this.connector);
			this.server.setHandler(context);
			this.server.start();
		}

		private String postText(String path, String body) throws Exception {
			return post(path, body, "text/plain");
		}

		private String post(String path, String body) throws Exception {
			return post(path, body, "application/json");
		}

		private String post(String path, String body, String contentType) throws Exception {
			byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
			String request = "POST " + path + " HTTP/1.1\r\n"
					+ "Host: localhost\r\n"
					+ "Content-Type: " + contentType + "\r\n"
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

	/** Test-only resource that reproduces the request-binding shapes used across the real API. */
	@Path("/test-binding")
	public static class TestBindingResource {

		/** Same shape as e.g. ChatResource.buildChat: an abstract declared body type. */
		@POST
		@Path("/transaction")
		@Consumes(MediaType.APPLICATION_JSON)
		@Produces(MediaType.TEXT_PLAIN)
		public String acceptTransaction(TransactionData transactionData) {
			return transactionData.getClass().getSimpleName();
		}

		/** Same shape as e.g. PaymentsResource.makePayment: a concrete declared body type. */
		@POST
		@Path("/payment")
		@Consumes(MediaType.APPLICATION_JSON)
		@Produces(MediaType.TEXT_PLAIN)
		public String acceptPayment(PaymentTransactionData paymentTransactionData) {
			return paymentTransactionData.getClass().getSimpleName();
		}

		/**
		 * No endpoint declares a primitive entity parameter today (a reflective scan of all 422 resource
		 * methods found 156 entity parameters and zero primitives), so this stands in for one and keeps the
		 * interceptor's {@code !isPrimitive()} guard honest. Dropping that guard turns this into a 400.
		 */
		@POST
		@Path("/primitive")
		@Consumes(MediaType.TEXT_PLAIN)
		@Produces(MediaType.TEXT_PLAIN)
		public String acceptPrimitive(long value) {
			return "got " + value;
		}

		@POST
		@Path("/descriptor-fault")
		@Consumes(MediaType.APPLICATION_JSON)
		@Produces(MediaType.TEXT_PLAIN)
		public String throwDescriptorException(TransactionData transactionData) {
			throw DescriptorException.attributeNameNotSpecified();
		}

		@POST
		@Path("/database-fault")
		@Consumes(MediaType.APPLICATION_JSON)
		@Produces(MediaType.TEXT_PLAIN)
		public String throwDatabaseException(TransactionData transactionData) {
			throw DatabaseException.databaseAccessorNotConnected();
		}

	}

}
