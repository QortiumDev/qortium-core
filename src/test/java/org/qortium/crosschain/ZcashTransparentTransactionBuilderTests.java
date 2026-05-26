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

public class ZcashTransparentTransactionBuilderTests {

	private static final String XPRV = "xprv9z8QpS7vxwMC2fCnG1oZc6c4aFRLgsqSF86yWrJBKEzMY3T3ySCo85x8Uv5FxTavAQwgEDy1g3iLRT5kdtFjoNNBKukLTMzKwCUn1Abwoxg";
	private static final byte[] SECRET = "This string is exactly 32 bytes!".getBytes();
	private static final int LOCK_TIME = 1_700_000_000;
	private static final int SIGHASH_ALL = 0x01;

	@BeforeClass
	public static void beforeClass() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testBuildSpendUsesZip225V5Format() throws ForeignBlockchainException, TransformationException {
		MockProvider provider = new MockProvider();
		TestZcashBitcoiny bitcoiny = new TestZcashBitcoiny(provider);
		BitcoinyDeterministicKey receiveKey = BitcoinyDeterministicKeyChain.fromBase58(bitcoiny.getNetworkParameters(), XPRV).getReceiveKey(0);
		String walletAddress = bitcoiny.pkhToAddress(receiveKey.getPublicKeyHash());
		byte[] walletScript = BitcoinyScript.p2pkhScript(receiveKey.getPublicKeyHash());
		provider.addUnspentOutput(walletAddress, new UnspentOutput(HashCode.fromString("11".repeat(32)).asBytes(), 0, 10,
				100_000_000L, walletScript, walletAddress));

		String recipient = bitcoiny.pkhToAddress(HashCode.fromString("22".repeat(Bitcoiny.HASH160_LENGTH)).asBytes());
		BitcoinySignedTransaction signedTransaction = bitcoiny.buildSpendTransaction(XPRV, recipient, 10_000L, 10L);

		assertNotNull(signedTransaction);
		assertFalse(signedTransaction.getTxHash().isEmpty());
		byte[] rawTransaction = signedTransaction.getRawTransaction();
		assertEquals("050000800a27a726f04dec4d", HashCode.fromBytes(java.util.Arrays.copyOf(rawTransaction, 12)).toString());
		assertEquals("000000", HashCode.fromBytes(java.util.Arrays.copyOfRange(rawTransaction, rawTransaction.length - 3, rawTransaction.length)).toString());

		BitcoinyTransaction parsedTransaction = ZcashFamilyTransactionParser.deserializeRawTransaction(signedTransaction.getTxHash(), rawTransaction);
		assertEquals(signedTransaction.getTxHash(), parsedTransaction.txHash);
		assertEquals(1, parsedTransaction.inputs.size());
		assertEquals(2, parsedTransaction.outputs.size());
		assertEquals(0, parsedTransaction.locktime);
		assertTrue(parsedTransaction.outputs.stream().anyMatch(output -> output.value == 10_000L));

		List<byte[]> chunks = BitcoinyScript.extractScriptSigChunks(HashCode.fromString(parsedTransaction.inputs.get(0).scriptSig).asBytes());
		assertEquals(2, chunks.size());
		assertEquals((byte) SIGHASH_ALL, chunks.get(0)[chunks.get(0).length - 1]);
		assertEquals(33, chunks.get(1).length);
	}

	@Test
	public void testBuildHtlcRedeemAndRefundUseZip225V5Format() throws ForeignBlockchainException, TransformationException {
		MockProvider provider = new MockProvider();
		TestZcashBitcoiny bitcoiny = new TestZcashBitcoiny(provider);
		ECKey refundKey = ECKey.fromPrivate(HashCode.fromString("11".repeat(32)).asBytes());
		ECKey redeemKey = ECKey.fromPrivate(HashCode.fromString("22".repeat(32)).asBytes());
		byte[] redeemScript = BitcoinyHTLC.buildScript(refundKey.getPubKeyHash(), LOCK_TIME, redeemKey.getPubKeyHash(), Crypto.hash160(SECRET));
		List<UnspentOutput> fundingOutputs = Collections.singletonList(
				new UnspentOutput(HashCode.fromString("33".repeat(32)).asBytes(), 1, 20, 20_000L,
						BitcoinyScript.p2shScript(Crypto.hash160(redeemScript)), null));

		BitcoinySignedTransaction redeemTransaction = bitcoiny.buildHtlcRedeemTransaction(Coin.valueOf(19_000L), redeemKey,
				fundingOutputs, redeemScript, SECRET, redeemKey.getPubKeyHash());
		BitcoinyTransaction parsedRedeem = ZcashFamilyTransactionParser.deserializeRawTransaction(redeemTransaction.getTxHash(), redeemTransaction.getRawTransaction());

		assertEquals("050000800a27a726f04dec4d", HashCode.fromBytes(java.util.Arrays.copyOf(redeemTransaction.getRawTransaction(), 12)).toString());
		assertEquals(redeemTransaction.getTxHash(), parsedRedeem.txHash);
		assertEquals(1, parsedRedeem.inputs.size());
		List<byte[]> redeemChunks = BitcoinyScript.extractScriptSigChunks(HashCode.fromString(parsedRedeem.inputs.get(0).scriptSig).asBytes());
		assertEquals(4, redeemChunks.size());
		assertArrayEquals(SECRET, redeemChunks.get(0));
		assertEquals((byte) SIGHASH_ALL, redeemChunks.get(1)[redeemChunks.get(1).length - 1]);
		assertArrayEquals(redeemScript, redeemChunks.get(3));

		BitcoinySignedTransaction refundTransaction = bitcoiny.buildHtlcRefundTransaction(Coin.valueOf(19_000L), refundKey,
				fundingOutputs, redeemScript, LOCK_TIME, refundKey.getPubKeyHash());
		BitcoinyTransaction parsedRefund = ZcashFamilyTransactionParser.deserializeRawTransaction(refundTransaction.getTxHash(), refundTransaction.getRawTransaction());

		assertEquals("050000800a27a726f04dec4d", HashCode.fromBytes(java.util.Arrays.copyOf(refundTransaction.getRawTransaction(), 12)).toString());
		assertEquals(refundTransaction.getTxHash(), parsedRefund.txHash);
		assertEquals(LOCK_TIME, parsedRefund.locktime);
		List<byte[]> refundChunks = BitcoinyScript.extractScriptSigChunks(HashCode.fromString(parsedRefund.inputs.get(0).scriptSig).asBytes());
		assertEquals(3, refundChunks.size());
		assertEquals((byte) SIGHASH_ALL, refundChunks.get(0)[refundChunks.get(0).length - 1]);
		assertArrayEquals(redeemScript, refundChunks.get(2));
	}

