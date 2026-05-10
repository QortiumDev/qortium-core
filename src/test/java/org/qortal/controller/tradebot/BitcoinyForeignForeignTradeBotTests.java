package org.qortal.controller.tradebot;

import cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock;
import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.ECKey;
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
import org.qortal.crosschain.BitcoinyHTLC;
import org.qortal.crosschain.BitcoinySignedTransaction;
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
import org.qortal.transaction.MessageTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;
import org.qortal.utils.Amounts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
	private static final byte[] INVALID_SECRET = HashCode.fromString("11223344556677889900aabbccddeeff00112233445566778899aabbccddeeff").asBytes();

	private static final long OFFERED_FOREIGN_AMOUNT = 100_000L;
	private static final long REQUESTED_FOREIGN_AMOUNT = 250_000L;
	private static final long NATIVE_FEE_RESERVE = 3L * Amounts.MULTIPLIER;
	private static final long TEST_MESSAGE_FEE = Amounts.MULTIPLIER / 100L;
	private static final int TRADE_TIMEOUT = 120;

	private MockBitcoiny bitcoin;
	private MockBitcoiny litecoin;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
		BitcoinyForeignForeignTradeBot.getInstance().resetTestHooks();
		BitcoinyForeignForeignTradeBot.getInstance().setMessageFeeOverrideForTesting(TEST_MESSAGE_FEE);
		BitcoinyForeignForeignTradeBot.getInstance().setMessageSubmitterForTesting((repository, messageTransaction, sender) -> {
			messageTransaction.getTransactionData().setFee(TEST_MESSAGE_FEE);
			TransactionUtils.signAndMint(repository, messageTransaction.getTransactionData(), sender);
			return Transaction.ValidationResult.OK;
		});
		installMockBitcoinys();
		setMockHtlcStatus(BitcoinyHTLC.Status.UNFUNDED);
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

	@Test
	public void testMakerProgressConfirmsAt() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = createAndDeployMakerTrade(repository);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);

			TradeBotData makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_TAKER_MESSAGE.name(), makerTradeBotData.getState());
		}
	}

	@Test
	public void testMakerProgressExpiresUndeployedAt() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount creator = Common.getTestAccount(repository, "chloe");
			BitcoinyForeignForeignTradeBot.getInstance().createTrade(repository, createRequest(creator));
			TradeBotData makerTradeBotData = repository.getCrossChainRepository().getAllTradeBotData().get(0);
			makerTradeBotData.setTimestamp(System.currentTimeMillis() - 25L * 60L * 60L * 1000L);
			repository.getCrossChainRepository().save(makerTradeBotData);
			repository.saveChanges();

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, makerTradeBotData);

			assertTrue(repository.getCrossChainRepository().getAllTradeBotData().isEmpty());
			assertEquals(TradeStates.State.MAKER_REFUNDED.name(), makerTradeBotData.getState());
		}
	}

	@Test
	public void testMakerProgressFundsOfferedForeignHtlc() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupReservedMakerTrade(repository);
			setMockHtlcStatus(BitcoinyHTLC.Status.UNFUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);

			TradeBotData makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);
			assertEquals(1, this.bitcoin.spendTransactionCount);
			assertEquals(1, this.bitcoin.broadcastTransactions.size());
			assertNotNull(makerTradeBotData.getLockTimeA());
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_TAKER_MESSAGE.name(), makerTradeBotData.getState());
		}
	}

	@Test
	public void testMakerProgressDoesNotRebroadcastWhileFundingInProgress() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupReservedMakerTrade(repository);
			setMockHtlcStatus(BitcoinyHTLC.Status.FUNDING_IN_PROGRESS);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);

			TradeBotData makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);
			assertEquals(0, this.bitcoin.spendTransactionCount);
			assertEquals(0, this.bitcoin.broadcastTransactions.size());
			assertNotNull(makerTradeBotData.getLockTimeA());
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_TAKER_MESSAGE.name(), makerTradeBotData.getState());
		}
	}

	@Test
	public void testMakerProgressDeclaresFundedOfferedForeignHtlc() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupReservedMakerTrade(repository);
			setMockHtlcStatus(BitcoinyHTLC.Status.FUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);

			TradeBotData makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);
			assertEquals(0, this.bitcoin.spendTransactionCount);
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_TAKER_HTLC.name(), makerTradeBotData.getState());

			CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
			assertEquals(AcctMode.FOREIGN_LOCKED, tradeData.mode);
			assertEquals(makerTradeBotData.getLockTimeA(), tradeData.lockTimeA);
		}
	}

	@Test
	public void testMakerProgressCancelsStaleUnfundedReservation() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupReservedMakerTrade(repository);
			int lockTimeA = (int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L);
			setup.makerTradeBotData.setLockTimeA(lockTimeA);
			setup.makerTradeBotData.setTimestamp(System.currentTimeMillis()
					- (BitcoinyForeignForeignTradeBot.RESERVATION_TIMEOUT_MINUTES + 1L) * 60L * 1000L);
			repository.getCrossChainRepository().save(setup.makerTradeBotData);
			repository.saveChanges();
			setMockHtlcStatus(BitcoinyHTLC.Status.UNFUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);

			ATData atData = repository.getATRepository().fromATAddress(setup.atAddress);
			CrossChainTradeData tradeData = BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);
			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.CANCELLED, tradeData.mode);
			assertEquals(0, this.bitcoin.spendTransactionCount);
		}
	}

	@Test
	public void testMakerProgressCancelsUnsafeMakerLocktime() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupReservedMakerTrade(repository);
			int unsafeLockTimeA = (int) (System.currentTimeMillis() / 1000L
					+ BitcoinyForeignForeignTradeBot.FOREIGN_LOCKTIME_SAFETY_MARGIN_MINUTES * 60L);
			setup.makerTradeBotData.setLockTimeA(unsafeLockTimeA);
			repository.getCrossChainRepository().save(setup.makerTradeBotData);
			repository.saveChanges();
			setMockHtlcStatus(BitcoinyHTLC.Status.FUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);

			ATData atData = repository.getATRepository().fromATAddress(setup.atAddress);
			CrossChainTradeData tradeData = BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);
			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.CANCELLED, tradeData.mode);
		}
	}

	@Test
	public void testTakerProgressWaitsForMakerOfferedForeignHtlc() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupMakerDeclaredTrade(repository);
			CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
			String makerOfferedP2sh = deriveMakerOfferedP2shAddress(tradeData);
			setMockHtlcStatuses(Map.of(makerOfferedP2sh, BitcoinyHTLC.Status.UNFUNDED), BitcoinyHTLC.Status.UNFUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.takerTradeBotData);

			TradeBotData takerTradeBotData = getTradeBotData(repository, setup.takerTradeBotData);
			assertEquals(0, this.litecoin.spendTransactionCount);
			assertNull(takerTradeBotData.getLockTimeB());
			assertEquals(TradeStates.State.TAKER_WAITING_FOR_FOREIGN_LOCK.name(), takerTradeBotData.getState());
		}
	}

	@Test
	public void testTakerProgressFundsRequestedForeignHtlc() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupMakerDeclaredTrade(repository);
			CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
			int lockTimeB = BitcoinyForeignForeignTradeBot.calcTakerLockTime(tradeData.lockTimeA);
			String makerOfferedP2sh = deriveMakerOfferedP2shAddress(tradeData);
			String takerRequestedP2sh = deriveTakerRequestedP2shAddress(tradeData, lockTimeB);
			setMockHtlcStatuses(Map.of(
					makerOfferedP2sh, BitcoinyHTLC.Status.FUNDED,
					takerRequestedP2sh, BitcoinyHTLC.Status.UNFUNDED), BitcoinyHTLC.Status.UNFUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.takerTradeBotData);

			TradeBotData takerTradeBotData = getTradeBotData(repository, setup.takerTradeBotData);
			assertEquals(1, this.litecoin.spendTransactionCount);
			assertEquals(1, this.litecoin.broadcastTransactions.size());
			assertEquals(Integer.valueOf(tradeData.lockTimeA), takerTradeBotData.getLockTimeA());
			assertEquals(Integer.valueOf(lockTimeB), takerTradeBotData.getLockTimeB());
			assertEquals(TradeStates.State.TAKER_WAITING_FOR_FOREIGN_LOCK.name(), takerTradeBotData.getState());

			CrossChainTradeData updatedTradeData = getTradeData(repository, setup.atAddress);
			assertEquals(AcctMode.FOREIGN_LOCKED, updatedTradeData.mode);
		}
	}

	@Test
	public void testTakerProgressDoesNotRebroadcastRequestedFundingInProgress() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupMakerDeclaredTrade(repository);
			CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
			int lockTimeB = BitcoinyForeignForeignTradeBot.calcTakerLockTime(tradeData.lockTimeA);
			String makerOfferedP2sh = deriveMakerOfferedP2shAddress(tradeData);
			String takerRequestedP2sh = deriveTakerRequestedP2shAddress(tradeData, lockTimeB);
			setMockHtlcStatuses(Map.of(
					makerOfferedP2sh, BitcoinyHTLC.Status.FUNDED,
					takerRequestedP2sh, BitcoinyHTLC.Status.FUNDING_IN_PROGRESS), BitcoinyHTLC.Status.UNFUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.takerTradeBotData);

			TradeBotData takerTradeBotData = getTradeBotData(repository, setup.takerTradeBotData);
			assertEquals(0, this.litecoin.spendTransactionCount);
			assertEquals(0, this.litecoin.broadcastTransactions.size());
			assertEquals(Integer.valueOf(lockTimeB), takerTradeBotData.getLockTimeB());
			assertEquals(TradeStates.State.TAKER_WAITING_FOR_FOREIGN_LOCK.name(), takerTradeBotData.getState());
		}
	}

	@Test
	public void testTakerProgressDeclaresFundedRequestedForeignHtlc() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupMakerDeclaredTrade(repository);
			CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
			int lockTimeB = BitcoinyForeignForeignTradeBot.calcTakerLockTime(tradeData.lockTimeA);
			String makerOfferedP2sh = deriveMakerOfferedP2shAddress(tradeData);
			String takerRequestedP2sh = deriveTakerRequestedP2shAddress(tradeData, lockTimeB);
			setMockHtlcStatuses(Map.of(
					makerOfferedP2sh, BitcoinyHTLC.Status.FUNDED,
					takerRequestedP2sh, BitcoinyHTLC.Status.FUNDED), BitcoinyHTLC.Status.UNFUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.takerTradeBotData);

			TradeBotData takerTradeBotData = getTradeBotData(repository, setup.takerTradeBotData);
			assertEquals(0, this.litecoin.spendTransactionCount);
			assertEquals(TradeStates.State.TAKER_WAITING_FOR_MAKER_REDEEM.name(), takerTradeBotData.getState());

			CrossChainTradeData updatedTradeData = getTradeData(repository, setup.atAddress);
			assertEquals(AcctMode.TRADING, updatedTradeData.mode);
			assertEquals(Integer.valueOf(lockTimeB), updatedTradeData.lockTimeB);
		}
	}

	@Test
	public void testTakerProgressSkipsUnsafeTakerLocktime() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupReservedMakerTrade(repository);
			int unsafeLockTimeA = (int) (System.currentTimeMillis() / 1000L
					+ (BitcoinyForeignForeignTradeBot.FOREIGN_LOCKTIME_SAFETY_MARGIN_MINUTES + 10L) * 60L);
			byte[] messageData = BitcoinyForeignForeignACCTv1.buildMakerHtlcMessage(unsafeLockTimeA);
			PrivateKeyAccount makerTradeAccount = new PrivateKeyAccount(repository, setup.makerTradeBotData.getTradePrivateKey());
			MessageTransaction messageTransaction = MessageTransaction.build(repository, makerTradeAccount, Group.NO_GROUP,
					setup.atAddress, messageData, false, false);
			messageTransaction.getTransactionData().setFee(TEST_MESSAGE_FEE);
			TransactionUtils.signAndMint(repository, messageTransaction.getTransactionData(), makerTradeAccount);

			setup.takerTradeBotData = getTakerTradeBotData(repository, setup.atAddress);
			CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
			String makerOfferedP2sh = deriveMakerOfferedP2shAddress(tradeData);
			setMockHtlcStatuses(Map.of(makerOfferedP2sh, BitcoinyHTLC.Status.FUNDED), BitcoinyHTLC.Status.FUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.takerTradeBotData);

			TradeBotData takerTradeBotData = getTradeBotData(repository, setup.takerTradeBotData);
			assertEquals(0, this.litecoin.spendTransactionCount);
			assertNull(takerTradeBotData.getLockTimeB());
			assertEquals(TradeStates.State.TAKER_WAITING_FOR_FOREIGN_LOCK.name(), takerTradeBotData.getState());
		}
	}

	@Test
	public void testMakerProgressWaitsForTakerRequestedForeignHtlc() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupTradingMakerTrade(repository);
			CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
			String takerRequestedP2sh = deriveTakerRequestedP2shAddress(tradeData, tradeData.lockTimeB);
			setMockHtlcStatuses(Map.of(takerRequestedP2sh, BitcoinyHTLC.Status.UNFUNDED), BitcoinyHTLC.Status.UNFUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);

			TradeBotData makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);
			assertEquals(0, this.litecoin.redeemTransactionCount);
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_TAKER_HTLC.name(), makerTradeBotData.getState());

			CrossChainTradeData updatedTradeData = getTradeData(repository, setup.atAddress);
			assertEquals(AcctMode.TRADING, updatedTradeData.mode);
		}
	}

	@Test
	public void testMakerProgressRedeemsRequestedForeignHtlc() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupTradingMakerTrade(repository);
			CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
			String takerRequestedP2sh = deriveTakerRequestedP2shAddress(tradeData, tradeData.lockTimeB);
			setMockHtlcStatuses(Map.of(takerRequestedP2sh, BitcoinyHTLC.Status.FUNDED), BitcoinyHTLC.Status.UNFUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);

			TradeBotData makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);
			assertEquals(1, this.litecoin.redeemTransactionCount);
			assertEquals(1, this.litecoin.broadcastTransactions.size());
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_AT_REDEEM.name(), makerTradeBotData.getState());

			CrossChainTradeData updatedTradeData = getTradeData(repository, setup.atAddress);
			assertEquals(AcctMode.TRADING, updatedTradeData.mode);
		}
	}

	@Test
	public void testMakerProgressDoesNotRebroadcastRequestedRedeemInProgress() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupTradingMakerTrade(repository);
			CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
			String takerRequestedP2sh = deriveTakerRequestedP2shAddress(tradeData, tradeData.lockTimeB);
			setMockHtlcStatuses(Map.of(takerRequestedP2sh, BitcoinyHTLC.Status.REDEEM_IN_PROGRESS), BitcoinyHTLC.Status.UNFUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);

			TradeBotData makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);
			assertEquals(0, this.litecoin.redeemTransactionCount);
			assertEquals(0, this.litecoin.broadcastTransactions.size());
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_TAKER_HTLC.name(), makerTradeBotData.getState());
		}
	}

	@Test
	public void testMakerProgressRevealsConfirmedRequestedRedeem() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupTradingMakerTrade(repository);
			CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
			String takerRequestedP2sh = deriveTakerRequestedP2shAddress(tradeData, tradeData.lockTimeB);
			setMockHtlcStatuses(Map.of(takerRequestedP2sh, BitcoinyHTLC.Status.REDEEMED), BitcoinyHTLC.Status.UNFUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);

			TradeBotData makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);
			assertEquals(0, this.litecoin.redeemTransactionCount);
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_AT_REDEEM.name(), makerTradeBotData.getState());

			ATData atData = repository.getATRepository().fromATAddress(setup.atAddress);
			CrossChainTradeData updatedTradeData = BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);
			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.REDEEMED, updatedTradeData.mode);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, makerTradeBotData);

			makerTradeBotData = getTradeBotData(repository, makerTradeBotData);
			assertEquals(TradeStates.State.MAKER_DONE.name(), makerTradeBotData.getState());
		}
	}

	@Test
	public void testMakerProgressWaitsBeforeSecretRevealUntilRequestedRedeemConfirmed() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupTradingMakerTrade(repository);
			CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
			String takerRequestedP2sh = deriveTakerRequestedP2shAddress(tradeData, tradeData.lockTimeB);
			setMockHtlcStatuses(Map.of(takerRequestedP2sh, BitcoinyHTLC.Status.FUNDED), BitcoinyHTLC.Status.UNFUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);
			TradeBotData makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_AT_REDEEM.name(), makerTradeBotData.getState());

			setMockHtlcStatuses(Map.of(takerRequestedP2sh, BitcoinyHTLC.Status.REDEEM_IN_PROGRESS), BitcoinyHTLC.Status.UNFUNDED);
			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, makerTradeBotData);

			makerTradeBotData = getTradeBotData(repository, makerTradeBotData);
			ATData atData = repository.getATRepository().fromATAddress(setup.atAddress);
			CrossChainTradeData updatedTradeData = BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);
			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.TRADING, updatedTradeData.mode);
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_AT_REDEEM.name(), makerTradeBotData.getState());
		}
	}

	@Test
	public void testMakerProgressSkipsUnsafeTakerLocktime() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			int unsafeLockTimeB = (int) (System.currentTimeMillis() / 1000L
					+ BitcoinyForeignForeignTradeBot.FOREIGN_LOCKTIME_SAFETY_MARGIN_MINUTES * 60L);
			int lockTimeA = unsafeLockTimeB + BitcoinyForeignForeignTradeBot.FOREIGN_LOCKTIME_SAFETY_MARGIN_MINUTES * 60 + 600;
			MakerTradeSetup setup = setupTradingMakerTradeWithLockTimes(repository, lockTimeA, unsafeLockTimeB);
			CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
			String takerRequestedP2sh = deriveTakerRequestedP2shAddress(tradeData, tradeData.lockTimeB);
			setMockHtlcStatuses(Map.of(takerRequestedP2sh, BitcoinyHTLC.Status.FUNDED), BitcoinyHTLC.Status.FUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);

			TradeBotData makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);
			assertEquals(0, this.litecoin.redeemTransactionCount);
			assertEquals(TradeStates.State.MAKER_WAITING_FOR_TAKER_HTLC.name(), makerTradeBotData.getState());

			CrossChainTradeData updatedTradeData = getTradeData(repository, setup.atAddress);
			assertEquals(AcctMode.TRADING, updatedTradeData.mode);
		}
	}

	@Test
	public void testTakerProgressRedeemsOfferedForeignHtlcFromAtSecretReveal() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupMakerSecretRevealedTrade(repository);
			setMockHtlcStatus(BitcoinyHTLC.Status.FUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.takerTradeBotData);

			TradeBotData takerTradeBotData = getTradeBotData(repository, setup.takerTradeBotData);
			assertEquals(1, this.bitcoin.redeemTransactionCount);
			assertEquals(1, this.bitcoin.broadcastTransactions.size());
			assertEquals(TradeStates.State.TAKER_DONE.name(), takerTradeBotData.getState());
		}
	}

	@Test
	public void testTakerProgressRedeemsOfferedForeignHtlcFromRequestedChainSecret() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupTradingMakerTrade(repository);
			CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
			String takerRequestedP2sh = deriveTakerRequestedP2shAddress(tradeData, tradeData.lockTimeB);
			setMockHtlcStatus(BitcoinyHTLC.Status.FUNDED);
			setMockHtlcSecret(takerRequestedP2sh, setup.makerTradeBotData.getSecret());

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.takerTradeBotData);

			TradeBotData takerTradeBotData = getTradeBotData(repository, setup.takerTradeBotData);
			assertEquals(1, this.bitcoin.redeemTransactionCount);
			assertEquals(1, this.bitcoin.broadcastTransactions.size());
			assertEquals(TradeStates.State.TAKER_DONE.name(), takerTradeBotData.getState());
		}
	}

	@Test
	public void testTakerProgressIgnoresInvalidRequestedChainSecret() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupTradingMakerTrade(repository);
			CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
			String takerRequestedP2sh = deriveTakerRequestedP2shAddress(tradeData, tradeData.lockTimeB);
			setMockHtlcStatus(BitcoinyHTLC.Status.FUNDED);
			setMockHtlcSecret(takerRequestedP2sh, INVALID_SECRET);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.takerTradeBotData);

			TradeBotData takerTradeBotData = getTradeBotData(repository, setup.takerTradeBotData);
			assertEquals(0, this.bitcoin.redeemTransactionCount);
			assertEquals(0, this.bitcoin.broadcastTransactions.size());
			assertEquals(TradeStates.State.TAKER_WAITING_FOR_MAKER_REDEEM.name(), takerTradeBotData.getState());
		}
	}

	@Test
	public void testTakerProgressHandlesOfferedRedeemInProgress() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupMakerSecretRevealedTrade(repository);
			setMockHtlcStatus(BitcoinyHTLC.Status.REDEEM_IN_PROGRESS);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.takerTradeBotData);

			TradeBotData takerTradeBotData = getTradeBotData(repository, setup.takerTradeBotData);
			assertEquals(0, this.bitcoin.redeemTransactionCount);
			assertEquals(0, this.bitcoin.broadcastTransactions.size());
			assertEquals(TradeStates.State.TAKER_DONE.name(), takerTradeBotData.getState());
		}
	}

	@Test
	public void testTakerProgressDoesNotCompleteRedeemedAtWithoutOfferedRedeem() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupMakerSecretRevealedTrade(repository);
			setMockHtlcStatus(BitcoinyHTLC.Status.UNFUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.takerTradeBotData);

			TradeBotData takerTradeBotData = getTradeBotData(repository, setup.takerTradeBotData);
			assertEquals(0, this.bitcoin.redeemTransactionCount);
			assertEquals(0, this.bitcoin.broadcastTransactions.size());
			assertEquals(TradeStates.State.TAKER_WAITING_FOR_MAKER_REDEEM.name(), takerTradeBotData.getState());
		}
	}

	@Test
	public void testTakerProgressWaitsWithoutSecret() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = setupTradingMakerTrade(repository);
			setMockHtlcStatus(BitcoinyHTLC.Status.FUNDED);

			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.takerTradeBotData);

			TradeBotData takerTradeBotData = getTradeBotData(repository, setup.takerTradeBotData);
			assertEquals(0, this.bitcoin.redeemTransactionCount);
			assertEquals(0, this.bitcoin.broadcastTransactions.size());
			assertEquals(TradeStates.State.TAKER_WAITING_FOR_MAKER_REDEEM.name(), takerTradeBotData.getState());
		}
	}

	@Test
	public void testCanDeleteOnlyInactiveForeignForeignStates() throws Exception {
		try (final Repository repository = RepositoryManager.getRepository()) {
			MakerTradeSetup setup = createAndDeployMakerTrade(repository);
			BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);
			TradeBotData makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);

			assertFalse(BitcoinyForeignForeignTradeBot.getInstance().canDelete(repository, makerTradeBotData));

			makerTradeBotData.setState(TradeStates.State.MAKER_DONE.name());
			makerTradeBotData.setStateValue(TradeStates.State.MAKER_DONE.value);
			repository.getCrossChainRepository().save(makerTradeBotData);
			repository.saveChanges();

			assertTrue(BitcoinyForeignForeignTradeBot.getInstance().canDelete(repository, makerTradeBotData));
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

	private MakerTradeSetup createAndDeployMakerTrade(Repository repository) throws DataException, TransformationException {
		PrivateKeyAccount creator = Common.getTestAccount(repository, "chloe");
		byte[] unsignedDeployBytes = BitcoinyForeignForeignTradeBot.getInstance().createTrade(repository, createRequest(creator));
		DeployAtTransactionData deployData = (DeployAtTransactionData) fromUnsignedBytes(unsignedDeployBytes);
		TransactionUtils.signAndMint(repository, deployData, creator);

		TradeBotData makerTradeBotData = repository.getCrossChainRepository().getAllTradeBotData().get(0);
		MakerTradeSetup setup = new MakerTradeSetup();
		setup.creator = creator;
		setup.atAddress = deployData.getAtAddress();
		setup.makerTradeBotData = makerTradeBotData;
		return setup;
	}

	private MakerTradeSetup setupReservedMakerTrade(Repository repository)
			throws DataException, TransformationException, ForeignBlockchainException {
		MakerTradeSetup setup = createAndDeployMakerTrade(repository);
		BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);
		setup.makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);

		PrivateKeyAccount responder = Common.getTestAccount(repository, "dilbert");
		ATData atData = repository.getATRepository().fromATAddress(setup.atAddress);
		CrossChainTradeData tradeData = BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);
		byte[] unsignedReserveBytes = BitcoinyForeignForeignTradeBot.getInstance().startResponse(repository, atData, tradeData,
				responder.getPublicKey(), TAKER_REQUESTED_KEY, bitcoinAddress(TAKER_OFFERED_RECEIVE_HASH));
		TransactionData transactionData = fromUnsignedBytes(unsignedReserveBytes);
		TransactionUtils.signAndMint(repository, transactionData, responder);

		setup.taker = responder;
		setup.takerTradeBotData = getTakerTradeBotData(repository, setup.atAddress);
		return setup;
	}

	private MakerTradeSetup setupMakerDeclaredTrade(Repository repository)
			throws DataException, TransformationException, ForeignBlockchainException {
		MakerTradeSetup setup = setupReservedMakerTrade(repository);
		setMockHtlcStatus(BitcoinyHTLC.Status.FUNDED);
		BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);
		setup.makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);
		setup.takerTradeBotData = getTradeBotData(repository, setup.takerTradeBotData);
		return setup;
	}

	private MakerTradeSetup setupTradingMakerTrade(Repository repository)
			throws DataException, TransformationException, ForeignBlockchainException {
		MakerTradeSetup setup = setupMakerDeclaredTrade(repository);
		CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
		int lockTimeB = BitcoinyForeignForeignTradeBot.calcTakerLockTime(tradeData.lockTimeA);
		String makerOfferedP2sh = deriveMakerOfferedP2shAddress(tradeData);
		String takerRequestedP2sh = deriveTakerRequestedP2shAddress(tradeData, lockTimeB);
		setMockHtlcStatuses(Map.of(
				makerOfferedP2sh, BitcoinyHTLC.Status.FUNDED,
				takerRequestedP2sh, BitcoinyHTLC.Status.FUNDED), BitcoinyHTLC.Status.UNFUNDED);

		BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.takerTradeBotData);
		setup.makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);
		setup.takerTradeBotData = getTradeBotData(repository, setup.takerTradeBotData);
		return setup;
	}

	private MakerTradeSetup setupMakerSecretRevealedTrade(Repository repository)
			throws DataException, TransformationException, ForeignBlockchainException {
		MakerTradeSetup setup = setupTradingMakerTrade(repository);
		CrossChainTradeData tradeData = getTradeData(repository, setup.atAddress);
		String takerRequestedP2sh = deriveTakerRequestedP2shAddress(tradeData, tradeData.lockTimeB);
		setMockHtlcStatuses(Map.of(takerRequestedP2sh, BitcoinyHTLC.Status.REDEEMED), BitcoinyHTLC.Status.UNFUNDED);

		BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);
		setup.makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);
		setup.takerTradeBotData = getTradeBotData(repository, setup.takerTradeBotData);
		return setup;
	}

	private MakerTradeSetup setupTradingMakerTradeWithLockTimes(Repository repository, int lockTimeA, int lockTimeB)
			throws DataException, TransformationException, ForeignBlockchainException {
		MakerTradeSetup setup = setupReservedMakerTrade(repository);
		sendMessage(repository, setup.makerTradeBotData.getTradePrivateKey(),
				BitcoinyForeignForeignACCTv1.buildMakerHtlcMessage(lockTimeA), setup.atAddress);

		BitcoinyForeignForeignTradeBot.getInstance().progress(repository, setup.makerTradeBotData);
		setup.makerTradeBotData = getTradeBotData(repository, setup.makerTradeBotData);

		sendMessage(repository, setup.taker.getPrivateKey(),
				BitcoinyForeignForeignACCTv1.buildTakerHtlcMessage(lockTimeB), setup.atAddress);
		setup.takerTradeBotData = getTradeBotData(repository, setup.takerTradeBotData);
		return setup;
	}

	private static void sendMessage(Repository repository, byte[] senderPrivateKey, byte[] messageData, String atAddress)
			throws DataException {
		PrivateKeyAccount sender = new PrivateKeyAccount(repository, senderPrivateKey);
		MessageTransaction messageTransaction = MessageTransaction.build(repository, sender, Group.NO_GROUP,
				atAddress, messageData, false, false);
		messageTransaction.getTransactionData().setFee(TEST_MESSAGE_FEE);
		TransactionUtils.signAndMint(repository, messageTransaction.getTransactionData(), sender);
	}

	private static TradeBotData getTradeBotData(Repository repository, TradeBotData tradeBotData) throws DataException {
		return repository.getCrossChainRepository().getTradeBotData(tradeBotData.getTradePrivateKey());
	}

	private static TradeBotData getTakerTradeBotData(Repository repository, String atAddress) throws DataException {
		return repository.getCrossChainRepository().getAllTradeBotData().stream()
				.filter(tradeBotData -> BitcoinyForeignForeignACCTv1.NAME.equals(tradeBotData.getAcctName()))
				.filter(tradeBotData -> atAddress.equals(tradeBotData.getAtAddress()))
				.filter(tradeBotData -> tradeBotData.getState().startsWith("TAKER_"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing taker trade-bot data"));
	}

	private static CrossChainTradeData getTradeData(Repository repository, String atAddress) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(atAddress);
		return BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);
	}

	private void installMockBitcoinys() {
		this.bitcoin = new MockBitcoiny(bitcoinyParams("BITCOIN"), "BTC", MAKER_OFFERED_RECEIVE_HASH, MAKER_OFFERED_KEY);
		this.litecoin = new MockBitcoiny(bitcoinyParams("LITECOIN"), "LTC", TAKER_REQUESTED_RECEIVE_HASH, TAKER_REQUESTED_KEY);

		BitcoinyForeignForeignTradeBot.getInstance().setBitcoinyResolverForTesting(blockchain -> {
			ForeignBlockchainRegistry.Entry entry = ForeignBlockchainRegistry.fromString(blockchain);
			if (entry == null)
				return null;

			if ("BITCOIN".equals(entry.name()))
				return this.bitcoin;

			if ("LITECOIN".equals(entry.name()))
				return this.litecoin;

			return null;
		});
	}

	private void setMockHtlcStatus(BitcoinyHTLC.Status status) {
		BitcoinyForeignForeignTradeBot.getInstance().setHtlcStatusResolverForTesting((bitcoiny, p2shAddress, minimumAmount) -> status);
	}

	private void setMockHtlcStatuses(Map<String, BitcoinyHTLC.Status> statuses, BitcoinyHTLC.Status defaultStatus) {
		Map<String, BitcoinyHTLC.Status> statusMap = new HashMap<>(statuses);
		BitcoinyForeignForeignTradeBot.getInstance().setHtlcStatusResolverForTesting((bitcoiny, p2shAddress, minimumAmount) ->
				statusMap.getOrDefault(p2shAddress, defaultStatus));
	}

	private void setMockHtlcSecret(String p2shAddress, byte[] secret) {
		BitcoinyForeignForeignTradeBot.getInstance().setHtlcSecretResolverForTesting((bitcoiny, resolvedP2shAddress) ->
				p2shAddress.equals(resolvedP2shAddress) ? secret : null);
	}

	private String deriveMakerOfferedP2shAddress(CrossChainTradeData tradeData) {
		return BitcoinyHtlcTradeSupport.deriveP2shAddress(this.bitcoin, tradeData.creatorOfferedForeignPKH,
				tradeData.lockTimeA, tradeData.partnerOfferedForeignPKH, tradeData.hashOfSecretA);
	}

	private String deriveTakerRequestedP2shAddress(CrossChainTradeData tradeData, int lockTimeB) {
		return BitcoinyHtlcTradeSupport.deriveP2shAddress(this.litecoin, tradeData.partnerRequestedForeignPKH,
				lockTimeB, tradeData.creatorRequestedForeignPKH, tradeData.hashOfSecretA);
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

	private static class MakerTradeSetup {
		PrivateKeyAccount creator;
		PrivateKeyAccount taker;
		String atAddress;
		TradeBotData makerTradeBotData;
		TradeBotData takerTradeBotData;
	}

	private static class MockBitcoiny extends Bitcoiny {
		private static final byte[] DUMMY_RAW_TRANSACTION = new byte[] { 1, 2, 3, 4 };

		private final byte[] receivePublicKeyHash;
		private final Set<String> validWalletKeys;
		private int transactionCounter;
		private int spendTransactionCount;
		private int redeemTransactionCount;
		private final List<BitcoinySignedTransaction> broadcastTransactions = new ArrayList<>();
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
		public BitcoinySignedTransaction buildSpendTransaction(String xprv58, String recipient, long amount, Long feePerByte) {
			++this.spendTransactionCount;
			return fakeTransaction("fund");
		}

		@Override
		public BitcoinySignedTransaction buildHtlcRedeemTransaction(Coin redeemAmount, ECKey redeemKey,
				List<UnspentOutput> fundingOutputs, byte[] redeemScriptBytes, byte[] secret, byte[] receivingAccountInfo) {
			++this.redeemTransactionCount;
			return fakeTransaction("redeem");
		}

		@Override
		public String getUnusedReceiveAddress(String key58) {
			return BitcoinyAddress.fromPubKeyHash(this.getNetworkParameters(), this.receivePublicKeyHash).toString();
		}

		@Override
		public List<UnspentOutput> getUnspentOutputs(String base58Address, boolean includeUnconfirmed) {
			return Collections.singletonList(new UnspentOutput(new byte[32], 0, 1, REQUESTED_FOREIGN_AMOUNT + this.feeRequired));
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
