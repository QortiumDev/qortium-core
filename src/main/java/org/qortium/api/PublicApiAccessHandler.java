package org.qortium.api;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.InetAddressPattern;
import org.qortium.settings.Settings;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
		String passedApiKey = request.getHeaders().get(Security.API_KEY_HEADER);

		if (!isRequestAllowed(
				Request.getRemoteAddr(request),
				request.getMethod(),
				request.getHttpURI().getPath(),
				passedApiKey,
				Settings.getInstance())) {
			Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403);
			return true;
		}

		return super.handle(request, response, callback);
	}

	public static boolean isRequestAllowed(String remoteAddress, String method, String path, Settings settings) {
		return isRequestAllowed(remoteAddress, method, path, null, null, settings);
	}

	static boolean isRequestAllowed(String remoteAddress, String method, String path,
			String passedApiKey, Settings settings) {
		String nodeApiKey = passedApiKey == null || passedApiKey.isBlank() ? null : getNodeApiKey();
		return isRequestAllowed(remoteAddress, method, path, passedApiKey, nodeApiKey, settings);
	}

	public static boolean isRequestAllowed(String remoteAddress, String method, String path,
			String passedApiKey, String nodeApiKey, Settings settings) {
		if (isTrustedRequest(remoteAddress, passedApiKey, nodeApiKey, settings))
			return true;

		if (!settings.isPublicApiWhitelistEnabled())
			return false;

		return matchesAny(remoteAddress, settings.getPublicApiWhitelist())
				&& matchesPublicPath(method, path, settings.getPublicApiPaths());
	}

	static boolean isTrustedRequest(String remoteAddress, String passedApiKey, String nodeApiKey, Settings settings) {
		if (matchesAny(remoteAddress, settings.getApiWhitelist()))
			return true;

		// The node's API key authenticates the node owner, so let it bypass the IP/path
		// rules; endpoint-level key checks still run afterwards.
		return settings.isApiKeyRemoteAccessEnabled() && matchesApiKey(passedApiKey, nodeApiKey);
	}

	private static boolean matchesApiKey(String passedApiKey, String nodeApiKey) {
		if (passedApiKey == null || passedApiKey.isBlank() || nodeApiKey == null || nodeApiKey.isBlank())
			return false;

		return MessageDigest.isEqual(
				nodeApiKey.getBytes(StandardCharsets.UTF_8),
				passedApiKey.getBytes(StandardCharsets.UTF_8));
	}

	/** The node's generated API key, or null when none has been generated yet. */
	static String getNodeApiKey() {
		ApiKey apiKey = ApiService.getInstance().getApiKey();

		if (apiKey == null) {
			try {
				apiKey = new ApiKey();
			} catch (IOException e) {
				// Couldn't load an API key, so there is nothing to match against.
				return null;
			}
			ApiService.getInstance().setApiKey(apiKey);
		}

		return apiKey.generated() ? apiKey.toString() : null;
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
