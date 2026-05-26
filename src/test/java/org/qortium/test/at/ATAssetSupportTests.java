package org.qortium.test.at;

import com.google.common.primitives.Bytes;
import org.ciyam.at.API.ATTransactionType;
import org.ciyam.at.CompilationException;
import org.ciyam.at.FunctionCode;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.junit.Before;
import org.junit.Test;
import org.qortium.account.Account;
import org.qortium.account.PrivateKeyAccount;
import org.qortium.asset.Asset;
import org.qortium.at.ChainFunctionCode;
import org.qortium.block.BlockChain;
import org.qortium.data.PaymentData;
import org.qortium.data.at.ATStateData;
import org.qortium.data.transaction.BaseTransactionData;
import org.qortium.data.transaction.IssueAssetTransactionData;
import org.qortium.data.transaction.MessageTransactionData;
import org.qortium.data.transaction.MultiPaymentTransactionData;
import org.qortium.data.transaction.TransactionData;
import org.qortium.data.transaction.TransferAssetTransactionData;
import org.qortium.group.Group;
import org.qortium.repository.DataException;
import org.qortium.repository.Repository;
import org.qortium.repository.RepositoryManager;
import org.qortium.test.common.AssetUtils;
import org.qortium.test.common.AtUtils;
import org.qortium.test.common.BlockUtils;
import org.qortium.test.common.Common;
import org.qortium.test.common.TransactionUtils;
import org.qortium.transaction.DeployAtTransaction;
import org.qortium.transaction.MessageTransaction;
import org.qortium.transaction.MultiPaymentTransaction;
import org.qortium.transaction.Transaction;
import org.qortium.transaction.Transaction.ValidationResult;
import org.qortium.utils.Amounts;
import org.qortium.utils.Base58;
import org.qortium.utils.BitTwiddling;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ATAssetSupportTests extends Common {

	private static final long ASSET_AMOUNT = 25L * Amounts.MULTIPLIER;
	private static final long PAYOUT_AMOUNT = 7L * Amounts.MULTIPLIER;
	private static final long NATIVE_FEE_RESERVE = 2L * Amounts.MULTIPLIER;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@Test
	public void testDeployFundsConfiguredAssetAndNativeFeeReserve() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long assetId = AssetUtils.issueAsset(repository, "alice", "AT-PRIMARY", 100L * Amounts.MULTIPLIER, true);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, AtUtils.buildSimpleAT(), ASSET_AMOUNT, assetId, NATIVE_FEE_RESERVE);
			Account atAccount = deployAtTransaction.getATAccount();

			assertEquals(ASSET_AMOUNT, atAccount.getConfirmedBalance(assetId));
			assertEquals(NATIVE_FEE_RESERVE, atAccount.getConfirmedBalance(Asset.NATIVE));
		}
	}

	@Test
	public void testConfiguredAssetBalanceIsVisibleAndNativeFeesAreChargedSeparately() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long assetId = AssetUtils.issueAsset(repository, "alice", "AT-BALANCE", 100L * Amounts.MULTIPLIER, true);

			byte[] creationBytes = buildCurrentBalanceAT();
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, ASSET_AMOUNT, assetId, NATIVE_FEE_RESERVE);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);
			long extractedBalance = extractLong(atStateData, 0);

			assertEquals(ASSET_AMOUNT, extractedBalance);
			assertEquals(ASSET_AMOUNT, atAccount.getConfirmedBalance(assetId));
			assertEquals(NATIVE_FEE_RESERVE - atStateData.getFees(), atAccount.getConfirmedBalance(Asset.NATIVE));
		}
	}

	@Test
	public void testNativeFeeTopUpIsAllowed() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-NATIVE-TOP-UP", 100L * Amounts.MULTIPLIER, true);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, AtUtils.buildSimpleAT(), ASSET_AMOUNT, configuredAssetId, NATIVE_FEE_RESERVE);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			transferAsset(repository, deployer, atAddress, Asset.NATIVE, PAYOUT_AMOUNT);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(NATIVE_FEE_RESERVE + PAYOUT_AMOUNT - atStateData.getFees(), atAccount.getConfirmedBalance(Asset.NATIVE));
		}
	}

	@Test
	public void testNonConfiguredAssetTransferToAtIsAcceptedAndVisible() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-CONFIGURED-ONLY", 100L * Amounts.MULTIPLIER, true);
			long otherAssetId = AssetUtils.issueAsset(repository, "alice", "AT-OTHER-INCOMING", 100L * Amounts.MULTIPLIER, true);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, buildAssetBalanceReaderAT(otherAssetId), 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			transferAsset(repository, deployer, atAddress, otherAssetId, PAYOUT_AMOUNT);
			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(PAYOUT_AMOUNT, extractLong(atStateData, 0));
			assertEquals(PAYOUT_AMOUNT, atAccount.getConfirmedBalance(otherAssetId));
		}
	}

	@Test
	public void testUnspendableAssetTransferToAtIsRejected() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-UNSPENDABLE-CONFIGURED", 100L * Amounts.MULTIPLIER, true);
			long unspendableAssetId = issueAsset(repository, deployer, "AT-UNSPENDABLE-INCOMING", 100L * Amounts.MULTIPLIER, true, true);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, AtUtils.buildSimpleAT(), ASSET_AMOUNT, configuredAssetId, NATIVE_FEE_RESERVE);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			assertEquals(ValidationResult.ASSET_NOT_SPENDABLE, importTransferAsset(repository, deployer, atAddress, unspendableAssetId, PAYOUT_AMOUNT));
		}
	}

	@Test
	public void testAtCanPayConfiguredNonNativeAsset() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "bob");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-CONFIGURED-PAYOUT", 100L * Amounts.MULTIPLIER, true);

			byte[] creationBytes = buildPayAssetToAddressAT(recipient.getAddress(), configuredAssetId, PAYOUT_AMOUNT);
			long recipientInitialBalance = recipient.getConfirmedBalance(configuredAssetId);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			transferAsset(repository, deployer, atAddress, configuredAssetId, PAYOUT_AMOUNT);

			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);
			long extractedPaidAmount = extractLong(atStateData, 0);

			assertEquals(PAYOUT_AMOUNT, extractedPaidAmount);
			assertEquals(recipientInitialBalance + PAYOUT_AMOUNT, recipient.getConfirmedBalance(configuredAssetId));
			assertEquals(0L, atAccount.getConfirmedBalance(configuredAssetId));
			assertEquals(0L, atAccount.getConfirmedBalance(Asset.NATIVE));
		}
	}

	@Test
	public void testAtCanPayNonConfiguredSpendableAsset() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "bob");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-OTHER-PAYOUT-CONFIGURED", 100L * Amounts.MULTIPLIER, true);
			long otherAssetId = AssetUtils.issueAsset(repository, "alice", "AT-OTHER-PAYOUT", 100L * Amounts.MULTIPLIER, true);

			byte[] creationBytes = buildPayAssetToAddressAT(recipient.getAddress(), otherAssetId, PAYOUT_AMOUNT);
			long recipientInitialBalance = recipient.getConfirmedBalance(otherAssetId);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			transferAsset(repository, deployer, atAddress, otherAssetId, PAYOUT_AMOUNT);

			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);
			long extractedPaidAmount = extractLong(atStateData, 0);

			assertEquals(PAYOUT_AMOUNT, extractedPaidAmount);
			assertEquals(recipientInitialBalance + PAYOUT_AMOUNT, recipient.getConfirmedBalance(otherAssetId));
			assertEquals(0L, atAccount.getConfirmedBalance(otherAssetId));
			assertEquals(0L, atAccount.getConfirmedBalance(Asset.NATIVE));
		}
	}

	@Test
	public void testAtCanBindIncomingAssetEscrowToSenderAndRefund() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount issuer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount taker = Common.getTestAccount(repository, "bob");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-REVERSE-ESCROW", 100L * Amounts.MULTIPLIER, true);

			transferAsset(repository, issuer, taker.getAddress(), configuredAssetId, PAYOUT_AMOUNT);
			long takerInitialBalance = taker.getConfirmedBalance(configuredAssetId);

			byte[] creationBytes = buildSenderBoundAssetRefundAT(configuredAssetId, PAYOUT_AMOUNT);
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, issuer, creationBytes, 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			// First AT execution sees no taker payment and stops for the next block.
			BlockUtils.mintBlock(repository);

			transferAsset(repository, taker, atAddress, configuredAssetId, PAYOUT_AMOUNT);
			assertEquals(takerInitialBalance - PAYOUT_AMOUNT, taker.getConfirmedBalance(configuredAssetId));

			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(configuredAssetId, extractLong(atStateData, 0));
			assertEquals(PAYOUT_AMOUNT, extractLong(atStateData, 1));
			assertEquals(PAYOUT_AMOUNT, extractLong(atStateData, 2));
			assertEquals(takerInitialBalance, taker.getConfirmedBalance(configuredAssetId));
			assertEquals(0L, atAccount.getConfirmedBalance(configuredAssetId));
		}
	}

	@Test
	public void testNonNativeAtCanPayNativeSurplusAfterFees() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "bob");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-NATIVE-SURPLUS-PAYOUT", 100L * Amounts.MULTIPLIER, true);
			long nativeTopUp = 3L * Amounts.MULTIPLIER;
			long initialNativeBalance = NATIVE_FEE_RESERVE + nativeTopUp;

			byte[] creationBytes = buildPayAssetToAddressAT(recipient.getAddress(), Asset.NATIVE, initialNativeBalance);
			long recipientInitialBalance = recipient.getConfirmedBalance(Asset.NATIVE);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			Account atAccount = deployAtTransaction.getATAccount();
			String atAddress = atAccount.getAddress();

			transferAsset(repository, deployer, atAddress, Asset.NATIVE, nativeTopUp);
			long nativeBalanceBeforePayout = atAccount.getConfirmedBalance(Asset.NATIVE);

			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);
			long extractedPaidAmount = extractLong(atStateData, 0);
			BlockChain.CiyamAtSettings ciyamAtSettings = BlockChain.getInstance().getCiyamAtSettings();
			long expectedPaidAmount = nativeBalanceBeforePayout - ciyamAtSettings.maxStepsPerRound * ciyamAtSettings.feePerStep;

			assertEquals(expectedPaidAmount, extractedPaidAmount);
			assertEquals(recipientInitialBalance + expectedPaidAmount, recipient.getConfirmedBalance(Asset.NATIVE));
			assertEquals(0L, atAccount.getConfirmedBalance(Asset.NATIVE));
		}
	}

	@Test
	public void testTransferAssetTransactionsAreVisibleToAts() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-INCOMING-CONFIGURED", 100L * Amounts.MULTIPLIER, true);

			byte[] creationBytes = buildIncomingAssetReaderAT();
			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, creationBytes, 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// First AT execution sees no incoming transfer and stops for the next block.
			BlockUtils.mintBlock(repository);

			transferAsset(repository, deployer, atAddress, configuredAssetId, PAYOUT_AMOUNT);
			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(PAYOUT_AMOUNT, extractLong(atStateData, 0));
			assertEquals(configuredAssetId, extractLong(atStateData, 1));
		}
	}

	@Test
	public void testMultiPaymentTransactionIsVisibleToAts() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-MULTIPAYMENT", 100L * Amounts.MULTIPLIER, true);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, buildIncomingAssetReaderAT(), 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// First AT execution sees no incoming transfer and stops for the next block.
			BlockUtils.mintBlock(repository);

			sendMultiPayment(repository, deployer, List.of(new PaymentData(atAddress, configuredAssetId, PAYOUT_AMOUNT)));
			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(PAYOUT_AMOUNT, extractLong(atStateData, 0));
			assertEquals(configuredAssetId, extractLong(atStateData, 1));
		}
	}

	@Test
	public void testMultiPaymentEntriesToAtAreSummedWhenAssetMatches() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-MULTIPAYMENT-SUM", 100L * Amounts.MULTIPLIER, true);
			long secondAmount = 3L * Amounts.MULTIPLIER;

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, buildIncomingAssetReaderAT(), 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// First AT execution sees no incoming transfer and stops for the next block.
			BlockUtils.mintBlock(repository);

			sendMultiPayment(repository, deployer, List.of(
					new PaymentData(atAddress, configuredAssetId, PAYOUT_AMOUNT),
					new PaymentData(atAddress, configuredAssetId, secondAmount)));
			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(PAYOUT_AMOUNT + secondAmount, extractLong(atStateData, 0));
			assertEquals(configuredAssetId, extractLong(atStateData, 1));
		}
	}

	@Test
	public void testMultiPaymentOnlyCountsEntriesForAt() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "bob");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-MULTIPAYMENT-RECIPIENTS", 100L * Amounts.MULTIPLIER, true);
			long otherRecipientAmount = 5L * Amounts.MULTIPLIER;

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, buildIncomingAssetReaderAT(), 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// First AT execution sees no incoming transfer and stops for the next block.
			BlockUtils.mintBlock(repository);

			sendMultiPayment(repository, deployer, List.of(
					new PaymentData(recipient.getAddress(), configuredAssetId, otherRecipientAmount),
					new PaymentData(atAddress, configuredAssetId, PAYOUT_AMOUNT)));
			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(PAYOUT_AMOUNT, extractLong(atStateData, 0));
			assertEquals(configuredAssetId, extractLong(atStateData, 1));
		}
	}

	@Test
	public void testMixedAssetMultiPaymentToAtIsAmbiguous() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-MULTIPAYMENT-MIXED", 100L * Amounts.MULTIPLIER, true);
			long nativeAmount = 3L * Amounts.MULTIPLIER;

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, buildIncomingAssetReaderAT(), 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// First AT execution sees no incoming transfer and stops for the next block.
			BlockUtils.mintBlock(repository);

			sendMultiPayment(repository, deployer, List.of(
					new PaymentData(atAddress, configuredAssetId, PAYOUT_AMOUNT),
					new PaymentData(atAddress, Asset.NATIVE, nativeAmount)));
			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(-1L, extractLong(atStateData, 0));
			assertEquals(-1L, extractLong(atStateData, 1));
		}
	}

	@Test
	public void testMixedAssetMultiPaymentAmountsAreReadableByAsset() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-MULTIPAYMENT-BY-ASSET", 100L * Amounts.MULTIPLIER, true);
			long nativeAmount = 3L * Amounts.MULTIPLIER;

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, buildIncomingAssetSpecificReaderAT(configuredAssetId), 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// First AT execution sees no incoming transfer and stops for the next block.
			BlockUtils.mintBlock(repository);

			sendMultiPayment(repository, deployer, List.of(
					new PaymentData(atAddress, configuredAssetId, PAYOUT_AMOUNT),
					new PaymentData(atAddress, Asset.NATIVE, nativeAmount)));
			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(-1L, extractLong(atStateData, 0));
			assertEquals(-1L, extractLong(atStateData, 1));
			assertEquals(PAYOUT_AMOUNT, extractLong(atStateData, 2));
			assertEquals(nativeAmount, extractLong(atStateData, 3));
			assertEquals(2L, extractLong(atStateData, 4));
		}
	}

	@Test
	public void testAssetSpecificMultiPaymentReaderSumsOnlyMatchingAtEntries() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			PrivateKeyAccount recipient = Common.getTestAccount(repository, "bob");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-MULTIPAYMENT-BY-ASSET-SUM", 100L * Amounts.MULTIPLIER, true);
			long secondConfiguredAmount = 3L * Amounts.MULTIPLIER;
			long nativeAmount = 2L * Amounts.MULTIPLIER;
			long otherRecipientAmount = 5L * Amounts.MULTIPLIER;

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, buildIncomingAssetSpecificReaderAT(configuredAssetId), 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// First AT execution sees no incoming transfer and stops for the next block.
			BlockUtils.mintBlock(repository);

			sendMultiPayment(repository, deployer, List.of(
					new PaymentData(recipient.getAddress(), configuredAssetId, otherRecipientAmount),
					new PaymentData(atAddress, configuredAssetId, PAYOUT_AMOUNT),
					new PaymentData(atAddress, Asset.NATIVE, nativeAmount),
					new PaymentData(atAddress, configuredAssetId, secondConfiguredAmount)));
			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(-1L, extractLong(atStateData, 0));
			assertEquals(-1L, extractLong(atStateData, 1));
			assertEquals(PAYOUT_AMOUNT + secondConfiguredAmount, extractLong(atStateData, 2));
			assertEquals(nativeAmount, extractLong(atStateData, 3));
			assertEquals(3L, extractLong(atStateData, 4));
		}
	}

	@Test
	public void testNativeMultiPaymentTopUpIsVisibleToAts() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-MULTIPAYMENT-NATIVE", 100L * Amounts.MULTIPLIER, true);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, buildIncomingAssetReaderAT(), 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// First AT execution sees no incoming transfer and stops for the next block.
			BlockUtils.mintBlock(repository);

			sendMultiPayment(repository, deployer, List.of(new PaymentData(atAddress, Asset.NATIVE, PAYOUT_AMOUNT)));
			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(PAYOUT_AMOUNT, extractLong(atStateData, 0));
			assertEquals(Asset.NATIVE, extractLong(atStateData, 1));
		}
	}

	@Test
	public void testMessagePaymentTransactionsExposeMessageAndPaymentToAts() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-MESSAGE-PAYMENT", 100L * Amounts.MULTIPLIER, true);
			byte[] messageData = "pay-note".getBytes(StandardCharsets.UTF_8);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, buildMessagePaymentReaderAT(), 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// First AT execution sees no incoming message and stops for the next block.
			BlockUtils.mintBlock(repository);

			sendMessage(repository, deployer, atAddress, messageData, PAYOUT_AMOUNT, configuredAssetId);
			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(ATTransactionType.MESSAGE.value, extractLong(atStateData, 0));
			assertEquals(PAYOUT_AMOUNT, extractLong(atStateData, 1));
			assertEquals(configuredAssetId, extractLong(atStateData, 2));
			assertEquals(messageData.length, extractLong(atStateData, 3));
			assertEquals(BitTwiddling.longFromBEBytes(messageData, 0), extractLong(atStateData, 6));
		}
	}

	@Test
	public void testNativeMessagePaymentTopUpIsVisibleToAts() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-MESSAGE-NATIVE", 100L * Amounts.MULTIPLIER, true);
			byte[] messageData = "fee-note".getBytes(StandardCharsets.UTF_8);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, buildMessagePaymentReaderAT(), 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// First AT execution sees no incoming message and stops for the next block.
			BlockUtils.mintBlock(repository);

			sendMessage(repository, deployer, atAddress, messageData, PAYOUT_AMOUNT, Asset.NATIVE);
			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(ATTransactionType.MESSAGE.value, extractLong(atStateData, 0));
			assertEquals(PAYOUT_AMOUNT, extractLong(atStateData, 1));
			assertEquals(Asset.NATIVE, extractLong(atStateData, 2));
			assertEquals(messageData.length, extractLong(atStateData, 3));
			assertEquals(BitTwiddling.longFromBEBytes(messageData, 0), extractLong(atStateData, 6));
		}
	}

	@Test
	public void testMessageWithoutPaymentStillHasNoPaymentAsset() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			PrivateKeyAccount deployer = Common.getTestAccount(repository, "alice");
			long configuredAssetId = AssetUtils.issueAsset(repository, "alice", "AT-MESSAGE-NO-PAYMENT", 100L * Amounts.MULTIPLIER, true);
			byte[] messageData = "msg-only".getBytes(StandardCharsets.UTF_8);

			DeployAtTransaction deployAtTransaction = AtUtils.doDeployAT(repository, deployer, buildMessagePaymentReaderAT(), 0L, configuredAssetId, NATIVE_FEE_RESERVE);
			String atAddress = deployAtTransaction.getATAccount().getAddress();

			// First AT execution sees no incoming message and stops for the next block.
			BlockUtils.mintBlock(repository);

			sendMessage(repository, deployer, atAddress, messageData, 0L, null);
			BlockUtils.mintBlock(repository);

			ATStateData atStateData = repository.getATRepository().getLatestATState(atAddress);

			assertEquals(ATTransactionType.MESSAGE.value, extractLong(atStateData, 0));
			assertEquals(-1L, extractLong(atStateData, 1));
			assertEquals(-1L, extractLong(atStateData, 2));
			assertEquals(messageData.length, extractLong(atStateData, 3));
			assertEquals(BitTwiddling.longFromBEBytes(messageData, 0), extractLong(atStateData, 6));
		}
	}

	private static byte[] buildCurrentBalanceAT() {
		int addrCounter = 0;
		final int addrBalance = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);

		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CURRENT_BALANCE, addrBalance));
				codeByteBuffer.put(OpCode.STP_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile AT?", e);
			}
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	private static byte[] buildAssetBalanceReaderAT(long assetId) {
		int addrCounter = 0;
		final int addrBalance = addrCounter++;
		final int addrAssetId = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.putLong(addrAssetId * MachineState.VALUE_SIZE, assetId);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);

		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT.compile(ChainFunctionCode.GET_ASSET_BALANCE.value, addrBalance, addrAssetId));
				codeByteBuffer.put(OpCode.STP_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile AT?", e);
			}
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	private static byte[] buildPayAssetToAddressAT(String recipient, long assetId, long amount) {
		int addrCounter = 0;
		final int addrResult = addrCounter++;
		final int addrAssetId = addrCounter++;
		final int addrAmount = addrCounter++;
		final int addrLastTxTimestamp = addrCounter++;
		final int addrNoTransaction = addrCounter++;
		final int addrRecipientBytes = addrCounter;
		addrCounter += 4;
		final int addrRecipientBytesPointer = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.putLong(addrResult * MachineState.VALUE_SIZE, 0L);
		dataByteBuffer.putLong(addrAssetId * MachineState.VALUE_SIZE, assetId);
		dataByteBuffer.putLong(addrAmount * MachineState.VALUE_SIZE, amount);
		dataByteBuffer.position(addrRecipientBytes * MachineState.VALUE_SIZE);
		dataByteBuffer.put(Bytes.ensureCapacity(Base58.decode(recipient), 32, 0));
		dataByteBuffer.putLong(addrRecipientBytesPointer * MachineState.VALUE_SIZE, addrRecipientBytes);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);
		Integer labelPayAsset = null;

		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrNoTransaction));
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrNoTransaction, OpCode.calcOffset(codeByteBuffer, labelPayAsset)));
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				labelPayAsset = codeByteBuffer.position();

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrRecipientBytesPointer));
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(ChainFunctionCode.PAY_ASSET_AMOUNT_TO_B.value, addrResult, addrAssetId, addrAmount));
				codeByteBuffer.put(OpCode.FIN_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile AT?", e);
			}
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	private static byte[] buildIncomingAssetReaderAT() {
		int addrCounter = 0;
		final int addrAmount = addrCounter++;
		final int addrAssetId = addrCounter++;
		final int addrLastTxTimestamp = addrCounter++;
		final int addrNoTransaction = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);
		Integer labelReadTransaction = null;

		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrNoTransaction));
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrNoTransaction, OpCode.calcOffset(codeByteBuffer, labelReadTransaction)));
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				labelReadTransaction = codeByteBuffer.position();

				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_AMOUNT_FROM_TX_IN_A, addrAmount));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_ASSET_ID_FROM_TX_IN_A.value, addrAssetId));
				codeByteBuffer.put(OpCode.FIN_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile AT?", e);
			}
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	private static byte[] buildIncomingAssetSpecificReaderAT(long configuredAssetId) {
		int addrCounter = 0;
		final int addrLegacyAmount = addrCounter++;
		final int addrLegacyAssetId = addrCounter++;
		final int addrConfiguredAmount = addrCounter++;
		final int addrNativeAmount = addrCounter++;
		final int addrPaymentCount = addrCounter++;
		final int addrConfiguredAssetId = addrCounter++;
		final int addrNativeAssetId = addrCounter++;
		final int addrLastTxTimestamp = addrCounter++;
		final int addrNoTransaction = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.putLong(addrConfiguredAssetId * MachineState.VALUE_SIZE, configuredAssetId);
		dataByteBuffer.putLong(addrNativeAssetId * MachineState.VALUE_SIZE, Asset.NATIVE);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(768);
		Integer labelReadTransaction = null;

		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrNoTransaction));
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrNoTransaction, OpCode.calcOffset(codeByteBuffer, labelReadTransaction)));
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				labelReadTransaction = codeByteBuffer.position();

				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_AMOUNT_FROM_TX_IN_A, addrLegacyAmount));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_ASSET_ID_FROM_TX_IN_A.value, addrLegacyAssetId));
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT.compile(ChainFunctionCode.GET_AMOUNT_FROM_TX_IN_A_FOR_ASSET.value,
						addrConfiguredAmount, addrConfiguredAssetId));
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT.compile(ChainFunctionCode.GET_AMOUNT_FROM_TX_IN_A_FOR_ASSET.value,
						addrNativeAmount, addrNativeAssetId));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_PAYMENT_COUNT_FROM_TX_IN_A.value, addrPaymentCount));
				codeByteBuffer.put(OpCode.FIN_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile AT?", e);
			}
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	private static byte[] buildSenderBoundAssetRefundAT(long configuredAssetId, long expectedAmount) {
		int addrCounter = 0;
		final int addrAcceptedAssetId = addrCounter++;
		final int addrAcceptedAmount = addrCounter++;
		final int addrRefundedAmount = addrCounter++;
		final int addrExpectedAssetId = addrCounter++;
		final int addrExpectedAmount = addrCounter++;
		final int addrLastTxTimestamp = addrCounter++;
		final int addrNoTransaction = addrCounter++;
		final int addrIncomingAmount = addrCounter++;
		final int addrTakerAddress = addrCounter;
		addrCounter += 4;
		final int addrTakerAddressPointer = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.putLong(addrExpectedAssetId * MachineState.VALUE_SIZE, configuredAssetId);
		dataByteBuffer.putLong(addrExpectedAmount * MachineState.VALUE_SIZE, expectedAmount);
		dataByteBuffer.putLong(addrTakerAddressPointer * MachineState.VALUE_SIZE, addrTakerAddress);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(768);
		Integer labelFindPayment = null;
		Integer labelCheckPayment = null;

		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				labelFindPayment = codeByteBuffer.position();

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrNoTransaction));
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrNoTransaction, OpCode.calcOffset(codeByteBuffer, labelCheckPayment)));
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				labelCheckPayment = codeByteBuffer.position();

				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TIMESTAMP_FROM_TX_IN_A, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT.compile(ChainFunctionCode.GET_AMOUNT_FROM_TX_IN_A_FOR_ASSET.value,
						addrIncomingAmount, addrExpectedAssetId));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrIncomingAmount, addrExpectedAmount, OpCode.calcOffset(codeByteBuffer, labelFindPayment)));

				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_ASSET_ID_FROM_TX_IN_A.value, addrAcceptedAssetId));
				codeByteBuffer.put(OpCode.SET_DAT.compile(addrAcceptedAmount, addrIncomingAmount));
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrTakerAddressPointer));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrTakerAddressPointer));
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(ChainFunctionCode.PAY_ASSET_AMOUNT_TO_B.value,
						addrRefundedAmount, addrExpectedAssetId, addrExpectedAmount));
				codeByteBuffer.put(OpCode.FIN_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile AT?", e);
			}
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	private static byte[] buildMessagePaymentReaderAT() {
		int addrCounter = 0;
		final int addrType = addrCounter++;
		final int addrAmount = addrCounter++;
		final int addrAssetId = addrCounter++;
		final int addrMessageLength = addrCounter++;
		final int addrNoTransaction = addrCounter++;
		final int addrLastTxTimestamp = addrCounter++;
		final int addrMessageBytes = addrCounter;
		addrCounter += 4;
		final int addrMessageBytesPointer = addrCounter++;
		final int addrOffset = addrCounter++;

		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);
		dataByteBuffer.putLong(addrMessageBytesPointer * MachineState.VALUE_SIZE, addrMessageBytes);

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(512);
		Integer labelReadTransaction = null;

		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxTimestamp));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrNoTransaction));
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrNoTransaction, OpCode.calcOffset(codeByteBuffer, labelReadTransaction)));
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				labelReadTransaction = codeByteBuffer.position();

				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TYPE_FROM_TX_IN_A, addrType));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_AMOUNT_FROM_TX_IN_A, addrAmount));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_ASSET_ID_FROM_TX_IN_A.value, addrAssetId));
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(ChainFunctionCode.GET_MESSAGE_LENGTH_FROM_TX_IN_A.value, addrMessageLength));
				codeByteBuffer.put(OpCode.SET_VAL.compile(addrOffset, 0L));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(ChainFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, addrOffset));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrMessageBytesPointer));
				codeByteBuffer.put(OpCode.FIN_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile AT?", e);
			}
		}

		return toCreationBytes(codeByteBuffer, dataByteBuffer);
	}

	private static byte[] toCreationBytes(ByteBuffer codeByteBuffer, ByteBuffer dataByteBuffer) {
		codeByteBuffer.flip();

		byte[] codeBytes = new byte[codeByteBuffer.limit()];
		codeByteBuffer.get(codeBytes);

		final short ciyamAtVersion = 2;
		final short numCallStackPages = 0;
		final short numUserStackPages = 0;
		final long minActivationAmount = 0L;

		return MachineState.toCreationBytes(ciyamAtVersion, codeBytes, dataByteBuffer.array(), numCallStackPages, numUserStackPages, minActivationAmount);
	}

	private static long extractLong(ATStateData atStateData, int address) {
		byte[] dataBytes = MachineState.extractDataBytes(atStateData.getStateData());
		return BitTwiddling.longFromBEBytes(dataBytes, address * MachineState.VALUE_SIZE);
	}

	private static void transferAsset(Repository repository, PrivateKeyAccount sender, String recipient, long assetId, long amount) throws DataException {
		assertEquals(ValidationResult.OK, importTransferAsset(repository, sender, recipient, assetId, amount));
		BlockUtils.mintBlock(repository);
	}

	private static long issueAsset(Repository repository, PrivateKeyAccount issuer, String assetName, long quantity, boolean isDivisible, boolean isUnspendable) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, issuer.getPublicKey(), AssetUtils.fee, null);
		TransactionData transactionData = new IssueAssetTransactionData(baseTransactionData, assetName, "desc", quantity, isDivisible, "{}", isUnspendable);

		TransactionUtils.signAndMint(repository, transactionData, issuer);

		return repository.getAssetRepository().fromAssetName(assetName).getAssetId();
	}

	private static void sendMessage(Repository repository, PrivateKeyAccount sender, String recipient, byte[] data, long amount, Long assetId) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);
		Long fee = null;
		int version = Transaction.getVersionByTimestamp(timestamp);
		int nonce = 0;

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, sender.getPublicKey(), fee, null);
		TransactionData transactionData = new MessageTransactionData(baseTransactionData, version, nonce, recipient, amount, assetId, data, false, false);

		MessageTransaction messageTransaction = new MessageTransaction(repository, transactionData);
		transactionData.setFee(messageTransaction.calcRecommendedFee());

		TransactionUtils.signAndMint(repository, transactionData, sender);
	}

	private static void sendMultiPayment(Repository repository, PrivateKeyAccount sender, List<PaymentData> payments) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);
		Long fee = null;

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, sender.getPublicKey(), fee, null);
		TransactionData transactionData = new MultiPaymentTransactionData(baseTransactionData, payments);

		MultiPaymentTransaction multiPaymentTransaction = new MultiPaymentTransaction(repository, transactionData);
		transactionData.setFee(multiPaymentTransaction.calcRecommendedFee());

		TransactionUtils.signAndMint(repository, transactionData, sender);
	}

	private static ValidationResult importTransferAsset(Repository repository, PrivateKeyAccount sender, String recipient, long assetId, long amount) throws DataException {
		long timestamp = TransactionUtils.nextTimestamp(repository);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, sender.getPublicKey(), AssetUtils.fee, null);
		TransactionData transactionData = new TransferAssetTransactionData(baseTransactionData, recipient, amount, assetId);

		return TransactionUtils.signAndImport(repository, transactionData, sender);
	}

}
