package org.qortium.crosschain;

import org.junit.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZcashFamilyWalletConfigTests {

	@Test
	public void testDisabledUnifiedConfigDoesNotReadUnifiedInputs() {
		AtomicInteger signatureReads = new AtomicInteger();
		AtomicInteger debugReads = new AtomicInteger();
		AtomicInteger qdnCalls = new AtomicInteger();
		AtomicInteger nativeCalls = new AtomicInteger();
		ZcashFamilyWalletConfig config = new ZcashFamilyWalletConfig(
				"Test", "TEST", "Test", "legacy-signature", "encryption", "zs",
				() -> 1, () -> null, () -> false,
				() -> {
					signatureReads.incrementAndGet();
					return "unified-signature";
				},
				() -> {
					debugReads.incrementAndGet();
					return true;
				});

		PirateUnifiedWalletBootstrap bootstrap = new PirateUnifiedWalletBootstrap(config,
				signature -> {
					qdnCalls.incrementAndGet();
					return Path.of("unified-library");
				},
				(libraryPath, debugLogging) -> nativeCalls.incrementAndGet());

		assertFalse(bootstrap.initializeIfEnabled());
		assertEquals("legacy-signature", config.getQdnWalletSignature());
		assertEquals(0, signatureReads.get());
		assertEquals(0, debugReads.get());
		assertEquals(0, qdnCalls.get());
		assertEquals(0, nativeCalls.get());
	}

	@Test
	public void testEnabledUnifiedConfigPassesPinnedInputsAcrossBootstrapBoundary() {
		AtomicReference<String> resolvedSignature = new AtomicReference<>();
		AtomicReference<Path> initializedLibrary = new AtomicReference<>();
		AtomicReference<Boolean> initializedDebug = new AtomicReference<>();
		ZcashFamilyWalletConfig config = new ZcashFamilyWalletConfig(
				"Test", "TEST", "Test", "legacy-signature", "encryption", "zs",
				() -> 1, () -> null, () -> true, () -> "unified-signature", () -> true);
		Path expectedLibrary = Path.of("unified-library");
		PirateUnifiedWalletBootstrap bootstrap = new PirateUnifiedWalletBootstrap(config,
				signature -> {
					resolvedSignature.set(signature);
					return expectedLibrary;
				},
				(libraryPath, debugLogging) -> {
					initializedLibrary.set(libraryPath);
					initializedDebug.set(debugLogging);
				});

		assertTrue(bootstrap.initializeIfEnabled());
		assertEquals("unified-signature", resolvedSignature.get());
		assertEquals(expectedLibrary, initializedLibrary.get());
		assertEquals(Boolean.TRUE, initializedDebug.get());
	}
}
