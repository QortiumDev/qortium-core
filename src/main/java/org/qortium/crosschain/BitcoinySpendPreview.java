package org.qortium.crosschain;

import java.util.Arrays;

public class BitcoinySpendPreview {

	private final long amount;
	private final boolean sendMax;
	private final long feePerByte;
	private final long fee;
	private final long inputAmount;
	private final long outputAmount;
	private final int transactionSize;
	private final int inputCount;
	private final int outputCount;
	private final String txHash;
	private final byte[] rawTransaction;

	public BitcoinySpendPreview(long amount, long feePerByte, long fee, long inputAmount, long outputAmount,
			int transactionSize, int inputCount, int outputCount, String txHash, byte[] rawTransaction) {
		this(amount, false, feePerByte, fee, inputAmount, outputAmount, transactionSize, inputCount, outputCount, txHash,
				rawTransaction);
	}

	public BitcoinySpendPreview(long amount, boolean sendMax, long feePerByte, long fee, long inputAmount, long outputAmount,
			int transactionSize, int inputCount, int outputCount, String txHash, byte[] rawTransaction) {
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
		this.rawTransaction = Arrays.copyOf(rawTransaction, rawTransaction.length);
	}

	public long getAmount() {
		return this.amount;
	}

	public boolean isSendMax() {
		return this.sendMax;
	}

	public long getFeePerByte() {
		return this.feePerByte;
	}

	public long getFee() {
		return this.fee;
	}

	public long getInputAmount() {
		return this.inputAmount;
	}

	public long getOutputAmount() {
		return this.outputAmount;
	}

	public int getTransactionSize() {
		return this.transactionSize;
	}

	public int getInputCount() {
		return this.inputCount;
	}

	public int getOutputCount() {
		return this.outputCount;
	}

	public String getTxHash() {
		return this.txHash;
	}

	public byte[] getRawTransaction() {
		return Arrays.copyOf(this.rawTransaction, this.rawTransaction.length);
	}
}
