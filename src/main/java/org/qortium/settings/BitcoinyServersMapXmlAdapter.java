package org.qortium.settings;

import org.eclipse.persistence.oxm.annotations.XmlVariableNode;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.adapters.XmlAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * MOXy JSON adapter to support nested objects keyed by coin and network, e.g.
 *
 * <pre>
 * "bitcoinyServers": {
 *   "BTC": {
 *     "MAIN": {
 *       "replaceDefaults": false,
 *       "servers": [
 *         {"hostName": "electrum.example.org", "port": 50002, "connectionType": "SSL"}
 *       ]
 *     }
 *   }
 * }
 * </pre>
 */
public class BitcoinyServersMapXmlAdapter extends XmlAdapter<BitcoinyServersMapXmlAdapter.CoinMap, Map<String, Map<String, Settings.BitcoinyServerSettings>>> {

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class CoinMap {
		@XmlVariableNode("key")
		List<CoinEntry> entries = new ArrayList<>();
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class CoinEntry {
		@XmlTransient
		public String key;

		@XmlVariableNode("key")
		List<NetworkEntry> entries = new ArrayList<>();
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class NetworkEntry {
		@XmlTransient
		public String key;

		public boolean replaceDefaults;
		public List<Settings.BitcoinyServer> servers = new ArrayList<>();
		public List<Settings.BitcoinyServer> disabledServers = new ArrayList<>();
	}

	@Override
	public Map<String, Map<String, Settings.BitcoinyServerSettings>> unmarshal(CoinMap coinMap) {
		Map<String, Map<String, Settings.BitcoinyServerSettings>> output = new LinkedHashMap<>();
		if (coinMap == null || coinMap.entries == null)
			return output;

		for (CoinEntry coinEntry : coinMap.entries) {
			if (coinEntry == null || coinEntry.key == null)
				continue;

			Map<String, Settings.BitcoinyServerSettings> networkSettings = new LinkedHashMap<>();
			if (coinEntry.entries != null) {
				for (NetworkEntry networkEntry : coinEntry.entries) {
					if (networkEntry == null || networkEntry.key == null)
						continue;

					Settings.BitcoinyServerSettings settings = new Settings.BitcoinyServerSettings();
					settings.setReplaceDefaults(networkEntry.replaceDefaults);
					settings.setServers(networkEntry.servers);
					settings.setDisabledServers(networkEntry.disabledServers);
					networkSettings.put(networkEntry.key, settings);
				}
			}

			output.put(coinEntry.key, networkSettings);
		}

		return output;
	}

	@Override
	public CoinMap marshal(Map<String, Map<String, Settings.BitcoinyServerSettings>> map) {
		CoinMap output = new CoinMap();
		if (map == null)
			return output;

		for (Entry<String, Map<String, Settings.BitcoinyServerSettings>> coinEntry : map.entrySet()) {
			CoinEntry outputCoinEntry = new CoinEntry();
			outputCoinEntry.key = coinEntry.getKey();

			Map<String, Settings.BitcoinyServerSettings> networkSettings = coinEntry.getValue();
			if (networkSettings != null) {
				for (Entry<String, Settings.BitcoinyServerSettings> networkEntry : networkSettings.entrySet()) {
					NetworkEntry outputNetworkEntry = new NetworkEntry();
					outputNetworkEntry.key = networkEntry.getKey();

					Settings.BitcoinyServerSettings settings = networkEntry.getValue();
					if (settings != null) {
						outputNetworkEntry.replaceDefaults = settings.isReplaceDefaults();
						outputNetworkEntry.servers = settings.getServers();
						outputNetworkEntry.disabledServers = settings.getDisabledServers();
					}

					outputCoinEntry.entries.add(outputNetworkEntry);
				}
			}

			output.entries.add(outputCoinEntry);
		}

		return output;
	}
}
