package org.qortium.crosschain;

import org.bitcoinj.core.Context;
import org.qortium.utils.Amounts;

public class ConfiguredBitcoiny extends Bitcoiny {

	private final BitcoinyChainConfig config;
	private final BitcoinyNetwork network;

	protected ConfiguredBitcoiny(BitcoinyChainConfig config, BitcoinyNetwork network) {
		super(new ElectrumX(config.getDisplayName() + "-" + network.name(), network.getGenesisHash(),
						ElectrumServerList.getServers(config.getCurrencyCode(), network.name(), network.getServers()),
						config.getDefaultElectrumXPorts(), config.getDecimalPlaces()),
				new Context(network.getParams()),
				network.getParams(),
				config.getCurrencyCode(),
				config.getDefaultFeePerKb());

		this.config = config;
		this.network = network;
		this.blockchainProvider.setBlockchain(this);

		LOGGER.info(() -> String.format("Starting %s support using %s", this.config.getDisplayName(), this.network.name()));
	}

	protected BitcoinyChainConfig getConfig() {
		return this.config;
	}

	protected BitcoinyNetwork getNetwork() {
		return this.network;
	}

	@Override
	public String format(long amount) {
		return Amounts.prettyAmount(amount, this.config.getDecimalPlaces()) + " " + this.config.getCurrencyCode();
	}

	@Override
	public long getMinimumOrderAmount() {
		return this.config.getMinimumOrderAmount();
	}

	@Override
	public long getP2shFee(Long timestamp) throws ForeignBlockchainException {
		return this.network.getP2shFee(timestamp);
	}

	@Override
	public long getFeeRequired() {
		return this.network.getFeeRequired();
	}

	@Override
	public void setFeeRequired(long fee) {
		this.network.setFeeRequired(fee);
	}

}
