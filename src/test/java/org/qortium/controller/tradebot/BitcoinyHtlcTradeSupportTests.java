package org.qortium.controller.tradebot;

import cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock;
import com.google.common.hash.HashCode;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.junit.Before;
import org.junit.Test;
import org.qortium.crosschain.Bitcoiny;
import org.qortium.crosschain.BitcoinyBlockchainProvider;
import org.qortium.crosschain.BitcoinyHTLC;
import org.qortium.crosschain.BitcoinySignedTransaction;
import org.qortium.crosschain.BitcoinyTransaction;
import org.qortium.crosschain.ChainableServer;
import org.qortium.crosschain.ChainableServerConnection;
import org.qortium.crosschain.ForeignBlockchainException;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.TransactionHash;
import org.qortium.crosschain.UnspentOutput;
import org.qortium.crypto.Crypto;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.repository.DataException;
import org.qortium.test.common.Common;
import org.qortium.utils.NTP;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;

public class BitcoinyHtlcTradeSupportTests extends Common {

	private static final byte[] MAKER_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("aa00aa11aa22aa33aa44aa55aa66aa77aa88aa99").asBytes();
	private static final byte[] TAKER_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("bb00bb11bb22bb33bb44bb55bb66bb77bb88bb99").asBytes();
	private static final byte[] MAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("cc00cc11cc22cc33cc44cc55cc66cc77cc88cc99").asBytes();
	private static final byte[] TAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("dd00dd11dd22dd33dd44dd55dd66dd77dd88dd99").asBytes();
	private static final byte[] SECRET = "This string is exactly 32 bytes!".getBytes();
	private static final byte[] HASH_OF_SECRET = Crypto.hash160(SECRET);
	private static final long FOREIGN_AMOUNT = 100_000L;
	private static final long P2SH_FEE = 1_000L;
	private static final int LOCK_TIME = 1_765_000_000;
	private static final int LOCK_TIME_B = LOCK_TIME - 1_801;
	private static final int SAFETY_MARGIN_MINUTES = 30;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testBuildsRedeemScriptAndP2shAddress() {
		MockBitcoiny bitcoiny = new MockBitcoiny(getBitcoinNetworkParameters());
		CrossChainTradeData tradeData = buildTradeData();

		byte[] expectedScript = BitcoinyHTLC.buildScript(MAKER_FOREIGN_PUBLIC_KEY_HASH, LOCK_TIME,
				TAKER_FOREIGN_PUBLIC_KEY_HASH, HASH_OF_SECRET);

		assertArrayEquals(expectedScript, BitcoinyHtlcTradeSupport.buildRedeemScript(tradeData));
		assertEquals(bitcoiny.deriveP2shAddress(expectedScript), BitcoinyHtlcTradeSupport.deriveP2shAddress(bitcoiny, tradeData));
	}

	@Test
	public void testGenericRedeemScriptAndP2shAddressMatchExplicitHtlcScript() {
		MockBitcoiny bitcoiny = new MockBitcoiny(getBitcoinNetworkParameters());

		byte[] expectedScript = BitcoinyHTLC.buildScript(MAKER_FOREIGN_PUBLIC_KEY_HASH, LOCK_TIME,
				TAKER_FOREIGN_PUBLIC_KEY_HASH, HASH_OF_SECRET);

		assertArrayEquals(expectedScript, BitcoinyHtlcTradeSupport.buildRedeemScript(MAKER_FOREIGN_PUBLIC_KEY_HASH,
				LOCK_TIME, TAKER_FOREIGN_PUBLIC_KEY_HASH, HASH_OF_SECRET));
		assertEquals(bitcoiny.deriveP2shAddress(expectedScript), BitcoinyHtlcTradeSupport.deriveP2shAddress(bitcoiny,
				MAKER_FOREIGN_PUBLIC_KEY_HASH, LOCK_TIME, TAKER_FOREIGN_PUBLIC_KEY_HASH, HASH_OF_SECRET));
	}

