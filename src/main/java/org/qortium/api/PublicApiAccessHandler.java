package org.qortium.api;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.InetAddressPattern;
import org.qortium.settings.Settings;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

public class PublicApiAccessHandler extends Handler.Wrapper {

	public PublicApiAccessHandler() {
		super();
	}

	public PublicApiAccessHandler(Handler handler) {
		super(handler);
	}

	@Override
	public boolean handle(Request request, Response response, Callback callback) throws Exception {
		if (!isRequestAllowed(
				Request.getRemoteAddr(request),
				request.getMethod(),
				request.getHttpURI().getPath(),
				Settings.getInstance())) {
			Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
			return true;
		}

		return super.handle(request, response, callback);
	}

	public static boolean isRequestAllowed(String remoteAddress, String method, String path, Settings settings) {
		if (matchesAny(remoteAddress, settings.getApiWhitelist()))
			return true;

		if (!settings.isPublicApiWhitelistEnabled())
			return false;

		return matchesAny(remoteAddress, settings.getPublicApiWhitelist())
				&& matchesPublicPath(method, path, settings.getPublicApiPaths());
	}

	private static boolean matchesAny(String remoteAddress, String[] patterns) {
		if (remoteAddress == null || patterns == null)
			return false;

		InetAddress address;
		try {
			address = InetAddress.getByName(remoteAddress);
		} catch (UnknownHostException e) {
			return false;
		}

		for (String pattern : patterns) {
			if (pattern == null || pattern.isBlank())
				continue;

			try {
				if (InetAddressPattern.from(pattern.trim()).test(address))
					return true;
			} catch (IllegalArgumentException e) {
				// Treat invalid access-control patterns as non-matches.
			}
		}

		return false;
	}

	private static boolean matchesPublicPath(String method, String path, String[] allowedPaths) {
		if (method == null || path == null || allowedPaths == null)
			return false;

		String requestMethod = method.trim().toUpperCase(Locale.ROOT);
		for (String allowedPath : allowedPaths) {
			String[] parts = parseAllowedPath(allowedPath);
			if (parts == null)
				continue;

			if (parts[0].equals(requestMethod) && matchesAllowedPath(parts[1], path))
				return true;
		}

		return false;
	}

	private static boolean matchesAllowedPath(String allowedPath, String requestPath) {
		if (allowedPath.endsWith("/*")) {
			String pathPrefix = allowedPath.substring(0, allowedPath.length() - 2);
			return requestPath.equals(pathPrefix) || requestPath.startsWith(pathPrefix + "/");
		}

		return allowedPath.equals(requestPath);
	}

	private static String[] parseAllowedPath(String allowedPath) {
		if (allowedPath == null)
			return null;

		String[] parts = allowedPath.trim().split("\\s+", 2);
		if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank())
			return null;

		return new String[] {parts[0].toUpperCase(Locale.ROOT), parts[1]};
	}

}
