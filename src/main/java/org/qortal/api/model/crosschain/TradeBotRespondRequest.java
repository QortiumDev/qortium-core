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

	@Schema(description = "local-chain address for receiving the offered local asset from AT", example = "Qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq")
	public String receivingAddress;

	@Schema(description = "Optional local-chain asset amount to fill from a split offer. If omitted, the largest currently valid fill is used.", example = "1.00000000", type = "number")
	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	public Long fillLocalAmount;

	public TradeBotRespondRequest() {
	}

}
