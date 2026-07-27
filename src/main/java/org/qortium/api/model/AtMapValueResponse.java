package org.qortium.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/** Public read-only representation of one AT persistent-map lookup. */
@XmlAccessorType(XmlAccessType.FIELD)
public class AtMapValueResponse {

	@Schema(description = "AT address that owns the map")
	public String atAddress;

	@Schema(description = "first signed 64-bit map key")
	public long key1;

	@Schema(description = "second signed 64-bit map key")
	public long key2;

	@Schema(description = "stored map value, or zero when the key is unset")
	public long value;

	public AtMapValueResponse() {
	}

	public AtMapValueResponse(String atAddress, long key1, long key2, long value) {
		this.atAddress = atAddress;
		this.key1 = key1;
		this.key2 = key2;
		this.value = value;
	}
}
