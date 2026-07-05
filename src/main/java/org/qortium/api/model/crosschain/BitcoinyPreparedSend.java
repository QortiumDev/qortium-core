package org.qortium.api.model.crosschain;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class BitcoinyPreparedSend {

	@Schema(description = "Canonical blockchain registry name", example = "BITCOIN")
	public String blockchain;

	@Schema(description = "Currency code used by this blockchain", example = "BTC")
	public String currencyCode;

	@Schema(description = "Configured active foreign-chain network", example = "MAIN")
	public String activeNetwork;

	@Schema(description = "Normalized recipient address", example = "bc1q...")
	public String receivingAddress;

	@Schema(description = "Amount to send, in atomic units", type = "number")
	public long amount;

	@Schema(description = "Whether this transaction spends the maximum wallet balance after subtracting fees")
	public boolean sendMax;

	@Schema(description = "Fee rate used to build the transaction, in atomic units per byte", type = "number")
	public long feePerByte;

	@Schema(description = "Computed transaction fee, in atomic units", type = "number")
	public long fee;

	@Schema(description = "Total selected input amount, in atomic units", type = "number")
	public long inputAmount;

	@Schema(description = "Total transaction output amount, including change, in atomic units", type = "number")
	public long outputAmount;

	@Schema(description = "Serialized transaction size in bytes", type = "number")
	public int transactionSize;

	@Schema(description = "Number of transaction inputs", type = "number")
	public int inputCount;

	@Schema(description = "Number of transaction outputs", type = "number")
	public int outputCount;

	@Schema(description = "Prepared transaction hash")
	public String txHash;

	@Schema(description = "Prepared raw transaction hex")
	public String rawTransactionHex;

	public BitcoinyPreparedSend() {
		// For JAXB
	}

	public BitcoinyPreparedSend(String blockchain, String currencyCode, String activeNetwork, String receivingAddress,
			long amount, long feePerByte, long fee, long inputAmount, long outputAmount, int transactionSize,
			int inputCount, int outputCount, String txHash, String rawTransactionHex) {
		this(blockchain, currencyCode, activeNetwork, receivingAddress, amount, false, feePerByte, fee, inputAmount,
				outputAmount, transactionSize, inputCount, outputCount, txHash, rawTransactionHex);
	}

	public BitcoinyPreparedSend(String blockchain, String currencyCode, String activeNetwork, String receivingAddress,
			long amount, boolean sendMax, long feePerByte, long fee, long inputAmount, long outputAmount, int transactionSize,
			int inputCount, int outputCount, String txHash, String rawTransactionHex) {
		this.blockchain = blockchain;
		this.currencyCode = currencyCode;
		this.activeNetwork = activeNetwork;
		this.receivingAddress = receivingAddress;
		this.amount = amount;
		this.sendMax = sendMax;
		this.feePerByte = feePerByte;
		this.fee = fee;
		this.inputAmount = inputAmount;
		this.outputAmount = outputAmount;
		this.transactionSize = transactionSize;
		this.inputCount = inputCount;
		this.outputCount = outputCount;
		this.txHash = txHash;
		this.rawTransactionHex = rawTransactionHex;
	}
}
