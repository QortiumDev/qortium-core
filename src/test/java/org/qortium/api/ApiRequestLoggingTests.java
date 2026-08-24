package org.qortium.api;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ApiRequestLoggingTests {

	@Test
	public void testApiRequestLogOmitsSensitiveQueryString() throws Exception {
		AtomicReference<String> logLine = new AtomicReference<>();
		CountDownLatch logged = new CountDownLatch(1);
		Server server = new Server();
		LocalConnector connector = new LocalConnector(server);
		server.addConnector(connector);
		server.setRequestLog(new CustomRequestLog(line -> {
			logLine.set(line);
			logged.countDown();
		}, ApiService.API_REQUEST_LOG_FORMAT));
		server.setHandler(new Handler.Abstract() {
			@Override
			public boolean handle(Request request, Response response, Callback callback) {
				response.setStatus(204);
				callback.succeeded();
				return true;
			}
		});

		try {
			server.start();
			connector.getResponse("GET /crosschain/arrr/syncstatus?apiKey=secret-sentinel&json=true HTTP/1.1\r\n"
					+ "Host: localhost\r\n"
					+ "Referer: https://example.test/page?apiKey=referer-secret\r\n\r\n");
			assertTrue(logged.await(2, TimeUnit.SECONDS));
			String line = logLine.get();
			assertTrue(line.contains("/crosschain/arrr/syncstatus"));
			assertFalse(line.contains("secret-sentinel"));
			assertFalse(line.contains("apiKey"));
			assertFalse(line.contains("json=true"));
			assertFalse(line.contains("referer-secret"));
			assertFalse(line.contains("Referer"));
		} finally {
			server.stop();
		}
	}

	@Test
	public void testApiErrorLogPathOmitsQueryString() {
		String path = ApiErrorHandler.safeRequestPath(HttpURI.from(
				"/crosschain/arrr/syncstatus?apiKey=error-secret&json=true"));

		assertEquals("/crosschain/arrr/syncstatus", path);
		assertFalse(path.contains("error-secret"));
		assertFalse(path.contains("apiKey"));
	}
}
