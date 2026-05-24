package org.qortal.test;

import org.junit.Test;
import org.qortal.block.BlockChain;
import org.qortal.test.common.Common;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MemoryPoWSettingsTests extends Common {

	private static final String DEFAULT_MEMPOW_SETTINGS = "\t\"mempowSettings\": {\n"
			+ "\t\t\"feeAlternativeDifficulty\": 16,\n"
			+ "\t\t\"arbitraryDifficulty\": 14,\n"
			+ "\t\t\"chatDifficulty\": 8,\n"
			+ "\t\t\"messageConfirmableDifficulty\": 16,\n"
			+ "\t\t\"messageUnconfirmableDifficulty\": 12\n"
			+ "\t},\n";

	@Test
	public void testConfiguredMemoryPoWSettings() throws Exception {
		Common.useDefaultSettings();

		BlockChain blockChain = BlockChain.getInstance();
		assertEquals(16, blockChain.getMempowFeeAlternativeDifficulty());
		assertEquals(14, blockChain.getArbitraryTransactionPowDifficulty());
		assertEquals(8, blockChain.getChatPowDifficulty());
		assertEquals(16, blockChain.getMessagePowDifficultyConfirmable());
		assertEquals(12, blockChain.getMessagePowDifficultyUnconfirmable());
	}

	@Test
	public void testCustomMemoryPoWSettingsLoaded() throws Exception {
		Common.useDefaultSettings();

		String defaultConfig = loadDefaultTestChainConfig();
		String customSettings = "\t\"mempowSettings\": {\n"
				+ "\t\t\"feeAlternativeDifficulty\": 3,\n"
				+ "\t\t\"arbitraryDifficulty\": 4,\n"
				+ "\t\t\"chatDifficulty\": 5,\n"
				+ "\t\t\"messageConfirmableDifficulty\": 8,\n"
				+ "\t\t\"messageUnconfirmableDifficulty\": 9\n"
				+ "\t},\n";

		loadConfig(defaultConfig.replace(DEFAULT_MEMPOW_SETTINGS, customSettings));

		try {
			BlockChain blockChain = BlockChain.getInstance();
			assertEquals(3, blockChain.getMempowFeeAlternativeDifficulty());
			assertEquals(4, blockChain.getArbitraryTransactionPowDifficulty());
			assertEquals(5, blockChain.getChatPowDifficulty());
			assertEquals(8, blockChain.getMessagePowDifficultyConfirmable());
			assertEquals(9, blockChain.getMessagePowDifficultyUnconfirmable());
		} finally {
			Common.useDefaultSettings();
		}
	}

	@Test
	public void testInvalidMemoryPoWSettingsRejected() throws Exception {
		Common.useDefaultSettings();

		String defaultConfig = loadDefaultTestChainConfig();

		assertInvalidConfig(defaultConfig.replace(DEFAULT_MEMPOW_SETTINGS, ""),
				"No \"mempowSettings\" entry found");

		assertInvalidConfig(defaultConfig.replace("\"feeAlternativeDifficulty\": 16", "\"feeAlternativeDifficulty\": -1"),
				"Invalid \"mempowSettings.feeAlternativeDifficulty\"");

		assertInvalidConfig(defaultConfig.replace("\"chatDifficulty\": 8", "\"chatDifficulty\": 32"),
				"Invalid \"mempowSettings.chatDifficulty\"");

		assertInvalidConfig(defaultConfig.replace("\"messageUnconfirmableDifficulty\": 12", "\"messageUnconfirmableDifficulty\": 32"),
				"Invalid \"mempowSettings.messageUnconfirmableDifficulty\"");

		assertInvalidConfig(defaultConfig.replace("\t\t\"arbitraryDifficulty\": 14,\n", ""),
				"No \"mempowSettings.arbitraryDifficulty\" entry found");
	}

	private static String loadDefaultTestChainConfig() throws Exception {
		URL testChainUrl = Common.class.getClassLoader().getResource("test-chain-v2.json");
		assertNotNull(testChainUrl);
		return Files.readString(Paths.get(testChainUrl.toURI()), StandardCharsets.UTF_8);
	}

	private static void loadConfig(String config) throws Exception {
		Path tempDir = Files.createTempDirectory("mempow-settings-config");
		Path configPath = tempDir.resolve("blockchain.json");

		try {
			Files.writeString(configPath, config, StandardCharsets.UTF_8);
			BlockChain.fileInstance(tempDir.toString() + java.io.File.separator, configPath.getFileName().toString());
		} finally {
			Files.deleteIfExists(configPath);
			Files.deleteIfExists(tempDir);
		}
	}

	private static void assertInvalidConfig(String config, String expectedMessageFragment) throws Exception {
		try {
			loadConfig(config);
			fail("Expected invalid blockchain config");
		} catch (RuntimeException e) {
			assertTrue(e.getMessage().contains(expectedMessageFragment));
		}
	}

}
