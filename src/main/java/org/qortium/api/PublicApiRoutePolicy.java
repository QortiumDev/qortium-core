package org.qortium.api;

import java.util.Locale;

/** Shared raw-path policy used by both public access control and public-work protection. */
final class PublicApiRoutePolicy {

	enum WorkClass {
		NONE,
		CHAT_READ,
		BUILDER,
		PROCESS,
		QDN
	}

	private PublicApiRoutePolicy() {
	}

	static boolean isAllowed(String method, String path, String[] allowedPaths) {
		return classify(method, path, allowedPaths).allowed;
	}

	static Decision classify(String method, String path, String[] allowedPaths) {
		if (method == null || path == null || allowedPaths == null)
			return Decision.DENIED;

		String requestMethod = method.trim().toUpperCase(Locale.ROOT);
		boolean allowed = false;
		for (String configuredRoute : allowedPaths) {
			String[] parts = parseConfiguredRoute(configuredRoute);
			if (parts != null && parts[0].equals(requestMethod) && matchesConfiguredPath(parts[1], path)) {
				allowed = true;
				break;
			}
		}

		if (!allowed)
			return Decision.DENIED;

		if ("GET".equals(requestMethod)) {
			if (isPublicStagedDataPath(path))
				return new Decision(true, WorkClass.QDN);
			if (isPrivateGroupProtocolRead(path))
				return new Decision(true, WorkClass.CHAT_READ);
			return new Decision(true, WorkClass.NONE);
		}

		if ("POST".equals(requestMethod)) {
			if (path.equals("/arbitrary/public") || path.startsWith("/arbitrary/public/"))
				return new Decision(true, WorkClass.QDN);
			if ("/transactions/process".equals(path))
				return new Decision(true, WorkClass.PROCESS);
		}

		// Every other allowlisted non-GET route is bounded as builder work. This makes future/custom
		// public writes fail protected instead of silently bypassing the anti-abuse layer.
		return new Decision(true, WorkClass.BUILDER);
	}

	private static boolean isPublicStagedDataPath(String path) {
		String base = "/arbitrary/public/data";
		return path.equals(base) || path.startsWith(base + "/");
	}

	private static boolean isPrivateGroupProtocolRead(String path) {
		return "/chat/private/group/control".equals(path)
				|| path.startsWith("/chat/private/group/state/");
	}

	private static boolean matchesConfiguredPath(String configuredPath, String requestPath) {
		if (configuredPath.endsWith("/*")) {
			String pathPrefix = configuredPath.substring(0, configuredPath.length() - 2);
			return requestPath.equals(pathPrefix) || requestPath.startsWith(pathPrefix + "/");
		}

		return configuredPath.equals(requestPath);
	}

	private static String[] parseConfiguredRoute(String configuredRoute) {
		if (configuredRoute == null)
			return null;

		String[] parts = configuredRoute.trim().split("\\s+", 2);
		if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank())
			return null;

		return new String[] {parts[0].toUpperCase(Locale.ROOT), parts[1]};
	}

	static final class Decision {
		private static final Decision DENIED = new Decision(false, WorkClass.NONE);

		final boolean allowed;
		final WorkClass workClass;

		private Decision(boolean allowed, WorkClass workClass) {
			this.allowed = allowed;
			this.workClass = workClass;
		}
	}
}
