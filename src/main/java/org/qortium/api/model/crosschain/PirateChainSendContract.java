package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.List;

/**
 * Static description of this node's ARRR send contract ({@code GET /crosschain/arrr/sendcontract}).
 * Clients read it once to decide whether they can drive this node's {@code /send} endpoint and how
 * to present the fixed fee; it carries no wallet state and needs no entropy.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class PirateChainSendContract {

	/** Version 1: synchronous send, HTTP 200 with a txid. Version 2 (durable operations) is later. */
	public static final int SEND_PROTOCOL_VERSION = 1;
	public static final String FEE_POLICY_FIXED = "FIXED";
	public static final int AMOUNT_DECIMALS = 8;
	public static final int MAX_MEMO_BYTES = 512;
	public static final String RECIPIENT_TYPE_SAPLING = "sapling";

	@Schema(description = "Send protocol version implemented by POST /crosschain/arrr/send", example = "1")
	public int sendProtocolVersion = SEND_PROTOCOL_VERSION;

	@Schema(description = "Fee policy; FIXED means every send pays exactly feeAtomic", example = "FIXED")
	public String feePolicy = FEE_POLICY_FIXED;

	@Schema(description = "Fee in atomic units as decimal text", example = "10000")
	public String feeAtomic;

	@Schema(description = "Number of decimal places in arrrAmount", example = "8")
	public int amountDecimals = AMOUNT_DECIMALS;

	@Schema(description = "Maximum memo size in UTF-8 encoded bytes", example = "512")
	public int maxMemoBytes = MAX_MEMO_BYTES;

	@Schema(description = "Recipient address types accepted by /send", example = "[\"sapling\"]")
	public List<String> recipientAddressTypes = List.of(RECIPIENT_TYPE_SAPLING);

	public PirateChainSendContract() {
	}

	public PirateChainSendContract(long feeAtomic) {
		this.feeAtomic = Long.toString(feeAtomic);
	}
}
