package org.qortal.controller.tradebot;

import cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock;
import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.api.model.crosschain.TradeBotCreateRequest;
import org.qortal.asset.Asset;
import org.qortal.crosschain.AcctMode;
import org.qortal.crosschain.Bitcoiny;
import org.qortal.crosschain.BitcoinyAddress;
import org.qortal.crosschain.BitcoinyBlockchainProvider;
import org.qortal.crosschain.BitcoinyForeignForeignACCTv1;
import org.qortal.crosschain.BitcoinyTransaction;
import org.qortal.crosschain.ChainableServer;
import org.qortal.crosschain.ChainableServerConnection;
import org.qortal.crosschain.ForeignBlockchainException;
import org.qortal.crosschain.ForeignBlockchainRegistry;
import org.qortal.crosschain.TradeDirection;
import org.qortal.crosschain.TransactionHash;
import org.qortal.crosschain.UnspentOutput;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.crosschain.TradeBotData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.transaction.DeployAtTransaction;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Amounts;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;

public class BitcoinyForeignForeignTradeBotTests extends Common {

	private static final String MAKER_OFFERED_KEY = "maker-offered-xprv";
	private static final String TAKER_REQUESTED_KEY = "taker-requested-xprv";
	private static final String INVALID_KEY = "invalid-xprv";

	private static final byte[] MAKER_OFFERED_RECEIVE_HASH = HashCode.fromString("aa00aa11aa22aa33aa44aa55aa66aa77aa88aa99").asBytes();
	private static final byte[] MAKER_REQUESTED_RECEIVE_HASH = HashCode.fromString("bb00bb11bb22bb33bb44bb55bb66bb77bb88bb99").asBytes();
	private static final byte[] TAKER_OFFERED_RECEIVE_HASH = HashCode.fromString("cc00cc11cc22cc33cc44cc55cc66cc77cc88cc99").asBytes();
	private static final byte[] TAKER_REQUESTED_RECEIVE_HASH = HashCode.fromString("dd00dd11dd22dd33dd44dd55dd66dd77dd88dd99").asBytes();
	private static final byte[] MAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("ee00ee11ee22ee33ee44ee55ee66ee77ee88ee99").asBytes();
	private static final byte[] HASH_OF_SECRET_A = HashCode.fromString("ff00ff11ff22ff33ff44ff55ff66ff77ff88ff99").asBytes();

