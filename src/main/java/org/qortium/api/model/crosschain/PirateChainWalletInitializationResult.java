package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PirateChainWalletInitializationResult {

	@Schema(description = "The persisted wallet initialization policy", example = "NEW_AT_CURRENT_TIP")
	public String initializationMode;

	@Schema(description = "The exact validated Pirate Chain height retained as the wallet birthday",
			example = "4200000")
	public int birthdayHeight;

	public PirateChainWalletInitializationResult() {
	}

	public PirateChainWalletInitializationResult(String initializationMode, int birthdayHeight) {
		this.initializationMode = initializationMode;
		this.birthdayHeight = birthdayHeight;
	}
}
