package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PirateChainBalance {

	@Schema(description = "Total ARRR balance (zatoshis)", type = "number")
	public long zbalance;

	@Schema(description = "Available ARRR balance (verified zatoshis)", type = "number")
	public long verified_zbalance;

	public PirateChainBalance() {
	}

	public PirateChainBalance(long zbalance, long verifiedZbalance) {
		this.zbalance = zbalance;
		this.verified_zbalance = verifiedZbalance;
	}
}
