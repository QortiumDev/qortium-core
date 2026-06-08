package org.qortium.crosschain;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.qortium.crosschain.ChainableServer.ConnectionType;
import org.qortium.crosschain.ElectrumX.Server;
import org.qortium.settings.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class ElectrumServerList {

	public static final String RESOURCE_PATH = "crosschain/electrum-servers.json";

	private static final Logger LOGGER = LogManager.getLogger(ElectrumServerList.class);

	private ElectrumServerList() {
	}

	public static Collection<Server> getServers(String coinCode, String networkName, Collection<Server> fallbackServers) {
		List<Server> defaultServers = loadDefaultServers(coinCode, networkName, fallbackServers);
		List<Server> configuredServers = applyConfiguredServers(coinCode, networkName, defaultServers);
		return filterAllowedServers(preferSslServers(configuredServers));
	}

	public static boolean isAllowedByTransportPolicy(ChainableServer server) {
		if (server == null)
			return false;

		if (server.getConnectionType() != ConnectionType.TCP)
			return true;

		Settings settings = Settings.getLoadedInstance();
		return settings != null && settings.isPlaintextElectrumServersAllowed();
	}

	public static boolean isDefaultServer(String coinCode, String networkName, ChainableServer server, Collection<Server> fallbackServers) {
		if (server == null)
			return false;

		Server normalisedServer = new Server(
				normaliseHost(server.getHostName()),
				server.getConnectionType(),
				server.getPort());

		return loadDefaultServers(coinCode, networkName, fallbackServers).contains(normalisedServer);
	}

	static List<Server> loadDefaultServers(String coinCode, String networkName, Collection<Server> fallbackServers) {
		List<Server> generatedServers = loadGeneratedServers(coinCode, networkName);
		if (!generatedServers.isEmpty())
			return generatedServers;

		if (fallbackServers == null)
			return Collections.emptyList();

		LinkedHashSet<Server> normalisedFallbackServers = new LinkedHashSet<>();
		for (Server fallbackServer : fallbackServers)
			normalisedFallbackServers.add(new Server(
					normaliseHost(fallbackServer.getHostName()),
					fallbackServer.getConnectionType(),
					fallbackServer.getPort(),
					fallbackServer.getCertificateSha256Fingerprint()));

		return new ArrayList<>(normalisedFallbackServers);
	}

	static List<Server> loadGeneratedServers(String coinCode, String networkName) {
		ClassLoader classLoader = ElectrumServerList.class.getClassLoader();

		try (InputStream inputStream = classLoader.getResourceAsStream(RESOURCE_PATH)) {
			if (inputStream == null)
				return Collections.emptyList();

			try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
				Map<String, Map<String, List<Server>>> servers = parseServerResource(reader);
				Map<String, List<Server>> coinServers = servers.get(normalizeKey(coinCode));
				if (coinServers == null)
					return Collections.emptyList();

				List<Server> networkServers = coinServers.get(normalizeKey(networkName));
				return networkServers == null ? Collections.emptyList() : networkServers;
			}
		} catch (IOException | ParseException | ClassCastException e) {
			LOGGER.warn("Unable to load generated Electrum server list from {}", RESOURCE_PATH, e);
			return Collections.emptyList();
		}
	}

	static Map<String, Map<String, List<Server>>> parseServerResource(Reader reader) throws IOException, ParseException {
		Object parsed = new JSONParser().parse(reader);
		if (!(parsed instanceof JSONObject))
			return Collections.emptyMap();

		JSONObject root = (JSONObject) parsed;
		Object serversObject = root.get("servers");
		if (!(serversObject instanceof JSONObject))
			return Collections.emptyMap();

		Map<String, Map<String, List<Server>>> serversByCoin = new LinkedHashMap<>();
		JSONObject serversJson = (JSONObject) serversObject;

		for (Object coinKeyObject : serversJson.keySet()) {
			Object networksObject = serversJson.get(coinKeyObject);
			if (!(coinKeyObject instanceof String) || !(networksObject instanceof JSONObject))
				continue;

			Map<String, List<Server>> serversByNetwork = new LinkedHashMap<>();
			JSONObject networksJson = (JSONObject) networksObject;

			for (Object networkKeyObject : networksJson.keySet()) {
				Object serverArrayObject = networksJson.get(networkKeyObject);
				if (!(networkKeyObject instanceof String) || !(serverArrayObject instanceof JSONArray))
					continue;

				serversByNetwork.put(normalizeKey((String) networkKeyObject), parseServerArray((JSONArray) serverArrayObject));
			}

			serversByCoin.put(normalizeKey((String) coinKeyObject), serversByNetwork);
		}

		return serversByCoin;
	}

	private static List<Server> parseServerArray(JSONArray serverArray) {
		List<Server> servers = new ArrayList<>();

		for (Object serverObject : serverArray) {
			if (!(serverObject instanceof JSONObject))
				continue;

			Server server = parseServer((JSONObject) serverObject);
			if (server != null)
				servers.add(server);
		}

		return new ArrayList<>(new LinkedHashSet<>(servers));
	}

	static List<Server> preferSslServers(Collection<Server> servers) {
		if (servers == null || servers.isEmpty())
			return Collections.emptyList();

		List<Server> secureServers = servers.stream()
				.filter(server -> server.getConnectionType() == ConnectionType.SSL)
				.collect(Collectors.toList());

		if (!secureServers.isEmpty())
			return secureServers;

		return new ArrayList<>(servers);
	}

	static <T extends ChainableServer> List<T> filterAllowedServers(Collection<T> servers) {
		if (servers == null || servers.isEmpty())
			return Collections.emptyList();

		return servers.stream()
				.filter(ElectrumServerList::isAllowedByTransportPolicy)
				.collect(Collectors.toList());
	}

	static List<Server> applyConfiguredServers(String coinCode, String networkName, Collection<Server> defaultServers) {
		Settings settings = Settings.getLoadedInstance();
		Settings.BitcoinyServerSettings serverSettings = settings == null ? null : settings.getBitcoinyServerSettings(coinCode, networkName);
		if (serverSettings == null)
			return defaultServers == null ? Collections.emptyList() : new ArrayList<>(new LinkedHashSet<>(defaultServers));

		LinkedHashSet<Server> servers = new LinkedHashSet<>();
		if (!serverSettings.isReplaceDefaults() && defaultServers != null)
			servers.addAll(defaultServers);

		for (Settings.BitcoinyServer disabledServer : serverSettings.getDisabledServers())
			servers.remove(toServer(disabledServer));

		for (Settings.BitcoinyServer configuredServer : serverSettings.getServers()) {
			Server server = toServer(configuredServer);
			servers.remove(server);
			servers.add(server);
		}

		return new ArrayList<>(servers);
	}

	private static Server toServer(Settings.BitcoinyServer server) {
		return new Server(
				normaliseHost(server.getHostName()),
				ConnectionType.valueOf(server.getConnectionType()),
				server.getPort(),
				server.getCertificateSha256Fingerprint());
	}

	private static Server parseServer(JSONObject serverJson) {
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

		return new Server(normaliseHost(hostName), connectionType, port, certificateSha256Fingerprint);
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

	private static String normalizeKey(String value) {
		return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
	}

	private static String normaliseHost(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}
}
