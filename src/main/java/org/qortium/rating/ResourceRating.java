package org.qortium.rating;

import com.google.common.base.Utf8;
import org.qortium.arbitrary.misc.Service;
import org.qortium.data.transaction.ArbitraryTransactionData;
import org.qortium.naming.Name;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.transaction.ArbitraryTransaction;
import org.qortium.utils.Unicode;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public final class ResourceRating {

	public static final int NO_RATING = 0;
	public static final int MIN_RATING = 1;
	public static final int MAX_RATING = 10;
	public static final String DEFAULT_IDENTIFIER = "default";

	private static final Set<Service> INTERNAL_SERVICES = EnumSet.of(
			Service.AUTO_UPDATE,
			Service.AUTO_UPDATE_BINARY,
			Service.ARBITRARY_DATA);

	private ResourceRating() {
	}

	public static String normalizeIdentifier(String identifier) {
		if (identifier == null)
			return null;

		String normalized = identifier.trim();
		if (normalized.isEmpty() || DEFAULT_IDENTIFIER.equals(normalized))
			return null;

		return normalized;
	}

	public static String toIdentifierKey(String identifier) {
		String normalized = normalizeIdentifier(identifier);
		return normalized == null ? DEFAULT_IDENTIFIER : normalized;
	}

	public static String toNameKey(String name) {
		return name == null ? null : name.toLowerCase(Locale.ROOT);
	}

	public static boolean isRateableService(Service service) {
		return service != null && !service.isPrivate() && !INTERNAL_SERVICES.contains(service);
	}

	public static boolean isRatingInRange(int rating) {
		return rating >= MIN_RATING && rating <= MAX_RATING;
	}

	public static boolean isNoRating(int rating) {
		return rating == NO_RATING;
	}

	public static boolean isNameValid(String name) {
		if (name == null)
			return false;

		int nameLength = Utf8.encodedLength(name);
		return nameLength >= Name.MIN_NAME_SIZE && nameLength <= Name.MAX_NAME_SIZE;
	}

	public static boolean isIdentifierValid(String identifier) {
		if (identifier == null)
			return true;

		int identifierLength = Utf8.encodedLength(identifier);
		return identifierLength <= ArbitraryTransaction.MAX_IDENTIFIER_LENGTH;
	}

	public static boolean isNormalized(String value) {
		return value == null || value.equals(Unicode.normalize(value));
	}

	public static Target resolveTarget(Repository repository, Service service, String name, String identifier) throws DataException {
		String normalizedIdentifier = normalizeIdentifier(identifier);
		ArbitraryTransactionData latestTransaction = repository.getArbitraryRepository()
				.getLatestTransaction(name, service, null, normalizedIdentifier);

		if (latestTransaction == null)
			return null;

		String displayName = latestTransaction.getName();
		String nameKey = toNameKey(displayName);
		String identifierKey = toIdentifierKey(latestTransaction.getIdentifier());

		return new Target(service, nameKey, displayName, identifierKey);
	}

	public static Target fallbackTarget(Service service, String name, String identifier) {
		return new Target(service, toNameKey(name), name, toIdentifierKey(identifier));
	}

	public static final class Target {
		public final Service service;
		public final String nameKey;
		public final String displayName;
		public final String identifierKey;

		private Target(Service service, String nameKey, String displayName, String identifierKey) {
			this.service = service;
			this.nameKey = nameKey;
			this.displayName = displayName;
			this.identifierKey = identifierKey;
		}
	}

}
