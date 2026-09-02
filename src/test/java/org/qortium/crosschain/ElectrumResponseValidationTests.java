package org.qortium.crosschain;

import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Hostile-fixture coverage for the result normalisers.
 * <p>
 * Callers walk these results with unchecked casts, and an empty normalisation is turned into a
 * server-attributed network failure that evicts the server, so anything malformed has to be refused here
 * rather than one layer later as a ClassCastException.
 */
public class ElectrumResponseValidationTests {

	private static final String TX_HASH = "acc3758bd2a26f869fcc67d48ff30b96464d476bca82c1cd6656e7d506816412";

	private static Object json(String value) {
		try {
			return new JSONParser().parse(value);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private static Object history(String entries) {
		return json("{\"history\":[" + entries + "]}");
	}

	private static Object utxos(String entries) {
		return json("{\"utxos\":[" + entries + "]}");
	}

	// --- history ---

	@Test
	public void testWellFormedHistoryIsAccepted() {
		assertEquals(1, ElectrumMethods.normalizeHistory(
				history("{\"tx_hash\":\"" + TX_HASH + "\",\"height\":200004}")).orElseThrow().size());
	}

	@Test
	public void testMempoolHeightSemanticsSurvive() {
		// 0 means unconfirmed, -1 means unconfirmed with unconfirmed parents; both are legitimate.
		for (String height : new String[] {"0", "-1"})
			assertTrue(height, ElectrumMethods.normalizeHistory(
					history("{\"tx_hash\":\"" + TX_HASH + "\",\"height\":" + height + "}")).isPresent());
	}

	@Test
	public void testMalformedHistoryEntriesAreRejected() {
		String[] hostileEntries = {
				"null",
				"\"not an object\"",
				"[]",
				"{\"height\":200004}",
				"{\"tx_hash\":\"" + TX_HASH + "\"}",
				"{\"tx_hash\":\"tooshort\",\"height\":200004}",
				"{\"tx_hash\":\"" + TX_HASH.substring(0, 63) + "z\",\"height\":200004}",
				"{\"tx_hash\":123,\"height\":200004}",
				"{\"tx_hash\":\"" + TX_HASH + "\",\"height\":-2}",
				"{\"tx_hash\":\"" + TX_HASH + "\",\"height\":2147483648}",
				"{\"tx_hash\":\"" + TX_HASH + "\",\"height\":1.5}",
				"{\"tx_hash\":\"" + TX_HASH + "\",\"height\":\"200004\"}",
				"{\"tx_hash\":\"" + TX_HASH + "\",\"height\":1e300}",
		};

		for (String entry : hostileEntries)
			assertEquals(entry, Optional.empty(), ElectrumMethods.normalizeHistory(history(entry)));
	}

	@Test
	public void testOversizedHistoryIsRejected() {
		JSONArray oversized = new JSONArray();
		for (int index = 0; index <= ElectrumMethods.MAX_SCRIPT_RESULT_ENTRIES; index++)
			oversized.add(json("{\"tx_hash\":\"" + TX_HASH + "\",\"height\":1}"));

		assertEquals(Optional.empty(), ElectrumMethods.normalizeHistory(oversized));
	}

	// --- unspent outputs ---

	@Test
	public void testWellFormedUnspentOutputsAreAccepted() {
		assertEquals(1, ElectrumMethods.normalizeUnspentOutputs(
				utxos("{\"tx_hash\":\"" + TX_HASH + "\",\"tx_pos\":0,\"height\":437146,\"value\":45318048}"))
				.orElseThrow().size());
	}

	@Test
	public void testMalformedUnspentOutputsAreRejected() {
		String[] hostileEntries = {
				"null",
				"\"unknown method\"",
				"{\"tx_pos\":0,\"height\":437146,\"value\":1}",
				"{\"tx_hash\":\"" + TX_HASH + "\",\"height\":437146,\"value\":1}",
				"{\"tx_hash\":\"" + TX_HASH + "\",\"tx_pos\":0,\"height\":437146}",
				"{\"tx_hash\":\"" + TX_HASH + "\",\"tx_pos\":-1,\"height\":437146,\"value\":1}",
				"{\"tx_hash\":\"" + TX_HASH + "\",\"tx_pos\":0,\"height\":437146,\"value\":-1}",
				"{\"tx_hash\":\"" + TX_HASH + "\",\"tx_pos\":0,\"height\":-2,\"value\":1}",
				"{\"tx_hash\":\"" + TX_HASH + "\",\"tx_pos\":0,\"height\":437146,\"value\":1.5}",
				"{\"tx_hash\":\"" + TX_HASH + "\",\"tx_pos\":4294967296,\"height\":1,\"value\":1}",
				"{\"tx_hash\":\"" + TX_HASH + "\",\"tx_pos\":0,\"height\":437146,\"value\":1e300}",
		};

		for (String entry : hostileEntries)
			assertEquals(entry, Optional.empty(), ElectrumMethods.normalizeUnspentOutputs(utxos(entry)));
	}

	@Test
	public void testNullEntryDoesNotPassTheCapabilityProbe() throws Exception {
		// The review's exact fixture: {"utxos":[null]} used to pass the probe and blow up on the first cast.
		assertEquals(Optional.empty(), ElectrumMethods.normalizeUnspentOutputs(json("{\"utxos\":[null]}")));

		try {
			ElectrumX.validateWalletRpcResponses(json("{\"history\":[]}"), json("{\"utxos\":[null]}"));
			throw new AssertionError("A null unspent-output entry must reject the server");
		} catch (ForeignBlockchainException.NetworkException e) {
			assertTrue(e.getMessage().contains("listunspent"));
		}
	}

	// --- balance ---

	@Test
	public void testBalanceRequiresIntegerAmounts() {
		assertTrue(ElectrumMethods.normalizeBalance(json("{\"confirmed\":103873966,\"unconfirmed\":23684400}")).isPresent());
		// The mempool delta is legitimately negative when unconfirmed coins are being spent.
		assertTrue(ElectrumMethods.normalizeBalance(json("{\"confirmed\":10,\"unconfirmed\":-5}")).isPresent());

		assertEquals(Optional.empty(), ElectrumMethods.normalizeBalance(json("{}")));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBalance(json("{\"confirmed\":-1,\"unconfirmed\":0}")));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBalance(json("{\"confirmed\":1.5,\"unconfirmed\":0}")));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBalance(json("{\"confirmed\":\"10\",\"unconfirmed\":0}")));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBalance(json("{\"confirmed\":10}")));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBalance(json("{\"confirmed\":1e300,\"unconfirmed\":0}")));
	}

	// --- block headers ---

	@Test
	public void testBlockHeadersAreBounded() {
		assertTrue(ElectrumMethods.normalizeBlockHeaders(json("{\"count\":2,\"max\":2016,\"headers\":[\"aabb\",\"ccdd\"]}")).isPresent());

		// The array is chunks of the raw blob, so its length is unrelated to 'count' and must not be checked.
		assertTrue(ElectrumMethods.normalizeBlockHeaders(json("{\"count\":2,\"headers\":[\"aabb\"]}")).isPresent());
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBlockHeaders(json("{\"count\":-1,\"hex\":\"aabb\"}")));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBlockHeaders(
				json("{\"count\":" + (ElectrumMethods.MAX_BLOCK_HEADERS + 1L) + ",\"hex\":\"aabb\"}")));
		// odd-length and non-hex header payloads
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBlockHeaders(json("{\"count\":1,\"headers\":[\"abc\"]}")));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBlockHeaders(json("{\"count\":1,\"headers\":[\"zzzz\"]}")));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBlockHeaders(json("{\"count\":1,\"hex\":\"zzzz\"}")));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeBlockHeaders(json("{\"count\":1,\"hex\":\"\"}")));
	}

	// --- relay fee ---

	@Test
	public void testRelayFeeMustBeFiniteAndNonNegative() {
		assertTrue(ElectrumMethods.normalizeRelayFee(json("0.001")).isPresent());
		assertEquals(Optional.empty(), ElectrumMethods.normalizeRelayFee(json("-0.001")));
		assertEquals(Optional.empty(), ElectrumMethods.normalizeRelayFee(json("{\"minrelaytxfee\":-1}")));
	}
}
