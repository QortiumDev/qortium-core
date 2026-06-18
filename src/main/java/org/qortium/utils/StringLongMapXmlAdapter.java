package org.qortium.utils;

import org.eclipse.persistence.oxm.annotations.XmlVariableNode;

import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlValue;
import javax.xml.bind.annotation.adapters.XmlAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class StringLongMapXmlAdapter extends XmlAdapter<StringLongMapXmlAdapter.StringLongMap, Map<String, Long>> {

	public static class StringLongMap {
		@XmlVariableNode("key")
		List<MapEntry> entries = new ArrayList<>();
	}

	public static class MapEntry {
		@XmlTransient
		public String key;

		@XmlValue
		public Long value;
	}

	@Override
	public Map<String, Long> unmarshal(StringLongMap stringLongMap) throws Exception {
		if (stringLongMap == null || stringLongMap.entries == null)
			return new HashMap<>();

		Map<String, Long> map = new HashMap<>(stringLongMap.entries.size());

		for (MapEntry entry : stringLongMap.entries) {
			if (entry == null || entry.key == null)
				continue;

			map.put(entry.key, entry.value);
		}

		return map;
	}

	@Override
	public StringLongMap marshal(Map<String, Long> map) throws Exception {
		StringLongMap output = new StringLongMap();
		if (map == null)
			return output;

		for (Entry<String, Long> entry : map.entrySet()) {
			MapEntry mapEntry = new MapEntry();
			mapEntry.key = entry.getKey();
			mapEntry.value = entry.getValue();
			output.entries.add(mapEntry);
		}

		return output;
	}
}
