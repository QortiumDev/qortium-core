package org.qortium.test.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;
import org.qortium.settings.Settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreviewPirateUnifiedProfileTests {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};
	private static final String PIRATE_UNIFIED_V1_1_9_QDN_SIGNATURE =
			"3QtMvKDTMUG6V48SKPCwMTPgiqNYdaCwyXfpssfuGD13d7ZL31kk48cuRpuzxy8qnSGg4qgZKEUJ8zYJ7UDQ9aBk";

	@After
	public void restoreDefaultSettings() {
		Settings.fileInstance("src/test/resources/test-settings-v2.json");
	}

	@Test
	public void testParticipantProfileEnablesPinnedPirateUnifiedWallet() throws Exception {
		Map<String, Object> settings = readSettings(Path.of("preview/settings-preview.json"));

		assertEquals(Boolean.TRUE, walletSettings(settings).get("ARRR"));
		assertEquals(Boolean.TRUE, settings.get("pirateChainWalletUnified"));
		assertEquals(PIRATE_UNIFIED_V1_1_9_QDN_SIGNATURE, settings.get("pirateChainWalletQdnSignature"));

		Settings.fileInstance("preview/settings-preview.json");
		assertTrue(Settings.getInstance().isWalletEnabled("ARRR"));
		assertTrue(Settings.getInstance().isPirateChainWalletUnified());
		assertEquals(PIRATE_UNIFIED_V1_1_9_QDN_SIGNATURE,
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

	@SuppressWarnings("unchecked")
	private static Map<String, Object> walletSettings(Map<String, Object> settings) {
		assertTrue(settings.get("wallets") instanceof Map<?, ?>);
		return (Map<String, Object>) settings.get("wallets");
	}

	private static Map<String, Object> readSettings(Path path) throws Exception {
		return MAPPER.readValue(Files.readAllBytes(path), MAP_TYPE);
	}
}
