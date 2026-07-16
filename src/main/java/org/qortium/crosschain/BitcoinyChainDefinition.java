package org.qortium.crosschain;

import org.qortium.settings.Settings;

import java.util.function.BiFunction;
import java.util.function.Supplier;

public final class BitcoinyChainDefinition<T extends Bitcoiny> {

	private final BitcoinyChainConfig config;
	private final Supplier<? extends BitcoinyNetwork> networkSupplier;
	private final BiFunction<BitcoinyChainConfig, BitcoinyNetwork, T> instanceFactory;

	private T instance;
	private T notificationInstance;

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
		if (this.instance != null)
			return this.instance;

		if (!Settings.getInstance().isWalletEnabled(this.config.getCurrencyCode()))
			return null;

		this.instance = this.instanceFactory.apply(this.config, this.networkSupplier.get());

		return this.instance;
	}

	/**
	 * Creates a segregated watch-only chain instance without changing or bypassing the operator's
	 * wallet-enabled setting. This instance is never returned from {@link #getInstance()}.
	 */
	public synchronized T getOrCreateNotificationInstance() {
		if (this.notificationInstance == null)
			this.notificationInstance = this.instanceFactory.apply(this.config, this.networkSupplier.get());

		return this.notificationInstance;
	}

	public synchronized void resetForTesting() {
		this.instance = null;
		this.notificationInstance = null;
	}

}
