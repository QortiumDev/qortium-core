package org.qortium.test.crosschain;

import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.qortium.crosschain.Bitcoiny;

import java.util.function.Predicate;

class TestBitcoiny extends Bitcoiny {

	private long feeRequired = 1_000L;
	private final Predicate<byte[]> spendableOutputScriptFilter;

	TestBitcoiny(NetworkParameters params, MockBitcoinyBlockchainProvider blockchainProvider, String currencyCode) {
		this(params, blockchainProvider, currencyCode, null);
	}

	TestBitcoiny(NetworkParameters params, MockBitcoinyBlockchainProvider blockchainProvider, String currencyCode,
			Predicate<byte[]> spendableOutputScriptFilter) {
		super(blockchainProvider, new Context(params), params, currencyCode, Coin.valueOf(1_000L));
		this.spendableOutputScriptFilter = spendableOutputScriptFilter;
		blockchainProvider.setBlockchain(this);
	}

	@Override
	public long getP2shFee(Long timestamp) {
		return this.feeRequired;
	}

	@Override
	public long getFeeRequired() {
		return this.feeRequired;
	}

	@Override
	public void setFeeRequired(long fee) {
		this.feeRequired = fee;
	}

	@Override
	protected boolean hasSpendableOutputScriptFilter() {
		return this.spendableOutputScriptFilter != null;
	}

	@Override
	protected boolean isSpendableOutputScript(byte[] scriptPubKey) {
		return this.spendableOutputScriptFilter == null || this.spendableOutputScriptFilter.test(scriptPubKey);
	}

}