	@Test
	public void testForeignForeignRoleOrderingUsesFundingPartyAsRefundKey() {
		byte[] makerOfferedHtlcScript = BitcoinyHtlcTradeSupport.buildRedeemScript(MAKER_FOREIGN_PUBLIC_KEY_HASH,
				LOCK_TIME, TAKER_FOREIGN_PUBLIC_KEY_HASH, HASH_OF_SECRET);
		assertArrayEquals(BitcoinyHTLC.buildScript(MAKER_FOREIGN_PUBLIC_KEY_HASH, LOCK_TIME,
				TAKER_FOREIGN_PUBLIC_KEY_HASH, HASH_OF_SECRET), makerOfferedHtlcScript);

		byte[] takerRequestedHtlcScript = BitcoinyHtlcTradeSupport.buildRedeemScript(TAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH,
				LOCK_TIME_B, MAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH, HASH_OF_SECRET);
		assertArrayEquals(BitcoinyHTLC.buildScript(TAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH, LOCK_TIME_B,
				MAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH, HASH_OF_SECRET), takerRequestedHtlcScript);
	}

	@Test
	public void testMinimumFundedAmountIncludesP2shFee() throws ForeignBlockchainException {
		MockBitcoiny bitcoiny = new MockBitcoiny(getBitcoinNetworkParameters());
		bitcoiny.feeRequired = P2SH_FEE;

		assertEquals(FOREIGN_AMOUNT + P2SH_FEE, BitcoinyHtlcTradeSupport.minimumHtlcAmount(bitcoiny, FOREIGN_AMOUNT));
	}

	@Test
	public void testLaterRefundSafetyMargin() {
		int earlierLockTime = 1_000_000;
		int exactMargin = earlierLockTime + SAFETY_MARGIN_MINUTES * 60;

		assertFalse(BitcoinyHtlcTradeSupport.hasLaterRefundSafetyMargin(null, exactMargin + 1, SAFETY_MARGIN_MINUTES));
		assertFalse(BitcoinyHtlcTradeSupport.hasLaterRefundSafetyMargin(earlierLockTime, null, SAFETY_MARGIN_MINUTES));
		assertFalse(BitcoinyHtlcTradeSupport.hasLaterRefundSafetyMargin(earlierLockTime, earlierLockTime, SAFETY_MARGIN_MINUTES));
		assertFalse(BitcoinyHtlcTradeSupport.hasLaterRefundSafetyMargin(earlierLockTime, exactMargin, SAFETY_MARGIN_MINUTES));
		assertTrue(BitcoinyHtlcTradeSupport.hasLaterRefundSafetyMargin(earlierLockTime, exactMargin + 1, SAFETY_MARGIN_MINUTES));
	}

	@Test
	public void testTimeBeforeLockRequiresProtectedWindowAndMargin() {
		long now = 1_765_000_000_000L;
		long protectedWindowSeconds = 60L * 60L;
		int exactLockTime = (int) (now / 1000L + protectedWindowSeconds + SAFETY_MARGIN_MINUTES * 60L);

		assertFalse(BitcoinyHtlcTradeSupport.hasSufficientTimeBeforeLock(now, protectedWindowSeconds, SAFETY_MARGIN_MINUTES, null));
		assertFalse(BitcoinyHtlcTradeSupport.hasSufficientTimeBeforeLock(now, protectedWindowSeconds, SAFETY_MARGIN_MINUTES, exactLockTime));
		assertTrue(BitcoinyHtlcTradeSupport.hasSufficientTimeBeforeLock(now, protectedWindowSeconds, SAFETY_MARGIN_MINUTES, exactLockTime + 1));
	}

