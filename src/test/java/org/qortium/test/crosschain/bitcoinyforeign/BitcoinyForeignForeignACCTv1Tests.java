package org.qortium.test.crosschain.bitcoinyforeign;

import com.google.common.hash.HashCode;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.api.model.CrossChainOfferSummary;
import org.qortium.api.model.CrossChainTradeSummary;
import org.qortium.api.resource.CrossChainResource;
import org.qortium.asset.Asset;
import org.qortium.controller.tradebot.BitcoinyForeignForeignTradeBot;
import org.qortium.crosschain.AcctRegistry;
import org.qortium.crosschain.AcctMode;
import org.qortium.crosschain.BitcoinyForeignForeignACCTv1;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.TradeDirection;
import org.qortium.crypto.Crypto;
import org.qortium.data.at.ATData;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.DeployAtTransactionData;
import org.qortium.data.transaction.MessageTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.TransferAssetTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AssetUtils;
import org.qortium.test.common.ApiCommon;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.MessageTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.utils.Amounts;
import org.qortium.utils.BitTwiddling;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class BitcoinyForeignForeignACCTv1Tests extends Common {

	private static final byte[] MAKER_OFFERED_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("aa00aa11aa22aa33aa44aa55aa66aa77aa88aa99").asBytes();
	private static final byte[] MAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("bb00bb11bb22bb33bb44bb55bb66bb77bb88bb99").asBytes();
	private static final byte[] TAKER_OFFERED_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("cc00cc11cc22cc33cc44cc55cc66cc77cc88cc99").asBytes();
	private static final byte[] TAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("dd00dd11dd22dd33dd44dd55dd66dd77dd88dd99").asBytes();
	private static final byte[] SECRET_A = "This string is exactly 32 bytes!".getBytes(StandardCharsets.UTF_8);
	private static final byte[] WRONG_SECRET_A = "This string is exactly 32 byt3s!".getBytes(StandardCharsets.UTF_8);
	private static final byte[] HASH_OF_SECRET_A = Crypto.hash160(SECRET_A);
	private static final long OFFERED_FOREIGN_AMOUNT = 100_000L;
	private static final long REQUESTED_FOREIGN_AMOUNT = 250_000L;
	private static final long NATIVE_FEE_RESERVE = 3L * Amounts.MULTIPLIER;
	private static final int MAKER_LOCK_TIME = 1_765_010_000;
	private static final int TAKER_LOCK_TIME = MAKER_LOCK_TIME - BitcoinyForeignForeignACCTv1.REFUND_LOCKTIME_SAFETY_MARGIN_MINUTES * 60 - 1;
	private static final int UNSAFE_TAKER_LOCK_TIME = MAKER_LOCK_TIME - BitcoinyForeignForeignACCTv1.REFUND_LOCKTIME_SAFETY_MARGIN_MINUTES * 60;
	private static final int TRADE_TIMEOUT = 120;
	private static final int SHORT_TRADE_TIMEOUT = BitcoinyForeignForeignACCTv1.REFUND_LOCKTIME_SAFETY_MARGIN_MINUTES + 5;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testMessageBuildersUseFixedLengthsAndPayloadOffsets() {
		byte[] reserveMessage = BitcoinyForeignForeignACCTv1.buildReserveMessage(TAKER_OFFERED_FOREIGN_PUBLIC_KEY_HASH,
				TAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH);
		assertEquals(BitcoinyForeignForeignACCTv1.RESERVE_MESSAGE_LENGTH, reserveMessage.length);
		assertArrayEquals(TAKER_OFFERED_FOREIGN_PUBLIC_KEY_HASH, Arrays.copyOfRange(reserveMessage, 0, 20));
		assertArrayEquals(new byte[12], Arrays.copyOfRange(reserveMessage, 20, 32));
		assertArrayEquals(TAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH, Arrays.copyOfRange(reserveMessage, 32, 52));
		assertArrayEquals(new byte[12], Arrays.copyOfRange(reserveMessage, 52, 64));

		byte[] makerHtlcMessage = BitcoinyForeignForeignACCTv1.buildMakerHtlcMessage(MAKER_LOCK_TIME);
		assertEquals(BitcoinyForeignForeignACCTv1.MAKER_HTLC_MESSAGE_LENGTH, makerHtlcMessage.length);
		assertEquals(MAKER_LOCK_TIME, BitTwiddling.longFromBEBytes(makerHtlcMessage, 0));

		byte[] takerHtlcMessage = BitcoinyForeignForeignACCTv1.buildTakerHtlcMessage(TAKER_LOCK_TIME);
		assertEquals(BitcoinyForeignForeignACCTv1.TAKER_HTLC_MESSAGE_LENGTH, takerHtlcMessage.length);
		assertEquals(TAKER_LOCK_TIME, BitTwiddling.longFromBEBytes(takerHtlcMessage, 0));

		byte[] secretRevealMessage = BitcoinyForeignForeignACCTv1.buildSecretRevealMessage(SECRET_A);
		assertEquals(BitcoinyForeignForeignACCTv1.SECRET_REVEAL_MESSAGE_LENGTH, secretRevealMessage.length);
		assertArrayEquals(SECRET_A, secretRevealMessage);
	}

	@Test
	public void testBuildsForeignForeignAtWithExpectedInitialTradeData() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress());
			ATData atData = repository.getATRepository().fromATAddress(deployAtTransaction.getATAccount().getAddress());
			CrossChainTradeData tradeData = BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);

			assertNotNull(tradeData);
			assertArrayEquals(BitcoinyForeignForeignACCTv1.CODE_BYTES_HASH, atData.getCodeHash());
			assertEquals(BitcoinyForeignForeignACCTv1.NAME, tradeData.acctName);
			assertEquals(TradeDirection.SELL_FOREIGN_FOR_FOREIGN, tradeData.tradeDirection);
			assertEquals(AcctMode.OFFERING, tradeData.mode);
			assertTrue(tradeData.isFillableOffer());
			assertEquals("BITCOIN", tradeData.offeredForeignBlockchain);
			assertEquals(OFFERED_FOREIGN_AMOUNT, tradeData.offeredForeignAmount);
			assertEquals("LITECOIN", tradeData.requestedForeignBlockchain);
			assertEquals(REQUESTED_FOREIGN_AMOUNT, tradeData.requestedForeignAmount);
			assertEquals(tradeAccount.getAddress(), tradeData.creatorTradeAddress);
			assertArrayEquals(MAKER_OFFERED_FOREIGN_PUBLIC_KEY_HASH, tradeData.creatorOfferedForeignPKH);
			assertArrayEquals(MAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH, tradeData.creatorRequestedForeignPKH);
			assertArrayEquals(HASH_OF_SECRET_A, tradeData.hashOfSecretA);
			assertEquals(0L, tradeData.localAmount);
			assertEquals(0, tradeData.availableFillSlots);
			assertNull(tradeData.foreignBlockchain);
			assertEquals(0L, tradeData.expectedForeignAmount);

			CrossChainOfferSummary offerSummary = new CrossChainOfferSummary(tradeData, 123L);
			assertEquals(TradeDirection.SELL_FOREIGN_FOR_FOREIGN, offerSummary.getTradeDirection());
			assertEquals("BITCOIN", offerSummary.getOfferedForeignBlockchain());
			assertEquals(OFFERED_FOREIGN_AMOUNT, offerSummary.getOfferedForeignAmount());
			assertEquals("LITECOIN", offerSummary.getRequestedForeignBlockchain());
			assertEquals(REQUESTED_FOREIGN_AMOUNT, offerSummary.getRequestedForeignAmount());

			CrossChainTradeSummary tradeSummary = new CrossChainTradeSummary(tradeData, 456L);
			assertEquals(TradeDirection.SELL_FOREIGN_FOR_FOREIGN, tradeSummary.getTradeDirection());
			assertEquals("BITCOIN", tradeSummary.getOfferedForeignBlockchain());
			assertEquals(OFFERED_FOREIGN_AMOUNT, tradeSummary.getOfferedForeignAmount());
			assertEquals("LITECOIN", tradeSummary.getRequestedForeignBlockchain());
			assertEquals(REQUESTED_FOREIGN_AMOUNT, tradeSummary.getRequestedForeignAmount());
		}
	}

	@Test
	public void testTradeOfferFilteringMatchesEitherForeignChain() throws DataException {
		String atAddress;
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress());
			atAddress = deployAtTransaction.getATAccount().getAddress();
		}

		CrossChainResource resource = (CrossChainResource) ApiCommon.buildResource(CrossChainResource.class);
		List<CrossChainTradeData> bitcoinOffers = resource.getTradeOffers("BITCOIN", null, null, null, null, null, null);
		List<CrossChainTradeData> litecoinOffers = resource.getTradeOffers("LITECOIN", null, null, null, null, null, null);
		List<CrossChainTradeData> offeredBitcoinOffers = resource.getTradeOffers(null, "BITCOIN", null, null, null, null, null);
		List<CrossChainTradeData> requestedLitecoinOffers = resource.getTradeOffers(null, null, "LITECOIN", null, null, null, null);
		List<CrossChainTradeData> exactBitcoinLitecoinOffers = resource.getTradeOffers(null, "BITCOIN", "LITECOIN", null, null, null, null);
		List<CrossChainTradeData> swappedLitecoinBitcoinOffers = resource.getTradeOffers(null, "LITECOIN", "BITCOIN", null, null, null, null);
		List<CrossChainTradeData> nativeFilteredOffers = resource.getTradeOffers("BITCOIN", null, null, Asset.NATIVE, null, null, null);

		assertTrue(bitcoinOffers.stream().anyMatch(tradeData -> atAddress.equals(tradeData.atAddress)));
		assertTrue(litecoinOffers.stream().anyMatch(tradeData -> atAddress.equals(tradeData.atAddress)));
		assertTrue(offeredBitcoinOffers.stream().anyMatch(tradeData -> atAddress.equals(tradeData.atAddress)));
		assertTrue(requestedLitecoinOffers.stream().anyMatch(tradeData -> atAddress.equals(tradeData.atAddress)));
		assertTrue(exactBitcoinLitecoinOffers.stream().anyMatch(tradeData -> atAddress.equals(tradeData.atAddress)));
		assertFalse(swappedLitecoinBitcoinOffers.stream().anyMatch(tradeData -> atAddress.equals(tradeData.atAddress)));
		assertFalse(nativeFilteredOffers.stream().anyMatch(tradeData -> atAddress.equals(tradeData.atAddress)));
	}

	@Test
	public void testReserveMakerLockTakerLockAndSecretReveal() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress());
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			reserveTrade(repository, taker, atAddress);
			CrossChainTradeData tradeData = getTradeData(repository, atAddress);
			assertEquals(AcctMode.RESERVED, tradeData.mode);
			assertFalse(tradeData.isFillableOffer());
			assertEquals(taker.getAddress(), tradeData.partnerAddress);
			assertArrayEquals(TAKER_OFFERED_FOREIGN_PUBLIC_KEY_HASH, tradeData.partnerOfferedForeignPKH);
			assertArrayEquals(TAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH, tradeData.partnerRequestedForeignPKH);

			declareMakerHtlc(repository, tradeAccount, atAddress, MAKER_LOCK_TIME);
			tradeData = getTradeData(repository, atAddress);
			assertEquals(AcctMode.FOREIGN_LOCKED, tradeData.mode);
			assertEquals(Integer.valueOf(MAKER_LOCK_TIME), tradeData.lockTimeA);

			declareTakerHtlc(repository, taker, atAddress, UNSAFE_TAKER_LOCK_TIME);
			tradeData = getTradeData(repository, atAddress);
			assertEquals(AcctMode.FOREIGN_LOCKED, tradeData.mode);
			assertNull(tradeData.lockTimeB);

			declareTakerHtlc(repository, taker, atAddress, TAKER_LOCK_TIME);
			tradeData = getTradeData(repository, atAddress);
			assertEquals(AcctMode.TRADING, tradeData.mode);
			assertEquals(Integer.valueOf(TAKER_LOCK_TIME), tradeData.lockTimeB);
			assertNotNull(tradeData.tradeRefundHeight);

			revealSecret(repository, tradeAccount, atAddress, WRONG_SECRET_A);
			tradeData = getTradeData(repository, atAddress);
			assertEquals(AcctMode.TRADING, tradeData.mode);
			assertNull(BitcoinyForeignForeignACCTv1.getInstance().findSecretA(repository, tradeData));

			cancelTrade(repository, tradeAccount, atAddress);
			tradeData = getTradeData(repository, atAddress);
			assertEquals(AcctMode.TRADING, tradeData.mode);

			revealSecret(repository, tradeAccount, atAddress, SECRET_A);
			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			tradeData = BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);

			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.REDEEMED, tradeData.mode);
			assertArrayEquals(SECRET_A, BitcoinyForeignForeignACCTv1.getInstance().findSecretA(repository, tradeData));
		}
	}

	@Test
	public void testTradingTimeoutFinishesAsRefundedWithoutSecret() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), SHORT_TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			reserveTrade(repository, taker, atAddress);
			declareMakerHtlc(repository, tradeAccount, atAddress, MAKER_LOCK_TIME);
			declareTakerHtlc(repository, taker, atAddress, TAKER_LOCK_TIME);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);
			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.TRADING, tradeData.mode);
			assertNotNull(tradeData.tradeRefundHeight);

			BlockUtils.mintBlock(repository);
			atData = repository.getATRepository().fromATAddress(atAddress);
			tradeData = BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);
			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.TRADING, tradeData.mode);

			BlockUtils.mintBlocks(repository, SHORT_TRADE_TIMEOUT - BitcoinyForeignForeignACCTv1.REFUND_LOCKTIME_SAFETY_MARGIN_MINUTES + 2);

			atData = repository.getATRepository().fromATAddress(atAddress);
			tradeData = BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);
			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.REFUNDED, tradeData.mode);
			assertNull(BitcoinyForeignForeignACCTv1.getInstance().findSecretA(repository, tradeData));
		}
	}

	@Test
	public void testCancelBeforeTrading() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");

			assertCancelledAfter(repository, deployer, tradeAccount, null, false);
			assertCancelledAfter(repository, deployer, tradeAccount, taker, false);
			assertCancelledAfter(repository, deployer, tradeAccount, taker, true);
		}
	}

	@Test
	public void testWrongSenderAndUnexpectedMessagesAreIgnored() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount bystander = Common.getTestAccount(repository, "bob");

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress());
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			sendMessage(repository, bystander, new byte[] { 1, 2, 3 }, atAddress);
			BlockUtils.mintBlock(repository);
			assertEquals(AcctMode.OFFERING, getTradeData(repository, atAddress).mode);

			reserveTrade(repository, taker, atAddress);
			declareMakerHtlc(repository, bystander, atAddress, MAKER_LOCK_TIME);
			CrossChainTradeData tradeData = getTradeData(repository, atAddress);
			assertEquals(AcctMode.RESERVED, tradeData.mode);
			assertNull(tradeData.lockTimeA);

			declareMakerHtlc(repository, tradeAccount, atAddress, MAKER_LOCK_TIME);
			declareTakerHtlc(repository, bystander, atAddress, TAKER_LOCK_TIME);
			tradeData = getTradeData(repository, atAddress);
			assertEquals(AcctMode.FOREIGN_LOCKED, tradeData.mode);
			assertNull(tradeData.lockTimeB);
		}
	}

	@Test
	public void testLocalPaymentAttachedToCoordinationMessageIsRefundedAndIgnored() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount issuer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
			long assetId = AssetUtils.issueAsset(repository, "alice", "FOREIGN-FOREIGN-REFUND", 100L * Amounts.MULTIPLIER, true);
			long amount = 5L * Amounts.MULTIPLIER;

			transferAsset(repository, issuer, taker.getAddress(), assetId, amount);
			long takerInitialBalance = taker.getConfirmedBalance(assetId);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress());
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			sendPaymentMessage(repository, taker, BitcoinyForeignForeignACCTv1.buildReserveMessage(
					TAKER_OFFERED_FOREIGN_PUBLIC_KEY_HASH, TAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH), atAddress, amount, assetId);
			BlockUtils.mintBlock(repository);

			CrossChainTradeData tradeData = getTradeData(repository, atAddress);
			assertEquals(AcctMode.OFFERING, tradeData.mode);
			assertEquals(takerInitialBalance, taker.getConfirmedBalance(assetId));
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(assetId));
		}
	}

	@Test
	public void testRequiresSupportedBitcoinyPair() {
		ForeignBlockchainRegistry.Entry bitcoin = BitcoinyForeignForeignACCTv1.requireBitcoinyEntry("BTC", "offeredForeignBlockchain");
		ForeignBlockchainRegistry.Entry litecoin = BitcoinyForeignForeignACCTv1.requireBitcoinyEntry("LITECOIN", "requestedForeignBlockchain");
		ForeignBlockchainRegistry.Entry zcash = BitcoinyForeignForeignACCTv1.requireBitcoinyEntry("ZEC", "requestedForeignBlockchain");
		ForeignBlockchainRegistry.Entry pirateChain = ForeignBlockchainRegistry.fromString("PIRATECHAIN");

		assertTrue(BitcoinyForeignForeignACCTv1.isSupportedBitcoinyPair(bitcoin, litecoin));
		assertFalse(BitcoinyForeignForeignACCTv1.isSupportedBitcoinyPair(bitcoin, null));
		assertFalse(BitcoinyForeignForeignACCTv1.isSupportedBitcoinyPair(pirateChain, litecoin));
		assertFalse(BitcoinyForeignForeignACCTv1.isSupportedBitcoinyPair(bitcoin, zcash));
		assertFalse(BitcoinyForeignForeignACCTv1.isSupportedBitcoinyPair(zcash, litecoin));

		try {
			BitcoinyForeignForeignACCTv1.requireBitcoinyEntry("PIRATECHAIN", "offeredForeignBlockchain");
			fail("PirateChain should not be accepted as a Bitcoiny foreign/foreign chain");
		} catch (IllegalArgumentException e) {
			// Expected
		}
	}

	@Test
	public void testAcctIsRegisteredAndUserRoutable() {
		assertSame(BitcoinyForeignForeignACCTv1.getInstance(),
				AcctRegistry.getAcctByName(BitcoinyForeignForeignACCTv1.NAME));
		assertSame(BitcoinyForeignForeignACCTv1.getInstance(),
				AcctRegistry.getAcctByCodeHash(BitcoinyForeignForeignACCTv1.CODE_BYTES_HASH));
		assertSame(BitcoinyForeignForeignTradeBot.getInstance(),
				AcctRegistry.getTradeBotForAcct(BitcoinyForeignForeignACCTv1.getInstance()));
	}

	private void assertCancelledAfter(Repository repository, PrivateKeyAccount deployer, PrivateKeyAccount tradeAccount,
			PrivateKeyAccount taker, boolean makerHtlcDeclared) throws DataException {
		DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress());
		String atAddress = deployAtTransaction.getATAccount().getAddress();

		if (taker != null)
			reserveTrade(repository, taker, atAddress);

		if (makerHtlcDeclared)
			declareMakerHtlc(repository, tradeAccount, atAddress, MAKER_LOCK_TIME);

		cancelTrade(repository, tradeAccount, atAddress);

		ATData atData = repository.getATRepository().fromATAddress(atAddress);
		CrossChainTradeData tradeData = BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);

		assertTrue(atData.getIsFinished());
		assertEquals(AcctMode.CANCELLED, tradeData.mode);
		assertFalse(tradeData.isFillableOffer());
	}

	private static DeployAtTransaction deploy(Repository repository, PrivateKeyAccount deployer, String tradeAddress) throws DataException {
		return deploy(repository, deployer, tradeAddress, TRADE_TIMEOUT);
	}

	private static DeployAtTransaction deploy(Repository repository, PrivateKeyAccount deployer, String tradeAddress, int tradeTimeout)
			throws DataException {
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");
		ForeignBlockchainRegistry.Entry litecoin = ForeignBlockchainRegistry.fromString("LITECOIN");
		byte[] creationBytes = BitcoinyForeignForeignACCTv1.buildTradeAT(bitcoin, litecoin, tradeAddress,
				MAKER_OFFERED_FOREIGN_PUBLIC_KEY_HASH, MAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH, HASH_OF_SECRET_A,
				OFFERED_FOREIGN_AMOUNT, REQUESTED_FOREIGN_AMOUNT, tradeTimeout);

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

	private static CrossChainTradeData getTradeData(Repository repository, String atAddress) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(atAddress);
		return BitcoinyForeignForeignACCTv1.getInstance().populateTradeData(repository, atData);
	}

	private static void reserveTrade(Repository repository, PrivateKeyAccount taker, String atAddress) throws DataException {
		sendMessage(repository, taker, BitcoinyForeignForeignACCTv1.buildReserveMessage(TAKER_OFFERED_FOREIGN_PUBLIC_KEY_HASH,
				TAKER_REQUESTED_FOREIGN_PUBLIC_KEY_HASH), atAddress);
		BlockUtils.mintBlock(repository);
	}

	private static void declareMakerHtlc(Repository repository, PrivateKeyAccount makerTradeAccount, String atAddress, int lockTimeA)
			throws DataException {
		sendMessage(repository, makerTradeAccount, BitcoinyForeignForeignACCTv1.buildMakerHtlcMessage(lockTimeA), atAddress);
		BlockUtils.mintBlock(repository);
	}

	private static void declareTakerHtlc(Repository repository, PrivateKeyAccount taker, String atAddress, int lockTimeB)
			throws DataException {
		sendMessage(repository, taker, BitcoinyForeignForeignACCTv1.buildTakerHtlcMessage(lockTimeB), atAddress);
		BlockUtils.mintBlock(repository);
	}

	private static void revealSecret(Repository repository, PrivateKeyAccount makerTradeAccount, String atAddress, byte[] secret)
			throws DataException {
		sendMessage(repository, makerTradeAccount, BitcoinyForeignForeignACCTv1.buildSecretRevealMessage(secret), atAddress);
		BlockUtils.mintBlock(repository);
	}

	private static void cancelTrade(Repository repository, PrivateKeyAccount sender, String atAddress) throws DataException {
		byte[] cancelMessageData = BitcoinyForeignForeignACCTv1.getInstance().buildCancelMessage(sender.getAddress());
		sendMessage(repository, sender, cancelMessageData, atAddress);
		BlockUtils.mintBlock(repository);
	}

	private static MessageTransaction sendMessage(Repository repository, PrivateKeyAccount sender, byte[] data, String recipient)
			throws DataException {
		return sendPaymentMessage(repository, sender, data, recipient, 0L, null);
	}

	private static MessageTransaction sendPaymentMessage(Repository repository, PrivateKeyAccount sender, byte[] data, String recipient,
			long amount, Long assetId) throws DataException {
		long txTimestamp = TransactionUtils.nextTimestamp(repository);
		int version = Transaction.getVersionByTimestamp(txTimestamp);

		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, sender.getPublicKey(), null, null);
		MessageTransactionData messageTransactionData = new MessageTransactionData(baseTransactionData, version, 0, recipient,
				amount, assetId, data, false, false);
		MessageTransaction messageTransaction = new MessageTransaction(repository, messageTransactionData);

		messageTransactionData.setFee(messageTransaction.calcRecommendedFee());
		TransactionUtils.signAndMint(repository, messageTransactionData, sender);

		return messageTransaction;
	}

	private static void transferAsset(Repository repository, PrivateKeyAccount sender, String recipient, long assetId, long amount)
			throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, sender.getPublicKey(), AssetUtils.fee, null);
		TransactionData transactionData = new TransferAssetTransactionData(baseTransactionData, recipient, amount, assetId);
		TransactionUtils.signAndMint(repository, transactionData, sender);
	}

}
