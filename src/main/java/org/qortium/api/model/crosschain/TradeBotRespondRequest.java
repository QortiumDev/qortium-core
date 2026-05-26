package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

@XmlAccessorType(XmlAccessType.FIELD)
public class TradeBotRespondRequest {

	@Schema(description = "local-chain AT address", example = "Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	public String atAddress;

	@Schema(description = "Responder's foreign blockchain private key for local/foreign offers. SELL_FOREIGN_FOR_FOREIGN uses requestedForeignKey.",
			example = "xprv___________________________________________________________________________________________________________")
	public String foreignKey;

	@Schema(description = "Responder's requested-chain private key for funding/refunding SELL_FOREIGN_FOR_FOREIGN offers")
	public String requestedForeignKey;

	@Schema(description = "Responder's local-chain public key for SELL_FOREIGN reverse trade transactions; not used for SELL_FOREIGN_FOR_FOREIGN offers")
	public byte[] responderPublicKey;

	@Schema(description = "Receiving address for local/foreign offers: local-chain address for SELL_LOCAL, foreign-chain address for SELL_FOREIGN. SELL_FOREIGN_FOR_FOREIGN uses offeredForeignReceivingAddress.", example = "Qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq")
	public String receivingAddress;

	@Schema(description = "Responder's offered-chain receiving address for redeeming SELL_FOREIGN_FOR_FOREIGN offers")
	public String offeredForeignReceivingAddress;

	@Schema(description = "Optional local-chain asset amount to fill from a split offer. Not supported for SELL_FOREIGN_FOR_FOREIGN offers.", example = "1.00000000", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public Long fillLocalAmount;

	public TradeBotRespondRequest() {
	}

}
