package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

@XmlAccessorType(XmlAccessType.FIELD)
public class BitcoinySendRequest {

	@Schema(description = "BIP32 extended private key", example = "tprv___________________________________________________________________________________________________________")
	public String xprv58;

	@Schema(description = "Recipient address", example = "mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn")
	public String receivingAddress;

	@Schema(description = "Amount to send", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public long amount;

	@Schema(description = "Send the maximum spendable wallet balance to the recipient after subtracting the transaction fee")
	public Boolean sendMax;

	@Schema(description = "Transaction fee per byte (optional)", example = "0.00000100", type = "number")
	@XmlJavaTypeAdapter(value = org.qortium.api.AmountTypeAdapter.class)
	public Long feePerByte;

	public BitcoinySendRequest() {
	}

}
