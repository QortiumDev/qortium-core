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
import org.qortium.transform.TransformationException;

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

public class SaplingTransparentTransactionBuilderTests {

	private static final String XPRV = "xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg";
	private static final byte[] SECRET = "This string is exactly 32 bytes!".getBytes();
	private static final int LOCK_TIME = 1_700_000_000;

	@BeforeClass
	public static void beforeClass() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testBuildSpendUsesSaplingTransparentFormat() throws ForeignBlockchainException, TransformationException {
		MockProvider provider = new MockProvider();
		TestSaplingBitcoiny bitcoiny = new TestSaplingBitcoiny(provider);
		BitcoinyDeterministicKey receiveKey = BitcoinyDeterministicKeyChain.fromBase58(bitcoiny.getNetworkParameters(), XPRV).getReceiveKey(0);
		String walletAddress = bitcoiny.pkhToAddress(receiveKey.getPublicKeyHash());
		byte[] walletScript = BitcoinyScript.p2pkhScript(receiveKey.getPublicKeyHash());
		provider.addUnspentOutput(walletAddress, new UnspentOutput(HashCode.fromString("11".repeat(32)).asBytes(), 0, 10, 100_000_000L, walletScript, walletAddress));

		String recipient = bitcoiny.pkhToAddress(HashCode.fromString("22".repeat(20)).asBytes());
		BitcoinySignedTransaction signedTransaction = bitcoiny.buildSpendTransaction(XPRV, recipient, 10_000L, 10L);

		assertNotNull(signedTransaction);
		byte[] rawTransaction = signedTransaction.getRawTransaction();
		assertEquals("0400008085202f89", HashCode.fromBytes(Arrays.copyOf(rawTransaction, 8)).toString());
		assertFalse(signedTransaction.getTxHash().isEmpty());

		BitcoinyTransaction parsedTransaction = ZcashFamilyTransactionParser.deserializeRawTransaction(signedTransaction.getTxHash(), rawTransaction);
		assertEquals(1, parsedTransaction.inputs.size());
		assertEquals(2, parsedTransaction.outputs.size());
		assertTrue(parsedTransaction.outputs.stream().anyMatch(output -> output.value == 10_000L));
		assertEquals(0, parsedTransaction.locktime);
	}

	@Test
	public void testBuildHtlcRedeemAndRefundUseSaplingTransparentFormat() throws ForeignBlockchainException, TransformationException {
		MockProvider provider = new MockProvider();
		TestSaplingBitcoiny bitcoiny = new TestSaplingBitcoiny(provider);
		ECKey refundKey = ECKey.fromPrivate(HashCode.fromString("11".repeat(32)).asBytes());
		ECKey redeemKey = ECKey.fromPrivate(HashCode.fromString("22".repeat(32)).asBytes());
		byte[] redeemScript = BitcoinyHTLC.buildScript(refundKey.getPubKeyHash(), LOCK_TIME, redeemKey.getPubKeyHash(), Crypto.hash160(SECRET));
		List<UnspentOutput> fundingOutputs = Collections.singletonList(
				new UnspentOutput(HashCode.fromString("33".repeat(32)).asBytes(), 1, 20, 20_000L, BitcoinyScript.p2shScript(Crypto.hash160(redeemScript)), null));

		BitcoinySignedTransaction redeemTransaction = bitcoiny.buildHtlcRedeemTransaction(Coin.valueOf(19_000L), redeemKey,
				fundingOutputs, redeemScript, SECRET, redeemKey.getPubKeyHash());
		BitcoinyTransaction parsedRedeem = ZcashFamilyTransactionParser.deserializeRawTransaction(redeemTransaction.getTxHash(), redeemTransaction.getRawTransaction());

		assertEquals("0400008085202f89", HashCode.fromBytes(Arrays.copyOf(redeemTransaction.getRawTransaction(), 8)).toString());
		assertEquals(1, parsedRedeem.inputs.size());
		List<byte[]> redeemChunks = BitcoinyScript.extractScriptSigChunks(HashCode.fromString(parsedRedeem.inputs.get(0).scriptSig).asBytes());
		assertEquals(4, redeemChunks.size());
		assertArrayEquals(SECRET, redeemChunks.get(0));
		assertArrayEquals(redeemScript, redeemChunks.get(3));

		BitcoinySignedTransaction refundTransaction = bitcoiny.buildHtlcRefundTransaction(Coin.valueOf(19_000L), refundKey,
				fundingOutputs, redeemScript, LOCK_TIME, refundKey.getPubKeyHash());
		BitcoinyTransaction parsedRefund = ZcashFamilyTransactionParser.deserializeRawTransaction(refundTransaction.getTxHash(), refundTransaction.getRawTransaction());

		assertEquals(LOCK_TIME, parsedRefund.locktime);
		List<byte[]> refundChunks = BitcoinyScript.extractScriptSigChunks(HashCode.fromString(parsedRefund.inputs.get(0).scriptSig).asBytes());
		assertEquals(3, refundChunks.size());
		assertArrayEquals(redeemScript, refundChunks.get(2));
	}

	@Test
	public void testSplitSaplingBlockHeaders() throws ForeignBlockchainException {
		TestSaplingBitcoiny bitcoiny = new TestSaplingBitcoiny(new MockProvider());
		byte[] firstHeader = saplingHeader(1_700_000_001, 3);
		byte[] secondHeader = saplingHeader(1_700_000_002, 5);
		byte[] rawHeaders = concat(firstHeader, secondHeader);

		List<byte[]> headers = bitcoiny.splitRawBlockHeaders(rawHeaders, 2);

		assertEquals(2, headers.size());
		assertArrayEquals(firstHeader, headers.get(0));
		assertArrayEquals(secondHeader, headers.get(1));
		assertEquals(4 + 32 + 32 + 32, bitcoiny.getBlockHeaderTimestampOffset());
	}

