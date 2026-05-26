package org.qortium.test.crosschain;

import org.bitcoinj.core.NetworkParameters;
import org.junit.Test;
import org.qortium.crosschain.BitcoinyAddress;
import org.qortium.crosschain.BitcoinyChainSpecs;
import org.qortium.crosschain.BitcoinyDeterministicKey;
import org.qortium.crosschain.BitcoinyDeterministicKeyChain;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class BitcoinyDeterministicKeyTests {

	private static final String BITCOIN_TEST3_XPRV = "tprv8ZgxMBicQKsPdahhFSrCdvC1bsWyzHHZfTneTVqUXN6s1wEtZLwAkZXzFP6TYLg2aQMecZLXLre5bTVGajEB55L1HYJcawpdFG66STVAWPJ";
	private static final String BITCOIN_TEST3_XPUB = "tpubDCxs3oB9X7XJYkQGU6gfPwd4h3NEiBGA8mfD1aEbZiG5x3BTH4cJqszDP6dtoHPPjZNEj5jPxuSWHCvjg9AHz4dNg6w5vQhv1B8KwWKpxoz";
	private static final String DOGECOIN_MAIN_XPRV = "dgpv51eADS3spNJh9drNeW1Tc1P9z2LyaQRXPBortsq6yice1k47C2u2Prvgxycr2ihNBWzKZ2LthcBBGiYkWZ69KUTVkcLVbnjq7pD8mnApEru";

	@Test
	public void testBitcoinTest3PrivateKeyDerivesExpectedScanAddresses() {
		NetworkParameters params = bitcoinTest3Params();
		List<BitcoinyDeterministicKey> keys = BitcoinyDeterministicKeyChain.fromBase58(params, BITCOIN_TEST3_XPRV).getInitialLeafKeys(3);

		assertEquals("M/0/0", keys.get(0).getPathAsString());
		assertEquals(Arrays.asList(
				"mqL6kknyP11MyaC4C9okxL32BruPxbmKYQ",
				"moJtbbhs7T4Z5hmBH2iyKhGrCWBzQWS2CL",
				"mo8Jt92LWJ6vBPwgAwsPikeDeVQmtc92ow",
				"moLmZwsBQ3v5Z2sfC7dyw8RV7L6aaMoNax",
				"mtX7kLCJSyHCq6SemQQZpow6wyHft3XTnt",
				"mpXc8MuaxsjMes4iks6ymjae6smamouQap",
				"n1TifUaz6kWgutGDrba2WhKvgnXXwvJYah",
				"n1NMp1xaQbDwu6E5RT4VmnA3p5ittHYUw7"
		), toAddresses(params, keys));
	}

	@Test
	public void testBitcoinTest3PublicKeyDerivesExpectedScanAddresses() {
		NetworkParameters params = bitcoinTest3Params();
		List<BitcoinyDeterministicKey> keys = BitcoinyDeterministicKeyChain.fromBase58(params, BITCOIN_TEST3_XPUB).getInitialLeafKeys(3);

		assertEquals("M/0H/0/0", keys.get(0).getPathAsString());
		assertEquals(Arrays.asList(
				"mj9oa2DRiSYvoULYe6t6EaDshxD7Xqm1Vc",
				"mvYvU5KwLDVm9DFNbgGyxDva8udd8TsKPH",
				"mvay159JGpJwvjhqD1LAtug1t9T7VJpcqk",
				"n3y2fn3U2gp1k7NgpKxCKC4A2nU7rKnG3N",
				"mgyPGjcEULNsddcdfp7hMasVhYhmRsYgXF",
				"mpUMwwuuBrr3Zr3RNkhF4jMzdSHtFS9Y5Y",
				"myXgRj4qb6rVSY96htD1vH19mGha3uqGzd",
				"n1fqaSpmoLEZuta8vxpdGu9dRFGFoSbhB1"
		), toAddresses(params, keys));
	}

	@Test
	public void testDogecoinPrivateHeaderIsAccepted() {
		NetworkParameters params = BitcoinyChainSpecs.DOGECOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		List<BitcoinyDeterministicKey> keys = BitcoinyDeterministicKeyChain.fromBase58(params, DOGECOIN_MAIN_XPRV).getInitialLeafKeys(3);

		assertEquals("DP1iFao33xdEPa5vaArpj7sykfzKNeiJeX", toAddresses(params, keys).get(0));
	}

	@Test
	public void testExtendedKeyRejectsInvalidChecksum() {
		NetworkParameters params = bitcoinTest3Params();
		String invalidKey = BITCOIN_TEST3_XPRV.substring(0, BITCOIN_TEST3_XPRV.length() - 1)
				+ (BITCOIN_TEST3_XPRV.endsWith("1") ? "2" : "1");

		try {
			BitcoinyDeterministicKey.fromBase58(params, invalidKey);
			fail("Expected invalid checksum to be rejected");
		} catch (IllegalArgumentException e) {
			// Expected
		}
	}

	private static List<String> toAddresses(NetworkParameters params, List<BitcoinyDeterministicKey> keys) {
		return keys.stream()
				.map(key -> BitcoinyAddress.fromPubKeyHash(params, key.getPublicKeyHash()).toString())
				.collect(Collectors.toList());
	}

	private static NetworkParameters bitcoinTest3Params() {
		return BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.TEST3).getParams();
	}
}
