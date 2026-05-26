package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.TradeDirection;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

@XmlAccessorType(XmlAccessType.FIELD)
public class TradeBotCreateRequest {

	@Schema(description = "Trade creator's public key", example = "2zR1WFsbM7akHghqSCYKBPk6LDP8aKiQSRS1FrwoLvoB")
	public byte[] creatorPublicKey;

	@Schema(description = "Trade direction from the maker's viewpoint. Defaults to SELL_LOCAL.", example = "SELL_LOCAL")
	public TradeDirection tradeDirection;

	@Schema(description = "Local-chain asset id paid out on successful trade", example = "1")
	public long localAssetId;

	@Schema(description = "Local-chain asset amount paid out on successful trade", example = "80.40000000", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public long localAmount;

	@Schema(description = "Minimum local-chain asset amount accepted for one split fill. If omitted, the offer must be filled in one trade.", example = "1.00000000", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public Long minFillLocalAmount;

	@Schema(description = "Maximum local-chain asset amount accepted for one split fill. If omitted, the offer must be filled in one trade.", example = "10.00000000", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public Long maxFillLocalAmount;

	@Schema(description = "Local-chain asset amount funding AT", example = "80.50000000", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public long fundingLocalAmount;

	@Schema(description = "Native asset reserve for non-native AT execution fees", example = "0.00000000", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public long nativeFeeReserve;

	@Schema(description = "Foreign blockchain name or currency code", example = "BITCOIN")
	public String foreignBlockchain;

	@Schema(description = "Foreign blockchain amount wanted in return", example = "0.00864200", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public Long foreignAmount;

	@Schema(description = "Foreign blockchain being sold by maker for SELL_FOREIGN_FOR_FOREIGN offers", example = "BITCOIN")
	public String offeredForeignBlockchain;

	@Schema(description = "Foreign blockchain amount being sold by maker for SELL_FOREIGN_FOR_FOREIGN offers", example = "0.01000000", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public Long offeredForeignAmount;

	@Schema(description = "Foreign blockchain requested by maker for SELL_FOREIGN_FOR_FOREIGN offers", example = "DOGECOIN")
	public String requestedForeignBlockchain;

	@Schema(description = "Foreign blockchain amount requested by maker for SELL_FOREIGN_FOR_FOREIGN offers", example = "100.00000000", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public Long requestedForeignAmount;

	@Schema(description = "Suggested trade timeout (minutes)", example = "10080")
	public int tradeTimeout;

	@Schema(description = "Receiving address for local/foreign offers: foreign-chain address for SELL_LOCAL, local-chain address for SELL_FOREIGN. SELL_FOREIGN_FOR_FOREIGN uses requestedForeignReceivingAddress.", example = "1BitcoinEaterAddressDontSendf59kuE")
	public String receivingAddress;

	@Schema(description = "Maker's foreign blockchain private key for SELL_FOREIGN offers. SELL_FOREIGN_FOR_FOREIGN uses offeredForeignKey.")
	public String foreignKey;

	@Schema(description = "Maker's offered-chain private key for SELL_FOREIGN_FOR_FOREIGN offers")
	public String offeredForeignKey;

	@Schema(description = "Maker's requested-chain receiving address for SELL_FOREIGN_FOR_FOREIGN offers")
	public String requestedForeignReceivingAddress;

	public TradeBotCreateRequest() {
	}

	public ForeignBlockchainRegistry.Entry resolveForeignBlockchain() {
		return ForeignBlockchainRegistry.fromString(this.foreignBlockchain);
	}

	public ForeignBlockchainRegistry.Entry resolveOfferedForeignBlockchain() {
		return ForeignBlockchainRegistry.fromString(this.offeredForeignBlockchain);
	}

	public ForeignBlockchainRegistry.Entry resolveRequestedForeignBlockchain() {
		return ForeignBlockchainRegistry.fromString(this.requestedForeignBlockchain);
	}

	public TradeDirection getTradeDirection() {
		return this.tradeDirection != null ? this.tradeDirection : TradeDirection.SELL_LOCAL;
	}

}
