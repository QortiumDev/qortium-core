package org.qortium.block;

import org.junit.Test;

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
}
