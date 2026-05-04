package org.qortal.crosschain;

import org.json.simple.JSONArray;
import org.junit.Test;
import org.qortal.crosschain.ChainableServer.ConnectionType;
import org.qortal.crosschain.ElectrumServerDiscovery.CandidateServer;
import org.qortal.crosschain.ElectrumX.Server;

import java.io.StringReader;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("unchecked")
public class ElectrumServerListTests {

	@Test
	public void testParse1209kHtmlKeepsOnlyOkTcpAndSslServers() {
		String html = "<table>"
				+ row("Example.COM", "50002", "ssl", "OK")
				+ row("tcp.example.com", "50001", "tcp", "OK")
				+ row("closed.example.com", "50002", "ssl", "CLOSED")
				+ row("hidden.onion", "50002", "ssl", "OK")
				+ row("bad.example.com", "bad", "ssl", "OK")
				+ row("unsupported.example.com", "50002", "ws", "OK")
				+ "</table>";

		List<CandidateServer> candidates = ElectrumServerDiscovery.parse1209kHtml(html);

		assertEquals(2, candidates.size());
		assertEquals(new Server("example.com", ConnectionType.SSL, 50002), candidates.get(0).getServer());
		assertEquals(new Server("tcp.example.com", ConnectionType.TCP, 50001), candidates.get(1).getServer());
		assertEquals(ElectrumServerDiscovery.SOURCE_1209K, candidates.get(0).getSourceSummary());
	}

	@Test
	public void testParsePeerServersHandlesDefaultAndExplicitPorts() {
		Map<ConnectionType, Integer> defaultPorts = new EnumMap<>(ConnectionType.class);
		defaultPorts.put(ConnectionType.TCP, 50001);
		defaultPorts.put(ConnectionType.SSL, 50002);

		JSONArray features = new JSONArray();
		features.add("v1.4");
		features.add("s51002");
		features.add("t");

		JSONArray peer = new JSONArray();
		peer.add("203.0.113.1");
		peer.add("Peer.EXAMPLE.com");
		peer.add(features);

		JSONArray peers = new JSONArray();
		peers.add(peer);

		Set<Server> servers = ElectrumServerDiscovery.parsePeerServers(peers, defaultPorts);

		assertEquals(2, servers.size());
		assertTrue(servers.contains(new Server("peer.example.com", ConnectionType.SSL, 51002)));
		assertTrue(servers.contains(new Server("peer.example.com", ConnectionType.TCP, 50001)));
	}

	@Test
	public void testParseGeneratedResourceAndFallbackBehavior() throws Exception {
		String json = "{"
				+ "\"servers\": {"
				+ "\"BTC\": {"
				+ "\"MAIN\": ["
				+ "{\"host\":\"generated.example.com\",\"port\":50002,\"protocol\":\"SSL\"},"
				+ "{\"host\":\"generated.example.com\",\"port\":50002,\"protocol\":\"SSL\"},"
				+ "{\"host\":\"hidden.onion\",\"port\":50002,\"protocol\":\"SSL\"},"
				+ "{\"host\":\"bad.example.com\",\"port\":0,\"protocol\":\"SSL\"}"
				+ "]"
				+ "}"
				+ "}"
				+ "}";

		Map<String, Map<String, List<Server>>> parsed = ElectrumServerList.parseServerResource(new StringReader(json));
		List<Server> generated = parsed.get("BTC").get("MAIN");

		assertEquals(1, generated.size());
		assertEquals(new Server("generated.example.com", ConnectionType.SSL, 50002), generated.get(0));

		Collection<Server> servers = ElectrumServerList.getServers("NOPE", "MAIN", List.of(
				new Server("fallback.example.com", ConnectionType.SSL, 50002)
		));

		assertEquals(1, servers.size());
		assertTrue(servers.contains(new Server("fallback.example.com", ConnectionType.SSL, 50002)));
	}

	@Test
	public void testGeneratedResourceDoesNotAppendFallbackServers() {
		Server fallbackServer = new Server("fallback.example.com", ConnectionType.SSL, 50002);

		Collection<Server> servers = ElectrumServerList.getServers("BTC", "MAIN", List.of(fallbackServer));

		assertTrue(servers.size() > 1);
		assertFalse(servers.contains(fallbackServer));
	}

	@Test
	public void testServerSelectionPrefersSslWhenAvailable() {
		Server sslServer = new Server("ssl.example.com", ConnectionType.SSL, 50002);
		Server tcpServer = new Server("tcp.example.com", ConnectionType.TCP, 50001);

		List<Server> servers = ElectrumServerList.preferSslServers(List.of(tcpServer, sslServer));

		assertEquals(1, servers.size());
		assertEquals(sslServer, servers.get(0));
	}

	@Test
	public void testServerSelectionKeepsTcpWhenNoSslAvailable() {
		Server tcpServer = new Server("tcp.example.com", ConnectionType.TCP, 50001);

		List<Server> servers = ElectrumServerList.preferSslServers(List.of(tcpServer));

		assertEquals(1, servers.size());
		assertEquals(tcpServer, servers.get(0));
	}

	@Test
	public void testRefreshSelectionPrefersSslWhenAvailable() {
		CandidateServer sslCandidate = new CandidateServer(new Server("ssl.example.com", ConnectionType.SSL, 50002), "test");
		CandidateServer tcpCandidate = new CandidateServer(new Server("tcp.example.com", ConnectionType.TCP, 50001), "test");

		List<CandidateServer> candidates = RefreshElectrumServers.preferSslCandidates(List.of(tcpCandidate, sslCandidate));

		assertEquals(1, candidates.size());
		assertEquals(sslCandidate, candidates.get(0));
	}

	private static String row(String host, String port, String protocol, String status) {
		return "<tr>"
				+ td(host)
				+ td(port)
				+ td(protocol)
				+ td("1.4")
				+ td("100")
				+ td("100")
				+ td("0")
				+ td("0")
				+ td("0")
				+ td("0")
				+ td(status)
				+ "</tr>";
	}

	private static String td(String value) {
		return "<td>" + value + "</td>";
	}
}
