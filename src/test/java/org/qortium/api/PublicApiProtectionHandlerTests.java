package org.qortium.api;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.qortium.api.PublicApiProtectionHandler.RateBucket;
import org.qortium.api.PublicApiProtectionHandler.RequestClass;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.time.Duration;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PublicApiProtectionHandlerTests extends Common {

	private Settings settings;

	@Before
	public void beforeTest() throws Exception {
		Common.useDefaultSettings();
		this.settings = Settings.getInstance();
	}

	@Test
	public void testOnlyAnonymousPublicWriteWorkIsClassified() {
		assertEquals(RequestClass.BUILDER,
				PublicApiProtectionHandler.classify("POST", "/polls/public/vote"));
		assertEquals(RequestClass.BUILDER,
				PublicApiProtectionHandler.classify("POST", "/chat/public/build"));
		assertEquals(RequestClass.BUILDER,
				PublicApiProtectionHandler.classify("POST", "/transactions/convert"));
		assertEquals(RequestClass.PROCESS,
				PublicApiProtectionHandler.classify("POST", "/transactions/process"));
		assertEquals(RequestClass.QDN,
				PublicApiProtectionHandler.classify("POST", "/arbitrary/public/APP/name/base64"));
		assertEquals(RequestClass.QDN,
				PublicApiProtectionHandler.classify("GET", "/arbitrary/public/data/Hash58"));

		assertEquals(RequestClass.NONE,
				PublicApiProtectionHandler.classify("POST", "/polls/vote"));
		assertEquals(RequestClass.NONE,
				PublicApiProtectionHandler.classify("POST", "/transactions/sign"));
		assertEquals(RequestClass.NONE,
				PublicApiProtectionHandler.classify("GET", "/arbitrary/public/data/hash/extra"));
		assertEquals(RequestClass.NONE,
				PublicApiProtectionHandler.classify("GET", "/polls/public/capabilities"));
	}

	@Test
	public void testBodyLimitsSeparateSmallWritesFromQdnPayloads() throws Exception {
		FieldUtils.writeField(this.settings, "publicApiWriteMaxBodySize", 1024L, true);
		FieldUtils.writeField(this.settings, "publicQdnPublishMaxSize", 12_000L, true);

		assertEquals(1024L, PublicApiProtectionHandler.bodyLimit(RequestClass.BUILDER, this.settings));
		assertEquals(1024L, PublicApiProtectionHandler.bodyLimit(RequestClass.PROCESS, this.settings));
		assertEquals(16_000L + 1024L * 1024L,
				PublicApiProtectionHandler.bodyLimit(RequestClass.QDN, this.settings));
	}

	@Test
	public void testTokenBucketEnforcesBurstAndRefillsAtConfiguredRate() {
		long start = Duration.ofMinutes(1).toNanos();
		RateBucket bucket = new RateBucket(start);

		assertTrue(bucket.tryAcquire(start, 2, 2));
		assertTrue(bucket.tryAcquire(start, 2, 2));
		assertFalse(bucket.tryAcquire(start, 2, 2));
		assertTrue(bucket.tryAcquire(start + Duration.ofSeconds(30).toNanos(), 2, 2));
		assertFalse(bucket.tryAcquire(start + Duration.ofSeconds(30).toNanos(), 2, 2));
		assertFalse("Clock regression must not refill tokens", bucket.tryAcquire(start, 2, 2));
	}

	@Test
	public void testConcurrencyAdmissionNeverExceedsMaximum() {
		AtomicInteger active = new AtomicInteger();

		assertTrue(PublicApiProtectionHandler.tryAcquire(active, 2));
		assertTrue(PublicApiProtectionHandler.tryAcquire(active, 2));
		assertFalse(PublicApiProtectionHandler.tryAcquire(active, 2));
		assertEquals(2, active.get());
		active.decrementAndGet();
		assertTrue(PublicApiProtectionHandler.tryAcquire(active, 2));
		assertEquals(2, active.get());
	}

	@Test
	public void testHandlerEnforcesDeclaredAndChunkedBodyLimitsAndReleasesConcurrency() throws Exception {
		FieldUtils.writeField(this.settings, "apiWhitelist", new String[0], true);
		FieldUtils.writeField(this.settings, "publicApiWriteMaxBodySize", 3L, true);
		FieldUtils.writeField(this.settings, "publicApiBuilderRateLimitBurst", 100, true);
		FieldUtils.writeField(this.settings, "publicApiBuilderMaxConcurrentRequests", 1, true);

		PublicApiProtectionHandler handler = new PublicApiProtectionHandler(() -> 0L);
		try (HandlerServer server = new HandlerServer(handler)) {
			assertTrue(server.request(fixedBody("abc")).startsWith("HTTP/1.1 204"));
			awaitNoActiveBuilders(handler);
			assertTrue("A second sequential request proves callback permit release",
					server.request(fixedBody("abc")).startsWith("HTTP/1.1 204"));
			assertTrue(server.request(fixedBody("abcd")).startsWith("HTTP/1.1 413"));
			assertTrue(server.request(chunkedBody("abc")).startsWith("HTTP/1.1 204"));
			assertTrue(server.request(chunkedBody("abcd")).startsWith("HTTP/1.1 413"));
			awaitNoActiveBuilders(handler);
		}
	}

	private static void awaitNoActiveBuilders(PublicApiProtectionHandler handler) throws Exception {
		AtomicInteger active = (AtomicInteger) FieldUtils.readField(handler, "activeBuilders", true);
		long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
		while (active.get() != 0 && System.nanoTime() < deadline)
			Thread.sleep(5L);
		assertEquals(0, active.get());
	}

	@Test
	public void testHandlerReturns429AfterPerClientBurst() throws Exception {
		FieldUtils.writeField(this.settings, "apiWhitelist", new String[0], true);
		FieldUtils.writeField(this.settings, "publicApiBuilderRequestsPerMinute", 1, true);
		FieldUtils.writeField(this.settings, "publicApiBuilderRateLimitBurst", 1, true);

		PublicApiProtectionHandler handler = new PublicApiProtectionHandler(() -> 0L);
		try (HandlerServer server = new HandlerServer(handler)) {
			assertTrue(server.request(fixedBody("a")).startsWith("HTTP/1.1 204"));
			String limited = server.request(fixedBody("a"));
			assertTrue(limited.startsWith("HTTP/1.1 429"));
			assertTrue(limited.contains("Retry-After: 1"));
		}
	}

	private static String fixedBody(String body) {
		return "POST /polls/public/vote HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
				+ body.length() + "\r\n\r\n" + body;
	}

	private static String chunkedBody(String body) {
		return "POST /polls/public/vote HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
				+ Integer.toHexString(body.length()) + "\r\n" + body + "\r\n0\r\n\r\n";
	}

	private static final class HandlerServer implements AutoCloseable {
		private final Server server = new Server();
		private final LocalConnector connector = new LocalConnector(this.server);

		private HandlerServer(PublicApiProtectionHandler protectionHandler) throws Exception {
			protectionHandler.setHandler(new Handler.Abstract() {
				@Override
				public boolean handle(Request request, Response response, Callback callback) throws Exception {
					try (InputStream input = Request.asInputStream(request)) {
						input.readAllBytes();
					}
					response.setStatus(204);
					Content.Sink.write(response, true, "", callback);
					return true;
				}
			});
			this.server.addConnector(this.connector);
			this.server.setHandler(protectionHandler);
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
