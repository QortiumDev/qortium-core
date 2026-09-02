package org.qortium.crosschain;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The method family must follow the negotiated protocol version, and both families must normalise to the
 * same internal shapes. Fixtures are taken from the protocol specification's own examples and from live
 * replies captured from ElectrumX 2.0.0 (DGB electrum1.cipig.net:20059) and ElectrumX 1.15.0
 * (LTC electrum.ltc.xurious.com:50002) on 2026-09-02.
 */
public class ElectrumMethodsTests {

	/** P2PKH paying an all-zero hash160. */
	private static final byte[] PROBE_SCRIPT = hex("76a914" + "00".repeat(20) + "88ac");
	/**
	 * sha256 of PROBE_SCRIPT, byte-reversed, as the protocol defines a scripthash. Computed independently:
	 * sha256 is 75def5fc...79b8ac, so the reversed form is the value below.
	 */
	private static final String PROBE_SCRIPT_HASH = "acb87996319dca2c2e2afd6c0f7514b18e72e204069718976e1abdc8fcf5de75";
	private static final String PROBE_SCRIPT_HEX = "76a914000000000000000000000000000000000000000088ac";

	private static byte[] hex(String value) {
		byte[] bytes = new byte[value.length() / 2];
		for (int index = 0; index < bytes.length; index++)
			bytes[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
		return bytes;
	}

	private static Object json(String value) {
		try {
			return new JSONParser().parse(value);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private static ElectrumMethods methods(String version) {
		return ElectrumMethods.forVersion(version == null ? null : ElectrumProtocolVersion.parse(version).orElseThrow());
	}

	// --- family selection ---

	@Test
	public void testScriptHashFamilyBelowOneSeven() {
		for (String version : List.of("1.4", "1.4.2", "1.4.3", "1.5.3", "1.6", "1.6.0")) {
			ElectrumMethods electrumMethods = methods(version);

			assertFalse(version, electrumMethods.usesScriptPubKey());
			assertEquals(version, "blockchain.scripthash.get_history", electrumMethods.getHistory(PROBE_SCRIPT).getMethod());
			assertEquals(version, PROBE_SCRIPT_HASH, electrumMethods.getHistory(PROBE_SCRIPT).getParams()[0]);
		}
	}

	@Test
	public void testScriptPubKeyFamilyFromOneSeven() {
		for (String version : List.of("1.7", "1.7.0", "1.7.9")) {
			ElectrumMethods electrumMethods = methods(version);

			assertTrue(version, electrumMethods.usesScriptPubKey());
			assertEquals(version, "blockchain.scriptpubkey.get_history", electrumMethods.getHistory(PROBE_SCRIPT).getMethod());
			assertEquals(version, PROBE_SCRIPT_HEX, electrumMethods.getHistory(PROBE_SCRIPT).getParams()[0]);
		}
	}

	@Test
	public void testUnknownVersionFallsBackToTheScriptHashFamily() {
		assertFalse(methods(null).usesScriptPubKey());
		assertEquals("blockchain.scripthash.listunspent", methods(null).listUnspent(PROBE_SCRIPT).getMethod());
	}

	@Test
	public void testEveryScriptCallSwitchesFamilyTogether() {
		ElectrumMethods legacy = methods("1.4");
		ElectrumMethods modern = methods("1.7");

		assertEquals("blockchain.scripthash.get_balance", legacy.getBalance(PROBE_SCRIPT).getMethod());
		assertEquals("blockchain.scripthash.get_history", legacy.getHistory(PROBE_SCRIPT).getMethod());
		assertEquals("blockchain.scripthash.get_mempool", legacy.getMempool(PROBE_SCRIPT).getMethod());
		assertEquals("blockchain.scripthash.listunspent", legacy.listUnspent(PROBE_SCRIPT).getMethod());
		assertEquals("blockchain.scripthash.subscribe", legacy.subscribe(PROBE_SCRIPT).getMethod());
		assertEquals("blockchain.scripthash.unsubscribe", legacy.unsubscribe(PROBE_SCRIPT).getMethod());

		assertEquals("blockchain.scriptpubkey.get_balance", modern.getBalance(PROBE_SCRIPT).getMethod());
		assertEquals("blockchain.scriptpubkey.get_history", modern.getHistory(PROBE_SCRIPT).getMethod());
		assertEquals("blockchain.scriptpubkey.get_mempool", modern.getMempool(PROBE_SCRIPT).getMethod());
		assertEquals("blockchain.scriptpubkey.listunspent", modern.listUnspent(PROBE_SCRIPT).getMethod());
		assertEquals("blockchain.scriptpubkey.subscribe", modern.subscribe(PROBE_SCRIPT).getMethod());
		assertEquals("blockchain.scriptpubkey.unsubscribe", modern.unsubscribe(PROBE_SCRIPT).getMethod());
	}

	@Test
	public void testRelayFeeMovesToMempoolInfoAtOneSix() {
		assertEquals("blockchain.relayfee", methods("1.4").relayFee().getMethod());
		assertEquals("blockchain.relayfee", methods("1.5.3").relayFee().getMethod());
		assertEquals("mempool.get_info", methods("1.6").relayFee().getMethod());
		assertEquals("mempool.get_info", methods("1.7").relayFee().getMethod());
	}

	@Test
	public void testBlockHeadersArrayFromOneSix() {
		assertFalse(methods("1.4").usesBlockHeadersArray());
		assertFalse(methods("1.5.3").usesBlockHeadersArray());
		assertTrue(methods("1.6").usesBlockHeadersArray());
		assertTrue(methods("1.7").usesBlockHeadersArray());
	}

	@Test
	public void testUnsubscribeRequiresOneFourTwo() {
		assertFalse(methods("1.4").supportsUnsubscribe());
		assertFalse(methods("1.4.1").supportsUnsubscribe());
		assertTrue(methods("1.4.2").supportsUnsubscribe());
		assertTrue(methods("1.7").supportsUnsubscribe());
	}

	@Test
	public void testSubscriptionsAreKeyedByScriptHashAtEveryVersion() {
		// 1.7 renamed the notification method but kept the scripthash in its payload.
		assertEquals(PROBE_SCRIPT_HASH, ElectrumMethods.subscriptionKey(PROBE_SCRIPT));
		assertTrue(ElectrumMethods.isSubscriptionNotification("blockchain.scripthash.subscribe"));
		assertTrue(ElectrumMethods.isSubscriptionNotification("blockchain.scriptpubkey.subscribe"));
		assertFalse(ElectrumMethods.isSubscriptionNotification("blockchain.headers.subscribe"));
	}

	@Test
	public void testWalletProbeScriptIsWellFormedAndUnused() {
		// The probe must be a real script (1.7 sends the script itself) that no chain has history for.
		assertEquals("76a914e9310ed032af96579e8d680ffa72c7dd132fdbdf88ac",
				ElectrumMethods.scriptPubKeyHex(ElectrumX.WALLET_CAPABILITY_PROBE_SCRIPT));
		assertEquals(25, ElectrumX.WALLET_CAPABILITY_PROBE_SCRIPT.length);
	}

	@Test
	public void testScriptEncodings() {
		assertEquals(PROBE_SCRIPT_HASH, ElectrumMethods.scriptHash(PROBE_SCRIPT));
		assertEquals(PROBE_SCRIPT_HEX, ElectrumMethods.scriptPubKeyHex(PROBE_SCRIPT));
	}

	// --- result normalisation ---

	@Test
	public void testHistoryNormalisesFromBothShapes() {
		Object legacyResponse = json("[{\"height\":200004,\"tx_hash\":\"acc3758bd2a26f869fcc67d48ff30b96464d476bca82c1cd6656e7d506816412\"}]");
		Object modernResponse = json("{\"history\":[{\"height\":200004,\"tx_hash\":\"acc3758bd2a26f869fcc67d48ff30b96464d476bca82c1cd6656e7d506816412\"}]}");

		JSONArray fromLegacy = ElectrumMethods.normalizeHistory(legacyResponse).orElseThrow();
		JSONArray fromModern = ElectrumMethods.normalizeHistory(modernResponse).orElseThrow();

		assertEquals(1, fromLegacy.size());
		assertEquals(fromLegacy, fromModern);
		assertEquals(200004L, ((JSONObject) fromModern.get(0)).get("height"));
	}

	@Test
	public void testEmptyHistoryNormalisesFromBothShapes() {
		assertEquals(0, ElectrumMethods.normalizeHistory(json("[]")).orElseThrow().size());
		assertEquals(0, ElectrumMethods.normalizeHistory(json("{\"history\":[]}")).orElseThrow().size());
	}

	@Test
	public void testUnspentOutputsNormaliseFromBothShapes() {
		Object legacyResponse = json("[{\"tx_hash\":\"acc3758bd2a26f869fcc67d48ff30b96464d476bca82c1cd6656e7d506816412\",\"tx_pos\":0,\"height\":437146,\"value\":45318048}]");
		Object modernResponse = json("{\"utxos\":[{\"tx_hash\":\"acc3758bd2a26f869fcc67d48ff30b96464d476bca82c1cd6656e7d506816412\",\"tx_pos\":0,\"height\":437146,\"value\":45318048}]}");

		JSONArray fromLegacy = ElectrumMethods.normalizeUnspentOutputs(legacyResponse).orElseThrow();
		JSONArray fromModern = ElectrumMethods.normalizeUnspentOutputs(modernResponse).orElseThrow();

		assertEquals(fromLegacy, fromModern);
		assertEquals(45318048L, ((JSONObject) fromModern.get(0)).get("value"));
	}

	@Test
	public void testHistoryAndUnspentWrappersAreNotInterchangeable() {
		// A history reply must not be read as unspent outputs just because both are wrapped objects.
		assertEquals(Optional.empty(), ElectrumMethods.normalizeUnspentOutputs(json("{\"history\":[]}")));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeHistory(json("{\"utxos\":[]}")));
	}

	@Test
	public void testUnreadableScriptResponsesAreRejected() {
		assertEquals(Optional.empty(), ElectrumMethods.normalizeHistory(null));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeHistory("unknown method"));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeUnspentOutputs(null));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeUnspentOutputs("unknown method"));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBalance(null));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBalance(json("[]")));
	}

	@Test
	public void testBalanceIsTheSameObjectInBothFamilies() {
		JSONObject balance = (JSONObject) ElectrumMethods.normalizeBalance(json("{\"confirmed\":103873966,\"unconfirmed\":23684400}")).orElseThrow();

		assertEquals(103873966L, balance.get("confirmed"));
		assertEquals(23684400L, balance.get("unconfirmed"));
	}

	@Test
	public void testBlockHeadersNormaliseFromTheConcatenatedShape() {
		ElectrumMethods.BlockHeadersResult headers =
				ElectrumMethods.normalizeBlockHeaders(json("{\"hex\":\"aabbcc\",\"count\":2,\"max\":2016}")).orElseThrow();

		assertFalse(headers.isSplit());
		assertEquals(2, headers.getCount());
		assertEquals("aabbcc", headers.getConcatenatedHex());
	}

	@Test
	public void testBlockHeadersNormaliseFromTheArrayShape() {
		ElectrumMethods.BlockHeadersResult headers =
				ElectrumMethods.normalizeBlockHeaders(json("{\"count\":2,\"max\":2016,\"headers\":[\"aabb\",\"ccdd\"]}")).orElseThrow();

		assertTrue(headers.isSplit());
		assertEquals(2, headers.getCount());
		assertEquals(List.of("aabb", "ccdd"), headers.getHeaderHexes());
	}

	@Test
	public void testMalformedBlockHeadersAreRejected() {
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBlockHeaders(json("{\"max\":2016}")));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBlockHeaders(json("{\"count\":2,\"headers\":[1,2]}")));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBlockHeaders(json("[]")));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBlockHeaders(null));
	}

	@Test
	public void testRelayFeeNormalisesFromBothShapes() {
		assertEquals(0.001d, ElectrumMethods.normalizeRelayFee(json("0.001")).orElseThrow(), 1e-9d);
		assertEquals(0.001d, ElectrumMethods.normalizeRelayFee(
				json("{\"mempoolminfee\":0.001,\"minrelaytxfee\":0.001,\"incrementalrelayfee\":0.0001}")).orElseThrow(), 1e-9d);
		assertEquals(Optional.empty(), ElectrumMethods.normalizeRelayFee(json("{\"mempoolminfee\":0.001}")));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeRelayFee("unknown method"));
	}

	// --- wallet-capability probe follows the family ---

	@Test
	public void testWalletProbeAcceptsBothFamiliesAndRejectsUnknownMethodReplies() throws Exception {
		ElectrumX.validateWalletRpcResponses(json("[]"), json("[]"));
		ElectrumX.validateWalletRpcResponses(json("{\"history\":[]}"), json("{\"utxos\":[]}"));

		try {
			ElectrumX.validateWalletRpcResponses(null, json("[]"));
			throw new AssertionError("An unknown-method history reply must reject the server");
		} catch (ForeignBlockchainException.NetworkException e) {
			assertTrue(e.getMessage().contains("get_history"));
		}

		try {
			ElectrumX.validateWalletRpcResponses(json("[]"), json("{\"history\":[]}"));
			throw new AssertionError("A history-shaped reply must not pass as unspent outputs");
		} catch (ForeignBlockchainException.NetworkException e) {
			assertTrue(e.getMessage().contains("listunspent"));
		}
	}
}
