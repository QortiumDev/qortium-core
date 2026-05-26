package org.qortium.test.api;

import org.junit.Test;
import org.qortium.api.CorsFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CorsFilterTests {

	@Test
	public void testStandardRequestAddsCorsHeadersAndContinues() throws Exception {
		Exchange exchange = new Exchange("GET")
				.withRequestHeader("Origin", "https://example.com");

		new CorsFilter().doFilter(exchange.request, exchange.response, exchange.chain);

		assertTrue(exchange.chainCalled.get());
		assertEquals("*", exchange.responseHeaders.get("Access-Control-Allow-Origin"));
		assertEquals("GET, POST, DELETE", exchange.responseHeaders.get("Access-Control-Allow-Methods"));
		assertEquals("Origin, X-Requested-With, Content-Type, Accept", exchange.responseHeaders.get("Access-Control-Allow-Headers"));
	}

	@Test
	public void testPreflightRequestAddsCorsHeadersAndStopsChain() throws Exception {
		Exchange exchange = new Exchange("OPTIONS")
				.withRequestHeader("Origin", "https://example.com")
				.withRequestHeader("Access-Control-Request-Method", "POST")
				.withRequestHeader("Access-Control-Request-Headers", "X-API-KEY, Content-Type");

		new CorsFilter().doFilter(exchange.request, exchange.response, exchange.chain);

		assertFalse(exchange.chainCalled.get());
		assertEquals(HttpServletResponse.SC_OK, exchange.status);
		assertEquals("*", exchange.responseHeaders.get("Access-Control-Allow-Origin"));
		assertEquals("GET, POST, DELETE", exchange.responseHeaders.get("Access-Control-Allow-Methods"));
		assertEquals("X-API-KEY, Content-Type", exchange.responseHeaders.get("Access-Control-Allow-Headers"));
	}

	private static class Exchange {
		private final String method;
		private final Map<String, String> requestHeaders = new LinkedHashMap<>();
		private final Map<String, String> responseHeaders = new LinkedHashMap<>();
		private final AtomicBoolean chainCalled = new AtomicBoolean(false);
		private int status;

		private final HttpServletRequest request;
		private final HttpServletResponse response;
		private final FilterChain chain;

		private Exchange(String method) {
			this.method = method;
			this.request = (HttpServletRequest) Proxy.newProxyInstance(
					CorsFilterTests.class.getClassLoader(),
					new Class[] { HttpServletRequest.class },
					(proxy, invokedMethod, args) -> {
						switch (invokedMethod.getName()) {
							case "getMethod":
								return this.method;
							case "getHeader":
								return this.requestHeaders.get((String) args[0]);
							default:
								return defaultValue(invokedMethod.getReturnType());
						}
					});

			this.response = (HttpServletResponse) Proxy.newProxyInstance(
					CorsFilterTests.class.getClassLoader(),
					new Class[] { HttpServletResponse.class },
					(proxy, invokedMethod, args) -> {
						switch (invokedMethod.getName()) {
							case "setHeader":
								this.responseHeaders.put((String) args[0], (String) args[1]);
								return null;
							case "setStatus":
								this.status = (Integer) args[0];
								return null;
							default:
								return defaultValue(invokedMethod.getReturnType());
						}
					});

			this.chain = (ServletRequest request, ServletResponse response) -> this.chainCalled.set(true);
		}

		private Exchange withRequestHeader(String name, String value) {
			this.requestHeaders.put(name, value);
			return this;
		}
	}

	private static Object defaultValue(Class<?> returnType) {
		if (returnType == boolean.class)
			return false;

		if (returnType == int.class)
			return 0;

		if (returnType == long.class)
			return 0L;

		return null;
	}

}