	@Test
	public void testRegisteredKomodoRejectsLegacyBitcoinjTransactionBuilders() {
		RegisteredBitcoiny komodo = new RegisteredBitcoiny(BitcoinyChainSpecs.KOMODO, BitcoinyChainSpecs.KOMODO.getNetwork(BitcoinyChainSpecs.MAIN));
		String recipient = komodo.pkhToAddress(HashCode.fromString("44".repeat(20)).asBytes());

		assertUnsupportedBitcoinjBuilder(() -> komodo.buildSpend(XPRV, recipient, 1L));
		assertUnsupportedBitcoinjBuilder(() -> komodo.buildSpend(XPRV, recipient, 1L, null));
		assertUnsupportedBitcoinjBuilder(() -> komodo.buildSpendMultiple(XPRV, Map.of(recipient, 1L), null));
	}

	private static void assertUnsupportedBitcoinjBuilder(Runnable runnable) {
		try {
			runnable.run();
			fail("Expected legacy bitcoinj transaction builder to be unsupported");
		} catch (UnsupportedOperationException e) {
			assertTrue(e.getMessage().contains(BitcoinyChainSpecs.KOMODO_CURRENCY_CODE));
		}
	}

	private static byte[] saplingHeader(int timestamp, int solutionLength) {
		byte[] header = new byte[4 + 32 + 32 + 32 + 4 + 4 + 32 + 1 + solutionLength];
		header[4 + 32 + 32 + 32] = (byte) timestamp;
		header[4 + 32 + 32 + 32 + 1] = (byte) (timestamp >>> 8);
		header[4 + 32 + 32 + 32 + 2] = (byte) (timestamp >>> 16);
		header[4 + 32 + 32 + 32 + 3] = (byte) (timestamp >>> 24);
		header[4 + 32 + 32 + 32 + 4 + 4 + 32] = (byte) solutionLength;
		Arrays.fill(header, header.length - solutionLength, header.length, (byte) 0xaa);
		return header;
	}

	private static byte[] concat(byte[]... arrays) {
		int length = 0;
		for (byte[] array : arrays)
			length += array.length;

		byte[] result = new byte[length];
		int offset = 0;
		for (byte[] array : arrays) {
			System.arraycopy(array, 0, result, offset, array.length);
			offset += array.length;
		}

		return result;
	}

	private static class TestSaplingBitcoiny extends Bitcoiny {
		private long feeRequired = 10_000L;

		private TestSaplingBitcoiny(MockProvider provider) {
			super(provider, new Context(BitcoinyChainSpecs.KOMODO.getNetwork(BitcoinyChainSpecs.MAIN).getParams()),
					BitcoinyChainSpecs.KOMODO.getNetwork(BitcoinyChainSpecs.MAIN).getParams(),
					BitcoinyChainSpecs.KOMODO_CURRENCY_CODE, Coin.valueOf(10_000L));
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

		@Override
		public BitcoinySignedTransaction buildSpendTransaction(String xprv58, String recipient, long amount, Long feePerByte) {
			return SaplingTransparentTransactionBuilder.buildSpend(this, xprv58, recipient, amount, feePerByte);
		}

		@Override
		public BitcoinySignedTransaction buildHtlcRedeemTransaction(Coin redeemAmount, ECKey redeemKey, List<UnspentOutput> fundingOutputs,
				byte[] redeemScriptBytes, byte[] secret, byte[] receivingAccountInfo) throws ForeignBlockchainException {
			return SaplingTransparentTransactionBuilder.buildRedeem(this, redeemAmount, redeemKey, fundingOutputs,
					redeemScriptBytes, secret, receivingAccountInfo);
		}

		@Override
		public BitcoinySignedTransaction buildHtlcRefundTransaction(Coin refundAmount, ECKey refundKey, List<UnspentOutput> fundingOutputs,
				byte[] redeemScriptBytes, long lockTime, byte[] receivingAccountInfo) throws ForeignBlockchainException {
			return SaplingTransparentTransactionBuilder.buildRefund(this, refundAmount, refundKey, fundingOutputs,
					redeemScriptBytes, lockTime, receivingAccountInfo);
		}

		@Override
		public int getBlockHeaderTimestampOffset() {
			return 4 + 32 + 32 + 32;
		}

		@Override
		public List<byte[]> splitRawBlockHeaders(byte[] rawBlockHeaders, int count) throws ForeignBlockchainException {
			return SaplingTransparentTransactionBuilder.splitBlockHeaders(rawBlockHeaders, count);
		}
	}

	private static class MockProvider extends BitcoinyBlockchainProvider {
		private final Map<String, List<UnspentOutput>> unspentOutputsByAddress = new HashMap<>();
		private final Map<String, List<UnspentOutput>> unspentOutputsByScript = new HashMap<>();
		private final List<byte[]> broadcastTransactions = new ArrayList<>();
		private Bitcoiny blockchain;

		private void addUnspentOutput(String address, UnspentOutput unspentOutput) {
			this.unspentOutputsByAddress.computeIfAbsent(address, key -> new ArrayList<>()).add(unspentOutput);
			if (unspentOutput.script != null)
				this.unspentOutputsByScript.computeIfAbsent(HashCode.fromBytes(unspentOutput.script).toString(), key -> new ArrayList<>()).add(unspentOutput);
		}

		@Override
		public void setBlockchain(Bitcoiny blockchain) {
			this.blockchain = blockchain;
		}

		@Override
		public String getNetId() {
			return "KMD-mock";
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
