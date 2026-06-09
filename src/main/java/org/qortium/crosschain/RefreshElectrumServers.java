package org.qortium.crosschain;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.qortium.api.resource.CrossChainUtils;
import org.qortium.crosschain.ChainableServer.ConnectionType;
import org.qortium.crosschain.ElectrumServerDiscovery.CandidateServer;
import org.qortium.crosschain.ElectrumX.Server;
import org.qortium.crypto.ElectrumSSLSocketFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public final class RefreshElectrumServers {

	private static final double MIN_PROTOCOL_VERSION = 1.2;
	private static final double MAX_PROTOCOL_VERSION = 2.0;
	private static final String DEFAULT_OUTPUT_PATH = "src/main/resources/" + ElectrumServerList.RESOURCE_PATH;

	private static final Map<ConnectionType, Integer> DEFAULT_ELECTRUMX_PORTS = new EnumMap<>(ConnectionType.class);
	static {
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.TCP, 50001);
		DEFAULT_ELECTRUMX_PORTS.put(ConnectionType.SSL, 50002);
	}

	private RefreshElectrumServers() {
	}

	public static void main(String[] args) throws Exception {
		Options options = Options.parse(args);
		if (options.help) {
			printUsage();
			return;
		}

		List<CoinConfig> coinConfigs = coinConfigs();
		for (String coinCode : options.coinCodes) {
			if (coinConfigs.stream().noneMatch(coinConfig -> coinConfig.coinCode.equals(coinCode)))
				throw new IllegalArgumentException("Unsupported coin: " + coinCode);
		}
		for (String networkName : options.networkNames) {
			if (coinConfigs.stream().noneMatch(coinConfig -> options.coinCodes.contains(coinConfig.coinCode) && coinConfig.networkName.equals(networkName)))
				throw new IllegalArgumentException("Unsupported network for selected coins: " + networkName);
		}

		Map<String, Map<String, List<CandidateServer>>> generatedServers = readGeneratedServersIfPresent(options.outputPath);

		for (CoinConfig coinConfig : coinConfigs) {
			if (!options.coinCodes.contains(coinConfig.coinCode))
				continue;
			if (!options.networkNames.isEmpty() && !options.networkNames.contains(coinConfig.networkName))
				continue;

			List<CandidateServer> existingCandidates = copyCandidates(generatedServers
					.getOrDefault(coinConfig.coinCode, Collections.emptyMap())
					.getOrDefault(coinConfig.networkName, Collections.emptyList()));
			List<CandidateServer> candidates = new ArrayList<>(existingCandidates);
			System.out.printf("%s: starting with %d existing generated servers%n", coinConfig.label(), candidates.size());

			if (!options.skip1209k && coinConfig.chain1209k != null) {
				List<CandidateServer> scrapedServers = fetch1209kServers(coinConfig, options);
				candidates.addAll(scrapedServers);
				System.out.printf("%s: added %d OK servers from 1209k%n", coinConfig.label(), scrapedServers.size());
			}

			candidates = ElectrumServerDiscovery.dedupeCandidates(candidates);

			if (!options.skipPeerDiscovery) {
				List<CandidateServer> peerServers = discoverPeerServers(coinConfig, candidates, options);
				candidates.addAll(peerServers);
				System.out.printf("%s: added %d servers from Electrum peer discovery%n", coinConfig.label(), peerServers.size());
			}

			candidates = ElectrumServerDiscovery.dedupeCandidates(candidates);

			if (options.verify) {
				List<CandidateServer> verifiedServers = verifyCandidates(coinConfig, candidates, options);
				System.out.printf("%s: kept %d verified servers from %d candidates%n", coinConfig.label(), verifiedServers.size(), candidates.size());

				if (!verifiedServers.isEmpty())
					candidates = verifiedServers;
				else {
					System.out.printf("%s: no verified servers; keeping existing generated seeds%n", coinConfig.label());
					candidates = existingCandidates;
				}
			}

			List<CandidateServer> dedupedCandidates = ElectrumServerDiscovery.dedupeCandidates(candidates);
			candidates = preferSslCandidates(dedupedCandidates);
			if (candidates.size() < dedupedCandidates.size())
				System.out.printf("%s: selected %d SSL servers over %d TCP servers%n", coinConfig.label(), candidates.size(), dedupedCandidates.size() - candidates.size());
			else if (!candidates.isEmpty() && candidates.get(0).getServer().getConnectionType() == ConnectionType.TCP)
				System.out.printf("%s: no SSL servers available; keeping %d TCP servers%n", coinConfig.label(), candidates.size());

			candidates = sortCandidates(candidates);

			generatedServers.computeIfAbsent(coinConfig.coinCode, ignored -> new LinkedHashMap<>())
					.put(coinConfig.networkName, candidates);
		}

		writeGeneratedServers(options.outputPath, generatedServers);
		System.out.printf("Wrote generated Electrum server list to %s%n", options.outputPath);
	}

	private static List<CandidateServer> fetch1209kServers(CoinConfig coinConfig, Options options) {
		String url = "https://1209k.com/bitcoin-eye/ele.php?chain=" + coinConfig.chain1209k;

		try {
			URLConnection connection = new URL(url).openConnection();
			connection.setConnectTimeout(options.timeoutMs);
			connection.setReadTimeout(options.timeoutMs);
			connection.setRequestProperty("User-Agent", "Qortium Electrum server refresh");

			try (InputStream inputStream = connection.getInputStream();
				 Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
				return ElectrumServerDiscovery.parse1209kHtml(readAll(reader));
			}
		} catch (IOException e) {
			System.out.printf("%s: unable to fetch 1209k list from %s: %s%n", coinConfig.label(), url, e.getMessage());
			return Collections.emptyList();
		}
	}

	private static List<CandidateServer> discoverPeerServers(CoinConfig coinConfig, List<CandidateServer> candidates, Options options) {
		List<Server> seedServers = candidates.stream()
				.map(CandidateServer::getServer)
				.sorted(Comparator.comparing((Server server) -> server.getConnectionType() == ConnectionType.SSL ? 0 : 1)
						.thenComparing(Server::getHostName)
						.thenComparingInt(Server::getPort))
				.limit(options.maxPeerSeeds)
				.collect(Collectors.toList());

		List<CandidateServer> discovered = new ArrayList<>();

		for (Server seedServer : seedServers) {
			try {
				discovered.addAll(queryPeerServers(coinConfig, seedServer, options));
			} catch (Exception e) {
				System.out.printf("%s: peer discovery failed via %s: %s%n", coinConfig.label(), seedServer, e.getMessage());
			}
		}

		return ElectrumServerDiscovery.dedupeCandidates(discovered);
	}

	private static List<CandidateServer> queryPeerServers(CoinConfig coinConfig, Server seedServer, Options options) throws Exception {
		ElectrumServer electrumServer = null;

		try {
			Server pinnedSeed = pinSslServerIfNeeded(seedServer, options.timeoutMs);
			electrumServer = openValidatedConnection(coinConfig, pinnedSeed, options.timeoutMs, false);
			Object peers = rpc(electrumServer, "server.peers.subscribe");
			Set<Server> peerServers = ElectrumServerDiscovery.parsePeerServers(peers, DEFAULT_ELECTRUMX_PORTS);

			List<CandidateServer> candidates = new ArrayList<>();
			for (Server server : peerServers)
				candidates.add(new CandidateServer(server, ElectrumServerDiscovery.SOURCE_PEER));

			return candidates;
		} finally {
			if (electrumServer != null)
				electrumServer.closeServer(RefreshElectrumServers.class.getSimpleName(), "peer discovery");
		}
	}

	private static List<CandidateServer> verifyCandidates(CoinConfig coinConfig, List<CandidateServer> candidates, Options options) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(options.threads);
		try {
			List<Future<CandidateServer>> futures = new ArrayList<>();

			for (CandidateServer candidate : candidates) {
				Callable<CandidateServer> task = () -> {
					// Pin SSL servers to their current leaf certificate first, so self-signed servers can be
					// verified (and shipped) instead of being rejected by strict validation.
					Server server = pinSslServerIfNeeded(candidate.getServer(), options.timeoutMs);
					long responseTime = validateServer(coinConfig, server, options.timeoutMs);
					CandidateServer verified = new CandidateServer(server, candidate.getSources());
					verified.setResponseTimeMillis(responseTime);
					return verified;
				};
				futures.add(executor.submit(task));
			}

			List<CandidateServer> verified = new ArrayList<>();
			for (Future<CandidateServer> future : futures) {
				try {
					verified.add(future.get());
				} catch (Exception e) {
					// Failed candidates are pruned from the generated list.
				}
			}

			return ElectrumServerDiscovery.dedupeCandidates(verified);
		} finally {
			executor.shutdownNow();
		}
	}

	/**
	 * For an SSL server with no pinned fingerprint, capture its current leaf certificate fingerprint and return a
	 * pinned copy of the server. This lets the generated list ship explicit pins, and lets self-signed servers pass
	 * the subsequent strict-by-default verification handshake instead of being dropped.
	 */
	private static Server pinSslServerIfNeeded(Server server, int timeoutMs) {
		if (server.getConnectionType() != ConnectionType.SSL || server.getCertificateSha256Fingerprint() != null)
			return server;

		try {
			String fingerprint = ElectrumSSLSocketFactory.probeCertificateSha256Fingerprint(server.getHostName(), server.getPort(), timeoutMs);
			if (fingerprint != null)
				return new Server(server.getHostName(), server.getConnectionType(), server.getPort(), fingerprint);
		} catch (IOException e) {
			// Leave the server unpinned; verification still applies the active trust policy.
		}

		return server;
	}

	private static long validateServer(CoinConfig coinConfig, Server server, int timeoutMs) throws Exception {
		ElectrumServer electrumServer = null;
		long startTime = System.currentTimeMillis();

		try {
			electrumServer = openValidatedConnection(coinConfig, server, timeoutMs, true);
			return Math.max(1L, System.currentTimeMillis() - startTime);
		} finally {
			if (electrumServer != null)
				electrumServer.closeServer(RefreshElectrumServers.class.getSimpleName(), "verification");
		}
	}

	private static ElectrumServer openValidatedConnection(CoinConfig coinConfig, Server server, int timeoutMs, boolean checkHeight) throws Exception {
		SocketAddress endpoint = new InetSocketAddress(server.getHostName(), server.getPort());
		ElectrumServer electrumServer = ElectrumServer.createInstance(server, endpoint, timeoutMs, new ChainableServerConnectionRecorder(20));
		boolean success = false;

		try {
			electrumServer.setClientName("QortiumRefresh");

			rpc(electrumServer, "server.version");
			Object features = rpc(electrumServer, "server.features");
			if (!(features instanceof JSONObject))
				throw new IOException("missing server.features result");

			JSONObject featuresJson = (JSONObject) features;
			double protocolMin = CrossChainUtils.getVersionDecimal(featuresJson, "protocol_min");
			if (protocolMin < MIN_PROTOCOL_VERSION)
				throw new IOException("old protocol_min " + protocolMin);

			Object genesisHash = featuresJson.get("genesis_hash");
			if (coinConfig.genesisHash != null && !coinConfig.genesisHash.equals(genesisHash))
				throw new IOException("unexpected genesis hash " + genesisHash);

			if (checkHeight) {
				Object header = rpc(electrumServer, "blockchain.headers.subscribe");
				if (!(header instanceof JSONObject))
					throw new IOException("missing blockchain.headers.subscribe result");

				Object height = ((JSONObject) header).get("height");
				if (!(height instanceof Number) || ((Number) height).longValue() <= 0)
					throw new IOException("invalid blockchain height " + height);
			}

			success = true;
			return electrumServer;
		} finally {
			if (!success)
				electrumServer.closeServer(RefreshElectrumServers.class.getSimpleName(), "validation failure");
		}
	}

	@SuppressWarnings("unchecked")
	private static Object rpc(ElectrumServer server, String method, Object... params) throws Exception {
		JSONObject requestJson = new JSONObject();
		String id = UUID.randomUUID().toString();
		requestJson.put("id", id);
		requestJson.put("method", method);
		requestJson.put("jsonrpc", "2.0");

		JSONArray requestParams = new JSONArray();
		requestParams.addAll(Arrays.asList(params));

		if ("server.version".equals(method)) {
			requestParams.add("QortiumRefresh");
			JSONArray versions = new JSONArray();
			versions.add(String.format(Locale.ROOT, "%.1f", MIN_PROTOCOL_VERSION));
			versions.add(String.format(Locale.ROOT, "%.1f", MAX_PROTOCOL_VERSION));
			requestParams.add(versions);
		}

		requestJson.put("params", requestParams);

		String response = server.write((requestJson.toJSONString() + "\n").getBytes(StandardCharsets.UTF_8), id);
		Object responseObject = JSONValue.parse(response);
		if (!(responseObject instanceof JSONObject))
			throw new IOException("invalid JSON response");

		JSONObject responseJson = (JSONObject) responseObject;
		Object error = responseJson.get("error");
		if (error != null)
			throw new IOException("Electrum RPC error: " + error);

		return responseJson.get("result");
	}

	static List<CandidateServer> sortCandidates(List<CandidateServer> candidates) {
		candidates.sort(Comparator
				.comparing((CandidateServer candidate) -> isNumericHost(candidate.getServer().getHostName()) ? 0 : 1)
				.thenComparing(candidate -> candidate.getServer().getHostName().toLowerCase(Locale.ROOT))
				.thenComparingInt(candidate -> candidate.getServer().getPort())
				.thenComparing(candidate -> candidate.getServer().getConnectionType().name()));
		return candidates;
	}

	private static boolean isNumericHost(String hostName) {
		if (hostName == null || hostName.isBlank())
			return false;

		String firstLabel = hostName.split("\\.", 2)[0];
		return !firstLabel.isBlank() && firstLabel.chars().allMatch(Character::isDigit);
	}

	static List<CandidateServer> preferSslCandidates(List<CandidateServer> candidates) {
		List<CandidateServer> secureCandidates = candidates.stream()
				.filter(candidate -> candidate.getServer().getConnectionType() == ConnectionType.SSL)
				.collect(Collectors.toList());

		return secureCandidates.isEmpty() ? candidates : secureCandidates;
	}

	private static void writeGeneratedServers(Path outputPath, Map<String, Map<String, List<CandidateServer>>> generatedServers) throws IOException {
		String json = toJson(generatedServers);
		if (outputPath.getParent() != null)
			Files.createDirectories(outputPath.getParent());

		Files.write(outputPath, json.getBytes(StandardCharsets.UTF_8));
	}

	static Map<String, Map<String, List<CandidateServer>>> readGeneratedServers(Path inputPath) throws IOException, ParseException {
		try (Reader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
			Object parsed = new JSONParser().parse(reader);
			if (!(parsed instanceof JSONObject))
				return new LinkedHashMap<>();

			JSONObject root = (JSONObject) parsed;
			Object serversObject = root.get("servers");
			if (!(serversObject instanceof JSONObject))
				return new LinkedHashMap<>();

			Map<String, Map<String, List<CandidateServer>>> parsedServers = parseGeneratedServerResource((JSONObject) serversObject);
			return orderGeneratedServers(parsedServers);
		}
	}

	static Map<String, Map<String, List<CandidateServer>>> readGeneratedServersIfPresent(Path inputPath) throws IOException, ParseException {
		if (!Files.exists(inputPath))
			return new LinkedHashMap<>();

		return readGeneratedServers(inputPath);
	}

	private static Map<String, Map<String, List<CandidateServer>>> parseGeneratedServerResource(JSONObject serversJson) {
		Map<String, Map<String, List<CandidateServer>>> generatedServers = new LinkedHashMap<>();

		for (Object coinKeyObject : serversJson.keySet()) {
			Object networksObject = serversJson.get(coinKeyObject);
			if (!(coinKeyObject instanceof String) || !(networksObject instanceof JSONObject))
				continue;

			Map<String, List<CandidateServer>> networks = new LinkedHashMap<>();
			JSONObject networksJson = (JSONObject) networksObject;

			for (Object networkKeyObject : networksJson.keySet()) {
				Object serverArrayObject = networksJson.get(networkKeyObject);
				if (!(networkKeyObject instanceof String) || !(serverArrayObject instanceof JSONArray))
					continue;

				List<CandidateServer> candidates = parseGeneratedServerArray((JSONArray) serverArrayObject);
				if (!candidates.isEmpty())
					networks.put(normalizeKey((String) networkKeyObject), candidates);
			}

			if (!networks.isEmpty())
				generatedServers.put(normalizeKey((String) coinKeyObject), networks);
		}

		return generatedServers;
	}

	private static List<CandidateServer> parseGeneratedServerArray(JSONArray serverArray) {
		List<CandidateServer> candidates = new ArrayList<>();

		for (Object serverObject : serverArray) {
			if (!(serverObject instanceof JSONObject))
				continue;

			CandidateServer candidate = parseGeneratedServer((JSONObject) serverObject);
			if (candidate != null)
				candidates.add(candidate);
		}

		return ElectrumServerDiscovery.dedupeCandidates(candidates);
	}

	private static CandidateServer parseGeneratedServer(JSONObject serverJson) {
		String hostName = parseString(serverJson.get("host"));
		if (hostName == null)
			hostName = parseString(serverJson.get("hostname"));
		if (hostName == null || hostName.toLowerCase(Locale.ROOT).endsWith(".onion"))
			return null;

		Integer port = parsePort(serverJson.get("port"));
		if (port == null)
			return null;

		String protocol = parseString(serverJson.get("protocol"));
		if (protocol == null)
			protocol = parseString(serverJson.get("connectionType"));

		ConnectionType connectionType = ElectrumServerDiscovery.parseConnectionType(protocol);
		if (connectionType == null)
			return null;

		String certificateSha256Fingerprint = parseString(serverJson.get("certificateSha256Fingerprint"));
		if (certificateSha256Fingerprint == null)
			certificateSha256Fingerprint = parseString(serverJson.get("certSha256Fingerprint"));

		CandidateServer candidate = new CandidateServer(new Server(hostName, connectionType, port, certificateSha256Fingerprint), parseSources(serverJson.get("source")));
		candidate.setResponseTimeMillis(parseResponseTimeMillis(serverJson.get("responseTimeMillis")));

		return candidate;
	}

	private static Map<String, Map<String, List<CandidateServer>>> orderGeneratedServers(Map<String, Map<String, List<CandidateServer>>> parsedServers) {
		Map<String, Map<String, List<CandidateServer>>> orderedServers = new LinkedHashMap<>();

		for (BitcoinyChainSpec spec : BitcoinyChainSpecs.all()) {
			Map<String, List<CandidateServer>> parsedNetworks = parsedServers.remove(spec.getCurrencyCode());
			if (parsedNetworks == null)
				continue;

			Map<String, List<CandidateServer>> orderedNetworks = new LinkedHashMap<>();
			for (BitcoinyNetwork network : spec.getNetworks()) {
				List<CandidateServer> candidates = parsedNetworks.remove(network.name());
				if (candidates != null)
					orderedNetworks.put(network.name(), candidates);
			}
			orderedNetworks.putAll(parsedNetworks);

			orderedServers.put(spec.getCurrencyCode(), orderedNetworks);
		}

		orderedServers.putAll(parsedServers);
		return orderedServers;
	}

	private static List<CandidateServer> copyCandidates(List<CandidateServer> candidates) {
		return candidates.stream()
				.map(candidate -> {
					CandidateServer copy = new CandidateServer(candidate.getServer(), candidate.getSources());
					copy.setResponseTimeMillis(candidate.getResponseTimeMillis());
					return copy;
				})
				.collect(Collectors.toList());
	}

	private static String parseString(Object value) {
		if (!(value instanceof String))
			return null;

		String string = ((String) value).trim();
		return string.isEmpty() ? null : string;
	}

	private static Integer parsePort(Object value) {
		if (value instanceof Number)
			return ElectrumServerDiscovery.parsePort(String.valueOf(((Number) value).intValue()));

		if (value instanceof String)
			return ElectrumServerDiscovery.parsePort((String) value);

		return null;
	}

	private static Set<String> parseSources(Object value) {
		String source = parseString(value);
		if (source == null)
			return Collections.emptySet();

		return Arrays.stream(source.split(","))
				.map(String::trim)
				.filter(part -> !part.isEmpty())
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private static Long parseResponseTimeMillis(Object value) {
		if (!(value instanceof Number))
			return null;

		long responseTimeMillis = ((Number) value).longValue();
		return responseTimeMillis <= 0 ? null : responseTimeMillis;
	}

	private static String normalizeKey(String value) {
		return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
	}

	private static String toJson(Map<String, Map<String, List<CandidateServer>>> generatedServers) {
		StringBuilder builder = new StringBuilder();
		builder.append("{\n");
		builder.append("  \"generatedAt\": \"").append(JSONValue.escape(Instant.now().toString())).append("\",\n");
		builder.append("  \"servers\": {\n");

		int coinIndex = 0;
		for (Map.Entry<String, Map<String, List<CandidateServer>>> coinEntry : generatedServers.entrySet()) {
			if (coinIndex++ > 0)
				builder.append(",\n");

			builder.append("    \"").append(JSONValue.escape(coinEntry.getKey())).append("\": {\n");

			int networkIndex = 0;
			for (Map.Entry<String, List<CandidateServer>> networkEntry : coinEntry.getValue().entrySet()) {
				if (networkIndex++ > 0)
					builder.append(",\n");

				builder.append("      \"").append(JSONValue.escape(networkEntry.getKey())).append("\": [\n");

				int serverIndex = 0;
				for (CandidateServer candidate : networkEntry.getValue()) {
					if (serverIndex++ > 0)
						builder.append(",\n");

					Server server = candidate.getServer();
					builder.append("        {");
					builder.append("\"host\": \"").append(JSONValue.escape(server.getHostName())).append("\", ");
					builder.append("\"port\": ").append(server.getPort()).append(", ");
					builder.append("\"protocol\": \"").append(server.getConnectionType().name()).append("\"");
					if (server.getCertificateSha256Fingerprint() != null)
						builder.append(", \"certificateSha256Fingerprint\": \"").append(JSONValue.escape(server.getCertificateSha256Fingerprint())).append("\"");
					if (!candidate.getSources().isEmpty())
						builder.append(", \"source\": \"").append(JSONValue.escape(candidate.getSourceSummary())).append("\"");
					if (candidate.getResponseTimeMillis() != null)
						builder.append(", \"responseTimeMillis\": ").append(candidate.getResponseTimeMillis());
					builder.append("}");
				}

				builder.append("\n      ]");
			}

			builder.append("\n    }");
		}

		builder.append("\n  }\n");
		builder.append("}\n");
		return builder.toString();
	}

	private static String readAll(Reader reader) throws IOException {
		StringBuilder builder = new StringBuilder();
		char[] buffer = new char[8192];
		int read;
		while ((read = reader.read(buffer)) != -1)
			builder.append(buffer, 0, read);

		return builder.toString();
	}

	private static List<CoinConfig> coinConfigs() {
		List<CoinConfig> configs = new ArrayList<>();
		for (BitcoinyChainSpec spec : BitcoinyChainSpecs.all()) {
			for (BitcoinyChainSpec.ElectrumServerRefreshConfig refreshConfig : spec.getElectrumServerRefreshConfigs()) {
				BitcoinyNetwork network = spec.getNetwork(refreshConfig.getNetworkName());
				addCoin(configs, spec.getCurrencyCode(), refreshConfig.getNetworkName(), refreshConfig.getChain1209k(),
						network.getGenesisHash());
			}
		}
		return configs;
	}

	private static void addCoin(List<CoinConfig> configs, String coinCode, String networkName, String chain1209k, String genesisHash) {
		configs.add(new CoinConfig(coinCode, networkName, chain1209k, genesisHash));
	}

	private static void printUsage() {
		System.out.println("usage: tools/refresh-electrum-servers [options]");
		System.out.println("  --output <path>        Output JSON path (default: " + DEFAULT_OUTPUT_PATH + ")");
		System.out.println("  --coins <csv>          Coins to refresh (default: " + String.join(",", BitcoinyChainSpecs.currencyCodes()) + ")");
		System.out.println("  --networks <csv>       Networks to refresh for selected coins (default: all refreshable networks)");
		System.out.println("  --skip-1209k           Do not scrape 1209k.com");
		System.out.println("  --skip-peers           Do not query Electrum server.peers.subscribe");
		System.out.println("  --skip-verify          Keep discovered servers without live genesis/height checks");
		System.out.println("  Existing output JSON is used as the seed list when present");
		System.out.println("  --timeout-ms <ms>      Network timeout per request (default: 5000)");
		System.out.println("  --max-peer-seeds <n>   Number of seeds per coin to ask for peers (default: 8)");
		System.out.println("  --threads <n>          Verification worker count (default: 12)");
	}

	private static final class CoinConfig {
		private final String coinCode;
		private final String networkName;
		private final String chain1209k;
		private final String genesisHash;

		private CoinConfig(String coinCode, String networkName, String chain1209k, String genesisHash) {
			this.coinCode = coinCode;
			this.networkName = networkName;
			this.chain1209k = chain1209k;
			this.genesisHash = genesisHash;
		}

		private String label() {
			return this.coinCode + "-" + this.networkName;
		}
	}

	private static final class Options {
		private final Path outputPath;
		private final Set<String> coinCodes;
		private final Set<String> networkNames;
		private final boolean skip1209k;
		private final boolean skipPeerDiscovery;
		private final boolean verify;
		private final int timeoutMs;
		private final int maxPeerSeeds;
		private final int threads;
		private final boolean help;

		private Options(Path outputPath, Set<String> coinCodes, Set<String> networkNames, boolean skip1209k, boolean skipPeerDiscovery,
				boolean verify, int timeoutMs, int maxPeerSeeds, int threads, boolean help) {
			this.outputPath = outputPath;
			this.coinCodes = coinCodes;
			this.networkNames = networkNames;
			this.skip1209k = skip1209k;
			this.skipPeerDiscovery = skipPeerDiscovery;
			this.verify = verify;
			this.timeoutMs = timeoutMs;
			this.maxPeerSeeds = maxPeerSeeds;
			this.threads = threads;
			this.help = help;
		}

		private static Options parse(String[] args) {
			Path outputPath = Paths.get(DEFAULT_OUTPUT_PATH);
			Set<String> coinCodes = new LinkedHashSet<>(BitcoinyChainSpecs.currencyCodes());
			Set<String> networkNames = new LinkedHashSet<>();
			boolean skip1209k = false;
			boolean skipPeerDiscovery = false;
			boolean verify = true;
			int timeoutMs = 5000;
			int maxPeerSeeds = 8;
			int threads = 12;
			boolean help = false;

			for (int index = 0; index < args.length; index++) {
				String arg = args[index];
				switch (arg) {
					case "--help":
					case "-h":
						help = true;
						break;

					case "--output":
						outputPath = Paths.get(requireValue(args, ++index, arg));
						break;

					case "--coins":
						coinCodes = parseCoins(requireValue(args, ++index, arg));
						break;

					case "--networks":
						networkNames = parseNetworks(requireValue(args, ++index, arg));
						break;

					case "--skip-1209k":
						skip1209k = true;
						break;

					case "--skip-peers":
						skipPeerDiscovery = true;
						break;

					case "--skip-verify":
						verify = false;
						break;

					case "--timeout-ms":
						timeoutMs = parsePositiveInt(requireValue(args, ++index, arg), arg);
						break;

					case "--max-peer-seeds":
						maxPeerSeeds = parsePositiveInt(requireValue(args, ++index, arg), arg);
						break;

					case "--threads":
						threads = parsePositiveInt(requireValue(args, ++index, arg), arg);
						break;

					default:
						throw new IllegalArgumentException("Unknown option: " + arg);
				}
			}

			return new Options(outputPath, coinCodes, networkNames, skip1209k, skipPeerDiscovery, verify, timeoutMs, maxPeerSeeds, threads, help);
		}

		private static String requireValue(String[] args, int index, String option) {
			if (index >= args.length)
				throw new IllegalArgumentException(option + " requires a value");

			return args[index];
		}

		private static Set<String> parseCoins(String csv) {
			return Arrays.stream(csv.split(","))
					.map(String::trim)
					.filter(coin -> !coin.isEmpty())
					.map(coin -> coin.toUpperCase(Locale.ROOT))
					.collect(Collectors.toCollection(LinkedHashSet::new));
		}

		private static Set<String> parseNetworks(String csv) {
			return Arrays.stream(csv.split(","))
					.map(String::trim)
					.filter(network -> !network.isEmpty())
					.map(network -> network.toUpperCase(Locale.ROOT))
					.collect(Collectors.toCollection(LinkedHashSet::new));
		}

		private static int parsePositiveInt(String value, String option) {
			try {
				int parsed = Integer.parseInt(value);
				if (parsed <= 0)
					throw new NumberFormatException();

				return parsed;
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException(option + " requires a positive integer");
			}
		}
	}
}