	@Test
	public void testStatusResolverHook() throws ForeignBlockchainException {
		BitcoinyHtlcTradeSupport support = new BitcoinyHtlcTradeSupport();
		MockBitcoiny bitcoiny = new MockBitcoiny(getBitcoinNetworkParameters());

		support.setHtlcStatusResolverForTesting((resolvedBitcoiny, p2shAddress, minimumAmount) -> {
			assertSame(bitcoiny, resolvedBitcoiny);
			assertEquals("p2sh-address", p2shAddress);
			assertEquals(FOREIGN_AMOUNT + P2SH_FEE, minimumAmount);
			return BitcoinyHTLC.Status.FUNDED;
		});

		assertEquals(BitcoinyHTLC.Status.FUNDED, support.determineHtlcStatus(bitcoiny, "p2sh-address", FOREIGN_AMOUNT + P2SH_FEE));
	}

	@Test
	public void testSecretResolverHook() throws ForeignBlockchainException {
		BitcoinyHtlcTradeSupport support = new BitcoinyHtlcTradeSupport();
		MockBitcoiny bitcoiny = new MockBitcoiny(getBitcoinNetworkParameters());

		support.setHtlcSecretResolverForTesting((resolvedBitcoiny, p2shAddress) -> {
			assertSame(bitcoiny, resolvedBitcoiny);
			assertEquals("p2sh-address", p2shAddress);
			return SECRET;
		});

		assertArrayEquals(SECRET, support.findHtlcSecret(bitcoiny, "p2sh-address"));
	}

	@Test
	public void testFundIfUnfundedBroadcastsSpendTransaction() throws ForeignBlockchainException {
		BitcoinyHtlcTradeSupport support = new BitcoinyHtlcTradeSupport();
		MockBitcoiny bitcoiny = new MockBitcoiny(getBitcoinNetworkParameters());

		assertTrue(support.fundIfUnfunded(bitcoiny, "xprv", "p2sh-address", FOREIGN_AMOUNT + P2SH_FEE));
		assertEquals(1, bitcoiny.spendTransactionCount);
		assertEquals(1, bitcoiny.broadcastTransactions.size());

		bitcoiny.returnNullSpendTransaction = true;
		assertFalse(support.fundIfUnfunded(bitcoiny, "xprv", "p2sh-address", FOREIGN_AMOUNT + P2SH_FEE));
		assertEquals(2, bitcoiny.spendTransactionCount);
		assertEquals(1, bitcoiny.broadcastTransactions.size());
	}

	@Test
	public void testRedeemIfFundedHandlesFundedAndTerminalStatuses() throws ForeignBlockchainException {
		BitcoinyHtlcTradeSupport support = new BitcoinyHtlcTradeSupport();
		MockBitcoiny bitcoiny = new MockBitcoiny(getBitcoinNetworkParameters());
		byte[] tradePrivateKey = new ECKey().getPrivKeyBytes();
		byte[] redeemScript = BitcoinyHtlcTradeSupport.buildRedeemScript(buildTradeData());

		support.setHtlcStatusResolverForTesting((resolvedBitcoiny, p2shAddress, minimumAmount) -> BitcoinyHTLC.Status.REDEEMED);
		assertTrue(support.redeemIfFunded(bitcoiny, "p2sh-address", FOREIGN_AMOUNT + P2SH_FEE, FOREIGN_AMOUNT,
				tradePrivateKey, redeemScript, SECRET, TAKER_FOREIGN_PUBLIC_KEY_HASH));
		assertEquals(0, bitcoiny.redeemTransactionCount);

		support.setHtlcStatusResolverForTesting((resolvedBitcoiny, p2shAddress, minimumAmount) -> BitcoinyHTLC.Status.FUNDED);
		assertTrue(support.redeemIfFunded(bitcoiny, "p2sh-address", FOREIGN_AMOUNT + P2SH_FEE, FOREIGN_AMOUNT,
				tradePrivateKey, redeemScript, SECRET, TAKER_FOREIGN_PUBLIC_KEY_HASH));
		assertEquals(1, bitcoiny.redeemTransactionCount);
		assertEquals(1, bitcoiny.broadcastTransactions.size());

		support.setHtlcStatusResolverForTesting((resolvedBitcoiny, p2shAddress, minimumAmount) -> BitcoinyHTLC.Status.UNFUNDED);
		assertFalse(support.redeemIfFunded(bitcoiny, "p2sh-address", FOREIGN_AMOUNT + P2SH_FEE, FOREIGN_AMOUNT,
				tradePrivateKey, redeemScript, SECRET, TAKER_FOREIGN_PUBLIC_KEY_HASH));
	}

