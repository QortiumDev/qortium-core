package org.qortal.test.crosschain;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.qortal.crosschain.Bitcoiny;

class TestBitcoiny extends Bitcoiny {

	private long feeRequired = 1_000L;

	TestBitcoiny(NetworkParameters params, MockBitcoinyBlockchainProvider blockchainProvider, String currencyCode) {
		super(blockchainProvider, new Context(params), currencyCode, Coin.valueOf(1_000L));
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

}
