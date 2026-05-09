package org.qortal.test.crosschain.bitcoinyv5;

import com.google.common.hash.HashCode;
import org.junit.Before;
import org.junit.Test;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.crosschain.AcctMode;
import org.qortal.crosschain.BitcoinyACCTv4;
import org.qortal.crosschain.BitcoinyACCTv5;
import org.qortal.crosschain.ForeignBlockchainRegistry;
import org.qortal.crosschain.TradeDirection;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.DeployAtTransactionData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.TransferAssetTransactionData;
import org.qortal.group.Group;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.AssetUtils;
import org.qortal.test.common.BlockUtils;
import org.qortal.test.common.Common;
import org.qortal.test.common.TransactionUtils;
import org.qortal.transaction.DeployAtTransaction;
import org.qortal.transaction.MessageTransaction;
import org.qortal.transaction.Transaction;
import org.qortal.utils.Amounts;

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
	private static final int SHORT_REFUND_TIMEOUT = 3;

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
		}
	}

	@Test
	public void testLatestBitcoinyAcctStillV4AndV5IsRegistered() {
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");

		assertSame(BitcoinyACCTv4.getInstance(), bitcoin.getLatestAcct());
		assertSame(BitcoinyACCTv5.getInstance(), ForeignBlockchainRegistry.getAcctByName(BitcoinyACCTv5.NAME));
		assertSame(BitcoinyACCTv5.getInstance(), ForeignBlockchainRegistry.getAcctByCodeHash(BitcoinyACCTv5.CODE_BYTES_HASH));
	}

	@Test
	public void testReverseLockAndMakerRedeem() throws DataException {
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

			int lockTimeA = (int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L);
			byte[] lockMessageData = BitcoinyACCTv5.buildLockMessage(TAKER_FOREIGN_PUBLIC_KEY_HASH, HASH_OF_SECRET_A, lockTimeA, SHORT_REFUND_TIMEOUT);
			sendPaymentMessage(repository, taker, lockMessageData, atAddress, LOCAL_AMOUNT, localAssetId);
			BlockUtils.mintBlock(repository);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

			assertEquals(AcctMode.TRADING, tradeData.mode);
			assertEquals(taker.getAddress(), tradeData.partnerAddress);
			assertArrayEquals(TAKER_FOREIGN_PUBLIC_KEY_HASH, tradeData.partnerForeignPKH);
			assertArrayEquals(HASH_OF_SECRET_A, tradeData.hashOfSecretA);
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
		}
	}

	@Test
	public void testInvalidLockAmountRefundsAndStaysOffering() throws DataException {
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

			byte[] lockMessageData = BitcoinyACCTv5.buildLockMessage(TAKER_FOREIGN_PUBLIC_KEY_HASH, HASH_OF_SECRET_A,
					(int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L), SHORT_REFUND_TIMEOUT);
			sendPaymentMessage(repository, taker, lockMessageData, atAddress, wrongAmount, localAssetId);
			BlockUtils.mintBlock(repository);

			ATData atData = repository.getATRepository().fromATAddress(atAddress);
			CrossChainTradeData tradeData = BitcoinyACCTv5.getInstance().populateTradeData(repository, atData);

			assertFalse(atData.getIsFinished());
			assertEquals(AcctMode.OFFERING, tradeData.mode);
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

			DeployAtTransaction deployAtTransaction = deploy(repository, deployer, tradeAccount.getAddress(), localAssetId, SHORT_REFUND_TIMEOUT);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			byte[] lockMessageData = BitcoinyACCTv5.buildLockMessage(TAKER_FOREIGN_PUBLIC_KEY_HASH, HASH_OF_SECRET_A,
					(int) (System.currentTimeMillis() / 1000L + TRADE_TIMEOUT * 60L), SHORT_REFUND_TIMEOUT);
			sendPaymentMessage(repository, taker, lockMessageData, atAddress, LOCAL_AMOUNT, localAssetId);
			BlockUtils.mintBlock(repository);

			BlockUtils.mintBlocks(repository, SHORT_REFUND_TIMEOUT + 2);

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

	private DeployAtTransaction deploy(Repository repository, PrivateKeyAccount deployer, String tradeAddress, long localAssetId, int tradeTimeout) throws DataException {
		ForeignBlockchainRegistry.Entry bitcoin = ForeignBlockchainRegistry.fromString("BITCOIN");
		byte[] creationBytes = BitcoinyACCTv5.buildTradeAT(bitcoin, tradeAddress, MAKER_FOREIGN_PUBLIC_KEY_HASH,
				LOCAL_AMOUNT, FOREIGN_AMOUNT, tradeTimeout);

		long txTimestamp = System.currentTimeMillis();
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

	private static MessageTransaction sendMessage(Repository repository, PrivateKeyAccount sender, byte[] data, String recipient) throws DataException {
		return sendPaymentMessage(repository, sender, data, recipient, 0L, null);
	}

	private static MessageTransaction sendPaymentMessage(Repository repository, PrivateKeyAccount sender, byte[] data, String recipient,
			long amount, Long assetId) throws DataException {
		long txTimestamp = System.currentTimeMillis();
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
