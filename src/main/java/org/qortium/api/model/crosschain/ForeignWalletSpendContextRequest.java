package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class ForeignWalletSpendContextRequest {

	@Schema(description = "Root BIP32 extended public key")
	public String xpub58;

	@Schema(description = "Expected active BIP122 chain identifier")
	public String expectedChainId;

	public ForeignWalletSpendContextRequest() {
	}
}
