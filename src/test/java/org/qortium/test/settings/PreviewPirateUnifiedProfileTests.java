package org.qortium.test.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.qortium.settings.Settings;

import java.nio.file.Files;
import java.security.Security;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreviewPirateUnifiedProfileTests {

	/**
	 * Settings/NullAccount static init needs RIPEMD160 from BouncyCastle. In the full suite an
	 * earlier test registers the provider (Common.useSettings); run alone this class failed with
	 * "RIPEMD160 message digest not available" -> NoClassDefFoundError NullAccount. Register it
	 * here exactly like Controller#main so the test is order-independent.
	 */
	@BeforeClass
	public static void registerBouncyCastle() {
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null)
			Security.insertProviderAt(new BouncyCastleProvider(), 0);
		if (Security.getProvider(BouncyCastleJsseProvider.PROVIDER_NAME) == null)
			Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};
	private static final String PIRATE_UNIFIED_V1_2_3_QDN_SIGNATURE =
			"24hysb2o6HwXY6U7DmfdcZEpu4JtC5pF9WGftHhkeQPXeoNyatd8EfbUD6G2DptfhKKv9r7o865UEfXYFCCK2M6j";

	@After
	public void restoreDefaultSettings() {
		Settings.fileInstance("src/test/resources/test-settings-v2.json");
	}

	@Test
	public void testParticipantProfileEnablesPinnedPirateUnifiedWallet() throws Exception {
		Map<String, Object> settings = readSettings(Path.of("preview/settings-preview.json"));

		assertEquals(Boolean.TRUE, walletSettings(settings).get("ARRR"));
		assertEquals(Boolean.TRUE, settings.get("pirateChainWalletUnified"));
		assertEquals(PIRATE_UNIFIED_V1_2_3_QDN_SIGNATURE, settings.get("pirateChainWalletQdnSignature"));

		Settings.fileInstance("preview/settings-preview.json");
		assertTrue(Settings.getInstance().isWalletEnabled("ARRR"));
		assertTrue(Settings.getInstance().isPirateChainWalletUnified());
		assertEquals(PIRATE_UNIFIED_V1_2_3_QDN_SIGNATURE,
				Settings.getInstance().getPirateChainWalletQdnSignature());
	}

	@Test
	public void testSeedProfilesKeepPirateWalletDisabled() throws Exception {
		for (String profile : new String[] {
				"preview/settings-preview-seed.json",
				"preview/settings-preview-seed-netcup.json"
		}) {
			Map<String, Object> settings = readSettings(Path.of(profile));
			assertEquals(profile, Boolean.FALSE, walletSettings(settings).get("ARRR"));
			assertFalse(profile, Boolean.TRUE.equals(settings.get("pirateChainWalletUnified")));
		}
	}

	/**
	 * P-CORE-58 regression coverage: every preview profile must resolve to the same Pirate
	 * Unified wallet bundle pin as Settings' own compiled-in default (PIRATE_UNIFIED_V1_2_3_QDN_SIGNATURE),
	 * whether it pins the signature explicitly (settings-preview.json) or relies on the code
	 * default (the seed profiles). Without this, a future bundle bump in Settings.java can drift
	 * silently from a stale explicit pin left behind in one of these templates, and a main build
	 * downloads/uses the wrong bundle forever with only a generic provenance-mismatch log to go on.
	 */
	@Test
	public void testAllPreviewProfilesPinTheSameQdnSignatureAsSettingsDefault() throws Exception {
		Settings.fileInstance("src/test/resources/test-settings-v2.json");
		String settingsDefaultSignature = Settings.getInstance().getPirateChainWalletQdnSignature();
		assertEquals(PIRATE_UNIFIED_V1_2_3_QDN_SIGNATURE, settingsDefaultSignature);

		for (String profile : new String[] {
				"preview/settings-preview.json",
				"preview/settings-preview-seed.json",
				"preview/settings-preview-seed-netcup.json"
		}) {
			Map<String, Object> rawSettings = readSettings(Path.of(profile));
			Object explicitSignature = rawSettings.get("pirateChainWalletQdnSignature");
			if (explicitSignature != null) {
				assertEquals("Explicit pirateChainWalletQdnSignature in " + profile
								+ " has drifted from Settings' compiled-in default",
						settingsDefaultSignature, explicitSignature);
			}

			Settings.fileInstance(profile);
			assertEquals("Loading " + profile + " resolves to a different Pirate wallet bundle pin than Settings' default",
					settingsDefaultSignature, Settings.getInstance().getPirateChainWalletQdnSignature());
		}
	}

	@Test
	public void testSeedProfilesRetainCurrentAndSupportedPreviousBundle() throws Exception {
		for (String profile : new String[] {"preview/settings-preview-seed.json", "preview/settings-preview-seed-netcup.json"}) {
			Map<String, Object> settings = readSettings(Path.of(profile));
			assertEquals(profile, List.of(PIRATE_UNIFIED_V1_2_3_QDN_SIGNATURE,
					"bEd5dM3wcbYWyG9hUHQQQsrYrYQ2rnYMDPahbqACpxCojjND5hwyUwiQQZNsTqRXu5awnsSurSwHnKkVeh24q7a"), settings.get("qdnRetainedSignatures"));
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> walletSettings(Map<String, Object> settings) {
		assertTrue(settings.get("wallets") instanceof Map<?, ?>);
		return (Map<String, Object>) settings.get("wallets");
	}

	private static Map<String, Object> readSettings(Path path) throws Exception {
		return MAPPER.readValue(Files.readAllBytes(path), MAP_TYPE);
	}
}
