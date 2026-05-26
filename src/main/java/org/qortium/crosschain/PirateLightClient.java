package org.qortium.crosschain;

import org.qortium.crosschain.ChainableServer.ConnectionType;
import org.qortium.settings.Settings;

import java.util.Collection;
import java.util.Map;

/** Pirate Chain lightwalletd support through the shared Zcash-family light-client. */
public class PirateLightClient extends ZcashFamilyLightClient {

	public static class Server extends ZcashFamilyLightClient.Server {
		public Server(String hostname, ConnectionType connectionType, int port) {
			super(hostname, connectionType, port);
		}
	}

	public PirateLightClient(String netId, String genesisHash, Collection<Server> initialServerList,
			Map<ConnectionType, Integer> defaultPorts) {
		super(PirateChain.WALLET_CONFIG, netId, genesisHash, initialServerList, defaultPorts,
				() -> Settings.getInstance().getArrrDefaultBirthday());
	}

	@Override
	public ChainableServer getServer(String hostName, ChainableServer.ConnectionType type, int port) {
		return new Server(hostName, type, port);
	}
}
