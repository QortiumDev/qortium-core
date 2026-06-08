package org.qortium.crosschain;

import org.junit.After;
import org.junit.Test;
import org.qortium.crosschain.ChainableServer.ConnectionType;
import org.qortium.crosschain.ElectrumX.Server;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ElectrumServerSettingsTests extends Common {

	@After
	public void restoreDefaultSettings() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testConfiguredServersAreAppendedToGeneratedDefaults() throws Exception {
		Server configuredServer = new Server("custom-btc.example.com", ConnectionType.SSL, 50002);
		useServerSettings(bitcoinyServersJson("BTC", "MAIN", false, List.of(configuredServer), List.of()));

		Collection<Server> servers = ElectrumServerList.getServers("BTC", "MAIN", List.of());

		assertTrue(servers.size() > 1);
		assertTrue(servers.contains(configuredServer));
	}

	@Test
	public void testDisabledServerIsRemovedFromGeneratedDefaults() throws Exception {
		Server generatedServer = ElectrumServerList.loadGeneratedServers("BTC", "MAIN").get(0);
		useServerSettings(bitcoinyServersJson("BTC", "MAIN", false, List.of(), List.of(generatedServer)));

		Collection<Server> servers = ElectrumServerList.getServers("BTC", "MAIN", List.of());

		assertFalse(servers.contains(generatedServer));
	}

	@Test
	public void testReplaceDefaultsUsesOnlyConfiguredServers() throws Exception {
		Server configuredServer = new Server("replacement.example.com", ConnectionType.SSL, 50002);
		useServerSettings(bitcoinyServersJson("BTC", "MAIN", true, List.of(configuredServer), List.of()));

		Collection<Server> servers = ElectrumServerList.getServers("BTC", "MAIN", List.of());

		assertEquals(1, servers.size());
		assertTrue(servers.contains(configuredServer));
	}

	@Test
	public void testFallbackServersAreMergedWhenNoGeneratedServersExist() throws Exception {
		Server fallbackServer = new Server("fallback.example.com", ConnectionType.SSL, 50002);
		Server configuredServer = new Server("configured-regtest.example.com", ConnectionType.SSL, 50002);
		useServerSettings(bitcoinyServersJson("BTC", "REGTEST", false, List.of(configuredServer), List.of()));

		Collection<Server> servers = ElectrumServerList.getServers("BTC", "REGTEST", List.of(fallbackServer));

		assertEquals(2, servers.size());
		assertTrue(servers.contains(fallbackServer));
		assertTrue(servers.contains(configuredServer));
	}

	@Test
	public void testSslPreferenceAppliesAfterMergingSettings() throws Exception {
		Server tcpServer = new Server("configured-tcp.example.com", ConnectionType.TCP, 50001);
		Server sslServer = new Server("configured-ssl.example.com", ConnectionType.SSL, 50002);
		useServerSettings(bitcoinyServersJson("BTC", "REGTEST", true, List.of(tcpServer, sslServer), List.of()));

		Collection<Server> servers = ElectrumServerList.getServers("BTC", "REGTEST", List.of());

		assertEquals(1, servers.size());
		assertTrue(servers.contains(sslServer));
	}

	@Test
	public void testTcpServersAreFilteredByDefault() throws Exception {
		Server tcpServer = new Server("configured-tcp.example.com", ConnectionType.TCP, 50001);
		useServerSettings(bitcoinyServersJson("BTC", "REGTEST", true, List.of(tcpServer), List.of()));

		Collection<Server> servers = ElectrumServerList.getServers("BTC", "REGTEST", List.of());

		assertTrue(servers.isEmpty());
	}

	@Test
	public void testTcpServersCanBeExplicitlyAllowed() throws Exception {
		Server tcpServer = new Server("configured-tcp.example.com", ConnectionType.TCP, 50001);
		useServerSettings(plaintextElectrumServersJson("BTC", "REGTEST", true, List.of(tcpServer), List.of()));

		Collection<Server> servers = ElectrumServerList.getServers("BTC", "REGTEST", List.of());

		assertEquals(1, servers.size());
		assertTrue(servers.contains(tcpServer));
	}

	private static void useServerSettings(String json) throws Exception {
		Path directory = Files.createTempDirectory("electrum-server-settings-test");
		Path settingsPath = directory.resolve("settings.json");
		Files.write(settingsPath, (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
		Settings.fileInstance(settingsPath.toString());
	}

	private static String bitcoinyServersJson(String coin, String network, boolean replaceDefaults, List<Server> servers, List<Server> disabledServers) {
		return "{\"bitcoinyServers\":{\"" + coin + "\":{\"" + network + "\":{"
				+ "\"replaceDefaults\":" + replaceDefaults + ","
				+ "\"servers\":" + serverArrayJson(servers) + ","
				+ "\"disabledServers\":" + serverArrayJson(disabledServers)
				+ "}}}}";
	}

	private static String plaintextElectrumServersJson(String coin, String network, boolean replaceDefaults, List<Server> servers, List<Server> disabledServers) {
		return "{\"allowPlaintextElectrumServers\":true,\"bitcoinyServers\":{\"" + coin + "\":{\"" + network + "\":{"
				+ "\"replaceDefaults\":" + replaceDefaults + ","
				+ "\"servers\":" + serverArrayJson(servers) + ","
				+ "\"disabledServers\":" + serverArrayJson(disabledServers)
				+ "}}}}";
	}

	private static String serverArrayJson(List<Server> servers) {
		StringBuilder json = new StringBuilder("[");
		for (int i = 0; i < servers.size(); ++i) {
			Server server = servers.get(i);
			if (i > 0)
				json.append(',');

			json.append("{\"hostName\":\"")
					.append(server.getHostName())
					.append("\",\"port\":")
					.append(server.getPort())
					.append(",\"connectionType\":\"")
					.append(server.getConnectionType().name())
					.append("\"}");
		}

		return json.append(']').toString();
	}
}
