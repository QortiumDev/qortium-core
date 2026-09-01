package org.qortium.crosschain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Confirmed watch-only wallet output with enough public data for independent client attestation. */
public class WalletSpendContextUtxo {

	private final String address;
	private final int height;
	private final List<Integer> path;
	private final String pathAsString;
	private final byte[] scriptPubKey;
	private final byte[] transactionHash;
	private final int outputIndex;
	private final long value;

	public WalletSpendContextUtxo(String address, int height, List<Integer> path, String pathAsString,
			byte[] scriptPubKey, byte[] transactionHash, int outputIndex, long value) {
		this.address = address;
		this.height = height;
		this.path = Collections.unmodifiableList(new ArrayList<>(path));
		this.pathAsString = pathAsString;
		this.scriptPubKey = scriptPubKey.clone();
		this.transactionHash = transactionHash.clone();
		this.outputIndex = outputIndex;
		this.value = value;
	}

	public String getAddress() {
		return this.address;
	}

	public int getHeight() {
		return this.height;
	}

	public List<Integer> getPath() {
		return this.path;
	}

	public String getPathAsString() {
		return this.pathAsString;
	}

	public byte[] getScriptPubKey() {
		return this.scriptPubKey.clone();
	}

	public byte[] getTransactionHash() {
		return this.transactionHash.clone();
	}

	public int getOutputIndex() {
		return this.outputIndex;
	}

	public long getValue() {
		return this.value;
	}
}
