package org.qortium.crosschain;

import org.junit.After;
import org.junit.Test;
import org.qortium.settings.Settings;
import org.qortium.test.common.Common;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class BitcoinyChainDefinitionTests extends Common {

	@After
	public void restoreWalletSetting() {
		Settings.getInstance().enableWallet("BTC");
	}

	@Test
	public void testNotificationInstanceNeverBypassesOrBecomesSharedWalletInstance() {
		BitcoinyChainSpec spec = BitcoinyChainSpecs.BITCOIN;
		BitcoinyNetwork network = spec.getNetwork(BitcoinyChainSpecs.MAIN);
		BitcoinyChainDefinition<RegisteredBitcoiny> definition = new BitcoinyChainDefinition<>(
				spec.getConfig(), () -> network, (config, selectedNetwork) -> new RegisteredBitcoiny(spec, selectedNetwork));

		Settings.getInstance().disableWallet("BTC");
		RegisteredBitcoiny notificationInstance = definition.getOrCreateNotificationInstance();
		assertNotNull(notificationInstance);
		assertNull(definition.getInstance());

		Settings.getInstance().enableWallet("BTC");
		RegisteredBitcoiny walletInstance = definition.getInstance();
		assertNotNull(walletInstance);
		assertNotSame(notificationInstance, walletInstance);
		assertSame(notificationInstance, definition.getOrCreateNotificationInstance());
		assertSame(walletInstance, definition.getInstance());
	}
}
