package org.qortium.repository.hsqldb;

import org.qortium.utils.ByteArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared input contract for repository lookups by binary signature. */
public final class HSQLDBSignatureLookup {

	public static final int MAX_BATCH_SIZE = 500;

	private HSQLDBSignatureLookup() {
	}

	/**
	 * Removes null and empty values and deduplicates signatures by byte content.
	 * Input order is retained for predictable batching; query result order remains unspecified.
	 */
	public static List<byte[]> prepare(List<byte[]> signatures) {
		if (signatures == null || signatures.isEmpty())
			return Collections.emptyList();

		Map<ByteArray, byte[]> uniqueSignatures = new LinkedHashMap<>(signatures.size());
		for (byte[] signature : signatures)
			if (signature != null && signature.length > 0)
				uniqueSignatures.putIfAbsent(ByteArray.wrap(signature), signature);

		return new ArrayList<>(uniqueSignatures.values());
	}
}
