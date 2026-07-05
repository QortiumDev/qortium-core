package org.qortium.crosschain;

import com.google.common.hash.HashCode;
import org.bitcoinj.core.Transaction;
import org.qortium.crypto.Crypto;

import java.util.Arrays;

public final class BitcoinySignedTransaction {

	private final byte[] rawTransaction;
	private final String txHash;
	private final Long inputAmount;
	private final Long outputAmount;
	private final Long feeAmount;
	private final Integer inputCount;
	private final Integer outputCount;

	private BitcoinySignedTransaction(byte[] rawTransaction, String txHash) {
		this(rawTransaction, txHash, null, null, null, null, null);
	}

	private BitcoinySignedTransaction(byte[] rawTransaction, String txHash, Long inputAmount, Long outputAmount,
			Long feeAmount, Integer inputCount, Integer outputCount) {
		this.rawTransaction = Arrays.copyOf(rawTransaction, rawTransaction.length);
		this.txHash = txHash;
		this.inputAmount = inputAmount;
		this.outputAmount = outputAmount;
		this.feeAmount = feeAmount;
		this.inputCount = inputCount;
		this.outputCount = outputCount;
	}

	public static BitcoinySignedTransaction fromBitcoinj(Transaction transaction) {
		return new BitcoinySignedTransaction(transaction.bitcoinSerialize(), transaction.getTxId().toString());
	}

	public static BitcoinySignedTransaction fromRaw(byte[] rawTransaction) {
		byte[] txHashBytes = Crypto.doubleDigest(rawTransaction);
		reverse(txHashBytes);
		return new BitcoinySignedTransaction(rawTransaction, HashCode.fromBytes(txHashBytes).toString());
	}

	public static BitcoinySignedTransaction fromRaw(byte[] rawTransaction, long inputAmount, long outputAmount, long feeAmount,
			int inputCount, int outputCount) {
		byte[] txHashBytes = Crypto.doubleDigest(rawTransaction);
		reverse(txHashBytes);
		return new BitcoinySignedTransaction(rawTransaction, HashCode.fromBytes(txHashBytes).toString(), inputAmount, outputAmount,
				feeAmount, inputCount, outputCount);
	}

	public static BitcoinySignedTransaction fromRawWithTxHash(byte[] rawTransaction, String txHash) {
		return new BitcoinySignedTransaction(rawTransaction, txHash);
	}

	public byte[] getRawTransaction() {
		return Arrays.copyOf(this.rawTransaction, this.rawTransaction.length);
	}

	public String getTxHash() {
		return this.txHash;
	}

	public Long getInputAmount() {
		return this.inputAmount;
	}

	public Long getOutputAmount() {
		return this.outputAmount;
	}

	public Long getFeeAmount() {
		return this.feeAmount;
	}

	public Integer getInputCount() {
		return this.inputCount;
	}

	public Integer getOutputCount() {
		return this.outputCount;
	}

	private static void reverse(byte[] bytes) {
		for (int left = 0, right = bytes.length - 1; left < right; ++left, --right) {
			byte tmp = bytes[left];
			bytes[left] = bytes[right];
			bytes[right] = tmp;
		}
	}
}
