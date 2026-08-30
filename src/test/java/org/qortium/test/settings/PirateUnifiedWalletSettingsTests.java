package org.qortium.test.settings;

import org.bitcoinj.base.Base58;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.qortium.crosschain.PirateChain;
import org.qortium.settings.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PirateUnifiedWalletSettingsTests {
	private static final String PIRATE_UNIFIED_V1_1_9_QDN_SIGNATURE =
			"3QtMvKDTMUG6V48SKPCwMTPgiqNYdaCwyXfpssfuGD13d7ZL31kk48cuRpuzxy8qnSGg4qgZKEUJ8zYJ7UDQ9aBk";

	static {
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null)
			Security.insertProviderAt(new BouncyCastleProvider(), 1);
	}

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@After
	public void restoreDefaultSettings() {
		Settings.fileInstance("src/test/resources/test-settings-v2.json");
	}

	@Test
	public void testUnifiedWalletDefaultsAreInert() throws Exception {
		loadSettings("{\"storagePolicy\":\"FOLLOWED\"}");

		assertFalse(Settings.getInstance().isPirateChainWalletUnified());
		assertEquals(PIRATE_UNIFIED_V1_1_9_QDN_SIGNATURE,
				Settings.getInstance().getPirateChainWalletQdnSignature());
		assertFalse(Settings.getInstance().isPirateChainWalletDebugLogging());
		assertFalse(PirateChain.WALLET_CONFIG.isUnifiedWalletEnabled());
		assertNull(PirateChain.WALLET_CONFIG.getUnifiedQdnWalletSignature());
		assertFalse(PirateChain.WALLET_CONFIG.isUnifiedDebugLoggingEnabled());
	}

	@Test
	public void testUnifiedWalletUsesReviewedDefaultPin() throws Exception {
		loadSettings("{\"storagePolicy\":\"FOLLOWED\",\"pirateChainWalletUnified\":true}");

		assertTrue(Settings.getInstance().isPirateChainWalletUnified());
		assertEquals(PIRATE_UNIFIED_V1_1_9_QDN_SIGNATURE,
				Settings.getInstance().getPirateChainWalletQdnSignature());
		assertTrue(PirateChain.WALLET_CONFIG.isUnifiedWalletEnabled());
		assertEquals(PIRATE_UNIFIED_V1_1_9_QDN_SIGNATURE,
				PirateChain.WALLET_CONFIG.getUnifiedQdnWalletSignature());
	}

	@Test
	public void testLegacyNullPinUsesReviewedDefault() throws Exception {
		loadSettings("{\"storagePolicy\":\"FOLLOWED\",\"pirateChainWalletUnified\":true,"
				+ "\"pirateChainWalletQdnSignature\":null}");

		assertTrue(Settings.getInstance().isPirateChainWalletUnified());
		assertEquals(PIRATE_UNIFIED_V1_1_9_QDN_SIGNATURE,
				Settings.getInstance().getPirateChainWalletQdnSignature());
		assertEquals(PIRATE_UNIFIED_V1_1_9_QDN_SIGNATURE,
				PirateChain.WALLET_CONFIG.getUnifiedQdnWalletSignature());
	}

	@Test
	public void testUnifiedWalletRejectsBlankPin() throws Exception {
		RuntimeException exception = assertThrows(RuntimeException.class,
				() -> loadSettings("{\"storagePolicy\":\"FOLLOWED\",\"pirateChainWalletUnified\":true,"
						+ "\"pirateChainWalletQdnSignature\":\"\"}"));

		assertTrue(exception.getMessage().contains("pirateChainWalletQdnSignature is required"));
	}

	@Test
	public void testMalformedQdnSignatureIsRejectedEvenWhileDisabled() throws Exception {
		RuntimeException exception = assertThrows(RuntimeException.class,
				() -> loadSettings("{\"storagePolicy\":\"FOLLOWED\","
						+ "\"pirateChainWalletQdnSignature\":\"not-base58!\"}"));

		assertTrue(exception.getMessage().contains("Base58-encoded transaction signature"));
	}

	@Test
	public void testWrongLengthQdnSignatureIsRejected() throws Exception {
		String shortSignature = Base58.encode(new byte[63]);
		RuntimeException exception = assertThrows(RuntimeException.class,
				() -> loadSettings("{\"storagePolicy\":\"FOLLOWED\","
						+ "\"pirateChainWalletQdnSignature\":\"" + shortSignature + "\"}"));

		assertTrue(exception.getMessage().contains("must decode to 64 bytes"));
	}

	@Test
	public void testUnifiedWalletAcceptsPinnedSignature() throws Exception {
		String signature = Base58.encode(new byte[64]);
		loadSettings("{\"storagePolicy\":\"FOLLOWED\",\"pirateChainWalletUnified\":true,"
				+ "\"pirateChainWalletQdnSignature\":\"" + signature + "\","
				+ "\"pirateChainWalletDebugLogging\":true}");

		assertTrue(Settings.getInstance().isPirateChainWalletUnified());
		assertEquals(signature, Settings.getInstance().getPirateChainWalletQdnSignature());
		assertTrue(Settings.getInstance().isPirateChainWalletDebugLogging());
		assertTrue(PirateChain.WALLET_CONFIG.isUnifiedWalletEnabled());
		assertEquals(signature, PirateChain.WALLET_CONFIG.getUnifiedQdnWalletSignature());
		assertTrue(PirateChain.WALLET_CONFIG.isUnifiedDebugLoggingEnabled());
	}

	private void loadSettings(String json) throws Exception {
		Path settingsPath = this.temporaryFolder.newFile("settings-" + System.nanoTime() + ".json").toPath();
		Files.writeString(settingsPath, json, StandardCharsets.UTF_8);
		Settings.fileInstance(settingsPath.toString());
	}
}