	@Test
	public void testRefundIfExpiredRequiresWallAndMedianLocktime() throws ForeignBlockchainException {
		BitcoinyHtlcTradeSupport support = new BitcoinyHtlcTradeSupport();
		MockBitcoiny bitcoiny = new MockBitcoiny(getBitcoinNetworkParameters());
		byte[] tradePrivateKey = new ECKey().getPrivKeyBytes();
		byte[] redeemScript = BitcoinyHtlcTradeSupport.buildRedeemScript(buildTradeData());
		support.setHtlcStatusResolverForTesting((resolvedBitcoiny, p2shAddress, minimumAmount) -> BitcoinyHTLC.Status.FUNDED);

		int futureLockTime = (int) (NTP.getTime() / 1000L + 600L);
		bitcoiny.medianBlockTime = futureLockTime + 1;
		assertFalse(support.refundIfExpired(bitcoiny, "p2sh-address", FOREIGN_AMOUNT + P2SH_FEE, FOREIGN_AMOUNT,
				tradePrivateKey, redeemScript, futureLockTime, MAKER_FOREIGN_PUBLIC_KEY_HASH));
		assertEquals(0, bitcoiny.refundTransactionCount);

		int expiredLockTime = (int) (NTP.getTime() / 1000L - 60L);
		bitcoiny.medianBlockTime = expiredLockTime;
		assertFalse(support.refundIfExpired(bitcoiny, "p2sh-address", FOREIGN_AMOUNT + P2SH_FEE, FOREIGN_AMOUNT,
				tradePrivateKey, redeemScript, expiredLockTime, MAKER_FOREIGN_PUBLIC_KEY_HASH));
		assertEquals(0, bitcoiny.refundTransactionCount);

		bitcoiny.medianBlockTime = expiredLockTime + 1;
		assertTrue(support.refundIfExpired(bitcoiny, "p2sh-address", FOREIGN_AMOUNT + P2SH_FEE, FOREIGN_AMOUNT,
				tradePrivateKey, redeemScript, expiredLockTime, MAKER_FOREIGN_PUBLIC_KEY_HASH));
		assertEquals(1, bitcoiny.refundTransactionCount);
		assertEquals(1, bitcoiny.broadcastTransactions.size());
	}

	@Test
	public void testRefundIfExpiredTreatsUnfundedAndRefundedStatusesAsComplete() throws ForeignBlockchainException {
		BitcoinyHtlcTradeSupport support = new BitcoinyHtlcTradeSupport();
		MockBitcoiny bitcoiny = new MockBitcoiny(getBitcoinNetworkParameters());
		byte[] tradePrivateKey = new ECKey().getPrivKeyBytes();
		byte[] redeemScript = BitcoinyHtlcTradeSupport.buildRedeemScript(buildTradeData());

		for (BitcoinyHTLC.Status status : List.of(BitcoinyHTLC.Status.UNFUNDED, BitcoinyHTLC.Status.REFUND_IN_PROGRESS, BitcoinyHTLC.Status.REFUNDED)) {
			support.setHtlcStatusResolverForTesting((resolvedBitcoiny, p2shAddress, minimumAmount) -> status);
			assertTrue(support.refundIfExpired(bitcoiny, "p2sh-address", FOREIGN_AMOUNT + P2SH_FEE, FOREIGN_AMOUNT,
					tradePrivateKey, redeemScript, LOCK_TIME, MAKER_FOREIGN_PUBLIC_KEY_HASH));
		}

		assertEquals(0, bitcoiny.refundTransactionCount);
	}

