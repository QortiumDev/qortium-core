package org.qortium.test.crosschain.bitcoinyv5;

import com.google.common.hash.HashCode;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.crosschain.AcctRegistry;
import org.qortium.crosschain.AcctMode;
import org.qortium.crosschain.BitcoinyACCTv4;
import org.qortium.crosschain.BitcoinyACCTv5;
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
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.MessageTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.utils.Amounts;

import static org.junit.Assert.*;

public class BitcoinyACCTv5Tests extends Common {

	private static final byte[] MAKER_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("aa00aa11aa22aa33aa44aa55aa66aa77aa88aa99").asBytes();
	private static final byte[] TAKER_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("bb00bb11bb22bb33bb44bb55bb66bb77bb88bb99").asBytes();
	private static final byte[] SECRET_A = "This string is exactly 32 bytes!".getBytes();
	private static final byte[] HASH_OF_SECRET_A = Crypto.hash160(SECRET_A);
	private static final long LOCAL_AMOUNT = 25L * Amounts.MULTIPLIER;
	private static final long FOREIGN_AMOUNT = 864200L;
	private static final long NATIVE_FEE_RESERVE = 3L * Amounts.MULTIPLIER;
	private static final int TRADE_TIMEOUT = 120;
	private static final int SHORT_TRADE_TIMEOUT = 6;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testBuildsReverseAtWithExpectedInitialTradeData() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-INIT", 100L * Amounts.MULTIPLIER, true);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, TRADE_TIMEOUT);
			Account at = deployAtTransaction.getATAccount();

			ATData atData = repository.getATRepository().fromATAddress(at.getAddress());
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

