package org.qortium.test.crosschain;

import com.google.common.hash.HashCode;
import org.bitcoinj.core.NetworkParameters;
import org.junit.BeforeClass;
import org.junit.Test;
import org.qortium.crosschain.BitcoinyChainSpecs;
import org.qortium.crosschain.BitcoinyDeterministicKey;
import org.qortium.crosschain.BitcoinyDeterministicKeyChain;
import org.qortium.crosschain.BitcoinyScript;
import org.qortium.crosschain.BitcoinyTransactionData;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.UnspentOutput;
import org.qortium.crosschain.WalletSpendContext;
import org.qortium.crosschain.WalletSpendContextUtxo;
import org.qortium.test.common.Common;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WalletSpendContextTests {

	private static final String BTC_XPUB = "xpub661MyMwAqRbcFKoxjof6RWPGfcCirFqyx1wAaYjuKtASDHK5ufvbvDG5NUdKigNnDpdhbuimdjPeAUfpVW1mBrpHjp2oX1ahdcbC1VmUWt9";
	private static final String BTC_XPRV = "xprv9s21ZrQH143K2qjVdn864NSY7aNESo88ao1ZnALHmYdTLUywN8cMNQwbXDZs6N7YmfTaHoHX2FCTiDtUjsLH22EAqxLtaPQZHe8QMTtJUSm";

	@BeforeClass
	public static void beforeClass() throws Exception {
		Common.useDefaultSettings();
	}

	@Test
	public void testSpendContextAcceptsOnlyRootPublicKeyAndReturnsAttestableOutput() throws ForeignBlockchainException {
		NetworkParameters params = BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		MockBitcoinyBlockchainProvider provider = new MockBitcoinyBlockchainProvider("bitcoin-mainnet-mock");
		TestBitcoiny bitcoiny = new TestBitcoiny(params, provider, "BTC");

		assertTrue(bitcoiny.isValidDeterministicPublicKey(BTC_XPUB));
		assertFalse(bitcoiny.isValidDeterministicPublicKey(BTC_XPRV));

		BitcoinyDeterministicKey receiveKey = BitcoinyDeterministicKeyChain.fromBase58(params, BTC_XPUB).getReceiveKey(0);
		byte[] walletScript = BitcoinyScript.p2pkhScript(receiveKey.getPublicKeyHash());
		BitcoinyTransactionData previousTransaction = new BitcoinyTransactionData(
				1,
				Collections.singletonList(new BitcoinyTransactionData.Input(
						"00".repeat(32), 0, new byte[] { 0 }, BitcoinyTransactionData.NO_LOCKTIME_SEQUENCE)),
				Collections.singletonList(new BitcoinyTransactionData.Output(100_000L, walletScript)),
				0L);
		String txHash = previousTransaction.txHash();
		provider.addRawTransaction(txHash, previousTransaction.serialize());
		provider.addUnspentOutput(walletScript,
				new UnspentOutput(HashCode.fromString(txHash).asBytes(), 0, 10, 100_000L));
		provider.addUnspentOutput(walletScript,
				new UnspentOutput(HashCode.fromString("22".repeat(32)).asBytes(), 0, 0, 100_000L));

		WalletSpendContext spendContext = bitcoiny.getWalletSpendContext(BTC_XPUB, 200, 1_000, 1_000_000, 8_000_000);
		List<WalletSpendContextUtxo> outputs = spendContext.getOutputs();
		assertEquals(1, outputs.size());
		assertEquals(1, spendContext.getPreviousTransactions().size());
		assertArrayEquals(previousTransaction.serialize(), spendContext.getPreviousTransactions().get(txHash));
		WalletSpendContextUtxo output = outputs.get(0);
		assertEquals("M/0/0", output.getPathAsString());
		assertEquals(100_000L, output.getValue());
		assertEquals(10, output.getHeight());
		assertEquals(0, output.getOutputIndex());
		assertArrayEquals(walletScript, output.getScriptPubKey());
		assertEquals(txHash, HashCode.fromBytes(output.getTransactionHash()).toString());

		try {
			bitcoiny.getWalletSpendContext(BTC_XPUB, 200, 1_000,
					previousTransaction.serialize().length - 1, 8_000_000);
			fail("Expected the per-transaction byte limit to fail closed");
		} catch (ForeignBlockchainException e) {
			assertTrue(e.getMessage().contains("per-transaction limit"));
		}

		try {
			bitcoiny.getWalletSpendContext(BTC_XPUB, 200, 1_000,
					1_000_000, previousTransaction.serialize().length - 1);
			fail("Expected the aggregate byte limit to fail closed");
		} catch (ForeignBlockchainException e) {
			assertTrue(e.getMessage().contains("response limit"));
		}
	}

	@Test
	public void testSpendContextFailsInsteadOfTruncatingOutputs() throws ForeignBlockchainException {
		NetworkParameters params = BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		MockBitcoinyBlockchainProvider provider = new MockBitcoinyBlockchainProvider("bitcoin-mainnet-mock");
		TestBitcoiny bitcoiny = new TestBitcoiny(params, provider, "BTC");
		BitcoinyDeterministicKey receiveKey = BitcoinyDeterministicKeyChain.fromBase58(params, BTC_XPUB).getReceiveKey(0);
		byte[] walletScript = BitcoinyScript.p2pkhScript(receiveKey.getPublicKeyHash());

		for (int index = 0; index < 2; index++) {
			BitcoinyTransactionData previousTransaction = new BitcoinyTransactionData(
					1,
					Collections.singletonList(new BitcoinyTransactionData.Input(
							("0" + index).repeat(32), index, new byte[] { (byte) index }, BitcoinyTransactionData.NO_LOCKTIME_SEQUENCE)),
					Collections.singletonList(new BitcoinyTransactionData.Output(100_000L + index, walletScript)),
					0L);
			String txHash = previousTransaction.txHash();
			provider.addRawTransaction(txHash, previousTransaction.serialize());
			provider.addUnspentOutput(walletScript,
					new UnspentOutput(HashCode.fromString(txHash).asBytes(), 0, 10 + index, 100_000L + index));
		}

		try {
			bitcoiny.getWalletSpendContext(BTC_XPUB, 200, 1, 1_000_000, 8_000_000);
			fail("Expected the output limit to fail closed");
		} catch (ForeignBlockchainException e) {
			assertTrue(e.getMessage().contains("exceeded"));
		}
	}

	@Test
	public void testSpendContextFailsBeforeScanningPastKeyLimit() {
		NetworkParameters params = BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		MockBitcoinyBlockchainProvider provider = new MockBitcoinyBlockchainProvider("bitcoin-mainnet-mock");
		TestBitcoiny bitcoiny = new TestBitcoiny(params, provider, "BTC");

		try {
			bitcoiny.getWalletSpendContext(BTC_XPUB, 2, 1_000, 1_000_000, 8_000_000);
			fail("Expected the key limit to fail before the initial discovery batch");
		} catch (ForeignBlockchainException e) {
			assertTrue(e.getMessage().contains("exceeded"));
		}
	}

	@Test
	public void testSpendContextRejectsRawTransactionHashMismatch() {
		NetworkParameters params = BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		MockBitcoinyBlockchainProvider provider = new MockBitcoinyBlockchainProvider("bitcoin-mainnet-mock");
		TestBitcoiny bitcoiny = new TestBitcoiny(params, provider, "BTC");
		BitcoinyDeterministicKey receiveKey = BitcoinyDeterministicKeyChain.fromBase58(params, BTC_XPUB).getReceiveKey(0);
		byte[] walletScript = BitcoinyScript.p2pkhScript(receiveKey.getPublicKeyHash());
		BitcoinyTransactionData previousTransaction = new BitcoinyTransactionData(
				1,
				Collections.singletonList(new BitcoinyTransactionData.Input(
						"00".repeat(32), 0, new byte[] { 0 }, BitcoinyTransactionData.NO_LOCKTIME_SEQUENCE)),
				Collections.singletonList(new BitcoinyTransactionData.Output(100_000L, walletScript)),
				0L);
		String claimedTxHash = "11".repeat(32);
		provider.addRawTransaction(claimedTxHash, previousTransaction.serialize());
		provider.addUnspentOutput(walletScript,
				new UnspentOutput(HashCode.fromString(claimedTxHash).asBytes(), 0, 10, 100_000L));

		try {
			bitcoiny.getWalletSpendContext(BTC_XPUB, 200, 1_000, 1_000_000, 8_000_000);
			fail("Expected raw transaction hash mismatch to fail closed");
		} catch (ForeignBlockchainException e) {
			assertTrue(e.getMessage().contains("hash mismatch"));
		}
	}

	@Test
	public void testSpendContextDeduplicatesPreviousTransactions() throws ForeignBlockchainException {
		NetworkParameters params = BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		MockBitcoinyBlockchainProvider provider = new MockBitcoinyBlockchainProvider("bitcoin-mainnet-mock");
		TestBitcoiny bitcoiny = new TestBitcoiny(params, provider, "BTC");
		BitcoinyDeterministicKey receiveKey = BitcoinyDeterministicKeyChain.fromBase58(params, BTC_XPUB).getReceiveKey(0);
		byte[] walletScript = BitcoinyScript.p2pkhScript(receiveKey.getPublicKeyHash());
		BitcoinyTransactionData previousTransaction = new BitcoinyTransactionData(
				1,
				Collections.singletonList(new BitcoinyTransactionData.Input(
						"00".repeat(32), 0, new byte[] { 0 }, BitcoinyTransactionData.NO_LOCKTIME_SEQUENCE)),
				List.of(
						new BitcoinyTransactionData.Output(100_000L, walletScript),
						new BitcoinyTransactionData.Output(200_000L, walletScript)),
				0L);
		String txHash = previousTransaction.txHash();
		provider.addRawTransaction(txHash, previousTransaction.serialize());
		provider.addUnspentOutput(walletScript,
				new UnspentOutput(HashCode.fromString(txHash).asBytes(), 0, 10, 100_000L));
		provider.addUnspentOutput(walletScript,
				new UnspentOutput(HashCode.fromString(txHash).asBytes(), 1, 10, 200_000L));

		WalletSpendContext context = bitcoiny.getWalletSpendContext(BTC_XPUB, 200, 1_000, 1_000_000, 8_000_000);
		assertEquals(2, context.getOutputs().size());
		assertEquals(1, context.getPreviousTransactions().size());
	}

	@Test
	public void testSpendContextRejectsDuplicateOutpoints() {
		NetworkParameters params = BitcoinyChainSpecs.BITCOIN.getNetwork(BitcoinyChainSpecs.MAIN).getParams();
		MockBitcoinyBlockchainProvider provider = new MockBitcoinyBlockchainProvider("bitcoin-mainnet-mock");
		TestBitcoiny bitcoiny = new TestBitcoiny(params, provider, "BTC");
		BitcoinyDeterministicKey receiveKey = BitcoinyDeterministicKeyChain.fromBase58(params, BTC_XPUB).getReceiveKey(0);
		byte[] walletScript = BitcoinyScript.p2pkhScript(receiveKey.getPublicKeyHash());
		BitcoinyTransactionData previousTransaction = new BitcoinyTransactionData(
				1,
				Collections.singletonList(new BitcoinyTransactionData.Input(
						"00".repeat(32), 0, new byte[] { 0 }, BitcoinyTransactionData.NO_LOCKTIME_SEQUENCE)),
				Collections.singletonList(new BitcoinyTransactionData.Output(100_000L, walletScript)),
				0L);
		String txHash = previousTransaction.txHash();
		provider.addRawTransaction(txHash, previousTransaction.serialize());
		UnspentOutput duplicate = new UnspentOutput(HashCode.fromString(txHash).asBytes(), 0, 10, 100_000L);
		provider.addUnspentOutput(walletScript, duplicate);
		provider.addUnspentOutput(walletScript, duplicate);

		try {
			bitcoiny.getWalletSpendContext(BTC_XPUB, 200, 1_000, 1_000_000, 8_000_000);
			fail("Expected duplicate outpoints to fail closed");
		} catch (ForeignBlockchainException e) {
			assertTrue(e.getMessage().contains("duplicate outpoint"));
		}
	}
}
