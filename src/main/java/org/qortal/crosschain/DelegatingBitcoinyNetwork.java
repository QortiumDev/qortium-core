package org.qortal.crosschain;

import org.bitcoinj.core.NetworkParameters;

import java.util.Collection;

public interface DelegatingBitcoinyNetwork extends BitcoinyNetwork {

	BitcoinyNetwork getDelegate();

	@Override
	default NetworkParameters getParams() {
		return this.getDelegate().getParams();
	}

	@Override
	default Collection<ElectrumX.Server> getServers() {
		return this.getDelegate().getServers();
	}

	@Override
	default String getGenesisHash() {
		return this.getDelegate().getGenesisHash();
	}

	@Override
	default long getP2shFee(Long timestamp) throws ForeignBlockchainException {
		return this.getDelegate().getP2shFee(timestamp);
	}

	@Override
	default long getFeeRequired() {
		return this.getDelegate().getFeeRequired();
	}

	@Override
	default void setFeeRequired(long feeRequired) {
		this.getDelegate().setFeeRequired(feeRequired);
	}

}
