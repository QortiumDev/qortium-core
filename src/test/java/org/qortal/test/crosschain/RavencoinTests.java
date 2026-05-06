package org.qortal.test.crosschain;

import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.SupportedBlockchain;

public class RavencoinTests extends BitcoinyTests {

	@Override
	protected String getCoinName() {
		return "Ravencoin";
	}

	@Override
	protected String getCoinSymbol() {
		return "RVN";
	}

	@Override
	protected Bitcoiny getCoin() {
		return SupportedBlockchain.RAVENCOIN.getBitcoinyInstance();
	}

	@Override
	protected void resetCoinForTesting() {
		SupportedBlockchain.RAVENCOIN.resetForTesting();
	}

	@Override
	protected String getDeterministicKey58() {
		return "xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg";
	}

	@Override
	protected String getDeterministicPublicKey58() {
		return "xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR";
	}

	@Override
	protected String getRecipient() {
		return null;
	}
}
