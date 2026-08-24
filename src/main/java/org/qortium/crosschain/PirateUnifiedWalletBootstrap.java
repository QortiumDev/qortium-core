package org.qortium.crosschain;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Default-off boundary for resolving and initializing a Pirate Unified wallet bundle.
 * Production wiring is intentionally deferred to the persistent-storage integration.
 */
final class PirateUnifiedWalletBootstrap {

	@FunctionalInterface
	interface QdnLibraryResolver {
		Path resolve(String transactionSignature);
	}

	@FunctionalInterface
	interface UnifiedNativeInitializer {
		void initialize(Path libraryPath, boolean debugLogging);
	}

	private final ZcashFamilyWalletConfig config;
	private final QdnLibraryResolver qdnLibraryResolver;
	private final UnifiedNativeInitializer nativeInitializer;

	PirateUnifiedWalletBootstrap(ZcashFamilyWalletConfig config, QdnLibraryResolver qdnLibraryResolver,
			UnifiedNativeInitializer nativeInitializer) {
		this.config = Objects.requireNonNull(config);
		this.qdnLibraryResolver = Objects.requireNonNull(qdnLibraryResolver);
		this.nativeInitializer = Objects.requireNonNull(nativeInitializer);
	}

	boolean initializeIfEnabled() {
		if (!this.config.isUnifiedWalletEnabled())
			return false;

		String transactionSignature = Objects.requireNonNull(this.config.getUnifiedQdnWalletSignature(),
				"Unified QDN transaction signature");
		Path libraryPath = Objects.requireNonNull(this.qdnLibraryResolver.resolve(transactionSignature),
				"Unified native library path");
		this.nativeInitializer.initialize(libraryPath, this.config.isUnifiedDebugLoggingEnabled());
		return true;
	}
}
