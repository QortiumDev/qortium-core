package org.qortal.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortal.crosschain.ForeignBlockchainRegistry;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

@XmlAccessorType(XmlAccessType.FIELD)
public class TradeBotCreateRequest {

	@Schema(description = "Trade creator's public key", example = "2zR1WFsbM7akHghqSCYKBPk6LDP8aKiQSRS1FrwoLvoB")
	public byte[] creatorPublicKey;

	@Schema(description = "Native asset amount paid out on successful trade", example = "80.40000000", type = "number")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public long nativeAmount;

	@Schema(description = "Native asset amount funding AT, including covering AT execution fees", example = "80.50000000", type = "number")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public long fundingNativeAmount;

	@Schema(description = "Foreign blockchain name or currency code", example = "BITCOIN")
	public String foreignBlockchain;

	@Schema(description = "Foreign blockchain amount wanted in return", example = "0.00864200", type = "number")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public Long foreignAmount;

	@Schema(description = "Suggested trade timeout (minutes)", example = "10080")
	public int tradeTimeout;

	@Schema(description = "Foreign blockchain address for receiving", example = "1BitcoinEaterAddressDontSendf59kuE")
	public String receivingAddress;

	public TradeBotCreateRequest() {
	}

	public ForeignBlockchainRegistry.Entry resolveForeignBlockchain() {
		return ForeignBlockchainRegistry.fromString(this.foreignBlockchain);
	}

}