	private static final long OFFERED_FOREIGN_AMOUNT = 100_000L;
	private static final long REQUESTED_FOREIGN_AMOUNT = 250_000L;
	private static final long NATIVE_FEE_RESERVE = 3L * Amounts.MULTIPLIER;
	private static final long TEST_MESSAGE_FEE = Amounts.MULTIPLIER / 100L;
	private static final int TRADE_TIMEOUT = 120;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		BitcoinyForeignForeignTradeBot.getInstance().resetTestHooks();
		BitcoinyForeignForeignTradeBot.getInstance().setMessageFeeOverrideForTesting(TEST_MESSAGE_FEE);
		installMockBitcoinys();
	}

	@After
	public void afterTest() {
		BitcoinyForeignForeignTradeBot.getInstance().resetTestHooks();
	}

	@Test
	public void testDirectMakerCreateSavesForeignForeignState() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount creator = Common.getTestAccount(repository, "chloe");
			TradeBotCreateRequest request = createRequest(creator);

			byte[] unsignedDeployBytes = BitcoinyForeignForeignTradeBot.getInstance().createTrade(repository, request);
			TransactionData transactionData = fromUnsignedBytes(unsignedDeployBytes);

			assertTrue(transactionData instanceof DeployAtTransactionData);
			DeployAtTransactionData deployData = (DeployAtTransactionData) transactionData;
			assertEquals(0L, deployData.getAmount());
			assertEquals(Asset.NATIVE, deployData.getAssetId());
			assertEquals(NATIVE_FEE_RESERVE, deployData.getNativeFeeReserve());
			assertNotNull(deployData.getAtAddress());
			assertNotNull(deployData.getCreationBytes());

			List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();
			assertEquals(1, allTradeBotData.size());

			TradeBotData tradeBotData = allTradeBotData.get(0);
			assertEquals(BitcoinyForeignForeignACCTv1.NAME, tradeBotData.getAcctName());
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_AT_CONFIRM.name(), tradeBotData.getState());
			assertEquals(creator.getAddress(), tradeBotData.getCreatorAddress());
			assertEquals(deployData.getAtAddress(), tradeBotData.getAtAddress());
			assertEquals(Asset.NATIVE, tradeBotData.getLocalAssetId());
			assertEquals(0L, tradeBotData.getLocalAmount());
			assertEquals("BITCOIN", tradeBotData.getForeignBlockchain());
			assertEquals(OFFERED_FOREIGN_AMOUNT, tradeBotData.getForeignAmount());
			assertEquals(MAKER_OFFERED_KEY, tradeBotData.getForeignKey());
			assertEquals("BITCOIN", tradeBotData.getOfferedForeignBlockchain());
			assertEquals(Long.valueOf(OFFERED_FOREIGN_AMOUNT), tradeBotData.getOfferedForeignAmount());
			assertEquals(MAKER_OFFERED_KEY, tradeBotData.getOfferedForeignKey());
			assertEquals("LITECOIN", tradeBotData.getRequestedForeignBlockchain());
			assertEquals(Long.valueOf(REQUESTED_FOREIGN_AMOUNT), tradeBotData.getRequestedForeignAmount());
			assertNull(tradeBotData.getRequestedForeignKey());
			assertArrayEquals(tradeBotData.getTradeForeignPublicKey(), tradeBotData.getOfferedTradeForeignPublicKey());
			assertArrayEquals(tradeBotData.getTradeForeignPublicKey(), tradeBotData.getRequestedTradeForeignPublicKey());
			assertArrayEquals(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getOfferedTradeForeignPublicKeyHash());
			assertArrayEquals(tradeBotData.getTradeForeignPublicKeyHash(), tradeBotData.getRequestedTradeForeignPublicKeyHash());
			assertArrayEquals(Crypto.hash160(tradeBotData.getSecret()), tradeBotData.getHashOfSecret());
			assertArrayEquals(MAKER_OFFERED_RECEIVE_HASH, tradeBotData.getOfferedForeignReceivingAccountInfo());
			assertArrayEquals(MAKER_REQUESTED_RECEIVE_HASH, tradeBotData.getRequestedForeignReceivingAccountInfo());
			assertArrayEquals(MAKER_REQUESTED_RECEIVE_HASH, tradeBotData.getReceivingAccountInfo());

			TradeBotData roundTripped = repository.getCrossChainRepository().getTradeBotData(tradeBotData.getTradePrivateKey());
			assertEquals("BITCOIN", roundTripped.getOfferedForeignBlockchain());
			assertEquals("LITECOIN", roundTripped.getRequestedForeignBlockchain());
			assertArrayEquals(MAKER_REQUESTED_RECEIVE_HASH, roundTripped.getRequestedForeignReceivingAccountInfo());
		}
	}

	@Test
	public void testDirectMakerCreateRejectsInvalidCriteria() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount creator = Common.getTestAccount(repository, "chloe");

			TradeBotCreateRequest sameChain = createRequest(creator);
			sameChain.requestedForeignBlockchain = "BITCOIN";
			assertDataException(() -> BitcoinyForeignForeignTradeBot.getInstance().createTrade(repository, sameChain));

			TradeBotCreateRequest invalidKey = createRequest(creator);
			invalidKey.offeredForeignKey = INVALID_KEY;
			assertDataException(() -> BitcoinyForeignForeignTradeBot.getInstance().createTrade(repository, invalidKey));

			TradeBotCreateRequest invalidAmount = createRequest(creator);
			invalidAmount.offeredForeignAmount = 0L;
			assertDataException(() -> BitcoinyForeignForeignTradeBot.getInstance().createTrade(repository, invalidAmount));

			TradeBotCreateRequest shortTimeout = createRequest(creator);
			shortTimeout.tradeTimeout = BitcoinyForeignForeignTradeBot.MIN_FOREIGN_FOREIGN_TRADE_TIMEOUT_MINUTES - 1;
			assertDataException(() -> BitcoinyForeignForeignTradeBot.getInstance().createTrade(repository, shortTimeout));

			TradeBotCreateRequest invalidReceivingAddress = createRequest(creator);
			invalidReceivingAddress.requestedForeignReceivingAddress = bitcoinAddress(TAKER_OFFERED_RECEIVE_HASH);
			assertDataException(() -> BitcoinyForeignForeignTradeBot.getInstance().createTrade(repository, invalidReceivingAddress));

			assertTrue(repository.getCrossChainRepository().getAllTradeBotData().isEmpty());
		}
	}

	@Test
	public void testDirectTakerReserveSavesForeignForeignState() throws DataException, TransformationException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount makerTradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount responder = Common.getTestAccount(repository, "dilbert");

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, makerTradeAccount);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);

			byte[] unsignedMessageBytes = BitcoinyForeignForeignTradeBot.getInstance().startResponse(repository, atData, tradeData,
					responder.getPublicKey(), TAKER_REQUESTED_KEY, bitcoinAddress(TAKER_OFFERED_RECEIVE_HASH));
			TransactionData transactionData = fromUnsignedBytes(unsignedMessageBytes);

			assertTrue(transactionData instanceof MessageTransactionData);
			MessageTransactionData messageData = (MessageTransactionData) transactionData;
			assertArrayEquals(responder.getPublicKey(), messageData.getSenderPublicKey());
			assertEquals(atAddress, messageData.getRecipient());
			assertEquals(0L, messageData.getAmount());
			assertNull(messageData.getAssetId());
			assertEquals(BitcoinyForeignForeignACCTv1.RESERVE_MESSAGE_LENGTH, messageData.getData().length);

			List<TradeBotData> allTradeBotData = repository.getCrossChainRepository().getAllTradeBotData();
			assertEquals(1, allTradeBotData.size());

			TradeBotData tradeBotData = allTradeBotData.get(0);
			assertEquals(BitcoinyForeignForeignACCTv1.NAME, tradeBotData.getAcctName());
			assertEquals(TradeStates.State.TAKER_WAITING_FOR_FOREIGN_LOCK.name(), tradeBotData.getState());
			assertEquals(atAddress, tradeBotData.getAtAddress());
			assertEquals(Crypto.toAddress(responder.getPublicKey()), tradeBotData.getTradeLocalAddress());
			assertEquals("LITECOIN", tradeBotData.getForeignBlockchain());
			assertEquals(REQUESTED_FOREIGN_AMOUNT, tradeBotData.getForeignAmount());
			assertEquals(TAKER_REQUESTED_KEY, tradeBotData.getForeignKey());
			assertEquals("BITCOIN", tradeBotData.getOfferedForeignBlockchain());
			assertEquals("LITECOIN", tradeBotData.getRequestedForeignBlockchain());
			assertNull(tradeBotData.getOfferedForeignKey());
			assertEquals(TAKER_REQUESTED_KEY, tradeBotData.getRequestedForeignKey());
			assertArrayEquals(tradeBotData.getTradeForeignPublicKey(), tradeBotData.getOfferedTradeForeignPublicKey());
			assertArrayEquals(tradeBotData.getTradeForeignPublicKey(), tradeBotData.getRequestedTradeForeignPublicKey());
			assertArrayEquals(tradeBotData.getTradeForeignPublicKeyHash(), Arrays.copyOfRange(messageData.getData(), 0, 20));
			assertArrayEquals(tradeBotData.getTradeForeignPublicKeyHash(), Arrays.copyOfRange(messageData.getData(), 32, 52));
			assertArrayEquals(TAKER_OFFERED_RECEIVE_HASH, tradeBotData.getOfferedForeignReceivingAccountInfo());
			assertArrayEquals(TAKER_REQUESTED_RECEIVE_HASH, tradeBotData.getRequestedForeignReceivingAccountInfo());
			assertArrayEquals(TAKER_OFFERED_RECEIVE_HASH, tradeBotData.getReceivingAccountInfo());

			TradeBotData roundTripped = repository.getCrossChainRepository().getTradeBotData(tradeBotData.getTradePrivateKey());
			assertEquals("BITCOIN", roundTripped.getOfferedForeignBlockchain());
			assertEquals("LITECOIN", roundTripped.getRequestedForeignBlockchain());
			assertArrayEquals(TAKER_REQUESTED_RECEIVE_HASH, roundTripped.getRequestedForeignReceivingAccountInfo());
		}
	}

	@Test
	public void testDirectTakerReserveRejectsInvalidCriteria() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount makerTradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount responder = Common.getTestAccount(repository, "dilbert");

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, makerTradeAccount);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);

			CrossChainTradeData reservedTradeData = BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);
			reservedTradeData.mode = AcctMode.RESERVED;
			assertDataException(() -> BitcoinyForeignForeignTradeBot.getInstance().startResponse(repository, atData, reservedTradeData,
					responder.getPublicKey(), TAKER_REQUESTED_KEY, bitcoinAddress(TAKER_OFFERED_RECEIVE_HASH)));

			assertDataException(() -> BitcoinyForeignForeignTradeBot.getInstance().startResponse(repository, atData, tradeData,
					responder.getPublicKey(), INVALID_KEY, bitcoinAddress(TAKER_OFFERED_RECEIVE_HASH)));

			assertDataException(() -> BitcoinyForeignForeignTradeBot.getInstance().startResponse(repository, atData, tradeData,
					responder.getPublicKey(), TAKER_REQUESTED_KEY, litecoinAddress(TAKER_REQUESTED_RECEIVE_HASH)));

			assertTrue(repository.getCrossChainRepository().getAllTradeBotData().isEmpty());
		}
	}

	private static TradeBotCreateRequest createRequest(PrivateKeyAccount creator) {
		TradeBotCreateRequest request = new TradeBotCreateRequest();
		request.creatorPublicKey = creator.getPublicKey();
		request.tradeDirection = TradeDirection.SELL_FOREIGN_FOR_FOREIGN;
		request.localAssetId = Asset.NATIVE;
		request.localAmount = 0L;
		request.fundingLocalAmount = 0L;
		request.nativeFeeReserve = NATIVE_FEE_RESERVE;
		request.offeredForeignBlockchain = "BITCOIN";
		request.offeredForeignAmount = OFFERED_FOREIGN_AMOUNT;
		request.offeredForeignKey = MAKER_OFFERED_KEY;
		request.requestedForeignBlockchain = "LITECOIN";
		request.requestedForeignAmount = REQUESTED_FOREIGN_AMOUNT;
		request.requestedForeignReceivingAddress = litecoinAddress(MAKER_REQUESTED_RECEIVE_HASH);
		request.tradeTimeout = TRADE_TIMEOUT;
		return request;
	}

	private static DeployAtTransaction deploy(Repository repository, PrivateKeyAccount deployer, PrivateKeyAccount tradeAccount)
			throws DataException {
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");
		ForeignBlockchainRegistry.Entry litecoin = ForeignBlockchainRegistry.fromString("LITECOIN");
		byte[] makerOfferedForeignPublicKeyHash = Crypto.hash160(TradeBot.deriveTradeForeignPublicKey(tradeAccount.getPrivateKey()));
		byte[] creationBytes = BitcoinyForeignForeignACCTv1.buildTradeAT(bitcoin, litecoin, tradeAccount.getAddress(),
				makerOfferedForeignPublicKeyHash, MAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH, HASH_OF_SECRET_A,
				OFFERED_FOREIGN_AMOUNT, REQUESTED_FOREIGN_AMOUNT, TRADE_TIMEOUT);

		long txTimestamp = TransactionUtils.nextTimestamp(repository);
		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, deployer.getPublicKey(), null, null);
		TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData,
				"BTC-LTC foreign/foreign trade", "Bitcoin-Litecoin foreign/foreign cross-chain trade", "ACCT",
				"BTC-LTC foreign/foreign ACCT", creationBytes, 0L, Asset.NATIVE, NATIVE_FEE_RESERVE);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		deployAtTransactionData.setFee(deployAtTransaction.calcRecommendedFee());
		TransactionUtils.signAndMint(repository, deployAtTransactionData, deployer);

		return deployAtTransaction;
	}

	private static void installMockBitcoinys() {
		MockBitcoiny bitcoin = new MockBitcoiny(bitcoinyParams("BITCOIN"), "BTC", MAKER_OFFERED_RECEIVE_HASH, MAKER_OFFERED_KEY);
		MockBitcoiny litecoin = new MockBitcoiny(bitcoinyParams("LITECOIN"), "LTC", TAKER_REQUESTED_RECEIVE_HASH, TAKER_REQUESTED_KEY);

		BitcoinyForeignForeignTradeBot.getInstance().setBitcoinyResolverForTesting(blockchain -> {
			ForeignBlockchainRegistry.Entry entry = ForeignBlockchainRegistry.fromString(blockchain);
			if (entry == null)
				return null;

			if ("BITCOIN".equals(entry.name()))
				return bitcoin;

			if ("LITECOIN".equals(entry.name()))
				return litecoin;

			return null;
		});
	}

	private static NetworkParameters bitcoinyParams(String blockchain) {
		return ForeignBlockchainRegistry.fromString(blockchain).getBitcoinyInstance().getNetworkParameters();
	}

	private static String bitcoinAddress(byte[] publicKeyHash) {
		return BitcoinyAddress.fromPubKeyHash(bitcoinyParams("BITCOIN"), publicKeyHash).toString();
	}

	private static String litecoinAddress(byte[] publicKeyHash) {
		return BitcoinyAddress.fromPubKeyHash(bitcoinyParams("LITECOIN"), publicKeyHash).toString();
	}

	private static TransactionData fromUnsignedBytes(byte[] unsignedBytes) throws TransformationException {
		return TransactionTransformer.fromBytes(Bytes.concat(unsignedBytes, new byte[TransactionTransformer.SIGNATURE_LENGTH]));
	}

	private static void assertDataException(ThrowingRunnable runnable) {
		try {
			runnable.run();
			fail("Expected DataException");
		} catch (DataException e) {
			// Expected
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws DataException;
	}

	private static class MockBitcoiny extends Bitcoiny {
		private final byte[] receivePublicKeyHash;
		private final Set<String> validWalletKeys;
		private long feeRequired = 1_000L;

		private MockBitcoiny(NetworkParameters params, String currencyCode, byte[] receivePublicKeyHash, String... validWalletKeys) {
			this(params, currencyCode, receivePublicKeyHash, new MockProvider(), validWalletKeys);
		}

		private MockBitcoiny(NetworkParameters params, String currencyCode, byte[] receivePublicKeyHash, MockProvider provider,
				String... validWalletKeys) {
			super(provider, new Context(params), currencyCode, Coin.valueOf(1_000L));
			this.receivePublicKeyHash = receivePublicKeyHash;
			this.validWalletKeys = new HashSet<>(Arrays.asList(validWalletKeys));
			provider.setBlockchain(this);
		}

		@Override
		public long getFeeRequired() {
			return this.feeRequired;
		}

		@Override
		public long getP2shFee(Long timestamp) {
			return this.feeRequired;
		}

		@Override
		public boolean isValidWalletKey(String walletKey) {
			return this.validWalletKeys.contains(walletKey);
		}

		@Override
		public void setFeeRequired(long fee) {
			this.feeRequired = fee;
		}

		@Override
		public String getUnusedReceiveAddress(String key58) {
			return BitcoinyAddress.fromPubKeyHash(this.getNetworkParameters(), this.receivePublicKeyHash).toString();
		}
	}

	private static class MockProvider extends BitcoinyBlockchainProvider {
		@Override
		public void setBlockchain(Bitcoiny blockchain) {
		}

		@Override
		public String getNetId() {
			return "foreign-foreign-tradebot-mock";
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
