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
import java.util.Arrays;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TimestampedLegacyTransactionBuilderTests {

	private static final String XPRV = "TDt9Ee2cL2o71x13ZrmAGDPnxA2eey8jra3hXYNoww7zCKqSb6qi9Q78g4szyLsTpf8GEUAVWpxJcMDnatsxrXaLB9E5kdvviG9rtEZ3kz4vs8a";
	private static final String PREVIOUS_TX_HASH_WIRE = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
	private static final String P2PKH_SCRIPT = "76a914" + "22".repeat(20) + "88ac";
	private static final String P2SH_SCRIPT = "a914" + "33".repeat(20) + "87";
	private static final String TIMESTAMPED_LEGACY_RAW_HEX = "01000000"
			+ "44332211"
			+ "01"
			+ PREVIOUS_TX_HASH_WIRE
			+ "02000000"
			+ "03aabbcc"
			+ "feffffff"
			+ "02"
			+ "1027000000000000"
			+ "19" + P2PKH_SCRIPT
			+ "0500000000000000"
			+ "17" + P2SH_SCRIPT
			+ "78563412";
	private static final byte[] SECRET = "This string is exactly 32 bytes!".getBytes();
	private static final int LOCK_TIME = 1_700_000_000;
	private static final long FIXED_TIMESTAMP = 1_700_000_000L;

	@BeforeClass
	public static void beforeClass() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testBuildSpendUsesTimestampedLegacyFormat() throws ForeignBlockchainException {
		MockProvider provider = new MockProvider();
		TestTimestampedBitcoiny bitcoiny = new TestTimestampedBitcoiny(provider);
		BitcoinyDeterministicKey receiveKey = BitcoinyDeterministicKeyChain.fromBase58(bitcoiny.getNetworkParameters(), XPRV).getReceiveKey(0);
		String walletAddress = bitcoiny.pkhToAddress(receiveKey.getPublicKeyHash());
		byte[] walletScript = BitcoinyScript.p2pkhScript(receiveKey.getPublicKeyHash());
		provider.addUnspentOutput(walletAddress, new UnspentOutput(HashCode.fromString("11".repeat(32)).asBytes(), 0, 10, 100_000_000L, walletScript, walletAddress));
		provider.addAddressTransaction(walletScript, new TransactionHash(10, "11".repeat(32)));

		String recipient = bitcoiny.pkhToAddress(HashCode.fromString("22".repeat(20)).asBytes());
		BitcoinySignedTransaction signedTransaction = TimestampedLegacyTransactionBuilder.buildSpend(bitcoiny, XPRV, recipient, 10_000L, 10L, FIXED_TIMESTAMP);

		assertNotNull(signedTransaction);
		byte[] rawTransaction = signedTransaction.getRawTransaction();
		assertEquals("0100000000f15365", HashCode.fromBytes(Arrays.copyOf(rawTransaction, 8)).toString());
		assertFalse(signedTransaction.getTxHash().isEmpty());

		BitcoinyTransaction parsedTransaction = BitcoinyRawTransactionParser.parse(BitcoinyTransactionFormat.TIMESTAMPED_LEGACY, rawTransaction);
		assertEquals(signedTransaction.getTxHash(), parsedTransaction.txHash);
		assertEquals(1, parsedTransaction.inputs.size());
		assertEquals(2, parsedTransaction.outputs.size());
		assertTrue(parsedTransaction.outputs.stream().anyMatch(output -> output.value == 10_000L));
		assertEquals(0, parsedTransaction.locktime);

		List<byte[]> chunks = BitcoinyScript.extractScriptSigChunks(HashCode.fromString(parsedTransaction.inputs.get(0).scriptSig).asBytes());
		assertEquals(2, chunks.size());
	}

	@Test
	public void testBuildHtlcRedeemAndRefundUseTimestampedLegacyFormat() throws ForeignBlockchainException {
		MockProvider provider = new MockProvider();
		TestTimestampedBitcoiny bitcoiny = new TestTimestampedBitcoiny(provider);
		ECKey refundKey = ECKey.fromPrivate(HashCode.fromString("11".repeat(32)).asBytes());
		ECKey redeemKey = ECKey.fromPrivate(HashCode.fromString("22".repeat(32)).asBytes());
		byte[] redeemScript = BitcoinyHTLC.buildScript(refundKey.getPubKeyHash(), LOCK_TIME, redeemKey.getPubKeyHash(), Crypto.hash160(SECRET));
		List<UnspentOutput> fundingOutputs = Collections.singletonList(
				new UnspentOutput(HashCode.fromString("33".repeat(32)).asBytes(), 1, 20, 20_000L, BitcoinyScript.p2shScript(Crypto.hash160(redeemScript)), null));

		BitcoinySignedTransaction redeemTransaction = TimestampedLegacyTransactionBuilder.buildRedeem(bitcoiny, Coin.valueOf(19_000L), redeemKey,
				fundingOutputs, redeemScript, SECRET, redeemKey.getPubKeyHash(), FIXED_TIMESTAMP);
		BitcoinyTransaction parsedRedeem = BitcoinyRawTransactionParser.parse(BitcoinyTransactionFormat.TIMESTAMPED_LEGACY, redeemTransaction.getRawTransaction());

		assertEquals("0100000000f15365", HashCode.fromBytes(Arrays.copyOf(redeemTransaction.getRawTransaction(), 8)).toString());
		assertEquals(redeemTransaction.getTxHash(), parsedRedeem.txHash);
		assertEquals(1, parsedRedeem.inputs.size());
		List<byte[]> redeemChunks = BitcoinyScript.extractScriptSigChunks(HashCode.fromString(parsedRedeem.inputs.get(0).scriptSig).asBytes());
		assertEquals(4, redeemChunks.size());
		assertArrayEquals(SECRET, redeemChunks.get(0));
		assertArrayEquals(redeemScript, redeemChunks.get(3));

		BitcoinySignedTransaction refundTransaction = TimestampedLegacyTransactionBuilder.buildRefund(bitcoiny, Coin.valueOf(19_000L), refundKey,
				fundingOutputs, redeemScript, LOCK_TIME, refundKey.getPubKeyHash(), FIXED_TIMESTAMP);
		BitcoinyTransaction parsedRefund = BitcoinyRawTransactionParser.parse(BitcoinyTransactionFormat.TIMESTAMPED_LEGACY, refundTransaction.getRawTransaction());

		assertEquals("0100000000f15365", HashCode.fromBytes(Arrays.copyOf(refundTransaction.getRawTransaction(), 8)).toString());
		assertEquals(refundTransaction.getTxHash(), parsedRefund.txHash);
		assertEquals(LOCK_TIME, parsedRefund.locktime);
		List<byte[]> refundChunks = BitcoinyScript.extractScriptSigChunks(HashCode.fromString(parsedRefund.inputs.get(0).scriptSig).asBytes());
		assertEquals(3, refundChunks.size());
		assertArrayEquals(redeemScript, refundChunks.get(2));
	}

	@Test
	public void testRegisteredVergeRejectsLegacyBitcoinjTransactionBuildersAndParsesTimestampedTransactions() throws ForeignBlockchainException {
		RegisteredBitcoiny verge = new RegisteredBitcoiny(BitcoinyChainSpecs.VERGE, BitcoinyChainSpecs.VERGE.getNetwork(BitcoinyChainSpecs.MAIN));
		String recipient = verge.pkhToAddress(HashCode.fromString("44".repeat(20)).asBytes());

		assertUnsupportedBitcoinjBuilder(() -> verge.buildSpend(XPRV, recipient, 1L));
		assertUnsupportedBitcoinjBuilder(() -> verge.buildSpend(XPRV, recipient, 1L, null));
		assertUnsupportedBitcoinjBuilder(() -> verge.buildSpendMultiple(XPRV, Map.of(recipient, 1L), null));

		BitcoinyTransaction parsedTransaction = verge.deserializeRawTransaction(HashCode.fromString(TIMESTAMPED_LEGACY_RAW_HEX).asBytes());
		assertEquals(1, parsedTransaction.inputs.size());
		assertEquals(2, parsedTransaction.outputs.size());
	}

	private static void assertUnsupportedBitcoinjBuilder(Runnable runnable) {
		try {
			runnable.run();
			fail("Expected legacy bitcoinj transaction builder to be unsupported");
		} catch (UnsupportedOperationException e) {
			assertTrue(e.getMessage().contains(BitcoinyChainSpecs.VERGE_CURRENCY_CODE));
		}
	}

	private static class TestTimestampedBitcoiny extends Bitcoiny {
		private long feeRequired = 10_000L;

		private TestTimestampedBitcoiny(MockProvider provider) {
			super(provider, new Context(BitcoinyChainSpecs.VERGE.getNetwork(BitcoinyChainSpecs.MAIN).getParams()),
					BitcoinyChainSpecs.VERGE.getNetwork(BitcoinyChainSpecs.MAIN).getParams(),
					BitcoinyChainSpecs.VERGE_CURRENCY_CODE, Coin.valueOf(10_000L));
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
		private final Map<String, List<TransactionHash>> transactionHashesByScript = new HashMap<>();
		private Bitcoiny blockchain;

		private void addUnspentOutput(String address, UnspentOutput unspentOutput) {
			this.unspentOutputsByAddress.computeIfAbsent(address, key -> new ArrayList<>()).add(unspentOutput);
			if (unspentOutput.script != null)
				this.unspentOutputsByScript.computeIfAbsent(HashCode.fromBytes(unspentOutput.script).toString(), key -> new ArrayList<>()).add(unspentOutput);
		}

		private void addAddressTransaction(byte[] scriptPubKey, TransactionHash transactionHash) {
			this.transactionHashesByScript.computeIfAbsent(HashCode.fromBytes(scriptPubKey).toString(), key -> new ArrayList<>()).add(transactionHash);
		}

		@Override
		public void setBlockchain(Bitcoiny blockchain) {
			this.blockchain = blockchain;
		}

		@Override
		public String getNetId() {
			return "verge-mainnet-mock";
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
			return this.unspentOutputsByAddress.getOrDefault(base58Address, Collections.emptyList()).stream()
					.mapToLong(unspentOutput -> unspentOutput.value)
					.sum();
		}

		@Override
		public byte[] getRawTransaction(String txHash) throws ForeignBlockchainException {
			throw new ForeignBlockchainException.NotFoundException("mock raw transaction not found");
		}

		@Override
		public byte[] getRawTransaction(byte[] txHash) throws ForeignBlockchainException {
			throw new ForeignBlockchainException.NotFoundException("mock raw transaction not found");
		}

		@Override
		public BitcoinyTransaction getTransaction(String txHash) throws ForeignBlockchainException {
			throw new ForeignBlockchainException.NotFoundException("mock transaction not found");
		}

		@Override
		public List<TransactionHash> getAddressTransactions(byte[] scriptPubKey, boolean includeUnconfirmed) {
			return new ArrayList<>(this.transactionHashesByScript.getOrDefault(HashCode.fromBytes(scriptPubKey).toString(), Collections.emptyList()));
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
