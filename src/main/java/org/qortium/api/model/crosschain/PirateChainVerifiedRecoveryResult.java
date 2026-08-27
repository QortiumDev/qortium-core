package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PirateChainVerifiedRecoveryResult {

	@Schema(description = "Native account key group id for the imported key")
	public long keyId;

	@Schema(description = "Key pool of the imported spending key", example = "sapling")
	public String pool;

	@Schema(description = "Canonical lowercase verified receive address", example = "zs1...")
	public String address;

	@Schema(description = "Verified sequential address index")
	public int addressIndex;

	@Schema(description = "Earliest birthday height retained for this key group")
	public int birthdayHeight;

	@Schema(description = "True when the exact spending key already existed in this wallet")
	public boolean alreadyImported;

	@Schema(description = "Current wallet-wide rescan requirement after the import")
	public boolean rescanRequired;

	@Schema(description = "Durable minimum replay height across pending verified imports. OMITTED from the "
			+ "response when no verified-import rescan is pending; when present, a historical rescan from this "
			+ "height is still owed before recovered funds are visible and spendable.", nullable = true)
	public Long requiredRescanFromHeight;

	public PirateChainVerifiedRecoveryResult() {
	}

	public PirateChainVerifiedRecoveryResult(long keyId, String pool, String address, int addressIndex,
			int birthdayHeight, boolean alreadyImported, boolean rescanRequired, Long requiredRescanFromHeight) {
		this.keyId = keyId;
		this.pool = pool;
		this.address = address;
		this.addressIndex = addressIndex;
		this.birthdayHeight = birthdayHeight;
		this.alreadyImported = alreadyImported;
		this.rescanRequired = rescanRequired;
		this.requiredRescanFromHeight = requiredRescanFromHeight;
	}
}
