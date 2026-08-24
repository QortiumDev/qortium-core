package org.qortium.crosschain;

import org.junit.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZcashFamilyWalletConfigTests {

	@Test
	public void testDisabledUnifiedConfigDoesNotReadUnifiedInputs() {
		AtomicInteger signatureReads = new AtomicInteger();
		AtomicInteger debugReads = new AtomicInteger();
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
				}, () -> Path.of("wallets"));

		assertEquals("legacy-signature", config.getQdnWalletSignature());
		assertEquals("legacy-signature", config.getActiveQdnWalletSignature());
		assertEquals("legacy-signature", config.getRustLibOuterDirectory().getFileName().toString());
		assertFalse(config.isUnifiedDebugLoggingEnabled());
		assertEquals(0, signatureReads.get());
		assertEquals(0, debugReads.get());
	}

	@Test
	public void testEnabledUnifiedConfigSelectsPinnedBundleAndIsolatedDirectory() {
		ZcashFamilyWalletConfig config = new ZcashFamilyWalletConfig(
				"Test", "TEST", "Test", "legacy-signature", "encryption", "zs",
				() -> 1, () -> null, () -> true, () -> "unified-signature", () -> true,
				() -> Path.of("wallets"));

		assertEquals("unified-signature", config.getActiveQdnWalletSignature());
		assertEquals("unified-signature", config.getRustLibOuterDirectory().getFileName().toString());
		assertEquals("legacy-signature", config.getLegacyRustLibOuterDirectory().getFileName().toString());
		assertTrue(config.isUnifiedDebugLoggingEnabled());
	}
}
