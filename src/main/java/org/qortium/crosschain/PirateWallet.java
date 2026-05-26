package org.qortium.crosschain;

import org.qortium.settings.Settings;

import java.io.IOException;
import java.util.Collection;
import java.util.Random;

public class PirateWallet extends ZcashFamilyWallet {

	public PirateWallet(byte[] entropyBytes, boolean isNullSeedWallet) throws IOException {
		super(PirateChain.WALLET_CONFIG, entropyBytes, isNullSeedWallet);
	}

	public PirateLightClient.Server getRandomServer() {
		PirateChain.PirateChainNet pirateChainNet = Settings.getInstance().getPirateChainNet();
		Collection<PirateLightClient.Server> servers = pirateChainNet.getServers();
		PirateLightClient.Server[] serversArray = servers.toArray(new PirateLightClient.Server[0]);

		return serversArray[new Random().nextInt(serversArray.length)];
	}
}
