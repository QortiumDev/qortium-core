package org.qortium.settings;

import org.eclipse.persistence.oxm.annotations.XmlVariableNode;

import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.XmlValue;
import javax.xml.bind.annotation.adapters.XmlAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * MOXy JSON adapter to support JSON objects mapping directly to string entries, e.g.
 *
 * <pre>
 * "bitcoinyNetworks": {
 *   "BTC": "TEST4",
 *   "LTC": "TEST4"
 * }
 * </pre>
 */
public class StringMapXmlAdapter extends XmlAdapter<StringMapXmlAdapter.StringMap, Map<String, String>> {

	@XmlType(name = "stringMap")
	public static class StringMap {
		@XmlVariableNode("key")
		List<MapEntry> entries = new ArrayList<>();
	}

	@XmlType(name = "stringMapEntry")
	public static class MapEntry {
		@XmlTransient
		public String key;

		@XmlValue
		public String value;
	}

	@Override
	public Map<String, String> unmarshal(StringMap stringMap) {
		if (stringMap == null || stringMap.entries == null)
			return new LinkedHashMap<>();

		Map<String, String> map = new LinkedHashMap<>(stringMap.entries.size());
		for (MapEntry entry : stringMap.entries) {
			if (entry == null || entry.key == null)
				continue;

			map.put(entry.key, entry.value);
		}

		return map;
	}

	@Override
	public StringMap marshal(Map<String, String> map) {
		StringMap output = new StringMap();
		if (map == null)
			return output;

		for (Entry<String, String> entry : map.entrySet()) {
			MapEntry mapEntry = new MapEntry();
			mapEntry.key = entry.getKey();
			mapEntry.value = entry.getValue();
			output.entries.add(mapEntry);
		}

		return output;
	}
}
