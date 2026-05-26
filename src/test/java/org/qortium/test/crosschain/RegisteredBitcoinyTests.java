package org.qortium.test.crosschain;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.ForeignBlockchainRegistry;

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
				{ new ChainFixture(ForeignBlockchainRegistry.fromStringRequired("BITCOIN"), "Bitcoin", "BTC",
						"tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TYLg2aQMecZLXLre5bTVGajEB55L1HYJcawpdFG66STVAWPJ",
						"tpubDCxs3oB9X7XJYkQGU6gfPwd4h3NEiBGA8mfD1aEbZiG5x3BTH4cJqszDP6dtoHPPjZNEj5jPxuSWHCvjg9AHz4dNg6w5vQhv1B8KwWKpxoz",
						"2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE") },
				{ new ChainFixture(ForeignBlockchainRegistry.fromStringRequired("BITCOINCASH"), "Bitcoin Cash", "BCH",
						"xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg",
						"xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR",
						null) },
				{ new ChainFixture(ForeignBlockchainRegistry.fromStringRequired("LITECOIN"), "Litecoin", "LTC",
						"tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TYLg2aQMecZLXLre5bTVGajEB55L1HYJcawpdFG66STVAWPJ",
						"tpubDCxs3oB9X7XJYkQGU6gfPwd4h3NEiBGA8mfD1aEbZiG5x3BTH4cJqszDP6dtoHPPjZNEj5jPxuSWHCvjg9AHz4dNg6w5vQhv1B8KwWKpxoz",
						"2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE") },
				{ new ChainFixture(ForeignBlockchainRegistry.fromStringRequired("DOGECOIN"), "Dogecoin", "DOGE",
						"dgpv51eADS3spNJh9drNeW1Tc1P9z2LyaQRXPBortsq6yice1k47C2u2Prvgxycr2ihNBWzKZ2LthcBBGiYkWZ69KUTVkcLVbnjq7pD8mnApEru",
						"dgub8rqf3khHiPeYE3cNn3Y4DQQ411nAnFpuSUPt5k5GJZQsydsTLkaf4onaWn4N8pHvrV3oNMEATKoPGTFZwm2Uhh7Dy9gYwA7rkSv6oLofbag",
						null) },
				{ new ChainFixture(ForeignBlockchainRegistry.fromStringRequired("DIGIBYTE"), "Digibyte", "DGB",
						"xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg",
						"xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR",
						null) },
				{ new ChainFixture(ForeignBlockchainRegistry.fromStringRequired("RAVENCOIN"), "Ravencoin", "RVN",
						"xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg",
						"xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR",
						null) },
				{ new ChainFixture(ForeignBlockchainRegistry.fromStringRequired("DASH"), "Dash", "DASH",
						"xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg",
						"xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR",
						null) },
				{ new ChainFixture(ForeignBlockchainRegistry.fromStringRequired("PEERCOIN"), "Peercoin", "PPC",
						"xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg",
						"xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR",
						null) },
				{ new ChainFixture(ForeignBlockchainRegistry.fromStringRequired("NAMECOIN"), "Namecoin", "NMC",
						"xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg",
						"xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR",
						null) },
				{ new ChainFixture(ForeignBlockchainRegistry.fromStringRequired("FIRO"), "Firo", "FIRO",
						"xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg",
						"xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR",
						null) },
				{ new ChainFixture(ForeignBlockchainRegistry.fromStringRequired("KOMODO"), "Komodo", "KMD",
						"xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg",
						"xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR",
						null) },
				{ new ChainFixture(ForeignBlockchainRegistry.fromStringRequired("VERUSCOIN"), "VerusCoin", "VRSC",
						"xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg",
						"xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR",
						null) },
				{ new ChainFixture(ForeignBlockchainRegistry.fromStringRequired("ZCASH"), "Zcash", "ZEC",
						"xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg",
						"xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR",
						null) },
				{ new ChainFixture(ForeignBlockchainRegistry.fromStringRequired("LBRYCREDITS"), "LBRY Credits", "LBC",
						"xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg",
						"xpub6D7mDwepoJuVF9HFN3LZyEYo8HFq6LZHcM2aKEhnsaXLQqnCWyX3ftGcLDcjYmiPCc9GNX4VjfT32hwvYQnh9H5Z5diAvMsXRrxFmckyNoR",
						null) },
				{ new ChainFixture(ForeignBlockchainRegistry.fromStringRequired("VERGE"), "Verge", "XVG",
						"TDt9Ee2cL2o71x13ZrmAGDPnxA2eey8jra3hXYNoww7zCKqSb6qi9Q78g4szyLsTpf8GEUAVWpxJcMDnatsxrXaLB9E5kdvviG9rtEZ3kz4vs8a",
						"ToEA6mwKngS8q39wQLXVVNSLoKFpVtRJi8TvSsTCydsqrAZQhRrMrHvjcFZd2YbVvUNf6oStUiNnmKhCyd3Bmsy9CCp6YJy92kYZmij3jMjPrGo",
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
		return this.fixture.foreignBlockchain.getBitcoinyInstance();
	}

	@Override
	protected void resetCoinForTesting() {
		this.fixture.foreignBlockchain.resetForTesting();
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

	@Override
	protected boolean supportsSpendTransactionTests() {
		return !"BITCOINCASH".equals(this.fixture.foreignBlockchain.name())
				&& !"ZCASH".equals(this.fixture.foreignBlockchain.name());
	}

	private static class ChainFixture {
		private final ForeignBlockchainRegistry.Entry foreignBlockchain;
		private final String coinName;
		private final String coinSymbol;
		private final String deterministicKey58;
		private final String deterministicPublicKey58;
		private final String recipient;

		private ChainFixture(ForeignBlockchainRegistry.Entry foreignBlockchain, String coinName, String coinSymbol,
				String deterministicKey58, String deterministicPublicKey58, String recipient) {
			this.foreignBlockchain = foreignBlockchain;
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
