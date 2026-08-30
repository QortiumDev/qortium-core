package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PirateChainWalletInitializationRequest {

	@Schema(description = "32 bytes of entropy, Base58 encoded, selecting the Unified wallet account",
			example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV")
	public String entropy58;

	@Schema(description = "Explicit one-time wallet initialization policy",
			example = "NEW_AT_CURRENT_TIP", allowableValues = { "NEW_AT_CURRENT_TIP" })
	public String initializationMode;

	public PirateChainWalletInitializationRequest() {
	}
}
