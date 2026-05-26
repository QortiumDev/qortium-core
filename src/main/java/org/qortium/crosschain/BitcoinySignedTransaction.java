package org.qortium.crosschain;

import com.google.common.hash.HashCode;
import org.bitcoinj.core.Transaction;
import org.qortium.crypto.Crypto;

import java.util.Arrays;

public final class BitcoinySignedTransaction {

	private final byte[] rawTransaction;
	private final String txHash;

	private BitcoinySignedTransaction(byte[] rawTransaction, String txHash) {
		this.rawTransaction = Arrays.copyOf(rawTransaction, rawTransaction.length);
		this.txHash = txHash;
	}

	public static BitcoinySignedTransaction fromBitcoinj(Transaction transaction) {
		return new BitcoinySignedTransaction(transaction.bitcoinSerialize(), transaction.getTxId().toString());
	}

	public static BitcoinySignedTransaction fromRaw(byte[] rawTransaction) {
		byte[] txHashBytes = Crypto.doubleDigest(rawTransaction);
		reverse(txHashBytes);
		return new BitcoinySignedTransaction(rawTransaction, HashCode.fromBytes(txHashBytes).toString());
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

	private static void reverse(byte[] bytes) {
		for (int left = 0, right = bytes.length - 1; left < right; ++left, --right) {
			byte tmp = bytes[left];
			bytes[left] = bytes[right];
			bytes[right] = tmp;
		}
	}
}
