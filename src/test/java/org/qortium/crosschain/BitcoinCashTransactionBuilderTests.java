package org.qortium.crosschain;

import cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock;
import com.google.common.hash.HashCode;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.crypto.ECKey;
import org.junit.BeforeClass;
import org.junit.Test;
import org.qortium.crypto.Crypto;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BitcoinCashTransactionBuilderTests {

	private static final String XPRV = "xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg";
	private static final byte[] SECRET = "This string is exactly 32 bytes!".getBytes();
	private static final int LOCK_TIME = 1_700_000_000;
	private static final int SIGHASH_ALL_FORKID = 0x41;

	@BeforeClass
	public static void beforeClass() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testBuildSpendUsesForkIdSignatures() throws ForeignBlockchainException {
		MockProvider provider = new MockProvider();
		TestBitcoinCashBitcoiny bitcoiny = new TestBitcoinCashBitcoiny(provider);
		BitcoinyDeterministicKey receiveKey = BitcoinyDeterministicKeyChain.fromBase58(bitcoiny.getNetworkParameters(), XPRV).getReceiveKey(0);
		String walletAddress = bitcoiny.pkhToAddress(receiveKey.getPublicKeyHash());
		byte[] walletScript = BitcoinyScript.p2pkhScript(receiveKey.getPublicKeyHash());
		provider.addUnspentOutput(walletAddress, new UnspentOutput(HashCode.fromString("11".repeat(32)).asBytes(), 0, 10,
				100_000_000L, walletScript, walletAddress));

		String recipient = bitcoiny.pkhToAddress(HashCode.fromString("22".repeat(20)).asBytes());
		BitcoinySignedTransaction signedTransaction = BitcoinCashTransactionBuilder.buildSpend(bitcoiny, XPRV, recipient, 10_000L, 10L);

		assertNotNull(signedTransaction);
		assertFalse(signedTransaction.getTxHash().isEmpty());

		BitcoinyTransaction parsedTransaction = BitcoinyRawTransactionParser.parse(signedTransaction.getRawTransaction());
		assertEquals(signedTransaction.getTxHash(), parsedTransaction.txHash);
		assertEquals("02000000", HashCode.fromBytes(java.util.Arrays.copyOf(signedTransaction.getRawTransaction(), 4)).toString());
		assertEquals(1, parsedTransaction.inputs.size());
		assertEquals(2, parsedTransaction.outputs.size());
		assertTrue(parsedTransaction.outputs.stream().anyMatch(output -> output.value == 10_000L));

		List<byte[]> chunks = BitcoinyScript.extractScriptSigChunks(HashCode.fromString(parsedTransaction.inputs.get(0).scriptSig).asBytes());
		assertEquals(2, chunks.size());
		assertEquals((byte) SIGHASH_ALL_FORKID, chunks.get(0)[chunks.get(0).length - 1]);
	}

	@Test
	public void testBuildHtlcRedeemAndRefundUseForkIdSignatures() throws ForeignBlockchainException {
		MockProvider provider = new MockProvider();
		TestBitcoinCashBitcoiny bitcoiny = new TestBitcoinCashBitcoiny(provider);
		ECKey refundKey = ECKey.fromPrivate(HashCode.fromString("11".repeat(32)).asBytes());
		ECKey redeemKey = ECKey.fromPrivate(HashCode.fromString("22".repeat(32)).asBytes());
		byte[] redeemScript = BitcoinyHTLC.buildScript(refundKey.getPubKeyHash(), LOCK_TIME, redeemKey.getPubKeyHash(), Crypto.hash160(SECRET));
		List<UnspentOutput> fundingOutputs = Collections.singletonList(
				new UnspentOutput(HashCode.fromString("33".repeat(32)).asBytes(), 1, 20, 20_000L, BitcoinyScript.p2shScript(Crypto.hash160(redeemScript)), null));

		BitcoinySignedTransaction redeemTransaction = BitcoinCashTransactionBuilder.buildRedeem(bitcoiny, Coin.valueOf(19_000L),
				redeemKey, fundingOutputs, redeemScript, SECRET, redeemKey.getPubKeyHash());
		BitcoinyTransaction parsedRedeem = BitcoinyRawTransactionParser.parse(redeemTransaction.getRawTransaction());

		assertEquals(redeemTransaction.getTxHash(), parsedRedeem.txHash);
		assertEquals("02000000", HashCode.fromBytes(java.util.Arrays.copyOf(redeemTransaction.getRawTransaction(), 4)).toString());
		List<byte[]> redeemChunks = BitcoinyScript.extractScriptSigChunks(HashCode.fromString(parsedRedeem.inputs.get(0).scriptSig).asBytes());
		assertEquals(4, redeemChunks.size());
		assertArrayEquals(SECRET, redeemChunks.get(0));
		assertEquals((byte) SIGHASH_ALL_FORKID, redeemChunks.get(1)[redeemChunks.get(1).length - 1]);
		assertArrayEquals(redeemScript, redeemChunks.get(3));

		BitcoinySignedTransaction refundTransaction = BitcoinCashTransactionBuilder.buildRefund(bitcoiny, Coin.valueOf(19_000L),
				refundKey, fundingOutputs, redeemScript, LOCK_TIME, refundKey.getPubKeyHash());
		BitcoinyTransaction parsedRefund = BitcoinyRawTransactionParser.parse(refundTransaction.getRawTransaction());

		assertEquals(refundTransaction.getTxHash(), parsedRefund.txHash);
		assertEquals(LOCK_TIME, parsedRefund.locktime);
		List<byte[]> refundChunks = BitcoinyScript.extractScriptSigChunks(HashCode.fromString(parsedRefund.inputs.get(0).scriptSig).asBytes());
		assertEquals(3, refundChunks.size());
		assertEquals((byte) SIGHASH_ALL_FORKID, refundChunks.get(0)[refundChunks.get(0).length - 1]);
		assertArrayEquals(redeemScript, refundChunks.get(2));
	}

	@Test
	public void testBuildSpendIgnoresCashTokenUtxos() {
		MockProvider provider = new MockProvider();
		TestBitcoinCashBitcoiny bitcoiny = new TestBitcoinCashBitcoiny(provider);
		BitcoinyDeterministicKey receiveKey = BitcoinyDeterministicKeyChain.fromBase58(bitcoiny.getNetworkParameters(), XPRV).getReceiveKey(0);
		String walletAddress = bitcoiny.pkhToAddress(receiveKey.getPublicKeyHash());
		byte[] walletScript = BitcoinyScript.p2pkhScript(receiveKey.getPublicKeyHash());
		provider.addUnspentOutput(walletAddress, new UnspentOutput(HashCode.fromString("44".repeat(32)).asBytes(), 0, 10,
				100_000_000L, new byte[] { (byte) 0xef, 0x01 }, walletAddress), walletScript);

		String recipient = bitcoiny.pkhToAddress(HashCode.fromString("22".repeat(20)).asBytes());

		assertNull(BitcoinCashTransactionBuilder.buildSpend(bitcoiny, XPRV, recipient, 10_000L, 10L));
	}

	@Test
	public void testRegisteredBitcoinCashRejectsLegacyBitcoinjTransactionBuilders() {
		RegisteredBitcoiny bitcoinCash = new RegisteredBitcoiny(BitcoinyChainSpecs.BITCOIN_CASH,
				BitcoinyChainSpecs.BITCOIN_CASH.getNetwork(BitcoinyChainSpecs.MAIN));
		String recipient = bitcoinCash.pkhToAddress(HashCode.fromString("44".repeat(20)).asBytes());

		assertUnsupportedBitcoinjBuilder(() -> bitcoinCash.buildSpend(XPRV, recipient, 1L));
		assertUnsupportedBitcoinjBuilder(() -> bitcoinCash.buildSpend(XPRV, recipient, 1L, null));
		assertUnsupportedBitcoinjBuilder(() -> bitcoinCash.buildSpendMultiple(XPRV, Map.of(recipient, 1L), null));
	}

	private static void assertUnsupportedBitcoinjBuilder(Runnable runnable) {
		try {
			runnable.run();
			fail("Expected legacy bitcoinj transaction builder to be unsupported");
		} catch (UnsupportedOperationException e) {
			assertTrue(e.getMessage().contains(BitcoinyChainSpecs.BITCOIN_CASH_CURRENCY_CODE));
		}
	}

	private static class TestBitcoinCashBitcoiny extends Bitcoiny {
		private long feeRequired = 10_000L;

		private TestBitcoinCashBitcoiny(MockProvider provider) {
			super(provider, new Context(BitcoinyChainSpecs.BITCOIN_CASH.getNetwork(BitcoinyChainSpecs.MAIN).getParams()),
					BitcoinyChainSpecs.BITCOIN_CASH.getNetwork(BitcoinyChainSpecs.MAIN).getParams(),
					BitcoinyChainSpecs.BITCOIN_CASH_CURRENCY_CODE, Coin.valueOf(1_000L));
			provider.setBlockchain(this);
		}

		@Override
		public long getP2shFee(Long timestamp) {
			return this.feeRequired;
		}

		@Override
		public long getFeeRequired() {
			return this.feeRequired;
		}

		@Override
		public void setFeeRequired(long fee) {
			this.feeRequired = fee;
		}
	}

	private static class MockProvider extends BitcoinyBlockchainProvider {
		private final Map<String, List<UnspentOutput>> unspentOutputsByAddress = new HashMap<>();
		private final Map<String, List<UnspentOutput>> unspentOutputsByScript = new HashMap<>();
		private final List<byte[]> broadcastTransactions = new ArrayList<>();
		private Bitcoiny blockchain;

		private void addUnspentOutput(String address, UnspentOutput unspentOutput) {
			addUnspentOutput(address, unspentOutput, unspentOutput.script);
		}

		private void addUnspentOutput(String address, UnspentOutput unspentOutput, byte[] lookupScript) {
			this.unspentOutputsByAddress.computeIfAbsent(address, key -> new ArrayList<>()).add(unspentOutput);
			this.unspentOutputsByScript.computeIfAbsent(HashCode.fromBytes(lookupScript).toString(), key -> new ArrayList<>()).add(unspentOutput);
		}

		@Override
		public void setBlockchain(Bitcoiny blockchain) {
			this.blockchain = blockchain;
		}

		@Override
		public String getNetId() {
			return "BCH-mock";
		}

		@Override
		public int getCurrentHeight() {
			return 100;
		}

		@Override
		public List<CompactBlock> getCompactBlocks(int startHeight, int count) {
			return Collections.emptyList();
		}

		@Override
		public List<byte[]> getRawBlockHeaders(int startHeight, int count) {
			return Collections.emptyList();
		}

		@Override
		public List<Long> getBlockTimestamps(int startHeight, int count) {
			return Collections.emptyList();
		}

		@Override
		public long getConfirmedBalance(byte[] scriptPubKey) {
			return 0;
		}

		@Override
		public long getConfirmedAddressBalance(String base58Address) {
			return 0;
		}

		@Override
		public byte[] getRawTransaction(String txHash) throws ForeignBlockchainException {
			throw new ForeignBlockchainException.NotFoundException("mock raw transaction not found");
		}

		@Override
		public byte[] getRawTransaction(byte[] txHash) throws ForeignBlockchainException {
			return getRawTransaction(HashCode.fromBytes(txHash).toString());
		}

		@Override
		public BitcoinyTransaction getTransaction(String txHash) throws ForeignBlockchainException {
			throw new ForeignBlockchainException.NotFoundException("mock transaction not found");
		}

		@Override
		public List<TransactionHash> getAddressTransactions(byte[] scriptPubKey, boolean includeUnconfirmed) {
			return Collections.emptyList();
		}

		@Override
		public List<BitcoinyTransaction> getAddressBitcoinyTransactions(String address, boolean includeUnconfirmed) {
			return Collections.emptyList();
		}

		@Override
		public List<UnspentOutput> getUnspentOutputs(String address, boolean includeUnconfirmed) {
			return new ArrayList<>(this.unspentOutputsByAddress.getOrDefault(address, Collections.emptyList()));
		}

		@Override
		public List<UnspentOutput> getUnspentOutputs(byte[] scriptPubKey, boolean includeUnconfirmed) {
			return new ArrayList<>(this.unspentOutputsByScript.getOrDefault(HashCode.fromBytes(scriptPubKey).toString(), Collections.emptyList()));
		}

		@Override
		public void broadcastTransaction(byte[] rawTransaction) {
			this.broadcastTransactions.add(rawTransaction);
		}

		@Override
		public Set<ChainableServer> getServers() {
			return new HashSet<>();
		}

		@Override
		public Set<ChainableServer> getUselessServers() {
			return new HashSet<>();
		}

		@Override
		public ChainableServer getCurrentServer() {
			return null;
		}

		@Override
		public boolean addServer(ChainableServer server) {
			return false;
		}

		@Override
		public boolean removeServer(ChainableServer server) {
			return false;
		}

		@Override
		public Optional<ChainableServerConnection> setCurrentServer(ChainableServer server, String requestedBy) {
			return Optional.empty();
		}

		@Override
		public List<ChainableServerConnection> getServerConnections() {
			return Collections.emptyList();
		}

		@Override
		public ChainableServer getServer(String hostName, ChainableServer.ConnectionType type, int port) {
			return null;
		}
	}
}
