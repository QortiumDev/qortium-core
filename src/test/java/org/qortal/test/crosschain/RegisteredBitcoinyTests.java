package org.qortal.test.crosschain;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.SupportedBlockchain;

import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class RegisteredBitcoinyTests extends BitcoinyTests {

	private final ChainFixture fixture;

	public RegisteredBitcoinyTests(ChainFixture fixture) {
		this.fixture = fixture;
	}

	@Parameterized.Parameters
	public static Collection<Object[]> chainFixtures() {
		return Arrays.asList(new Object[][] {
				{ new ChainFixture(SupportedBlockchain.BITCOIN, "Bitcoin", "BTC",
						"tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TYLg2aQMecZLXLre5bTVGajEB55L1HYJcawpdFG66STVAWPJ",
						"tpubDCxs3oB9X7XJYkQGU6gfPwd4h3NEiBGA8mfD1aEbZiG5x3BTH4cJqszDP6dtoHPPjZNEj5jPxuSWHCvjg9AHz4dNg6w5vQhv1B8KwWKpxoz",
						"2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE") },
				{ new ChainFixture(SupportedBlockchain.LITECOIN, "Litecoin", "LTC",
						"tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TYLg2aQMecZLXLre5bTVGajEB55L1HYJcawpdFG66STVAWPJ",
						"tpubDCxs3oB9X7XJYkQGU6gfPwd4h3NEiBGA8mfD1aEbZiG5x3BTH4cJqszDP6dtoHPPjZNEj5jPxuSWHCvjg9AHz4dNg6w5vQhv1B8KwWKpxoz",
						"2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE") },
				{ new ChainFixture(SupportedBlockchain.DOGECOIN, "Dogecoin", "DOGE",
						"dgpv51eADS3spNJh9drNeW1Tc1P9z2LyaQRXPBortsq6yice1k47C2u2Prvgxycr2ihNBWzKZ2LthcBBGiYkWZ69KUTVkcLVbnjq7pD8mnApEru",
						"dgub8rqf3khHiPeYE3cNn3Y4DQQ411nAnFpuSUPt5k5GJZQsydsTLkaf4onaWn4N8pHvrV3oNMEATKoPGTFZwm2Uhh7Dy9gYwA7rkSv6oLofbag",
						null) },
				{ new ChainFixture(SupportedBlockchain.DIGIBYTE, "Digibyte", "DGB",
						"xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg",
						"xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR",
						null) },
				{ new ChainFixture(SupportedBlockchain.RAVENCOIN, "Ravencoin", "RVN",
						"xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg",
						"xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR",
						null) }
		});
	}

	@Override
	protected String getCoinName() {
		return this.fixture.coinName;
	}

	@Override
	protected String getCoinSymbol() {
		return this.fixture.coinSymbol;
	}

	@Override
	protected Bitcoiny getCoin() {
		return this.fixture.blockchain.getBitcoinyInstance();
	}

	@Override
	protected void resetCoinForTesting() {
		this.fixture.blockchain.resetForTesting();
	}

	@Override
	protected String getDeterministicKey58() {
		return this.fixture.deterministicKey58;
	}

	@Override
	protected String getDeterministicPublicKey58() {
		return this.fixture.deterministicPublicKey58;
	}

	@Override
	protected String getRecipient() {
		return this.fixture.recipient;
	}

	private static class ChainFixture {
		private final SupportedBlockchain blockchain;
		private final String coinName;
		private final String coinSymbol;
		private final String deterministicKey58;
		private final String deterministicPublicKey58;
		private final String recipient;

		private ChainFixture(SupportedBlockchain blockchain, String coinName, String coinSymbol,
				String deterministicKey58, String deterministicPublicKey58, String recipient) {
			this.blockchain = blockchain;
			this.coinName = coinName;
			this.coinSymbol = coinSymbol;
			this.deterministicKey58 = deterministicKey58;
			this.deterministicPublicKey58 = deterministicPublicKey58;
			this.recipient = recipient;
		}

		@Override
		public String toString() {
			return this.coinSymbol;
		}
	}
}
