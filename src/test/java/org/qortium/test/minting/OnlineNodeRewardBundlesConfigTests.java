package org.qortium.test.minting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Test;
import org.qortium.block.Block;
import org.qortium.block.BlockChain;
import org.qortium.data.block.BlockData;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;
import org.qortium.transform.Transformer;
import org.qortium.transform.block.BlockTransformer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class OnlineNodeRewardBundlesConfigTests extends Common {

	private static final long BATCH_START_HEIGHT = 900L;
	private static final long PAYOUT_HEIGHT = 1000L;

	@After
	public void restoreDefaultTestSettings() {
		URL defaultSettingsUrl = OnlineNodeRewardBundlesConfigTests.class.getClassLoader().getResource("test-settings-v2.json");
		assertNotNull("Default test settings JSON file not found", defaultSettingsUrl);
		Settings.fileInstance(defaultSettingsUrl.getPath());
	}

	@Test
	public void testTriggerSelectsCaptureBoundaryAndBlockVersion() throws IOException {
		loadConfig(PAYOUT_HEIGHT, BATCH_START_HEIGHT);

		assertEquals(PAYOUT_HEIGHT, BlockChain.getInstance().getOnlineNodeRewardBundlesPayoutHeight());
		assertEquals(997L, BlockChain.getInstance().getOnlineNodeRewardBundlesCaptureStartHeight());

		Block parentBeforeBoundary = blockAtHeight(995);
		Block parentAtBoundary = blockAtHeight(996);
		assertEquals(Block.CURRENT_VERSION, parentBeforeBoundary.getNextBlockVersion());
		assertEquals(Block.ONLINE_NODE_REWARD_BUNDLES_VERSION, parentAtBoundary.getNextBlockVersion());
	}

	@Test
	public void testShippedPreviewTransitionHeightsAndRepresentations() throws IOException {
		ObjectNode chainJson = readChainConfig("previewchain.json");
		assertEquals(100, chainJson.path("blockRewardBatchSize").asInt());
		assertEquals(10, chainJson.path("blockRewardBatchAccountsBlockCount").asInt());
		assertEquals(100000L,
				chainJson.path("featureTriggers").path("onlineNodeRewardBundlesPayoutHeight").asLong());

		loadConfig(chainJson);
		assertEquals(99990L, BlockChain.getInstance().getOnlineNodeRewardBundlesCaptureStartHeight());

		assertRepresentationAtHeight(99989, Block.CURRENT_VERSION);
		assertRepresentationAtHeight(99990, Block.ONLINE_NODE_REWARD_BUNDLES_VERSION);
		assertRepresentationAtHeight(99999, Block.ONLINE_NODE_REWARD_BUNDLES_VERSION);
		assertRepresentationAtHeight(100000, Block.ONLINE_NODE_REWARD_BUNDLES_VERSION);
	}

	@Test
	public void testOmittedTriggerIsDisabled() throws IOException {
		loadConfig(null, BATCH_START_HEIGHT);
		assertEquals(BlockChain.FEATURE_TRIGGER_DISABLED_HEIGHT,
				BlockChain.getInstance().getOnlineNodeRewardBundlesPayoutHeight());
		assertEquals(BlockChain.FEATURE_TRIGGER_DISABLED_HEIGHT,
				BlockChain.getInstance().getOnlineNodeRewardBundlesCaptureStartHeight());
	}

	@Test
	public void testRejectsNonpositiveTrigger() {
		assertThrows(RuntimeException.class, () -> loadConfig(0L, BATCH_START_HEIGHT));
	}

	@Test
	public void testRejectsTriggerOutsideBatchBoundary() {
		assertThrows(RuntimeException.class, () -> loadConfig(PAYOUT_HEIGHT + 1L, BATCH_START_HEIGHT));
	}

	@Test
	public void testRejectsCaptureWindowAtBatchActivationBoundary() {
		assertThrows(RuntimeException.class, () -> loadConfig(PAYOUT_HEIGHT, 997L));
	}

	private static Block blockAtHeight(int height) {
		BlockData blockData = new BlockData(Block.CURRENT_VERSION, new byte[BlockTransformer.BLOCK_SIGNATURE_LENGTH],
				0, 0L, new byte[Transformer.SIGNATURE_LENGTH], height, 1L,
				new byte[Transformer.PUBLIC_KEY_LENGTH], new byte[Transformer.SIGNATURE_LENGTH], 0, 0L);
		return new Block(null, blockData);
	}

	private static void assertRepresentationAtHeight(int height, int expectedVersion) {
		int wrongVersion = expectedVersion == Block.CURRENT_VERSION
				? Block.ONLINE_NODE_REWARD_BUNDLES_VERSION
				: Block.CURRENT_VERSION;

		assertEquals(expectedVersion, blockAtHeight(height - 1).getNextBlockVersion());
		assertTrue(Block.isVersionValidAtHeight(expectedVersion, height));
		assertFalse("Consensus must reject the wrong block representation at height " + height,
				Block.isVersionValidAtHeight(wrongVersion, height));
	}

	private static void loadConfig(Long payoutHeight, long batchStartHeight) throws IOException {
		ObjectNode chainJson = readChainConfig("test-chain-v2.json");

		ObjectNode featureTriggers = (ObjectNode) chainJson.with("featureTriggers");
		featureTriggers.put("blockRewardBatchStartHeight", batchStartHeight);
		if (payoutHeight == null)
			featureTriggers.remove("onlineNodeRewardBundlesPayoutHeight");
		else
			featureTriggers.put("onlineNodeRewardBundlesPayoutHeight", payoutHeight);

		loadConfig(chainJson);
	}

	private static ObjectNode readChainConfig(String resourceName) throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
		try (InputStream in = OnlineNodeRewardBundlesConfigTests.class.getClassLoader().getResourceAsStream(resourceName)) {
			assertNotNull("Bundled chain JSON file not found: " + resourceName, in);
			return (ObjectNode) objectMapper.readTree(in);
		}
	}

	private static void loadConfig(ObjectNode chainJson) throws IOException {
		ObjectMapper objectMapper = new ObjectMapper();
		Path tempDir = Files.createTempDirectory("qortium-online-node-bundle-config");
		Path chainConfigPath = tempDir.resolve("test-chain-v2-online-node-bundles.json");
		Path settingsPath = tempDir.resolve("test-settings-online-node-bundles.json");
		objectMapper.writeValue(chainConfigPath.toFile(), chainJson);
		Files.writeString(settingsPath, buildSettingsJson(chainConfigPath), StandardCharsets.UTF_8);

		Settings.fileInstance(settingsPath.toString());
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
}
