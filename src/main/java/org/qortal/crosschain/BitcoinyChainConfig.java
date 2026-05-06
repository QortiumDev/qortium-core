package org.qortal.crosschain;

import org.bitcoinj.core.Coin;
import org.qortal.crosschain.ChainableServer.ConnectionType;

import java.util.EnumMap;
import java.util.Map;

public class BitcoinyChainConfig {

	private final String displayName;
	private final String currencyCode;
	private final Coin defaultFeePerKb;
	private final long minimumOrderAmount;
	private final Map<ConnectionType, Integer> defaultElectrumXPorts;

	public BitcoinyChainConfig(String displayName, String currencyCode, Coin defaultFeePerKb,
			long minimumOrderAmount, Map<ConnectionType, Integer> defaultElectrumXPorts) {
		this.displayName = displayName;
		this.currencyCode = currencyCode;
		this.defaultFeePerKb = defaultFeePerKb;
		this.minimumOrderAmount = minimumOrderAmount;
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
