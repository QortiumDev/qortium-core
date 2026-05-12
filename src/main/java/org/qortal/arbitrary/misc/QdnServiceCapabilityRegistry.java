package org.qortal.arbitrary.misc;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public final class QdnServiceCapabilityRegistry {

	private static final String APP_LIBRARY_RATING_PREFIX = "app-library-";
	private static final String APP_LIBRARY_RATING_SEPARATOR = "-rating-";

	private static final Set<Service> APP_LIBRARY_RATING_SERVICES = Collections.unmodifiableSet(EnumSet.of(
			Service.APP,
			Service.WEBSITE,
			Service.PLUGIN,
			Service.EXTENSION,
			Service.GAME));

	private QdnServiceCapabilityRegistry() {
	}

	public static Service parseKnownService(String serviceName) {
		if (serviceName == null)
			return null;

		String normalized = serviceName.trim().toUpperCase(Locale.ROOT);
		if (normalized.isEmpty())
			return null;

		try {
			return Service.valueOf(normalized);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static Set<Service> getAppLibraryRatingServices() {
		return APP_LIBRARY_RATING_SERVICES;
	}

	public static boolean supportsAppLibraryRatings(Service service) {
		return service != null && APP_LIBRARY_RATING_SERVICES.contains(service);
	}

	public static Service requireAppLibraryRatingService(String serviceName) {
		Service service = parseKnownService(serviceName);
		if (!supportsAppLibraryRatings(service))
			throw new IllegalArgumentException("Unsupported app-library rating service: " + serviceName);

		return service;
	}

	public static String appLibraryRatingPrefix() {
		return APP_LIBRARY_RATING_PREFIX;
	}

	public static String appLibraryRatingPrefix(Service service) {
		if (!supportsAppLibraryRatings(service))
			throw new IllegalArgumentException("Unsupported app-library rating service: " + service);

		return APP_LIBRARY_RATING_PREFIX + service.name() + APP_LIBRARY_RATING_SEPARATOR;
	}

	public static AppLibraryRatingPollName parseAppLibraryRatingPollName(String pollName) {
		if (pollName == null || !pollName.startsWith(APP_LIBRARY_RATING_PREFIX))
			return null;

		int serviceStart = APP_LIBRARY_RATING_PREFIX.length();
		int separatorIndex = pollName.indexOf(APP_LIBRARY_RATING_SEPARATOR, serviceStart);
		if (separatorIndex <= serviceStart)
			return null;

		int appNameStart = separatorIndex + APP_LIBRARY_RATING_SEPARATOR.length();
		if (appNameStart >= pollName.length())
			return null;

		String serviceToken = pollName.substring(serviceStart, separatorIndex);
		Service service = parseKnownService(serviceToken);
		String canonicalServiceName = service != null ? service.name() : serviceToken;

		return new AppLibraryRatingPollName(canonicalServiceName, pollName.substring(appNameStart),
				service, supportsAppLibraryRatings(service));
	}

	public static final class AppLibraryRatingPollName {
		public final String service;
		public final String appName;
		public final Service knownService;
		public final boolean ratingCapable;

		private AppLibraryRatingPollName(String service, String appName, Service knownService, boolean ratingCapable) {
			this.service = service;
			this.appName = appName;
			this.knownService = knownService;
			this.ratingCapable = ratingCapable;
		}
	}

}
