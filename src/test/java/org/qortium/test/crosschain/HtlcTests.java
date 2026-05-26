package org.qortium.test.crosschain;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinyHTLC;
import org.qortium.crosschain.BitcoinyScript;
import org.qortium.crosschain.BitcoinyTransaction;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.TransactionHash;
import org.qortium.crosschain.UnspentOutput;
import org.qortium.crypto.Crypto;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import java.util.Collections;

import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.*;

public class HtlcTests extends Common {

	private static final String RUN_LIVE_CROSSCHAIN_TESTS_PROPERTY = "qortium.runLiveCrosschainTests";
	private static final byte[] EXPECTED_SECRET = "This string is exactly 32 bytes!".getBytes();
	private static final int LOCK_TIME = 1_700_000_000;

	private Bitcoiny bitcoin;
	private Bitcoiny litecoin;
	private ForeignBlockchainRegistry.Entry bitcoinEntry;
	private ForeignBlockchainRegistry.Entry litecoinEntry;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings(); // TestNet3
		bitcoinEntry = ForeignBlockchainRegistry.fromStringRequired("BITCOIN");
		litecoinEntry = ForeignBlockchainRegistry.fromStringRequired("LITECOIN");
		bitcoin = bitcoinEntry.getBitcoinyInstance();
		litecoin = litecoinEntry.getBitcoinyInstance();
	}

	@After
	public void afterTest() {
		bitcoinEntry.resetForTesting();
		litecoinEntry.resetForTesting();
		bitcoinEntry = null;
		litecoinEntry = null;
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
	public void testFindHtlcSecretIgnoresRefundTransaction() throws ForeignBlockchainException {
		HtlcFixture fixture = createHtlcFixture("Bitcoin-mock-refund", false);
		Transaction refundTransaction = BitcoinyHTLC.buildRefundTransaction(fixture.bitcoiny.getNetworkParameters(),
				Coin.valueOf(19_000L), fixture.refundKey, Collections.singletonList(fixture.fundingOutput),
				fixture.redeemScriptBytes, LOCK_TIME, fixture.refundKey.getPubKeyHash());
		addSpendTransaction(fixture, refundTransaction, 11);

		assertNull(BitcoinyHTLC.findHtlcSecret(fixture.bitcoiny, fixture.p2shAddress));
	}

	@Test
	public void testFindHtlcSecretIgnoresWrongRedeemScript() throws ForeignBlockchainException {
		HtlcFixture fixture = createHtlcFixture("Bitcoin-mock-wrong-redeem-script", false);
		byte[] wrongRedeemScript = BitcoinyHTLC.buildScript(fixture.refundKey.getPubKeyHash(), LOCK_TIME + 1,
				fixture.redeemKey.getPubKeyHash(), Crypto.hash160(EXPECTED_SECRET));
		byte[] scriptSig = buildRedeemScriptSig(EXPECTED_SECRET, fixture.redeemKey, wrongRedeemScript);
		addSpendTransaction(fixture, "22".repeat(32), scriptSig, 11);

		assertNull(BitcoinyHTLC.findHtlcSecret(fixture.bitcoiny, fixture.p2shAddress));
	}

	@Test
	public void testFindHtlcSecretIgnoresMalformedAndShortSecretScripts() throws ForeignBlockchainException {
		HtlcFixture fixture = createHtlcFixture("Bitcoin-mock-malformed-secret", false);
		addSpendTransaction(fixture, "33".repeat(32), HashCode.fromString("ff").asBytes(), 11);

		byte[] shortSecretScriptSig = buildRedeemScriptSig(new byte[] {1, 2, 3}, fixture.redeemKey, fixture.redeemScriptBytes);
		addSpendTransaction(fixture, "44".repeat(32), shortSecretScriptSig, 12);

		assertNull(BitcoinyHTLC.findHtlcSecret(fixture.bitcoiny, fixture.p2shAddress));
	}

	@Test
	public void testFindHtlcSecretIgnoresUnconfirmedRedeemTransaction() throws ForeignBlockchainException {
		HtlcFixture fixture = createHtlcFixture("Bitcoin-mock-unconfirmed-redeem", false);
		Transaction redeemTransaction = BitcoinyHTLC.buildRedeemTransaction(fixture.bitcoiny.getNetworkParameters(),
				Coin.valueOf(19_000L), fixture.redeemKey, Collections.singletonList(fixture.fundingOutput),
				fixture.redeemScriptBytes, EXPECTED_SECRET, fixture.redeemKey.getPubKeyHash());
		addSpendTransaction(fixture, redeemTransaction, 0);

		assertNull(BitcoinyHTLC.findHtlcSecret(fixture.bitcoiny, fixture.p2shAddress));
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

		BitcoinyHTLC.Status htlcStatus = BitcoinyHTLC.determineHtlcStatus(fixture.bitcoiny, fixture.p2shAddress, 1L);

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

		BitcoinyHTLC.Status htlcStatus1 = BitcoinyHTLC.determineHtlcStatus(mockBitcoiny, p2shAddress, 1L);
		BitcoinyHTLC.Status htlcStatus2 = BitcoinyHTLC.determineHtlcStatus(mockBitcoiny, p2shAddress, 1L);

		assertEquals(BitcoinyHTLC.Status.UNFUNDED, htlcStatus1);
		assertEquals(htlcStatus1, htlcStatus2);
	}

	private HtlcFixture createHtlcFixture(boolean includeRedeemTransaction) {
		return createHtlcFixture("Bitcoin-mock-htlc-" + (includeRedeemTransaction ? "redeem" : "funding"), includeRedeemTransaction);
	}

	private HtlcFixture createHtlcFixture(String netId, boolean includeRedeemTransaction) {
		NetworkParameters params = bitcoin.getNetworkParameters();
		MockBitcoinyBlockchainProvider blockchainProvider = new MockBitcoinyBlockchainProvider(netId);
		TestBitcoiny mockBitcoiny = new TestBitcoiny(params, blockchainProvider, "BTC");
		ECKey refundKey = ECKey.fromPrivate(HashCode.fromString("11".repeat(32)).asBytes());
		ECKey redeemKey = ECKey.fromPrivate(HashCode.fromString("22".repeat(32)).asBytes());
		byte[] redeemScriptBytes = BitcoinyHTLC.buildScript(refundKey.getPubKeyHash(), LOCK_TIME, redeemKey.getPubKeyHash(), Crypto.hash160(EXPECTED_SECRET));
		String p2shAddress = mockBitcoiny.deriveP2shAddress(redeemScriptBytes);
		byte[] p2shScriptPubKey = BitcoinyScript.scriptPubKey(params, p2shAddress);
		Transaction fundingTransaction = new Transaction(params);
		fundingTransaction.addOutput(Coin.valueOf(20_000L), Address.fromString(params, p2shAddress));
		String fundingTxHash = fundingTransaction.getTxId().toString();
		UnspentOutput fundingOutput = new UnspentOutput(HashCode.fromString(fundingTxHash).asBytes(), 0, 10, 20_000L,
				fundingTransaction.getOutput(0).getScriptPubKey().getProgram(), p2shAddress);

		HtlcFixture fixture = new HtlcFixture(mockBitcoiny, blockchainProvider, p2shAddress, p2shScriptPubKey,
				refundKey, redeemKey, redeemScriptBytes, fundingOutput);

		if (includeRedeemTransaction) {
			Transaction redeemTransaction = BitcoinyHTLC.buildRedeemTransaction(params, Coin.valueOf(19_000L), redeemKey,
					Collections.singletonList(fundingOutput), redeemScriptBytes, EXPECTED_SECRET, redeemKey.getPubKeyHash());
			addSpendTransaction(fixture, redeemTransaction, 11);
		} else {
			blockchainProvider.addAddressTransaction(p2shScriptPubKey, new TransactionHash(10, fundingTxHash));
			blockchainProvider.addTransaction(new BitcoinyTransaction(fundingTxHash, fundingTransaction.bitcoinSerialize().length, (int) fundingTransaction.getLockTime(),
					1_700_000_000, Collections.emptyList(), Collections.singletonList(
							new BitcoinyTransaction.Output(HashCode.fromBytes(p2shScriptPubKey).toString(), 20_000L))));
		}

		return fixture;
	}

	private static void addSpendTransaction(HtlcFixture fixture, Transaction transaction, int height) {
		addSpendTransaction(fixture, transaction.getTxId().toString(), transaction.getInput(0).getScriptSig().getProgram(), height);
	}

	private static void addSpendTransaction(HtlcFixture fixture, String txHash, byte[] scriptSig, int height) {
		fixture.blockchainProvider.addAddressTransaction(fixture.p2shScriptPubKey, new TransactionHash(height, txHash));
		fixture.blockchainProvider.addTransaction(new BitcoinyTransaction(txHash, scriptSig.length, 0, height == 0 ? null : 1_700_000_000,
				Collections.singletonList(new BitcoinyTransaction.Input(HashCode.fromBytes(scriptSig).toString(), 0xffffffff,
						HashCode.fromBytes(fixture.fundingOutput.hash).toString(), fixture.fundingOutput.index)),
				Collections.emptyList()));
	}

	private static byte[] buildRedeemScriptSig(byte[] secret, ECKey redeemKey, byte[] redeemScriptBytes) {
		return Bytes.concat(
				BitcoinyScript.pushData(secret),
				BitcoinyScript.pushData(new byte[] {1}),
				BitcoinyScript.pushData(redeemKey.getPubKey()),
				BitcoinyScript.pushData(redeemScriptBytes));
	}

	private void assumeLiveCrosschainTestsEnabled() {
		assumeTrue(Boolean.getBoolean(RUN_LIVE_CROSSCHAIN_TESTS_PROPERTY));
	}

	private static class HtlcFixture {
		private final Bitcoiny bitcoiny;
		private final MockBitcoinyBlockchainProvider blockchainProvider;
		private final String p2shAddress;
		private final byte[] p2shScriptPubKey;
		private final ECKey refundKey;
		private final ECKey redeemKey;
		private final byte[] redeemScriptBytes;
		private final UnspentOutput fundingOutput;

		private HtlcFixture(Bitcoiny bitcoiny, MockBitcoinyBlockchainProvider blockchainProvider, String p2shAddress,
				byte[] p2shScriptPubKey, ECKey refundKey, ECKey redeemKey, byte[] redeemScriptBytes,
				UnspentOutput fundingOutput) {
			this.bitcoiny = bitcoiny;
			this.blockchainProvider = blockchainProvider;
			this.p2shAddress = p2shAddress;
			this.p2shScriptPubKey = p2shScriptPubKey;
			this.refundKey = refundKey;
			this.redeemKey = redeemKey;
			this.redeemScriptBytes = redeemScriptBytes;
			this.fundingOutput = fundingOutput;
		}
	}

}