			assertNotNull(tradeData);
			assertArrayEquals(BitcoinyACCTv5.CODE_BYTES_HASH, atData.getCodeHash());
			assertEquals(BitcoinyACCTv5.NAME, tradeData.acctName);
			assertEquals("BITCOIN", tradeData.foreignBlockchain);
			assertEquals(TradeDirection.SELL_FOREIGN, tradeData.tradeDirection);
			assertEquals(AcctMode.OFFERING, tradeData.mode);
			assertTrue(tradeData.isFillableOffer());
			assertEquals(localAssetId, tradeData.localAssetId);
			assertEquals(LOCAL_AMOUNT, tradeData.localAmount);
			assertEquals(LOCAL_AMOUNT, tradeData.totalLocalAmount);
			assertEquals(LOCAL_AMOUNT, tradeData.remainingLocalAmount);
			assertEquals(0L, tradeData.activeLocalAmount);
			assertEquals(0L, tradeData.completedLocalAmount);
			assertEquals(LOCAL_AMOUNT, tradeData.minFillLocalAmount);
			assertEquals(LOCAL_AMOUNT, tradeData.maxFillLocalAmount);
			assertEquals(0, tradeData.activeFillCount);
			assertEquals(1, tradeData.availableFillSlots);
			assertEquals(FOREIGN_AMOUNT, tradeData.expectedForeignAmount);
			assertArrayEquals(MAKER_FOREIGN_PUBLIC_KEY_HASH, tradeData.creatorForeignPKH);
			assertArrayEquals(HASH_OF_SECRET_A, tradeData.hashOfSecretA);
		}
	}

	@Test
	public void testLatestBitcoinyAcctStillV4AndV5IsRegistered() {
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");

		assertSame(BitcoinyACCTv4.getInstance(), bitcoin.getLatestAcct());
		assertSame(BitcoinyACCTv5.getInstance(), AcctRegistry.getAcctByName(BitcoinyACCTv5.NAME));
		assertSame(BitcoinyACCTv5.getInstance(), AcctRegistry.getAcctByCodeHash(BitcoinyACCTv5.CODE_BYTES_HASH));
	}

	@Test
	public void testReservationForeignLockLocalLockAndMakerRedeem() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount issuer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount makerReceiving = Common.getTestAccount(repository, "bob");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-REDEEM", 100L * Amounts.MULTIPLIER, true);

			transferAsset(repository, issuer, taker.getAddress(), localAssetId, LOCAL_AMOUNT);
			long makerReceivingInitialBalance = makerReceiving.getConfirmedBalance(localAssetId);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			reserveTrade(repository, taker, atAddress);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);
			assertEquals(AcctMode.RESERVED, tradeData.mode);
			assertFalse(tradeData.isFillableOffer());
			assertEquals(taker.getAddress(), tradeData.partnerAddress);
			assertArrayEquals(TAKER_FOREIGN_PUBLIC_KEY_HASH, tradeData.partnerForeignPKH);
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));

			int lockTimeA = (int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L);
			declareForeignLock(repository, tradeAccount, atAddress, lockTimeA);

			atData = repository.getATRepository().fromATAddress(atAddress);
			tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);
			assertEquals(AcctMode.FOREIGN_LOCKED, tradeData.mode);
			assertEquals(Integer.valueOf(lockTimeA), tradeData.lockTimeA);
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));

			sendLocalLock(repository, taker, atAddress, LOCAL_AMOUNT, localAssetId);

			atData = repository.getATRepository().fromATAddress(atAddress);
			tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);
			assertEquals(AcctMode.TRADING, tradeData.mode);
			assertEquals(LOCAL_AMOUNT, tradeData.activeLocalAmount);
			assertEquals(LOCAL_AMOUNT, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));

			byte[] redeemMessageData = BitcoinyACCTv5.buildRedeemMessage(SECRET_A, makerReceiving.getAddress());
			sendMessage(repository, tradeAccount, redeemMessageData, atAddress);
			BlockUtils.mintBlock(repository);

			atData = repository.getATRepository().fromATAddress(atAddress);
			tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.REDEEMED, tradeData.mode);
			assertEquals(makerReceivingInitialBalance + LOCAL_AMOUNT, makerReceiving.getConfirmedBalance(localAssetId));
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));
			assertArrayEquals(SECRET_A, BitcoinyACCTv5.getInstance().findSecretA(repository, tradeData));
		}
	}

	@Test
	public void testInvalidLocalLockAmountRefundsAndStaysForeignLocked() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount issuer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-WRONG-AMOUNT", 100L * Amounts.MULTIPLIER, true);
			long wrongAmount = LOCAL_AMOUNT - Amounts.MULTIPLIER;

			transferAsset(repository, issuer, taker.getAddress(), localAssetId, wrongAmount);
			long takerInitialBalance = taker.getConfirmedBalance(localAssetId);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			reserveTrade(repository, taker, atAddress);
			declareForeignLock(repository, tradeAccount, atAddress, (int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L));

			sendLocalLock(repository, taker, atAddress, wrongAmount, localAssetId);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.FOREIGN_LOCKED, tradeData.mode);
			assertEquals(takerInitialBalance, taker.getConfirmedBalance(localAssetId));
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));
		}
	}

	@Test
	public void testWrongSenderLocalLockRefundsAndStaysForeignLocked() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount issuer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount otherSender = Common.getTestAccount(repository, "bob");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-WRONG-SENDER", 100L * Amounts.MULTIPLIER, true);

			transferAsset(repository, issuer, otherSender.getAddress(), localAssetId, LOCAL_AMOUNT);
			long otherSenderInitialBalance = otherSender.getConfirmedBalance(localAssetId);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			reserveTrade(repository, taker, atAddress);
			declareForeignLock(repository, tradeAccount, atAddress, (int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L));

			sendLocalLock(repository, otherSender, atAddress, LOCAL_AMOUNT, localAssetId);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.FOREIGN_LOCKED, tradeData.mode);
			assertEquals(otherSenderInitialBalance, otherSender.getConfirmedBalance(localAssetId));
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));
		}
	}

	@Test
	public void testPrematureLocalLockRefundsAndStaysReserved() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount issuer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-EARLY-LOCK", 100L * Amounts.MULTIPLIER, true);

			transferAsset(repository, issuer, taker.getAddress(), localAssetId, LOCAL_AMOUNT);
			long takerInitialBalance = taker.getConfirmedBalance(localAssetId);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			reserveTrade(repository, taker, atAddress);

			sendLocalLock(repository, taker, atAddress, LOCAL_AMOUNT, localAssetId);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.RESERVED, tradeData.mode);
			assertEquals(takerInitialBalance, taker.getConfirmedBalance(localAssetId));
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));
		}
	}

	@Test
	public void testRefundsTakerIfMakerDoesNotRedeem() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount issuer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-REFUND", 100L * Amounts.MULTIPLIER, true);

			transferAsset(repository, issuer, taker.getAddress(), localAssetId, LOCAL_AMOUNT);
			long takerInitialBalance = taker.getConfirmedBalance(localAssetId);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, SHORT_TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			reserveTrade(repository, taker, atAddress);
			declareForeignLock(repository, tradeAccount, atAddress, (int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L));
			sendLocalLock(repository, taker, atAddress, LOCAL_AMOUNT, localAssetId);

			BlockUtils.mintBlocks(repository, BitcoinyACCTv5.calcLocalRefundTimeout(SHORT_TRADE_TIMEOUT) + 2);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.REFUNDED, tradeData.mode);
			assertEquals(takerInitialBalance, taker.getConfirmedBalance(localAssetId));
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));
		}
	}

	@Test
	public void testOfferCancelFinishesEmptyReverseOffer() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-CANCEL", 100L * Amounts.MULTIPLIER, true);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			byte[] cancelMessageData = BitcoinyACCTv5.getInstance().buildCancelMessage(deployer.getAddress());
			sendMessage(repository, deployer, cancelMessageData, atAddress);
			BlockUtils.mintBlock(repository);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.CANCELLED, tradeData.mode);
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));
			assertFalse(tradeData.isFillableOffer());
		}
	}

	@Test
	public void testMakerTradeAddressCanCancelEmptyReverseOffer() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-MAKER-CANCEL-OFFER", 100L * Amounts.MULTIPLIER, true);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			cancelTrade(repository, tradeAccount, atAddress);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.CANCELLED, tradeData.mode);
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));
			assertFalse(tradeData.isFillableOffer());
		}
	}

	@Test
	public void testMakerTradeAddressCanCancelReservedReverseOffer() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-MAKER-CANCEL-RESERVED", 100L * Amounts.MULTIPLIER, true);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			reserveTrade(repository, taker, atAddress);

			cancelTrade(repository, tradeAccount, atAddress);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.CANCELLED, tradeData.mode);
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));
			assertFalse(tradeData.isFillableOffer());
		}
	}

	@Test
	public void testMakerTradeAddressCanCancelForeignLockedReverseOffer() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-MAKER-CANCEL-FOREIGN", 100L * Amounts.MULTIPLIER, true);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			reserveTrade(repository, taker, atAddress);
			declareForeignLock(repository, tradeAccount, atAddress, (int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L));

			cancelTrade(repository, tradeAccount, atAddress);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.CANCELLED, tradeData.mode);
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));
			assertFalse(tradeData.isFillableOffer());
		}
	}

	@Test
	public void testMakerTradeAddressCannotCancelAfterTakerLocalLock() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount issuer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-MAKER-CANCEL-TRADING", 100L * Amounts.MULTIPLIER, true);

			transferAsset(repository, issuer, taker.getAddress(), localAssetId, LOCAL_AMOUNT);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			reserveTrade(repository, taker, atAddress);
			declareForeignLock(repository, tradeAccount, atAddress, (int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L));
			sendLocalLock(repository, taker, atAddress, LOCAL_AMOUNT, localAssetId);

			cancelTrade(repository, tradeAccount, atAddress);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.TRADING, tradeData.mode);
			assertEquals(LOCAL_AMOUNT, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));
		}
	}

	private DeployAtTransaction deploy(Repository repository, PrivateKeyAccount deployer, String tradeAddress, long localAssetId, int tradeTimeout) throws DataException {
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");
		byte[] creationBytes = BitcoinyACCTv5.buildTradeAT(bitcoin, tradeAddress, MAKER_FOREIGN_PUBLIC_KEY_HASH,
				HASH_OF_SECRET_A, LOCAL_AMOUNT, FOREIGN_AMOUNT, tradeTimeout);

		long txTimestamp = TransactionUtils.nextTimestamp(repository);
		Long fee = null;
		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, deployer.getPublicKey(), fee, null);
		TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData,
				"BTC-asset reverse cross-chain trade", "Bitcoin-local asset reverse cross-chain trade", "ACCT",
				"BTC-asset reverse ACCT", creationBytes, 0L, localAssetId, NATIVE_FEE_RESERVE);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		deployAtTransactionData.setFee(deployAtTransaction.calcRecommendedFee());
		TransactionUtils.signAndMint(repository, deployAtTransactionData, deployer);

		return deployAtTransaction;
	}

	private static void reserveTrade(Repository repository, PrivateKeyAccount taker, String atAddress) throws DataException {
		sendMessage(repository, taker, BitcoinyACCTv5.buildReserveMessage(TAKER_FOREIGN_PUBLIC_KEY_HASH), atAddress);
		BlockUtils.mintBlock(repository);
	}

	private static void declareForeignLock(Repository repository, PrivateKeyAccount makerTradeAccount, String atAddress, int lockTimeA) throws DataException {
		sendMessage(repository, makerTradeAccount, BitcoinyACCTv5.buildForeignLockMessage(lockTimeA), atAddress);
		BlockUtils.mintBlock(repository);
	}

	private static void sendLocalLock(Repository repository, PrivateKeyAccount taker, String atAddress, long amount, long assetId) throws DataException {
		sendPaymentMessage(repository, taker, BitcoinyACCTv5.buildLocalLockMessage(), atAddress, amount, assetId);
		BlockUtils.mintBlock(repository);
	}

	private static void cancelTrade(Repository repository, PrivateKeyAccount sender, String atAddress) throws DataException {
		byte[] cancelMessageData = BitcoinyACCTv5.getInstance().buildCancelMessage(sender.getAddress());
		sendMessage(repository, sender, cancelMessageData, atAddress);
		BlockUtils.mintBlock(repository);
	}

	private static MessageTransaction sendMessage(Repository repository, PrivateKeyAccount sender, byte[] data, String recipient) throws DataException {
		return sendPaymentMessage(repository, sender, data, recipient, 0L, null);
	}

	private static MessageTransaction sendPaymentMessage(Repository repository, PrivateKeyAccount sender, byte[] data, String recipient,
			long amount, Long assetId) throws DataException {
		long txTimestamp = TransactionUtils.nextTimestamp(repository);
		Long fee = null;
		int version = Transaction.getVersionByTimestamp(txTimestamp);

		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, sender.getPublicKey(), fee, null);
		TransactionData messageTransactionData = new MessageTransactionData(baseTransactionData, version, 0, recipient,
				amount, assetId, data, false, false);
		MessageTransaction messageTransaction = new MessageTransaction(repository, messageTransactionData);

		messageTransactionData.setFee(messageTransaction.calcRecommendedFee());
		TransactionUtils.signAndMint(repository, messageTransactionData, sender);

		return messageTransaction;
	}

	private static void transferAsset(Repository repository, PrivateKeyAccount sender, String recipient, long assetId, long amount) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, sender.getPublicKey(), AssetUtils.fee, null);
		TransactionData transactionData = new TransferAssetTransactionData(baseTransactionData, recipient, amount, assetId);
		TransactionUtils.signAndMint(repository, transactionData, sender);
	}
}
