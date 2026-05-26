package org.qortium.crosschain;

import org.bitcoinj.core.NetworkParameters;

import java.util.Collection;

public interface BitcoinyNetwork {

	String name();

	NetworkParameters getParams();

	Collection<ElectrumX.Server> getServers();

	String getGenesisHash();

	String getChainId();

	long getP2shFee(Long timestamp) throws ForeignBlockchainException;

	long getFeeRequired();

	void setFeeRequired(long feeRequired);

}
