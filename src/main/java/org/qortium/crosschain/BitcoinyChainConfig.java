package org.qortium.crosschain;

import org.bitcoinj.base.Coin;
import org.qortium.crosschain.ChainableServer.ConnectionType;

import java.util.EnumMap;
import java.util.Map;

public class BitcoinyChainConfig {

	private final String displayName;
	private final String currencyCode;
	private final Coin defaultFeePerKb;
	private final long minimumOrderAmount;
	private final int decimalPlaces;
	private final Map<ConnectionType, Integer> defaultElectrumXPorts;

	public BitcoinyChainConfig(String displayName, String currencyCode, Coin defaultFeePerKb,
			long minimumOrderAmount, Map<ConnectionType, Integer> defaultElectrumXPorts) {
		this(displayName, currencyCode, defaultFeePerKb, minimumOrderAmount, 8, defaultElectrumXPorts);
	}

	public BitcoinyChainConfig(String displayName, String currencyCode, Coin defaultFeePerKb,
			long minimumOrderAmount, int decimalPlaces, Map<ConnectionType, Integer> defaultElectrumXPorts) {
		this.displayName = displayName;
		this.currencyCode = currencyCode;
		this.defaultFeePerKb = defaultFeePerKb;
		this.minimumOrderAmount = minimumOrderAmount;
		this.decimalPlaces = decimalPlaces;
		this.defaultElectrumXPorts = new EnumMap<>(defaultElectrumXPorts);
	}

	public String getDisplayName() {
		return this.displayName;
	}

	public String getCurrencyCode() {
		return this.currencyCode;
	}

	public Coin getDefaultFeePerKb() {
		return this.defaultFeePerKb;
	}

	public long getMinimumOrderAmount() {
		return this.minimumOrderAmount;
	}

	public int getDecimalPlaces() {
		return this.decimalPlaces;
	}

	public Map<ConnectionType, Integer> getDefaultElectrumXPorts() {
		return new EnumMap<>(this.defaultElectrumXPorts);
	}

	public static Map<ConnectionType, Integer> defaultElectrumXPorts() {
		Map<ConnectionType, Integer> ports = new EnumMap<>(ConnectionType.class);
		ports.put(ConnectionType.TCP, 50001);
		ports.put(ConnectionType.SSL, 50002);
		return ports;
	}

}
