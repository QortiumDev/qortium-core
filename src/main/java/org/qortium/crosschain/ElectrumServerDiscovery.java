package org.qortium.crosschain;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.json.simple.JSONArray;
import org.qortium.crosschain.ChainableServer.ConnectionType;
import org.qortium.crosschain.ElectrumX.Server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ElectrumServerDiscovery {

	public static final String SOURCE_1209K = "1209k";
	public static final String SOURCE_PEER = "peer";

	private ElectrumServerDiscovery() {
	}

	public static final class CandidateServer {
		private final Server server;
		private final Set<String> sources = new LinkedHashSet<>();
		private Long responseTimeMillis;

		public CandidateServer(Server server, String source) {
			this.server = Objects.requireNonNull(server);
			if (source != null && !source.isBlank())
				this.sources.add(source);
		}

		public CandidateServer(Server server, Collection<String> sources) {
			this.server = Objects.requireNonNull(server);
			if (sources != null)
				this.sources.addAll(sources);
		}

		public Server getServer() {
			return this.server;
		}

		public Set<String> getSources() {
			return Collections.unmodifiableSet(this.sources);
		}

		public String getSourceSummary() {
			return String.join(",", this.sources);
		}

		public Long getResponseTimeMillis() {
			return this.responseTimeMillis;
		}

		public void setResponseTimeMillis(Long responseTimeMillis) {
			this.responseTimeMillis = responseTimeMillis;
		}

		public void addSources(Collection<String> sources) {
			this.sources.addAll(sources);
		}
	}

	public static List<CandidateServer> parse1209kHtml(String html) {
		if (html == null || html.isBlank())
			return Collections.emptyList();

		Document document = Jsoup.parse(html);
		List<CandidateServer> candidates = new ArrayList<>();

		for (Element row : document.select("tr")) {
			Elements cells = row.select("td");
			if (cells.size() < 11)
				continue;

			String host = cleanCell(cells.get(0).text());
			Integer port = parsePort(cells.get(1).text());
			ConnectionType connectionType = parseConnectionType(cells.get(2).text());
			String status = cleanCell(cells.get(10).text());

			if (host == null || port == null || connectionType == null || status == null)
				continue;

			if (!"ok".equals(status))
				continue;

			if (host.endsWith(".onion"))
				continue;

			candidates.add(new CandidateServer(new Server(host, connectionType, port), SOURCE_1209K));
		}

		return dedupeCandidates(candidates);
	}

	public static Set<Server> parsePeerServers(Object peers, Map<ConnectionType, Integer> defaultPorts) {
		if (!(peers instanceof JSONArray))
			return Collections.emptySet();

		Set<Server> newServers = new LinkedHashSet<>();

		for (Object rawPeer : (JSONArray) peers) {
			if (!(rawPeer instanceof JSONArray))
				continue;

			JSONArray peer = (JSONArray) rawPeer;
			if (peer.size() < 3)
				continue;

			Object hostnameObject = peer.get(1);
			Object featuresObject = peer.get(2);
			if (!(hostnameObject instanceof String) || !(featuresObject instanceof JSONArray))
				continue;

			String hostname = cleanCell((String) hostnameObject);
			if (hostname == null || hostname.endsWith(".onion"))
				continue;

			for (Object rawFeature : (JSONArray) featuresObject) {
				if (!(rawFeature instanceof String))
					continue;

				String feature = (String) rawFeature;
				if (feature.isEmpty())
					continue;

				ConnectionType connectionType = null;
				Integer port = null;

				switch (feature.charAt(0)) {
					case 's':
						connectionType = ConnectionType.SSL;
						port = defaultPorts.get(connectionType);
						break;

					case 't':
						connectionType = ConnectionType.TCP;
						port = defaultPorts.get(connectionType);
						break;

					default:
						break;
				}

				if (connectionType == null || port == null)
					continue;

				if (feature.length() > 1) {
					port = parsePort(feature.substring(1));
					if (port == null)
						continue;
				}

				newServers.add(new Server(hostname, connectionType, port));
			}
		}

		return newServers;
	}

	public static List<CandidateServer> dedupeCandidates(Collection<CandidateServer> candidates) {
		Map<String, CandidateServer> deduped = new LinkedHashMap<>();

		for (CandidateServer candidate : candidates) {
			String key = serverKey(candidate.getServer());
			CandidateServer existing = deduped.get(key);
			if (existing == null) {
				deduped.put(key, candidate);
				continue;
			}

			existing.addSources(candidate.getSources());
			if (existing.getResponseTimeMillis() == null || (candidate.getResponseTimeMillis() != null && candidate.getResponseTimeMillis() < existing.getResponseTimeMillis()))
				existing.setResponseTimeMillis(candidate.getResponseTimeMillis());
		}

		return new ArrayList<>(deduped.values());
	}

	public static String serverKey(Server server) {
		return String.format("%s:%d:%s",
				server.getHostName().toLowerCase(Locale.ROOT),
				server.getPort(),
				server.getConnectionType().name());
	}

	static ConnectionType parseConnectionType(String value) {
		if (value == null)
			return null;

		switch (value.trim().toUpperCase(Locale.ROOT)) {
			case "SSL":
			case "S":
				return ConnectionType.SSL;

			case "TCP":
			case "T":
				return ConnectionType.TCP;

			default:
				return null;
		}
	}

	static Integer parsePort(String value) {
		if (value == null)
			return null;

		try {
			int port = Integer.parseInt(value.trim());
			if (port <= 0 || port > 65535)
				return null;

			return port;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String cleanCell(String value) {
		if (value == null)
			return null;

		String cleaned = value.trim();
		return cleaned.isEmpty() ? null : cleaned.toLowerCase(Locale.ROOT);
	}
}
