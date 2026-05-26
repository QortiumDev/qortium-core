package org.qortium.crosschain;

import org.qortium.settings.Settings;

import java.util.function.BiFunction;
import java.util.function.Supplier;

public final class BitcoinyChainDefinition<T extends Bitcoiny> {

	private final BitcoinyChainConfig config;
	private final Supplier<? extends BitcoinyNetwork> networkSupplier;
	private final BiFunction<BitcoinyChainConfig, BitcoinyNetwork, T> instanceFactory;

	private T instance;

	public BitcoinyChainDefinition(BitcoinyChainConfig config, Supplier<? extends BitcoinyNetwork> networkSupplier,
			BiFunction<BitcoinyChainConfig, BitcoinyNetwork, T> instanceFactory) {
		this.config = config;
		this.networkSupplier = networkSupplier;
		this.instanceFactory = instanceFactory;
	}

	public BitcoinyChainConfig getConfig() {
		return this.config;
	}

	public String getCurrencyCode() {
		return this.config.getCurrencyCode();
	}

	public synchronized T getInstance() {
		if (this.instance == null && Settings.getInstance().isWalletEnabled(this.config.getCurrencyCode()))
			this.instance = this.instanceFactory.apply(this.config, this.networkSupplier.get());

		return this.instance;
	}

	public synchronized void resetForTesting() {
		this.instance = null;
	}

}
