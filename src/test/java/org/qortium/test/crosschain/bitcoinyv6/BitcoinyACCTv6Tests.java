package org.qortium.test.crosschain.bitcoinyv6;

import com.google.common.hash.HashCode;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.crosschain.AcctRegistry;
import org.qortium.crosschain.AcctMode;
import org.qortium.crosschain.BitcoinyACCTv4;
import org.qortium.crosschain.BitcoinyACCTv6;
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

public class BitcoinyACCTv6Tests extends Common {

	private static final byte[] MAKER_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("aa00aa11aa22aa33aa44aa55aa66aa77aa88aa99").asBytes();
	private static final byte[] TAKER_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("bb00bb11bb22bb33bb44bb55bb66bb77bb88bb99").asBytes();
	private static final byte[] SECOND_TAKER_FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("cc00cc11cc22cc33cc44cc55cc66cc77cc88cc99").asBytes();
	private static final byte[] SECRET_A = "This string is exactly 32 bytes!".getBytes();
	private static final byte[] HASH_OF_SECRET_A = Crypto.hash160(SECRET_A);
	private static final long TOTAL_LOCAL_AMOUNT = 80L * Amounts.MULTIPLIER;
	private static final long TOTAL_FOREIGN_AMOUNT = 800_000L;
	private static final long MIN_FILL_LOCAL_AMOUNT = 10L * Amounts.MULTIPLIER;
	private static final long MAX_FILL_LOCAL_AMOUNT = 40L * Amounts.MULTIPLIER;
	private static final long FILL_LOCAL_AMOUNT = 25L * Amounts.MULTIPLIER;
	private static final long FILL_FOREIGN_AMOUNT = 250_000L;
	private static final long NATIVE_FEE_RESERVE = 3L * Amounts.MULTIPLIER;
	private static final int TRADE_TIMEOUT = 120;
	private static final int SHORT_TRADE_TIMEOUT = 6;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testBuildsReverseSplitAtWithExpectedInitialTradeData() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-SPLIT-INIT", 100L * Amounts.MULTIPLIER, true);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, TRADE_TIMEOUT);
			Account at = deployAtTransaction.getATAccount();

			ATData atData = repository.getATRepository().fromATAddress(at.getAddress());
			CrossChainTradeData tradeData = BitcoinyACCTv6.getInstance().populateTradeData(repository, atData);

