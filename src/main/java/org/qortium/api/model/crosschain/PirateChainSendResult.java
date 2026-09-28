package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/**
 * JSON result of a successful {@code POST /crosschain/arrr/send} (send protocol version 1: the
 * native send ran synchronously and returned a transaction id, meaning the transaction was
 * handed to the network, not that it is confirmed).
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class PirateChainSendResult {

	@Schema(description = "Transaction id returned by the native wallet after broadcast")
	public String txid;

	@Schema(description = "Fee that was paid, in atomic units, as decimal text", example = "10000")
	public String feeAtomic;

	@Schema(description = "Fee policy applied", example = "FIXED")
	public String feePolicy;

	@Schema(description = "Send protocol version that produced this result", example = "1")
	public int sendProtocolVersion;

	public PirateChainSendResult() {
	}

	public PirateChainSendResult(String txid, String feeAtomic, String feePolicy, int sendProtocolVersion) {
		this.txid = txid;
		this.feeAtomic = feeAtomic;
		this.feePolicy = feePolicy;
		this.sendProtocolVersion = sendProtocolVersion;
	}
}
