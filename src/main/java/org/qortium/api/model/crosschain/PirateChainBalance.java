package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PirateChainBalance {

	@Schema(description = "Total ARRR balance (zatoshis)", type = "number")
	public long zbalance;

	@Schema(description = "Available ARRR balance (verified zatoshis). Meaningless (a copy of the total "
			+ "balance) when verifiedBalanceKnown is false: callers must check that flag rather than "
			+ "trusting this figure on its own.", type = "number")
	public long verified_zbalance;

	@Schema(description = "Whether verified_zbalance reflects a real reading. The legacy backend can "
			+ "report a wallet's total balance without a verified/spendable figure; when that happens "
			+ "this is false and verified_zbalance is an unusable placeholder, never a silent stand-in "
			+ "for the true value.")
	public boolean verifiedBalanceKnown = true;

	public PirateChainBalance() {
	}

	public PirateChainBalance(long zbalance, long verifiedZbalance) {
		this(zbalance, verifiedZbalance, true);
	}

	public PirateChainBalance(long zbalance, long verifiedZbalance, boolean verifiedBalanceKnown) {
		this.zbalance = zbalance;
		this.verified_zbalance = verifiedZbalance;
		this.verifiedBalanceKnown = verifiedBalanceKnown;
	}
}
