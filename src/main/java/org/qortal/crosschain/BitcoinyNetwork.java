package org.qortal.crosschain;

import org.bitcoinj.core.NetworkParameters;

import java.util.Collection;

public interface BitcoinyNetwork {

	String name();

	NetworkParameters getParams();

	Collection<ElectrumX.Server> getServers();

	String getGenesisHash();

	long getP2shFee(Long timestamp) throws ForeignBlockchainException;

	long getFeeRequired();

	void setFeeRequired(long feeRequired);

}
