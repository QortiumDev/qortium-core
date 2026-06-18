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
				+ "\"onlineAccountsSignatureV2Height\":27000,"
				+ "\"assetOrderBoundsHeight\":27000,"
				+ "\"stableParameter\":\"same\""
				+ "}";
		String secondConfig = "{"
				+ "\"networkId\":\"qortium-preview\","
				+ "\"checkpoints\":[{\"height\":25000,\"signature\":\"second\"}],"
				+ "\"onlineAccountsSignatureV2Height\":30000,"
				+ "\"assetOrderBoundsHeight\":31000,"
				+ "\"stableParameter\":\"same\""
				+ "}";

		assertEquals(hash(firstConfig), hash(secondConfig));
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
	public void testOtherConfigChangesStillAffectHash() {
		String firstConfig = "{"
				+ "\"networkId\":\"qortium-preview\","
				+ "\"onlineAccountsSignatureV2Height\":27000,"
				+ "\"assetOrderBoundsHeight\":27000,"
				+ "\"stableParameter\":\"first\""
				+ "}";
		String secondConfig = "{"
				+ "\"networkId\":\"qortium-preview\","
				+ "\"onlineAccountsSignatureV2Height\":27000,"
				+ "\"assetOrderBoundsHeight\":27000,"
				+ "\"stableParameter\":\"second\""
				+ "}";

		assertNotEquals(hash(firstConfig), hash(secondConfig));
	}

	private static String hash(String json) {
		return BlockChain.computeChainConfigHash(json.getBytes(StandardCharsets.UTF_8));
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
