package org.qortium.crosschain;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.junit.Test;
import org.qortium.crosschain.ChainableServer.ConnectionType;
import org.qortium.crosschain.ElectrumX.Server;
import org.qortium.crypto.ElectrumSSLSocketFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Opt-in acceptance against real ElectrumX servers, one per protocol generation Core has to speak.
 * <p>
 * Run with <code>-Dqortium.runLiveCrosschainTests=true</code>. It is off by default because it depends on
 * third-party servers being reachable and on their current certificates.
 */
public class ElectrumLiveProtocolTests {

	private static final String RUN_PROPERTY = "qortium.runLiveCrosschainTests";
	private static final int TIMEOUT_MS = 15000;

	/**
	 * P2PKH paying an all-zero hash160. Deliberately not the wallet-capability probe script: this one has
	 * real history on most chains, so the reads below exercise populated results rather than empty ones.
	 */
	private static final byte[] PROBE_SCRIPT = hex("76a914" + "00".repeat(20) + "88ac");

	private static byte[] hex(String value) {
		byte[] bytes = new byte[value.length() / 2];
		for (int index = 0; index < bytes.length; index++)
			bytes[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
		return bytes;
	}

	@Test
	public void testProtocolOneSevenServer() throws Exception {
		// ElectrumX 2.0.0, protocol 1.4-1.7.0: serves blockchain.scriptpubkey.* only.
		ElectrumProtocolVersion negotiated = liveCheck("DGB", "electrum1.cipig.net", 20059);
		assertTrue("expected a 1.7 server", negotiated.isAtOrAbove(ElectrumProtocolVersion.of(1, 7)));
	}

	@Test
	public void testProtocolOneSixServer() throws Exception {
		// ElectrumX 1.19.0, protocol 1.4-1.6.0: scripthash.* still present, relayfee gone, headers array.
		ElectrumProtocolVersion negotiated = liveCheck("FIRO", "electrumx01.firo.org", 50002);
		assertTrue("expected a 1.6 server, got " + negotiated,
				negotiated.isWithin(ElectrumProtocolVersion.of(1, 6), ElectrumProtocolVersion.of(1, 6)));
	}

	@Test
	public void testProtocolOneFourServer() throws Exception {
		// ElectrumX 1.15.0, protocol 1.4-1.4.2: the original scripthash.* family.
		ElectrumProtocolVersion negotiated = liveCheck("LTC", "electrum.ltc.xurious.com", 50002);
		assertTrue("expected a 1.4 server", negotiated.isAtOrBelow(ElectrumProtocolVersion.of(1, 4)));
	}

	@Test
	public void testProtocolOneSevenServerNamecoin() throws Exception {
		ElectrumProtocolVersion negotiated = liveCheck("NMC", "nmc2.bitcoins.sk", 57002);
		assertTrue("expected a 1.7 server", negotiated.isAtOrAbove(ElectrumProtocolVersion.of(1, 7)));
	}

	/**
	 * The two families must be two ways of asking the same question. Read the same script from one server
	 * twice — once forced to protocol 1.4, once at 1.7 — and require identical answers.
	 */
	@Test
	public void testBothFamiliesReturnTheSameDataFromOneServer() throws Exception {
		assumeTrue("Live ElectrumX protocol acceptance is opt-in: set -D" + RUN_PROPERTY + "=true",
				Boolean.parseBoolean(System.getProperty(RUN_PROPERTY)));

		JSONArray legacyHistory = historyAt("electrum1.cipig.net", 20059, "1.4");
		JSONArray modernHistory = historyAt("electrum1.cipig.net", 20059, "1.7");

		System.out.printf("DGB same script: 1.4 scripthash history %d entries, 1.7 scriptpubkey history %d entries%n",
				legacyHistory.size(), modernHistory.size());

		assertEquals("the 1.4 and 1.7 families must return the same history", legacyHistory, modernHistory);
	}

	private JSONArray historyAt(String host, int port, String maximumVersion) throws Exception {
		Server server = new Server(host, ConnectionType.SSL, port,
				ElectrumSSLSocketFactory.probeCertificateSha256Fingerprint(host, port, TIMEOUT_MS));
		ElectrumServer connection = ElectrumServer.createInstance(server,
				new InetSocketAddress(host, port), TIMEOUT_MS, new ChainableServerConnectionRecorder(10));

		try {
			connection.setClientName("QortiumLiveTest");
			Object versionResponse = rpc(connection, "server.version", "QortiumLiveTest",
					List.of(ElectrumX.MIN_PROTOCOL_VERSION.toString(), maximumVersion));
			connection.setNegotiatedProtocolVersion(ElectrumX.negotiatedProtocolVersion(versionResponse).orElseThrow());

			ElectrumRequest historyRequest = connection.getMethods().getHistory(PROBE_SCRIPT);
			return ElectrumMethods.normalizeHistory(rpc(connection, historyRequest)).orElseThrow();
		} finally {
			connection.closeServer(ElectrumLiveProtocolTests.class.getSimpleName(), "live acceptance finished");
		}
	}

	/**
	 * Negotiate, then read history, unspent outputs, balance and headers through whichever method family
	 * the server chose, asserting each answer normalises into the shape Core's callers expect.
	 */
	private ElectrumProtocolVersion liveCheck(String coin, String host, int port) throws Exception {
		assumeTrue("Live ElectrumX protocol acceptance is opt-in: set -D" + RUN_PROPERTY + "=true",
				Boolean.parseBoolean(System.getProperty(RUN_PROPERTY)));

		// Pin to whatever certificate the server currently presents, so the acceptance survives a rotation.
		Server server = new Server(host, ConnectionType.SSL, port,
				ElectrumSSLSocketFactory.probeCertificateSha256Fingerprint(host, port, TIMEOUT_MS));
		ElectrumServer connection = ElectrumServer.createInstance(server,
				new InetSocketAddress(host, port), TIMEOUT_MS, new ChainableServerConnectionRecorder(10));

		try {
			connection.setClientName("QortiumLiveTest");
			Object versionResponse = rpc(connection, "server.version", "QortiumLiveTest", ElectrumX.buildVersionParams());

			assertEquals(coin + " negotiation was rejected", Optional.empty(),
					ElectrumX.negotiatedVersionRejectionNote(versionResponse));

			ElectrumProtocolVersion negotiated = ElectrumX.negotiatedProtocolVersion(versionResponse).orElseThrow();
			connection.setNegotiatedProtocolVersion(negotiated);
			ElectrumMethods methods = connection.getMethods();

			System.out.printf("%s %s:%d negotiated protocol %s, using %s%n", coin, host, port, negotiated,
					methods.usesScriptPubKey() ? "blockchain.scriptpubkey.*" : "blockchain.scripthash.*");

			JSONObject features = (JSONObject) rpc(connection, "server.features");
			assertNotNull(coin + " server.features must always carry genesis_hash", features.get("genesis_hash"));

			ElectrumRequest historyRequest = methods.getHistory(PROBE_SCRIPT);
			JSONArray history = ElectrumMethods.normalizeHistory(rpc(connection, historyRequest))
					.orElseThrow(() -> new AssertionError(coin + " could not read " + historyRequest.getMethod()));

			ElectrumRequest unspentRequest = methods.listUnspent(PROBE_SCRIPT);
			JSONArray unspent = ElectrumMethods.normalizeUnspentOutputs(rpc(connection, unspentRequest))
					.orElseThrow(() -> new AssertionError(coin + " could not read " + unspentRequest.getMethod()));

			ElectrumRequest balanceRequest = methods.getBalance(PROBE_SCRIPT);
			JSONObject balance = ElectrumMethods.normalizeBalance(rpc(connection, balanceRequest))
					.orElseThrow(() -> new AssertionError(coin + " could not read " + balanceRequest.getMethod()));
			assertTrue(coin + " balance must report a confirmed amount", balance.get("confirmed") instanceof Number);

			ElectrumMethods.BlockHeadersResult headers = ElectrumMethods.normalizeBlockHeaders(
					rpc(connection, "blockchain.block.headers", 1000L, 2L))
					.orElseThrow(() -> new AssertionError(coin + " could not read blockchain.block.headers"));
			assertEquals(coin + " header split must follow the protocol version", methods.usesBlockHeadersArray(), headers.isSplit());

			// The relay fee is best-effort: Core does not depend on it, and some 1.6/1.7 servers answer
			// mempool.get_info with an internal server error because their daemon does not implement it.
			ElectrumRequest relayFeeRequest = methods.relayFee();
			String relayFee;
			try {
				relayFee = String.valueOf(ElectrumMethods.normalizeRelayFee(rpc(connection, relayFeeRequest)).orElse(null));
			} catch (IOException e) {
				relayFee = "unavailable (" + e.getMessage() + ")";
			}

			System.out.printf("  %s -> %d entries, %s -> %d utxos, %s -> confirmed %s, headers split=%b count=%d, %s -> %s%n",
					historyRequest.getMethod(), history.size(),
					unspentRequest.getMethod(), unspent.size(),
					balanceRequest.getMethod(), balance.get("confirmed"),
					headers.isSplit(), headers.getCount(),
					relayFeeRequest.getMethod(), relayFee);

			return negotiated;
		} finally {
			connection.closeServer(ElectrumLiveProtocolTests.class.getSimpleName(), "live acceptance finished");
		}
	}

	private static Object rpc(ElectrumServer connection, ElectrumRequest request) throws IOException {
		return rpc(connection, request.getMethod(), request.getParams());
	}

	@SuppressWarnings("unchecked")
	private static Object rpc(ElectrumServer connection, String method, Object... params) throws IOException {
		JSONObject requestJson = new JSONObject();
		String id = UUID.randomUUID().toString();
		requestJson.put("id", id);
		requestJson.put("method", method);
		requestJson.put("jsonrpc", "2.0");
		JSONArray requestParams = new JSONArray();
		requestParams.addAll(List.of(params));
		requestJson.put("params", requestParams);

		String response = connection.write((requestJson.toJSONString() + "\n").getBytes(), id);
		Object parsed = JSONValue.parse(response);
		if (!(parsed instanceof JSONObject))
			throw new IOException("invalid JSON response to " + method);

		Object error = ((JSONObject) parsed).get("error");
		if (error != null)
			throw new IOException(method + " failed: " + error);

		return ((JSONObject) parsed).get("result");
	}
}
