package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class BitcoinyRawTransactionRequest {

	@Schema(description = "Raw transaction hex")
	public String rawTransactionHex;

	@Schema(description = "Expected active BIP122 chain identifier. Home-local signing clients should always supply this.")
	public String expectedChainId;

	public BitcoinyRawTransactionRequest() {
		// For JAXB
	}
}