	private static CrossChainTradeData buildTradeData() {
		CrossChainTradeData tradeData = new CrossChainTradeData();
		tradeData.creatorForeignPKH = MAKER_FOREIGN_PUBLIC_KEY_HASH;
		tradeData.partnerForeignPKH = TAKER_FOREIGN_PUBLIC_KEY_HASH;
		tradeData.hashOfSecretA = HASH_OF_SECRET;
		tradeData.lockTimeA = LOCK_TIME;
		return tradeData;
	}

	private static NetworkParameters getBitcoinNetworkParameters() {
		return ForeignBlockchainRegistry.fromString("BITCOIN").getBitcoinyInstance().getNetworkParameters();
	}

	private static class MockBitcoiny extends Bitcoiny {
		private static final byte[] DUMMY_RAW_TRANSACTION = new byte[] {1, 2, 3, 4};

		private long feeRequired = P2SH_FEE;
		private int transactionCounter;
		private int spendTransactionCount;
		private int redeemTransactionCount;
		private int refundTransactionCount;
		private int medianBlockTime = Integer.MAX_VALUE;
		private boolean returnNullSpendTransaction;
		private final List<BitcoinySignedTransaction> broadcastTransactions = new ArrayList<>();

		private MockBitcoiny(NetworkParameters params) {
			this(params, new MockProvider());
		}

		private MockBitcoiny(NetworkParameters params, MockProvider provider) {
			super(provider, new Context(params), params, "BTC", Coin.valueOf(1_000L));
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
		public int getMedianBlockTime() {
			return this.medianBlockTime;
		}

		@Override
		public BitcoinySignedTransaction buildSpendTransaction(String xprv58, String recipient, long amount, Long feePerByte) {
			++this.spendTransactionCount;
			if (this.returnNullSpendTransaction)
				return null;

			return fakeTransaction("fund");
		}

		@Override
		public BitcoinySignedTransaction buildHtlcRedeemTransaction(Coin redeemAmount, ECKey redeemKey,
				List<UnspentOutput> fundingOutputs, byte[] redeemScriptBytes, byte[] secret, byte[] receivingAccountInfo) {
			++this.redeemTransactionCount;
			return fakeTransaction("redeem");
		}

		@Override
		public BitcoinySignedTransaction buildHtlcRefundTransaction(Coin refundAmount, ECKey refundKey,
				List<UnspentOutput> fundingOutputs, byte[] redeemScriptBytes, long lockTime, byte[] receivingAccountInfo) {
			++this.refundTransactionCount;
			return fakeTransaction("refund");
		}

		@Override
		public List<UnspentOutput> getUnspentOutputs(String base58Address, boolean includeUnconfirmed) {
			return Collections.singletonList(new UnspentOutput(new byte[32], 0, 1, FOREIGN_AMOUNT + this.feeRequired));
		}

		@Override
		public void broadcastTransaction(BitcoinySignedTransaction transaction) {
			this.broadcastTransactions.add(transaction);
		}

		private BitcoinySignedTransaction fakeTransaction(String type) {
			return BitcoinySignedTransaction.fromRawWithTxHash(DUMMY_RAW_TRANSACTION, type + "-" + ++this.transactionCounter);
		}
	}

	private static class MockProvider extends BitcoinyBlockchainProvider {
		@Override
		public void setBlockchain(Bitcoiny blockchain) {
		}

		@Override
		public String getNetId() {
			return "htlc-trade-support-mock";
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
			throw new ForeignBlockchainException.NotFoundException("mock raw transaction not found");
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
			return Collections.emptyList();
		}

		@Override
		public List<UnspentOutput> getUnspentOutputs(byte[] scriptPubKey, boolean includeUnconfirmed) {
			return Collections.emptyList();
		}

		@Override
		public void broadcastTransaction(byte[] rawTransaction) {
		}

		@Override
		public Set<ChainableServer> getServers() {
			return Collections.emptySet();
		}

		@Override
		public Set<ChainableServer> getUselessServers() {
			return Collections.emptySet();
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
