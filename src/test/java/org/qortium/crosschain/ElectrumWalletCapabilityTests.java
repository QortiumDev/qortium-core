package org.qortium.crosschain;

import org.json.simple.JSONArray;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ElectrumWalletCapabilityTests {

	@Test
	public void testWalletCapabilityAcceptsArrayResponses() throws ForeignBlockchainException {
		ElectrumX.validateWalletRpcResponses(new JSONArray(), new JSONArray());
	}

	@Test
	public void testWalletCapabilityRejectsMissingHistorySupport() {
		try {
			ElectrumX.validateWalletRpcResponses(null, new JSONArray());
			fail("Missing history support must reject the Electrum server");
		} catch (ForeignBlockchainException.NetworkException e) {
			assertTrue(e.getMessage().contains("get_history"));
		}
	}

	@Test
	public void testWalletCapabilityRejectsMissingUnspentSupport() {
		try {
			ElectrumX.validateWalletRpcResponses(new JSONArray(), "unknown method");
			fail("Missing unspent-output support must reject the Electrum server");
		} catch (ForeignBlockchainException.NetworkException e) {
			assertTrue(e.getMessage().contains("listunspent"));
		}
	}
}
