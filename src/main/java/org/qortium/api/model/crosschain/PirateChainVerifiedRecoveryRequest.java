package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class PirateChainVerifiedRecoveryRequest {

	@Schema(description = "32 bytes of entropy, Base58 encoded, selecting the Unified wallet account", example = "5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV")
	public String entropy58;

	@Schema(description = "Key pool of the imported spending key", example = "sapling", allowableValues = { "sapling", "ironwood" })
	public String pool;

	@Schema(description = "Bech32 extended spending key to import; never logged and never echoed")
	public String spendingKey;

	@Schema(description = "Canonical receive address the spending key must control", example = "zs1...")
	public String expectedAddress;

	@Schema(description = "Sequential address index proving the expected address, 0 to 4096 inclusive", example = "0")
	public Integer addressIndex;

	@Schema(description = "Conservative wallet birthday height for historical recovery", example = "2000000")
	public Integer birthdayHeight;

	@Schema(description = "Optional label for the recovered key, at most 100 UTF-8 bytes after trimming")
	public String label;

	public PirateChainVerifiedRecoveryRequest() {
	}
}
