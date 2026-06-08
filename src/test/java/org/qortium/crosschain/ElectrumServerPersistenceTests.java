package org.qortium.crosschain;

import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Context;
import org.junit.After;
import org.junit.Test;
import org.qortium.api.resource.CrossChainUtils;
import org.qortium.crosschain.ChainableServer.ConnectionType;
import org.qortium.crosschain.ElectrumX.Server;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ElectrumServerPersistenceTests extends Common {

	@After
	public void restoreDefaultSettings() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testAddCustomServerPersistsAndAppliesToProvider() throws Exception {
		useEmptySettings();
		TestBitcoiny bitcoiny = new TestBitcoiny(List.of());
		Server server = new Server("custom.example.com", ConnectionType.SSL, 50002);

		assertTrue(CrossChainUtils.addServer(bitcoiny, server));

		Settings.BitcoinyServerSettings serverSettings = Settings.getInstance().getBitcoinyServerSettings("BTC", "MAIN");
		assertTrue(serverSettings.getServers().contains(Settings.BitcoinyServer.from(server)));
		assertTrue(bitcoiny.getBlockchainProvider().getServers().contains(server));
	}

	@Test
	public void testAddTcpServerIsRejectedWhenPlaintextIsDisabled() throws Exception {
		useEmptySettings();
		TestBitcoiny bitcoiny = new TestBitcoiny(List.of());
		Server server = new Server("custom-tcp.example.com", ConnectionType.TCP, 50001);

		assertFalse(CrossChainUtils.addServer(bitcoiny, server));

		Settings.BitcoinyServerSettings serverSettings = Settings.getInstance().getBitcoinyServerSettings("BTC", "MAIN");
		assertTrue(serverSettings == null || !serverSettings.getServers().contains(Settings.BitcoinyServer.from(server)));
		assertFalse(bitcoiny.getBlockchainProvider().getServers().contains(server));
	}

	@Test
	public void testRemoveCustomServerRemovesPersistedOverrideAndProviderEntry() throws Exception {
		useEmptySettings();
		Server server = new Server("custom.example.com", ConnectionType.SSL, 50002);
		TestBitcoiny bitcoiny = new TestBitcoiny(List.of(server));

		assertTrue(CrossChainUtils.addServer(bitcoiny, server));
		assertTrue(CrossChainUtils.removeServer(bitcoiny, server));

		Settings.BitcoinyServerSettings serverSettings = Settings.getInstance().getBitcoinyServerSettings("BTC", "MAIN");
		assertFalse(serverSettings.getServers().contains(Settings.BitcoinyServer.from(server)));
		assertFalse(serverSettings.getDisabledServers().contains(Settings.BitcoinyServer.from(server)));
		assertFalse(bitcoiny.getBlockchainProvider().getServers().contains(server));
	}

	@Test
	public void testRemoveDefaultServerPersistsDisabledServerAndAppliesToProvider() throws Exception {
		useEmptySettings();
		Server defaultServer = ElectrumServerList.loadGeneratedServers("BTC", "MAIN").get(0);
		TestBitcoiny bitcoiny = new TestBitcoiny(List.of(defaultServer));

		assertTrue(CrossChainUtils.removeServer(bitcoiny, defaultServer));

		Settings.BitcoinyServerSettings serverSettings = Settings.getInstance().getBitcoinyServerSettings("BTC", "MAIN");
		assertTrue(serverSettings.getDisabledServers().contains(Settings.BitcoinyServer.from(defaultServer)));
		assertFalse(bitcoiny.getBlockchainProvider().getServers().contains(defaultServer));
	}

	@Test
	public void testAddDisabledDefaultServerRemovesDisabledOverrideAndAppliesToProvider() throws Exception {
		useEmptySettings();
		Server defaultServer = ElectrumServerList.loadGeneratedServers("BTC", "MAIN").get(0);
		TestBitcoiny bitcoiny = new TestBitcoiny(List.of(defaultServer));

		assertTrue(CrossChainUtils.removeServer(bitcoiny, defaultServer));
		assertTrue(CrossChainUtils.addServer(bitcoiny, defaultServer));

		Settings.BitcoinyServerSettings serverSettings = Settings.getInstance().getBitcoinyServerSettings("BTC", "MAIN");
		assertFalse(serverSettings.getDisabledServers().contains(Settings.BitcoinyServer.from(defaultServer)));
		assertTrue(bitcoiny.getBlockchainProvider().getServers().contains(defaultServer));
	}

	private static void useEmptySettings() throws Exception {
		Path directory = Files.createTempDirectory("electrum-server-persistence-test");
		Path settingsPath = directory.resolve("settings.json");
		Files.write(settingsPath, ("{}" + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
		Settings.fileInstance(settingsPath.toString());
	}

	private static class TestBitcoiny extends Bitcoiny {
		private long feeRequired = 1_000L;

		private TestBitcoiny(List<Server> servers) {
			super(new ElectrumX("Bitcoin-MAIN",
							BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.MAIN).getGenesisHash(),
							servers,
							BitcoinyChainConfig.defaultElectrumXPorts()),
					new Context(BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams()),
					BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams(),
					"BTC",
					Coin.valueOf(5_000));

			this.blockchainProvider.setBlockchain(this);
		}

		@Override
		public long getP2shFee(Long timestamp) {
			return 1_000L;
		}

		@Override
		public long getFeeRequired() {
			return this.feeRequired;
		}

		@Override
		public void setFeeRequired(long fee) {
			this.feeRequired = fee;
		}
	}
}
