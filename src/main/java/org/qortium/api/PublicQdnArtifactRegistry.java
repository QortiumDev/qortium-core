package org.qortium.api;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.qortium.utils.Base58;

import java.time.Duration;

/**
 * Short-lived capabilities for pre-signature artifacts emitted by public QDN
 * builders. The shared _misc store can also contain authenticated/private build
 * output, so hash knowledge alone is not sufficient authorization.
 */
public final class PublicQdnArtifactRegistry {

	private static final PublicQdnArtifactRegistry INSTANCE = new PublicQdnArtifactRegistry();

	private final Cache<String, Boolean> artifacts = CacheBuilder.newBuilder()
			.maximumSize(10_000)
			.expireAfterWrite(Duration.ofMinutes(10))
			.build();

	private PublicQdnArtifactRegistry() {
	}

	public static PublicQdnArtifactRegistry getInstance() {
		return INSTANCE;
	}

	public void register(byte[] hash) {
		if (hash != null && hash.length == 32)
			this.artifacts.put(Base58.encode(hash), Boolean.TRUE);
	}

	public boolean contains(String hash58) {
		return hash58 != null && this.artifacts.getIfPresent(hash58) != null;
	}

	void clearForTesting() {
		this.artifacts.invalidateAll();
	}
}
