package org.qortium.block;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.junit.After;
import org.junit.Test;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class FeatureTriggerScheduleTests extends Common {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@After
	public void restoreSettings() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testPreviewAndLocalTestnetMakeExplicitRegisteredTriggerDecisions() throws Exception {
		ObjectNode preview = readJsonResource("previewchain.json");
		ObjectNode testnet = (ObjectNode) OBJECT_MAPPER.readTree(Path.of("testnet/testchain.json").toFile());
		Set<String> registered = BlockChain.getRegisteredFeatureTriggerNames();

		assertEquals(registered, fieldNames(preview.with("featureTriggers")));
		assertEquals(registered, fieldNames(testnet.with("featureTriggers")));
		assertEquals(99_990L, preview.path("featureTriggerScheduleEnforcementHeight").asLong());
		assertEquals(0L, testnet.path("featureTriggerScheduleEnforcementHeight").asLong());

		Iterator<String> names = testnet.with("featureTriggers").fieldNames();
		while (names.hasNext()) {
			String name = names.next();
			long expectedHeight = "onlineNodeRewardBundlesPayoutHeight".equals(name) ? 100L : 0L;
			assertEquals("Unexpected local-testnet height for " + name, expectedHeight,
					testnet.with("featureTriggers").path(name).asLong());
		}
		assertEquals(10, testnet.path("blockRewardBatchSize").asInt());
		assertEquals(3, testnet.path("blockRewardBatchAccountsBlockCount").asInt());
		assertEquals(97L, testnet.with("featureTriggers").path("onlineNodeRewardBundlesPayoutHeight").asLong()
				- testnet.path("blockRewardBatchAccountsBlockCount").asLong());

		loadConfig(testnet);
		assertEquals(97L, BlockChain.getInstance().getOnlineNodeRewardBundlesCaptureStartHeight());
		assertEquals(0L, BlockChain.getInstance().getFeatureTriggerScheduleEnforcementHeight());
	}

	@Test
	public void testUnknownAndMisspelledFeatureTriggersFailConfigLoad() throws Exception {
		for (String unknownName : new String[] {"futureTrigger", "atBalanceQueryHeigth"}) {
			ObjectNode chain = readJsonResource("test-chain-v2.json");
			chain.with("featureTriggers").put(unknownName, 123L);
			assertThrows(RuntimeException.class, () -> loadConfig(chain));
		}

		ObjectNode blank = readJsonResource("test-chain-v2.json");
		blank.with("featureTriggers").put(" ", 123L);
		assertThrows(RuntimeException.class, () -> loadConfig(blank));

		ObjectNode negative = readJsonResource("test-chain-v2.json");
		negative.with("featureTriggers").put("atBalanceQueryHeight", -1L);
		assertThrows(RuntimeException.class, () -> loadConfig(negative));

		ObjectNode missingHeight = readJsonResource("test-chain-v2.json");
		missingHeight.with("featureTriggers").putNull("atBalanceQueryHeight");
		assertThrows(RuntimeException.class, () -> loadConfig(missingHeight));
	}

	@Test
	public void testScheduleCommitmentUsesEffectiveFallbacksAndCanonicalOrder() throws Exception {
		BlockChain legacy = unmarshal("{"
				+ "\"onlineAccountsSignatureV2Height\":27,"
				+ "\"assetOrderBoundsHeight\":31,"
				+ "\"blockRewardBatchStartHeight\":50"
				+ "}");
		BlockChain container = unmarshal("{"
				+ "\"blockRewardBatchStartHeight\":999,"
				+ "\"featureTriggers\":{"
				+ "\"blockRewardBatchStartHeight\":50,"
				+ "\"assetOrderBoundsHeight\":31,"
				+ "\"onlineAccountsSignatureV2Height\":27"
				+ "}"
				+ "}");

		assertEquals(legacy.computeFeatureTriggerScheduleHash(), container.computeFeatureTriggerScheduleHash());

		BlockChain changed = unmarshal("{"
				+ "\"onlineAccountsSignatureV2Height\":27,"
				+ "\"assetOrderBoundsHeight\":32,"
				+ "\"blockRewardBatchStartHeight\":50"
				+ "}");
		assertNotEquals(legacy.computeFeatureTriggerScheduleHash(), changed.computeFeatureTriggerScheduleHash());
	}

	@Test
	public void testOmittedNonlegacyTriggerEqualsExplicitDisabledHeight() throws Exception {
		BlockChain omitted = unmarshal("{\"blockRewardBatchStartHeight\":0}");
		BlockChain explicit = unmarshal("{"
				+ "\"blockRewardBatchStartHeight\":0,"
				+ "\"featureTriggers\":{"
				+ "\"atMapStorageHeight\":" + BlockChain.FEATURE_TRIGGER_DISABLED_HEIGHT
				+ "}"
				+ "}");
		assertEquals(omitted.computeFeatureTriggerScheduleHash(), explicit.computeFeatureTriggerScheduleHash());
	}

	private static Set<String> fieldNames(JsonNode node) {
		Set<String> names = new HashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private static ObjectNode readJsonResource(String resourceName) throws Exception {
		try (InputStream in = FeatureTriggerScheduleTests.class.getClassLoader().getResourceAsStream(resourceName)) {
			if (in == null)
				throw new IllegalStateException("Missing test resource " + resourceName);
			return (ObjectNode) OBJECT_MAPPER.readTree(in);
		}
	}

	private static void loadConfig(ObjectNode chain) throws Exception {
		Path tempDirectory = Files.createTempDirectory("qortium-feature-trigger-registry");
		Path chainPath = tempDirectory.resolve("chain.json");
		Path settingsPath = tempDirectory.resolve("settings.json");
		OBJECT_MAPPER.writeValue(chainPath.toFile(), chain);
		String escapedPath = chainPath.toString().replace("\\", "\\\\");
		Files.writeString(settingsPath, "{\"repositoryPath\":\"testdb\",\"restrictedApi\":false,"
				+ "\"blockchainConfig\":\"" + escapedPath + "\",\"exportPath\":\"backup\","
				+ "\"bootstrap\":false,\"testNtpOffset\":0,\"minPeers\":0,\"pruneBlockLimit\":100}",
				StandardCharsets.UTF_8);
		Settings.fileInstance(settingsPath.toString());
	}

	private static BlockChain unmarshal(String json) throws Exception {
		JAXBContext context = JAXBContextFactory.createContext(new Class[] {
				BlockChain.class, GenesisBlock.GenesisInfo.class
		}, null);
		Unmarshaller unmarshaller = context.createUnmarshaller();
		unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, "application/json");
		unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, false);
		return unmarshaller.unmarshal(new StreamSource(new StringReader(json)), BlockChain.class).getValue();
	}
}
