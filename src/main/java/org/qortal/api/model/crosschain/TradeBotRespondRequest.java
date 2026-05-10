package org.qortal.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

@XmlAccessorType(XmlAccessType.FIELD)
public class TradeBotRespondRequest {

	@Schema(description = "local-chain AT address", example = "Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	public String atAddress;

	@Schema(description = "Foreign blockchain private key, e.g. BIP32 'm' key for supported Bitcoiny chains",
			example = "xprv___________________________________________________________________________________________________________")
	public String foreignKey;

	@Schema(description = "Responder's requested foreign blockchain private key, required later for SELL_FOREIGN_FOR_FOREIGN offers")
	public String requestedForeignKey;

	@Schema(description = "Responder's local-chain public key, required for SELL_FOREIGN reservation messages")
	public byte[] responderPublicKey;

	@Schema(description = "Receiving address: local-chain address for SELL_LOCAL offers, foreign-chain address for SELL_FOREIGN offers. SELL_FOREIGN_FOR_FOREIGN uses offeredForeignReceivingAddress.", example = "Qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq")
	public String receivingAddress;

	@Schema(description = "Responder's offered foreign blockchain receiving address, required later for SELL_FOREIGN_FOR_FOREIGN offers")
	public String offeredForeignReceivingAddress;

	@Schema(description = "Optional local-chain asset amount to fill from a split offer. If omitted, the largest currently valid fill is used.", example = "1.00000000", type = "number")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public Long fillLocalAmount;

	public TradeBotRespondRequest() {
	}

}
