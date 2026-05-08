package org.qortal.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class TradeBotRespondRequest {

	@Schema(description = "local-chain AT address", example = "Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
	public String atAddress;

	@Schema(description = "Foreign blockchain private key, e.g. BIP32 'm' key for supported Bitcoiny chains",
			example = "xprv___________________________________________________________________________________________________________")
	public String foreignKey;

	@Schema(description = "local-chain address for receiving the offered local asset from AT", example = "Qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq")
	public String receivingAddress;

	public TradeBotRespondRequest() {
	}

}
