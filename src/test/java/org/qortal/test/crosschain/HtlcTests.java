package org.qortal.test.crosschain;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Longs;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.BitcoinyHTLC;
import org.qortal.crosschain.BitcoinyTransaction;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.SupportedBlockchain;
import org.qortal.crosschain.TransactionHash;
import org.qortal.crypto.Crypto;
import org.qortal.repository.DataException;
import org.qortal.test.common.Common;

import java.util.Collections;

import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.*;

public class HtlcTests extends Common {

	private static final String RUN_LIVE_CROSSCHAIN_TESTS_PROPERTY = "qortium.runLiveCrosschainTests";
	private static final byte[] EXPECTED_SECRET = "This string is exactly 32 bytes!".getBytes();
	private static final int LOCK_TIME = 1_700_000_000;

	private Bitcoiny bitcoin;
	private Bitcoiny litecoin;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings(); // TestNet3
		bitcoin = SupportedBlockchain.BITCOIN.getBitcoinyInstance();
		litecoin = SupportedBlockchain.LITECOIN.getBitcoinyInstance();
	}

	@After
	public void afterTest() {
		SupportedBlockchain.BITCOIN.resetForTesting();
		SupportedBlockchain.LITECOIN.resetForTesting();
		bitcoin = null;
		litecoin = null;
	}

	@Test
	public void testFindHtlcSecret() throws ForeignBlockchainException {
		HtlcFixture fixture = createHtlcFixture(true);

		byte[] secret = BitcoinyHTLC.findHtlcSecret(fixture.bitcoiny, fixture.p2shAddress);

		assertNotNull(secret);
		assertArrayEquals("secret incorrect", EXPECTED_SECRET, secret);
	}

	@Test
	public void testFindHtlcSecretFromLiveProvider() throws ForeignBlockchainException {
		assumeLiveCrosschainTestsEnabled();

		// This actually exists on TEST3 but can take a while to fetch
		String p2shAddress = "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE";

		byte[] secret = BitcoinyHTLC.findHtlcSecret(bitcoin, p2shAddress);

		assertNotNull(secret);
		assertArrayEquals("secret incorrect", EXPECTED_SECRET, secret);
	}

	@Test
	public void testHtlcSecretCaching() throws ForeignBlockchainException {
		HtlcFixture fixture = createHtlcFixture(true);

		byte[] secret1 = BitcoinyHTLC.findHtlcSecret(fixture.bitcoiny, fixture.p2shAddress);
		byte[] secret2 = BitcoinyHTLC.findHtlcSecret(fixture.bitcoiny, fixture.p2shAddress);

		assertArrayEquals(EXPECTED_SECRET, secret1);
		assertArrayEquals(secret1, secret2);
	}

	@Test
	public void testDetermineHtlcStatus() throws ForeignBlockchainException {
		HtlcFixture fixture = createHtlcFixture(false);

		BitcoinyHTLC.Status htlcStatus = BitcoinyHTLC.determineHtlcStatus(fixture.bitcoiny.getBlockchainProvider(), fixture.p2shAddress, 1L);

		assertEquals(BitcoinyHTLC.Status.FUNDED, htlcStatus);
	}

	@Test
	public void testDetermineHtlcStatusFromLiveProvider() throws ForeignBlockchainException {
		assumeLiveCrosschainTestsEnabled();

		// This actually exists on TEST3 but can take a while to fetch
		String p2shAddress = "2N8WCg52ULCtDSMjkgVTm5mtPdCsUptkHWE";

		BitcoinyHTLC.Status htlcStatus = BitcoinyHTLC.determineHtlcStatus(litecoin.getBlockchainProvider(), p2shAddress, 1L);
		assertNotNull(htlcStatus);

		System.out.println(String.format("HTLC %s status: %s", p2shAddress, htlcStatus.name()));
	}

	@Test
	public void testHtlcStatusCaching() throws ForeignBlockchainException {
		MockBitcoinyBlockchainProvider blockchainProvider = new MockBitcoinyBlockchainProvider("Bitcoin-mock-empty");
		TestBitcoiny mockBitcoiny = new TestBitcoiny(bitcoin.getNetworkParameters(), blockchainProvider, "BTC");
		String p2shAddress = mockBitcoiny.deriveP2shAddress(Crypto.hash160(Longs.toByteArray(12345L)));

		BitcoinyHTLC.Status htlcStatus1 = BitcoinyHTLC.determineHtlcStatus(mockBitcoiny.getBlockchainProvider(), p2shAddress, 1L);
		BitcoinyHTLC.Status htlcStatus2 = BitcoinyHTLC.determineHtlcStatus(mockBitcoiny.getBlockchainProvider(), p2shAddress, 1L);

		assertEquals(BitcoinyHTLC.Status.UNFUNDED, htlcStatus1);
		assertEquals(htlcStatus1, htlcStatus2);
	}

	private HtlcFixture createHtlcFixture(boolean includeRedeemTransaction) {
		NetworkParameters params = bitcoin.getNetworkParameters();
		MockBitcoinyBlockchainProvider blockchainProvider = new MockBitcoinyBlockchainProvider("Bitcoin-mock-htlc");
		TestBitcoiny mockBitcoiny = new TestBitcoiny(params, blockchainProvider, "BTC");
		ECKey refundKey = ECKey.fromPrivate(HashCode.fromString("11".repeat(32)).asBytes());
		ECKey redeemKey = ECKey.fromPrivate(HashCode.fromString("22".repeat(32)).asBytes());
		byte[] redeemScriptBytes = BitcoinyHTLC.buildScript(refundKey.getPubKeyHash(), LOCK_TIME, redeemKey.getPubKeyHash(), Crypto.hash160(EXPECTED_SECRET));
		String p2shAddress = mockBitcoiny.deriveP2shAddress(redeemScriptBytes);
		byte[] p2shScriptPubKey = ScriptBuilder.createOutputScript(Address.fromString(params, p2shAddress)).getProgram();
		Transaction fundingTransaction = new Transaction(params);
		fundingTransaction.addOutput(Coin.valueOf(20_000L), Address.fromString(params, p2shAddress));
		String fundingTxHash = fundingTransaction.getTxId().toString();

		if (includeRedeemTransaction) {
			TransactionOutput fundingOutput = fundingTransaction.getOutput(0);
			Transaction redeemTransaction = BitcoinyHTLC.buildRedeemTransaction(params, Coin.valueOf(19_000L), redeemKey,
					Collections.singletonList(fundingOutput), redeemScriptBytes, EXPECTED_SECRET, redeemKey.getPubKeyHash());
			String redeemTxHash = redeemTransaction.getTxId().toString();
			blockchainProvider.addAddressTransaction(p2shScriptPubKey, new TransactionHash(11, redeemTxHash));
			blockchainProvider.addRawTransaction(redeemTxHash, redeemTransaction.bitcoinSerialize());
		} else {
			blockchainProvider.addAddressTransaction(p2shScriptPubKey, new TransactionHash(10, fundingTxHash));
			blockchainProvider.addTransaction(new BitcoinyTransaction(fundingTxHash, fundingTransaction.bitcoinSerialize().length, (int) fundingTransaction.getLockTime(),
					1_700_000_000, Collections.emptyList(), Collections.singletonList(
							new BitcoinyTransaction.Output(HashCode.fromBytes(p2shScriptPubKey).toString(), 20_000L))));
		}

		return new HtlcFixture(mockBitcoiny, p2shAddress);
	}

	private void assumeLiveCrosschainTestsEnabled() {
		assumeTrue(Boolean.getBoolean(RUN_LIVE_CROSSCHAIN_TESTS_PROPERTY));
	}

	private static class HtlcFixture {
		private final Bitcoiny bitcoiny;
		private final String p2shAddress;

		private HtlcFixture(Bitcoiny bitcoiny, String p2shAddress) {
			this.bitcoiny = bitcoiny;
			this.p2shAddress = p2shAddress;
		}
	}

}