	@Test
	public void testConsensusBranchIdBoundaries() {
		assertEquals(0, ZcashTransparentTransactionBuilder.consensusBranchId(419_199));
		assertEquals(0x76B809BB, ZcashTransparentTransactionBuilder.consensusBranchId(419_200));
		assertEquals(0x2BB40E60, ZcashTransparentTransactionBuilder.consensusBranchId(653_600));
		assertEquals(0xF5B9230B, ZcashTransparentTransactionBuilder.consensusBranchId(903_000));
		assertEquals(0xE9FF75A6, ZcashTransparentTransactionBuilder.consensusBranchId(1_046_400));
		assertEquals(0xC2D6D0B4, ZcashTransparentTransactionBuilder.consensusBranchId(1_687_104));
		assertEquals(0xC8E71055, ZcashTransparentTransactionBuilder.consensusBranchId(2_726_400));
		assertEquals(0x4DEC4DF0, ZcashTransparentTransactionBuilder.consensusBranchId(3_146_400));
	}

	@Test
	public void testRegisteredZcashRejectsLegacyBitcoinjTransactionBuilders() {
		RegisteredBitcoiny zcash = new RegisteredBitcoiny(BitcoinyChainSpecs.ZCASH, BitcoinyChainSpecs.ZCASH.getNetwork(BitcoinyChainSpecs.MAIN));
		String recipient = zcash.pkhToAddress(HashCode.fromString("44".repeat(Bitcoiny.HASH160_LENGTH)).asBytes());

		assertUnsupportedBitcoinjBuilder(() -> zcash.buildSpend(XPRV, recipient, 1L));
		assertUnsupportedBitcoinjBuilder(() -> zcash.buildSpend(XPRV, recipient, 1L, null));
		assertUnsupportedBitcoinjBuilder(() -> zcash.buildSpendMultiple(XPRV, Map.of(recipient, 1L), null));
	}

	private static void assertUnsupportedBitcoinjBuilder(Runnable runnable) {
		try {
			runnable.run();
			fail("Expected legacy bitcoinj transaction builder to be unsupported");
		} catch (UnsupportedOperationException e) {
			assertTrue(e.getMessage().contains(BitcoinyChainSpecs.ZCASH_CURRENCY_CODE));
		}
	}

	private static class TestZcashBitcoiny extends Bitcoiny {
		private long feeRequired = 10_000L;

		private TestZcashBitcoiny(MockProvider provider) {
			super(provider, new Context(BitcoinyChainSpecs.ZCASH.getNetwork(BitcoinyChainSpecs.MAIN).getParams()),
					BitcoinyChainSpecs.ZCASH.getNetwork(BitcoinyChainSpecs.MAIN).getParams(),
					BitcoinyChainSpecs.ZCASH_CURRENCY_CODE, Coin.valueOf(10_000L));
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
			return ZcashTransparentTransactionBuilder.buildSpend(this, xprv58, recipient, amount, feePerByte);
		}

		@Override
		public BitcoinySignedTransaction buildHtlcRedeemTransaction(Coin redeemAmount, ECKey redeemKey, List<UnspentOutput> fundingOutputs,
				byte[] redeemScriptBytes, byte[] secret, byte[] receivingAccountInfo) throws ForeignBlockchainException {
			return ZcashTransparentTransactionBuilder.buildRedeem(this, redeemAmount, redeemKey, fundingOutputs,
					redeemScriptBytes, secret, receivingAccountInfo);
		}

		@Override
		public BitcoinySignedTransaction buildHtlcRefundTransaction(Coin refundAmount, ECKey refundKey, List<UnspentOutput> fundingOutputs,
				byte[] redeemScriptBytes, long lockTime, byte[] receivingAccountInfo) throws ForeignBlockchainException {
			return ZcashTransparentTransactionBuilder.buildRefund(this, refundAmount, refundKey, fundingOutputs,
					redeemScriptBytes, lockTime, receivingAccountInfo);
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
			return "ZEC-mock";
		}

		@Override
		public int getCurrentHeight() {
			return 3_200_000;
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
