package org.qortal.crosschain;

import org.bitcoinj.core.Transaction;

final class RegisteredBitcoiny extends ConfiguredBitcoiny {

	private final BitcoinyChainSpec spec;

	RegisteredBitcoiny(BitcoinyChainSpec spec, BitcoinyNetwork network) {
		super(spec.getConfig(), network);
		this.spec = spec;
	}

	@Override
	public String normalizeAddress(String address) {
		return this.spec.normalizeAddress(address, this.getNetworkParameters());
	}

	@Override
	protected boolean hasSpendableOutputScriptFilter() {
		return this.spec.hasSpendableOutputScriptFilter();
	}

	@Override
	protected boolean isSpendableOutputScript(byte[] scriptPubKey) {
		return this.spec.isSpendableOutputScript(scriptPubKey);
	}

	@Override
	public Transaction buildSpend(String xprv58, String recipient, long amount) {
		Long defaultSpendFeePerByte = this.spec.getDefaultSpendFeePerByte();
		if (defaultSpendFeePerByte != null)
			return buildSpend(xprv58, recipient, amount, defaultSpendFeePerByte);

		return super.buildSpend(xprv58, recipient, amount);
	}
}
