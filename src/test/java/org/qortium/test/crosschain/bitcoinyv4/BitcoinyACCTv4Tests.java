package org.qortium.test.crosschain.bitcoinyv4;

import com.google.common.hash.HashCode;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.crosschain.AcctMode;
import org.qortium.crosschain.BitcoinyACCTv4;
import org.qortium.crosschain.ForeignBlockchainRegistry;
import org.qortium.crosschain.TradeDirection;
import org.qortium.crypto.Crypto;
import org.qortium.data.at.ATData;
import org.qortium.data.crosschain.CrossChainTradeData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.DeployAtTransactionData;
import org.qortium.data.transaction.MessageTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.MessageTransaction;

import static org.junit.Assert.*;

public class BitcoinyACCTv4Tests extends Common {

	private static final byte[] FOREIGN_PUBLIC_KEY_HASH = HashCode.fromString("bb00bb11bb22bb33bb44bb55bb66bb77bb88bb99").asBytes();
	private static final byte[] SECRET_A = "This string is exactly 32 bytes!".getBytes();
	private static final byte[] HASH_OF_SECRET_A = Crypto.hash160(SECRET_A);
	private static final long TOTAL_LOCAL_AMOUNT = 80_40200000L;
	private static final long FUNDING_AMOUNT = 123_45600000L;
	private static final long TOTAL_FOREIGN_AMOUNT = 864200L;
	private static final long MIN_FILL_LOCAL_AMOUNT = 10_00000000L;
	private static final long MAX_FILL_LOCAL_AMOUNT = 40_00000000L;
	private static final int TRADE_TIMEOUT = 10080;
	private static final int SHORT_TRADE_TIMEOUT = 4;
	private static final int CANCEL_WAIT_TRADE_TIMEOUT = 10;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testSplitFillLockAndRedeem() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount partner = Common.getTestAccount(repository, "dilbert");

