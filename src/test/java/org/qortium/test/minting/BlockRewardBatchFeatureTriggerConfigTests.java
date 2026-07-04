package org.qortium.test.minting;

import org.junit.After;
import org.junit.Test;
import org.qortium.block.BlockChain;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

public class BlockRewardBatchFeatureTriggerConfigTests extends Common {

	private static final long FEATURE_TRIGGER_HEIGHT = 123450L;
	private static final long LEGACY_FALLBACK_HEIGHT = 999999000L;

	@After
	public void restoreDefaultTestSettings() {
		URL defaultSettingsUrl = BlockRewardBatchFeatureTriggerConfigTests.class.getClassLoader().getResource("test-settings-v2.json");
		assertNotNull("Default test settings JSON file not found", defaultSettingsUrl);
		Settings.fileInstance(defaultSettingsUrl.getPath());
	}

	@Test
	public void testBlockRewardBatchFeatureTriggerLoadsThroughSettingsConfigPath() throws IOException {
		Path tempDir = Files.createTempDirectory("qortium-block-reward-batch-trigger-config");
		Path chainConfigPath = tempDir.resolve("test-chain-v2-feature-trigger.json");
		Path settingsPath = tempDir.resolve("test-settings-feature-trigger.json");

		Files.writeString(chainConfigPath, buildChainJsonWithBlockRewardBatchFeatureTrigger(), StandardCharsets.UTF_8);
		Files.writeString(settingsPath, buildSettingsJson(chainConfigPath), StandardCharsets.UTF_8);

		Settings.fileInstance(settingsPath.toString());

		assertNotEquals(LEGACY_FALLBACK_HEIGHT, BlockChain.getInstance().getBlockRewardBatchStartHeight());
		assertEquals(FEATURE_TRIGGER_HEIGHT, BlockChain.getInstance().getBlockRewardBatchStartHeight());
	}

	private static String buildChainJsonWithBlockRewardBatchFeatureTrigger() throws IOException {
		String chainJson = readBundledResource("test-chain-v2.json");
		String marker = "\t\"mempowSettings\": {";
		String featureTriggers = "\t\"featureTriggers\": {\n"
				+ "\t\t\"blockRewardBatchStartHeight\": " + FEATURE_TRIGGER_HEIGHT + "\n"
				+ "\t},\n"
				+ marker;

		String configuredChainJson = chainJson.replace(marker, featureTriggers);
		assertNotEquals("Failed to insert featureTriggers block into test chain JSON", chainJson, configuredChainJson);
		return configuredChainJson;
	}

	private static String buildSettingsJson(Path chainConfigPath) {
		String escapedChainConfigPath = chainConfigPath.toString().replace("\\", "\\\\");

		return "{\n"
				+ "  \"repositoryPath\": \"testdb\",\n"
				+ "  \"restrictedApi\": false,\n"
				+ "  \"blockchainConfig\": \"" + escapedChainConfigPath + "\",\n"
				+ "  \"exportPath\": \"qortium-backup-test\",\n"
				+ "  \"bootstrap\": false,\n"
				+ "  \"wipeUnconfirmedOnStart\": false,\n"
				+ "  \"testNtpOffset\": 0,\n"
				+ "  \"minPeers\": 0,\n"
				+ "  \"pruneBlockLimit\": 100\n"
				+ "}\n";
	}

	private static String readBundledResource(String resourceName) throws IOException {
		try (InputStream in = BlockRewardBatchFeatureTriggerConfigTests.class.getClassLoader().getResourceAsStream(resourceName)) {
			assertNotNull("Bundled resource not found: " + resourceName, in);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
