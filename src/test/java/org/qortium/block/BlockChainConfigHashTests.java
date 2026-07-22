package org.qortium.block;

import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.junit.Test;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class BlockChainConfigHashTests {

	@Test
	public void testCheckpointsAndFeatureTriggersAreExcludedFromHash() {
		String firstConfig = "{"
				+ "\"networkId\":\"qortium-preview\","
				+ "\"checkpoints\":[{\"height\":24000,\"signature\":\"first\"}],"
				+ "\"featureTriggers\":{"
				+ "\"onlineAccountsSignatureV2Height\":27000,"
				+ "\"assetOrderBoundsHeight\":27000"
				+ "},"
				+ "\"stableParameter\":\"same\""
				+ "}";
		String secondConfig = "{"
				+ "\"networkId\":\"qortium-preview\","
				+ "\"checkpoints\":[{\"height\":25000,\"signature\":\"second\"}],"
				+ "\"featureTriggers\":{"
				+ "\"onlineAccountsSignatureV2Height\":30000,"
				+ "\"assetOrderBoundsHeight\":31000"
				+ "},"
				+ "\"stableParameter\":\"same\""
				+ "}";

		assertEquals(hash(firstConfig), hash(secondConfig));
	}

	@Test
	public void testLegacyTopLevelFeatureTriggersAreIncludedInHash() {
		String firstConfig = "{"
				+ "\"networkId\":\"qortium-preview\","
				+ "\"onlineAccountsSignatureV2Height\":27000,"
				+ "\"assetOrderBoundsHeight\":27000,"
				+ "\"stableParameter\":\"same\""
				+ "}";
		String secondConfig = "{"
				+ "\"networkId\":\"qortium-preview\","
				+ "\"onlineAccountsSignatureV2Height\":30000,"
				+ "\"assetOrderBoundsHeight\":31000,"
				+ "\"stableParameter\":\"same\""
				+ "}";

		assertNotEquals(hash(firstConfig), hash(secondConfig));
	}

	@Test
	public void testFeatureTriggersObjectIsExcludedFromHash() {
		String firstConfig = "{"
				+ "\"networkId\":\"qortium-preview\","
				+ "\"featureTriggers\":{"
				+ "\"futureTrigger\":27000,"
				+ "\"anotherFutureTrigger\":31000"
				+ "},"
				+ "\"stableParameter\":\"same\""
				+ "}";
		String secondConfig = "{"
				+ "\"networkId\":\"qortium-preview\","
				+ "\"featureTriggers\":{"
				+ "\"futureTrigger\":42000,"
				+ "\"newlyAddedTrigger\":50000"
				+ "},"
				+ "\"stableParameter\":\"same\""
				+ "}";

		assertEquals(hash(firstConfig), hash(secondConfig));
	}

	@Test
	public void testFeatureTriggersObjectCanCarryUnknownTriggers() throws Exception {
		BlockChain blockChain = unmarshal("{"
				+ "\"featureTriggers\":{"
				+ "\"futureTrigger\":27000,"
				+ "\"anotherFutureTrigger\":31000"
				+ "}"
				+ "}");

		assertEquals(27000L, blockChain.getFeatureTriggerHeight("futureTrigger"));
		assertEquals(31000L, blockChain.getFeatureTriggerHeight("anotherFutureTrigger"));
		assertEquals(BlockChain.FEATURE_TRIGGER_DISABLED_HEIGHT, blockChain.getFeatureTriggerHeight("missingTrigger"));
	}

	@Test
	public void testKnownFeatureTriggersPreferContainerValues() throws Exception {
		BlockChain blockChain = unmarshal("{"
				+ "\"onlineAccountsSignatureV2Height\":27000,"
				+ "\"assetOrderBoundsHeight\":27000,"
				+ "\"featureTriggers\":{"
				+ "\"onlineAccountsSignatureV2Height\":30000,"
				+ "\"assetOrderBoundsHeight\":31000"
				+ "}"
				+ "}");

		assertEquals(30000L, blockChain.getOnlineAccountsSignatureV2Height());
		assertEquals(31000L, blockChain.getAssetOrderBoundsHeight());
	}

	@Test
	public void testKnownFeatureTriggersFallBackToLegacyTopLevelValues() throws Exception {
		BlockChain blockChain = unmarshal("{"
				+ "\"onlineAccountsSignatureV2Height\":27000,"
				+ "\"assetOrderBoundsHeight\":31000"
				+ "}");

		assertEquals(27000L, blockChain.getOnlineAccountsSignatureV2Height());
		assertEquals(31000L, blockChain.getAssetOrderBoundsHeight());
	}

	/** The chain-config hash the live Previewnet network advertises in its peer handshake.
	 * Peers whose hash differs are rejected at handshake, so a change to this value is a
	 * network-wide flag day: every node must adopt the new config at once or be partitioned
	 * (as happened with v1.3.4, which dropped a hashed legacy top-level field and could no
	 * longer peer with the network). Schedule consensus changes via "featureTriggers" —
	 * excluded from the hash — and only change this pin as a deliberate, coordinated cutover. */
	private static final String LIVE_PREVIEWNET_CONFIG_HASH =
			"8e655fc30e325d11ec44d350dd456ad80bff0a507935133edc3db5345e9253e5";

	@Test
	public void testShippedPreviewnetConfigHashMatchesLiveNetwork() throws Exception {
		assertEquals(LIVE_PREVIEWNET_CONFIG_HASH,
				BlockChain.computeChainConfigHash(readBundledConfig("previewchain.json")));
	}

	@Test
	public void testShippedPreviewnetEnablesAtMapsWithoutHashingTheNewStepCost() throws Exception {
		BlockChain blockChain = unmarshal(new String(readBundledConfig("previewchain.json"), StandardCharsets.UTF_8));

		assertEquals(70_000L, blockChain.getAtMapStorageHeight());
		assertEquals(100, blockChain.getCiyamAtSettings().mapEntryStepCost);
	}

	@Test
	public void testShippedMainnetLeavesAtMapsDisabled() throws Exception {
		BlockChain blockChain = unmarshal(new String(readBundledConfig("blockchain.json"), StandardCharsets.UTF_8));

		assertEquals(BlockChain.FEATURE_TRIGGER_DISABLED_HEIGHT, blockChain.getAtMapStorageHeight());
		assertEquals(100, blockChain.getCiyamAtSettings().mapEntryStepCost);
	}

	@Test
	public void testShippedPreviewnetSchedulesNewAtSafetyTriggersAtSeventyThousand() throws Exception {
		BlockChain blockChain = unmarshal(new String(readBundledConfig("previewchain.json"), StandardCharsets.UTF_8));

		// New pre-70,000 hardening triggers ride the same activation height as the other AT features.
		assertEquals(70_000L, blockChain.getAtSweepAssetsOnFinishHeight());
		assertEquals(70_000L, blockChain.getAtHashingStepCostHeight());
		// Hashing step cost is a Java-default chain parameter, never a shipped-JSON key.
		assertEquals(20, blockChain.getCiyamAtSettings().hashingStepCost);
	}

	@Test
	public void testNewAtSafetyTriggersDoNotChangeTheLivePreviewnetHash() throws Exception {
		// The shipped previewnet config, with the two new featureTriggers present, must still hash to
		// exactly the value the live network advertises: featureTriggers are excluded from the hash, so
		// scheduling atSweepAssetsOnFinishHeight / atHashingStepCostHeight is not a peering flag day.
		assertEquals(LIVE_PREVIEWNET_CONFIG_HASH,
				BlockChain.computeChainConfigHash(readBundledConfig("previewchain.json")));
	}

	@Test
	public void testAddingTheNewSafetyTriggersToFeatureTriggersLeavesHashUnchanged() {
		String withoutNewTriggers = "{"
				+ "\"networkId\":\"qortium-preview\","
				+ "\"featureTriggers\":{"
				+ "\"atPayoutSolvencyHeight\":70000,"
				+ "\"atMapStorageHeight\":70000"
				+ "},"
				+ "\"stableParameter\":\"same\""
				+ "}";
		String withNewTriggers = "{"
				+ "\"networkId\":\"qortium-preview\","
				+ "\"featureTriggers\":{"
				+ "\"atPayoutSolvencyHeight\":70000,"
				+ "\"atMapStorageHeight\":70000,"
				+ "\"atSweepAssetsOnFinishHeight\":70000,"
				+ "\"atHashingStepCostHeight\":70000"
				+ "},"
				+ "\"stableParameter\":\"same\""
				+ "}";

		assertEquals(hash(withoutNewTriggers), hash(withNewTriggers));
	}

	@Test
	public void testOtherConfigChangesStillAffectHash() {
		String firstConfig = "{"
				+ "\"networkId\":\"qortium-preview\","
				+ "\"featureTriggers\":{"
				+ "\"onlineAccountsSignatureV2Height\":27000,"
				+ "\"assetOrderBoundsHeight\":27000"
				+ "},"
				+ "\"stableParameter\":\"first\""
				+ "}";
		String secondConfig = "{"
				+ "\"networkId\":\"qortium-preview\","
				+ "\"featureTriggers\":{"
				+ "\"onlineAccountsSignatureV2Height\":27000,"
				+ "\"assetOrderBoundsHeight\":27000"
				+ "},"
				+ "\"stableParameter\":\"second\""
				+ "}";

		assertNotEquals(hash(firstConfig), hash(secondConfig));
	}

	private static String hash(String json) {
		return BlockChain.computeChainConfigHash(json.getBytes(StandardCharsets.UTF_8));
	}

	private static byte[] readBundledConfig(String filename) throws java.io.IOException {
		try (java.io.InputStream in = BlockChainConfigHashTests.class.getClassLoader().getResourceAsStream(filename)) {
			if (in == null)
				throw new IllegalStateException("Bundled chain config not found on classpath: " + filename);
			return in.readAllBytes();
		}
	}

	private static BlockChain unmarshal(String json) throws Exception {
		JAXBContext jc = JAXBContextFactory.createContext(new Class[] {
				BlockChain.class, GenesisBlock.GenesisInfo.class
		}, null);
		Unmarshaller unmarshaller = jc.createUnmarshaller();
		unmarshaller.setProperty(UnmarshallerProperties.MEDIA_TYPE, "application/json");
		unmarshaller.setProperty(UnmarshallerProperties.JSON_INCLUDE_ROOT, false);

		return unmarshaller.unmarshal(new StreamSource(new StringReader(json)), BlockChain.class).getValue();
	}
}