			long partnerInitialBalance = partner.getConfirmedBalance(Asset.NATIVE);
			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress());
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			long offerMessageTimestamp = TransactionUtils.nextTimestamp(repository);
			int lockTimeA = (int) (offerMessageTimestamp / 1000L + TRADE_TIMEOUT * 60);
			int refundTimeout = BitcoinyACCTv4.calcRefundTimeout(offerMessageTimestamp, lockTimeA);
			long fillForeignAmount = 123456L;

			byte[] lockMessageData = BitcoinyACCTv4.buildTradeMessage(0, partner.getAddress(), FOREIGN_PUBLIC_KEY_HASH,
					HASH_OF_SECRET_A, lockTimeA, refundTimeout, MIN_FILL_LOCAL_AMOUNT, fillForeignAmount);
			sendMessage(repository, tradeAccount, lockMessageData, atAddress, offerMessageTimestamp);
			BlockUtils.mintBlock(repository);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv4.getInstance().populateTradeData(repository, atData);

			assertEquals(AcctMode.OFFERING, tradeData.mode);
			assertEquals(1, tradeData.activeFillCount);
			assertEquals(BitcoinyACCTv4.SLOT_COUNT - 1, tradeData.availableFillSlots);
			assertEquals(TOTAL_LOCAL_AMOUNT - MIN_FILL_LOCAL_AMOUNT, tradeData.remainingLocalAmount);
			assertEquals(MIN_FILL_LOCAL_AMOUNT, tradeData.activeLocalAmount);
			assertEquals(0L, tradeData.completedLocalAmount);
			assertEquals(1, tradeData.fills.size());
			assertEquals(0, tradeData.fills.get(0).slotIndex);
			assertEquals(partner.getAddress(), tradeData.fills.get(0).partnerAddress);
			assertArrayEquals(HASH_OF_SECRET_A, tradeData.fills.get(0).hashOfSecretA);
			assertEquals(MIN_FILL_LOCAL_AMOUNT, tradeData.fills.get(0).localAmount);
			assertEquals(fillForeignAmount, tradeData.fills.get(0).expectedForeignAmount);

			byte[] redeemMessageData = BitcoinyACCTv4.buildRedeemMessage(0, SECRET_A, partner.getAddress());
			MessageTransaction redeemMessageTransaction = sendMessage(repository, partner, redeemMessageData, atAddress);
			BlockUtils.mintBlock(repository);

			atData = repository.getATRepository().fromATAddress(atAddress);
			tradeData = BitcoinyACCTv4.getInstance().populateTradeData(repository, atData);

			assertEquals(AcctMode.OFFERING, tradeData.mode);
			assertEquals(0, tradeData.activeFillCount);
			assertEquals(BitcoinyACCTv4.SLOT_COUNT, tradeData.availableFillSlots);
			assertEquals(TOTAL_LOCAL_AMOUNT - MIN_FILL_LOCAL_AMOUNT, tradeData.remainingLocalAmount);
			assertEquals(0L, tradeData.activeLocalAmount);
			assertEquals(MIN_FILL_LOCAL_AMOUNT, tradeData.completedLocalAmount);
			assertEquals(0, tradeData.fills.size());
			assertEquals(partnerInitialBalance - redeemMessageTransaction.getTransactionData().getFee() + MIN_FILL_LOCAL_AMOUNT,
					partner.getConfirmedBalance(Asset.NATIVE));
			assertTrue(tradeData.isFillableOffer());
		}
	}

	@Test
	public void testOfferCancelFinishesAndRefundsUnfilledOffer() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");

			long deployerInitialBalance = deployer.getConfirmedBalance(Asset.NATIVE);
			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress());
			String atAddress = deployAtTransaction.getATAccount().getAddress();
			long deployerPostDeployBalance = deployerInitialBalance - FUNDING_AMOUNT - deployAtTransaction.getTransactionData().getFee();

			byte[] cancelMessageData = BitcoinyACCTv4.getInstance().buildCancelMessage(deployer.getAddress());
			MessageTransaction cancelMessageTransaction = sendMessage(repository, deployer, cancelMessageData, atAddress);
			BlockUtils.mintBlock(repository);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv4.getInstance().populateTradeData(repository, atData);

			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.CANCELLED, tradeData.mode);
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(Asset.NATIVE));
			assertTrue(deployer.getConfirmedBalance(Asset.NATIVE) > deployerPostDeployBalance - cancelMessageTransaction.getTransactionData().getFee());
			assertFalse(tradeData.isFillableOffer());
		}
	}

	@Test
	public void testActiveFillRefundsBackToOffer() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount partner = Common.getTestAccount(repository, "dilbert");

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(),
					TOTAL_LOCAL_AMOUNT, TOTAL_FOREIGN_AMOUNT, MIN_FILL_LOCAL_AMOUNT, MAX_FILL_LOCAL_AMOUNT, SHORT_TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			int refundTimeout = lockFill(repository, tradeAccount, partner, atAddress, 0, MIN_FILL_LOCAL_AMOUNT, 123456L, SHORT_TRADE_TIMEOUT);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv4.getInstance().populateTradeData(repository, atData);

			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.OFFERING, tradeData.mode);
			assertEquals(1, tradeData.activeFillCount);
			assertEquals(TOTAL_LOCAL_AMOUNT - MIN_FILL_LOCAL_AMOUNT, tradeData.remainingLocalAmount);
			assertEquals(MIN_FILL_LOCAL_AMOUNT, tradeData.activeLocalAmount);

			BlockUtils.mintBlocks(repository, refundTimeout + 2);

			atData = repository.getATRepository().fromATAddress(atAddress);
			tradeData = BitcoinyACCTv4.getInstance().populateTradeData(repository, atData);

			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.OFFERING, tradeData.mode);
			assertEquals(0, tradeData.activeFillCount);
			assertEquals(BitcoinyACCTv4.SLOT_COUNT, tradeData.availableFillSlots);
			assertEquals(TOTAL_LOCAL_AMOUNT, tradeData.remainingLocalAmount);
			assertEquals(0L, tradeData.activeLocalAmount);
			assertEquals(0L, tradeData.completedLocalAmount);
			assertEquals(0, tradeData.fills.size());
			assertTrue(tradeData.isFillableOffer());
		}
	}

	@Test
	public void testCancelWaitsForActiveFillRefundBeforeFinishing() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount partner = Common.getTestAccount(repository, "dilbert");

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(),
					TOTAL_LOCAL_AMOUNT, TOTAL_FOREIGN_AMOUNT, MIN_FILL_LOCAL_AMOUNT, MAX_FILL_LOCAL_AMOUNT, CANCEL_WAIT_TRADE_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			int refundTimeout = lockFill(repository, tradeAccount, partner, atAddress, 0, MIN_FILL_LOCAL_AMOUNT, 123456L, CANCEL_WAIT_TRADE_TIMEOUT);

			byte[] cancelMessageData = BitcoinyACCTv4.getInstance().buildCancelMessage(deployer.getAddress());
			sendMessage(repository, deployer, cancelMessageData, atAddress);
			BlockUtils.mintBlock(repository);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv4.getInstance().populateTradeData(repository, atData);

			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.CANCELLED, tradeData.mode);
			assertEquals(1, tradeData.activeFillCount);
			assertEquals(TOTAL_LOCAL_AMOUNT - MIN_FILL_LOCAL_AMOUNT, tradeData.remainingLocalAmount);

			BlockUtils.mintBlocks(repository, refundTimeout + 2);

			atData = repository.getATRepository().fromATAddress(atAddress);
			tradeData = BitcoinyACCTv4.getInstance().populateTradeData(repository, atData);

			assertTrue(atData.getIsFinished());
			assertEquals(AcctMode.CANCELLED, tradeData.mode);
			assertEquals(0L, deployAtTransaction.getATAccount().getConfirmedBalance(Asset.NATIVE));
			assertFalse(tradeData.isFillableOffer());
		}
	}

	@Test
	public void testBuildsSplitFillAtWithExpectedInitialTradeData() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "chloe");
			PrivateKeyAccount tradeAccount = Common.getTestAccount(repository, "alice");

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress());
			Account at = deployAtTransaction.getATAccount();

			ATData atData = repository.getATRepository().fromATAddress(at.getAddress());
			CrossChainTradeData tradeData = BitcoinyACCTv4.getInstance().populateTradeData(repository, atData);

			assertNotNull(tradeData);
			assertArrayEquals(BitcoinyACCTv4.CODE_BYTES_HASH, atData.getCodeHash());
			assertEquals(BitcoinyACCTv4.NAME, tradeData.acctName);
			assertEquals("BITCOIN", tradeData.foreignBlockchain);
			assertEquals(TradeDirection.SELL_LOCAL, tradeData.tradeDirection);
			assertEquals(AcctMode.OFFERING, tradeData.mode);
			assertTrue(tradeData.isFillableOffer());
			assertEquals(Asset.NATIVE, tradeData.localAssetId);
			assertEquals(TOTAL_LOCAL_AMOUNT, tradeData.localAmount);
			assertEquals(TOTAL_LOCAL_AMOUNT, tradeData.totalLocalAmount);
			assertEquals(TOTAL_LOCAL_AMOUNT, tradeData.remainingLocalAmount);
			assertEquals(0L, tradeData.activeLocalAmount);
			assertEquals(0L, tradeData.completedLocalAmount);
			assertEquals(MIN_FILL_LOCAL_AMOUNT, tradeData.minFillLocalAmount);
			assertEquals(MAX_FILL_LOCAL_AMOUNT, tradeData.maxFillLocalAmount);
			assertEquals(0, tradeData.activeFillCount);
			assertEquals(BitcoinyACCTv4.SLOT_COUNT, tradeData.availableFillSlots);
			assertEquals(TOTAL_FOREIGN_AMOUNT, tradeData.expectedForeignAmount);
			assertEquals(0, tradeData.fills.size());
		}
	}

	@Test
	public void testLatestBitcoinyAcctIsSplitFillV4() {
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");

		assertSame(BitcoinyACCTv4.getInstance(), bitcoin.getLatestAcct());
		assertArrayEquals(BitcoinyACCTv4.CODE_BYTES_HASH, bitcoin.getLatestAcct().getCodeBytesHash());
	}

	@Test
	public void testOfferMessageRoundTrip() {
		int lockTimeA = 1777777777;
		long fillLocalAmount = 12_34500000L;
		long fillForeignAmount = 123456L;

		byte[] messageData = BitcoinyACCTv4.buildOfferMessage(FOREIGN_PUBLIC_KEY_HASH, HASH_OF_SECRET_A, lockTimeA,
				fillLocalAmount, fillForeignAmount);
		BitcoinyACCTv4.OfferMessageData offerMessageData = BitcoinyACCTv4.extractOfferMessageData(messageData);

		assertNotNull(offerMessageData);
		assertArrayEquals(FOREIGN_PUBLIC_KEY_HASH, offerMessageData.partnerForeignPKH);
		assertArrayEquals(HASH_OF_SECRET_A, offerMessageData.hashOfSecretA);
		assertEquals(lockTimeA, offerMessageData.lockTimeA);
		assertEquals(fillLocalAmount, offerMessageData.fillLocalAmount);
		assertEquals(fillForeignAmount, offerMessageData.fillForeignAmount);
		assertNull(BitcoinyACCTv4.extractOfferMessageData(new byte[BitcoinyACCTv4.OFFER_MESSAGE_LENGTH - 1]));
	}

	@Test
	public void testFillableOfferUsesRemainingAmountAndSlots() {
		CrossChainTradeData tradeData = new CrossChainTradeData();
		tradeData.mode = AcctMode.OFFERING;
		tradeData.totalLocalAmount = TOTAL_LOCAL_AMOUNT;
		tradeData.remainingLocalAmount = TOTAL_LOCAL_AMOUNT;
		tradeData.minFillLocalAmount = MIN_FILL_LOCAL_AMOUNT;
		tradeData.maxFillLocalAmount = MAX_FILL_LOCAL_AMOUNT;
		tradeData.availableFillSlots = 1;
		assertTrue(tradeData.isFillableOffer());
		assertTrue(tradeData.isFillableAmount(MIN_FILL_LOCAL_AMOUNT));
		assertTrue(tradeData.isFillableAmount(MAX_FILL_LOCAL_AMOUNT));

		tradeData.remainingLocalAmount = MIN_FILL_LOCAL_AMOUNT - 1;
		assertFalse(tradeData.isFillableOffer());

		tradeData.remainingLocalAmount = TOTAL_LOCAL_AMOUNT;
		tradeData.availableFillSlots = 0;
		assertFalse(tradeData.isFillableOffer());

		tradeData.mode = AcctMode.REDEEMED;
		tradeData.availableFillSlots = 1;
		assertFalse(tradeData.isFillableOffer());

		tradeData.mode = AcctMode.OFFERING;
		tradeData.remainingLocalAmount = 35_00000000L;
		tradeData.availableFillSlots = 1;
		assertTrue(tradeData.isFillableAmount(35_00000000L));
		assertTrue(tradeData.isFillableAmount(25_00000000L));
		assertFalse(tradeData.isFillableAmount(30_00000000L));

		tradeData.remainingLocalAmount = 100_00000000L;
		tradeData.minFillLocalAmount = 60_00000000L;
		tradeData.maxFillLocalAmount = 70_00000000L;
		assertFalse(tradeData.isFillableOffer());
	}

	private DeployAtTransaction deploy(Repository repository, PrivateKeyAccount deployer, String tradeAddress) throws DataException {
		return deploy(repository, deployer, tradeAddress, TOTAL_LOCAL_AMOUNT, TOTAL_FOREIGN_AMOUNT,
				MIN_FILL_LOCAL_AMOUNT, MAX_FILL_LOCAL_AMOUNT, TRADE_TIMEOUT);
	}

	private DeployAtTransaction deploy(Repository repository, PrivateKeyAccount deployer, String tradeAddress,
			long totalLocalAmount, long totalForeignAmount, long minFillLocalAmount, long maxFillLocalAmount, int tradeTimeout) throws DataException {
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");
		byte[] creationBytes = BitcoinyACCTv4.buildTradeAT(bitcoin, tradeAddress, FOREIGN_PUBLIC_KEY_HASH,
				totalLocalAmount, totalForeignAmount, minFillLocalAmount, maxFillLocalAmount, tradeTimeout);

		long txTimestamp = TransactionUtils.nextTimestamp(repository);
		Long fee = null;
		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, deployer.getPublicKey(), fee, null);
		TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData,
				"NATIVE-BTC split cross-chain trade", "Local-chain-Bitcoin split cross-chain trade", "ACCT",
				"NATIVE-BTC split ACCT", creationBytes, FUNDING_AMOUNT, Asset.NATIVE);

		DeployAtTransaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);
		deployAtTransactionData.setFee(deployAtTransaction.calcRecommendedFee());
		TransactionUtils.signAndMint(repository, deployAtTransactionData, deployer);

		return deployAtTransaction;
	}

	private int lockFill(Repository repository, PrivateKeyAccount tradeAccount, PrivateKeyAccount partner, String atAddress,
			int slotIndex, long fillLocalAmount, long fillForeignAmount, int tradeTimeout) throws DataException {
		long offerMessageTimestamp = TransactionUtils.nextTimestamp(repository);
		int lockTimeA = (int) (offerMessageTimestamp / 1000L + tradeTimeout * 60);
		int refundTimeout = BitcoinyACCTv4.calcRefundTimeout(offerMessageTimestamp, lockTimeA);

		byte[] lockMessageData = BitcoinyACCTv4.buildTradeMessage(slotIndex, partner.getAddress(), FOREIGN_PUBLIC_KEY_HASH,
				HASH_OF_SECRET_A, lockTimeA, refundTimeout, fillLocalAmount, fillForeignAmount);
		sendMessage(repository, tradeAccount, lockMessageData, atAddress, offerMessageTimestamp);
		BlockUtils.mintBlock(repository);

		return refundTimeout;
	}

	private MessageTransaction sendMessage(Repository repository, PrivateKeyAccount sender, byte[] data, String recipient) throws DataException {
		return sendMessage(repository, sender, data, recipient, TransactionUtils.nextTimestamp(repository));
	}

	private MessageTransaction sendMessage(Repository repository, PrivateKeyAccount sender, byte[] data, String recipient, long txTimestamp)
			throws DataException {
		Long fee = null;
		int version = org.qortium.transaction.Transaction.getVersionByTimestamp(txTimestamp);

		BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, sender.getPublicKey(), fee, null);
		TransactionData messageTransactionData = new MessageTransactionData(baseTransactionData, version, 0, recipient,
				0, null, data, false, false);
		MessageTransaction messageTransaction = new MessageTransaction(repository, messageTransactionData);

		messageTransactionData.setFee(messageTransaction.calcRecommendedFee());
		TransactionUtils.signAndMint(repository, messageTransactionData, sender);

		return messageTransaction;
	}
}
