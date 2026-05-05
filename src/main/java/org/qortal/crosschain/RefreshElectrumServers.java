package org.qortal.crosschain;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.qortal.api.resource.CrossChainUtils;
import org.qortal.crosschain.ChainableServer.ConnectionType;
import org.qortal.crosschain.ElectrumServerDiscovery.CandidateServer;
import org.qortal.crosschain.ElectrumX.Server;

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
import java.util.Collection;
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

		if (options.updateBuiltinsOnly) {
			Map<String, Map<String, List<CandidateServer>>> generatedServers = readGeneratedServers(options.outputPath);
			updateBuiltInServers(coinConfigs, generatedServers, options);
			System.out.printf("Updated Java built-in Electrum server fallbacks from %s with %s%n", options.outputPath, builtinLimitSummary(options.builtinLimit));
			return;
		}

		Map<String, Map<String, List<CandidateServer>>> generatedServers = new LinkedHashMap<>();

		for (CoinConfig coinConfig : coinConfigs) {
			if (!options.coinCodes.contains(coinConfig.coinCode))
				continue;

			List<CandidateServer> builtInCandidates = ElectrumServerDiscovery.fromBuiltIn(coinConfig.builtInServers);
			List<CandidateServer> candidates = new ArrayList<>(builtInCandidates);
			System.out.printf("%s: starting with %d built-in servers%n", coinConfig.label(), candidates.size());

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
					System.out.printf("%s: no verified servers; keeping built-in fallback seeds in generated file%n", coinConfig.label());
					candidates = builtInCandidates;
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

		if (options.updateBuiltins) {
			updateBuiltInServers(coinConfigs, generatedServers, options);
			System.out.printf("Updated Java built-in Electrum server fallbacks with %s%n", builtinLimitSummary(options.builtinLimit));
		}
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
			electrumServer = openValidatedConnection(coinConfig, seedServer, options.timeoutMs, false);
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
					CandidateServer verified = new CandidateServer(candidate.getServer(), candidate.getSources());
					long responseTime = validateServer(coinConfig, candidate.getServer(), options.timeoutMs);
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

	static Map<String, Map<String, List<CandidateServer>>> readGeneratedServers(Path inputPath) throws IOException, org.json.simple.parser.ParseException {
		try (Reader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
			Map<String, Map<String, List<Server>>> parsedServers = ElectrumServerList.parseServerResource(reader);
			Map<String, Map<String, List<CandidateServer>>> generatedServers = new LinkedHashMap<>();

			for (Map.Entry<String, Map<String, List<Server>>> coinEntry : parsedServers.entrySet()) {
				Map<String, List<CandidateServer>> networks = new LinkedHashMap<>();

				for (Map.Entry<String, List<Server>> networkEntry : coinEntry.getValue().entrySet()) {
					List<CandidateServer> candidates = networkEntry.getValue().stream()
							.map(server -> new CandidateServer(server, "generated"))
							.collect(Collectors.toList());
					networks.put(networkEntry.getKey(), candidates);
				}

				generatedServers.put(coinEntry.getKey(), networks);
			}

			return generatedServers;
		}
	}

	private static void updateBuiltInServers(List<CoinConfig> coinConfigs, Map<String, Map<String, List<CandidateServer>>> generatedServers, Options options) throws IOException {
		Map<Path, String> sourceByPath = new LinkedHashMap<>();

		for (CoinConfig coinConfig : coinConfigs) {
			if (!options.coinCodes.contains(coinConfig.coinCode) || coinConfig.sourcePath == null || coinConfig.networkName == null)
				continue;

			List<CandidateServer> candidates = generatedServers
					.getOrDefault(coinConfig.coinCode, Collections.emptyMap())
					.get(coinConfig.networkName);
			if (candidates == null || candidates.isEmpty())
				continue;

			String source = sourceByPath.computeIfAbsent(coinConfig.sourcePath, path -> {
				try {
					return Files.readString(path);
				} catch (IOException e) {
					throw new IllegalStateException("Unable to read " + path, e);
				}
			});

			sourceByPath.put(coinConfig.sourcePath, replaceBuiltInServerList(source, coinConfig.networkName, candidates, options.builtinLimit));
		}

		for (Map.Entry<Path, String> entry : sourceByPath.entrySet())
			Files.writeString(entry.getKey(), entry.getValue(), StandardCharsets.UTF_8);
	}

	static String replaceBuiltInServerList(String source, String networkName, List<CandidateServer> candidates, Integer limit) {
		String enumMarker = "\t\t" + networkName + " {";
		int enumStart = source.indexOf(enumMarker);
		if (enumStart < 0)
			throw new IllegalArgumentException("Unable to find enum constant " + networkName);

		int methodStart = source.indexOf(" getServers() {", enumStart);
		if (methodStart < 0)
			throw new IllegalArgumentException("Unable to find getServers() for " + networkName);

		String listStartMarker = "return Arrays.asList(\n";
		int listStart = source.indexOf(listStartMarker, methodStart);
		if (listStart < 0)
			throw new IllegalArgumentException("Unable to find server list start for " + networkName);
		listStart += listStartMarker.length();

		String listEndMarker = "\n\t\t\t\t);";
		int listEnd = source.indexOf(listEndMarker, listStart);
		if (listEnd < 0)
			throw new IllegalArgumentException("Unable to find server list end for " + networkName);

		return source.substring(0, listStart)
				+ toBuiltInServerList(candidates, limit)
				+ source.substring(listEnd);
	}

	private static String toBuiltInServerList(List<CandidateServer> candidates, Integer limit) {
		List<CandidateServer> limitedCandidates = limit == null
				? candidates
				: candidates.stream()
						.limit(limit)
						.collect(Collectors.toList());

		StringBuilder builder = new StringBuilder();
		builder.append("\t\t\t\t\t// Generated by tools/refresh-electrum-servers --update-builtins\n");

		for (int index = 0; index < limitedCandidates.size(); index++) {
			Server server = limitedCandidates.get(index).getServer();
			builder.append("\t\t\t\t\tnew Server(\"")
					.append(JSONValue.escape(server.getHostName()))
					.append("\", Server.ConnectionType.")
					.append(server.getConnectionType().name())
					.append(", ")
					.append(server.getPort())
					.append(")");

			if (index < limitedCandidates.size() - 1) {
				builder.append(",");
				builder.append("\n");
			}
		}

		return builder.toString();
	}

	private static String builtinLimitSummary(Integer builtinLimit) {
		return builtinLimit == null ? "no per-network limit" : "up to " + builtinLimit + " servers per network";
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
		addCoin(configs, "BTC", "MAIN", "btc", Bitcoin.BitcoinNet.MAIN.getGenesisHash(), Bitcoin.BitcoinNet.MAIN.getServers(), "src/main/java/org/qortal/crosschain/Bitcoin.java");
		addCoin(configs, "BTC", "TEST3", "tbtc", Bitcoin.BitcoinNet.TEST3.getGenesisHash(), Bitcoin.BitcoinNet.TEST3.getServers(), "src/main/java/org/qortal/crosschain/Bitcoin.java");
		addCoin(configs, "LTC", "MAIN", "ltc", Litecoin.LitecoinNet.MAIN.getGenesisHash(), Litecoin.LitecoinNet.MAIN.getServers(), "src/main/java/org/qortal/crosschain/Litecoin.java");
		addCoin(configs, "LTC", "TEST3", "tltc", Litecoin.LitecoinNet.TEST3.getGenesisHash(), Litecoin.LitecoinNet.TEST3.getServers(), "src/main/java/org/qortal/crosschain/Litecoin.java");
		addCoin(configs, "DOGE", "MAIN", "doge", Dogecoin.DogecoinNet.MAIN.getGenesisHash(), Dogecoin.DogecoinNet.MAIN.getServers(), "src/main/java/org/qortal/crosschain/Dogecoin.java");
		addCoin(configs, "DGB", "MAIN", "dgb", Digibyte.DigibyteNet.MAIN.getGenesisHash(), Digibyte.DigibyteNet.MAIN.getServers(), "src/main/java/org/qortal/crosschain/Digibyte.java");
		addCoin(configs, "RVN", "MAIN", "rvn", Ravencoin.RavencoinNet.MAIN.getGenesisHash(), Ravencoin.RavencoinNet.MAIN.getServers(), "src/main/java/org/qortal/crosschain/Ravencoin.java");
		return configs;
	}

	private static void addCoin(List<CoinConfig> configs, String coinCode, String networkName, String chain1209k, String genesisHash, Collection<Server> builtInServers, String sourcePath) {
		configs.add(new CoinConfig(coinCode, networkName, chain1209k, genesisHash, new ArrayList<>(builtInServers), Paths.get(sourcePath)));
	}

	private static void printUsage() {
		System.out.println("usage: tools/refresh-electrum-servers [options]");
		System.out.println("  --output <path>        Output JSON path (default: " + DEFAULT_OUTPUT_PATH + ")");
		System.out.println("  --coins <csv>          Coins to refresh (default: BTC,LTC,DOGE,DGB,RVN; BTC/LTC include TEST3)");
		System.out.println("  --skip-1209k           Do not scrape 1209k.com");
		System.out.println("  --skip-peers           Do not query Electrum server.peers.subscribe");
		System.out.println("  --skip-verify          Keep discovered servers without live genesis/height checks");
		System.out.println("  --update-builtins      Also update Java hardcoded fallback server lists");
		System.out.println("  --update-builtins-only Update Java hardcoded fallback lists from the existing JSON resource");
		System.out.println("  --builtin-limit <n>    Max hardcoded fallback servers per network (default: unlimited)");
		System.out.println("  --timeout-ms <ms>      Network timeout per request (default: 5000)");
		System.out.println("  --max-peer-seeds <n>   Number of seeds per coin to ask for peers (default: 8)");
		System.out.println("  --threads <n>          Verification worker count (default: 12)");
	}

	private static final class CoinConfig {
		private final String coinCode;
		private final String networkName;
		private final String chain1209k;
		private final String genesisHash;
		private final List<Server> builtInServers;
		private final Path sourcePath;

		private CoinConfig(String coinCode, String networkName, String chain1209k, String genesisHash, List<Server> builtInServers, Path sourcePath) {
			this.coinCode = coinCode;
			this.networkName = networkName;
			this.chain1209k = chain1209k;
			this.genesisHash = genesisHash;
			this.builtInServers = builtInServers;
			this.sourcePath = sourcePath;
		}

		private String label() {
			return this.coinCode + "-" + this.networkName;
		}
	}

	private static final class Options {
		private final Path outputPath;
		private final Set<String> coinCodes;
		private final boolean skip1209k;
		private final boolean skipPeerDiscovery;
		private final boolean verify;
		private final boolean updateBuiltins;
		private final boolean updateBuiltinsOnly;
		private final Integer builtinLimit;
		private final int timeoutMs;
		private final int maxPeerSeeds;
		private final int threads;
		private final boolean help;

		private Options(Path outputPath, Set<String> coinCodes, boolean skip1209k, boolean skipPeerDiscovery,
				boolean verify, boolean updateBuiltins, boolean updateBuiltinsOnly, Integer builtinLimit, int timeoutMs, int maxPeerSeeds, int threads, boolean help) {
			this.outputPath = outputPath;
			this.coinCodes = coinCodes;
			this.skip1209k = skip1209k;
			this.skipPeerDiscovery = skipPeerDiscovery;
			this.verify = verify;
			this.updateBuiltins = updateBuiltins;
			this.updateBuiltinsOnly = updateBuiltinsOnly;
			this.builtinLimit = builtinLimit;
			this.timeoutMs = timeoutMs;
			this.maxPeerSeeds = maxPeerSeeds;
			this.threads = threads;
			this.help = help;
		}

		private static Options parse(String[] args) {
			Path outputPath = Paths.get(DEFAULT_OUTPUT_PATH);
			Set<String> coinCodes = new LinkedHashSet<>(Arrays.asList("BTC", "LTC", "DOGE", "DGB", "RVN"));
			boolean skip1209k = false;
			boolean skipPeerDiscovery = false;
			boolean verify = true;
			boolean updateBuiltins = false;
			boolean updateBuiltinsOnly = false;
			Integer builtinLimit = null;
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

					case "--skip-1209k":
						skip1209k = true;
						break;

					case "--skip-peers":
						skipPeerDiscovery = true;
						break;

					case "--skip-verify":
						verify = false;
						break;

					case "--update-builtins":
						updateBuiltins = true;
						break;

					case "--update-builtins-only":
						updateBuiltins = true;
						updateBuiltinsOnly = true;
						break;

					case "--builtin-limit":
						builtinLimit = parsePositiveInt(requireValue(args, ++index, arg), arg);
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

			return new Options(outputPath, coinCodes, skip1209k, skipPeerDiscovery, verify, updateBuiltins, updateBuiltinsOnly, builtinLimit, timeoutMs, maxPeerSeeds, threads, help);
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
