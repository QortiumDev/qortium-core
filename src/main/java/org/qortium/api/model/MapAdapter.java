package org.qortium.api.model;

import javax.xml.bind.annotation.adapters.XmlAdapter;
import java.util.HashMap;
import java.util.Map;

public class MapAdapter extends XmlAdapter<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> marshal(Map<String, Object> map) {
        return map;
    }

    @Override
    public Map<String, Object> unmarshal(Map<String, Object> jsonMap) {
        if (jsonMap == null) {
            return new HashMap<>();
        }

        return jsonMap;
    }
}
