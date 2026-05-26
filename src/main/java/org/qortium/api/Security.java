package org.qortium.api;

import org.qortium.arbitrary.ArbitraryDataResource;
import org.qortium.arbitrary.misc.Service;
import org.qortium.controller.arbitrary.ArbitraryDataRenderManager;
import org.qortium.settings.Settings;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;

public abstract class Security {

	public static final String API_KEY_HEADER = "X-API-KEY";

	/**
	 * Check API call is allowed, retrieving the API key from the request header where required.
	 * @param request
	 */
	public static void checkApiCallAllowed(HttpServletRequest request) {
		checkApiCallAllowed(request, null);
	}

	/**
	 * Check API call is allowed, retrieving the API key first from the passedApiKey parameter, with a fallback
	 * to the request header when null.
	 * @param request
	 * @param passedApiKey - the API key to test, or null if it should be retrieved from the request headers.
	 */
	public static void checkApiCallAllowed(HttpServletRequest request, String passedApiKey) {
		// Retrieve the API key
		ApiKey apiKey = Security.getApiKey(request);
		if (!apiKey.generated()) {
			// Not generated an API key yet, so disallow sensitive API calls
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.UNAUTHORIZED, "API key not generated");
		}

		// We require an API key to be passed
		if (passedApiKey == null) {
			// API call not passed as a parameter, so try the header
			passedApiKey = request.getHeader(API_KEY_HEADER);
		}
		if (passedApiKey == null) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.UNAUTHORIZED, "Missing 'X-API-KEY' header");
		}

		// The API keys must match
		if (!apiKeyMatches(apiKey, passedApiKey)) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.UNAUTHORIZED, "API key invalid");
		}
	}

	private static boolean apiKeyMatches(ApiKey apiKey, String passedApiKey) {
		return MessageDigest.isEqual(
				apiKey.toString().getBytes(StandardCharsets.UTF_8),
				passedApiKey.getBytes(StandardCharsets.UTF_8));
	}

	public static void disallowLoopbackRequests(HttpServletRequest request) {
		try {
			InetAddress remoteAddr = InetAddress.getByName(request.getRemoteAddr());
			if (remoteAddr.isLoopbackAddress() && !Settings.getInstance().isGatewayLoopbackEnabled()) {
				throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.UNAUTHORIZED, "Local requests not allowed");
			}
		} catch (UnknownHostException e) {
			throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.UNAUTHORIZED);
		}
	}

	public static void requirePriorAuthorization(HttpServletRequest request, String resourceId, Service service, String identifier) {
		ArbitraryDataResource resource = new ArbitraryDataResource(resourceId, null, service, identifier);
		if (!ArbitraryDataRenderManager.getInstance().isAuthorized(resource)) {
			throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.UNAUTHORIZED, "Call /render/authorize first");
		}
	}

	public static void requirePriorAuthorizationOrApiKey(HttpServletRequest request, String resourceId, Service service, String identifier, String apiKey) {
		try {
			Security.checkApiCallAllowed(request, apiKey);

		} catch (ApiException e) {
			// API call wasn't allowed, but maybe it was pre-authorized
			Security.requirePriorAuthorization(request, resourceId, service, identifier);
		}
	}

	public static ApiKey getApiKey(HttpServletRequest request) {
		ApiKey apiKey = ApiService.getInstance().getApiKey();
		if (apiKey == null) {
			try {
				apiKey = new ApiKey();
			} catch (IOException e) {
				// Couldn't load API key - so we need to treat it as not generated, and therefore unauthorized
				throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.UNAUTHORIZED);
			}
			ApiService.getInstance().setApiKey(apiKey);
		}
		return apiKey;
	}

}
