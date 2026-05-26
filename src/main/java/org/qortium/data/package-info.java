// Only edit org/qortium/data/package-info.java
// Other package-info.java files are generated using above file

@XmlJavaTypeAdapters({
	@XmlJavaTypeAdapter(
		type = byte[].class,
		value = org.qortium.api.Base58TypeAdapter.class
	), @XmlJavaTypeAdapter(
		type = java.math.BigDecimal.class,
		value = org.qortium.api.BigDecimalTypeAdapter.class
	)
})
package org.qortium.data;

import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapters;
