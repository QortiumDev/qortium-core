package org.qortium.api;

import org.eclipse.jetty.ee8.servlet.ServletContextHandler;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class CorsFilter implements Filter {

	private static final String ALLOWED_ORIGIN = "*";
	private static final String ALLOWED_METHODS = "GET, POST, DELETE";
	private static final String ALLOWED_HEADERS = "Origin, X-Requested-With, Content-Type, Accept, X-API-KEY";

	public static void addTo(ServletContextHandler context) {
		context.addFilter(CorsFilter.class, "/*", null);
	}

	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
			throws IOException, ServletException {
		if (!(servletRequest instanceof HttpServletRequest) || !(servletResponse instanceof HttpServletResponse)) {
			chain.doFilter(servletRequest, servletResponse);
			return;
		}

		HttpServletRequest request = (HttpServletRequest) servletRequest;
		HttpServletResponse response = (HttpServletResponse) servletResponse;

		response.setHeader("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
		response.setHeader("Access-Control-Allow-Methods", ALLOWED_METHODS);
		response.setHeader("Access-Control-Allow-Headers", ALLOWED_HEADERS);

		if (isPreflightRequest(request)) {
			response.setStatus(HttpServletResponse.SC_OK);
			return;
		}

		chain.doFilter(servletRequest, servletResponse);
	}

	private static boolean isPreflightRequest(HttpServletRequest request) {
		return "OPTIONS".equalsIgnoreCase(request.getMethod())
				&& request.getHeader("Origin") != null
				&& request.getHeader("Access-Control-Request-Method") != null;
	}

}