			assertNotNull(tradeData);
			assertArrayEquals(BitcoinyACCTv6.CODE_BYTES_HASH, atData.getCodeHash());
			assertEquals(BitcoinyACCTv6.NAME, tradeData.acctName);
			assertEquals("BITCOIN", tradeData.foreignBlockchain);
			assertEquals(TradeDirection.SELL_FOREIGN, tradeData.tradeDirection);
			assertEquals(AcctMode.OFFERING, tradeData.mode);
			assertTrue(tradeData.isFillableOffer());
			assertEquals(localAssetId, tradeData.localAssetId);
			assertEquals(TOTAL_LOCAL_AMOUNT, tradeData.localAmount);
			assertEquals(TOTAL_LOCAL_AMOUNT, tradeData.totalLocalAmount);
			assertEquals(TOTAL_LOCAL_AMOUNT, tradeData.remainingLocalAmount);
			assertEquals(0L, tradeData.activeLocalAmount);
			assertEquals(0L, tradeData.completedLocalAmount);
			assertEquals(MIN_FILL_LOCAL_AMOUNT, tradeData.minFillLocalAmount);
			assertEquals(MAX_FILL_LOCAL_AMOUNT, tradeData.maxFillLocalAmount);
			assertEquals(0, tradeData.activeFillCount);
			assertEquals(BitcoinyACCTv6.SLOT_COUNT, tradeData.availableFillSlots);
			assertEquals(TOTAL_FOREIGN_AMOUNT, tradeData.expectedForeignAmount);
			assertArrayEquals(MAKER_FOREIGN_PUBLIC_KEY_HASH, tradeData.creatorForeignPKH);
			assertEquals(0, tradeData.fills.size());
		}
	}

	@Test
	public void testLatestBitcoinyAcctStillV4AndV6IsInternalOnly() {
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");

		assertSame(BitcoinyACCTv4.getInstance(), bitcoin.getLatestAcct());
		assertNull(AcctRegistry.getAcctByName(BitcoinyACCTv6.NAME));
		assertNull(AcctRegistry.getAcctByCodeHash(BitcoinyACCTv6.CODE_BYTES_HASH));
	}

	@Test
	public void testReserveForeignLockLocalLockAndMakerRedeemOneFill() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount issuer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount makerReceiving = Common.getTestAccount(repository, "bob");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-SPLIT-REDEEM", 100L * Amounts.MULTIPLIER, true);

			transferAsset(repository, issuer, taker.getAddress(), localAssetId, FILL_LOCAL_AMOUNT);
			long makerReceivingInitialBalance = makerReceiving.getConfirmedBalance(localAssetId);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			reserveFill(repository, taker, atAddress, 0, FILL_LOCAL_AMOUNT, FILL_FOREIGN_AMOUNT);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv6.getInstance().populateTradeData(repository, atData);
			assertEquals(AcctMode.OFFERING, tradeData.mode);
			assertTrue(tradeData.isFillableOffer());
			assertEquals(1, tradeData.activeFillCount);
			assertEquals(BitcoinyACCTv6.SLOT_COUNT - 1, tradeData.availableFillSlots);
			assertEquals(TOTAL_LOCAL_AMOUNT - FILL_LOCAL_AMOUNT, tradeData.remainingLocalAmount);
			assertEquals(0L, tradeData.activeLocalAmount);
			assertEquals(1, tradeData.fills.size());
			assertEquals(taker.getAddress(), tradeData.fills.get(0).partnerAddress);
			assertArrayEquals(TAKER_FOREIGN_PUBLIC_KEY_HASH, tradeData.fills.get(0).partnerForeignPKH);

			int lockTimeA = (int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L);
			declareForeignLock(repository, tradeAccount, atAddress, 0, HASH_OF_SECRET_A, lockTimeA);

			atData = repository.getATRepository().fromATAddress(atAddress);
			tradeData = BitcoinyACCTv6.getInstance().populateTradeData(repository, atData);
			assertEquals(1, tradeData.fills.size());
			assertArrayEquals(HASH_OF_SECRET_A, tradeData.fills.get(0).hashOfSecretA);
			assertEquals(lockTimeA, tradeData.fills.get(0).lockTimeA);
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));

			sendLocalLock(repository, taker, atAddress, 0, FILL_LOCAL_AMOUNT, localAssetId);

			atData = repository.getATRepository().fromATAddress(atAddress);
			tradeData = BitcoinyACCTv6.getInstance().populateTradeData(repository, atData);
			assertEquals(FILL_LOCAL_AMOUNT, tradeData.activeLocalAmount);
			assertEquals(FILL_LOCAL_AMOUNT, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));

			byte[] redeemMessageData = BitcoinyACCTv6.buildRedeemMessage(0, SECRET_A, makerReceiving.getAddress());
			sendMessage(repository, tradeAccount, redeemMessageData, atAddress);
			BlockUtils.mintBlock(repository);

			atData = repository.getATRepository().fromATAddress(atAddress);
			tradeData = BitcoinyACCTv6.getInstance().populateTradeData(repository, atData);

			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.OFFERING, tradeData.mode);
			assertEquals(0, tradeData.activeFillCount);
			assertEquals(TOTAL_LOCAL_AMOUNT - FILL_LOCAL_AMOUNT, tradeData.remainingLocalAmount);
			assertEquals(FILL_LOCAL_AMOUNT, tradeData.completedLocalAmount);
			assertEquals(makerReceivingInitialBalance + FILL_LOCAL_AMOUNT, makerReceiving.getConfirmedBalance(localAssetId));
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));
			assertArrayEquals(SECRET_A, BitcoinyACCTv6.findSecretA(repository, atAddress, 0, tradeAccount.getAddress(), HASH_OF_SECRET_A));
		}
	}

	@Test
	public void testTwoReservedFillsCanProgressIndependently() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount issuer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount firstTaker = Common.getTestAccount(repository, "dilbert");
			PrivateKeyAccount secondTaker = Common.getTestAccount(repository, "bob");
			PrivateKeyAccount makerReceiving = Common.getTestAccount(repository, "chloe");
			long secondFillLocalAmount = MIN_FILL_LOCAL_AMOUNT;
			long secondFillForeignAmount = 100_000L;
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-SPLIT-TWO-FILLS", 100L * Amounts.MULTIPLIER, true);

			transferAsset(repository, issuer, firstTaker.getAddress(), localAssetId, FILL_LOCAL_AMOUNT);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			reserveFill(repository, firstTaker, atAddress, 0, TAKER_FOREIGN_PUBLIC_KEY_HASH, FILL_LOCAL_AMOUNT, FILL_FOREIGN_AMOUNT);
			reserveFill(repository, secondTaker, atAddress, 1, SECOND_TAKER_FOREIGN_PUBLIC_KEY_HASH, secondFillLocalAmount, secondFillForeignAmount);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv6.getInstance().populateTradeData(repository, atData);
			assertEquals(2, tradeData.activeFillCount);
			assertEquals(0, tradeData.availableFillSlots);
			assertEquals(TOTAL_LOCAL_AMOUNT - FILL_LOCAL_AMOUNT - secondFillLocalAmount, tradeData.remainingLocalAmount);
			assertEquals(2, tradeData.fills.size());

			int lockTimeA = (int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L);
			declareForeignLock(repository, tradeAccount, atAddress, 0, HASH_OF_SECRET_A, lockTimeA);
			sendLocalLock(repository, firstTaker, atAddress, 0, FILL_LOCAL_AMOUNT, localAssetId);

			byte[] redeemMessageData = BitcoinyACCTv6.buildRedeemMessage(0, SECRET_A, makerReceiving.getAddress());
			sendMessage(repository, tradeAccount, redeemMessageData, atAddress);
			BlockUtils.mintBlock(repository);

			atData = repository.getATRepository().fromATAddress(atAddress);
			tradeData = BitcoinyACCTv6.getInstance().populateTradeData(repository, atData);

			assertFalse(atData.getIsFinished());
			assertEquals(1, tradeData.activeFillCount);
			assertEquals(BitcoinyACCTv6.SLOT_COUNT - 1, tradeData.availableFillSlots);
			assertEquals(TOTAL_LOCAL_AMOUNT - FILL_LOCAL_AMOUNT - secondFillLocalAmount, tradeData.remainingLocalAmount);
			assertEquals(FILL_LOCAL_AMOUNT, tradeData.completedLocalAmount);
			assertEquals(1, tradeData.fills.size());
			assertEquals(1, tradeData.fills.get(0).slotIndex);
			assertEquals(secondTaker.getAddress(), tradeData.fills.get(0).partnerAddress);
			assertArrayEquals(SECOND_TAKER_FOREIGN_PUBLIC_KEY_HASH, tradeData.fills.get(0).partnerForeignPKH);

			cancelFill(repository, tradeAccount, atAddress, 1);

			atData = repository.getATRepository().fromATAddress(atAddress);
			tradeData = BitcoinyACCTv6.getInstance().populateTradeData(repository, atData);

			assertEquals(0, tradeData.activeFillCount);
			assertEquals(BitcoinyACCTv6.SLOT_COUNT, tradeData.availableFillSlots);
			assertEquals(TOTAL_LOCAL_AMOUNT - FILL_LOCAL_AMOUNT, tradeData.remainingLocalAmount);
			assertEquals(FILL_LOCAL_AMOUNT, tradeData.completedLocalAmount);
			assertEquals(0, tradeData.fills.size());
		}
	}

	@Test
	public void testInvalidLocalLockAmountRefundsAndLeavesFillForeignLocked() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount issuer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-SPLIT-WRONG-AMOUNT", 100L * Amounts.MULTIPLIER, true);
			long wrongAmount = FILL_LOCAL_AMOUNT - Amounts.MULTIPLIER;

			transferAsset(repository, issuer, taker.getAddress(), localAssetId, wrongAmount);
			long takerInitialBalance = taker.getConfirmedBalance(localAssetId);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			reserveFill(repository, taker, atAddress, 0, FILL_LOCAL_AMOUNT, FILL_FOREIGN_AMOUNT);
			declareForeignLock(repository, tradeAccount, atAddress, 0, HASH_OF_SECRET_A,
					(int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L));

			sendLocalLock(repository, taker, atAddress, 0, wrongAmount, localAssetId);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv6.getInstance().populateTradeData(repository, atData);

			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.OFFERING, tradeData.mode);
			assertEquals(1, tradeData.activeFillCount);
			assertEquals(0L, tradeData.activeLocalAmount);
			assertEquals(takerInitialBalance, taker.getConfirmedBalance(localAssetId));
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));
		}
	}

	@Test
	public void testLocalLockRefundsBackToOfferIfMakerDoesNotRedeem() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount issuer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-SPLIT-REFUND", 100L * Amounts.MULTIPLIER, true);

			transferAsset(repository, issuer, taker.getAddress(), localAssetId, FILL_LOCAL_AMOUNT);
			long takerInitialBalance = taker.getConfirmedBalance(localAssetId);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, SHORT_TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			reserveFill(repository, taker, atAddress, 0, FILL_LOCAL_AMOUNT, FILL_FOREIGN_AMOUNT);
			declareForeignLock(repository, tradeAccount, atAddress, 0, HASH_OF_SECRET_A,
					(int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L));
			sendLocalLock(repository, taker, atAddress, 0, FILL_LOCAL_AMOUNT, localAssetId);

			BlockUtils.mintBlocks(repository, BitcoinyACCTv6.calcLocalRefundTimeout(SHORT_TRADE_TIMEOUT) + 2);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv6.getInstance().populateTradeData(repository, atData);

			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.OFFERING, tradeData.mode);
			assertEquals(0, tradeData.activeFillCount);
			assertEquals(BitcoinyACCTv6.SLOT_COUNT, tradeData.availableFillSlots);
			assertEquals(TOTAL_LOCAL_AMOUNT, tradeData.remainingLocalAmount);
			assertEquals(0L, tradeData.activeLocalAmount);
			assertEquals(0L, tradeData.completedLocalAmount);
			assertEquals(takerInitialBalance, taker.getConfirmedBalance(localAssetId));
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));
		}
	}

	@Test
	public void testMakerCanCancelReservedFillBackToOffer() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-SPLIT-CANCEL-FILL", 100L * Amounts.MULTIPLIER, true);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			reserveFill(repository, taker, atAddress, 0, FILL_LOCAL_AMOUNT, FILL_FOREIGN_AMOUNT);

			cancelFill(repository, tradeAccount, atAddress, 0);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv6.getInstance().populateTradeData(repository, atData);

			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.OFFERING, tradeData.mode);
			assertEquals(0, tradeData.activeFillCount);
			assertEquals(BitcoinyACCTv6.SLOT_COUNT, tradeData.availableFillSlots);
			assertEquals(TOTAL_LOCAL_AMOUNT, tradeData.remainingLocalAmount);
			assertEquals(0, tradeData.fills.size());
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));
		}
	}

	@Test
	public void testOfferCancelWaitsForActiveLocalLockRefundBeforeFinishing() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount issuer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "dilbert");
			long localAssetId = AssetUtils.issueAsset(repository, "alice", "REVERSE-SPLIT-CANCEL", 100L * Amounts.MULTIPLIER, true);

			transferAsset(repository, issuer, taker.getAddress(), localAssetId, FILL_LOCAL_AMOUNT);

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, SHORT_TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			reserveFill(repository, taker, atAddress, 0, FILL_LOCAL_AMOUNT, FILL_FOREIGN_AMOUNT);
			declareForeignLock(repository, tradeAccount, atAddress, 0, HASH_OF_SECRET_A,
					(int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L));
			sendLocalLock(repository, taker, atAddress, 0, FILL_LOCAL_AMOUNT, localAssetId);

			cancelTrade(repository, tradeAccount, atAddress);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv6.getInstance().populateTradeData(repository, atData);

			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.CANCELLED, tradeData.mode);
			assertEquals(1, tradeData.activeFillCount);
			assertEquals(FILL_LOCAL_AMOUNT, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));

			BlockUtils.mintBlocks(repository, BitcoinyACCTv6.calcLocalRefundTimeout(SHORT_TRADE_TIMEOUT) + 2);

			atData = repository.getATRepository().fromATAddress(atAddress);
			tradeData = BitcoinyACCTv6.getInstance().populateTradeData(repository, atData);

			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.CANCELLED, tradeData.mode);
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(localAssetId));
		}
	}

	private DeployAtTransaction deploy(Repository repository, PrivateKeyAccount deployer, String tradeAddress, long localAssetId, int tradeTimeout)
			throws DataException {
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");
		byte[] creationBytes = BitcoinyACCTv6.buildTradeAT(bitcoin, tradeAddress, MAKER_FOREIGN_PUBLIC_KEY_HASH,
				TOTAL_LOCAL_AMOUNT, TOTAL_FOREIGN_AMOUNT, MIN_FILL_LOCAL_AMOUNT, MAX_FILL_LOCAL_AMOUNT, tradeTimeout);

		long txTimestamp = TransactionUtils.nextTimestamp(repository);
		Long fee = null;
		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, deployer.getPublicKey(), fee, null);
		TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData,
				"BTC-asset reverse split cross-chain trade", "Bitcoin-local asset reverse split cross-chain trade", "ACCT",
				"BTC-asset reverse split ACCT", creationBytes, 0L, localAssetId, NATIVE_FEE_RESERVE);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		deployAtTransactionData.setFee(deployAtTransaction.calcRecommendedFee());
		TransactionUtils.signAndMint(repository, deployAtTransactionData, deployer);

		return deployAtTransaction;
	}

	private static void reserveFill(Repository repository, PrivateKeyAccount taker, String atAddress, int slotIndex,
			long fillLocalAmount, long fillForeignAmount) throws DataException {
		reserveFill(repository, taker, atAddress, slotIndex, TAKER_FOREIGN_PUBLIC_KEY_HASH, fillLocalAmount, fillForeignAmount);
	}

	private static void reserveFill(Repository repository, PrivateKeyAccount taker, String atAddress, int slotIndex,
			byte[] takerForeignPublicKeyHash, long fillLocalAmount, long fillForeignAmount) throws DataException {
		sendMessage(repository, taker, BitcoinyACCTv6.buildReserveMessage(slotIndex, takerForeignPublicKeyHash, fillLocalAmount, fillForeignAmount),
				atAddress);
		BlockUtils.mintBlock(repository);
	}

	private static void declareForeignLock(Repository repository, PrivateKeyAccount makerTradeAccount, String atAddress, int slotIndex,
			byte[] hashOfSecretA, int lockTimeA) throws DataException {
		sendMessage(repository, makerTradeAccount, BitcoinyACCTv6.buildForeignLockMessage(slotIndex, hashOfSecretA, lockTimeA), atAddress);
		BlockUtils.mintBlock(repository);
	}

	private static void sendLocalLock(Repository repository, PrivateKeyAccount taker, String atAddress, int slotIndex, long amount, long assetId)
			throws DataException {
		sendPaymentMessage(repository, taker, BitcoinyACCTv6.buildLocalLockMessage(slotIndex), atAddress, amount, assetId);
		BlockUtils.mintBlock(repository);
	}

	private static void cancelFill(Repository repository, PrivateKeyAccount sender, String atAddress, int slotIndex) throws DataException {
		sendMessage(repository, sender, BitcoinyACCTv6.buildCancelFillMessage(slotIndex, sender.getAddress()), atAddress);
		BlockUtils.mintBlock(repository);
	}

	private static void cancelTrade(Repository repository, PrivateKeyAccount sender, String atAddress) throws DataException {
		sendMessage(repository, sender, BitcoinyACCTv6.getInstance().buildCancelMessage(sender.getAddress()), atAddress);
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

	private static void transferAsset(Repository repository, PrivateKeyAccount sender, String recipient, long assetId, long amount)
			throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);
		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, sender.getPublicKey(), AssetUtils.fee, null);
		TransactionData transactionData = new TransferAssetTransactionData(baseTransactionData, recipient, amount, assetId);
		TransactionUtils.signAndMint(repository, transactionData, sender);
	}
}
