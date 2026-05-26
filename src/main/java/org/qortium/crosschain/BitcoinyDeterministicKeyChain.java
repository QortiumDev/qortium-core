package org.qortium.crosschain;

import org.bitcoinj.core.NetworkParameters;

import java.util.ArrayList;
import java.util.List;

public final class BitcoinyDeterministicKeyChain {

	private static final int RECEIVE_FUNDS_CHAIN = 0;
	private static final int CHANGE_CHAIN = 1;

	private final BitcoinyDeterministicKey accountKey;

	private BitcoinyDeterministicKeyChain(BitcoinyDeterministicKey accountKey) {
		this.accountKey = accountKey;
	}

	public static BitcoinyDeterministicKeyChain fromBase58(NetworkParameters params, String key58) {
		return new BitcoinyDeterministicKeyChain(BitcoinyDeterministicKey.fromBase58(params, key58));
	}

	public List<BitcoinyDeterministicKey> getInitialLeafKeys(int lookaheadIncrement) {
		return getLeafKeys(0, lookaheadIncrement + 1);
	}

	public List<BitcoinyDeterministicKey> getMoreLeafKeys(int existingLeafKeyCount, int lookaheadIncrement) {
		return getLeafKeys(existingLeafKeyCount / 2, lookaheadIncrement);
	}

	public BitcoinyDeterministicKey getReceiveKey(int index) {
		return this.accountKey.derive(RECEIVE_FUNDS_CHAIN).derive(index);
	}

	private List<BitcoinyDeterministicKey> getLeafKeys(int startIndex, int count) {
		List<BitcoinyDeterministicKey> keys = new ArrayList<>(count * 2);

		BitcoinyDeterministicKey receiveChain = this.accountKey.derive(RECEIVE_FUNDS_CHAIN);
		for (int index = startIndex; index < startIndex + count; ++index)
			keys.add(receiveChain.derive(index));

		BitcoinyDeterministicKey changeChain = this.accountKey.derive(CHANGE_CHAIN);
		for (int index = startIndex; index < startIndex + count; ++index)
			keys.add(changeChain.derive(index));

		return keys;
	}
}
